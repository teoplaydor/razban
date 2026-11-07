package com.razban.app.diag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticSchemaTest {
    private fun good(): JSONObject = JSONObject()
        .put("stage", "smoke")
        .put("error_code", "timeout")
        .put("duration_ms", 1600)
        .put("app_version", "0.1.44")
        .put("network_type", "wifi")
        .put("session_id", "aabbccddeeff00112233445566778899")

    @Test
    fun keepsAllowlistedEvent() {
        val out = DiagnosticSchema.redact(good())
        assertNotNull(out)
        assertEquals("smoke", out!!.getString("stage"))
        assertEquals("timeout", out.getString("error_code"))
        assertEquals(1600L, out.getLong("duration_ms"))
        assertEquals(6, out.length())
    }

    @Test
    fun dropsUriAndSecrets() {
        assertNull(DiagnosticSchema.redact(good().put("uri", "vless://secret")))
        assertNull(DiagnosticSchema.redact(good().put("password", "x")))
        assertNull(DiagnosticSchema.redact(good().put("config", "{}")))
        assertNull(DiagnosticSchema.redact(good().put("dns", "1.1.1.1")))
        assertNull(DiagnosticSchema.redact(JSONObject(good().toString()).put("stage", "https://evil.example/path")))
        val sneaky = good().put("app_version", "vless://abcd")
        assertNull(DiagnosticSchema.redact(sneaky))
    }

    @Test
    fun dropsUnknownFieldsAndBadEnums() {
        assertNull(DiagnosticSchema.redact(good().put("extra", "nope")))
        assertNull(DiagnosticSchema.redact(good().put("stage", "dns_lookup")))
        assertNull(DiagnosticSchema.redact(good().put("error_code", "ECONNREFUSED")))
        assertNull(DiagnosticSchema.redact(good().put("network_type", "ssid:HomeWifi")))
        assertNull(DiagnosticSchema.redact(good().put("session_id", "not-hex!!not-hex!!")))
        assertNull(DiagnosticSchema.redact(good().put("duration_ms", 9_999_999)))
    }
}
