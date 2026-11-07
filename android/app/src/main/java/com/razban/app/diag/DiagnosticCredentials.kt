package com.razban.app.diag

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Per-device ingest identity generated on the phone. Never a baked server key. */
object DiagnosticCredentials {
    private const val PREFS = "razban_diag"
    private const val ID = "device_id"
    private const val KEY = "hmac_key"
    private const val ENROLLED = "enrolled"
    private const val SESSION = "session_id"

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(ID, null)?.takeIf { it.length == 32 }?.let { return it }
        val id = randomHex(16)
        p.edit().putString(ID, id).apply()
        return id
    }

    fun hmacKey(ctx: Context): ByteArray {
        val p = prefs(ctx)
        p.getString(KEY, null)?.let { stored ->
            return try {
                Base64.decode(stored, Base64.NO_WRAP)
            } catch (_: Exception) {
                rotateKey(ctx)
            }
        }
        return rotateKey(ctx)
    }

    fun markEnrolled(ctx: Context) {
        prefs(ctx).edit().putBoolean(ENROLLED, true).apply()
    }

    fun isEnrolled(ctx: Context): Boolean = prefs(ctx).getBoolean(ENROLLED, false)

    fun clearEnrollment(ctx: Context) {
        prefs(ctx).edit().putBoolean(ENROLLED, false).apply()
    }

    fun sessionId(ctx: Context, rotate: Boolean = false): String {
        val p = prefs(ctx)
        if (!rotate) p.getString(SESSION, null)?.takeIf { it.length in 16..32 }?.let { return it }
        val id = randomHex(16)
        p.edit().putString(SESSION, id).apply()
        return id
    }

    fun sign(key: ByteArray, payload: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun rotateKey(ctx: Context): ByteArray {
        val raw = ByteArray(32)
        SecureRandom().nextBytes(raw)
        prefs(ctx).edit().putString(KEY, Base64.encodeToString(raw, Base64.NO_WRAP)).putBoolean(ENROLLED, false).apply()
        return raw
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
