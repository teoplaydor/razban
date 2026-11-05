package com.razban.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.razban.app.bridge.WebUiBridge
import com.razban.app.config.ConfigStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Bench test for the "профиль не добавляется / ничего не происходит в интерфейсе"
 * bug. Drives the EXACT path the WebView uses — a JSON-string envelope into
 * [WebUiBridge.postMessage] ("{id,method:'servers.add',params:{uri:…}}"), the
 * reply read back off [WebUiBridge.deliver], then `servers.list` read the same
 * way — and asserts the imported Reality server actually SHOWS in the list.
 *
 * The earlier diag ([XrayDiagTest]) called UriImport directly and so never
 * exercised the bridge NOR the list; it missed that a Reality profile's real
 * vless server lives in the xray sidecar config, which serversJson didn't
 * surface → the Servers tab stayed empty after a "successful" import.
 *
 * Dummy link: TEST-NET-2 host + fake keys — we assert SURFACING, not a handshake,
 * so no real relay secret goes into a public repo.
 */
@RunWith(AndroidJUnit4::class)
class BridgeImportTest {

    private val T = "razban-import"
    private val link =
        "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443" +
        "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com" +
        "&fp=chrome&pbk=AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKK0&sid=0123456789abcdef&type=tcp#Bench"

    private lateinit var ctx: Context
    private lateinit var bridge: WebUiBridge
    private val replies = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Clean slate: a fresh import, not a pre-seeded bundle.
        java.io.File(ctx.filesDir, "current-config.json").delete()
        java.io.File(ctx.filesDir, "xray-config.json").delete()
        bridge = WebUiBridge(ctx, {}, {})
        bridge.deliver = { s -> replies.add(s); android.util.Log.i(T, "DELIVER ${s.take(400)}") }
    }

    /** Post an rpc envelope and wait up to 8s for its {id,result} reply. */
    private fun rpc(method: String, params: JSONObject?): JSONObject {
        val id = "t" + System.nanoTime()
        val env = JSONObject().put("id", id).put("method", method)
        if (params != null) env.put("params", params)
        val before = replies.size
        android.util.Log.i(T, "POST $env")
        bridge.postMessage(env.toString())
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            synchronized(replies) {
                for (i in before until replies.size) {
                    val o = try { JSONObject(replies[i]) } catch (_: Exception) { continue }
                    if (o.optString("id") == id) return o
                }
            }
            Thread.sleep(50)
        }
        fail("no reply for $method within 8s")
        error("unreachable")
    }

    @Test
    fun importThenListShowsServer() {
        // 1) ADD via the exact bridge envelope the "Добавить → URI ссылка" field sends.
        val add = rpc("servers.add", JSONObject().put("uri", link))
        android.util.Log.i(T, "add reply=$add")
        assertNull("servers.add errored: ${add.optString("error")}",
            add.opt("error").takeIf { it != null && it !== JSONObject.NULL })
        assertEquals("import should report count=1", 1,
            add.getJSONObject("result").optInt("count", -1))

        // 2) both configs written
        assertTrue("xray sidecar config not written", ConfigStore.hasXray(ctx))
        assertTrue("sing-box config not written", ConfigStore.hasConfig(ctx))

        // 3) servers.changed event pushed (so the UI knows to refetch)
        val gotEvent = synchronized(replies) {
            replies.any {
                val o = try { JSONObject(it) } catch (_: Exception) { null }
                o?.optString("type") == "event" && o.optString("name") == "servers.changed"
            }
        }
        assertTrue("servers.changed event not pushed", gotEvent)

        // 4) THE REGRESSION: servers.list must now surface the imported server.
        val list = rpc("servers.list", null).getJSONObject("result")
        val servers = list.getJSONArray("servers")
        android.util.Log.i(T, "servers.list -> $servers")
        assertTrue("servers.list is EMPTY after import — the bug", servers.length() >= 1)
        var found = false
        for (i in 0 until servers.length()) {
            val s = servers.getJSONObject(i)
            if (s.optString("server") == "198.51.100.7" && s.optInt("port") == 443) {
                assertEquals("imported server should be vless", "vless", s.optString("protocol"))
                found = true
            }
        }
        assertTrue("imported Reality server 198.51.100.7:443 not in servers.list", found)
        android.util.Log.i(T, "PASS: import surfaced the Reality server in servers.list")
    }
}
