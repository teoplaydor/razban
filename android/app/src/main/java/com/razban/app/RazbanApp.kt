package com.razban.app

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * Application entry point. Initialises the embedded sing-box core (libbox)
 * exactly once, before any VPN service can start.
 *
 * libbox keeps three roots:
 *   basePath    — where it puts its control socket + caches
 *   workingPath — sing-box working dir (geoip/geosite caches, rule-set store)
 *   tempPath    — scratch
 *
 * We point them at the app's private storage so nothing is world-readable.
 */
class RazbanApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val base = filesDir
        val working = File(filesDir, "work").apply { mkdirs() }
        val temp = File(cacheDir, "tmp").apply { mkdirs() }

        Libbox.setup(SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = working.absolutePath
            tempPath = temp.absolutePath
            // Command server speaks over a unix socket under basePath when
            // listenPort == 0 — no TCP port opened on the device. Good for
            // battery + attack surface.
            commandServerListenPort = 0
            logMaxLines = 300
        })
        // The libbox.aar is built with `with_low_memory`; turning the limiter
        // on keeps the GC aggressive so a backgrounded tunnel survives Android's
        // memory pressure without being killed.
        Libbox.setMemoryLimit(true)

        // Seed the bundled default config on first run so the phone can connect
        // instantly without an import step.
        try { com.razban.app.config.ConfigStore.ensureDefaultConfig(this) } catch (_: Exception) {}
    }

    companion object {
        lateinit var instance: RazbanApp
            private set
    }
}
