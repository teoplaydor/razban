package com.razban.app.diag

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object DiagnosticTls {
    fun factoryFromPem(pem: String): Pair<SSLSocketFactory, X509TrustManager> {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = mutableListOf<X509Certificate>()
        val chunks = pem.split("-----END CERTIFICATE-----")
        for (chunk in chunks) {
            if (!chunk.contains("BEGIN CERTIFICATE")) continue
            val block = (chunk.substring(chunk.indexOf("-----BEGIN CERTIFICATE-----")) + "-----END CERTIFICATE-----\n").toByteArray()
            certs += cf.generateCertificate(ByteArrayInputStream(block)) as X509Certificate
        }
        require(certs.isNotEmpty()) { "no certificates" }
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null)
        certs.forEachIndexed { i, c -> ks.setCertificateEntry("diag-ca-$i", c) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return ctx.socketFactory to tm
    }
}
