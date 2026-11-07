package com.razban.app.diag

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException

class DiagnosticUploadException(val kind: String, message: String) : Exception(message)

class DiagnosticUploader(
    private val endpoint: String,
    private val caPem: String,
    private val expectedHost: String,
    private val deviceId: String,
    private val hmacKey: ByteArray,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 8_000,
) {
    fun enroll() {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("hmac_key_hex", hmacKey.joinToString("") { "%02x".format(it) })
            .toString()
            .toByteArray(Charsets.UTF_8)
        request("/v1/enroll", body, signed = false)
    }

    fun upload(events: List<JSONObject>): Int {
        val arr = JSONArray()
        events.forEach { DiagnosticSchema.redact(it)?.let { clean -> arr.put(clean) } }
        if (arr.length() == 0) return 0
        val body = JSONObject().put("events", arr).toString().toByteArray(Charsets.UTF_8)
        request("/v1/events", body, signed = true)
        return arr.length()
    }

    private fun request(path: String, body: ByteArray, signed: Boolean) {
        if (endpoint.isBlank()) throw DiagnosticUploadException("disabled", "no endpoint")
        val url = URL(endpoint.trimEnd('/') + path)
        if (url.protocol != "https") throw DiagnosticUploadException("tls", "https required")
        val conn = url.openConnection() as HttpsURLConnection
        val (factory, tm) = DiagnosticTls.factoryFromPem(caPem)
        conn.sslSocketFactory = factory
        // Strict cert path: only the pinned ingest CA is trusted. The peer
        // name must match the URL host (IP SAN on the dedicated ingest cert).
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
            if (!host.equals(url.host, true) && !host.equals(expectedHost, true)) return@HostnameVerifier false
            try {
                val certs = session.peerCertificates
                if (certs.isEmpty()) return@HostnameVerifier false
                tm.checkServerTrusted(
                    certs.filterIsInstance<java.security.cert.X509Certificate>().toTypedArray(),
                    "RSA",
                )
                val peer = certs[0] as java.security.cert.X509Certificate
                val names = peer.subjectAlternativeNames
                    ?.flatMap { entry -> entry.drop(1).map { v -> v.toString() } }
                    ?: emptyList()
                names.any { it.equals(host, true) || it.equals(expectedHost, true) } ||
                    peer.subjectX500Principal.name.contains("CN=$host")
            } catch (_: Exception) {
                false
            }
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Device-Id", deviceId)
        if (signed) {
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val mac = DiagnosticCredentials.sign(hmacKey, (ts + "." + nonce + ".").toByteArray() + body)
            conn.setRequestProperty("X-Ts", ts)
            conn.setRequestProperty("X-Nonce", nonce)
            conn.setRequestProperty("X-MAC", mac)
        }
        conn.outputStream.use { it.write(body) }
        val code = try {
            conn.responseCode
        } catch (e: SSLPeerUnverifiedException) {
            throw DiagnosticUploadException("cert", e.message ?: "cert")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            throw DiagnosticUploadException("cert", e.message ?: "handshake")
        }
        val text = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { src ->
                val buf = ByteArrayOutputStream()
                src.copyTo(buf)
                buf.toString("UTF-8")
            } ?: ""
        } finally {
            conn.disconnect()
        }
        when (code) {
            in 200..299 -> return
            401, 403 -> throw DiagnosticUploadException("auth", "http $code $text")
            else -> throw DiagnosticUploadException("http", "http $code")
        }
    }
}
