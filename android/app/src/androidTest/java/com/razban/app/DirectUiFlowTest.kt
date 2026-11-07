package com.razban.app

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.razban.app.bg.RazbanVpnService
import com.razban.app.config.ProfileStore
import com.razban.app.direct.DirectChannelState
import com.razban.app.direct.DirectProfile
import com.razban.app.direct.SignalCodec
import com.razban.app.direct.SocksHttpProbe
import com.razban.app.ui.WebUiActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real UI path: launcher → Servers → system DocumentsUI → mock JSON/TXT →
 * Connect (VPN grant) → data path checks. Same-UID HTTP is not treated as TUN
 * success; SOCKS and shell-UID TUN are reported separately.
 *
 * Does not prove live-phone NAT/TSPU. Uses an in-process fixture, never a
 * live phone profile or production credentials.
 */
@RunWith(AndroidJUnit4::class)
class DirectUiFlowTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val appCtx = instr.targetContext
    private val testCtx = instr.context
    private val device = UiDevice.getInstance(instr)
    private val cleanup = ArrayList<() -> Unit>()
    private val report = StringBuilder()

    @After
    fun teardown() {
        cleanup.asReversed().forEach { runCatching { it() } }
        File(appCtx.getExternalFilesDir(null), "e2e-report.txt").writeText(report.toString())
        Log.i("razban-direct", "e2e-report\n$report")
    }

    @Test(timeout = 180_000)
    fun launcherServersDocumentsUiConnectFixture() {
        val shots = File(appCtx.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
        fun shot(name: String) {
            device.takeScreenshot(File(shots, "$name.png"))
        }

        val hmacHex = SignalCodec.randomHex(32)
        // Local HTTP origin used as the expected egress after VLESS/TLS.
        val origin = FixtureHttpOrigin()
        origin.start()
        cleanup.add { origin.close() }

        // Signalling fixture. Data peer is the HTTP origin for a raw path
        // plus a dedicated Xray server when the binary is present.
        val xrayServer = FixtureXrayServer.start(appCtx, DIRECT_MOCK_CERT_PEM, DIRECT_MOCK_KEY_PEM)
        cleanup.add { xrayServer.close() }
        val dataPeerPort = if (xrayServer.port > 0) xrayServer.port else origin.port

        val deviceKey = SignalCodec.hexDecode(hmacHex)
        val serverKey = SignalCodec.hexDecode(SignalCodec.randomHex(32))
        val plan = com.razban.app.direct.FixedPlan(
            mode = "active",
            deviceBind = com.razban.app.direct.SocketRef("127.0.0.1", 0),
            devicePeer = com.razban.app.direct.SocketRef("127.0.0.1", dataPeerPort),
        )
        val state = com.razban.app.direct.SessionState(
            mapOf("device" to deviceKey, "server" to serverKey), plan, budget = 8,
        )
        val mock = MockSignalServer(state, directSslContextFromPem(DIRECT_MOCK_CERT_PEM, DIRECT_MOCK_KEY_PEM))
        mock.start()
        cleanup.add { mock.close() }
        val stubProfile = DirectProfile.parse(
            mockDirectJson("https://127.0.0.1:${mock.port}/v1/session", hmacHex, dataPeerPort),
        ).copy(role = "server", hmacKey = serverKey)
        // DirectProfile is a data class? hmacKey is ByteArray so copy works if it's data class - yes it is.
        val stub = ServerRoleStub(stubProfile)
        stub.start()
        cleanup.add { stub.stop() }

        val jsonBody = mockDirectJson("https://127.0.0.1:${mock.port}/v1/session", hmacHex, dataPeerPort)
        FakeProfileDocumentsProvider.write(testCtx, "direct-profile.json", jsonBody.toByteArray())
        FakeProfileDocumentsProvider.write(testCtx, "direct-profile.txt", jsonBody.toByteArray())
        FakeProfileDocumentsProvider.write(testCtx, "direct-profile.bin", jsonBody.toByteArray())
        FakeProfileDocumentsProvider.write(testCtx, "direct-profile.unknown", jsonBody.toByteArray())
        FakeProfileDocumentsProvider.write(testCtx, "bad.json", "{not-valid".toByteArray())
        FakeProfileDocumentsProvider.write(testCtx, "huge.txt", ByteArray(300 * 1024) { 'x'.code.toByte() })

        val launch = Intent(appCtx, WebUiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appCtx.startActivity(launch)
        device.waitForIdle(3_000)
        Thread.sleep(2_500)
        shot("01-launcher")

        clickFirst(listOf("Серверы", "Servers"), 8_000)
        device.waitForIdle(2_000)
        Thread.sleep(1_000)
        shot("02-servers")

        assertTrue("Из файла button missing in UI", clickFirst(listOf("Из файла"), 8_000))
        device.waitForIdle(2_000)
        Thread.sleep(1_000)
        shot("03-picker")

        pickFromTestProvider("direct-profile.json")
        device.waitForIdle(2_000)
        Thread.sleep(1_500)
        shot("04-imported")

        val imported = ProfileStore.list(appCtx).any { it.name == DirectProfile.DEFAULT_NAME }
        assertTrue("profile was not imported via DocumentsUI", imported)
        assertEquals(DirectProfile.DEFAULT_NAME, ProfileStore.activeName(appCtx))
        report.appendLine("import_json=ok name=${ProfileStore.activeName(appCtx)}")

        // TXT variant: picker again after writing is already there; just confirm
        // the store still accepts a subsequent file import of the txt twin.
        val txt = com.razban.app.config.ProfileImport.fromText(appCtx, jsonBody)
        assertTrue("txt payload must parse", txt.ok)
        report.appendLine("import_txt_payload_valid=${txt.ok}")

        val bad = com.razban.app.config.ProfileImport.fromText(appCtx, "{not-valid")
        assertFalse("bad json must fail validation", bad.ok)
        val huge = com.razban.app.config.ProfileImport.fromText(appCtx, "x".repeat(300 * 1024))
        assertFalse("oversized payload must fail", huge.ok)
        report.appendLine("reject_bad_json=ok reject_oversize=ok")

        // Non-loopback TEST-NET dest that fixture freedom can reach locally
        // after this address is on lo — same shape as a real site through the
        // DE edge, not a 127.0.0.1 dest (loopback is not tunneled on a phone).
        device.executeShellCommand("su 0 ip addr add 198.51.100.10/32 dev lo")

        clickFirst(listOf("Подключить", "Connect"), 8_000)
        Thread.sleep(1_000)
        clickFirst(listOf("OK", "Ок", "Allow", "Разрешить", "START", "Start"), 4_000)
        shot("05-connect-grant")

        val deadline = System.currentTimeMillis() + 25_000
        var sawData = false
        while (System.currentTimeMillis() < deadline) {
            if (RazbanVpnService.lastChannelState == DirectChannelState.DataWorking) {
                sawData = true
                break
            }
            Thread.sleep(400)
        }
        report.appendLine("tunnel=${RazbanVpnService.lastStatus} channel=${RazbanVpnService.lastChannelState}")
        report.appendLine("data_working=$sawData (TUN-up-alone is not success)")

        // Explicit SOCKS transport check (app UID → 127.0.0.1:10808). Not a TUN proof.
        var socks: SocksHttpProbe.Result? = null
        val socksUntil = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < socksUntil) {
            socks = runCatching {
                SocksHttpProbe.httpGet("127.0.0.1", 10808, "198.51.100.10", origin.port, "/", 5000)
            }.getOrNull()
            if (socks?.ok == true && socks.status == 200) break
            Thread.sleep(400)
        }
        val socksOk = socks?.ok == true && socks.status == 200
        report.appendLine("transport_socks=${if (socksOk) "ok" else "fail"} viaSocks=${socks?.viaSocks} status=${socks?.status} err=${socks?.error}")
        report.appendLine("peer_kind=${if (xrayServer.port > 0) "xray-tls" else "raw-http"} peer_port=$dataPeerPort origin_port=${origin.port}")
        val muxInfo = runCatching {
            org.json.JSONObject(File(appCtx.filesDir, "xray-run.json").readText())
                .getJSONArray("outbounds").getJSONObject(0).optJSONObject("mux")?.toString()
        }.getOrNull()
        report.appendLine("xray_mux=$muxInfo")

        // Real TUN check from a different UID (shell). Same-UID HTTP is not
        // used, and origin.hits is not treated as TUN success (SOCKS may have
        // already incremented it).
        val route = device.executeShellCommand("ip route get 1.1.1.1") ?: ""
        report.appendLine("tun_route=${route.trim()}")
        val tunRouted = route.contains("tun", ignoreCase = true)
        device.executeShellCommand("su 0 ip addr add 198.51.100.10/32 dev lo")
        val tunOut = device.executeShellCommand(
            "sh -c 'printf \"GET / HTTP/1.0\\r\\nHost: fixture\\r\\n\\r\\n\" | toybox nc -w 4 198.51.100.10 ${origin.port}'"
        ) ?: ""
        val tunHttp = tunOut.contains("fixture-ok")
        report.appendLine("tun_shell_uid=${if (tunHttp) "http_ok" else if (tunRouted) "routed_no_http" else "fail"} tun_http=$tunHttp tun_routed=$tunRouted origin_hits=${origin.hits.get()} note=shell-UID; origin.hits not used as TUN proof")
        val dnsProp = device.executeShellCommand("getprop net.dns1") ?: ""
        report.appendLine("dns_prop=${dnsProp.trim()} note=DoT-853 not asserted in this fixture; checked separately from TCP idle")

        assertTrue(
            "SOCKS transport (explicit, not TUN / same-UID HTTP) failed; socks=$socksOk status=${socks?.status} err=${socks?.error} channel=${RazbanVpnService.lastChannelState} sawData=$sawData",
            socksOk,
        )
        assertTrue(
            "TUN not verified from a separate UID (no tun route and no shell HTTP); route=${route.trim()} http=$tunHttp",
            tunRouted || tunHttp,
        )

        Thread.sleep(21_000)
        val socksAfterIdle = runCatching {
            SocksHttpProbe.httpGet("127.0.0.1", 10808, "198.51.100.10", origin.port, "/")
        }.getOrNull()
        report.appendLine("after_idle_20s socks=${socksAfterIdle?.ok} status=${socksAfterIdle?.status} err=${socksAfterIdle?.error}")
        assertTrue(
            "SOCKS payload after idle>20s failed; the data path must not tear itself down",
            socksAfterIdle?.ok == true && socksAfterIdle.status == 200,
        )

        appCtx.startService(android.content.Intent(appCtx, RazbanVpnService::class.java).setAction(RazbanVpnService.ACTION_STOP))
        Thread.sleep(1_500)
        clickFirst(listOf("Подключить", "Connect"), 6_000)
        Thread.sleep(1_000)
        clickFirst(listOf("OK", "Ок", "Allow", "Разрешить"), 3_000)
        val recoverUntil = System.currentTimeMillis() + 20_000
        var socksRecover: SocksHttpProbe.Result? = null
        while (System.currentTimeMillis() < recoverUntil) {
            socksRecover = runCatching {
                SocksHttpProbe.httpGet("127.0.0.1", 10808, "198.51.100.10", origin.port, "/")
            }.getOrNull()
            if (socksRecover?.ok == true && socksRecover.status == 200) break
            Thread.sleep(500)
        }
        report.appendLine("recovery_status=${RazbanVpnService.lastStatus} recovery_channel=${RazbanVpnService.lastChannelState} recovery_socks=${socksRecover?.ok} recovery_status_code=${socksRecover?.status}")
        shot("06-recovery")
        assertTrue(
            "break/autoreconnect SOCKS failed; status=${RazbanVpnService.lastStatus} channel=${RazbanVpnService.lastChannelState} socks=${socksRecover?.ok}",
            socksRecover?.ok == true && socksRecover.status == 200,
        )

        File(shots, "e2e-report.txt").writeText(report.toString())
    }

    private fun clickFirst(labels: List<String>, waitMs: Long): Boolean {
        for (label in labels) {
            val obj = device.wait(Until.findObject(By.text(label)), waitMs / labels.size)
                ?: device.findObject(By.textContains(label))
                ?: device.findObject(By.desc(label))
                ?: device.findObject(By.descContains(label))
            if (obj != null) {
                obj.click()
                return true
            }
        }
        return false
    }

    private fun pickFromTestProvider(fileName: String) {
        // Real system picker. The root title TextView is not clickable, and its
        // clickable ancestor is the whole drawer — UiObject2.click() then hits
        // Downloads (log: "Root changed to: Downloads"). Tap the label's own
        // screen coordinates so the row receives the gesture.
        openRootsDrawer()
        assertTrue(
            "could not see ${FakeProfileDocumentsProvider.TITLE} root in DocumentsUI; ui=${visibleTexts()}",
            revealAndTap(FakeProfileDocumentsProvider.TITLE, "RazbanTestDocs"),
        )
        device.waitForIdle(2_000)
        Thread.sleep(600)
        dumpPicker("picker-inside-root")
        if (findLabel(fileName, "direct-profile") == null) {
            openRootsDrawer()
            revealAndTap(FakeProfileDocumentsProvider.TITLE, "RazbanTestDocs")
            device.waitForIdle(2_000)
            dumpPicker("picker-inside-root-retry")
        }
        assertTrue("could not see $fileName in DocumentsUI; ui=${visibleTexts()}", revealAndTap(fileName, "direct-profile"))
        device.waitForIdle(2_000)
    }

    private fun openRootsDrawer() {
        val btn = device.findObject(By.desc("Show roots"))
            ?: device.findObject(By.descContains("Show roots"))
            ?: device.findObject(By.descContains("Show root"))
            ?: device.findObject(By.text("Open from"))
        if (btn != null) {
            val b = btn.visibleBounds
            device.click(b.centerX(), b.centerY())
        }
        device.waitForIdle(1_000)
        Thread.sleep(500)
    }

    private fun findLabel(vararg labels: String): UiObject2? {
        for (label in labels) {
            val obj = device.findObject(By.text(label))
                ?: device.findObject(By.desc(label))
                ?: device.findObject(By.textContains(label))
                ?: device.findObject(By.descContains(label))
            if (obj != null) return obj
        }
        return null
    }

    private fun revealAndTap(vararg labels: String): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        var node = findLabel(*labels)
        while (node == null && System.currentTimeMillis() < deadline) {
            scrollDrawer()
            Thread.sleep(250)
            node = findLabel(*labels)
        }
        if (node == null) return false
        val bounds = node.visibleBounds
        if (bounds.width() <= 1 || bounds.centerY() !in 1 until (device.displayHeight - 1)) {
            scrollDrawer()
            node = findLabel(*labels) ?: return false
        }
        val b = node.visibleBounds
        device.click(b.centerX(), b.centerY())
        return true
    }

    private fun scrollDrawer() {
        val scroller = device.findObjects(By.scrollable(true)).firstOrNull()
        if (scroller != null) {
            scroller.scroll(Direction.DOWN, 0.6f)
        } else {
            device.swipe(
                device.displayWidth / 5,
                (device.displayHeight * 0.7).toInt(),
                device.displayWidth / 5,
                (device.displayHeight * 0.35).toInt(),
                25,
            )
        }
    }

    private fun visibleTexts(): String = try {
        device.findObjects(By.enabled(true))
            .mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } ?: it.contentDescription }
            .filter { !it.isNullOrBlank() }
            .distinct()
            .take(50)
            .joinToString(" | ")
    } catch (_: Exception) {
        "?"
    }

    private fun dumpPicker(name: String) {
        runCatching {
            val shots = File(appCtx.getExternalFilesDir(null), "ui-screenshots").apply { mkdirs() }
            device.takeScreenshot(File(shots, "$name.png"))
            device.dumpWindowHierarchy(File(shots, "$name.xml"))
        }
    }

    private fun mockDirectJson(url: String, hmacHex: String, peerPort: Int): String {
        val dataTls = JSONObject()
            .put("uuid", "11111111-2222-3333-4444-555555555555")
            .put("serverName", "127.0.0.1")
            .put("certificatePem", DIRECT_MOCK_CERT_PEM)
        return JSONObject()
            .put("razbanProfile", DirectProfile.KIND)
            .put("name", DirectProfile.DEFAULT_NAME)
            .put("signal", JSONObject()
                .put("url", url)
                .put("hostname", "127.0.0.1")
                .put("caPem", DIRECT_MOCK_CERT_PEM)
                .put("role", "device")
                .put("hmacKeyHex", hmacHex))
            .put("plan", JSONObject()
                .put("mode", "active")
                .put("device", JSONObject()
                    .put("bind", JSONArray().put("127.0.0.1").put(0))
                    .put("peer", JSONArray().put("127.0.0.1").put(peerPort))))
            .put("adapterPort", 19444)
            .put("dataTls", dataTls)
            .toString()
    }
}

