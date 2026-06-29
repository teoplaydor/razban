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
        val base = if (f.exists()) f.readText()
        else {
            // Fall back to the bundled default (adapted), persisting it for next time.
            val raw = readBundledDefault(context) ?: return null
            try { val a = adaptForAndroid(raw); f.writeText(a); a } catch (_: Exception) { return null }
        }
        // Re-apply the RU direct splices (dns-ru + .ru/VK route + DoH-IP route) on
        // EVERY load. adaptForAndroid runs ONLY at import/seed, so a user whose 'hub'
        // config was adapted by an OLDER apk keeps a STALE RU adaptation (e.g. no
        // dns-ru at all → every .ru resolves via the tunnel → dead) until they
        // re-redeem. Freshening here makes an app UPDATE alone deliver the latest
        // RU/VK/DNS fixes — no re-redeem needed. THEN overlay the user's per-app /
        // per-domain picks (above the RU rules, so user pins win).
        return injectUserRoutes(freshenRuConfig(base), context)
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
                // ALWAYS set mtu (not just when out of range): if the field is omitted
                // sing-box falls back to its Android default tunMTU=9000 → jumbo-MTU
                // fragmentation blackhole (the desktop wintun saga). Clamp to ≤1500.
                val mtu = inb.optInt("mtu", 0)
                inb.put("mtu", if (mtu in 1..1500) mtu else 1420)
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

        // 🔴 Make RU domains RESOLVE on the real RU NIC (not via the tunnel). Without
        // this, dns.final=dns-proxy resolves yandex.ru from Helsinki → EU geo-IPs →
        // broken page. This is the actual "yandex.ru doesn't open" fix; ensureRuDirect
        // (below) only fixes egress, not resolution.
        ensureRuDirectDns(root)

        // Exact `domain` rules leak subdomains (e.g. cdn.discordapp.com wouldn't
        // match a "domain":["discordapp.com"] entry). Desktop ConfigBuilder emits
        // domain_suffix everywhere (inv #24); normalize here so a bundled/imported
        // config matches the host AND its subtree the same way.
        normalizeExactDomains(root)

        // Blanket RU ccTLD → direct (.ru / .su / .xn--p1ai = punycode .рф). Russian
        // domains must egress on the real RU IP — a foreign exit hits their reverse
        // geo-fence or just adds latency. This routes EVERY .ru direct from the FIRST
        // packet, instead of waiting for the runtime GeoClassifier to observe + pin
        // each host (the window where yandex.ru briefly tunneled). User pins
        // (injectUserRoutes) still splice ABOVE this, so an explicit override wins.
        ensureRuDirect(root)

        // Compact (not toString(2)) — the core ignores indentation, and pretty-printing
        // a 1000+-rule config on every connect is pure CPU waste on the hot path.
        return root.toString()
    }

    // RU ccTLDs + the RU-service CDNs/assets that live on FOREIGN TLDs. adaptForAndroid
    // strips the geosite rule_sets that cover these on desktop, and they aren't .ru, so
    // without this they tunnel → Yandex/VK/Mail.ru geo-fence or stall. Shared by the
    // ROUTE splice (egress direct) AND the DNS splice (resolve on the real RU NIC).
    private val ruDirectSuffixes = listOf(
        ".ru", ".su", ".xn--p1ai",
        // Yandex assets/CDN (foreign TLD — not caught by .ru):
        "yastatic.net", "yandex.net", "yandexcloud.net", "yastat.net",
        // VK family (incl. foreign-TLD helpers/CDNs that otherwise tunnel → VK lag;
        // userapi.com already covers the sun*-*.userapi.com video/photo cluster, and
        // vk.ru/vkvideo.ru are caught by the leading .ru blanket):
        "vk.com", "vk.me", "vkuser.net", "vk-cdn.net", "vkuservideo.net",
        "userapi.com", "mycdn.me", "vkuserlive.net", "vk-apps.com",
        "vk-portal.net", "vkcache.com", "vk-share.com", "mvk.com",
        "vk.team", "vkads.com", "okcdn.ru",
        // Mail.ru / marketplaces / maps:
        "my.com", "mradx.net", "avito.st", "wbstatic.net",
        "ozonusercontent.com", "2gis.com", "sber.ru",
        // RU banks — login flows hit non-.ru auth/CDN domains that otherwise tunnel →
        // the bank geo-fences the foreign exit (or the RU-Trusted-CA OCSP fetch tunnels
        // and fails) → login dies (the "в Сбербанк не смог войти" bug). The .ru bank
        // domains are already covered by ".ru"; these are the foreign-TLD ones.
        "sberbank.com", "sber.com", "sberdevices.com", "tbank.com", "vtb.com", "gazprombank.com",
        // 🔴 DoH/DoT resolver endpoints → MUST egress direct. Yandex Browser (and any
        // Chromium) does its OWN encrypted DNS (DoH/DoQ), bypassing our dns-ru splice.
        // A public DoH resolver is ANYCAST: if its endpoint tunnels, the browser hits the
        // Helsinki PoP → RU sites resolve to EU IPs → geo-fenced (only google.com, being
        // region-agnostic anycast, survives — the exact "только google.com работает" bug).
        // Routed direct, the browser's DoH hits the nearest (Moscow) PoP → RU-correct IPs.
        // DoH is encrypted so RU DPI can't poison it; foreign blocked sites still tunnel by
        // SNI/route.final. Covers the browser-DoH layer the system dns-ru can't reach.
        "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com", "family.cloudflare-dns.com", "one.one.one.one",
        "dns.google", "dns64.dns.google", "dns.quad9.net", "doh.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "common.dot.dns.yandex.net",
        "dns.yandex.ru", "secure.dns.yandex.ru"
    )

    /** Public DoH/DoT resolver IPs — the browser may use DoH/DoT BY IP (no domain to
     *  sniff), so a domain_suffix rule can't catch it. Pin these direct via ip_cidr so
     *  the encrypted query egresses on the real RU NIC → anycast resolves from Moscow →
     *  RU-correct answers. (1.1.1.1 is also dns-proxy's DoT target via detour:proxy — that
     *  is a server-level dial, unaffected by route.rules, so no conflict.) */
    private val dohDirectIps = listOf(
        // NOTE: deliberately EXCLUDES 1.1.1.1 / 1.0.0.1 — those are dns-proxy's DoT
        // target (detour:proxy, for resolving FOREIGN/blocked names via the tunnel to
        // dodge RU poisoning). Pinning them direct here could send dns-proxy's own
        // query out the RU NIC → RU DPI poisons blocked-domain DNS. The browser's
        // Cloudflare DoH is still covered by the hostname rule (cloudflare-dns.com /
        // one.one.one.one in ruDirectSuffixes), so dropping the raw IPs costs ~nothing.
        "8.8.8.8/32", "8.8.4.4/32",
        "9.9.9.9/32", "9.9.9.10/32", "149.112.112.112/32",
        "94.140.14.14/32", "94.140.15.15/32",
        "208.67.222.222/32", "208.67.220.220/32",
        "77.88.8.8/32", "77.88.8.1/32"
    )

    private fun ensureRuDirect(root: JSONObject) {
        val route = root.optJSONObject("route") ?: return
        val rules = route.optJSONArray("rules") ?: JSONArray()
        // idempotent — skip if a ".ru" direct suffix rule is already present
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (r.optString("outbound") != "direct") continue
            val ds = r.optJSONArray("domain_suffix") ?: continue
            for (j in 0 until ds.length()) if (ds.optString(j) == ".ru") return
        }
        // RU domains must EGRESS direct (a foreign exit geo-fences them). The sibling
        // ensureRuDirectDns makes them RESOLVE direct too — without BOTH, yandex.ru's
        // A-record is fetched via the tunnel and Yandex geo-DNS returns EU IPs = broken.
        // Spliced HIGH (before dpi-bypass/proxy) so it wins; user pins still go above.
        val ruRule = routeRule("domain_suffix", JSONArray(ruDirectSuffixes), "direct") ?: return
        // DoH/DoT-by-IP can't be matched by domain (no name to sniff) — pin the public
        // resolver IPs direct so the browser's encrypted DNS egresses on the RU NIC.
        val ipRule = routeRule("ip_cidr", JSONArray(dohDirectIps), "direct")
        // splice after the leading action rules (sniff / hijack-dns / anti-loop)
        val merged = JSONArray()
        var injected = false
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i)
            val isAction = r != null && (r.has("action") || r.optString("protocol").isNotEmpty())
            if (!injected && !isAction) {
                merged.put(ruRule)
                if (ipRule != null) merged.put(ipRule)
                injected = true
            }
            merged.put(rules.get(i))
        }
        if (!injected) { merged.put(ruRule); if (ipRule != null) merged.put(ipRule) }
        route.put("rules", merged)
    }

    /** 🔴 THE real "yandex.ru doesn't open" fix. The desktop's direct-DNS classifier
     *  is stripped on Android, so dns.final = dns-proxy (detour:proxy) → EVERY DNS
     *  query, incl. yandex.ru, is resolved FROM THE HELSINKI EXIT. Yandex/CDN geo-DNS
     *  then hands back EU edge IPs → the .ru page loads broken even though its TCP
     *  egress is direct (ensureRuDirect). Fix: a dedicated direct DNS server — Yandex
     *  77.88.8.8 (survives RU DPI + is RU-geo-correct), detour:"direct" so its own
     *  query egresses on the real RU NIC — plus a dns rule routing the RU suffixes to
     *  it. Foreign/blocked domains keep resolving via the tunnel (dns.final stays
     *  dns-proxy) to dodge RU DNS poisoning of blocked names. Idempotent. */
    private fun ensureRuDirectDns(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: return
        // The `direct` outbound must be NON-empty for a DNS server to detour to it —
        // sing-box FATALs at RUN on `detour:"direct"` to a bare {type:direct} ("empty
        // direct outbound makes no sense", inv #17.1). udp_fragment:false is a non-
        // default dial field that makes the struct non-zero (a true no-op otherwise).
        root.optJSONArray("outbounds")?.let { outs ->
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("type") == "direct" && o.optString("tag") == "direct" && !o.has("udp_fragment"))
                    o.put("udp_fragment", false)
            }
        }
        // independent_cache — isolate the per-server DNS caches (dns-ru / dns-proxy /
        // dns-direct) so a name cached under one server isn't served from another's
        // path. Desktop sets this; Android didn't. Faster repeat lookups, zero risk.
        if (!dns.has("independent_cache")) dns.put("independent_cache", true)
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        var hasRu = false
        for (i in 0 until servers.length())
            if (servers.optJSONObject(i)?.optString("tag") == "dns-ru") { hasRu = true; break }
        if (!hasRu) {
            servers.put(JSONObject()
                .put("tag", "dns-ru").put("type", "udp")
                .put("server", "77.88.8.8").put("detour", "direct"))
            dns.put("servers", servers)
        }
        val rules = dns.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until rules.length())
            if (rules.optJSONObject(i)?.optString("server") == "dns-ru") return   // already spliced
        val ruDnsRule = JSONObject()
            .put("domain_suffix", JSONArray(ruDirectSuffixes))
            .put("server", "dns-ru")
        val merged = JSONArray().put(ruDnsRule)   // prepend so RU resolution wins
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        dns.put("rules", merged)
    }

    /** Re-apply the RU splices fresh on every load (see currentConfigJson). Removes
     *  the prior dns-ru server + RU dns/route rules, then re-runs ensureRuDirectDns +
     *  ensureRuDirect so the LATEST ruDirectSuffixes/dohDirectIps win even on a config
     *  that an older apk adapted. Idempotent + safe on an unadapted config. */
    private fun freshenRuConfig(json: String): String = try {
        val root = JSONObject(json)
        removeRuSplices(root)
        ensureRuDirectDns(root)
        ensureRuDirect(root)
        root.toString()
    } catch (_: Exception) { json }

    private fun removeRuSplices(root: JSONObject) {
        root.optJSONObject("dns")?.let { dns ->
            dns.optJSONArray("servers")?.let { srv ->
                val keep = JSONArray()
                for (i in 0 until srv.length()) {
                    val s = srv.optJSONObject(i)
                    if (s != null && s.optString("tag").startsWith("dns-ru")) continue
                    keep.put(srv.get(i))
                }
                dns.put("servers", keep)
            }
            dns.optJSONArray("rules")?.let { rules ->
                val keep = JSONArray()
                for (i in 0 until rules.length()) {
                    val r = rules.optJSONObject(i)
                    if (r != null && r.optString("server").startsWith("dns-ru")) continue
                    keep.put(rules.get(i))
                }
                dns.put("rules", keep)
            }
        }
        root.optJSONObject("route")?.optJSONArray("rules")?.let { rules ->
            val keep = JSONArray()
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i)
                if (r != null && isOurRuSplice(r)) continue
                keep.put(rules.get(i))
            }
            root.optJSONObject("route")!!.put("rules", keep)
        }
    }

    /** Identify the route rules WE added (the blanket .ru/VK domain rule + the
     *  DoH-resolver-IP ip_cidr rule), so removeRuSplices drops only ours, never a
     *  ColdBoot/user direct rule. Signature is precise: the blanket rule contains
     *  .ru AND .su AND .xn--p1ai together; the ip rule contains 8.8.8.8/32. */
    private fun isOurRuSplice(r: JSONObject): Boolean {
        if (r.optString("outbound") != "direct") return false
        r.optJSONArray("domain_suffix")?.let { ds ->
            var ru = false; var su = false; var rf = false
            for (i in 0 until ds.length()) when (ds.optString(i)) {
                ".ru" -> ru = true; ".su" -> su = true; ".xn--p1ai" -> rf = true
            }
            if (ru && su && rf) return true
        }
        r.optJSONArray("ip_cidr")?.let { ic ->
            for (i in 0 until ic.length()) if (ic.optString(i) == "8.8.8.8/32") return true
        }
        return false
    }

    private fun normalizeExactDomains(root: JSONObject) {
        val route = root.optJSONObject("route") ?: return
        val rules = route.optJSONArray("rules") ?: return
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val dom = r.optJSONArray("domain") ?: continue
            val suffix = r.optJSONArray("domain_suffix") ?: JSONArray()
            for (j in 0 until dom.length()) suffix.put(dom.optString(j))
            r.put("domain_suffix", suffix)
            r.remove("domain")
        }
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

    /** Overlay the user's per-app (package_name) and per-domain (domain_suffix)
     *  route picks (saved in SharedPreferences by settings.set) onto the base
     *  config's route.rules. Priority, highest first, spliced ABOVE the baked
     *  ruleset but BELOW any leading action rules (sniff/hijack-dns):
     *    app pins beat domain rules (matches desktop inv. #1);
     *    bypass→direct, proxy & dpi→the tunnel (no byedpi on Android → dpi folds
     *    to VPN, same as stripDpiBypass does for the baked config).
     *  Idempotent + cheap: returns the input unchanged when nothing is pinned. */
    fun injectUserRoutes(json: String, context: Context): String {
        val ur = try {
            JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("userRoutes", "{}") ?: "{}")
        } catch (_: Exception) { return json }
        val keys = listOf("bypassApps", "proxyApps", "dpiApps",
                          "bypassDomains", "proxyDomains", "dpiDomains")
        if (keys.all { (ur.optJSONArray(it)?.length() ?: 0) == 0 }) return json
        return try {
            val root = JSONObject(json)
            val route = root.optJSONObject("route") ?: return json
            val existing = route.optJSONArray("rules") ?: JSONArray()
            val tunnel = primaryProxyTag(root)
            val userRules = JSONArray()
            // apps first — an app pin must beat any global domain rule
            routeRule("package_name", ur.optJSONArray("bypassApps"), "direct")?.let { userRules.put(it) }
            routeRule("package_name", ur.optJSONArray("proxyApps"), tunnel)?.let { userRules.put(it) }
            routeRule("package_name", ur.optJSONArray("dpiApps"), tunnel)?.let { userRules.put(it) }
            // domains next
            routeRule("domain_suffix", ur.optJSONArray("bypassDomains"), "direct")?.let { userRules.put(it) }
            routeRule("domain_suffix", ur.optJSONArray("proxyDomains"), tunnel)?.let { userRules.put(it) }
            routeRule("domain_suffix", ur.optJSONArray("dpiDomains"), tunnel)?.let { userRules.put(it) }
            // splice: leading action rules (sniff/hijack-dns) stay on top, then the
            // user rules, then everything else (the baked ColdBoot/itdoginfo rules).
            val merged = JSONArray()
            var injected = false
            for (i in 0 until existing.length()) {
                val r = existing.optJSONObject(i)
                val isAction = r != null && (r.has("action") || r.optString("protocol").isNotEmpty())
                if (!injected && !isAction) {
                    for (j in 0 until userRules.length()) merged.put(userRules.get(j))
                    injected = true
                }
                merged.put(existing.get(i))
            }
            if (!injected) for (j in 0 until userRules.length()) merged.put(userRules.get(j))
            route.put("rules", merged)
            android.util.Log.d("razban-config",
                "injectUserRoutes: layered ${userRules.length()} user route rule(s) (tunnel=$tunnel)")
            root.toString()   // compact — no pretty-print on the hot connect path
        } catch (_: Exception) { json }
    }

    private fun routeRule(field: String, arr: JSONArray?, outbound: String): JSONObject? {
        if (arr == null || arr.length() == 0) return null
        return JSONObject().put(field, arr).put("outbound", outbound)
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
