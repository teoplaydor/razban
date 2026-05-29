package com.razban.app.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Owns the active sing-box config on disk and the per-app package lists.
 *
 * The config is the SAME JSON the desktop ConfigBuilder emits (so the entire
 * routing brain — embedded itdoginfo+ColdBoot ruleset, domain_suffix rules,
 * selector/urltest bundle outbounds — is reused verbatim). [adaptForAndroid]
 * strips the handful of desktop-only fields that are invalid or meaningless on
 * Android, so even a config exported from the Windows build loads cleanly.
 */
object ConfigStore {

    private const val FILE = "current-config.json"
    private const val PREFS = "razban"
    private const val BUNDLED_ASSET = "default-config.json"

    /** True if an imported config exists OR a default config is bundled in the
     *  APK assets (so the app works out-of-the-box without any import). */
    fun hasConfig(context: Context): Boolean =
        File(context.filesDir, FILE).exists() || hasBundledDefault(context)

    /** Seed the active config from the bundled default on first run, so the
     *  phone connects instantly without an import step. Idempotent: only writes
     *  if there's no config yet. Called from RazbanApp.onCreate. */
    fun ensureDefaultConfig(context: Context) {
        if (File(context.filesDir, FILE).exists()) return
        val raw = readBundledDefault(context) ?: return
        try { importConfig(context, raw) } catch (_: Exception) {}
    }

    private fun hasBundledDefault(context: Context): Boolean = try {
        context.assets.open(BUNDLED_ASSET).use { it.read() >= 0 }
    } catch (_: Exception) { false }

    private fun readBundledDefault(context: Context): String? = try {
        context.assets.open(BUNDLED_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) { null }

    fun currentConfigJson(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (f.exists()) return f.readText()
        // Fall back to the bundled default (adapted), persisting it for next time.
        val raw = readBundledDefault(context) ?: return null
        return try { val a = adaptForAndroid(raw); f.writeText(a); a } catch (_: Exception) { null }
    }

    /** Import a sing-box config (pasted text / file / URL body). Adapts and
     *  persists it. Throws if the JSON is unparseable. */
    fun importConfig(context: Context, json: String) {
        val adapted = adaptForAndroid(json)
        File(context.filesDir, FILE).writeText(adapted)
    }

    fun includePackages(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("include_packages", emptySet())!!.toList()

    fun excludePackages(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet("exclude_packages", emptySet())!!.toList()

    fun setIncludePackages(context: Context, pkgs: Set<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("include_packages", pkgs).apply()

    fun setExcludePackages(context: Context, pkgs: Set<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("exclude_packages", pkgs).apply()

    /**
     * Windows → Android config deltas (see the port brief, §E):
     *  - tun: drop `interface_name`; force `auto_route`; force `stack=gvisor`;
     *    clamp `mtu` to ≤1500 (default 1420).
     *  - route: drop `default_interface` (no named NICs on Android).
     *  - dns: ensure at least one upstream server with detour=direct so queries
     *    can resolve even before LocalDNSTransport kicks in (and as the
     *    survives-RU-blocks chain: Yandex → Cloudflare → Google).
     *  - experimental.clash_api: keep (harmless); the app drives the core via
     *    the libbox command socket, not TCP, but leaving clash_api in does no
     *    harm if present.
     */
    fun adaptForAndroid(json: String): String {
        val root = JSONObject(json)

        // tun inbound
        val inbounds = root.optJSONArray("inbounds") ?: JSONArray()
        for (i in 0 until inbounds.length()) {
            val inb = inbounds.optJSONObject(i) ?: continue
            if (inb.optString("type") == "tun") {
                inb.remove("interface_name")
                inb.put("auto_route", true)
                inb.put("stack", "gvisor")
                val mtu = inb.optInt("mtu", 1420)
                if (mtu > 1500 || mtu <= 0) inb.put("mtu", 1420)
            }
        }
        root.put("inbounds", inbounds)

        // outbounds — byedpi (the dpi-bypass SOCKS) isn't bundled on Android
        // yet, so any `dpi-bypass` outbound points at a dead local port. Drop
        // it and re-point its routing at the tunnel (proxy), so dpi-classified
        // domains still reach their destination instead of black-holing.
        val tunnelTag = primaryProxyTag(root)
        stripDpiBypass(root, tunnelTag)

        // route
        val route = root.optJSONObject("route")
        if (route != null) {
            route.remove("default_interface")
            route.put("auto_detect_interface", true)
        }

        // dns — the desktop config points DNS at its loopback classifier
        // (127.0.0.1:5354) which doesn't exist on the phone. Replace the whole
        // block with a clean Android upstream chain (Yandex survives most RU
        // ISP resolver blocks; then the global fallbacks), all detour=direct.
        replaceDns(root)

        return root.toString(2)
    }

    /** The selector/urltest tag that represents "the tunnel" (what dpi should
     *  fall back to). Prefers a `selector`/`urltest` outbound; else "proxy". */
    private fun primaryProxyTag(root: JSONObject): String {
        val outs = root.optJSONArray("outbounds") ?: return "proxy"
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val t = o.optString("type")
            if (t == "selector" || t == "urltest") return o.optString("tag", "proxy")
        }
        // fall back to any tag literally named "proxy"
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy") return "proxy"
        }
        return "proxy"
    }

    private fun stripDpiBypass(root: JSONObject, tunnelTag: String) {
        // 1) remove the dpi-bypass outbound(s)
        val outs = root.optJSONArray("outbounds")
        if (outs != null) {
            val kept = JSONArray()
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("tag") == "dpi-bypass") continue
                kept.put(o)
            }
            root.put("outbounds", kept)
        }
        // 2) re-point any route rule / final that used it
        val route = root.optJSONObject("route") ?: return
        if (route.optString("final") == "dpi-bypass") route.put("final", tunnelTag)
        val rules = route.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (r.optString("outbound") == "dpi-bypass") r.put("outbound", tunnelTag)
        }
    }

    private fun replaceDns(root: JSONObject) {
        val servers = JSONArray()
        for (addr in listOf("77.88.8.8", "1.1.1.1", "8.8.8.8")) {
            servers.put(JSONObject().apply {
                put("address", addr)
                put("detour", "direct")
            })
        }
        root.put("dns", JSONObject().apply {
            put("servers", servers)
            put("strategy", "prefer_ipv4")
        })
    }
}
