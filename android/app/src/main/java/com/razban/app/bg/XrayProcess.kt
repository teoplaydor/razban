package com.razban.app.bg

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the bundled xray-core as a sidecar process for protocols sing-box can't
 * do (VLESS+Reality — sing-box's uTLS produces an unreadable ClientHello in our
 * libbox build; "failed to read client hello" server-side. xray's Reality works).
 *
 * The binary ships as `jniLibs/<abi>/libxray.so` (a plain executable named .so so
 * it lands in nativeLibraryDir with execute permission; the APK sets
 * extractNativeLibs=true + useLegacyPackaging=true so it's a real file on disk).
 * xray exposes a local SOCKS5 on 127.0.0.1:[SOCKS_PORT]; sing-box routes the TUN
 * into it via a `socks` outbound. RazbanVpnService excludes our own package from
 * the TUN while xray is active, so xray's own connection to the server egresses
 * on the physical NIC instead of looping back into the tunnel.
 */
object XrayProcess {

    const val SOCKS_PORT = 10808
    private const val TAG = "razban-xray"

    @Volatile private var proc: Process? = null

    val isRunning: Boolean get() = proc?.isAlive == true

    /** Write [configJson], exec xray, block until its SOCKS port accepts (≤8 s). */
    fun start(ctx: Context, configJson: String): Boolean {
        stop()
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) {
            Log.e(TAG, "libxray.so not found at ${bin.absolutePath}")
            return false
        }
        val cfg = File(ctx.filesDir, "xray-run.json")
        try { cfg.writeText(configJson) } catch (t: Throwable) {
            Log.e(TAG, "write config failed", t); return false
        }
        return try {
            val p = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
                .apply { environment()["XRAY_LOCATION_ASSET"] = ctx.filesDir.absolutePath }
                .start()
            proc = p
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true }.start()
            val up = waitForPort(SOCKS_PORT, 8000)
            if (!up) Log.e(TAG, "xray socks $SOCKS_PORT never came up (alive=${p.isAlive})")
            up
        } catch (t: Throwable) {
            Log.e(TAG, "exec failed", t); proc = null; false
        }
    }

    fun stop() {
        try { proc?.destroy() } catch (_: Throwable) {}
        proc = null
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (proc?.isAlive == false) return false
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Throwable) {}
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        return false
    }
}
