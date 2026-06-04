package com.razban.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the MECHANISM the rewritten AndroidUpdater relies on: the system
 * DownloadManager actually downloads a file to getExternalFilesDir and reports
 * STATUS_SUCCESSFUL — i.e. the download path that used to "time out" with the old
 * HttpURLConnection+60s-readTimeout now works (background, resumable, no fixed
 * timeout). A US CI runner downloads fine; the RU-throttle resilience is
 * DownloadManager's design feature. (Full updater flow needs a version mismatch +
 * the install dialog, which is interactive — out of scope for an emulator gate.)
 */
class UpdaterDownloadTest {

    @Test
    fun downloadManager_fetches_a_file() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val dest = File(ctx.getExternalFilesDir(null), "dltest.bin")
        if (dest.exists()) dest.delete()

        // Small, reliable public file — same host family as the real update channel.
        val url = "https://teoplaydor.github.io/razban/version.json"
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(dest))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val id = dm.enqueue(req)
        android.util.Log.i("razban-test", "updater-dl: enqueued id=$id")

        var status = -1
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) break
            Thread.sleep(1000)
        }

        android.util.Log.i("razban-test",
            "updater-dl: status=$status exists=${dest.exists()} size=${dest.length()}")
        assertTrue("DownloadManager did not complete (status=$status)", status == DownloadManager.STATUS_SUCCESSFUL)
        assertTrue("downloaded file missing/empty", dest.exists() && dest.length() > 0)
        dest.delete()
    }
}
