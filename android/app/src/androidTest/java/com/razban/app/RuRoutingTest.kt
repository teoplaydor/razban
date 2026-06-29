package com.razban.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.razban.app.config.ConfigStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the RU-routing + sniffable-path fixes are ACTUALLY present in the config
 * the libbox core runs — i.e. exactly what ConfigStore.currentConfigJson(ctx) returns
 * (seed → adaptForAndroid → freshenRuConfig → injectUserRoutes). Deterministic and
 * geo-INDEPENDENT (the US runner can't reproduce a RU geo-RST, but it CAN prove the
 * config carries the rules). This catches the #1 reason a shipped fix changes nothing
 * on the user's device: the fix never reached the running config. Every rule's presence
 * is logged under the `ru-route` tag so the CI run shows the real config.
 */
@RunWith(AndroidJUnit4::class)
class RuRoutingTest {

    @Test
    fun sniffableAndRuFixesPresent() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = ConfigStore.currentConfigJson(ctx)
            ?: throw AssertionError("ru-route: currentConfigJson returned null (no config available)")
        android.util.Log.i("razban-core", "ru-route: config length=${json.length}")

        val root = JSONObject(json)
        val dns = root.optJSONObject("dns") ?: throw AssertionError("ru-route: no dns block")
        val route = root.optJSONObject("route") ?: throw AssertionError("ru-route: no route block")

        val strategy = dns.optString("strategy")
        val servers = dns.optJSONArray("servers") ?: JSONArray()
        val dnsRules = dns.optJSONArray("rules") ?: JSONArray()
        val routeRules = route.optJSONArray("rules") ?: JSONArray()

        var dnsRuServer = false
        for (i in 0 until servers.length())
            if (servers.optJSONObject(i)?.optString("tag") == "dns-ru") dnsRuServer = true

        var dnsRuRule = false
        var echStrip = false
        for (i in 0 until dnsRules.length()) {
            val r = dnsRules.optJSONObject(i) ?: continue
            if (r.optString("server") == "dns-ru") dnsRuRule = true
            if (r.has("query_type") && r.optString("action") == "reject") echStrip = true
        }

        var quicReject = false
        var ruDirect = false
        for (i in 0 until routeRules.length()) {
            val r = routeRules.optJSONObject(i) ?: continue
            if (r.optString("network") == "udp" && r.optString("action") == "reject") quicReject = true
            if (r.optString("outbound") == "direct") {
                val ds = r.optJSONArray("domain_suffix")
                if (ds != null) for (j in 0 until ds.length()) if (ds.optString(j) == ".ru") ruDirect = true
            }
        }

        android.util.Log.i("razban-core",
            "ru-route: strategy=$strategy dnsRuServer=$dnsRuServer dnsRuRule=$dnsRuRule " +
            "echStrip=$echStrip quicReject=$quicReject ruDirect=$ruDirect")
        // Dump the real rules so the CI log shows what the core actually got.
        android.util.Log.i("razban-core", "ru-route: dnsRules=$dnsRules")
        val firstRoute = (0 until minOf(10, routeRules.length())).map { routeRules.opt(it).toString() }
        android.util.Log.i("razban-core", "ru-route: routeRules[0..10]=$firstRoute")

        assertTrue("ru-route: dns-ru server MISSING — RU resolution fix not applied to running config", dnsRuServer)
        assertTrue("ru-route: dns-ru rule MISSING — RU suffixes not routed to dns-ru", dnsRuRule)
        assertTrue("ru-route: .ru→direct route rule MISSING", ruDirect)
        assertTrue("ru-route: QUIC reject (udp:443) MISSING — ERR_CONNECTION_CLOSED fix not applied", quicReject)
        assertTrue("ru-route: ECH-strip dns rule (query_type 64/65 reject) MISSING", echStrip)
        assertTrue("ru-route: dns.strategy not ipv4_only (got '$strategy')", strategy == "ipv4_only")
    }
}
