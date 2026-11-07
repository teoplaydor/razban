package com.razban.app.diag

import org.json.JSONObject

/** Pure record gate used by the reporter and by unit tests. */
object DiagnosticGate {
    fun accept(enabled: Boolean, raw: JSONObject, queue: DiagnosticQueue): Boolean {
        if (!enabled) return false
        val clean = DiagnosticSchema.redact(raw) ?: return false
        queue.enqueue(clean)
        return true
    }
}
