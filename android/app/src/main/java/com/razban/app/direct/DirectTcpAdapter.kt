package com.razban.app.direct

import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifecycle-owned loopback adapter: stock Xray (TLS+MUX) dials 127.0.0.1:adapterPort;
 * each accepted stream negotiates a new direct TCP channel and byte-bridges it.
 * No custom multiplexer and no automatic fallback to the relay profile.
 */
class DirectTcpAdapter(
    private val profile: DirectProfile,
    networkProvider: () -> android.net.Network?,
    protectSocket: (Socket) -> Boolean,
) {
    private val coordinator = DirectTcpCoordinator(profile, networkProvider, protectSocket)
    private val sockets = ProtectedSockets(networkProvider, protectSocket)
    private val listener = BoundedLocalListener(profile.adapterPort)
    private val stop = AtomicBoolean(false)
    private val worker = AtomicReference<Thread>()
    private val current = AtomicReference<Socket>()
    val events = CopyOnWriteArrayList<String>()
    @Volatile var lastError: String? = null
        private set

    val listenPort: Int get() = listener.localPort

    fun start() {
        listener.startAccept()
        val t = Thread { workerLoop() }.apply { isDaemon = true; name = "razban-direct-adapter" }
        worker.set(t)
        t.start()
        events.add("started")
        Log.d(TAG, "adapter listening")
    }

    fun stop() {
        stop.set(true)
        failClosed("stopped")
        listener.close()
        try { worker.get()?.join(3000) } catch (_: InterruptedException) {}
        events.add("stopped")
    }

    fun failClosed(reason: String) {
        lastError = reason
        val s = current.getAndSet(null)
        if (s != null) {
            try { s.shutdownInput() } catch (_: Exception) {}
            try { s.shutdownOutput() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
            events.add("fail_closed")
        }
    }

    fun closeCurrent(forced: Boolean = false) {
        if (forced) events.add("forced_disconnect") else events.add("shutdown_current")
        val s = current.getAndSet(null)
        if (s != null) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun workerLoop() {
        while (!stop.get()) {
            val local = try {
                listener.pending.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue
            var session: JSONObject? = null
            var direct: Socket? = null
            try {
                var joined = coordinator.call("join")
                val waitUntil = System.nanoTime() + 3_000_000_000L
                while (joined.optString("state") == "busy") {
                    if (System.nanoTime() > waitUntil) throw java.util.concurrent.TimeoutException("previous peer cleanup deadline")
                    events.add("waiting_previous_peer_cleanup")
                    Thread.sleep(150)
                    joined = coordinator.call("join")
                }
                events.add("joined")
                val remotePlan = FixedPlan.from(joined.getJSONObject("plan"))
                if (remotePlan != profile.plan) throw IllegalStateException("fixed endpoint plan mismatch")
                session = coordinator.armed(joined)
                events.add("armed")
                val epoch = session.getDouble("epoch")
                DirectTcpRuntime.awaitEpoch(coordinator.clock, epoch)
                val peer = profile.plan.devicePeer ?: throw IllegalStateException("device_peer")
                direct = sockets.open(
                    InetSocketAddress(peer.host, peer.port),
                    localBind = profile.plan.deviceBind,
                    timeoutMs = DirectTcpRuntime.CONNECT_TIMEOUT_MS,
                    connect = true,
                )
                current.set(direct)
                events.add("connected")
                DirectTcpRuntime.relayUntilClosed(local, direct, stop)
                events.add("stream_finished")
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName
                events.add("stream_error")
                Log.d(TAG, "stream_error ${t.javaClass.simpleName}")
            } finally {
                current.set(null)
                try { direct?.close() } catch (_: Exception) {}
                try { local.close() } catch (_: Exception) {}
                if (session != null && session.has("session")) {
                    try { coordinator.call("done", session) } catch (_: Exception) {
                        events.add("done_error")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "razban-direct"
    }
}
