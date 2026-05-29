package com.razban.app.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
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
 * In-app self-updater for Android, over a PRIVATE channel: the update manifest
 * and APK live behind a secret header (Caddy 403s anyone without it), so the
 * build — which embeds the bundle config — is never publicly downloadable.
 *
 * Flow: fetch update.json (with the secret header) → if its versionCode is
 * newer than ours → prompt → download the APK (same header) → verify SHA-256 →
 * launch the system installer. On Android 8+ the user grants "install unknown
 * apps" for Razban once; thereafter updates are one-tap.
 *
 * Manifest shape (update.json):
 *   { "versionCode": 7, "versionName": "0.1.7",
 *     "url": "https://razban.huyb.ru/app-update/Razban-0.1.7.apk",
 *     "sha256": "…", "mandatory": false, "notes": "…" }
 */
object AndroidUpdater {

    private const val TAG = "razban-update"
    private const val HEADER = "X-Razban-Update-Key"

    /** Check once and, if a newer build exists, prompt the user. Safe to call
     *  on every launch — failures are silent (never nag on a network blip). */
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
        // Android 8+: the app needs permission to install packages. Send the
        // user to grant it once, then they re-tap Обновить.
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
        android.widget.Toast.makeText(activity, "Загрузка обновления…", android.widget.Toast.LENGTH_SHORT).show()
        Thread {
            val apk = try { download(activity, info) } catch (e: Exception) {
                Log.e(TAG, "download failed", e); toast(activity, "Ошибка загрузки обновления"); null
            } ?: return@Thread
            if (info.sha256 != null && !sha256Matches(apk, info.sha256)) {
                Log.e(TAG, "sha256 mismatch"); toast(activity, "Обновление повреждено (хеш не совпал)")
                apk.delete(); return@Thread
            }
            install(activity, apk)
        }.start()
    }

    private fun download(activity: Activity, info: UpdateInfo): File {
        val out = File(activity.cacheDir, "update.apk")
        if (out.exists()) out.delete()
        val c = (URL(info.url).openConnection() as HttpURLConnection).apply {
            setRequestProperty(HEADER, BuildConfig.UPDATE_KEY)
            connectTimeout = 15000; readTimeout = 60000
        }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        } finally { c.disconnect() }
        return out
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try { activity.startActivity(intent) }
        catch (e: Exception) { Log.e(TAG, "install intent failed", e); toast(activity, "Не удалось запустить установку") }
    }

    private fun sha256Matches(f: File, expected: String): Boolean = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(65536); while (true) { val n = ins.read(b); if (n < 0) break; md.update(b, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    } catch (_: Exception) { false }

    private fun currentVersionCode(activity: Activity): Int = try {
        val pi = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    } catch (_: Exception) { Int.MAX_VALUE }

    private fun toast(activity: Activity, msg: String) =
        activity.runOnUiThread { android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_LONG).show() }
}
