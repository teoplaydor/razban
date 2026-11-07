package com.razban.app.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket

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
            {"razbanProfile":"direct-tcp-experimental","signal":{"url":"https://192.0.2.1:9444/v1/session","caPem":"-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----","hmacKeyHex":"${"ab"*32}"},"plan":{"mode":"active","device":{"peer":["192.0.2.2",9443]}},"xray":{},"evil":"-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----"}
        """.trimIndent()
        try {
            DirectProfile.parse(doc)
            throw AssertionError("expected private key rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("private_key_forbidden", e.message)
        }
    }
}
