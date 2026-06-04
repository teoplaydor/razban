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

    /** Fire-and-forget TCP connect to host:port on a worker thread; stores
     *  (ok, latencyMs, timestamp) in healthCache. Dedups by tag so a polling UI
     *  doesn't spawn a new socket every call. */
    private fun kickPing(tag: String, host: String, port: Int) {
        if (!pingInFlight.add(tag)) return
        Thread {
            val t0 = System.currentTimeMillis()
            var ok = false; var lat = 0
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(host, port), 2500)
                    ok = true; lat = (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
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

    private fun kickCountry(host: String) {
        if (host.isEmpty() || countryCache.containsKey(host)) return
        if (!countryInFlight.add(host)) return
        Thread {
            try {
                val ip = if (host.matches(Regex("^[0-9.]+$"))) host
                         else java.net.InetAddress.getByName(host).hostAddress ?: host
                val cc = com.razban.app.bg.GeoClassifier.countryOf(ip)
                if (cc != null) countryCache[host] = cc
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

    /** {endpoints:[{tag, ok, latencyMs, pinging}]} — the bundle card's badges.
     *  Real per-protocol TCP reachability: pings each endpoint directly (no VPN
     *  required) so a freshly-added (by-code) server shows live latency instead
     *  of "no connection". Async + 10s cache — the first call kicks the pings
     *  and reports `pinging:true`, so the UI shows a spinner until results land
     *  (~1-2s); subsequent polls return the cached latency. When the tunnel is
     *  up, `ok` is forced true (urltest keeps a live protocol among them). */
    private fun bundleHealth(): JSONObject {
        val protos = ConfigStore.protocolOutbounds(ctx)
        val connected = RazbanVpnService.lastStatus == RazbanVpnService.Status.Started
        val now = System.currentTimeMillis()
        val endpoints = JSONArray()
        for ((tag, type, addr) in protos) {
            // UDP/QUIC protocols (hysteria2/tuic) never answer a TCP connect, so
            // a TCP probe would always show a misleading "× нет". Report udp-ok
            // without probing (latency -1 → the UI renders "udp✓").
            if (type == "hysteria2" || type == "hysteria" || type == "tuic") {
                endpoints.put(JSONObject().put("tag", tag).put("ok", true)
                    .put("latencyMs", -1).put("pinging", false))
                continue
            }
            val cached = healthCache[tag]
            val fresh = cached != null && (now - cached.third) < 10_000
            if (!fresh) kickPing(tag, addr.first, addr.second)
            val lat = cached?.second ?: 0
            endpoints.put(JSONObject().put("tag", tag)
                .put("ok", (cached?.first ?: false) || connected)
                .put("latencyMs", if (lat > 0) lat else if (connected) 1 else 0)
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
    private fun installedAppsJson(): JSONArray {
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
                // ConfigStore.injectUserRoutes emits as package_name rules).
                arr.put(JSONObject()
                    .put("name", label)
                    .put("executableName", pkg)
                    .put("isRunning", false)
                    .put("pid", 0))
            }
        } catch (_: Exception) {}
        return arr
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
