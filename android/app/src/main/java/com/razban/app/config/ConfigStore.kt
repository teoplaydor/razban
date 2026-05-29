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

    /** Seed/refresh the active config from the bundled default. Called from
     *  RazbanApp.onCreate. Behaviour:
     *   - no config yet → seed from bundle.
     *   - app was updated AND the current config came from the bundle (user
     *     didn't import their own) → RE-SEED from the new bundle. This is the
     *     fix for "updated the app but the config/protocols didn't change":
     *     an install-over-old keeps filesDir, so without this the stale config
     *     persisted. No manual uninstall needed anymore.
     *   - user imported their own config → never overwrite it. */
    fun ensureDefaultConfig(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val f = File(context.filesDir, FILE)
        val curVer = currentVersionCode(context)
        val fromBundle = prefs.getBoolean("config_from_bundle", false)
        val seededVer = prefs.getInt("seeded_config_version", -1)
        // "Unknown provenance" = installed before this tracking existed (≤0.1.3).
        // Treat it as bundle-eligible so a one-time re-seed fixes the stale
        // config carried over an install-over-old.
        val unknownProvenance = !prefs.contains("config_from_bundle")
        val needSeed = !f.exists() || ((fromBundle || unknownProvenance) && seededVer != curVer)
        if (!needSeed) return
        val raw = readBundledDefault(context) ?: return
        try {
            f.writeText(adaptForAndroid(raw))
            prefs.edit()
                .putBoolean("config_from_bundle", true)
                .putInt("seeded_config_version", curVer)
                .apply()
        } catch (_: Exception) {}
    }

    private fun currentVersionCode(context: Context): Int = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { -1 }

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
     *  persists it. Marks the config as user-provided so app updates won't
     *  overwrite it with the bundled default. Throws if the JSON is unparseable. */
    fun importConfig(context: Context, json: String) {
        val adapted = adaptForAndroid(json)
        File(context.filesDir, FILE).writeText(adapted)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("config_from_bundle", false).apply()
    }

    /** Parsed view of the active config's protocol outbounds, for the Servers
     *  UI (so it shows the bundle's protocols instead of "0 of 0"). */
    fun protocolOutbounds(context: Context): List<Triple<String, String, Pair<String, Int>>> {
        val json = currentConfigJson(context) ?: return emptyList()
        val out = ArrayList<Triple<String, String, Pair<String, Int>>>()
        try {
            val root = JSONObject(json)
            val realTypes = setOf("vless", "vmess", "trojan", "hysteria2", "hysteria",
                "tuic", "anytls", "shadowtls", "shadowsocks", "wireguard")
            // sing-box 1.13 puts servers in "outbounds" and/or "endpoints".
            for (key in listOf("outbounds", "endpoints")) {
                val arr = root.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val type = o.optString("type")
                    if (type !in realTypes) continue
                    // Skip inner/wrapped layers (e.g. the shadowsocks inside a
                    // ShadowTLS outbound has detour=<shadowtls-tag>). They're not
                    // user-facing protocols — only the outer one is.
                    if (o.optString("detour", "").isNotEmpty()) continue
                    val tag = o.optString("tag", type)
                    val server = o.optString("server", o.optString("address", ""))
                    val port = o.optInt("server_port", o.optInt("port", 0))
                    out.add(Triple(tag, type, server to port))
                }
            }
        } catch (_: Exception) {}
        return out
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

        // rule_set — the desktop config references local .srs files by ABSOLUTE
        // DESKTOP paths (C:\...\assets\geo\*.srs) that don't exist on the phone.
        // sing-box FATALs at startup trying to open them → tunnel never comes up.
        // Drop the rule_set declarations AND the route/dns rules that reference
        // them. The per-domain routing (the big domain_suffix snapshot + the
        // ColdBoot RU-direct entries baked into route.rules) stays intact and
        // covers the important cases; only geoip-based fallbacks are lost.
        stripRuleSets(root)

        // dns — surgically remove only the desktop loopback classifier
        // (razban-classify on 127.0.0.1:5354) if present, keeping the real
        // tagged servers (dns-direct/dns-proxy) intact. Replacing the whole
        // block broke `route.default_domain_resolver=dns-direct` (the tag
        // vanished → "default domain resolver not found").
        adaptDns(root)

        return root.toString(2)
    }

    private fun stripRuleSets(root: JSONObject) {
        root.optJSONObject("route")?.let {
            it.remove("rule_set")
            filterOutRuleSetRules(it)
        }
        root.optJSONObject("dns")?.let { filterOutRuleSetRules(it) }
    }

    private fun filterOutRuleSetRules(holder: JSONObject) {
        val rules = holder.optJSONArray("rules") ?: return
        val kept = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (r.has("rule_set")) continue   // references a now-removed .srs set
            kept.put(r)
        }
        holder.put("rules", kept)
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

    private fun adaptDns(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: run {
            // No dns block at all → add a minimal working one.
            root.put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("tag", "dns-direct").put("type", "udp").put("server", "8.8.8.8")))
                .put("final", "dns-direct").put("strategy", "ipv4_only"))
            return
        }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        val removedTags = HashSet<String>()
        val kept = JSONArray()
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            val addr = s.optString("server", s.optString("address", ""))
            val tag = s.optString("tag")
            // Drop the desktop loopback classifier (doesn't exist on Android).
            val isLoopback = addr.startsWith("127.") || addr.contains("localhost") ||
                addr.contains("5354") || tag.contains("classify", true) || tag.contains("razban", true)
            if (isLoopback) { if (tag.isNotEmpty()) removedTags.add(tag) } else kept.put(s)
        }
        if (kept.length() == 0) {
            kept.put(JSONObject().put("tag", "dns-direct").put("type", "udp").put("server", "8.8.8.8"))
            kept.put(JSONObject().put("tag", "dns-proxy").put("type", "tls").put("server", "1.1.1.1").put("detour", "proxy"))
        }
        dns.put("servers", kept)
        val firstTag = kept.optJSONObject(0)?.optString("tag")?.ifEmpty { "dns-direct" } ?: "dns-direct"

        // Repoint dns.final / route.default_domain_resolver if they referenced a removed server.
        if (removedTags.contains(dns.optString("final"))) dns.put("final", firstTag)
        root.optJSONObject("route")?.let { route ->
            if (removedTags.contains(route.optString("default_domain_resolver")))
                route.put("default_domain_resolver", firstTag)
        }
        // Drop dns rules pointing at removed servers.
        dns.optJSONArray("rules")?.let { rules ->
            val kr = JSONArray()
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                if (removedTags.contains(r.optString("server"))) continue
                kr.put(r)
            }
            dns.put("rules", kr)
        }
    }
}
