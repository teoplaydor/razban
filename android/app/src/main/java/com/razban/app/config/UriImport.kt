package com.razban.app.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turn a pasted server-share URI into a complete sing-box 1.13 config JSON.
 *
 * Why this exists: the Servers page "Из буфера" button calls servers.addMany,
 * and the Android bridge previously ONLY accepted a full sing-box config JSON —
 * a `vless://` link silently did nothing (count:0, no error, no server). This
 * parses the link, builds a working config (tun + dns + route), and hands it to
 * ConfigStore.importConfig, which adapts + persists it as the active config.
 *
 * Supported now: vless:// (reality / tls, tcp·raw·ws·grpc·httpupgrade transports).
 * Other schemes return null so the caller can report "not recognised".
 *
 * Config shape is kept byte-for-schema identical to what `sing-box check`
 * accepts on 1.13.12: NEW dns server format ({type,server}), dns hijacked via a
 * route rule action (the 1.13-removed `dns` outbound is NOT used).
 */
object UriImport {

    /** First supported URI found in [raw] → full config JSON, or null. */
    fun toConfig(raw: String): String? {
        val line = raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) }
            ?: return null
        val outbound = parseVless(line) ?: return null
        return wrap(outbound, nameFromFragment(line))
    }

    private fun nameFromFragment(uri: String): String {
        val h = uri.indexOf('#')
        if (h < 0 || h == uri.length - 1) return "Imported"
        return try { URLDecoder.decode(uri.substring(h + 1), "UTF-8") } catch (_: Exception) { "Imported" }
    }

    // vless://<uuid>@<host>:<port>?<query>#<name>
    private fun parseVless(uri: String): JSONObject? {
        return try {
            var body = uri.substring("vless://".length)
            val hash = body.indexOf('#'); if (hash >= 0) body = body.substring(0, hash)
            val qIdx = body.indexOf('?')
            val creds = if (qIdx >= 0) body.substring(0, qIdx) else body
            val query = if (qIdx >= 0) body.substring(qIdx + 1) else ""
            val at = creds.lastIndexOf('@'); if (at < 0) return null
            val uuid = creds.substring(0, at)
            val hp = creds.substring(at + 1)
            val colon = hp.lastIndexOf(':'); if (colon < 0) return null
            val host = hp.substring(0, colon).trim('[', ']')
            val port = hp.substring(colon + 1).toIntOrNull() ?: return null
            val p = parseQuery(query)

            val ob = JSONObject()
                .put("type", "vless").put("tag", "proxy")
                .put("server", host).put("server_port", port).put("uuid", uuid)
            p["flow"]?.takeIf { it.isNotEmpty() }?.let { ob.put("flow", it) }

            when (p["security"]) {
                "reality", "tls", "xtls" -> {
                    val tls = JSONObject().put("enabled", true)
                        .put("server_name", p["sni"] ?: p["peer"] ?: host)
                    p["alpn"]?.let { a ->
                        val arr = JSONArray(); a.split(",").forEach { if (it.isNotBlank()) arr.put(it.trim()) }
                        if (arr.length() > 0) tls.put("alpn", arr)
                    }
                    if (p["allowInsecure"] == "1" || p["insecure"] == "1") tls.put("insecure", true)
                    tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", p["fp"] ?: "chrome"))
                    if (p["security"] == "reality") {
                        val r = JSONObject().put("enabled", true)
                        p["pbk"]?.let { r.put("public_key", it) }
                        p["sid"]?.let { r.put("short_id", it) }
                        tls.put("reality", r)
                    }
                    ob.put("tls", tls)
                }
            }

            when (p["type"] ?: "tcp") {
                "ws" -> {
                    val tr = JSONObject().put("type", "ws")
                    p["path"]?.let { tr.put("path", it) }
                    p["host"]?.let { tr.put("headers", JSONObject().put("Host", it)) }
                    ob.put("transport", tr)
                }
                "grpc" -> ob.put("transport", JSONObject().put("type", "grpc")
                    .put("service_name", p["serviceName"] ?: p["path"] ?: ""))
                "httpupgrade" -> {
                    val tr = JSONObject().put("type", "httpupgrade")
                    p["path"]?.let { tr.put("path", it) }
                    p["host"]?.let { tr.put("host", it) }
                    ob.put("transport", tr)
                }
                // "tcp" / "raw" → plain, no transport block
            }
            ob
        } catch (_: Exception) { null }
    }

    private fun parseQuery(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (q.isEmpty()) return m
        for (pair in q.split("&")) {
            val eq = pair.indexOf('='); if (eq < 0) continue
            val k = pair.substring(0, eq)
            val v = try { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") } catch (_: Exception) { pair.substring(eq + 1) }
            if (k.isNotEmpty()) m[k] = v
        }
        return m
    }

    private fun wrap(outbound: JSONObject, name: String): String {
        outbound.put("tag", "proxy")   // route.final points here
        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("dns", JSONObject()
                .put("servers", JSONArray().put(JSONObject()
                    .put("tag", "dns-remote").put("type", "udp").put("server", "8.8.8.8")))
                .put("final", "dns-remote"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in")
                .put("address", JSONArray().put("172.19.0.1/30"))
                .put("mtu", 1420).put("auto_route", true).put("stack", "gvisor")))
            .put("outbounds", JSONArray()
                .put(outbound)
                .put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject()
                .put("rules", JSONArray()
                    .put(JSONObject().put("action", "sniff"))
                    .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))
                .put("final", "proxy")
                .put("auto_detect_interface", true))
            .toString()
    }
}
