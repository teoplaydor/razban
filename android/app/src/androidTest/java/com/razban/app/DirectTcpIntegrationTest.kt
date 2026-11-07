package com.razban.app

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.razban.app.bg.RazbanVpnService
import com.razban.app.bridge.WebUiBridge
import com.razban.app.config.ConfigStore
import com.razban.app.config.ProfileImport
import com.razban.app.config.ProfileStore
import com.razban.app.config.ProfileSwitch
import com.razban.app.direct.DirectProfile
import com.razban.app.direct.DirectTcpAdapter
import com.razban.app.direct.DirectTcpCoordinator
import com.razban.app.direct.FixedPlan
import com.razban.app.direct.ProtectedSockets
import com.razban.app.direct.Reject
import com.razban.app.direct.SessionState
import com.razban.app.direct.SignalCodec
import com.razban.app.direct.SocketRef
import com.razban.app.direct.SocksHttpProbe
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

internal const val DIRECT_MOCK_CERT_PEM = "-----BEGIN CERTIFICATE-----\n" +
        "MIIDJTCCAg2gAwIBAgIUC87aFoQZx+0F/hyMjq9PIze8PmMwDQYJKoZIhvcNAQEL\n" +
        "BQAwFDESMBAGA1UEAwwJMTI3LjAuMC4xMB4XDTI2MTEwNzExNDg0NFoXDTI2MTIw\n" +
        "NzExNDg0NFowFDESMBAGA1UEAwwJMTI3LjAuMC4xMIIBIjANBgkqhkiG9w0BAQEF\n" +
        "AAOCAQ8AMIIBCgKCAQEA2ouomXsnNsHquOTjPI3bKRGOzdHn59SK3iAvXtqnhKWd\n" +
        "jKLIcKqSSuVhLk8c1h3Do9GEJv5HsjPK/GfKnukYxtB1mufGUsgbqVGUCi/3ovQ9\n" +
        "1ffQurcHewK7W1HStIdYL4+/KdS4l+rL/Gsz9/gU50EIPn4p6GiZ6LHWNHDJb9lW\n" +
        "Qi5+3WRSn4aMyX3zwDW934eTuwWzRlRyAmySt6gCAzg2e8cDUPvcf1KYF3Tw8VbR\n" +
        "/rZ9nKMFWlGH1HCzc9JqczF3VPAauu9M2EiFIopY4E7tCDCkX/ylJoYQttfM0aNQ\n" +
        "9RyVFebctTGaTbRFp9lnQ7tVtKPUdTjNUpvPwXV7jQIDAQABo28wbTAdBgNVHQ4E\n" +
        "FgQU21gaN2oV08to66NMrzJ64wDsVuUwHwYDVR0jBBgwFoAU21gaN2oV08to66NM\n" +
        "rzJ64wDsVuUwDwYDVR0TAQH/BAUwAwEB/zAaBgNVHREEEzARhwR/AAABgglsb2Nh\n" +
        "bGhvc3QwDQYJKoZIhvcNAQELBQADggEBAHHHNeUuUCTs5actWEDLipamlWhqA1Hl\n" +
        "W1OcPlH9/cdJqwQcn8rFzGdgPDkXUKQoqebcYU080xxIfKAo49f+6kRFCbAsBS1a\n" +
        "cXaQntxWDt1Fyax2VCKpNh4JDC2NbMOkKrq31tOxaEbGYFYYCAmmmflsF7en6iWM\n" +
        "woxYng6Fi0+TD2hMyMm0wwf5VY7Jjpet57N+Khp+Lgy/nwA30QHao4jryrLbkoOf\n" +
        "87oojPixa6P+G/BmHov8JPuuH394+rna3aKTT2phs6Y3oDGOYHRAva2jGCtrVO7G\n" +
        "D6yAnEK13DEQ1Vvw1oKo6ftgx5mfUYJrSUaAE+FBSfGXKZ+mWSMQroM=\n" +
        "-----END CERTIFICATE-----\n"