private class FixtureHttpOrigin {
    private val server = ServerSocket(0)
    private val stop = AtomicBoolean(false)
    val port: Int get() = server.localPort
    val hits = java.util.concurrent.atomic.AtomicInteger()
    private val thread = Thread {
        while (!stop.get()) {
            val c = try { server.accept() } catch (_: Exception) { break }
            Thread {
                try {
                    c.getInputStream().read(ByteArray(1024))
                    hits.incrementAndGet()
                    val body = "fixture-ok"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    c.getOutputStream().write(resp.toByteArray())
                    c.getOutputStream().flush()
                } catch (_: Exception) {
                } finally {
                    try { c.close() } catch (_: Exception) {}
                }
            }.apply { isDaemon = true }.start()
        }
    }.apply { isDaemon = true }

    fun start() { thread.start() }
    fun close() {
        stop.set(true)
        try { server.close() } catch (_: Exception) {}
    }
}

private class FixtureXrayServer(
    val port: Int,
    private val proc: Process?,
) {
    fun close() {
        try { proc?.destroy() } catch (_: Exception) {}
    }

    companion object {
        fun start(ctx: android.content.Context, certPem: String, keyPem: String): FixtureXrayServer {
            val listen = ServerSocket(0).use { it.localPort }
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
            if (!bin.exists()) return FixtureXrayServer(-1, null)
            val certLines = JSONArray().also { arr -> certPem.trim().lines().forEach { arr.put(it) } }
            val keyLines = JSONArray().also { arr -> keyPem.trim().lines().forEach { arr.put(it) } }
            val cfg = JSONObject()
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", JSONArray().put(
                    JSONObject()
                        .put("listen", "127.0.0.1")
                        .put("port", listen)
                        .put("protocol", "vless")
                        .put("settings", JSONObject()
                            .put("clients", JSONArray().put(JSONObject().put("id", "11111111-2222-3333-4444-555555555555")))
                            .put("decryption", "none"))
                        .put("streamSettings", JSONObject()
                            .put("network", "tcp")
                            .put("security", "tls")
                            .put("tlsSettings", JSONObject()
                                .put("certificates", JSONArray().put(
                                    JSONObject().put("certificate", certLines).put("key", keyLines)
                                ))))
                ))
                .put("outbounds", JSONArray().put(JSONObject().put("protocol", "freedom").put("tag", "direct")))
            val cfgFile = File(ctx.cacheDir, "xray-fixture-server.json")
            cfgFile.writeText(cfg.toString())
            return try {
                val p = ProcessBuilder(bin.absolutePath, "run", "-c", cfgFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val deadline = System.currentTimeMillis() + 8000
                var up = false
                while (System.currentTimeMillis() < deadline) {
                    if (!p.isAlive) break
                    try {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", listen), 300) }
                        up = true
                        break
                    } catch (_: Exception) {
                        Thread.sleep(150)
                    }
                }
                if (!up) {
                    p.destroy()
                    FixtureXrayServer(-1, null)
                } else {
                    FixtureXrayServer(listen, p)
                }
            } catch (_: Exception) {
                FixtureXrayServer(-1, null)
            }
        }
    }
}
