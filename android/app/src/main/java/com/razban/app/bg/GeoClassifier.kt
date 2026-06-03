package com.razban.app.bg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime domain classifier for Android — the SAFE, datacenter-verifiable subset
 * of the desktop SmartProbe. It watches live connections ([CoreStatus]); for a
 * domain we haven't classified yet, it GeoIP-checks the destination IP. If the IP
 * is in Russia the domain is a Russian service, so we pin it DIRECT (faster, no
 * foreign geo-fence) instead of leaving it on the default tunnel.
 *
 * Why this is SAFE by construction (cannot break a working app):
 *  • RKN-blocked FOREIGN services connect to FOREIGN IPs (Google/Cloudflare/…),
 *    which never GeoIP as RU → they are NEVER pinned direct → they stay tunneled.
 *  • Only genuine RU-hosted services (RU IPs) get the direct pin, where direct is
 *    always the correct route. So the verdict can only IMPROVE routing.
 *  • The risky half — actively probing whether a foreign domain is RKN-BLOCKED
 *    (TCP/TLS/HTTP desync detection) — is intentionally NOT here: it can only be
 *    validated on a real RU network, and a wrong "works→direct" verdict there
 *    would break access. That stays a desktop-only / future-with-RU-testing piece.
 *
 * Pinned domains persist in the same `userRoutes.bypassDomains` the UI uses, so a
 * reload (ACTION_RELOAD analog) routes them direct and the choice survives.
 */
object GeoClassifier {
    private const val PREFS = "razban"
    private const val TAG = "razban-geo"

    @Volatile private var running = false
    private var worker: Thread? = null
    private var onRoute: (() -> Unit)? = null
    private lateinit var appCtx: Context

    // domain(lowercase) -> already handled (pinned or checked-not-RU). Avoids
    // re-querying GeoIP for the same host every cycle.
    private val handled = ConcurrentHashMap<String, Boolean>()

    fun start(context: Context, onRoute: () -> Unit) {
        if (running) return
        running = true
        appCtx = context.applicationContext
        this.onRoute = onRoute
        // Don't re-classify domains the user (or a prior session) already pinned.
        try {
            val ur = JSONObject(prefs().getString("userRoutes", "{}") ?: "{}")
            for (k in listOf("bypassDomains", "proxyDomains", "dpiDomains")) {
                val a = ur.optJSONArray(k) ?: continue
                for (i in 0 until a.length()) handled[a.getString(i).lowercase()] = true
            }
        } catch (_: Exception) {}
        worker = Thread({ loop() }, "razban-geo").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        worker?.interrupt(); worker = null
        onRoute = null
        handled.clear()
    }

    private fun prefs() = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loop() {
        // Let the tunnel settle before the first sweep.
        try { Thread.sleep(5000) } catch (_: InterruptedException) { return }
        while (running) {
            try {
                val pending = CoreStatus.observedDomains()
                    .filter { (host, ip) -> classifiable(host, ip) && handled[host.lowercase()] == null }
                    .distinctBy { it.first.lowercase() }
                    .take(5) // cap GeoIP lookups per cycle (be gentle on the API)
                var changed = false
                for ((host, ip) in pending) {
                    if (!running) return
                    handled[host.lowercase()] = true // mark attempted — never retry the same host
                    val cc = geoCountry(ip) ?: continue
                    if (cc == "RU") {
                        if (pinDirect(host)) {
                            changed = true
                            android.util.Log.i(TAG, "geo: $host ($ip) → RU → direct")
                        }
                    } else {
                        android.util.Log.i(TAG, "geo: $host ($ip) → $cc → leave on tunnel")
                    }
                }
                if (changed) onRoute?.invoke()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                android.util.Log.w(TAG, "loop error: ${e.message}")
            }
            try { Thread.sleep(4000) } catch (_: InterruptedException) { return }
        }
    }

    private fun classifiable(host: String, ip: String): Boolean {
        if (host.isEmpty() || ip.isEmpty()) return false
        // host must be a real domain, not an IP literal.
        if (host.contains(":") || host.matches(Regex("^[0-9.]+$"))) return false
        if (!host.contains(".")) return false
        return !isPrivateOrLocal(ip)
    }

    private fun isPrivateOrLocal(ip: String): Boolean {
        if (ip == "::1" || ip.startsWith("127.") || ip.startsWith("fe80") || ip.startsWith("fd")) return true
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }

    /** Append the domain to userRoutes.bypassDomains (→ direct after reload). */
    private fun pinDirect(host: String): Boolean {
        return try {
            val p = prefs()
            val ur = JSONObject(p.getString("userRoutes", "{}") ?: "{}")
            val arr = ur.optJSONArray("bypassDomains") ?: JSONArray()
            for (i in 0 until arr.length()) if (arr.getString(i).equals(host, ignoreCase = true)) return false
            arr.put(host)
            ur.put("bypassDomains", arr)
            p.edit().putString("userRoutes", ur.toString()).apply()
            true
        } catch (_: Exception) { false }
    }

    /** 2-letter country code for an IP via ipinfo.io (HTTPS, no key). null on error. */
    private fun geoCountry(ip: String): String? {
        return try {
            val c = (URL("https://ipinfo.io/$ip/country").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Razban")
            }
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() }.trim() else ""
            c.disconnect()
            if (body.length == 2 && body.all { it.isLetter() }) body.uppercase() else null
        } catch (_: Exception) { null }
    }
}
