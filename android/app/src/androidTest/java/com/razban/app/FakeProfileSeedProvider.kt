package com.razban.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exported seed endpoint (no MANAGE_DOCUMENTS) living in the same test-package
 * process as [FakeProfileDocumentsProvider], so instrumented tests running as
 * the app UID can install picker files the system DocumentsUI will open.
 */
class FakeProfileSeedProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return handleWrite(method, arg, extras) ?: super.call(method, arg, extras)
    }

    override fun call(authority: String, method: String, arg: String?, extras: Bundle?): Bundle? {
        return handleWrite(method, arg, extras) ?: super.call(authority, method, arg, extras)
    }

    private fun handleWrite(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != FakeProfileDocumentsProvider.METHOD_WRITE) return null
        val name = extras?.getString("name") ?: arg ?: return null
        val body = extras?.getByteArray("body") ?: return null
        FakeProfileDocumentsProvider.store(context, name, body)
        return Bundle().apply { putInt("bytes", body.size) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
