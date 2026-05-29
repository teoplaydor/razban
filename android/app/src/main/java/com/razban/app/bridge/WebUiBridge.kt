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
        "vpn.stats" -> JSONObject()
            .put("connected", RazbanVpnService.lastStatus == RazbanVpnService.Status.Started)
            .put("uploadBytes", 0).put("downloadBytes", 0)
            .put("uploadSpeed", 0).put("downloadSpeed", 0)
        "vpn.pendingRouteChanges" -> JSONObject().put("pending", 0).put("total", 0)
        "vpn.applyPendingRouteChanges" -> true
        "vpn.dropConnections" -> JSONObject().put("dropped", 0)
        "traffic.throughput" -> JSONObject().put("hosts", JSONArray()).put("processes", JSONArray())

        "settings.get" -> settingsJson()
        "settings.set" -> { saveSettings(params); true }

        "clipboard.read" -> readClipboard()
        "clipboard.write" -> { writeClipboard((params as? JSONObject)?.optString("text") ?: params?.toString() ?: ""); true }

        // Servers/bundles: the phone runs an imported sing-box config (a bundle).
        // Surface it minimally so the UI shows "connected to bundle".
        "servers.list" -> JSONObject().put("servers", JSONArray()).put("groups", JSONArray())
        "bundles.list" -> bundlesJson()
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

        // Data-heavy desktop tabs — benign empties for now (filled per iteration).
        "apps.connections" -> JSONArray()
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

    private fun bundlesJson(): JSONArray {
        val arr = JSONArray()
        if (ConfigStore.hasConfig(ctx)) {
            arr.put(JSONObject().put("id", "imported").put("name", "Импортированный бандл")
                .put("active", true).put("endpoints", JSONArray()))
        }
        return arr
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
