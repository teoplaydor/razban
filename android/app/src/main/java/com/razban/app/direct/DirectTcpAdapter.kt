package com.razban.app.direct

import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
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
    private val live = CopyOnWriteArrayList<Socket>()
    private val inflight = Semaphore(4)
    val events = CopyOnWriteArrayList<String>()
    @Volatile var lastError: String? = null
        private set
    var onDiagnostic: ((stage: String, error: String?, durationMs: Long?) -> Unit)? = null
    var onChannelState: ((DirectChannelState) -> Unit)? = null

    val listenPort: Int get() = listener.localPort

    fun start() {
        listener.startAccept()
        val t = Thread { workerLoop() }.apply { isDaemon = true; name = "razban-direct-adapter" }
        worker.set(t)
        t.start()
        events.add("started")
        diag("direct_start")
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
        current.set(null)
        val closing = live.toList()
        live.clear()
        var any = false
        for (s in closing) {
            any = true
            try { s.shutdownInput() } catch (_: Exception) {}
            try { s.shutdownOutput() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
        if (any) {
            events.add("fail_closed")
            if (reason != "stopped") {
                val code = if (reason == "no_underlying_network") "no_underlying_network" else "unknown"
                diag("direct_fail_closed", code)
            }
        }
    }

    fun closeCurrent(forced: Boolean = false) {
        if (forced) events.add("forced_disconnect") else events.add("shutdown_current")
        current.set(null)
        val closing = live.toList()
        live.clear()
        for (s in closing) {
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
            if (!inflight.tryAcquire()) {
                if (!listener.pending.offer(local)) {
                    try { local.close() } catch (_: Exception) {}
                    events.add("queue_full_rejected")
                }
                continue
            }
            Thread {
                try {
                    handleLocal(local)
                } finally {
                    inflight.release()
                }
            }.apply { isDaemon = true; name = "razban-direct-stream" }.start()
        }
    }

    private fun handleLocal(local: Socket) {
            if (events.contains("data_ok")) {
                relayToKnownPeer(local)
                return
            }
            var session: JSONObject? = null
            var direct: Socket? = null
            val streamStarted = System.currentTimeMillis()
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
                diag("direct_joined")
                channel(DirectChannelState.Signaling)
                val remotePlan = FixedPlan.from(joined.getJSONObject("plan"))
                if (remotePlan != profile.plan) throw IllegalStateException("fixed endpoint plan mismatch")
                session = coordinator.armed(joined)
                events.add("armed")
                diag("direct_armed")
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
                live.add(direct)
                events.add("connected")
                diag("direct_connected")
                channel(DirectChannelState.TcpConnected)
                var probed = false
                DirectTcpRuntime.relayUntilClosed(local, direct, stop) { up, down ->
                    if (!probed && up > 0 && down > 0) {
                        probed = true
                        events.add("data_ok")
                        diag("direct_data_ok")
                        channel(DirectChannelState.DataWorking)
                    }
                }
                events.add("stream_finished")
                diag("direct_stream_finished", durationMs = System.currentTimeMillis() - streamStarted)
                if (!stop.get() && live.none { it !== direct }) channel(DirectChannelState.Error)
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName
                events.add("stream_error")
                diag("direct_stream_error", mapError(t), System.currentTimeMillis() - streamStarted)
                if (!stop.get() && live.none { it !== direct }) channel(DirectChannelState.Error)
                Log.d(TAG, "stream_error ${t.javaClass.simpleName}")
            } finally {
                if (direct != null) live.remove(direct)
                current.compareAndSet(direct, null)
                try { direct?.close() } catch (_: Exception) {}
                try { local.close() } catch (_: Exception) {}
                if (session != null && session.has("session")) {
                    try { coordinator.call("done", session) } catch (_: Exception) {
                        events.add("done_error")
                    }
                }
            }
    }

    private fun relayToKnownPeer(local: Socket) {
        val streamStarted = System.currentTimeMillis()
        var direct: Socket? = null
        try {
            val peer = profile.plan.devicePeer ?: throw IllegalStateException("device_peer")
            direct = sockets.open(
                InetSocketAddress(peer.host, peer.port),
                localBind = profile.plan.deviceBind,
                timeoutMs = DirectTcpRuntime.CONNECT_TIMEOUT_MS,
                connect = true,
            )
            current.set(direct)
            live.add(direct)
            events.add("connected")
            diag("direct_connected")
            var probed = false
            DirectTcpRuntime.relayUntilClosed(local, direct, stop) { up, down ->
                if (!probed && up > 0 && down > 0) {
                    probed = true
                    events.add("data_ok")
                    diag("direct_data_ok")
                    channel(DirectChannelState.DataWorking)
                }
            }
            events.add("stream_finished")
            diag("direct_stream_finished", durationMs = System.currentTimeMillis() - streamStarted)
            if (!stop.get() && live.none { it !== direct }) channel(DirectChannelState.Error)
        } catch (t: Throwable) {
            lastError = t.javaClass.simpleName
            events.add("stream_error")
            diag("direct_stream_error", mapError(t), System.currentTimeMillis() - streamStarted)
            if (!stop.get() && live.none { it !== direct }) channel(DirectChannelState.Error)
            Log.d(TAG, "stream_error ${t.javaClass.simpleName}")
        } finally {
            if (direct != null) live.remove(direct)
            current.compareAndSet(direct, null)
            try { direct?.close() } catch (_: Exception) {}
            try { local.close() } catch (_: Exception) {}
        }
    }

    private fun diag(stage: String, error: String? = null, durationMs: Long? = null) {
        try { onDiagnostic?.invoke(stage, error, durationMs) } catch (_: Exception) {}
    }

    private fun channel(state: DirectChannelState) {
        try { onChannelState?.invoke(state) } catch (_: Exception) {}
    }

    private fun mapError(t: Throwable): String = when {
        t is java.net.SocketTimeoutException -> "timeout"
        t is java.util.concurrent.TimeoutException -> "timeout"
        t.message?.contains("plan mismatch") == true -> "plan_mismatch"
        t.message?.contains("protect") == true -> "protect_failed"
        t is java.io.IOException -> "io_error"
        else -> "unknown"
    }

    companion object {
        private const val TAG = "razban-direct"
    }
}