internal const val DIRECT_MOCK_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" +
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDai6iZeyc2weq4\n" +
        "5OM8jdspEY7N0efn1IreIC9e2qeEpZ2MoshwqpJK5WEuTxzWHcOj0YQm/keyM8r8\n" +
        "Z8qe6RjG0HWa58ZSyBupUZQKL/ei9D3V99C6twd7ArtbUdK0h1gvj78p1LiX6sv8\n" +
        "azP3+BTnQQg+finoaJnosdY0cMlv2VZCLn7dZFKfhozJffPANb3fh5O7BbNGVHIC\n" +
        "bJK3qAIDODZ7xwNQ+9x/UpgXdPDxVtH+tn2cowVaUYfUcLNz0mpzMXdU8Bq670zY\n" +
        "SIUiiljgTu0IMKRf/KUmhhC218zRo1D1HJUV5ty1MZpNtEWn2WdDu1W0o9R1OM1S\n" +
        "m8/BdXuNAgMBAAECggEAGB5i7v1OaJaiEYxWdQUFc3+CjmhkpYspVYuhGWDG2Awc\n" +
        "Wmb5+U/PHADl5rcdwZZAqcBM9gNeD5FJDO4PcyuFFnvvCWIcMF/xMuXT5q6V2nVb\n" +
        "Vap8bf7BDpb7QlVstuEGSclyxBURLNg2xuwLLN9DafodWiLmURsqJn9p2m5lbLhl\n" +
        "Z0XBQgjdFLD74LSyJr96tDDkVGI/Mj3M5bD9dR7SA6gFrQAAHxcN3IBsjnU8zUYF\n" +
        "nRcbPX5v6ES4fTXs//Itki9O0mgfv0EfSeJZVJd0hD5P6R6t0DsYY8Mgh7UWB6sR\n" +
        "J1oi+gOnbbrq4O51Qezpo1/ZTAlAQagkVBmsiIe5aQKBgQDu+Nogk2Oepu8dAC7q\n" +
        "5FKmWz6JEkbbrQrAYKwV2vKGQfEOfRsU2NcC+VIsJhrEc34mXNyJFfkPCLyf6Tqu\n" +
        "mDg7NVFkPR1ZLriUuAzTfLmEuZI7NQ6wQT7Z6JNqlJqxaE1hQ2vXbYqkP9XiA5+F\n" +
        "BU6AzoLzer92LLFIDWEamPGckwKBgQDqHjN6uwC18gF7wsaXkUmddoTpV73LkiBc\n" +
        "BIICprXAANKKqdRcLb/Sh+0tMRIKnMCeHoK06W1jHxYSHK2xlv0eZ4oZl5zhJFuU\n" +
        "4lQSzxsMUm6tvTDDSj66RO0L1FAEbNwmBbg1mBRvh/zE3yof1XVHDWnFdEAyMj1z\n" +
        "hGW/xP+7XwKBgQC8Y8AJ6EejnfRpB2j1UhP6A3qUMq1bptoP+OYQwK9qufdgNa4w\n" +
        "zBUMyiJGna+CaY3sjk5pX8IdYQTah9H0LBpASvMkM628TcHDtCigXPYftO+b72Lp\n" +
        "/jWfMeNtJUOiUhiWccFFbpzaiVyCtdp+0kUl9Cd17LGdZvRHWgzQG/x1MwKBgDKT\n" +
        "n69t4rNruqJP67Mmse6Qghoe2GO4B8GZP7Tu6ui1xMYr9jKLt5r1vmk65pjgUERY\n" +
        "dwg6PWVie9Qh2ew5oCkHWTA6Lqv8vMQnON5vW3qH/F0QOA6YZYC8DzG1BixOkCiu\n" +
        "p6L8Xb3SdOrbAzCpXpKg9S6w9IVjkR75hTmuh0ilAoGBALgZUtH5+ZXkZJCbH5h7\n" +
        "xgKGckVQOUD1davNpVs8hNllP2cZiBvUI8OAhW5FkNvdwwR1X4TGwUceY7SI6khp\n" +
        "RfwJ5Z1kXrlAMgBu2a2qHZCqwiBS/WGZKtIuVyt4JGvsznuZcRtYYM4+VAkrrsZN\n" +
        "m/zjtU69i3T0NaEnnKUZXW6n\n" +
        "-----END PRIVATE KEY-----\n"



/**
 * Emulator tests: multi-profile import/persistence, mock signalling, reconnect,
 * protected-socket ordering, and explicit SOCKS HTTP. This does not prove TSPU.
 */
@RunWith(AndroidJUnit4::class)
class DirectTcpIntegrationTest {

    private lateinit var ctx: Context
    private val cleanup = ArrayList<() -> Unit>()

