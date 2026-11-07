package com.razban.app.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps per-profile signalling credentials with an Android Keystore AES key.
 * Falls back to private app-storage bytes if the Keystore is unavailable —
 * still never written into the APK or logs.
 */
internal object ProfileSecrets {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "razban_direct_cred"
    private const val TAG = "razban-direct"

    fun write(context: Context, fileName: String, secret: ByteArray) {
        val file = context.getFileStreamPath(fileName)
        val blob = try { encrypt(secret) } catch (t: Throwable) {
            Log.w(TAG, "keystore wrap unavailable, using private file")
            secret
        }
        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { it.write(blob) }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    fun read(context: Context, fileName: String): ByteArray? {
        val raw = try { context.openFileInput(fileName).use { it.readBytes() } } catch (_: Exception) { return null }
        if (raw.isEmpty()) return null
        return try { decrypt(raw) } catch (_: Throwable) { raw }
    }

    fun delete(context: Context, fileName: String) {
        try { context.deleteFile(fileName) } catch (_: Exception) {}
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        val ivLen = blob[0].toInt() and 0xff
        if (ivLen !in 12..16 || blob.size <= 1 + ivLen) throw IllegalArgumentException("blob")
        val iv = blob.copyOfRange(1, 1 + ivLen)
        val ct = blob.copyOfRange(1 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
