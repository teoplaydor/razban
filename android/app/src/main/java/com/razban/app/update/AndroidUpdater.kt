package com.razban.app.update

import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.razban.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app self-updater for Android.
 *
 * Flow: fetch the public update manifest (GitHub Pages) → if newer → prompt →
 * hand the APK to the system **DownloadManager** (background download that
 * SURVIVES the app being backgrounded/killed, shows a live progress notification,
 * resumes on network drops, and has no fixed read-timeout) → on completion a
 * manifest-registered [UpdateInstallReceiver] fires → we SHA-256-verify → post a
 * "ready, tap to install" notification (and best-effort auto-launch the installer).
 *
 * WHY DownloadManager (replaces the old HttpURLConnection+Thread): the 124 MB APK
 * over a throttled RU link to GitHub regularly tripped the 60 s readTimeout → the
 * download "timed out" and nothing installed, with no progress shown. DownloadManager
 * fixes all three (background, progress, resilience) for free.
 */
object AndroidUpdater {

    private const val TAG = "razban-update"
    private const val HEADER = "X-Razban-Update-Key"
    private const val PREFS = "razban"
    private const val CHANNEL = "razban-update"
    private const val NOTIF_READY = 4711
    private const val NOTIF_ERROR = 4712
    const val FILE_NAME = "update.apk"

    /** Check once and, if a newer build exists, prompt the user. Safe on every
     *  launch — failures are silent (never nag on a network blip). */
    suspend fun checkAndPrompt(activity: Activity) {
        val info = try { fetchManifest() } catch (e: Exception) {
            Log.d(TAG, "update check failed: ${e.message}"); return
        } ?: return
        if (info.versionCode <= currentVersionCode(activity)) {
            Log.d(TAG, "up to date (have ${currentVersionCode(activity)}, latest ${info.versionCode})")
            return
        }
        withContext(Dispatchers.Main) {
            if (activity.isFinishing) return@withContext
            AlertDialog.Builder(activity)
                .setTitle("Доступно обновление ${info.versionName}")
                .setMessage((info.notes ?: "Новая версия Razban.") + "\n\nСкачать и установить?")
                .setPositiveButton("Обновить") { _, _ -> startUpdate(activity, info) }
                .setNegativeButton(if (info.mandatory) "Позже" else "Не сейчас", null)
                .setCancelable(!info.mandatory)
                .show()
        }
    }

    private data class UpdateInfo(
        val versionCode: Int, val versionName: String,
        val url: String, val sha256: String?, val mandatory: Boolean, val notes: String?
    )

    private suspend fun fetchManifest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val c = (URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty(HEADER, BuildConfig.UPDATE_KEY)
            connectTimeout = 8000; readTimeout = 8000
        }
        try {
            if (c.responseCode != 200) return@withContext null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            UpdateInfo(
                versionCode = o.optInt("versionCode", -1),
                versionName = o.optString("versionName", "?"),
                url = o.optString("url"),
                sha256 = o.optString("sha256").ifEmpty { null },
                mandatory = o.optBoolean("mandatory", false),
                notes = o.optString("notes").ifEmpty { null }
            ).takeIf { it.versionCode > 0 && it.url.isNotEmpty() }
        } finally { c.disconnect() }
    }

    private fun startUpdate(activity: Activity, info: UpdateInfo) {
        // Android 8+: need permission to install packages. Send the user to grant
        // it once, then they re-tap Обновить.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Разрешите установку обновлений")
                .setMessage("Включите «Установка неизвестных приложений» для Razban, затем снова нажмите «Обновить».")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        enqueueDownload(activity, info)
    }

    private fun enqueueDownload(context: Context, info: UpdateInfo) {
        val dest = File(context.getExternalFilesDir(null), FILE_NAME)
        try { if (dest.exists()) dest.delete() } catch (_: Exception) {}
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.url)).apply {
            setTitle("Обновление Razban ${info.versionName}")
            setDescription("Загрузка обновления…")
            // Live progress notification while running, kept after completion.
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(dest))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType("application/vnd.android.package-archive")
            // Header is for the legacy private channel; harmless on the public GitHub URL.
            if (BuildConfig.UPDATE_KEY.isNotEmpty()) addRequestHeader(HEADER, BuildConfig.UPDATE_KEY)
        }
        val id = try { dm.enqueue(req) } catch (e: Exception) {
            Log.e(TAG, "enqueue failed", e); toast(context, "Не удалось начать загрузку"); return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("update_dl_id", id)
            .putString("update_sha", info.sha256 ?: "")
            .putString("update_ver", info.versionName)
            .apply()
        Log.i(TAG, "download enqueued id=$id ${info.url}")
        toast(context, "Загрузка обновления — прогресс в шторке уведомлений")
    }

    /** Called by [UpdateInstallReceiver] on ACTION_DOWNLOAD_COMPLETE. Verifies the
     *  finished download is ours + intact, then surfaces the installer. */
    fun handleDownloadComplete(context: Context, downloadId: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (downloadId != p.getLong("update_dl_id", -1L)) return    // not our download
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var ok = false
        try {
            dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cur ->
                if (cur.moveToFirst()) {
                    val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    ok = st == DownloadManager.STATUS_SUCCESSFUL
                    if (!ok) {
                        val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        Log.e(TAG, "download not successful: status=$st reason=$reason")
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "query failed", e) }
        if (!ok) { notifyError(context, "Не удалось скачать обновление. Нажмите для повтора."); return }

        val apk = File(context.getExternalFilesDir(null), FILE_NAME)
        if (!apk.exists() || apk.length() < 1_000_000L) {
            notifyError(context, "Файл обновления повреждён. Нажмите для повтора."); return
        }
        val sha = p.getString("update_sha", "") ?: ""
        if (sha.isNotEmpty() && !sha256Matches(apk, sha)) {
            Log.e(TAG, "sha256 mismatch"); try { apk.delete() } catch (_: Exception) {}
            notifyError(context, "Обновление повреждено (хеш). Нажмите для повтора."); return
        }
        Log.i(TAG, "download verified — surfacing installer")
        notifyReadyToInstall(context, apk, p.getString("update_ver", "") ?: "")
    }

    private fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun notifyReadyToInstall(context: Context, apk: File, ver: String) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(context, 1, installIntent(context, apk),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Обновление $ver готово")
            .setContentText("Нажмите, чтобы установить")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm(context).notify(NOTIF_READY, n)
        // Best-effort direct launch — works when a foreground task permits it
        // (otherwise the notification tap is the reliable path on Android 10+).
        try { context.startActivity(installIntent(context, apk)) } catch (_: Exception) {}
    }

    private fun notifyError(context: Context, msg: String) {
        ensureChannel(context)
        // Tapping retries: reopens the app so checkAndPrompt offers the update again.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = if (open != null) PendingIntent.getActivity(context, 2, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) else null
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Обновление Razban")
            .setContentText(msg)
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        nm(context).notify(NOTIF_ERROR, n)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Обновления", NotificationManager.IMPORTANCE_HIGH)
            nm(context).createNotificationChannel(ch)
        }
    }

    private fun nm(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun sha256Matches(f: File, expected: String): Boolean = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(65536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    } catch (_: Exception) { false }

    private fun currentVersionCode(activity: Activity): Int = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { Int.MAX_VALUE }

    private fun toast(context: Context, msg: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {}
    }
}
