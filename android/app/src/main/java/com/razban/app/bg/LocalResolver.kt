package com.razban.app.bg

import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * The DNS bridge the core uses for "local" resolution. CRITICAL for avoiding
 * the desktop ClassifyingDnsServer's loop problem in a much simpler way:
 * resolution runs on the *underlying* (protected) network via
 * [Network.getAllByName], so a query never re-enters the TUN. There is no
 * 127.0.0.1:5354 listener, no hijack-dns anti-loop rule — the platform gives
 * us the underlying network object directly.
 *
 * raw() == false → the core calls [lookup] with an A/AAAA question and expects
 * resolved IPs back; we don't have to parse/serialise wire-format DNS.
 */
object LocalResolver : LocalDNSTransport {

    override fun raw(): Boolean = false

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val host = domain.trimEnd('.')
        try {
            val net = DefaultNetworkMonitor.currentNetwork
            val all = if (net != null) net.getAllByName(host)
                      else java.net.InetAddress.getAllByName(host)
            val filtered = all.asSequence()
                .filter {
                    when (network) {
                        "ip4" -> it is Inet4Address
                        "ip6" -> it is Inet6Address
                        else -> true
                    }
                }
                .mapNotNull { it.hostAddress }
                .toList()
            if (filtered.isEmpty()) ctx.errorCode(3 /* NXDOMAIN */)
            else ctx.success(filtered.joinToString("\n"))
        } catch (_: Exception) {
            ctx.errorCode(2 /* SERVFAIL */)
        }
    }

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        // Only reached when raw() == true, which we never return.
        ctx.errorCode(4 /* NotImplemented */)
    }
}