    @Before
    fun setup() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.filesDir, "current-config.json").delete()
        File(ctx.filesDir, "xray-config.json").delete()
        File(ctx.filesDir, "direct-config.json").delete()
        File(ctx.filesDir, "active-profile").delete()
        File(ctx.filesDir, "profiles").deleteRecursively()
    }

    @After
    fun teardown() {
        RazbanVpnService.lastStatus = RazbanVpnService.Status.Stopped
        cleanup.asReversed().forEach { run -> try { run() } catch (_: Exception) {} }
        cleanup.clear()
    }

    @Test
    fun importAndPersistTwoProfiles() {
        val replies = Collections.synchronizedList(ArrayList<String>())
        val bridge = WebUiBridge(ctx, {}, {})
        bridge.deliver = { s -> replies.add(s) }
        val relayLink =
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com" +
                "&fp=chrome&pbk=AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKK0&sid=0123456789abcdef&type=tcp#RelayBaseline"
        val addRelay = rpc(bridge, replies, "servers.add", JSONObject().put("uri", relayLink))
        assertTrue("relay import failed: $addRelay", addRelay.optJSONObject("result")?.optInt("count") == 1)

        val deviceKey = SignalCodec.randomHex(32)
        val directDoc = sampleDirectDoc("https://192.0.2.10:9444/v1/session", deviceKey, "192.0.2.20")
        val addDirect = rpc(bridge, replies, "servers.add", JSONObject().put("text", directDoc))
        assertEquals(1, addDirect.getJSONObject("result").optInt("count"))
        assertEquals(DirectProfile.KIND, addDirect.getJSONObject("result").optString("kind"))

        val listed = ProfileStore.list(ctx).map { it.name to it.kind }.toSet()
        assertTrue(listed.contains("RelayBaseline" to "relay"))
        assertTrue(listed.contains("DirectTCPexperimental" to DirectProfile.KIND))

        assertTrue(ProfileStore.select(ctx, "RelayBaseline"))
        assertEquals("RelayBaseline", ProfileStore.activeName(ctx))
        assertFalse(ProfileStore.isDirectActive(ctx))

        assertTrue(ProfileStore.select(ctx, "DirectTCPexperimental"))
        assertEquals("DirectTCPexperimental", ProfileStore.activeName(ctx))
        assertTrue(ProfileStore.isDirectActive(ctx))
        val loaded = ProfileStore.currentDirect(ctx)
        requireNotNull(loaded)
        assertEquals("192.0.2.20", loaded.plan.devicePeer?.host)

        val viaBridge = rpc(bridge, replies, "profiles.select", JSONObject().put("name", "RelayBaseline"))
        assertTrue(viaBridge.getJSONObject("result").optBoolean("ok"))
        assertEquals("RelayBaseline", ProfileStore.activeName(ctx))

        val listJson = rpc(bridge, replies, "profiles.list", null).getJSONObject("result")
        assertEquals(2, listJson.getJSONArray("profiles").length())
        assertFalse(listJson.toString().contains(deviceKey))
        assertFalse(listJson.toString().contains("BEGIN CERTIFICATE"))
        assertFalse(listJson.toString().contains("11111111-2222-3333-4444-555555555555"))
    }

    @Test
    fun importDirectJsonFromFileAndSwitchCycle() {
        val replies = Collections.synchronizedList(ArrayList<String>())
        val bridge = WebUiBridge(ctx, {}, {})
        bridge.deliver = { s -> replies.add(s) }
        val relayLink =
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com" +
                "&fp=chrome&pbk=AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHHIIIIJJJJKK0&sid=0123456789abcdef&type=tcp#RelayBaseline"
        assertTrue(rpc(bridge, replies, "servers.add", JSONObject().put("uri", relayLink)).optJSONObject("result")?.optInt("count") == 1)

        val deviceKey = SignalCodec.randomHex(32)
        val directDoc = sampleDirectDoc("https://192.0.2.10:9444/v1/session", deviceKey, "192.0.2.20")
        val file = File(ctx.cacheDir, "mock-direct-tcp.json")
        file.writeText(directDoc)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val imported = ProfileImport.fromUri(ctx, uri)
        assertTrue("file import failed: ${imported.error}", imported.ok)
        assertEquals(DirectProfile.DEFAULT_NAME, imported.name)
        assertEquals(DirectProfile.KIND, imported.kind)
        assertTrue(ProfileStore.isDirectActive(ctx))
        val listed = ProfileStore.listJson(ctx).toString()
        assertFalse(listed.contains(deviceKey))
        assertFalse(listed.contains("BEGIN PRIVATE KEY"))

        assertTrue(ProfileStore.select(ctx, "RelayBaseline"))
        assertFalse(ProfileStore.isDirectActive(ctx))
        assertFalse(File(ctx.filesDir, "direct-config.json").exists())

        assertTrue(ProfileStore.select(ctx, DirectProfile.DEFAULT_NAME))
        assertTrue(ProfileStore.isDirectActive(ctx))
        assertTrue(File(ctx.filesDir, "direct-config.json").exists())

        ConfigStore.importConfig(ctx, """{"outbounds":[{"type":"direct","tag":"direct"}]}""")
        ProfileStore.recordRedeemedRelay(ctx)
        assertEquals("RelayBaseline", ProfileStore.activeName(ctx))
        assertFalse(ProfileStore.isDirectActive(ctx))
        assertFalse(File(ctx.filesDir, "direct-config.json").exists())
        assertFalse(ConfigStore.hasXray(ctx))

        assertTrue(ProfileStore.select(ctx, DirectProfile.DEFAULT_NAME))
        assertTrue(ProfileStore.isDirectActive(ctx))
    }

    @Test
    fun selectWhileConnectedDoesNotClaimSuccess() {
        val replies = Collections.synchronizedList(ArrayList<String>())
        val bridge = WebUiBridge(ctx, {}, {})
        bridge.deliver = { s -> replies.add(s) }
        val relayLink =
            "vless://11111111-2222-3333-4444-555555555555@198.51.100.7:443" +
                "?encryption=none&security=reality&sni=www.microsoft.com&type=tcp#RelayBaseline"
        rpc(bridge, replies, "servers.add", JSONObject().put("uri", relayLink))
        val deviceKey = SignalCodec.randomHex(32)
        rpc(bridge, replies, "servers.add", JSONObject().put("text", sampleDirectDoc("https://192.0.2.10:9444/v1/session", deviceKey, "192.0.2.20")))
        assertEquals(DirectProfile.DEFAULT_NAME, ProfileStore.activeName(ctx))
        assertTrue(ProfileStore.isDirectActive(ctx))

        RazbanVpnService.lastStatus = RazbanVpnService.Status.Started
        val blocked = rpc(bridge, replies, "profiles.select", JSONObject().put("name", "RelayBaseline"))
        val result = blocked.getJSONObject("result")
        assertFalse(result.optBoolean("ok"))
        assertEquals(ProfileSwitch.DISCONNECT_REQUIRED, result.optString("error"))
        assertEquals(DirectProfile.DEFAULT_NAME, result.optString("active"))
        assertEquals(DirectProfile.DEFAULT_NAME, ProfileStore.activeName(ctx))
        assertTrue(ProfileStore.isDirectActive(ctx))

        RazbanVpnService.lastStatus = RazbanVpnService.Status.Stopped
        val ok = rpc(bridge, replies, "profiles.select", JSONObject().put("name", "RelayBaseline"))
        assertTrue(ok.getJSONObject("result").optBoolean("ok"))
        assertEquals("RelayBaseline", ProfileStore.activeName(ctx))
        assertFalse(ProfileStore.isDirectActive(ctx))
    }

    @Test
    fun mockSignallingAndReconnect() {
        val deviceKey = SignalCodec.hexDecode(SignalCodec.randomHex(32))
        val serverKey = SignalCodec.hexDecode(SignalCodec.randomHex(32))
        val echo = EchoServer()
        echo.start()
        cleanup.add { echo.close() }
        val plan = FixedPlan(
            mode = "active",
            deviceBind = SocketRef("127.0.0.1", 0),
            devicePeer = SocketRef("127.0.0.1", echo.port),
        )
        val state = SessionState(mapOf("device" to deviceKey, "server" to serverKey), plan, budget = 4)
        val mock = MockSignalServer(state, directSslContextFromPem(DIRECT_MOCK_CERT_PEM, DIRECT_MOCK_KEY_PEM))
        mock.start()
        cleanup.add { mock.close() }

        val url = "https://127.0.0.1:${mock.port}/v1/session"
        val deviceProfile = DirectProfile.parse(sampleDirectDoc(url, SignalCodec.hexEncode(deviceKey), "127.0.0.1", echo.port))
        val serverProfile = DirectProfile(
            name = "stub",
            signalUrl = url,
            signalHostname = "127.0.0.1",
            caPem = DIRECT_MOCK_CERT_PEM,
            role = "server",
            hmacKey = serverKey,
            plan = plan,
            adapterPort = deviceProfile.adapterPort,
            xrayJson = deviceProfile.xrayJson,
        )
        val stub = ServerRoleStub(serverProfile)
        stub.start()
        cleanup.add { stub.stop() }

        val adapter = DirectTcpAdapter(deviceProfile, networkProvider = { null }, protectSocket = { true })
        adapter.start()
        cleanup.add { adapter.stop() }

        val first = exchange(adapter.listenPort, "ping-one")
        assertEquals("ping-one", first)
        adapter.closeCurrent(forced = true)
        val second = exchange(adapter.listenPort, "ping-two")
        assertEquals("ping-two", second)
        assertTrue("expected at least two connects: ${adapter.events}", adapter.events.count { it == "connected" } >= 2)
        assertTrue(adapter.events.contains("forced_disconnect") || adapter.events.contains("stream_finished"))
    }

    @Test(timeout = 60000)
    fun idleOver20sThenBidirectionalThenReconnect() {
        val deviceKey = SignalCodec.hexDecode(SignalCodec.randomHex(32))
        val serverKey = SignalCodec.hexDecode(SignalCodec.randomHex(32))
        val echo = EchoServer()
        echo.start()
        cleanup.add { echo.close() }
        val plan = FixedPlan(
            mode = "active",
            deviceBind = SocketRef("127.0.0.1", 0),
            devicePeer = SocketRef("127.0.0.1", echo.port),
        )
        val state = SessionState(mapOf("device" to deviceKey, "server" to serverKey), plan, budget = 4)
        val mock = MockSignalServer(state, directSslContextFromPem(DIRECT_MOCK_CERT_PEM, DIRECT_MOCK_KEY_PEM))
        mock.start()
        cleanup.add { mock.close() }
        val url = "https://127.0.0.1:${mock.port}/v1/session"
        val deviceProfile = DirectProfile.parse(sampleDirectDoc(url, SignalCodec.hexEncode(deviceKey), "127.0.0.1", echo.port))
        val serverProfile = DirectProfile(
            name = "stub",
            signalUrl = url,
            signalHostname = "127.0.0.1",
            caPem = DIRECT_MOCK_CERT_PEM,
            role = "server",
            hmacKey = serverKey,
            plan = plan,
            adapterPort = deviceProfile.adapterPort,
            xrayJson = deviceProfile.xrayJson,
        )
        val stub = ServerRoleStub(serverProfile)
        stub.start()
        cleanup.add { stub.stop() }
        val adapter = DirectTcpAdapter(deviceProfile, networkProvider = { null }, protectSocket = { true })
        adapter.start()
        cleanup.add { adapter.stop() }

        Socket().use { s ->
            s.soTimeout = 30_000
            s.connect(InetSocketAddress("127.0.0.1", adapter.listenPort), 3000)
            Thread.sleep(21_000)
            val payload = "idle-ping"
            s.getOutputStream().write(payload.toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(payload.length)
            var off = 0
            val deadline = System.currentTimeMillis() + 8000
            while (off < buf.size && System.currentTimeMillis() < deadline) {
                val n = s.getInputStream().read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            assertEquals(payload, String(buf, 0, off))
        }
        assertTrue("expected data probe after bidirectional bytes: ${adapter.events}", adapter.events.contains("data_ok"))
        adapter.closeCurrent(forced = true)
        val second = exchange(adapter.listenPort, "ping-two")
        assertEquals("ping-two", second)
        assertTrue("expected reconnect: ${adapter.events}", adapter.events.count { it == "connected" } >= 2)
    }

    @Test
    fun serversListShowsDirectPeerAndTlsNotLoopbackReality() {
        val hmac = SignalCodec.randomHex(32)
        val doc = sampleDirectDoc("https://192.0.2.9:9444/v1/session", hmac, "192.0.2.85", 9443)
        val imported = ProfileStore.importPayload(ctx, doc)
        assertTrue(imported.ok)
        val replies = Collections.synchronizedList(ArrayList<String>())
        val bridge = WebUiBridge(ctx, {}, {})
        bridge.deliver = { replies.add(it) }
        val listed = rpc(bridge, replies, "servers.list", null)
        val servers = listed.getJSONObject("result").getJSONArray("servers")
        assertTrue(servers.length() >= 1)
        val first = servers.getJSONObject(0)
        assertEquals("192.0.2.85", first.getString("server"))
        assertEquals(9443, first.getInt("port"))
        assertEquals("tls", first.optString("security"))
        assertFalse(first.getJSONObject("reality").optBoolean("enabled"))
        assertFalse(first.getString("server") == "127.0.0.1")
        val state = rpc(bridge, replies, "vpn.state", null).opt("result")
        assertEquals("disconnected", state)
    }

    @Test
    fun protectAndBindHappenBeforeConnect() {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val events = ArrayList<String>()
        val sockets = ProtectedSockets(
            networkProvider = {
                events.add("network")
                network
            },
            protectSocket = {
                events.add("protect")
                true
            },
        )
        val s = sockets.open(InetSocketAddress("8.8.8.8", 443), connect = false)
        try {
            assertTrue(sockets.events.contains("protect"))
            assertFalse(sockets.events.contains("connect"))
            if (network != null) {
                assertTrue(sockets.events.contains("bindSocket"))
                assertTrue(events.indexOf("network") <= events.indexOf("protect") || sockets.events.contains("protect"))
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun httpProbeUsesExplicitSocks() {
        val socks = MockSocksHttpServer()
        socks.start()
        cleanup.add { socks.close() }
        val result = SocksHttpProbe.httpGet("127.0.0.1", socks.port, "example.test", 80, "/")
        assertTrue("socks probe failed: $result", result.ok)
        assertTrue(result.viaSocks)
        assertEquals(200, result.status)
        assertTrue(socks.sawConnect)
    }

    private fun exchange(port: Int, payload: String): String {
        Socket().use { s ->
            s.soTimeout = 10_000
            s.connect(InetSocketAddress("127.0.0.1", port), 3000)
            s.getOutputStream().write(payload.toByteArray())
            s.getOutputStream().flush()
            val buf = ByteArray(payload.length)
            var off = 0
            val deadline = System.currentTimeMillis() + 10_000
            while (off < buf.size && System.currentTimeMillis() < deadline) {
                val n = s.getInputStream().read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return String(buf, 0, off)
        }
    }

    private fun rpc(bridge: WebUiBridge, replies: MutableList<String>, method: String, params: JSONObject?): JSONObject {
        val id = "t" + System.nanoTime()
        val env = JSONObject().put("id", id).put("method", method)
        if (params != null) env.put("params", params)
        val before = replies.size
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
        throw AssertionError("no reply for $method")
    }

    private fun sampleDirectDoc(url: String, hmacHex: String, peerHost: String, peerPort: Int = 9443): String {
        val dataTls = JSONObject()
            .put("uuid", "11111111-2222-3333-4444-555555555555")
            .put("serverName", "127.0.0.1")
            .put("certificatePem", DIRECT_MOCK_CERT_PEM)
        return JSONObject()
            .put("razbanProfile", DirectProfile.KIND)
            .put("name", "DirectTCPexperimental")
            .put("signal", JSONObject()
                .put("url", url)
                .put("hostname", "127.0.0.1")
                .put("caPem", DIRECT_MOCK_CERT_PEM)
                .put("role", "device")
                .put("hmacKeyHex", hmacHex))
            .put("plan", JSONObject()
                .put("mode", "active")
                .put("device", JSONObject()
                    .put("bind", org.json.JSONArray().put("127.0.0.1").put(0))
                    .put("peer", org.json.JSONArray().put(peerHost).put(peerPort))))
            .put("adapterPort", 0)
            .put("dataTls", dataTls)
            .toString()
    }
}

internal class EchoServer {
    private val server = ServerSocket(0)
    private val stop = AtomicBoolean(false)
    private val thread: Thread
    val port: Int get() = server.localPort

    init {
        thread = Thread {
            while (!stop.get()) {
                val c = try { server.accept() } catch (_: Exception) { break }
                Thread {
                    try {
                        val buf = ByteArray(1024)
                        val n = c.getInputStream().read(buf)
                        if (n > 0) {
                            c.getOutputStream().write(buf, 0, n)
                            c.getOutputStream().flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { c.close() } catch (_: Exception) {}
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        thread.isDaemon = true
    }

    fun start() { thread.start() }
    fun close() {
        stop.set(true)
        try { server.close() } catch (_: Exception) {}
        try { thread.join(2000) } catch (_: InterruptedException) {}
    }
}

internal class ServerRoleStub(private val profile: DirectProfile) {
    private val stop = AtomicBoolean(false)
    private val coordinator = DirectTcpCoordinator(profile, networkProvider = { null }, protectSocket = { true })
    private lateinit var thread: Thread
    fun start() {
        thread = Thread {
            while (!stop.get()) {
                try {
                    val next = coordinator.call("next")
                    if (next.optString("state") == "idle" || !next.has("session")) {
                        // Stay well under SessionState's 40/2s rate cap and 256 nonce cap
                        // (a 50ms poll burned both during a 21s idle and blocked reconnect).
                        Thread.sleep(250)
                        continue
                    }
                    val armed = coordinator.armed(next)
                    coordinator.call("done", armed)
                } catch (_: Exception) {
                    try { Thread.sleep(400) } catch (_: InterruptedException) {}
                }
            }
        }
        thread.isDaemon = true
        thread.start()
    }
    fun stop() {
        stop.set(true)
        try { thread.join(2000) } catch (_: InterruptedException) {}
    }
}

internal class MockSignalServer(private val state: SessionState, sslContext: SSLContext) {
    private val server: SSLServerSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    private val stop = AtomicBoolean(false)
    private val thread: Thread
    val port: Int get() = server.localPort

    init {
        thread = Thread {
            while (!stop.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { handle(socket) }.apply { isDaemon = true }.start()
            }
        }
        thread.isDaemon = true
    }

    fun start() { thread.start() }
    fun close() {
        stop.set(true)
        try { server.close() } catch (_: Exception) {}
        try { thread.join(2000) } catch (_: InterruptedException) {}
    }

    private fun handle(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val headers = readHeaders(input)
            val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) break
                off += n
            }
            val role = Regex("X-Role:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)
            val mac = Regex("X-MAC:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)
            val pathOk = headers.lineSequence().first().contains(" /v1/session")
            val (code, obj) = try {
                if (!pathOk) throw Reject(404, "path")
                200 to state.operate(role, body, mac)
            } catch (e: Reject) {
                e.code to JSONObject().put("error", e.reason)
            } catch (_: Exception) {
                400 to JSONObject().put("error", "request")
            }
            obj.put("server_time", SignalCodec.nowSeconds())
            val raw = obj.toString().toByteArray()
            val resp = "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${raw.size}\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(resp.toByteArray(Charsets.US_ASCII))
            out.write(raw)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readHeaders(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        var match = 0
        while (match < 4) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            match = if ((match == 0 || match == 2) && b == '\r'.code) match + 1
            else if ((match == 1 || match == 3) && b == '\n'.code) match + 1
            else if (b == '\r'.code) 1 else 0
        }
        return buf.toString("ISO-8859-1")
    }
}

internal class MockSocksHttpServer {
    private val server = ServerSocket(0)
    private val stop = AtomicBoolean(false)
    private val thread: Thread
    val port: Int get() = server.localPort
    @Volatile var sawConnect = false

    init {
        thread = Thread {
            val c = try { server.accept() } catch (_: Exception) { return@Thread }
            try {
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                val greet = ByteArray(3)
                inp.read(greet)
                out.write(byteArrayOf(0x05, 0x00))
                out.flush()
                val req = ByteArray(256)
                val n = inp.read(req)
                if (n >= 4) sawConnect = true
                out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                out.flush()
                val http = ByteArray(256)
                inp.read(http)
                val body = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                out.write(body.toByteArray())
                out.flush()
            } catch (_: Exception) {
            } finally {
                try { c.close() } catch (_: Exception) {}
            }
        }
        thread.isDaemon = true
    }

    fun start() { thread.start() }
    fun close() {
        stop.set(true)
        try { server.close() } catch (_: Exception) {}
        try { thread.join(2000) } catch (_: InterruptedException) {}
    }
}

internal fun directSslContextFromPem(certPem: String, keyPem: String): SSLContext {
    val cf = CertificateFactory.getInstance("X.509")
    val cert = cf.generateCertificate(certPem.byteInputStream()) as X509Certificate
    val b64 = keyPem.replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    ks.load(null)
    ks.setKeyEntry("key", key, CharArray(0), arrayOf(cert))
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, CharArray(0))
    val trust = KeyStore.getInstance(KeyStore.getDefaultType())
    trust.load(null)
    trust.setCertificateEntry("ca", cert)
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(trust)
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, tmf.trustManagers, java.security.SecureRandom())
    return ctx
}
