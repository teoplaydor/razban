package com.razban.app.bg

import android.content.Context
import com.razban.app.config.ConfigStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Server-by-code. The user types a short code (e.g. "hub") and we pull the
 * matching server config from the Razban distribution host. The code is BOTH
 * the obscure lookup id AND the AES key material, so the encrypted blob on the
 * server is useless without the code — which is why the public APK can ship
 * WITHOUT embedding any connection secrets.
 *
 * Wire format — must stay byte-identical to `build/codes/make-code.py`:
 *   code_norm = code.trim().lowercase()                              (utf-8)
 *   key  = SHA256("razban-code-key/v1\n" + code_norm)         (32B AES-256 key)
 *   name = SHA256("razban-code-id/v1\n"  + code_norm).hex[:24] + ".bin"
 *   blob = nonce(12) || AES-256-GCM(key, nonce, configJSON, aad="razban-code/v1")
 */
object CoreCode {

    private const val BASE = "https://razban.huyb.ru/c/"
    private val AAD = "razban-code/v1".toByteArray(Charsets.UTF_8)

    /** Redeem a code: fetch + decrypt + import the config. Returns the number of
     *  outbounds imported. Throws with a short user-facing (RU) message. */
    fun redeem(ctx: Context, codeRaw: String): Int {
        val code = codeRaw.trim().lowercase()
        if (code.isEmpty()) throw IllegalArgumentException("Пустой код")

        val key = sha256("razban-code-key/v1\n".toByteArray(Charsets.UTF_8) + code.toByteArray(Charsets.UTF_8))
        val name = sha256("razban-code-id/v1\n".toByteArray(Charsets.UTF_8) + code.toByteArray(Charsets.UTF_8))
            .toHex().take(24) + ".bin"

        val blob = httpGet(BASE + name) ?: throw IllegalStateException("Код не найден")
        if (blob.size < 28) throw IllegalStateException("Повреждённые данные")

        val nonce = blob.copyOfRange(0, 12)
        val ct = blob.copyOfRange(12, blob.size)
        val json = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalStateException("Неверный код")   // GCM auth failure = wrong key
        }

        val root = JSONObject(json)   // also validates it parses
        val outs = root.optJSONArray("outbounds")?.length() ?: 0
        if (outs == 0) throw IllegalStateException("Конфиг без серверов")

        // adaptForAndroid runs inside importConfig; marks config as user-provided
        // so an app update won't clobber the code-imported server.
        ConfigStore.importConfig(ctx, json)
        return outs
    }

    private fun httpGet(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Razban-Android")
        }
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
