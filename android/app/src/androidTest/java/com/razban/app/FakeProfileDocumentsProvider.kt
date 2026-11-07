package com.razban.app

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * DocumentsProvider that serves mock profile files (json / txt / wrong MIME /
 * missing MIME / oversized / invalid) so UIAutomator can pick them from the
 * real system picker. No live phone profile and no secrets.
 *
 * Files are seeded from the instrumented test process via
 * [FakeProfileSeedProvider] (same test-package process, no MANAGE_DOCUMENTS
 * gate). Writing directly into the test package filesDir from the app UID
 * fails with ENOENT.
 */
class FakeProfileDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT
        val c = MatrixCursor(cols)
        val row = c.newRow()
        put(row, cols, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT)
        put(row, cols, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT)
        put(row, cols, DocumentsContract.Root.COLUMN_TITLE, TITLE)
        put(row, cols, DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
        put(row, cols, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        put(row, cols, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 0L)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_DOC
        val c = MatrixCursor(cols)
        addDoc(c, cols, documentId)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection ?: DEFAULT_DOC
        val c = MatrixCursor(cols)
        if (parentDocumentId == ROOT) {
            val names = LinkedHashSet<String>()
            names.addAll(stored.keys)
            dir(context).listFiles()?.forEach { names.add(it.name) }
            names.forEach { addDoc(c, cols, it) }
        }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val body = stored[documentId]
        val f = File(dir(context), documentId)
        if (body != null && (!f.exists() || f.length().toInt() != body.size)) {
            f.writeBytes(body)
        }
        if (!f.exists()) throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getDocumentType(documentId: String): String {
        if (documentId == ROOT) return DocumentsContract.Document.MIME_TYPE_DIR
        return mimeFor(documentId)
    }

    private fun addDoc(c: MatrixCursor, cols: Array<out String>, id: String) {
        val row = c.newRow()
        if (id == ROOT) {
            put(row, cols, DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT)
            put(row, cols, DocumentsContract.Document.COLUMN_DISPLAY_NAME, TITLE)
            put(row, cols, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            put(row, cols, DocumentsContract.Document.COLUMN_FLAGS, 0)
            put(row, cols, DocumentsContract.Document.COLUMN_SIZE, 0L)
            put(row, cols, DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            return
        }
        val f = File(dir(context), id)
        val size = stored[id]?.size?.toLong() ?: if (f.exists()) f.length() else 0L
        put(row, cols, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        put(row, cols, DocumentsContract.Document.COLUMN_DISPLAY_NAME, id)
        put(row, cols, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeFor(id))
        put(row, cols, DocumentsContract.Document.COLUMN_FLAGS, 0)
        put(row, cols, DocumentsContract.Document.COLUMN_SIZE, size)
        put(row, cols, DocumentsContract.Document.COLUMN_LAST_MODIFIED, if (f.exists()) f.lastModified() else System.currentTimeMillis())
    }

    private fun put(row: MatrixCursor.RowBuilder, cols: Array<out String>, column: String, value: Any) {
        if (column in cols) row.add(value)
    }

    companion object {
        const val AUTHORITY = "com.razban.app.test.docs"
        const val SEED_AUTHORITY = "com.razban.app.test.docs.seed"
        const val TITLE = "RazbanTestDocs"
        const val METHOD_WRITE = "write"
        private const val ROOT = "root"
        internal val stored = ConcurrentHashMap<String, ByteArray>()
        private val DEFAULT_ROOT = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DEFAULT_DOC = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun dir(context: Context?): File {
            val d = File(context!!.filesDir, "fake-docs")
            if (!d.isDirectory) d.mkdirs()
            return d
        }

        fun store(context: Context?, name: String, body: ByteArray) {
            if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                throw FileNotFoundException("bad document name")
            }
            stored[name] = body
            if (context != null) {
                val d = dir(context)
                if (d.isDirectory || d.mkdirs()) {
                    File(d, name).writeBytes(body)
                }
            }
        }

        fun write(context: Context, name: String, body: ByteArray) {
            val extras = Bundle().apply {
                putString("name", name)
                putByteArray("body", body)
            }
            val uri = Uri.parse("content://$SEED_AUTHORITY/write")
            val result = try {
                context.contentResolver.call(uri, METHOD_WRITE, name, extras)
            } catch (_: Exception) {
                null
            }
            if (result != null) return
            store(context, name, body)
            val d = dir(context)
            if (!d.isDirectory) {
                throw FileNotFoundException(
                    "cannot seed $name via provider IPC and ${d.absolutePath} is missing",
                )
            }
            File(d, name).writeBytes(body)
        }

        fun mimeFor(name: String): String = when {
            name.endsWith(".json") -> "application/json"
            name.endsWith(".txt") -> "text/plain"
            name.endsWith(".bin") -> "application/octet-stream"
            name.endsWith(".unknown") -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }
}
