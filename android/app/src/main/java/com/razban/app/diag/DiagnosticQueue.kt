package com.razban.app.diag

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Bounded offline queue. Drops oldest events when full or expired. */
class DiagnosticQueue(
    private val file: File,
    private val maxEvents: Int = 40,
    private val maxAgeMs: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Synchronized
    fun enqueue(event: JSONObject): Int {
        val clean = DiagnosticSchema.redact(event) ?: return size()
        val root = load()
        val items = root.getJSONArray("items")
        items.put(JSONObject().put("queued_at", now()).put("event", clean))
        persist(prune(items))
        return size()
    }

    @Synchronized
    fun snapshot(limit: Int = 10): List<JSONObject> {
        val items = prune(load().getJSONArray("items"))
        val out = ArrayList<JSONObject>()
        for (i in 0 until minOf(limit, items.length())) {
            items.optJSONObject(i)?.optJSONObject("event")?.let { out.add(JSONObject(it.toString())) }
        }
        return out
    }

    @Synchronized
    fun drop(count: Int) {
        val items = prune(load().getJSONArray("items"))
        val kept = JSONArray()
        for (i in count until items.length()) kept.put(items.get(i))
        persist(kept)
    }

    @Synchronized
    fun clear() {
        persist(JSONArray())
    }

    @Synchronized
    fun size(): Int = prune(load().getJSONArray("items")).length()

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject().put("items", JSONArray())
        return try {
            JSONObject(file.readText()).also { if (!it.has("items")) it.put("items", JSONArray()) }
        } catch (_: Exception) {
            JSONObject().put("items", JSONArray())
        }
    }

    private fun prune(items: JSONArray): JSONArray {
        val cutoff = now() - maxAgeMs
        val kept = JSONArray()
        for (i in 0 until items.length()) {
            val row = items.optJSONObject(i) ?: continue
            if (row.optLong("queued_at") < cutoff) continue
            if (row.optJSONObject("event") == null) continue
            kept.put(row)
        }
        val start = (kept.length() - maxEvents).coerceAtLeast(0)
        if (start == 0) return kept
        val trimmed = JSONArray()
        for (i in start until kept.length()) trimmed.put(kept.get(i))
        return trimmed
    }

    private fun persist(items: JSONArray) {
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("items", items).toString())
    }
}
