package com.razban.app.diag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticBackoffToggleTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun event() = JSONObject()
        .put("stage", "smoke")
        .put("app_version", "0.1.44")
        .put("network_type", "wifi")
        .put("session_id", "aabbccddeeff00112233445566778899")

    @Test
    fun backoffGrowsThenResets() {
        var now = 0L
        val b = DiagnosticBackoff(longArrayOf(2_000, 5_000, 15_000), now = { now })
        assertTrue(b.canSend())
        assertEquals(2_000L, b.failure())
        assertFalse(b.canSend())
        now = 1_999
        assertFalse(b.canSend())
        now = 2_000
        assertTrue(b.canSend())
        assertEquals(5_000L, b.failure())
        now = 7_000
        assertTrue(b.canSend())
        b.success()
        assertEquals(0L, b.pendingDelayMs())
        assertTrue(b.canSend())
    }

    @Test
    fun toggleSkipsQueue() {
        val q = DiagnosticQueue(tmp.newFile("t.json"))
        assertFalse(DiagnosticGate.accept(false, event(), q))
        assertEquals(0, q.size())
        assertTrue(DiagnosticGate.accept(true, event(), q))
        assertEquals(1, q.size())
        assertFalse(DiagnosticGate.accept(true, event().put("uri", "https://example"), q))
        assertEquals(1, q.size())
    }
}
