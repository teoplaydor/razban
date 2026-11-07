package com.razban.app.direct

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Rejected signalling request. Never includes application payload. */
class Reject(val code: Int, val reason: String) : Exception(reason)

data class SocketRef(val host: String, val port: Int) {
    fun toJsonArray(): org.json.JSONArray = org.json.JSONArray().put(host).put(port)

    companion object {
        fun from(arr: org.json.JSONArray?): SocketRef? {
            if (arr == null || arr.length() < 2) return null
            val port = arr.getInt(1)
            if (port <= 0 || port > 65535) return null
            val host = arr.getString(0)
            if (host.isBlank()) return null
            return SocketRef(host, port)
        }
    }
}

/** Operator-fixed endpoints. Signalling never accepts an arbitrary peer. */
data class FixedPlan(
    val mode: String,
    val deviceBind: SocketRef? = null,
    val devicePeer: SocketRef? = null,
    val serverBind: SocketRef? = null,
    val serverPeer: SocketRef? = null,
) {
    fun toJson(): JSONObject {
        val o = JSONObject().put("mode", mode)
        val device = JSONObject()
        deviceBind?.let { device.put("bind", it.toJsonArray()) }
        devicePeer?.let { device.put("peer", it.toJsonArray()) }
        if (device.length() > 0) o.put("device", device)
        val server = JSONObject()
        serverBind?.let { server.put("bind", it.toJsonArray()) }
        serverPeer?.let { server.put("peer", it.toJsonArray()) }
        if (server.length() > 0) o.put("server", server)
        return o
    }

    companion object {
        fun from(obj: JSONObject): FixedPlan {
            val device = obj.optJSONObject("device")
            val server = obj.optJSONObject("server")
            return FixedPlan(
                mode = obj.getString("mode"),
                deviceBind = SocketRef.from(device?.optJSONArray("bind")),
                devicePeer = SocketRef.from(device?.optJSONArray("peer")),
                serverBind = SocketRef.from(server?.optJSONArray("bind")),
                serverPeer = SocketRef.from(server?.optJSONArray("peer")),
            )
        }
    }
}

object SignalCodec {
    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

    fun hexEncode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) append("%02x".format(b))
    }

    fun hexDecode(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "hex" }
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return hexEncode(buf)
    }

    fun macHex(key: ByteArray, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hexEncode(mac.doFinal(body))
    }

    fun macMatches(key: ByteArray, body: ByteArray, signature: String?): Boolean {
        val expected = macHex(key, body)
        val got = signature ?: ""
        val a = expected.toByteArray(Charsets.US_ASCII)
        val b = got.toByteArray(Charsets.US_ASCII)
        return a.size == b.size && MessageDigest.isEqual(a, b)
    }

    fun requestBody(
        op: String,
        at: Double = nowSeconds(),
        nonce: String = randomHex(16),
        session: String? = null,
        challenge: String? = null,
    ): ByteArray {
        val o = JSONObject().put("op", op).put("at", at).put("nonce", nonce)
        if (session != null) o.put("session", session)
        if (challenge != null) o.put("challenge", challenge)
        return o.toString().toByteArray(Charsets.UTF_8)
    }
}

data class ClockEstimate(var offset: Double = 0.0, var rtt: Double = 0.0) {
    fun observe(t0: Double, t1: Double, serverTime: Double) {
        rtt = t1 - t0
        offset = serverTime - (t0 + t1) / 2.0
    }
}

object DirectTcpClock {
    fun validateArmed(rtt: Double, epoch: Double, now: Double, offset: Double) {
        if (rtt > 0.5) throw java.util.concurrent.TimeoutException("clock/barrier uncertainty")
        val delta = epoch - (now + offset)
        if (delta < 0 || delta > 2) throw java.util.concurrent.TimeoutException("clock/barrier uncertainty")
    }

    fun startDelay(epoch: Double, now: Double, offset: Double): Double {
        val delay = epoch - (now + offset)
        if (delay < 0 || delay > 2) throw java.util.concurrent.TimeoutException("start window")
        return delay
    }
}

/**
 * Coordinator session state machine. Used by tests and the in-process mock
 * coordinator. Production phones talk to a remote coordinator over HTTPS;
 * they do not host this server.
 */
