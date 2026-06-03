package com.razban.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.razban.app.bg.RazbanVpnService
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Открываются ли сайты" — end-to-end real-traffic check. Connects with the REAL
 * bundled config (the production VPS, no synthetic import — relies on the
 * default-config.json the CI injects into assets), then fetches real websites and
 * asserts they actually load:
 *
 *  • tunneled sites (youtube/google/github) prove the proxy path carries real
 *    traffic end-to-end (not just a handshake);
 *  • the direct-routed site (ya.ru) proves DIRECT egress still has internet while
 *    the VPN is up — the "direct apps have no internet" regression class.
 *
 * Needs network (the CI runner has it). VPN consent is pre-granted by the harness.
 */
@RunWith(AndroidJUnit4::class)
class SiteAccessTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        // Foreground so starting a foreground service is allowed (Android 12+).
        shell("am start -n ${ctx.packageName}/com.razban.app.ui.MainActivity")
        Thread.sleep(1500)
    }

    @Test
    fun sitesOpenThroughVpn() {
        // Baseline (no VPN): confirm the emulator has internet at all.
        val baseline = httpGet("https://www.google.com/")
        android.util.Log.i("razban-core", "site-access baseline (no VPN) google = $baseline")

        connectAndWait()

        val sites = listOf(
            Triple("https://www.youtube.com/", "tunneled", 0),
            Triple("https://www.google.com/", "tunneled", 0),
            Triple("https://github.com/", "tunneled", 0),
            Triple("https://ya.ru/", "direct", 0),
        )
        val results = sites.map { (url, kind, _) ->
            val code = httpGet(url)
            android.util.Log.i("razban-core", "site-access [$kind] $url -> $code")
            Triple(url, kind, code)
        }
        // ── GeoClassifier: sustain traffic to a RU-hosted domain so the runtime
        //    classifier observes its RU IP and pins it DIRECT. Datacenter-safe to
        //    verify: ya.ru resolves to RU IPs everywhere, so GeoIP returns RU. ──
        android.util.Log.i("razban-core", "geo-phase: sustaining ya.ru for the GeoClassifier")
        repeat(8) { httpGet("https://ya.ru/"); Thread.sleep(2500) }
        val ur = ctx.getSharedPreferences("razban", Context.MODE_PRIVATE)
            .getString("userRoutes", "{}") ?: "{}"
        android.util.Log.i("razban-core", "geo-result: userRoutes=$ur")
        android.util.Log.i("razban-core", "geo-pinned-ya.ru=${ur.contains("ya.ru")}")

        stop()

        val opened = results.count { it.third in 200..399 }
        android.util.Log.i("razban-core", "site-access RESULT: $opened/${results.size} opened :: $results")

        assertTrue("expected ≥3 sites to open through the VPN, got $opened :: $results", opened >= 3)
        val direct = results.first { it.second == "direct" }
        assertTrue("DIRECT-routed site did not open — direct egress has no internet while VPN up: $direct",
            direct.third in 200..399)
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun connectAndWait() {
        // No config import → uses the bundled real config (ConfigStore falls back
        // to the injected default-config.json).
        ContextCompat.startForegroundService(ctx, Intent(ctx, RazbanVpnService::class.java))
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Started) {
                Thread.sleep(2500) // let routes settle
                return
            }
            Thread.sleep(300)
        }
        throw AssertionError("VPN did not reach Started (status=${RazbanVpnService.lastStatus})")
    }

    private fun stop() {
        ctx.startService(Intent(ctx, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            RazbanVpnService.lastStatus != RazbanVpnService.Status.Stopped) Thread.sleep(200)
    }

    private fun httpGet(urlStr: String): Int {
        var last = -1
        repeat(4) { attempt ->
            try {
                val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 12000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Razban-test")
                }
                last = c.responseCode
                try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                c.disconnect()
                if (last in 200..399) return last
            } catch (e: Exception) {
                android.util.Log.w("razban-core", "site $urlStr attempt $attempt: ${e.javaClass.simpleName}: ${e.message}")
                last = -1
            }
            Thread.sleep(2500)
        }
        return last
    }

    private fun shell(cmd: String) {
        try {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } catch (_: Exception) {}
    }
}
