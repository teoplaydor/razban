package com.razban.app.bg

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import java.util.Collections

/**
 * Feeds the sing-box core the current default physical network — the Android
 * analog of the desktop `default_interface` pin + DetectPhysicalDefaultInterface
 * PowerShell pass. Here the OS hands us the default network via a
 * ConnectivityManager callback, so there is no heuristic to get wrong.
 */
object DefaultNetworkMonitor {

    @Volatile
    var currentNetwork: Network? = null
        private set

    /** Called when the underlying physical network changes, so the VpnService
     *  can setUnderlyingNetworks() — required for protected sockets to egress. */
    var onNetwork: ((Network?) -> Unit)? = null

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var listener: InterfaceUpdateListener? = null

    fun start(context: Context, l: InterfaceUpdateListener) {
        listener = l
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                onNetwork?.invoke(network)
                update(context, network)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                currentNetwork = network
                onNetwork?.invoke(network)
                update(context, network)
            }
            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    onNetwork?.invoke(null)
                    listener?.updateDefaultInterface("", -1, false, false)
                }
            }
        }
        callback = cb
        // CRITICAL: track the underlying PHYSICAL network, NOT the VPN. Once
        // our VpnService is up, registerDefaultNetworkCallback would report the
        // VPN itself as "default" → the core would bind its outbound sockets to
        // the tun → every dial loops back into the tunnel and is reset (no
        // egress, DNS fails). Requiring NET_CAPABILITY_NOT_VPN makes the
        // callback follow the real Wi-Fi/cellular network underneath.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try { cm.registerNetworkCallback(request, cb) } catch (_: Exception) {}
    }

    /** Synchronously find + cache the active PHYSICAL (non-VPN) network that has
     *  internet. The registerNetworkCallback's onAvailable is delivered async on a
     *  handler thread, so at the moment the core calls openTun() (synchronously
     *  inside startOrReloadService) currentNetwork is usually STILL NULL — which
     *  left the VpnService's underlying network unset → the core's protected
     *  sockets had nowhere to egress (direct AND proxy → 0 bytes) and LocalResolver
     *  fell back into the tun (DNS failed). Seeding this before the core starts +
     *  re-setting it right after establish() removes that race entirely. */
    fun seed(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun usable(n: Network?): Boolean {
            if (n == null) return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                   c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        val active = cm.activeNetwork
        val pick = if (usable(active)) active
                   else try { cm.allNetworks.firstOrNull { usable(it) } } catch (_: Exception) { null }
        if (pick != null) currentNetwork = pick
        return pick
    }

    fun stop(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        callback?.let { try { cm?.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        listener = null
    }

    private fun update(context: Context, network: Network) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val lp: LinkProperties = cm.getLinkProperties(network) ?: return
        val name = lp.interfaceName ?: return
        val index = try { java.net.NetworkInterface.getByName(name)?.index ?: 0 } catch (_: Exception) { 0 }
        val caps = cm.getNetworkCapabilities(network)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)?.not() ?: false
        listener?.updateDefaultInterface(name, index, expensive, false)
    }

    /** Snapshot of all interfaces, for the core's interface enumeration. */
    fun interfaces(context: Context): NetworkInterfaceIterator {
        val out = ArrayList<NetworkInterface>()
        try {
            for (ni in Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp) continue
                val addrs = ArrayList<String>()
                for (ia in ni.interfaceAddresses) {
                    var host = ia.address?.hostAddress ?: continue
                    // CRITICAL: strip the IPv6 scope-zone suffix (e.g.
                    // "fe80::1%dummy0"). The Go core parses these with
                    // netip.ParsePrefix, which PANICS on a zone in a prefix —
                    // a native SIGABRT that kills the whole process. Seen on
                    // the emulator's dummy0/link-local addresses.
                    val pct = host.indexOf('%')
                    if (pct >= 0) host = host.substring(0, pct)
                    val prefix = ia.networkPrefixLength.toInt()
                    if (prefix < 0 || prefix > 128) continue
                    addrs.add("$host/$prefix")
                }
                // Use explicit gomobile setters — acronym fields (MTU, DNSServer)
                // don't map cleanly to Kotlin property syntax.
                out.add(NetworkInterface().apply {
                    setName(ni.name)
                    setIndex(ni.index)
                    setMTU(try { ni.mtu } catch (_: Exception) { 1500 })
                    setAddresses(StringList(addrs))
                    // Linux IFF_* flags. CRITICAL: sing-box filters the available
                    // interface list by net.FlagUp (route/network.go:291). With
                    // setFlags(0) EVERY interface looked "down" → the list came back
                    // empty → the core failed every dial (even outbound/direct) with
                    // "no available network interface", so the tunnel carried zero
                    // traffic. We already skip !isUp above, so IFF_UP is always set.
                    var f = 0x1 /* IFF_UP */
                    if (ni.isLoopback) f = f or 0x8           /* IFF_LOOPBACK */
                    if (ni.isPointToPoint) f = f or 0x10      /* IFF_POINTOPOINT */
                    if (try { ni.supportsMulticast() } catch (_: Exception) { false }) f = f or 0x1000 /* IFF_MULTICAST */
                    setFlags(f)
                    setType(Libbox.InterfaceTypeOther)
                    setDNSServer(StringList(emptyList()))
                    setMetered(false)
                })
            }
        } catch (_: Exception) {}
        return NetIfaceList(out)
    }
}
