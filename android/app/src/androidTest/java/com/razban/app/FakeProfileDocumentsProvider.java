package com.razban.app;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java DocumentsProvider so the standalone test-package process can serve
 * files to system DocumentsUI. The androidTest APK does not package
 * kotlin-stdlib; a Kotlin provider crashes with NoClassDefFoundError
 * (Intrinsics) when bound from another process.
 *
 * Seeded via {@link FakeProfileSeedProvider} because the instrumented test
 * runs as the app UID and cannot write the test package filesDir.
 */
public class FakeProfileDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "com.razban.app.test.docs";
    public static final String SEED_AUTHORITY = "com.razban.app.test.docs.seed";
    public static final String TITLE = "RazbanTestDocs";
    public static final String METHOD_WRITE = "write";

    static final ConcurrentHashMap<String, byte[]> stored = new ConcurrentHashMap<String, byte[]>();

    private static final String ROOT = "root";
    private static final String[] DEFAULT_ROOT = new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
    };
    private static final String[] DEFAULT_DOC = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        String[] cols = projection != null ? projection : DEFAULT_ROOT;
        MatrixCursor c = new MatrixCursor(cols);
        MatrixCursor.RowBuilder row = c.newRow();
        put(row, cols, DocumentsContract.Root.COLUMN_ROOT_ID, ROOT);
        put(row, cols, DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT);
        put(row, cols, DocumentsContract.Root.COLUMN_TITLE, TITLE);
        put(row, cols, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        put(row, cols, DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        put(row, cols, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, 0L);
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        String[] cols = projection != null ? projection : DEFAULT_DOC;
        MatrixCursor c = new MatrixCursor(cols);
        addDoc(c, cols, documentId);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        String[] cols = projection != null ? projection : DEFAULT_DOC;
        MatrixCursor c = new MatrixCursor(cols);
        if (ROOT.equals(parentDocumentId)) {
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            names.addAll(stored.keySet());
            File[] files = dir(getContext()).listFiles();
            if (files != null) {
                for (File f : files) {
                    names.add(f.getName());
                }
            }
            for (String name : names) {
                addDoc(c, cols, name);
            }
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        byte[] body = stored.get(documentId);
        File f = new File(dir(getContext()), documentId);
        if (body != null && (!f.exists() || f.length() != body.length)) {
            try {
                writeBytes(f, body);
            } catch (IOException e) {
                throw new FileNotFoundException(documentId + ": " + e.getMessage());
            }
        }
        if (!f.exists()) {
            throw new FileNotFoundException(documentId);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getDocumentType(String documentId) {
        if (ROOT.equals(documentId)) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return mimeFor(documentId);
    }

    private void addDoc(MatrixCursor c, String[] cols, String id) {
        MatrixCursor.RowBuilder row = c.newRow();
        if (ROOT.equals(id)) {
            put(row, cols, DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT);
            put(row, cols, DocumentsContract.Document.COLUMN_DISPLAY_NAME, TITLE);
            put(row, cols, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR);
            put(row, cols, DocumentsContract.Document.COLUMN_FLAGS, 0);
            put(row, cols, DocumentsContract.Document.COLUMN_SIZE, 0L);
            put(row, cols, DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());
            return;
        }
        File f = new File(dir(getContext()), id);
        byte[] body = stored.get(id);
        long size = body != null ? body.length : (f.exists() ? f.length() : 0L);
        put(row, cols, DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        put(row, cols, DocumentsContract.Document.COLUMN_DISPLAY_NAME, id);
        put(row, cols, DocumentsContract.Document.COLUMN_MIME_TYPE, mimeFor(id));
        put(row, cols, DocumentsContract.Document.COLUMN_FLAGS, 0);
        put(row, cols, DocumentsContract.Document.COLUMN_SIZE, size);
        put(row, cols, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                f.exists() ? f.lastModified() : System.currentTimeMillis());
    }

    private static void put(MatrixCursor.RowBuilder row, String[] cols, String column, Object value) {
        for (String col : cols) {
            if (column.equals(col)) {
                row.add(value);
                return;
            }
        }
    }

    public static File dir(Context context) {
        File d = new File(context.getFilesDir(), "fake-docs");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static void store(Context context, String name, byte[] body) throws IOException {
        if (name == null || name.length() == 0 || name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) {
            throw new FileNotFoundException("bad document name");
        }
        stored.put(name, body);
        if (context != null) {
            File d = dir(context);
            if (d.isDirectory() || d.mkdirs()) {
                writeBytes(new File(d, name), body);
            }
        }
    }

    public static void write(Context context, String name, byte[] body) throws IOException {
        Bundle extras = new Bundle();
        extras.putString("name", name);
        extras.putByteArray("body", body);
        Uri uri = Uri.parse("content://" + SEED_AUTHORITY + "/write");
        Bundle result = null;
        try {
            result = context.getContentResolver().call(uri, METHOD_WRITE, name, extras);
        } catch (Exception ignored) {
            // Remote process crash / permission: fall through and report clearly.
        }
        if (result != null) {
            return;
        }
        store(context, name, body);
        File d = dir(context);
        if (!d.isDirectory()) {
            throw new FileNotFoundException(
                    "cannot seed " + name + " via provider IPC and " + d.getAbsolutePath() + " is missing");
        }
        writeBytes(new File(d, name), body);
    }

    public static String mimeFor(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static void writeBytes(File f, byte[] body) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(body);
        } finally {
            out.close();
        }
    }
}
