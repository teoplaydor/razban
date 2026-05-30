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
        "processes.all", "processes.running", "processes.installed" -> JSONArray()
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

    private fun settingsJson(): JSONObject {
        val p = ctx.getSharedPreferences("razban", Context.MODE_PRIVATE)
        return JSONObject()
            .put("theme", "dark")
            .put("language", "ru")
            .put("routingMode", p.getString("routingMode", "Smart"))
            .put("startWithWindows", false)
            .put("autoConnectOnStartup", false)
            .put("proxy", JSONObject().put("enabled", true).put("mixedPort", 2080).put("listenAddress", "127.0.0.1"))
            .put("dns", JSONObject().put("primaryDns", "tls://1.1.1.1").put("domesticDns", "77.88.8.8"))
            .put("tun", JSONObject().put("enabled", true).put("interfaceName", "Razban").put("mtu", 1420))
            .put("clashApi", JSONObject().put("enabled", true).put("port", 9090))
            .put("logs", JSONObject().put("level", "info"))
            .put("killSwitch", JSONObject().put("enabled", false))
            .put("experimental", JSONObject().put("testUrl", "https://www.gstatic.com/generate_204").put("testInterval", 300))
    }

    private fun saveSettings(params: Any?) {
        val o = params as? JSONObject ?: return
        ctx.getSharedPreferences("razban", Context.MODE_PRIVATE).edit()
            .putString("routingMode", o.optString("routingMode", "Smart")).apply()
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
            arr.put(JSONObject()
                .put("id", "bundle").put("name", "Razban (мульти-протокол)")
                .put("host", protos.firstOrNull()?.third?.first ?: "")
                .put("active", true)
                .put("protocolCount", protos.size)
                .put("endpoints", endpoints))
        }
        return arr
    }

    /** {endpoints:[{tag, ok, latencyMs}]} — the bundle card's "N доступно".
     *  Connected ⇒ all protocols reachable (urltest is live among them). */
    private fun bundleHealth(): JSONObject {
        val connected = RazbanVpnService.lastStatus == RazbanVpnService.Status.Started
        val endpoints = JSONArray()
        for ((tag, _, _) in ConfigStore.protocolOutbounds(ctx)) {
            endpoints.put(JSONObject().put("tag", tag).put("ok", connected)
                .put("latencyMs", if (connected) 1 else 0))
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

    private fun readClipboard(): String {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        return cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
    }

    private fun writeClipboard(text: String) {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(android.content.ClipData.newPlainText("razban", text))
    }
}
