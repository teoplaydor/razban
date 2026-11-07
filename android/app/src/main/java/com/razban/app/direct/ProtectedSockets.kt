package com.razban.app.direct

import android.net.Network
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Builds signal/data sockets: bindSocket + protect MUST succeed before connect.
 * Loopback targets skip bindSocket so a physical NIC bind does not break local
 * tests; production peers are never loopback. Process-wide bindProcessToNetwork
 * is intentionally not used.
 */
class ProtectedSockets(
    private val networkProvider: () -> Network?,
    private val protectSocket: (Socket) -> Boolean,
) {
    val events = ArrayList<String>()

    fun open(
        peer: InetSocketAddress,
        localBind: SocketRef? = null,
        timeoutMs: Int = DirectTcpRuntime.CONNECT_TIMEOUT_MS,
        connect: Boolean = true,
    ): Socket {
        val socket = Socket()
        socket.reuseAddress = false
        socket.soTimeout = timeoutMs
        DirectTcpRuntime.applyNormalTtl(socket)
        try {
            if (localBind != null && (localBind.port != 0 || (localBind.host != "0.0.0.0" && localBind.host != "::"))) {
                socket.bind(InetSocketAddress(localBind.host, localBind.port))
                events.add("bind")
            }
            val loopback = peer.address.isLoopbackAddress
            if (!loopback) {
                val network = networkProvider() ?: throw IOException("no_underlying_network")
                events.add("network")
                network.bindSocket(socket)
                events.add("bindSocket")
                if (!protectSocket(socket)) throw IOException("protect_failed")
                events.add("protect")
            } else {
                events.add("loopback")
                protectSocket(socket)
                events.add("protect")
            }
            if (connect) {
                socket.connect(peer, timeoutMs)
                events.add("connect")
            }
            return socket
        } catch (t: Throwable) {
            try { socket.close() } catch (_: Exception) {}
            throw t
        }
    }
}
