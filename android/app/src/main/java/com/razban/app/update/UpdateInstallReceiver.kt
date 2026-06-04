package com.razban.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when the system DownloadManager finishes a download (manifest-registered,
 * so it runs even if the app was backgrounded/killed during the 124 MB pull).
 * Hands off to [AndroidUpdater.handleDownloadComplete], which verifies it's our
 * update + intact and surfaces the installer.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        try { AndroidUpdater.handleDownloadComplete(context, id) } catch (_: Exception) {}
    }
}
