package com.razban.app.bg

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * On-device connectivity diagnostic. Runs right after Connect on a background
 * thread and answers, with EVIDENCE (not theory), the recurring "RU sites don't
 * open / direct doesn't work" question by probing each layer independently:
 *
 *  1. underlying network bound? (without it, every protected socket → 0 egress)
 *  2. Android system Private DNS (DoT) active? — the #1 silent cause: if the
 *     phone's Settings → Private DNS is on, EVERY app resolves via that DoT
 *     provider, which under the VPN tunnels → RU geo-DNS returns EU IPs →
 *     RU sites geo-fenced, in EVERY app (not just the browser). We don't see it
 *     because it's a phone OS setting, not our config.
 *  3. direct egress to a known RU IP via a PROTECTED socket — does the `direct`
 *     path physically carry bytes on this device at all?
 *  4. direct egress to a foreign IP (control).
 *  5. what IP does yandex.ru actually resolve to — RU or foreign? (the
 *     resolution-vs-routing tell.)
 *
 * The result is logged (tag `razban-diag`) AND cached for the UI (diag.run RPC),
 * so the user can read back exactly which layer is broken.
 */
object Diagnostics {

    @Volatile var last: JSONObject? = null

    // Stable Yandex RU front IPs (yandex.ru / ya.ru) for a direct-egress probe and
    // for the "did yandex resolve to a Russian IP" check. AS13238 Yandex blocks.
    private val RU_PROBE_IPS = listOf("77.88.55.88", "5.255.255.77", "87.250.250.242")

    // Well-known Yandex (AS13238) RU ranges — CIDR-EXACT (string prefixes were
    // over-broad and could falsely report "yandex → RU, OK", masking the very DNS
    // tunneling this tool exists to detect). All three RU_PROBE_IPS fall inside.
    private val RU_CIDRS = listOf(
        "5.45.192.0/18", "5.255.192.0/18", "37.9.64.0/18", "37.140.128.0/18",
        "77.88.0.0/18", "84.201.128.0/17", "87.250.224.0/19", "93.158.0.0/16",
        "95.108.128.0/17", "100.43.64.0/19", "141.8.128.0/18", "178.154.128.0/17",
        "199.21.96.0/22", "213.180.192.0/19"
    ).map(::parseCidr)

    private fun ipToLong(ip: String): Long {
        val p = ip.split(".")
        if (p.size != 4) return -1
        return try {
            ((p[0].toLong() shl 24) or (p[1].toLong() shl 16) or
             (p[2].toLong() shl 8) or p[3].toLong()) and 0xFFFFFFFFL
        } catch (_: NumberFormatException) { -1 }
    }
    private fun parseCidr(c: String): Pair<Long, Long> {
        val parts = c.split("/")
        val bits = parts[1].toInt()
        val mask = if (bits == 0) 0L else (-1L shl (32 - bits)) and 0xFFFFFFFFL
        return (ipToLong(parts[0]) and mask) to mask
    }
    private fun isRuIp(ip: String): Boolean {
        val v = ipToLong(ip)
        if (v < 0) return false
        return RU_CIDRS.any { (base, mask) -> (v and mask) == base }
    }

    /** @param service the live VpnService (needed for protect()); null → skip egress probes. */
    fun run(ctx: Context, service: VpnService?, underlying: Network?): JSONObject {
        val r = JSONObject()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)

        // 1. underlying network ------------------------------------------------
        val net = underlying ?: DefaultNetworkMonitor.currentNetwork
        r.put("underlyingBound", net != null)
        var netType = "none"
        if (net != null && cm != null) {
            val c = cm.getNetworkCapabilities(net)
            netType = when {
                c == null -> "unknown"
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
        r.put("underlyingType", netType)

        // 2. Android Private DNS (DoT) — the silent killer ---------------------
        var privActive = false
        var privName: String? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && cm != null) {
            // Check ONLY the underlying physical net — once the VPN is up
            // cm.activeNetwork is the VPN itself, whose LinkProperties don't reflect
            // the system Private DNS on the uplink. Fall back to activeNetwork only
            // if we have no underlying net at all.
            val probes = listOfNotNull(net ?: cm.activeNetwork)
            for (n in probes) {
                val lp = try { cm.getLinkProperties(n) } catch (_: Exception) { null } ?: continue
                if (lp.isPrivateDnsActive) {
                    privActive = true
                    privName = lp.privateDnsServerName   // null = "Automatic/opportunistic"
                    break
                }
            }
        }
        r.put("privateDnsActive", privActive)
        r.put("privateDnsName", privName ?: (if (privActive) "automatic" else ""))

        // 3 + 4. direct egress probes (protected sockets) ----------------------
        if (service != null) {
            var ruOk = false; var ruMs = -1L
            for (ip in RU_PROBE_IPS) {
                val ms = protectedConnect(service, ip, 443, 4000)
                if (ms >= 0) { ruOk = true; ruMs = ms; break }
            }
            r.put("directRuEgress", ruOk)
            r.put("directRuMs", ruMs)
            r.put("directForeignEgress", protectedConnect(service, "1.1.1.1", 443, 4000) >= 0)
        }

        // 5. resolution tell — what IP does yandex.ru actually get? ------------
        var yIp = ""
        try {
            val all = if (net != null) net.getAllByName("yandex.ru") else InetAddress.getAllByName("yandex.ru")
            yIp = all.firstOrNull()?.hostAddress ?: ""
        } catch (_: Exception) {}
        r.put("yandexResolvedIp", yIp)
        r.put("yandexIpIsRu", yIp.isNotEmpty() && isRuIp(yIp))

        // verdict --------------------------------------------------------------
        val verdict = when {
            !r.optBoolean("underlyingBound") -> "BROKEN: no underlying network → 0 egress (direct + tunnel both dead)"
            r.optBoolean("privateDnsActive") -> "LIKELY CAUSE: Android Private DNS is ON (${r.optString("privateDnsName")}) — turn it OFF (Settings → Network → Private DNS → Off). It tunnels every app's DNS → RU sites get EU IPs."
            r.has("directRuEgress") && !r.optBoolean("directRuEgress") -> "BROKEN: direct egress to a Russian IP fails on this device — the direct route can't carry traffic (not a DNS issue)."
            r.optString("yandexResolvedIp").isNotEmpty() && !r.optBoolean("yandexIpIsRu") -> "DNS issue: yandex.ru resolved to a FOREIGN IP (${r.optString("yandexResolvedIp")}) — resolution is going through the tunnel."
            else -> "OK: underlying bound, no Private DNS, direct egress works, yandex.ru → RU IP."
        }
        r.put("verdict", verdict)

        last = r
        android.util.Log.i("razban-diag", "DIAG $r")
        return r
    }

    /** Open a PROTECTED TCP socket (bypasses the TUN, egresses on the physical
     *  NIC) and connect. Returns ms on success, -1 on failure. This is exactly
     *  the path sing-box's `direct` outbound uses, so it proves whether direct
     *  egress works on THIS device. */
    private fun protectedConnect(service: VpnService, ip: String, port: Int, timeoutMs: Int): Long {
        val s = Socket()
        return try {
            s.bind(null)                 // allocate the underlying fd so protect() has one
            service.protect(s)           // keep it OFF the VPN — egress on the real network
            val t0 = System.currentTimeMillis()
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            System.currentTimeMillis() - t0
        } catch (_: Exception) {
            -1L
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }
}
