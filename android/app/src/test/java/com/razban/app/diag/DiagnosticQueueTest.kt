package com.razban.app.diag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun event(stage: String = "smoke") = JSONObject()
        .put("stage", stage)
        .put("app_version", "0.1.44")
        .put("network_type", "wifi")
        .put("session_id", "aabbccddeeff00112233445566778899")

    @Test
    fun persistsAndDropsOldest() {
        var now = 1_000_000L
        val q = DiagnosticQueue(tmp.newFile("q.json"), maxEvents = 2, maxAgeMs = 10_000, now = { now })
        q.enqueue(event("vpn_start"))
        q.enqueue(event("vpn_started"))
        q.enqueue(event("vpn_stop"))
        assertEquals(2, q.size())
        val snap = q.snapshot()
        assertEquals("vpn_started", snap[0].getString("stage"))
        assertEquals("vpn_stop", snap[1].getString("stage"))
        q.drop(1)
        assertEquals(1, q.size())
        assertEquals("vpn_stop", q.snapshot()[0].getString("stage"))
    }

    @Test
    fun expiresOldEvents() {
        var now = 1_000L
        val q = DiagnosticQueue(tmp.newFile("e.json"), maxEvents = 10, maxAgeMs = 100, now = { now })
        q.enqueue(event())
        now = 10_000L
        assertEquals(0, q.size())
    }

    @Test
    fun refusesUnredactedPayload() {
        val q = DiagnosticQueue(tmp.newFile("b.json"))
        q.enqueue(JSONObject().put("stage", "vpn_start").put("uri", "vless://x"))
        assertEquals(0, q.size())
    }
}
