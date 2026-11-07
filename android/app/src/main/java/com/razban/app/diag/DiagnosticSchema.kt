package com.razban.app.diag

import org.json.JSONArray
import org.json.JSONObject

/**
 * Allowlisted diagnostic events only. Anything else is dropped before it is
 * queued or sent — including URIs, configs, secrets, DNS and traffic content.
 */
object DiagnosticSchema {
    const val SCHEMA = 1
    val STAGES = setOf(
        "vpn_start", "vpn_started", "vpn_stop", "vpn_failed",
        "xray_start", "xray_ok", "xray_failed",
        "direct_start", "direct_joined", "direct_armed", "direct_connected",
        "direct_stream_finished", "direct_stream_error", "direct_fail_closed",
        "import_ok", "import_failed", "profile_select", "smoke",
    )
    val ERRORS = setOf(
        "no_config", "xray_start_failed", "no_underlying_network", "protect_failed",
        "plan_mismatch", "signal_failed", "timeout", "io_error", "unknown",
    )
    val NETWORKS = setOf("wifi", "cellular", "ethernet", "none", "other")
    val FIELDS = setOf("stage", "error_code", "duration_ms", "app_version", "network_type", "session_id")
    private val FORBIDDEN_KEYS = setOf(
        "uri", "url", "query", "config", "json", "key", "password", "secret", "token",
        "dns", "sni", "host", "hostname", "uuid", "pem", "hmac", "authorization",
        "cookie", "header", "payload", "body", "log", "logcat", "packet", "domain",
        "server", "peer", "bind",
    )
    private val FORBIDDEN_VALUE = Regex(
        """(?i)(vless://|https?://|dns:|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|BEGIN CERTIFICATE|[?&](uuid|password|token|key)=)"""
    )

    fun redact(raw: JSONObject): JSONObject? {
        val keys = raw.keys().asSequence().toList()
        if (keys.any { it.lowercase() in FORBIDDEN_KEYS }) return null
        val stage = raw.optString("stage")
        if (stage !in STAGES) return null
        val out = JSONObject().put("stage", stage)
        if (raw.has("error_code") && !raw.isNull("error_code")) {
            val code = raw.optString("error_code")
            if (code.isNotEmpty() && code !in ERRORS) return null
            if (code.isNotEmpty()) out.put("error_code", code)
        }
        if (raw.has("duration_ms") && !raw.isNull("duration_ms")) {
            val dur = raw.optLong("duration_ms", -1)
            if (dur !in 0..3_600_000L) return null
            out.put("duration_ms", dur)
        }
        val version = raw.optString("app_version")
        if (version.isEmpty() || version.length > 32 || FORBIDDEN_VALUE.containsMatchIn(version)) return null
        out.put("app_version", version)
        val net = raw.optString("network_type")
        if (net !in NETWORKS) return null
        out.put("network_type", net)
        val session = raw.optString("session_id")
        if (session.length !in 16..32 || session.any { it !in "0123456789abcdef" }) return null
        out.put("session_id", session)
        for (key in keys) {
            if (key !in FIELDS) return null
            val value = raw.opt(key)?.toString() ?: continue
            if (FORBIDDEN_VALUE.containsMatchIn(value)) return null
        }
        return out
    }

    fun redactBatch(arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            redact(item)?.let { out.put(it) }
        }
        return out
    }
}
