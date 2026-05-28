package com.razban.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.razban.app.bg.RazbanVpnService
import com.razban.app.config.ConfigStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * End-to-end VPN-flow test on a real Android (emulator). Proves the VpnService
 * port works for BOTH routing paths the desktop has:
 *
 *  • directFlow  — config with final=direct: the TUN comes up, app traffic
 *    enters it and egresses via the protected socket to the internet.
 *    ("напрямую")
 *  • proxyFlow   — config with final=socks→10.0.2.2:1080 (a logging SOCKS
 *    server the CI runs on the host): app traffic is tunneled out through that
 *    "VPN exit". The CI step then asserts the host log saw the request.
 *    ("через VPN")
 *
 * VPN consent is pre-granted by the CI via `appops set <pkg> ACTIVATE_VPN
 * allow`, so VpnService.prepare() returns null and no dialog blocks the test.
 */
@RunWith(AndroidJUnit4::class)
class VpnFlowTest {

    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Pre-grant VPN consent so VpnService.prepare() returns null (no dialog).
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        // Bring the app to the foreground so starting a foreground service is
        // allowed on Android 12+ (background FGS start would throw).
        shell("am start -n ${ctx.packageName}/com.razban.app.ui.MainActivity")
        Thread.sleep(1500)
    }

    @Test
    fun directFlow() {
        // Baseline: confirm the emulator has internet at all BEFORE the VPN.
        val baseline = ipGetStatus("https://1.1.1.1/")
        android.util.Log.i("razban-core", "directFlow baseline (no VPN) = $baseline")

        ConfigStore.importConfig(ctx, directConfig())
        connectAndWait()
        // PROOF OF EGRESS via IP literal (no DNS dependency): traffic must
        // reach the internet through the TUN → direct outbound. Marker IP
        // 1.1.1.1 so the CI can confirm the SOCKS exit did NOT see it.
        val code = ipGetStatus("https://1.1.1.1/")
        // Informational: does DNS resolve under the tunnel? (separate concern)
        val dns = httpGetStatus("https://example.org/")
        android.util.Log.i("razban-core", "directFlow DNS probe = $dns")
        stop()
        assertTrue("direct egress through the TUN should reach the internet (got $code)", code > 0)
    }

    @Test
    fun proxyFlow() {
        ConfigStore.importConfig(ctx, proxyConfig())
        connectAndWait()
        // PROOF the proxy exit carries traffic, via IP literal 8.8.8.8 (no DNS).
        // The host SOCKS logger must record "8.8.8.8" → the connection was
        // tunneled through the proxy.
        val code = ipGetStatus("https://8.8.8.8/")
        stop()
        assertTrue("traffic tunneled through the SOCKS exit should reach the internet (got $code)", code > 0)
    }

    /** HTTPS GET to an IP literal — uses a trust-all SSL context so the
     *  hostname/cert mismatch on a bare IP doesn't mask the egress signal.
     *  TEST-ONLY: never in the shipped app. Any HTTP status (even 4xx) means
     *  the connection completed end-to-end = traffic flowed. */
    private fun ipGetStatus(urlStr: String): Int {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        })
        val sc = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, java.security.SecureRandom()) }
        var last = -1
        repeat(6) { attempt ->
            try {
                val c = (URL(urlStr).openConnection() as javax.net.ssl.HttpsURLConnection).apply {
                    sslSocketFactory = sc.socketFactory
                    setHostnameVerifier { _, _ -> true }
                    connectTimeout = 8000; readTimeout = 8000
                    instanceFollowRedirects = false; requestMethod = "GET"
                }
                last = c.responseCode
                try { c.inputStream.use { it.readBytes() } } catch (_: Exception) {}
                c.disconnect()
                if (last > 0) return last
            } catch (e: Exception) {
                last = -1
                android.util.Log.w("razban-core", "ipGet $urlStr attempt $attempt err: ${e.javaClass.simpleName}: ${e.message}")
            }
            Thread.sleep(2000)
        }
        return last
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun connectAndWait() {
        ContextCompat.startForegroundService(ctx, Intent(ctx, RazbanVpnService::class.java))
        // Wait up to 25s for the core to establish the tunnel.
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline) {
            if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Started) return
            Thread.sleep(300)
        }
        assertEquals("VPN did not reach Started",
            RazbanVpnService.Status.Started, RazbanVpnService.lastStatus)
    }

    private fun stop() {
        ctx.startService(Intent(ctx, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            RazbanVpnService.lastStatus != RazbanVpnService.Status.Stopped) Thread.sleep(200)
    }

    private fun httpGetStatus(urlStr: String): Int {
        var last = -1
        repeat(6) { attempt ->
            try {
                val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                }
                last = c.responseCode
                c.inputStream.use { it.bufferedReader().use(BufferedReader::readText) }
                c.disconnect()
                if (last in 200..399) return last
            } catch (e: Exception) {
                last = -1
                android.util.Log.w("razban-core", "fetch $urlStr attempt $attempt err: ${e.javaClass.simpleName}: ${e.message}")
            }
            Thread.sleep(2000)
        }
        return last
    }

    private fun shell(cmd: String) {
        try {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } catch (_: Exception) {}
    }

    private fun directConfig(): String = """
        {
          "log": { "level": "warn" },
          "dns": { "servers": [ { "address": "1.1.1.1", "detour": "direct" } ] },
          "inbounds": [ {
            "type": "tun", "tag": "tun-in",
            "address": [ "172.19.0.1/30", "fdfe:dcba:9876::1/126" ],
            "auto_route": true, "stack": "gvisor", "mtu": 1420
          } ],
          "outbounds": [ { "type": "direct", "tag": "direct", "udp_fragment": false } ],
          "route": {
            "auto_detect_interface": true,
            "final": "direct",
            "rules": [ { "action": "sniff" }, { "protocol": "dns", "action": "hijack-dns" } ]
          }
        }
    """.trimIndent()

    private fun proxyConfig(): String = """
        {
          "log": { "level": "warn" },
          "dns": { "servers": [ { "address": "1.1.1.1", "detour": "direct" } ] },
          "inbounds": [ {
            "type": "tun", "tag": "tun-in",
            "address": [ "172.19.0.1/30", "fdfe:dcba:9876::1/126" ],
            "auto_route": true, "stack": "gvisor", "mtu": 1420
          } ],
          "outbounds": [
            { "type": "direct", "tag": "direct", "udp_fragment": false },
            { "type": "socks", "tag": "proxy", "server": "10.0.2.2", "server_port": 1080, "version": "5" }
          ],
          "route": {
            "auto_detect_interface": true,
            "final": "proxy",
            "rules": [ { "action": "sniff" }, { "protocol": "dns", "action": "hijack-dns" } ]
          }
        }
    """.trimIndent()
}
