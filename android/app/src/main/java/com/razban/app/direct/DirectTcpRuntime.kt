package com.razban.app.direct

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object DirectTcpRuntime {
    const val NORMAL_TTL = 128
    const val CONNECT_TIMEOUT_MS = 8000

    fun applyNormalTtl(socket: Socket) {
        try {
            val os = Class.forName("android.system.Os")
            val constants = Class.forName("android.system.OsConstants")
            val pfdCls = Class.forName("android.os.ParcelFileDescriptor")
            val pfd = pfdCls.getMethod("fromSocket", Socket::class.java).invoke(null, socket)
            val fd = pfdCls.getMethod("getFileDescriptor").invoke(pfd)
            val proto = constants.getField("IPPROTO_IP").getInt(null)
            val ttlOpt = constants.getField("IP_TTL").getInt(null)
            os.getMethod(
                "setsockoptInt",
                Class.forName("java.io.FileDescriptor"),
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(null, fd, proto, ttlOpt, NORMAL_TTL)
        } catch (_: Throwable) {
            // Host JVM unit tests may not expose IP_TTL; production Android path uses Os.
        }
    }

    fun readTtl(socket: Socket): Int? {
        return try {
            val os = Class.forName("android.system.Os")
            val constants = Class.forName("android.system.OsConstants")
            val pfdCls = Class.forName("android.os.ParcelFileDescriptor")
            val pfd = pfdCls.getMethod("fromSocket", Socket::class.java).invoke(null, socket)
            val fd = pfdCls.getMethod("getFileDescriptor").invoke(pfd)
            val proto = constants.getField("IPPROTO_IP").getInt(null)
            val ttlOpt = constants.getField("IP_TTL").getInt(null)
            os.getMethod(
                "getsockoptInt",
                Class.forName("java.io.FileDescriptor"),
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(null, fd, proto, ttlOpt) as Int
        } catch (_: Throwable) { null }
    }

    /** Public/data sockets: exact bind, normal TTL, no SO_REUSEADDR. */
    fun openActiveChannelSocket(bind: SocketRef): Socket {
        val s = Socket()
        s.reuseAddress = false
        s.soTimeout = CONNECT_TIMEOUT_MS
        applyNormalTtl(s)
        try {
            s.bind(InetSocketAddress(bind.host, bind.port))
        } catch (t: Throwable) {
            try { s.close() } catch (_: Exception) {}
            throw t
        }
        return s
    }

    fun awaitEpoch(clock: ClockEstimate, epoch: Double) {
        val delay = DirectTcpClock.startDelay(epoch, SignalCodec.nowSeconds(), clock.offset)
        val ms = (delay * 1000).toLong().coerceAtLeast(0)
        if (ms > 0) Thread.sleep(ms)
    }

    data class RelayStats(val bytesLeftToRight: Int, val bytesRightToLeft: Int)

    fun relayUntilClosed(left: Socket, right: Socket, stop: AtomicBoolean): RelayStats {
        val a = AtomicInteger()
        val b = AtomicInteger()
        val t = Thread {
            try { copy(left, right, a, stop) } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        try { copy(right, left, b, stop) } catch (_: Exception) {}
        try { t.join(3000) } catch (_: InterruptedException) {}
        return RelayStats(a.get(), b.get())
    }

    private fun copy(from: Socket, to: Socket, count: AtomicInteger, stop: AtomicBoolean) {
        val buf = ByteArray(16 * 1024)
        val input = from.getInputStream()
        val output = to.getOutputStream()
        while (!stop.get()) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
            count.addAndGet(n)
        }
        try { to.shutdownOutput() } catch (_: Exception) {}
    }
}

/** One accepted local stream plus one queued stream; extras are rejected. */
class BoundedLocalListener(port: Int = 0) {
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", port))
        soTimeout = 200
    }
    val localPort: Int get() = listener.localPort
    val pending = ArrayBlockingQueue<Socket>(1)
    val events = CopyOnWriteArrayList<String>()
    private val threads = ArrayList<Thread>()
    private val stop = AtomicBoolean(false)

    fun startAccept() {
        val t = Thread {
            while (!stop.get()) {
                val client = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                client.soTimeout = 3000
                if (!pending.offer(client)) {
                    try { client.close() } catch (_: Exception) {}
                    events.add("queue_full_rejected")
                }
            }
        }
        t.isDaemon = true
        threads.add(t)
        t.start()
    }

    fun close() {
        stop.set(true)
        try { listener.close() } catch (_: Exception) {}
        while (true) {
            val s = pending.poll() ?: break
            try { s.close() } catch (_: Exception) {}
        }
        for (t in threads) try { t.join(3000) } catch (_: InterruptedException) {}
    }

    fun allStopped(): Boolean = threads.all { !it.isAlive }
}
