package com.razban.app.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot hook. Intentionally a NO-OP unless the user explicitly enabled
 * start-on-boot, mirroring the desktop invariant "there is NO legitimate
 * auto-connect path — the VPN only ever starts on an explicit user action."
 * Wiring is left in (manifest receiver) so the toggle can be added later
 * without a manifest change.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context
            .getSharedPreferences("razban", Context.MODE_PRIVATE)
            .getBoolean("start_on_boot", false)
        if (!enabled) return
        // Even when enabled we cannot establish() a VPN from a background boot
        // receiver without prior consent; this just pre-warms nothing. Left as
        // an explicit, opt-in hook.
    }
}
