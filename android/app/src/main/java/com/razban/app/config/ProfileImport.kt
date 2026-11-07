package com.razban.app.config

import android.content.Context
import android.net.Uri

/**
 * Shared import path for the native «Из файла» picker, ACTION_VIEW / ACTION_SEND,
 * and tests. Reads a bounded UTF-8 document and hands it to [ProfileStore.importPayload].
 * The payload itself is never logged.
 */
object ProfileImport {
    const val MAX_BYTES = 256 * 1024

    fun fromText(context: Context, text: String): ProfileStore.ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ProfileStore.ImportResult(false, error = "empty")
        return ProfileStore.importPayload(context, trimmed)
    }

    fun fromUri(context: Context, uri: Uri): ProfileStore.ImportResult {
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return ProfileStore.ImportResult(false, error = "unreadable")
            stream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BYTES) return ProfileStore.ImportResult(false, error = "too_large")
                fromText(context, bytes.toString(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            ProfileStore.ImportResult(false, error = "unreadable")
        }
    }
}