class SessionState(
    private val keys: Map<String, ByteArray>,
    val plan: FixedPlan,
    private val budget: Int = 4,
) {
    data class Session(
        val id: String,
        val challenge: String,
        var expires: Double,
        var state: String,
        val ready: MutableSet<String> = HashSet(),
        val done: MutableSet<String> = HashSet(),
        var epoch: Double? = null,
    )

    private val createdLock = Any()
    private var created = 0
    var session: Session? = null
    private val nonces = keys.keys.associateWith { HashMap<String, Double>() }
    private val rate = keys.keys.associateWith { ArrayDeque<Double>() }
    val events = ArrayList<Map<String, Any?>>()

    fun event(phase: String, extra: Map<String, Any?> = emptyMap()) {
        val row = LinkedHashMap<String, Any?>()
        row["at"] = SignalCodec.nowSeconds()
        row["phase"] = phase
        row.putAll(extra)
        events.add(row)
    }

    fun authorize(role: String?, body: ByteArray, signature: String?, now: Double = SignalCodec.nowSeconds()): JSONObject {
        if (role == null || role !in keys || body.size > 2048) throw Reject(401, "identity_or_size")
        if (!SignalCodec.macMatches(keys.getValue(role), body, signature)) throw Reject(401, "authentication")
        val obj = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { throw Reject(400, "format") }
        val nonce = obj.opt("nonce")
        if (nonce !is String || nonce.length != 32 || nonce.any { it !in "0123456789abcdef" }) throw Reject(400, "nonce")
        val at = try { obj.getDouble("at") } catch (_: Exception) { throw Reject(400, "format") }
        if (abs(now - at) > 10) throw Reject(401, "expired_timestamp")
        val seen = nonces.getValue(role)
        val stale = seen.filter { it.value < now - 30 }.keys.toList()
        for (n in stale) seen.remove(n)
        if (nonce in seen) throw Reject(409, "replay")
        val q = rate.getValue(role)
        while (q.isNotEmpty() && q.first() < now - 2) q.removeFirst()
        if (q.size >= 40 || seen.size >= 256) throw Reject(429, "rate_or_nonce_cap")
        seen[nonce] = now
        q.addLast(now)
        return obj
    }

    fun operate(role: String?, body: ByteArray, signature: String?): JSONObject = synchronized(createdLock) {
        val req = authorize(role, body, signature)
        val op = req.optString("op")
        val now = SignalCodec.nowSeconds()
        session?.let { s ->
            if (s.expires < now && s.state != "closed") {
                s.state = "closed"
                event("session_expired")
            }
        }
        if (op == "join") {
            if (role != "device") throw Reject(403, "role")
            val s = session
            if (s != null && s.state != "closed" && role in s.done) return JSONObject().put("state", "busy")
            val cur = if (s == null || s.state == "closed") {
                if (created >= budget) throw Reject(429, "session_budget")
                created += 1
                Session(
                    id = SignalCodec.randomHex(16),
                    challenge = SignalCodec.randomHex(16),
                    expires = now + 15,
                    state = "waiting",
                ).also {
                    session = it
                    event("joined", mapOf("number" to created))
                }
            } else s
            return publicView(cur)
        }
        if (op == "next") {
            if (role != "server") throw Reject(403, "role")
            val s = session
            return if (s != null && s.state != "closed" && role !in s.done) publicView(s)
            else JSONObject().put("state", "idle")
        }
        val s = session ?: throw Reject(409, "session_or_challenge")
        if (req.optString("session") != s.id || req.optString("challenge") != s.challenge)
            throw Reject(409, "session_or_challenge")
        when (op) {
            "ready" -> {
                if (s.state != "waiting" && s.state != "armed") throw Reject(409, "session_closed")
                s.ready.add(role!!)
                if (s.ready.size == 2 && s.epoch == null) {
                    s.epoch = now + 0.7
                    s.state = "armed"
                    event("armed", mapOf("number" to created))
                }
            }
            "done" -> {
                s.done.add(role!!)
                if (s.done.size == 2) {
                    s.state = "closed"
                    event("closed", mapOf("number" to created))
                }
            }
            "status" -> {}
            else -> throw Reject(400, "operation")
        }
        return publicView(s)
    }

    fun publicView(s: Session): JSONObject {
        val o = JSONObject()
            .put("session", s.id)
            .put("challenge", s.challenge)
            .put("expires", s.expires)
            .put("state", s.state)
            .put("plan", plan.toJson())
        if (s.epoch == null) o.put("epoch", JSONObject.NULL) else o.put("epoch", s.epoch)
        return o
    }
}
