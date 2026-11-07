package com.razban.app.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Turn a pasted server-share URI into runnable config(s).
 *
 * The Servers "Из буфера" button → servers.addMany previously only accepted a
 * full sing-box config JSON; a `vless://` link silently did nothing. This parses
 * the link and returns:
 *   - [Result.singbox]: the sing-box config that owns the TUN + routing.
 *   - [Result.xray]: an OPTIONAL xray config. For VLESS+Reality we DON'T build a
 *     sing-box reality outbound (sing-box's uTLS is broken in our libbox build →
 *     "failed to read client hello"); instead xray runs as a sidecar doing the
 *     Reality connection and exposing a local SOCKS, and the sing-box config just
 *     forwards the TUN into that SOCKS. For plain protocols xray is null and
 *     sing-box dials the server natively.
 *
 * Config shapes are validated against sing-box 1.13.12 (new dns server format,
 * dns hijacked via a route rule action — the removed `dns` outbound is unused).
 */
object UriImport {

    data class Result(val singbox: String, val xray: String?)

    fun profileName(raw: String): String {
        val line = raw.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) } ?: return "RelayBaseline"
        val name = fragmentName(line)
        return if (name.isBlank() || name == "Imported") "RelayBaseline" else name
    }

    fun toImport(raw: String): Result? {

        val line = raw.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("vless://", true) } ?: return null
        val v = parseVless(line) ?: return null
        val name = fragmentName(line)
        val sec = v.q["security"]
        return if (sec == "reality") {
            // xray sidecar does Reality; sing-box forwards the TUN into its SOCKS.
            Result(singboxViaSocks(), xrayReality(v))
        } else {
            val ob = nativeVlessOutbound(v) ?: return null
            Result(wrapNative(ob), null)
        }
    }

    // ───────────────────────── parse ─────────────────────────

    private class V(val host: String, val port: Int, val uuid: String, val q: Map<String, String>)

    // vless://<uuid>@<host>:<port>?<query>#<name>
    private fun parseVless(uri: String): V? = try {
        var body = uri.substring("vless://".length)
        val hash = body.indexOf('#'); if (hash >= 0) body = body.substring(0, hash)
        val qIdx = body.indexOf('?')
        val creds = if (qIdx >= 0) body.substring(0, qIdx) else body
        val query = if (qIdx >= 0) body.substring(qIdx + 1) else ""
        val at = creds.lastIndexOf('@')
        val uuid = creds.substring(0, at)
        val hp = creds.substring(at + 1)
        val colon = hp.lastIndexOf(':')
        val host = hp.substring(0, colon).trim('[', ']')
        val port = hp.substring(colon + 1).toInt()
        V(host, port, uuid, parseQuery(query))
    } catch (_: Exception) { null }

    private fun parseQuery(q: String): Map<String, String> {
        val m = HashMap<String, String>()
        if (q.isEmpty()) return m
        for (pair in q.split("&")) {
            val eq = pair.indexOf('='); if (eq < 0) continue
            val k = pair.substring(0, eq)
            val raw = pair.substring(eq + 1)
            val v = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
            if (k.isNotEmpty()) m[k] = v
        }
        return m
    }

    private fun fragmentName(uri: String): String {
        val h = uri.indexOf('#')
        if (h < 0 || h == uri.length - 1) return "Imported"
        return try { URLDecoder.decode(uri.substring(h + 1), "UTF-8") } catch (_: Exception) { "Imported" }
    }

    // ───────────────────── xray (Reality) ─────────────────────

    private fun xrayReality(v: V): String {
        val user = JSONObject().put("id", v.uuid).put("encryption", "none")
        v.q["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val vnext = JSONObject().put("address", v.host).put("port", v.port)
            .put("users", JSONArray().put(user))
        val rs = JSONObject()
            .put("serverName", v.q["sni"] ?: v.q["peer"] ?: v.host)
            .put("fingerprint", v.q["fp"] ?: "chrome")
        v.q["pbk"]?.let { rs.put("publicKey", it) }
        v.q["sid"]?.let { rs.put("shortId", it) }
        v.q["spx"]?.let { rs.put("spiderX", it) }
        val stream = JSONObject().put("network", v.q["type"] ?: "raw")
            .put("security", "reality").put("realitySettings", rs)
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
            .put("streamSettings", stream)
        val inbound = JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1")
            .put("port", 10808).put("protocol", "socks")
            .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .toString()
    }

    /** sing-box owns the TUN; all of it goes to the local xray SOCKS. */
    private fun singboxViaSocks(): String = JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("dns", JSONObject()
            .put("servers", JSONArray().put(JSONObject()
                .put("tag", "dns-remote").put("type", "tcp").put("server", "8.8.8.8")))
            .put("final", "dns-remote"))
        .put("inbounds", JSONArray().put(tunInbound()))
        .put("outbounds", JSONArray()
            .put(JSONObject().put("type", "socks").put("tag", "proxy")
                .put("server", "127.0.0.1").put("server_port", 10808).put("version", "5"))
            .put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", routeBlock())
        .toString()

    // ─────────────────── sing-box native ──────────────────────

    private fun nativeVlessOutbound(v: V): JSONObject? = try {
        val ob = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", v.host).put("server_port", v.port).put("uuid", v.uuid)
        v.q["flow"]?.takeIf { it.isNotEmpty() }?.let { ob.put("flow", it) }
        when (v.q["security"]) {
            "tls", "xtls" -> {
                val tls = JSONObject().put("enabled", true)
                    .put("server_name", v.q["sni"] ?: v.q["peer"] ?: v.host)
                v.q["alpn"]?.let { a ->
                    val arr = JSONArray(); a.split(",").forEach { if (it.isNotBlank()) arr.put(it.trim()) }
                    if (arr.length() > 0) tls.put("alpn", arr)
                }
                if (v.q["allowInsecure"] == "1" || v.q["insecure"] == "1") tls.put("insecure", true)
                v.q["fp"]?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
                ob.put("tls", tls)
            }
        }
        when (v.q["type"]) {
            "ws" -> {
                val tr = JSONObject().put("type", "ws")
                v.q["path"]?.let { tr.put("path", it) }
                v.q["host"]?.let { tr.put("headers", JSONObject().put("Host", it)) }
                ob.put("transport", tr)
            }
            "grpc" -> ob.put("transport", JSONObject().put("type", "grpc")
                .put("service_name", v.q["serviceName"] ?: v.q["path"] ?: ""))
        }
        ob
    } catch (_: Exception) { null }

    private fun wrapNative(outbound: JSONObject): String = JSONObject()
        .put("log", JSONObject().put("level", "warn"))
        .put("dns", JSONObject()
            .put("servers", JSONArray().put(JSONObject()
                .put("tag", "dns-remote").put("type", "udp").put("server", "8.8.8.8")))
            .put("final", "dns-remote"))
        .put("inbounds", JSONArray().put(tunInbound()))
        .put("outbounds", JSONArray().put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct")))
        .put("route", routeBlock())
        .toString()

    // ───────────────────────── shared ─────────────────────────

    private fun tunInbound(): JSONObject = JSONObject()
        .put("type", "tun").put("tag", "tun-in")
        .put("address", JSONArray().put("172.19.0.1/30"))
        .put("mtu", 1420).put("auto_route", true).put("stack", "gvisor")

    private fun routeBlock(): JSONObject = JSONObject()
        .put("rules", JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns")))
        .put("final", "proxy")
        .put("auto_detect_interface", true)
}
