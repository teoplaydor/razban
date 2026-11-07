package com.razban.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.IOException;

/**
 * Exported seed endpoint (no MANAGE_DOCUMENTS) in the same test-package
 * process as {@link FakeProfileDocumentsProvider}. Written in Java so the
 * standalone test APK can run it without kotlin-stdlib.
 */
public class FakeProfileSeedProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle handled = handleWrite(method, arg, extras);
        return handled != null ? handled : super.call(method, arg, extras);
    }

    @Override
    public Bundle call(String authority, String method, String arg, Bundle extras) {
        Bundle handled = handleWrite(method, arg, extras);
        return handled != null ? handled : super.call(authority, method, arg, extras);
    }

    private Bundle handleWrite(String method, String arg, Bundle extras) {
        if (!FakeProfileDocumentsProvider.METHOD_WRITE.equals(method)) {
            return null;
        }
        String name = extras != null ? extras.getString("name") : null;
        if (name == null) {
            name = arg;
        }
        byte[] body = extras != null ? extras.getByteArray("body") : null;
        if (name == null || body == null) {
            return null;
        }
        try {
            FakeProfileDocumentsProvider.store(getContext(), name, body);
        } catch (IOException e) {
            return null;
        }
        Bundle out = new Bundle();
        out.putInt("bytes", body.length);
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
