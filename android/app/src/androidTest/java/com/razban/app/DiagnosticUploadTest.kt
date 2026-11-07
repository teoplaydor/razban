package com.razban.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.razban.app.diag.DiagnosticSchema
import com.razban.app.diag.DiagnosticUploadException
import com.razban.app.diag.DiagnosticUploader
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

private const val MOCK_CERT_PEM = "-----BEGIN CERTIFICATE-----\n" +
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
private const val MOCK_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" +
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
private const val WRONG_CA_PEM = "-----BEGIN CERTIFICATE-----\nMIIDGTCCAgGgAwIBAgIUT5uuT3dWgT+1sqavTMcRQnEQTVowDQYJKoZIhvcNAQEL\nBQAwHDEaMBgGA1UEAwwRd3JvbmctaW5nZXN0LnRlc3QwHhcNMjYxMTA3MTIyNjQz\nWhcNMjYxMTA5MTIyNjQzWjAcMRowGAYDVQQDDBF3cm9uZy1pbmdlc3QudGVzdDCC\nASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAI3cCUNbbzzNJoIpslgy2j+Z\nUOsjnsecVV3bGXFWdxF9EcvO80rXMHCUZi9lhkwnaPizuoSEb/jxCVGs1UwHXaLP\nAC8lN/nU2/Iesyi7wNVG2D5rH+gnDB62vf4RZRUJLX9ysRpPEIBORFkLZ4UW3PnY\n5zy8wXU98HOWP2LwvAOJ6LKD/zGgCEsoAlx+VAnqVu5HAoyn7Dzi7yS8MDseoIGJ\nIjzsO9m7RmC6GO/Ccg55JWlHadCQD1jlOC57wGb7dBrEpA+RpxJQRSTgAnUYr+6X\nGMYhwCtMZejrEUnA18lwGnxmLKn2YoXcctFKYa4SZ2fIkYj7x9rkotXooSlB2Z8C\nAwEAAaNTMFEwHQYDVR0OBBYEFHu+w9h0TBLY/OlZ24WeI4KzkpZfMB8GA1UdIwQY\nMBaAFHu+w9h0TBLY/OlZ24WeI4KzkpZfMA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZI\nhvcNAQELBQADggEBAGLWMoq/usYG64R5ptkzVQGW1ay2K0ZiQ0Hod0RWLZ64k7Yo\n5+AU58Q7c0AjbGJcT2C3qfeD8L7WalGr/aSAd5awWCFSjj21Kb7O5GBq2jM9GGx9\nh4XZqOXRTVZxOriow9jI0khQWX6dD3DK9IpvGRSqb0MZ5qC4YHJTcshUxZLonRo2\nc/dyhZkbAqz9ekNEbVRlkZOphwpucITefk4/lOH9RlogG6FtbPoU5Y2BHUnsZBTB\nx2te6wPuw7s1paEZ6H0YWfaOWrp1k8Y89uq+jKgm861bv4hvNHCTUsB6xRC4OEa6\n6K/6pihF5gl0SEnvkNUQukd19bnBJoo6Lt/om6s=\n-----END CERTIFICATE-----\n"


@RunWith(AndroidJUnit4::class)
class DiagnosticUploadTest {
    private val cleanup = ArrayList<() -> Unit>()

    @After
    fun teardown() {
        cleanup.asReversed().forEach { run -> try { run() } catch (_: Exception) {} }
        cleanup.clear()
    }

    @Test
    fun mockHttpsUploadAccepted() {
        val server = MockIngestServer(sslContextFromPem(MOCK_CERT_PEM, MOCK_KEY_PEM))
        server.start()
        cleanup.add { server.close() }
        val key = ByteArray(32) { 9 }
        val device = "aa".repeat(16)
        val uploader = DiagnosticUploader(
            endpoint = "https://127.0.0.1:${server.port}",
            caPem = MOCK_CERT_PEM,
            expectedHost = "127.0.0.1",
            deviceId = device,
            hmacKey = key,
        )
        uploader.enroll()
        val sent = uploader.upload(listOf(sampleEvent()))
        assertEquals(1, sent)
        assertEquals(1, server.accepted.size)
        assertEquals("smoke", server.accepted[0].getString("stage"))
        assertEquals(device, server.lastDevice)
    }

