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

    fun hasConfig(context: Context): Boolean = File(context.filesDir, FILE).exists()

    fun currentConfigJson(context: Context): String? {
        val f = File(context.filesDir, FILE)
        return if (f.exists()) f.readText() else null
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

        // route
        val route = root.optJSONObject("route")
        if (route != null) {
            route.remove("default_interface")
            route.put("auto_detect_interface", true)
        }

        // dns — guarantee a working upstream chain with detour=direct
        ensureDns(root)

        return root.toString(2)
    }

    private fun ensureDns(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        if (servers.length() == 0) {
            // Yandex first — it survives most RU ISP resolver blocks; then the
            // global fallbacks. All direct so DNS doesn't tunnel.
            for (addr in listOf("77.88.8.8", "1.1.1.1", "8.8.8.8")) {
                servers.put(JSONObject().apply {
                    put("address", addr)
                    put("detour", "direct")
                })
            }
        }
    }
}
