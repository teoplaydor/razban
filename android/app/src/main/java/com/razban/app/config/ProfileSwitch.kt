package com.razban.app.config

/**
 * Tunnel-aware profile switching. A live VPN cannot silently adopt another
 * profile: [RazbanVpnService.reload] only swaps sing-box JSON and would leave
 * a Direct TCP adapter or xray sidecar running. The UI and bridge must either
 * fully stop/restart or ask the user to disconnect — never report success
 * while the old datapath is still up.
 */
object ProfileSwitch {
    const val DISCONNECT_REQUIRED = "disconnect_required"

    fun blockedByTunnel(statusName: String): Boolean {
        return statusName == "Started" || statusName == "Starting"
    }
}
