package com.razban.app.direct

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HTTP(S) probe that MUST go through an explicit local SOCKS hop.
 * In Xray mode the app UID is excluded from the TUN, so a plain
 * HttpURLConnection can false-green by bypassing the VPN.
 */
object SocksHttpProbe {
    data class Result(val ok: Boolean, val status: Int, val viaSocks: Boolean, val error: String? = null)

    fun httpGet(socksHost: String, socksPort: Int, host: String, port: Int, path: String = "/", timeoutMs: Int = 4000): Result {
        val socket = Socket()
        return try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greet = ByteArray(2)
            readFully(inp, greet)
            if (greet[0] != 5.toByte() || greet[1] != 0.toByte()) throw IOException("socks_auth")
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x03
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            val p = 5 + hostBytes.size
            req[p] = ((port ushr 8) and 0xff).toByte()
            req[p + 1] = (port and 0xff).toByte()
            out.write(req)
            out.flush()
            val reply = ByteArray(10)
            val n = inp.read(reply)
            if (n < 2 || reply[1] != 0.toByte()) throw IOException("socks_connect")
            val http = "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
            out.write(http)
            out.flush()
            val buf = ByteArray(256)
            val got = inp.read(buf)
            val text = if (got > 0) String(buf, 0, got, Charsets.US_ASCII) else ""
            val status = Regex("^HTTP/1\\.[01]\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            Result(status in 200..399, status, true)
        } catch (t: Throwable) {
            Result(false, 0, false, t.javaClass.simpleName)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof")
            off += n
        }
    }
}