    @Test
    fun rejectsBadAuth() {
        val server = MockIngestServer(sslContextFromPem(MOCK_CERT_PEM, MOCK_KEY_PEM))
        server.start()
        cleanup.add { server.close() }
        val goodKey = ByteArray(32) { 3 }
        val badKey = ByteArray(32) { 4 }
        val device = "bb".repeat(16)
        DiagnosticUploader(
            endpoint = "https://127.0.0.1:${server.port}",
            caPem = MOCK_CERT_PEM,
            expectedHost = "127.0.0.1",
            deviceId = device,
            hmacKey = goodKey,
        ).enroll()
        try {
            DiagnosticUploader(
                endpoint = "https://127.0.0.1:${server.port}",
                caPem = MOCK_CERT_PEM,
                expectedHost = "127.0.0.1",
                deviceId = device,
                hmacKey = badKey,
            ).upload(listOf(sampleEvent()))
            fail("expected auth rejection")
        } catch (e: DiagnosticUploadException) {
            assertEquals("auth", e.kind)
        }
        assertTrue(server.accepted.isEmpty())
    }

    @Test
    fun rejectsBadCert() {
        val server = MockIngestServer(sslContextFromPem(MOCK_CERT_PEM, MOCK_KEY_PEM))
        server.start()
        cleanup.add { server.close() }
        val key = ByteArray(32) { 5 }
        try {
            DiagnosticUploader(
                endpoint = "https://127.0.0.1:${server.port}",
                caPem = WRONG_CA_PEM,
                expectedHost = "127.0.0.1",
                deviceId = "cc".repeat(16),
                hmacKey = key,
            ).enroll()
            fail("expected cert rejection")
        } catch (e: DiagnosticUploadException) {
            assertEquals("cert", e.kind)
        } catch (e: Exception) {
            val text = (e.message ?: "") + e.javaClass.simpleName
            assertTrue("unexpected ${e.javaClass.name}: ${e.message}", text.contains("cert", true) || text.contains("SSL") || text.contains("Trust"))
        }
        assertTrue(server.accepted.isEmpty())
    }

    private fun sampleEvent(): JSONObject = JSONObject()
        .put("stage", "smoke")
        .put("app_version", "0.1.44")
        .put("network_type", "wifi")
        .put("session_id", "aabbccddeeff00112233445566778899")
        .also { assertTrue(DiagnosticSchema.redact(it) != null) }
}

private class MockIngestServer(sslContext: SSLContext) {
    private val server: SSLServerSocket = sslContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    private val stop = AtomicBoolean(false)
    private val thread: Thread
    private val keys = ConcurrentHashMap<String, ByteArray>()
    val accepted = CopyOnWriteArrayList<JSONObject>()
    @Volatile var lastDevice: String = ""
    val port: Int get() = server.localPort

    init {
        thread = Thread {
            while (!stop.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                Thread { handle(socket) }.apply { isDaemon = true; start() }
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
            val first = headers.lineSequence().first()
            val device = Regex("X-Device-Id:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1) ?: ""
            val ts = Regex("X-Ts:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1) ?: ""
            val nonce = Regex("X-Nonce:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1) ?: ""
            val mac = Regex("X-MAC:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1) ?: ""
            val (code, obj) = when {
                first.contains(" /v1/enroll") -> enroll(body, device)
                first.contains(" /v1/events") -> events(body, device, ts, nonce, mac)
                else -> 404 to JSONObject().put("error", "path")
            }
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

    private fun enroll(body: ByteArray, headerDevice: String): Pair<Int, JSONObject> {
        val o = JSONObject(body.toString(Charsets.UTF_8))
        val id = o.optString("device_id")
        val hex = o.optString("hmac_key_hex")
        if (id.length != 32 || hex.length != 64 || (headerDevice.isNotEmpty() && headerDevice != id)) {
            return 400 to JSONObject().put("error", "enroll")
        }
        keys[id] = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        lastDevice = id
        return 200 to JSONObject().put("ok", true)
    }

    private fun events(body: ByteArray, device: String, ts: String, nonce: String, mac: String): Pair<Int, JSONObject> {
        val key = keys[device] ?: return 401 to JSONObject().put("error", "unknown_device")
        val material = (ts + "." + nonce + ".").toByteArray(Charsets.UTF_8) + body
        val macInst = Mac.getInstance("HmacSHA256")
        macInst.init(SecretKeySpec(key, "HmacSHA256"))
        val expect = macInst.doFinal(material).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(expect.toByteArray(), mac.lowercase().toByteArray())) {
            return 401 to JSONObject().put("error", "mac")
        }
        val arr = JSONObject(body.toString(Charsets.UTF_8)).optJSONArray("events")
            ?: return 400 to JSONObject().put("error", "events")
        val kept = DiagnosticSchema.redactBatch(arr)
        for (i in 0 until kept.length()) accepted.add(kept.getJSONObject(i))
        lastDevice = device
        return 200 to JSONObject().put("accepted", kept.length())
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

private fun sslContextFromPem(certPem: String, keyPem: String): SSLContext {
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
