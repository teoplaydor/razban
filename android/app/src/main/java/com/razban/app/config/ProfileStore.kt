package com.razban.app.config

import android.content.Context
import com.razban.app.direct.DirectProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Multiple named profiles stored beside the existing current-config.json path.
 * RelayBaseline uses the ordinary import path; DirectTCPexperimental is separate.
 * Switching is manual — there is no automatic fallback.
 */
object ProfileStore {
    const val RELAY_BASELINE = "RelayBaseline"
    const val DIRECT_TCP = DirectProfile.DEFAULT_NAME
    private const val ACTIVE = "active-profile"
    private const val DIRECT_ACTIVE = "direct-config.json"

    data class Meta(val name: String, val kind: String, val summary: String)
    data class ImportResult(
        val ok: Boolean,
        val name: String? = null,
        val kind: String? = null,
        val count: Int = 0,
        val error: String? = null,
    )

    fun list(context: Context): List<Meta> {
        val dir = File(context.filesDir, "profiles")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { folder ->
                val meta = File(folder, "meta.json")
                if (!meta.exists()) return@mapNotNull null
                try {
                    val o = JSONObject(meta.readText())
                    Meta(o.getString("name"), o.getString("kind"), o.optString("summary"))
                } catch (_: Exception) { null }
            }
            .sortedBy { it.name }
    }

    fun listJson(context: Context): JSONObject {
        val active = activeName(context)
        val arr = JSONArray()
        for (p in list(context)) {
            arr.put(JSONObject()
                .put("name", p.name)
                .put("kind", p.kind)
                .put("summary", p.summary)
                .put("active", p.name == active))
        }
        return JSONObject().put("profiles", arr).put("active", active ?: JSONObject.NULL)
    }

    fun activeName(context: Context): String? {
        val f = File(context.filesDir, ACTIVE)
        return if (f.exists()) f.readText().trim().ifBlank { null } else null
    }

    fun isDirectActive(context: Context): Boolean {
        val name = activeName(context) ?: return File(context.filesDir, DIRECT_ACTIVE).exists()
        return list(context).firstOrNull { it.name == name }?.kind == DirectProfile.KIND ||
            File(context.filesDir, DIRECT_ACTIVE).exists() && name == DIRECT_TCP
    }

    fun currentDirect(context: Context): DirectProfile? {
        val active = File(context.filesDir, DIRECT_ACTIVE)
        if (!active.exists()) return null
        return try { hydrate(context, JSONObject(active.readText())) } catch (_: Exception) { null }
    }

    fun select(context: Context, name: String): Boolean {
        val folder = folder(context, name)
        val meta = File(folder, "meta.json")
        if (!meta.exists()) return false
        val kind = JSONObject(meta.readText()).optString("kind")
        val singbox = File(folder, "singbox.json")
        if (singbox.exists()) ConfigStore.importConfig(context, singbox.readText())
        val xray = File(folder, "xray.json")
        ConfigStore.setXrayConfig(context, if (xray.exists()) xray.readText() else null)
        val direct = File(folder, "direct.json")
        if (kind == DirectProfile.KIND && direct.exists()) {
            File(context.filesDir, DIRECT_ACTIVE).writeText(direct.readText())
        } else {
            File(context.filesDir, DIRECT_ACTIVE).delete()
        }
        File(context.filesDir, ACTIVE).writeText(name)
        return true
    }

    fun saveRelay(context: Context, name: String, singbox: String, xray: String?) {
        val safe = safeName(name.ifBlank { RELAY_BASELINE })
        val folder = folder(context, safe)
        folder.mkdirs()
        File(folder, "singbox.json").writeText(singbox)
        val xrayFile = File(folder, "xray.json")
        if (xray == null) xrayFile.delete() else xrayFile.writeText(xray)
        File(folder, "meta.json").writeText(
            JSONObject().put("name", safe).put("kind", "relay")
                .put("summary", "RelayBaseline").toString()
        )
        File(context.filesDir, ACTIVE).writeText(safe)
        File(context.filesDir, DIRECT_ACTIVE).delete()
    }

    fun saveDirect(context: Context, profile: DirectProfile) {
        val safe = safeName(profile.name.ifBlank { DIRECT_TCP })
        val folder = folder(context, safe)
        folder.mkdirs()
        val settings = profile.settingsJson()
        File(folder, "direct.json").writeText(settings.toString())
        File(folder, "singbox.json").writeText(DirectProfile.singboxViaSocks())
        File(folder, "xray.json").writeText(profile.xrayJson)
        File(folder, "meta.json").writeText(
            JSONObject().put("name", safe).put("kind", DirectProfile.KIND)
                .put("summary", "DirectTCPexperimental").toString()
        )
        ProfileSecrets.write(context, credFile(safe), profile.hmacKey)
        File(context.filesDir, DIRECT_ACTIVE).writeText(settings.toString())
        File(context.filesDir, ACTIVE).writeText(safe)
        ConfigStore.importConfig(context, DirectProfile.singboxViaSocks())
        ConfigStore.setXrayConfig(context, profile.xrayJson)
    }

    fun importPayload(context: Context, text: String): ImportResult {
        val trimmed = text.trim()
        if (trimmed.contains("\"razbanProfile\"") && trimmed.contains(DirectProfile.KIND)) {
            return try {
                val profile = DirectProfile.parse(trimmed)
                saveDirect(context, profile)
                ImportResult(true, profile.name, DirectProfile.KIND, 1)
            } catch (e: Exception) {
                ImportResult(false, error = e.message ?: "direct_import")
            }
        }
        if (trimmed.startsWith("{") && trimmed.contains("\"outbounds\"")) {
            ConfigStore.setXrayConfig(context, null)
            ConfigStore.importConfig(context, trimmed)
            val name = JSONObject(trimmed).optString("profileName").ifBlank { RELAY_BASELINE }
            saveRelay(context, name, File(context.filesDir, "current-config.json").readText(), null)
            return ImportResult(true, name, "relay", 1)
        }
        val parsed = UriImport.toImport(trimmed) ?: return ImportResult(false, error = "unsupported")
        ConfigStore.setXrayConfig(context, parsed.xray)
        ConfigStore.importConfig(context, parsed.singbox)
        val name = UriImport.profileName(trimmed)
        saveRelay(context, name, parsed.singbox, parsed.xray)
        return ImportResult(true, name, "relay", 1)
    }

    private fun hydrate(context: Context, settings: JSONObject): DirectProfile {
        val name = settings.optString("name").ifBlank { DIRECT_TCP }
        val stored = ProfileSecrets.read(context, credFile(name))
        if (stored == null) throw IllegalStateException("direct_credential_missing")
        val signal = settings.getJSONObject("signal")
        signal.put("hmacKeyHex", SignalHex.encode(stored))
        settings.put("signal", signal)
        return DirectProfile.parse(settings.toString())
    }

    private fun folder(context: Context, name: String) = File(File(context.filesDir, "profiles"), safeName(name))

    private fun credFile(name: String) = "profile-${safeName(name)}.hmac"

    private fun safeName(name: String): String {
        val cleaned = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
        return cleaned.ifBlank { RELAY_BASELINE }
    }
}

private object SignalHex {
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b))
    }
}
