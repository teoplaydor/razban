package com.razban.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.razban.app.bg.RazbanVpnService
import com.razban.app.config.ConfigStore
import com.razban.app.config.UriImport
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic (not a pass/fail gate): import the real Reality profile the way the
 * UI does, bring the tunnel up, and log everything so CI logcat shows exactly
 * where import + xray-sidecar + connect stand. The emulator can reach the real
 * RUVDS:443 (no TSPU on the GitHub→RUVDS path), so a working Path-2 chain should
 * get xray's Reality handshake through.
 */
@RunWith(AndroidJUnit4::class)
class XrayDiagTest {

    private val link =
        "vless://455e94c2-e904-4a86-a659-d1e92994f9a2@170.168.119.63:443?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome&pbk=c0Qg1i2Vg50WGPrz3bP9VKUEYveV3P-7P_2Km8ntbi8&sid=02dee99982b9c353&type=tcp#diag"

    private val T = "razban-diag"
    private lateinit var ctx: Context

    @Before
    fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        shell("appops set ${ctx.packageName} ACTIVATE_VPN allow")
        shell("am start -n ${ctx.packageName}/com.razban.app.ui.MainActivity")
        Thread.sleep(1500)
    }

    @Test
    fun importAndConnect() {
        // 1) IMPORT — exact logic the servers.addMany bridge runs.
        val r = UriImport.toImport(link)
        android.util.Log.i(T, "toImport -> ${if (r == null) "NULL" else "ok"}")
        assertNotNull("UriImport.toImport returned null for the Reality link", r)
        android.util.Log.i(T, "SINGBOX_CONFIG=${r!!.singbox}")
        android.util.Log.i(T, "XRAY_CONFIG=${r.xray}")
        ConfigStore.setXrayConfig(ctx, r.xray)
        ConfigStore.importConfig(ctx, r.singbox)
        android.util.Log.i(T, "hasXray=${ConfigStore.hasXray(ctx)} " +
            "nativeLibDir=${ctx.applicationInfo.nativeLibraryDir} " +
            "libxray.exists=${java.io.File(ctx.applicationInfo.nativeLibraryDir, "libxray.so").exists()}")
        android.util.Log.i(T, "ADAPTED_SINGBOX=${ConfigStore.currentConfigJson(ctx)}")

        // 2) CONNECT — start the service; xray should come up first, then sing-box.
        ContextCompat.startForegroundService(ctx, Intent(ctx, RazbanVpnService::class.java))
        val deadline = System.currentTimeMillis() + 45_000
        var started = false
        while (System.currentTimeMillis() < deadline) {
            if (RazbanVpnService.lastStatus == RazbanVpnService.Status.Started) { started = true; break }
            Thread.sleep(500)
        }
        android.util.Log.i(T, "VPN status after wait = ${RazbanVpnService.lastStatus} (started=$started)")

        // 3) give xray a few seconds to finish the Reality handshake, then stop.
        Thread.sleep(8000)
        android.util.Log.i(T, "final VPN status = ${RazbanVpnService.lastStatus}")
        ctx.startService(Intent(ctx, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        Thread.sleep(3000)
        // No hard assert on egress: the app excludes itself from the TUN in xray
        // mode, so in-process probes bypass it. The signal is the logcat above +
        // the razban-xray lines + the RUVDS server log.
    }

    private fun shell(cmd: String) {
        try {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        } catch (_: Exception) {}
    }
}
