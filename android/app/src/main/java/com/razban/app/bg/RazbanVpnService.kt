package com.razban.app.bg

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.razban.app.config.ConfigStore
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Android VPN service. This single class plays three roles that on
 * desktop are spread across VpnService.cs + SingBoxRunner + ClashApiClient:
 *
 *   1. android.net.VpnService — owns the system TUN file descriptor.
 *   2. libbox.PlatformInterface — the callbacks the Go core invokes; the
 *      critical one is [openTun], which translates sing-box's TunOptions into
 *      VpnService.Builder calls and hands back the fd. (Android equivalent of
 *      wintun adapter creation + auto_route, but the OS owns the route table.)
 *   3. libbox.CommandServerHandler — service lifecycle callbacks from the
 *      core's command server (stop / reload / system-proxy — the latter is a
 *      no-op on Android).
 *
 * Disconnect = closeService() + close the fd. There is no process to kill
 * (the core runs in-process) and no OS route cleanup to do (closing the fd
 * tears down the TUN and Android restores the previous default route) — so
 * the whole desktop "PowerShell cleanup pass" disappears here.
 */
class RazbanVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var status: Status = Status.Stopped
        private set(value) {
            field = value
            lastStatus = value   // mirror to a static so instrumented tests can poll
        }

    enum class Status { Stopped, Starting, Started, Stopping }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopTunnel(); return START_NOT_STICKY }
        }
        if (status == Status.Stopped) startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        status = Status.Starting
        Notifications.startForeground(this)
        // Keep the VpnService's underlying network in sync with the physical
        // (NOT_VPN) network the monitor tracks — required for the core's
        // protected sockets to actually egress (SFA does this too).
        DefaultNetworkMonitor.onNetwork = { net ->
            try { setUnderlyingNetworks(if (net != null) arrayOf(net) else null) } catch (_: Throwable) {}
        }
        scope.launch {
            try {
                android.util.Log.d(TAG, "startTunnel: libbox ${Libbox.version()}")
                val config = ConfigStore.currentConfigJson(this@RazbanVpnService)
                    ?: error("no config imported")
                android.util.Log.d(TAG, "startTunnel: config loaded (${config.length} chars)")

                val server = Libbox.newCommandServer(this@RazbanVpnService, this@RazbanVpnService)
                android.util.Log.d(TAG, "startTunnel: command server created")
                server.start()
                android.util.Log.d(TAG, "startTunnel: command server started")
                commandServer = server

                // Per-app entry filter (VPN-level whitelist XOR blacklist).
                // Route-level direct/proxy/dpi distinction is done by
                // package_name rules baked into the config JSON.
                val override = OverrideOptions().apply {
                    autoRedirect = false
                    val include = ConfigStore.includePackages(this@RazbanVpnService)
                    val exclude = ConfigStore.excludePackages(this@RazbanVpnService)
                    if (include.isNotEmpty()) includePackage = StringList(include)
                    else if (exclude.isNotEmpty()) excludePackage = StringList(exclude)
                }
                android.util.Log.d(TAG, "startTunnel: startOrReloadService…")
                server.startOrReloadService(config, override)
                android.util.Log.d(TAG, "startTunnel: service started OK")
                status = Status.Started
                broadcastStatus()
                // Subscribe to the core's live status stream → real up/down
                // numbers for the UI (vpn.stats / throughput bars).
                CoreStatus.start()
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "startTunnel FAILED", t)
                writeDebugMessage("start failed: ${t.message}")
                stopTunnel()
            }
        }
    }

    /** Graceful reload — pushes a freshly-generated config into the live core
     *  without dropping the TUN (Android analog of clash_api PUT /configs). */
    fun reload() {
        scope.launch {
            val cfg = ConfigStore.currentConfigJson(this@RazbanVpnService) ?: return@launch
            try {
                commandServer?.startOrReloadService(cfg, OverrideOptions().apply { autoRedirect = false })
            } catch (t: Throwable) {
                writeDebugMessage("reload failed: ${t.message}")
            }
        }
    }

    private fun stopTunnel() {
        if (status == Status.Stopping || status == Status.Stopped) return
        status = Status.Stopping
        scope.launch {
            CoreStatus.stop()
            try { commandServer?.closeService() } catch (_: Throwable) {}
            try { commandServer?.close() } catch (_: Throwable) {}
            commandServer = null
            try { tunFd?.close() } catch (_: Throwable) {}
            tunFd = null
            status = Status.Stopped
            broadcastStatus()
            Notifications.stopForeground(this@RazbanVpnService)
            stopSelf()
        }
    }

    override fun onRevoke() {
        // The user disabled the VPN from system settings, or another VPN took
        // over (only one VpnService can be active at a time on Android — this
        // is why desktop's conflicting-TUN detection has no equivalent here).
        stopTunnel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun broadcastStatus() {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATUS, status.name))
    }

    // ──────────────────── PlatformInterface ─────────────────────

    /**
     * THE heart of the port. The core calls this with the TUN parameters it
     * derived from the config's `tun` inbound; we materialise them as a real
     * Android VPN interface and return the fd. libbox dup()s the fd, so we keep
     * our own ParcelFileDescriptor to close on teardown.
     */
    override fun openTun(options: TunOptions): Int {
        android.util.Log.d(TAG, "openTun: enter (mtu=${options.getMTU()}, autoRoute=${options.autoRoute})")
        if (prepare(this) != null) error("VPN permission not granted")

        val builder = Builder()
            .setSession("Razban")
            .setMtu(options.getMTU())          // authoritative MSS clamp on Android — no wintun 65535 quirk

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        // Interface addresses (e.g. 172.19.0.1/30, fdfe:.../126).
        options.inet4Address.let { while (it.hasNext()) it.next().let { p -> builder.addAddress(p.address(), p.prefix()) } }
        options.inet6Address.let { while (it.hasNext()) it.next().let { p -> builder.addAddress(p.address(), p.prefix()) } }

        if (options.autoRoute) {
            // DNS server(s) the OS will send queries to while the VPN is up.
            // MUST be set, otherwise the system has no resolver under the tunnel
            // → every lookup is UnknownHostException ("No address associated
            // with hostname"). Prefer the tun's hijack address (tun_addr+1) so
            // sing-box's own dns module answers; ALWAYS also add public
            // resolvers as a guarantee (their queries enter the tun and route
            // per the config — no leak).
            var dnsAdded = false
            try {
                val dns = options.getDNSServerAddress() // StringBox, throws if prefix == /32
                builder.addDnsServer(dns.value)
                dnsAdded = true
            } catch (_: Throwable) { /* fall through to public resolvers */ }
            try { builder.addDnsServer("1.1.1.1"); dnsAdded = true } catch (_: Throwable) {}
            try { builder.addDnsServer("8.8.8.8") } catch (_: Throwable) {}
            android.util.Log.d(TAG, "openTun: DNS servers added=$dnsAdded")

            // Route the auto_route-computed ranges (everything EXCEPT LAN /
            // private space) through the TUN; sing-box then sends any flow it
            // classifies as `direct` back out the protected socket on the real
            // NIC. Using the ranges instead of a blanket 0.0.0.0/0 keeps LAN
            // devices reachable. Works on every API level — no hidden IpPrefix
            // / excludeRoute (API 33) dependency.
            val rr4 = options.inet4RouteRange
            if (rr4.hasNext()) while (rr4.hasNext()) rr4.next().let { builder.addRoute(it.address(), it.prefix()) }
            else builder.addRoute("0.0.0.0", 0)
            val rr6 = options.inet6RouteRange
            while (rr6.hasNext()) rr6.next().let { builder.addRoute(it.address(), it.prefix()) }
        }

        // Per-app entry filtering (mirror of the OverrideOptions set at start;
        // libbox surfaces them back through TunOptions too).
        options.includePackage.let { while (it.hasNext()) tryAddAllowed(builder, it.next()) }
        options.excludePackage.let { while (it.hasNext()) tryAddDisallowed(builder, it.next()) }
        // NOTE: we intentionally do NOT exclude our own package. The command
        // channel is a unix socket (not network), so it isn't captured by the
        // TUN, and routing our own update/probe traffic through the tunnel is
        // fine for a privacy tool. (When byedpi is bundled later it gets its
        // own process-name/uid exclusion — see TODO.) Keeping self IN the tun
        // is also what lets the instrumented VPN test generate routable traffic.

        val pfd = builder.establish() ?: error("VpnService.Builder.establish() returned null")
        tunFd = pfd
        // Point the VPN at its underlying physical network so protected
        // outbound sockets route there (not back into the tun).
        try {
            DefaultNetworkMonitor.currentNetwork?.let { setUnderlyingNetworks(arrayOf(it)) }
        } catch (_: Throwable) {}
        android.util.Log.d(TAG, "openTun: established fd=${pfd.fd}, underlying=${DefaultNetworkMonitor.currentNetwork}")
        return pfd.fd
    }

    private fun tryAddAllowed(b: Builder, pkg: String) =
        try { b.addAllowedApplication(pkg) } catch (_: Throwable) {}

    private fun tryAddDisallowed(b: Builder, pkg: String) =
        try { b.addDisallowedApplication(pkg) } catch (_: Throwable) {}

    private var protectCount = 0
    override fun autoDetectInterfaceControl(fd: Int) {
        // Protect the core's own outbound sockets from being re-captured by the
        // TUN (the thing auto_route would otherwise loop). Mandatory.
        val ok = protect(fd)
        if (protectCount++ < 5) android.util.Log.d(TAG, "autoDetectInterfaceControl: protect(fd=$fd) -> $ok")
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): ConnectionOwner = ConnectionResolver.find(
        this, ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort
    )

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
        DefaultNetworkMonitor.start(this, listener)

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
        DefaultNetworkMonitor.stop(this)

    override fun getInterfaces(): NetworkInterfaceIterator =
        DefaultNetworkMonitor.interfaces(this)

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun systemCertificates(): StringIterator = StringList(emptyList())
    override fun clearDNSCache() {}
    override fun sendNotification(notification: Notification) =
        Notifications.fromCore(this, notification)

    // ─────────────────── CommandServerHandler ───────────────────

    override fun serviceStop() { stopTunnel() }
    override fun serviceReload() { reload() }
    override fun getSystemProxyStatus(): SystemProxyStatus =
        SystemProxyStatus().apply { available = false; enabled = false } // N/A on Android
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun writeDebugMessage(message: String) {
        android.util.Log.d("razban-core", message)
    }

    companion object {
        const val TAG = "razban-core"
        const val ACTION_STOP = "com.razban.app.STOP"
        const val ACTION_STATUS = "com.razban.app.STATUS"
        const val EXTRA_STATUS = "status"

        /** Last status, readable from the test process (same process as the
         *  service). Not for production logic — UI uses the broadcast. */
        @Volatile
        var lastStatus: Status = Status.Stopped
            private set
    }
}
