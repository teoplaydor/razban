package com.razban.app.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import com.razban.app.bg.RazbanVpnService
import com.razban.app.config.ConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Android side of the same JSON-RPC contract the desktop WebBridge speaks,
 * so the IDENTICAL React UI (loaded in the WebView) works unchanged. The UI
 * calls `window.chrome.webview.postMessage({id,method,params})`; a shim routes
 * that into [postMessage] here; we dispatch and reply via [deliver] with
 * `{id,result}`/`{id,error}`. Events are pushed as `{type:'event',name,payload}`.
 *
 * Core methods (vpn/settings/clipboard/version) are implemented against the
 * Android VpnService + ConfigStore. Desktop-only/admin methods return benign
 * empties so every tab renders without throwing; they get fleshed out over
 * iterations.
 */
class WebUiBridge(
    private val ctx: Context,
    private val onConnect: () -> Unit,
    private val onDisconnect: () -> Unit,
) {
    /** Set by the Activity: posts a JSON string to JS (window.__razbanDeliver). */
    var deliver: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun postMessage(raw: String) {
        scope.launch {
            var id: String? = null
            try {
                val msg = JSONObject(raw)
                id = msg.optString("id", null)
                val method = msg.optString("method")
                val params = msg.opt("params")
                val result = handle(method, params)
                android.util.Log.d("razban-bridge", "rpc $method -> ${result?.toString()?.take(160)}")
                if (id != null) reply(id, result, null)
            } catch (t: Throwable) {
                if (id != null) reply(id!!, null, t.message ?: "error")
            }
        }
    }

    private fun reply(id: String, result: Any?, error: String?) {
        val o = JSONObject().put("id", id)
        if (error != null) o.put("error", error) else o.put("result", result ?: JSONObject.NULL)
        deliver(o.toString())
    }

    /** Push an event to the UI (bridge.on('name', ...)). */
    fun pushEvent(name: String, payload: Any?) {
        val o = JSONObject().put("type", "event").put("name", name).put("payload", payload ?: JSONObject.NULL)
        deliver(o.toString())
    }

    private fun handle(method: String, params: Any?): Any? = when (method) {
        "app.version" -> JSONObject()
            .put("version", appVersion())
            .put("platform", "Android ${android.os.Build.VERSION.RELEASE}")
            .put("framework", "libbox/sing-box 1.13.12")
            .put("singboxExists", true)
            .put("data", ctx.filesDir.absolutePath)

        "vpn.state" -> stateString()
        "vpn.connect" -> { onConnect(); true }
        "vpn.disconnect" -> { onDisconnect(); true }
        "vpn.stats" -> com.razban.app.bg.CoreStatus.statsJson(
            RazbanVpnService.lastStatus == RazbanVpnService.Status.Started)
        "vpn.pendingRouteChanges" -> JSONObject().put("pending", 0).put("total", 0)
        "vpn.applyPendingRouteChanges" -> true
        "vpn.dropConnections" -> JSONObject().put("dropped", 0)
        "traffic.throughput" -> com.razban.app.bg.CoreStatus.throughputJson()

        "settings.get" -> settingsJson()
        "settings.set" -> { saveSettings(params); true }

        "clipboard.read" -> readClipboard()
        "clipboard.write" -> { writeClipboard((params as? JSONObject)?.optString("text") ?: params?.toString() ?: ""); true }

        // Servers/bundles: the phone runs a bundled sing-box config. Surface its
        // protocol outbounds so the Servers tab shows them (not "0 of 0").
        "servers.list" -> serversJson()
        "bundles.list" -> bundlesJson()
        // Live per-protocol health for the bundle card. We don't have the admin
        // agent on the phone, so reflect the tunnel state: when connected, the
        // bundle's protocols are reachable (sing-box's urltest is actively
        // picking among them). Shows "N of N доступно" instead of "0 of N".
        "bundles.health" -> bundleHealth()
        // Real entry-point geo (the user's OWN public IP→country), fetched on the
        // underlying NOT_VPN network so it isn't masked by the tunnel — replaces the
        // browser-timezone guess that showed e.g. Hong Kong instead of Moscow.
        "geo.entry" -> entryGeoJson()
        // Selecting the bundle: on Android there's a single bundle = the active
        // config, and sing-box auto-rotates protocols via urltest. Acknowledge.
        "servers.setActive", "bundles.setActive" -> true
        "bundles.delete", "servers.remove" -> true   // no-op: don't delete the bundled config
        "subscriptions.list" -> JSONArray()

        // Config import path the React UI can call (we also keep clipboard import
        // in the legacy native screen). If params has {config}, import it.
        "config.import" -> { importConfig(params); true }
        "config.hasConfig" -> ConfigStore.hasConfig(ctx)

        // Server-by-code: pull + decrypt a server config from the distribution
        // host by a short code (e.g. "hub"). On success the config is imported;
        // if the tunnel is up we hot-reload it onto the new server.
        "code.redeem" -> {
            val code = when (params) {
                is JSONObject -> params.optString("code", "")
                is String -> params
                else -> ""
            }
            val n = com.razban.app.bg.CoreCode.redeem(ctx, code)
            if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Started)
                ctx.startService(android.content.Intent(ctx, RazbanVpnService::class.java)
                    .setAction(RazbanVpnService.ACTION_RELOAD))
            pushEvent("servers.changed", true)
            JSONObject().put("ok", true).put("outbounds", n)
        }

        // The Servers page "Из буфера" button calls servers.addMany({text}).
        // On Android the pasted text is a full sing-box config JSON (an exported
        // bundle), not a list of URIs — import it as the active config.
        "servers.addMany", "servers.add" -> {
            val text = when (params) {
                is JSONObject -> params.optString("text", params.optString("config", ""))
                is String -> params
                else -> ""
            }
            if (text.trimStart().startsWith("{") && text.contains("\"outbounds\"")) {
                ConfigStore.importConfig(ctx, text)
                JSONObject().put("count", 1)
            } else {
                JSONObject().put("count", 0)
            }
        }

        // Live per-connection data from the core's command stream (Apps tab).
        "apps.connections" -> com.razban.app.bg.CoreStatus.connectionsJson()
        "processes.all", "processes.running", "processes.installed" -> installedAppsJson()
        "domains.observed" -> JSONObject().put("direct", JSONArray()).put("dpi", JSONArray()).put("vpn", JSONArray())
        "visited.list" -> JSONArray()
        "discovery.sources" -> JSONArray()
        "health.snapshot" -> JSONArray()

        else -> null   // unknown/desktop-only → null (UI tolerates it)
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun stateString(): String = when (RazbanVpnService.lastStatus) {
        RazbanVpnService.Status.Started -> "connected"
        RazbanVpnService.Status.Starting -> "connecting"
        RazbanVpnService.Status.Stopping -> "disconnecting"
        else -> "disconnected"
    }

    private fun appVersion(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1.0"
    } catch (_: Exception) { "0.1.0" }

    // Must mirror the FULL AppSettings shape the React Settings page expects.
    // Every nested section the UI dereferences (proxy/dns/tun/clashApi/logs/
    // experimental/killSwitch/discovery/speed/dpi/upstreamProxy) MUST be present
    // — a missing object makes `local.<section>.<field>` throw a TypeError that
    // unmounts the whole React tree (blank screen). Individual fields may be
    // absent (UI uses ?? fallbacks), but the section objects may not.
    private fun settingsJson(): JSONObject {
        val p = ctx.getSharedPreferences("razban", Context.MODE_PRIVATE)
        val base = JSONObject()
            .put("theme", "dark")
            .put("language", "ru")
            .put("routingMode", p.getString("routingMode", "Smart"))
            .put("defaultRoute", "proxy")
            .put("startWithWindows", false)
            .put("startMinimized", false)
            .put("minimizeToTray", false)
            .put("autoConnectOnStartup", false)
            .put("checkForUpdates", true)
            .put("proxy", JSONObject().put("enabled", true).put("mixedPort", 2080)
                .put("socksPort", 0).put("httpPort", 0).put("listenAddress", "127.0.0.1")
                .put("setSystemProxy", false).put("allowLan", false))
            .put("dns", JSONObject().put("primaryDns", "tls://1.1.1.1").put("secondaryDns", "tls://8.8.8.8")
                .put("domesticDns", "77.88.8.8").put("strategy", "prefer_ipv4")
                .put("enableDoH", true).put("blockAds", false))
            .put("tun", JSONObject().put("enabled", true).put("interfaceName", "Razban").put("mtu", 1420)
                .put("stack", "gvisor").put("autoRoute", true).put("strictRoute", false))
            .put("clashApi", JSONObject().put("enabled", true).put("port", 9090)
                .put("secret", "").put("externalUi", false))
            .put("logs", JSONObject().put("level", "info").put("persist", false).put("maxFiles", 7))
            .put("experimental", JSONObject().put("enableSniffing", true).put("enableHttp3", false)
                .put("useGvisorStack", true).put("testUrl", "https://www.gstatic.com/generate_204")
                .put("testInterval", 300))
            .put("killSwitch", JSONObject().put("enabled", false)
                .put("blockOnDisconnect", false).put("blockOnLeak", false))
            .put("discovery", JSONObject().put("enabled", true).put("runAtStartup", false)
                .put("refreshInterval", "6h").put("maxServersPerSource", 50)
                .put("disabledSourceIds", JSONArray()).put("autoPruneDead", true)
                .put("pruneAfterFailures", 3).put("healthCheckInterval", "5m")
                .put("pingIntervalSeconds", 30))
            .put("speed", JSONObject().put("enableTcpFastOpen", false).put("enableMultiplex", false)
                .put("multiplexProtocol", "smux").put("multiplexMaxConnections", 4)
                .put("multiplexMinStreams", 4).put("enableBrutal", false).put("brutalUpMbps", 0)
                .put("brutalDownMbps", 0).put("enableUdpOverTcp", false).put("enableTcpNoDelay", true)
                .put("enableDnsCache", true).put("dnsCacheSize", 0))
            .put("dpi", JSONObject().put("enabled", false).put("algorithm", "auto").put("localPort", 1080))
            .put("upstreamProxy", JSONObject().put("enabled", false).put("type", "http")
                .put("host", "").put("port", 0))
            .put("customRules", JSONArray())
        // Echo back the saved per-app/per-domain buckets so the Apps/Sites tabs
        // render the user's current pins and round-trip them on the next save.
        val ur = try { JSONObject(p.getString("userRoutes", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        for (k in listOf("bypassApps", "proxyApps", "dpiApps",
                         "bypassDomains", "proxyDomains", "dpiDomains", "httpProxyDomains"))
            base.put(k, ur.optJSONArray(k) ?: JSONArray())
        return base
    }

    private fun saveSettings(params: Any?) {
        val o = params as? JSONObject ?: return
        // Persist the user's per-app / per-domain route buckets (settings.set sends
        // the full AppSettings) so ConfigStore.injectUserRoutes can layer them into
        // the live config. Then hot-reload if the tunnel is up so the change applies
        // immediately — the Android analog of the desktop's clash_api PUT /configs.
        val ur = JSONObject()
        for (k in listOf("bypassApps", "proxyApps", "dpiApps",
                         "bypassDomains", "proxyDomains", "dpiDomains", "httpProxyDomains"))
            ur.put(k, o.optJSONArray(k) ?: JSONArray())
        ctx.getSharedPreferences("razban", Context.MODE_PRIVATE).edit()
            .putString("routingMode", o.optString("routingMode", "Smart"))
            .putString("userRoutes", ur.toString())
            .apply()
        if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Started)
            ctx.startService(android.content.Intent(ctx, RazbanVpnService::class.java)
                .setAction(RazbanVpnService.ACTION_RELOAD))
    }

    private fun serversJson(): JSONObject {
        val servers = JSONArray()
        for ((tag, type, addr) in ConfigStore.protocolOutbounds(ctx)) {
            servers.put(JSONObject()
                .put("id", tag).put("name", tag).put("protocol", type)
                .put("server", addr.first).put("port", addr.second)
                .put("pingMs", 0)
                .put("reality", JSONObject().put("enabled", type == "vless")))
        }
        return JSONObject().put("servers", servers).put("groups", JSONArray())
    }

    private fun bundlesJson(): JSONArray {
        val arr = JSONArray()
        val protos = ConfigStore.protocolOutbounds(ctx)
        if (protos.isNotEmpty()) {
            val endpoints = JSONArray()
            var prio = 0
            for ((tag, type, addr) in protos) {
                endpoints.put(JSONObject().put("tag", tag).put("protocol", type)
                    .put("server", addr.first).put("port", addr.second)
                    .put("priority", prio++))
            }
            val host = protos.firstOrNull()?.third?.first ?: ""
            kickCountry(host)                       // resolve exit country (async, cached)
            val cc = countryCache[host]
            val obj = JSONObject()
                .put("id", "bundle").put("name", "Razban (мульти-протокол)")
                .put("host", host)
                .put("active", true)
                .put("protocolCount", protos.size)
                .put("endpoints", endpoints)
            if (cc != null) {                       // → globe draws the you→exit arc + highlight
                obj.put("countryCode", cc)
                obj.put("countryName", ccNames[cc] ?: cc)
            }
            arr.put(obj)
        }
        return arr
    }

    // ── per-endpoint TCP reachability cache (real ping; works even w/o VPN) ──
    private val healthCache = java.util.concurrent.ConcurrentHashMap<String, Triple<Boolean, Int, Long>>()
    private val pingInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun isTlsType(t: String) =
        t == "vless" || t == "vmess" || t == "trojan" || t == "anytls" || t == "shadowtls" || t == "naive"
    private fun isUdpType(t: String) =
        t == "hysteria2" || t == "hysteria" || t == "tuic" || t == "wireguard"

    // Trust-all TLS factory for the liveness probe: Reality/ShadowTLS present a
    // proxied/stolen foreign cert and AnyTLS a self-signed one, so cert-chain
    // validation must be OFF — we only care that the server completes a real TLS
    // handshake (proof it's actually speaking TLS, not just accepting SYNs).
    private val permissiveSsl: javax.net.ssl.SSLSocketFactory by lazy {
        val tm = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sc = javax.net.ssl.SSLContext.getInstance("TLS")
        sc.init(null, tm, java.security.SecureRandom())
        sc.socketFactory
    }

    /** Real reachability probe on a worker thread; stores (ok, latencyMs, ts) in
     *  healthCache, deduped by tag. A bare TCP connect is a FALSE-green — RKN/TSPU
     *  SYN-ACK injection and a dead-but-listening port both complete it, so a dead
     *  endpoint used to always show alive. So:
     *   • TLS protocols → a full TLS handshake (cert validation off). Completing it
     *     proves the server speaks TLS; a connect alone doesn't.
     *   • UDP/QUIC (hy2/tuic/wg) → a datagram + ICMP-unreachable check. UDP can't
     *     be positively confirmed externally, so we only DISPROVE via an ICMP
     *     PortUnreachable (= dead); a reply/timeout = reachable-unknown.
     *   • else (ss/socks/http) → TCP connect (weak; the best we can read).
     *  NOTE: while the tunnel is UP these probes route THROUGH it, so they answer
     *  "is the host reachable from the exit", not "from my ISP". The authoritative
     *  connected-state signal is libbox urltest (CoreStatus.writeGroups /
     *  OutboundGroupIterator delays) — TODO: wire it like the desktop clash_api
     *  path so the badge reflects the urltest-selected protocol. */
    private fun kickPing(tag: String, host: String, port: Int, type: String) {
        if (!pingInFlight.add(tag)) return
        Thread {
            val t0 = System.currentTimeMillis()
            var ok = false; var lat = -1
            try {
                when {
                    isUdpType(type) -> java.net.DatagramSocket().use { ds ->
                        ds.soTimeout = 1500
                        ds.connect(java.net.InetAddress.getByName(host), port)
                        ds.send(java.net.DatagramPacket(byteArrayOf(0), 1))
                        ok = try {
                            val buf = ByteArray(64)
                            ds.receive(java.net.DatagramPacket(buf, buf.size)); true   // reply → up
                        } catch (e: java.net.PortUnreachableException) { false }        // ICMP → dead
                          catch (e: java.net.SocketTimeoutException) { true }           // no disproof → reachable
                    }
                    isTlsType(type) -> java.net.Socket().use { raw ->
                        raw.connect(java.net.InetSocketAddress(host, port), 2500)
                        val ssl = permissiveSsl.createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket
                        ssl.soTimeout = 4000
                        ssl.startHandshake()                                            // throws if not really TLS
                        ok = true; lat = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                        try { ssl.close() } catch (_: Exception) {}
                    }
                    else -> java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, port), 2500)
                        ok = true; lat = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
                    }
                }
            } catch (_: Exception) { ok = false }
            healthCache[tag] = Triple(ok, lat, System.currentTimeMillis())
            pingInFlight.remove(tag)
        }.start()
    }

    // ── bundle-host country cache (drives the home globe's exit-country arc) ──
    // The desktop bundle carries countryCode; the Android bundle only has the host,
    // so the globe could only draw "вход" (one dot). Resolve the host's country via
    // the same ipinfo path GeoClassifier uses, cached, and feed it to bundlesJson so
    // the globe draws the flying you→exit arc + highlights the exit country.
    private val countryCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val countryInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    // Instant country for known deploy hosts so the globe draws the exit arc on
    // the FIRST frame, without waiting on the async ipinfo round-trip (which the
    // UI screenshot can race). Geo fills in any other server.
    private val knownHostCc = mapOf("78.17.1.133" to "FI")

    private fun kickCountry(host: String) {
        if (host.isEmpty() || countryCache.containsKey(host)) return
        knownHostCc[host]?.let { countryCache[host] = it; return }   // instant
        if (!countryInFlight.add(host)) return
        Thread {
            try {
                val ip = if (host.matches(Regex("^[0-9.]+$"))) host
                         else java.net.InetAddress.getByName(host).hostAddress ?: host
                val cc = com.razban.app.bg.GeoClassifier.countryOf(ip)
                if (cc != null) { countryCache[host] = cc; android.util.Log.i("razban-geo", "bundle host $host → $cc") }
            } catch (_: Exception) { /* leave uncached; retried next poll */ }
            finally { countryInFlight.remove(host) }
        }.apply { isDaemon = true }.start()
    }

    // ISO_A2 → Russian name for the deploy/common exit countries (globe label).
    private val ccNames = mapOf(
        "FI" to "Финляндия", "NL" to "Нидерланды", "DE" to "Германия", "LV" to "Латвия",
        "SE" to "Швеция", "GB" to "Великобритания", "FR" to "Франция", "US" to "США",
        "PL" to "Польша", "EE" to "Эстония", "LT" to "Литва", "NO" to "Норвегия",
        "CH" to "Швейцария", "TR" to "Турция", "JP" to "Япония", "SG" to "Сингапур",
        "KZ" to "Казахстан", "AM" to "Армения", "RU" to "Россия")

    // Real entry-point geolocation. The globe's "вход" was derived from the browser
    // timezone (Intl) → garbage (Hong Kong for a Moscow user). Resolve the user's
    // ACTUAL public IP→country via ipinfo.io, bound to the underlying NOT_VPN network
    // (DefaultNetworkMonitor.currentNetwork) so a live tunnel doesn't mask it as the
    // exit. Cached for the session; the @JavascriptInterface bridge already runs off
    // the UI thread (code.redeem blocks here too), so a short blocking fetch is safe.
    @Volatile private var entryGeo: JSONObject? = null
    private fun entryGeoJson(): JSONObject {
        entryGeo?.let { return it }
        return try {
            val net = com.razban.app.bg.DefaultNetworkMonitor.currentNetwork
            val url = java.net.URL("https://ipinfo.io/json")
            val conn = (net?.openConnection(url) ?: url.openConnection()) as java.net.HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.setRequestProperty("Accept", "application/json")
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cc = j.optString("country")
            val loc = j.optString("loc").split(",")
            val out = JSONObject().put("cc", cc).put("name", ccNames[cc] ?: cc)
                .put("city", j.optString("city"))
            loc.getOrNull(0)?.toDoubleOrNull()?.let { la -> loc.getOrNull(1)?.toDoubleOrNull()?.let { lo ->
                out.put("lat", la).put("lon", lo) } }
            if (cc.isNotEmpty()) entryGeo = out   // cache only a real answer
            android.util.Log.i("razban-geo", "entry geo → $cc (${j.optString("city")})")
            out
        } catch (e: Exception) {
            JSONObject().put("cc", "").put("error", e.message ?: "fetch failed")
        }
    }

    /** {endpoints:[{tag, ok, latencyMs, kind, pinging}]} — the bundle card badges.
     *  REAL per-protocol reachability (TLS handshake / UDP-ICMP / TCP — see
     *  kickPing), so a dead endpoint shows red instead of a permanent false-green.
     *  Async + 10s cache — first call kicks the probes and reports `pinging:true`
     *  (UI spinner) until results land (~1-2s). No more `|| connected` blanket and
     *  no hard-coded ok=true for hy2/tuic (that was the "dead UDP server always
     *  pings alive" bug — a TCP probe can't even see a UDP port). `kind` lets the
     *  UI render udp✓ / tls / tcp distinctly. */
    private fun bundleHealth(): JSONObject {
        val protos = ConfigStore.protocolOutbounds(ctx)
        val now = System.currentTimeMillis()
        val endpoints = JSONArray()
        for ((tag, type, addr) in protos) {
            val cached = healthCache[tag]
            val fresh = cached != null && (now - cached.third) < 10_000
            if (!fresh) kickPing(tag, addr.first, addr.second, type)
            val kind = if (isUdpType(type)) "udp" else if (isTlsType(type)) "tls" else "tcp"
            endpoints.put(JSONObject().put("tag", tag)
                .put("ok", cached?.first ?: false)
                .put("latencyMs", cached?.second ?: -1)
                .put("kind", kind)
                .put("pinging", cached == null))
        }
        return JSONObject().put("endpoints", endpoints)
    }

    private fun importConfig(params: Any?) {
        val cfg = when (params) {
            is JSONObject -> params.optString("config", params.toString())
            is String -> params
            else -> params?.toString() ?: return
        }
        if (cfg.trimStart().startsWith("{")) ConfigStore.importConfig(ctx, cfg)
    }

    /** Launchable (user-facing) installed apps for the Apps tab, so the user can
     *  pin an app to direct/VPN. `process` carries the package name — that's what
     *  ConfigStore.injectUserRoutes emits as a `package_name` route rule. Skips
     *  self; dedups multi-activity packages. (Icons omitted — name+package is
     *  enough to pick; base64-ing every icon would bloat the payload.) */
    @Volatile private var installedCache: JSONArray? = null
    private fun installedAppsJson(): JSONArray {
        installedCache?.let { return it }   // installed list rarely changes — build once (icons are pricey)
        val pm = ctx.packageManager
        val arr = JSONArray()
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                val label = try { ri.loadLabel(pm)?.toString() } catch (_: Exception) { null } ?: pkg
                // Field names MUST match the React AppEntry shape (executableName is
                // what the Apps tab reads + writes into the *Apps buckets, which
                // ConfigStore.injectUserRoutes emits as package_name rules). `icon` is
                // a small base64 PNG so the picker is recognizable at a glance.
                arr.put(JSONObject()
                    .put("name", label)
                    .put("executableName", pkg)
                    .put("isRunning", false)
                    .put("pid", 0)
                    .put("icon", try { iconDataUri(ri.loadIcon(pm)) } catch (_: Exception) { "" }))
            }
        } catch (_: Exception) {}
        installedCache = arr
        return arr
    }

    /** App icon → a small base64 PNG data URI (downscaled to 40px), so ~50 icons
     *  stay a few hundred KB total — acceptable for the cached one-time build, and
     *  makes the picker recognizable instead of 2-letter initials. */
    private fun iconDataUri(d: android.graphics.drawable.Drawable?): String {
        if (d == null) return ""
        return try {
            val sz = 40
            val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, sz, sz); d.draw(c)
            val bos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            "data:image/png;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun readClipboard(): String {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        return cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    }

    private fun writeClipboard(text: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("razban", text))
    }
}
