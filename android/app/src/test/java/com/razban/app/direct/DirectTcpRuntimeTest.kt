package com.razban.app.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DirectTcpRuntimeTest {
    @Test
    fun activeSocketIsBoundAndNoReuse() {
        val s = DirectTcpRuntime.openActiveChannelSocket(SocketRef("127.0.0.1", 0))
        try {
            assertEquals("127.0.0.1", (s.localSocketAddress as InetSocketAddress).address.hostAddress)
            assertEquals(false, s.reuseAddress)
            val ttl = DirectTcpRuntime.readTtl(s)
            if (ttl != null) assertEquals(DirectTcpRuntime.NORMAL_TTL, ttl)
        } finally {
            s.close()
        }
    }

    @Test
    fun busyBindIsLocalError() {
        val holder = Socket()
        holder.bind(InetSocketAddress("127.0.0.1", 0))
        try {
            val port = (holder.localSocketAddress as InetSocketAddress).port
            try {
                DirectTcpRuntime.openActiveChannelSocket(SocketRef("127.0.0.1", port))
                throw AssertionError("expected bind failure")
            } catch (e: BindException) {
                assertTrue(e.message?.isNotEmpty() == true)
            } catch (e: java.net.SocketException) {
                assertTrue(e.message?.contains("already") == true || e is BindException)
            }
        } finally {
            holder.close()
        }
    }

    @Test
    fun lateEpochRejectedBeforeConnect() {
        try {
            DirectTcpRuntime.awaitEpoch(ClockEstimate(), SignalCodec.nowSeconds() - 1)
            throw AssertionError("expected timeout")
        } catch (e: java.util.concurrent.TimeoutException) {
            assertEquals("start window", e.message)
        }
    }

    @Test
    fun boundedPendingQueueAndCleanup() {
        val listener = BoundedLocalListener(0)
        val peers = ArrayList<Socket>()
        try {
            listener.startAccept()
            val port = listener.localPort
            repeat(3) {
                peers.add(Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1000) })
                Thread.sleep(40)
            }
            assertEquals(1, listener.pending.size)
            assertTrue(listener.events.contains("queue_full_rejected"))
        } finally {
            peers.forEach { try { it.close() } catch (_: Exception) {} }
            listener.close()
        }
        assertTrue(listener.allStopped())
    }

    @Test
    fun parseRejectsPrivateKeyAndFakeKind() {
        try {
            DirectProfile.parse("""{"razbanProfile":"not-it"}""")
            throw AssertionError("expected kind rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("direct_profile_kind", e.message)
        }
        val doc = """
            {"razbanProfile":"direct-tcp-experimental","signal":{"url":"https://192.0.2.1:9444/v1/session","caPem":"-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----","hmacKeyHex":"${"ab".repeat(32)}"},"plan":{"mode":"active","device":{"peer":["192.0.2.2",9443]}},"xray":{},"evil":"-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----"}
        """.trimIndent()
        try {
            DirectProfile.parse(doc)
            throw AssertionError("expected private key rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("private_key_forbidden", e.message)
        }
    }

    @Test(timeout = 45000)
    fun relaySurvivesIdleOver20sThenBidirectionalPayload() {
        val pipes = relayPipes()
        val appA = pipes[0]
        val relayLeft = pipes[1]
        val relayRight = pipes[2]
        val appB = pipes[3]
        val stop = AtomicBoolean(false)
        val error = arrayOfNulls<Exception>(1)
        val worker = Thread {
            try {
                DirectTcpRuntime.relayUntilClosed(relayLeft, relayRight, stop)
            } catch (e: Exception) {
                error[0] = e
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            Thread.sleep(21_000)
            val east = "east-after-idle"
            val west = "west-after-idle"
            appA.getOutputStream().write(east.toByteArray())
            appA.getOutputStream().flush()
            assertEquals(east, readExact(appB, east.length, 5_000))
            appB.getOutputStream().write(west.toByteArray())
            appB.getOutputStream().flush()
            assertEquals(west, readExact(appA, west.length, 5_000))
        } finally {
            stop.set(true)
            pipes.forEach { try { it.close() } catch (_: Exception) {} }
            worker.join(2000)
        }
        assertTrue(error[0] == null)
    }

    @Test(timeout = 5000)
    fun pumpReadTimeoutIsErrorNotEof() {
        val pipes = relayPipes()
        val appA = pipes[0]
        val relayLeft = pipes[1]
        val relayRight = pipes[2]
        val appB = pipes[3]
        try {
            relayLeft.soTimeout = 150
            try {
                DirectTcpRuntime.pump(relayLeft, relayRight, AtomicInteger(), AtomicBoolean())
                throw AssertionError("expected SocketTimeoutException, got normal return")
            } catch (_: SocketTimeoutException) {
                // expected: must not be swallowed as EOF
            }
        } finally {
            pipes.forEach { try { it.close() } catch (_: Exception) {} }
        }
        // silence unused
        try { appA.close(); appB.close() } catch (_: Exception) {}
    }

    private fun relayPipes(): Array<Socket> {
        val s1 = ServerSocket(0)
        val s2 = ServerSocket(0)
        lateinit var relayLeft: Socket
        lateinit var relayRight: Socket
        val t1 = Thread { relayLeft = s1.accept() }
        val t2 = Thread { relayRight = s2.accept() }
        t1.start()
        t2.start()
        val appA = Socket().apply { connect(InetSocketAddress("127.0.0.1", s1.localPort), 1000) }
        val appB = Socket().apply { connect(InetSocketAddress("127.0.0.1", s2.localPort), 1000) }
        t1.join(1000)
        t2.join(1000)
        s1.close()
        s2.close()
        return arrayOf(appA, relayLeft, relayRight, appB)
    }

    private fun readExact(socket: Socket, n: Int, timeoutMs: Long): String {
        socket.soTimeout = timeoutMs.toInt()
        val buf = ByteArray(n)
        var off = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (off < n && System.currentTimeMillis() < deadline) {
            val got = socket.getInputStream().read(buf, off, n - off)
            if (got < 0) break
            off += got
        }
        return String(buf, 0, off)
    }
}
