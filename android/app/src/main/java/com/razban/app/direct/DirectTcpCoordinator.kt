package com.razban.app.direct

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Device (or test stub) signalling agent. TLS wraps a protect()+bindSocket
 * raw socket and verifies the coordinator hostname against the explicit CA.
 */
class DirectTcpCoordinator(
    private val profile: DirectProfile,
    networkProvider: () -> android.net.Network?,
    protectSocket: (Socket) -> Boolean,
) {
    private val sockets = ProtectedSockets(networkProvider, protectSocket)
    val clock = ClockEstimate()

    fun call(op: String, session: JSONObject? = null): JSONObject {
        val sid = session?.optString("session").orEmpty()
        val challenge = session?.optString("challenge").orEmpty()
        val body = SignalCodec.requestBody(
            op = op,
            session = sid.takeIf { it.isNotEmpty() && challenge.isNotEmpty() },
            challenge = challenge.takeIf { it.isNotEmpty() && sid.isNotEmpty() },
        )
        val mac = SignalCodec.macHex(profile.hmacKey, body)
        val uri = URI(profile.signalUrl)
        val port = if (uri.port > 0) uri.port else 443
        val t0 = SignalCodec.nowSeconds()
        val raw = sockets.open(InetSocketAddress(uri.host, port), timeoutMs = 2000, connect = true)
        val ssl = try {
            wrapTls(raw, profile.signalHostname, profile.caPem)
        } catch (t: Throwable) {
            try { raw.close() } catch (_: Exception) {}
            throw t
        }
        try {
            val path = (uri.rawPath.ifBlank { "/v1/session" }) +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val header = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: ${uri.host}\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("X-Role: ${profile.role}\r\n")
                append("X-MAC: $mac\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            val out = ssl.getOutputStream()
            out.write(header)
            out.write(body)
            out.flush()
            val (code, respBody) = readHttp(ssl.getInputStream())
            val t1 = SignalCodec.nowSeconds()
            val json = try { JSONObject(String(respBody, Charsets.UTF_8)) } catch (_: Exception) {
                throw IOException("signal_bad_json")
            }
            if (json.has("server_time")) clock.observe(t0, t1, json.getDouble("server_time"))
            if (code !in 200..299) {
                val reason = json.optString("error", "http_$code")
                throw IOException("signal_$reason")
            }
            return json
        } finally {
            try { ssl.close() } catch (_: Exception) {}
        }
    }

    fun armed(session: JSONObject): JSONObject {
        var s = call("ready", session)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (s.optString("state") != "armed") {
            if (System.nanoTime() > deadline) throw java.util.concurrent.TimeoutException("ready barrier")
            Thread.sleep(150)
            s = call("status", s)
        }
        if (!s.has("epoch") || s.isNull("epoch")) throw java.util.concurrent.TimeoutException("clock/barrier uncertainty")
        DirectTcpClock.validateArmed(clock.rtt, s.getDouble("epoch"), SignalCodec.nowSeconds(), clock.offset)
        return s
    }

    companion object {
        private const val TAG = "razban-direct"

        fun wrapTls(raw: Socket, hostname: String, caPem: String): SSLSocket {
            val factory = sslContextForCa(caPem).socketFactory
            val ssl = factory.createSocket(raw, hostname, raw.port, true) as SSLSocket
            ssl.startHandshake()
            val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, ssl.session)
            if (!ok) {
                try { ssl.close() } catch (_: Exception) {}
                throw SSLPeerUnverifiedException("hostname")
            }
            return ssl
        }

        fun sslContextForCa(caPem: String): SSLContext {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = ArrayList<X509Certificate>()
            val parts = caPem.split("-----END CERTIFICATE-----")
            for (part in parts) {
                if (!part.contains("BEGIN CERTIFICATE")) continue
                val pem = part.substring(part.indexOf("-----BEGIN CERTIFICATE-----")) + "-----END CERTIFICATE-----\n"
                certs.add(cf.generateCertificate(pem.byteInputStream(Charsets.US_ASCII)) as X509Certificate)
            }
            if (certs.isEmpty()) throw IOException("signal_ca")
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null)
            certs.forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, tmf.trustManagers, java.security.SecureRandom())
            return ctx
        }

        fun readHttp(input: InputStream): Pair<Int, ByteArray> {
            val headerBuf = ByteArrayOutputStream()
            var prev = 0
            var match = 0
            while (match < 4) {
                val b = input.read()
                if (b < 0) throw IOException("signal_truncated")
                headerBuf.write(b)
                match = if ((match == 0 || match == 2) && b == '\r'.code) match + 1
                else if ((match == 1 || match == 3) && b == '\n'.code) match + 1
                else if (b == '\r'.code) 1 else 0
                prev = b
                if (headerBuf.size() > 8192) throw IOException("signal_header")
            }
            val headers = headerBuf.toString("ISO-8859-1")
            val statusLine = headers.lineSequence().first()
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headers)?.groupValues?.get(1)?.toInt() ?: 0
            if (len < 0 || len > 8192) throw IOException("signal_body")
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) break
                off += n
            }
            if (off != len) throw IOException("signal_truncated")
            return code to body
        }

        fun redact(phase: String) {
            // Never log credentials, UUIDs, certificates or full configs.
            Log.d(TAG, phase)
        }
    }
}
