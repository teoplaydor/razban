package com.razban.app.diag

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.razban.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object DiagnosticReporter {
    private const val PREFS = "razban_diag"
    private const val ENABLED = "upload_enabled"
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "razban-diag").apply { isDaemon = true }
    }
    private val flushing = AtomicBoolean(false)
    @Volatile private var lastStatus = "idle"
    @Volatile private var lastError: String? = null
    @Volatile private var lastOkAt: Long = 0
    private val backoff = DiagnosticBackoff()

    fun isConfigured(): Boolean =
        BuildConfig.DIAGNOSTIC_UPLOAD_URL.isNotBlank() && BuildConfig.DIAGNOSTIC_UPLOAD_HOST.isNotBlank()

    fun isEnabled(ctx: Context): Boolean {
        if (!isConfigured()) return false
        val prefs = prefs(ctx)
        if (prefs.contains(ENABLED)) return prefs.getBoolean(ENABLED, false)
        return BuildConfig.DIAGNOSTIC_UPLOAD_DEFAULT
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(ENABLED, enabled).apply()
        if (enabled) {
            DiagnosticCredentials.sessionId(ctx, rotate = true)
            lastStatus = "enabled"
            flushAsync(ctx)
        } else {
            lastStatus = "disabled"
            lastError = null
        }
    }

    fun statusJson(ctx: Context): JSONObject {
        val q = queue(ctx)
        return JSONObject()
            .put("configured", isConfigured())
            .put("enabled", isEnabled(ctx))
            .put("queued", q.size())
            .put("status", lastStatus)
            .put("lastError", lastError ?: JSONObject.NULL)
            .put("lastOkAt", lastOkAt)
            .put("ui", BuildConfig.DIAGNOSTIC_UPLOAD_UI)
            .put("host", if (isConfigured()) BuildConfig.DIAGNOSTIC_UPLOAD_HOST else "")
    }

    fun record(ctx: Context, stage: String, errorCode: String? = null, durationMs: Long? = null) {
        if (!isEnabled(ctx)) return
        if (stage !in DiagnosticSchema.STAGES) return
        val app = ctx.applicationContext
        val event = JSONObject()
            .put("stage", stage)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("network_type", networkType(app))
            .put("session_id", DiagnosticCredentials.sessionId(app))
        if (errorCode != null) event.put("error_code", errorCode)
        if (durationMs != null) event.put("duration_ms", durationMs)
        val clean = DiagnosticSchema.redact(event) ?: return
        queue(app).enqueue(clean)
        flushAsync(app)
    }

    fun flushAsync(ctx: Context) {
        if (!isEnabled(ctx) || !isConfigured()) return
        val app = ctx.applicationContext
        worker.execute { flushSync(app) }
    }

    internal fun flushSync(ctx: Context) {
        if (!flushing.compareAndSet(false, true)) return
        try {
            if (!isEnabled(ctx) || !isConfigured()) return
            if (!backoff.canSend()) {
                lastStatus = "backoff"
                return
            }
            val q = queue(ctx)
            if (q.size() == 0) {
                lastStatus = if (lastOkAt > 0) "ok" else "idle"
                return
            }
            val pem = loadCa(ctx) ?: run {
                lastStatus = "no_ca"
                lastError = "cert"
                return
            }
            val uploader = DiagnosticUploader(
                endpoint = BuildConfig.DIAGNOSTIC_UPLOAD_URL,
                caPem = pem,
                expectedHost = BuildConfig.DIAGNOSTIC_UPLOAD_HOST,
                deviceId = DiagnosticCredentials.deviceId(ctx),
                hmacKey = DiagnosticCredentials.hmacKey(ctx),
            )
            try {
                if (!DiagnosticCredentials.isEnrolled(ctx)) {
                    uploader.enroll()
                    DiagnosticCredentials.markEnrolled(ctx)
                }
                val batch = q.snapshot(10)
                if (batch.isEmpty()) return
                val sent = uploader.upload(batch)
                if (sent > 0) q.drop(sent)
                backoff.success()
                lastOkAt = System.currentTimeMillis()
                lastStatus = "ok"
                lastError = null
            } catch (e: DiagnosticUploadException) {
                lastError = e.kind
                lastStatus = e.kind
                if (e.kind == "auth") DiagnosticCredentials.clearEnrollment(ctx)
                backoff.failure()
            } catch (_: Exception) {
                lastError = "unknown"
                lastStatus = "error"
                backoff.failure()
            }
        } finally {
            flushing.set(false)
        }
    }

    internal fun queueForTest(file: File) = DiagnosticQueue(file)

    private fun queue(ctx: Context): DiagnosticQueue {
        val dir = File(ctx.applicationContext.filesDir, "diag")
        return DiagnosticQueue(File(dir, "queue.json"))
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadCa(ctx: Context): String? = try {
        ctx.applicationContext.assets.open("diag-ca.pem").bufferedReader().use { it.readText() }
            .takeIf { it.contains("BEGIN CERTIFICATE") }
    } catch (_: Exception) {
        null
    }

    private fun networkType(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = if (Build.VERSION.SDK_INT >= 23) cm.activeNetwork else return "other"
            val caps = cm.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) {
            "other"
        }
    }
}
