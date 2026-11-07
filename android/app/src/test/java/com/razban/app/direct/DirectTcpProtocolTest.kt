package com.razban.app.direct

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DirectTcpProtocolTest {
    private lateinit var keys: Map<String, ByteArray>
    private lateinit var state: SessionState
    private val plan = FixedPlan(mode = "loopback-control")

    @Before
    fun setup() {
        keys = mapOf(
            "device" to SignalCodec.hexDecode(SignalCodec.randomHex(32)),
            "server" to SignalCodec.hexDecode(SignalCodec.randomHex(32)),
        )
        state = SessionState(keys, plan, budget = 1)
    }

    private fun raw(op: String = "join", extra: JSONObject = JSONObject()): ByteArray {
        extra.put("op", op)
        extra.put("nonce", SignalCodec.randomHex(16))
        extra.put("at", SignalCodec.nowSeconds())
        return extra.toString().toByteArray(Charsets.UTF_8)
    }

    private fun call(role: String = "device", op: String = "join", extra: JSONObject = JSONObject()): JSONObject {
        val body = raw(op, extra)
        return state.operate(role, body, SignalCodec.macHex(keys.getValue(role), body))
    }

    private fun expect(reason: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected Reject($reason)")
        } catch (e: Reject) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun authentication() {
        expect("authentication") {
            state.operate("device", raw(), "0".repeat(64))
        }
    }

    @Test
    fun unknownRole() {
        expect("identity_or_size") {
            state.operate("stranger", raw(), "0".repeat(64))
        }
    }

    @Test
    fun replay() {
        val body = raw()
        val sig = SignalCodec.macHex(keys.getValue("device"), body)
        state.operate("device", body, sig)
        expect("replay") { state.operate("device", body, sig) }
    }

    @Test
    fun atomicReplay() {
        val body = raw()
        val sig = SignalCodec.macHex(keys.getValue("device"), body)
        val pool = Executors.newFixedThreadPool(2)
        val futures = (0 until 2).map {
            pool.submit<String> {
                try {
                    state.operate("device", body, sig)
                    "accepted"
                } catch (e: Reject) {
                    e.reason
                }
            }
        }
        val results = futures.map { it.get(2, TimeUnit.SECONDS) }
        pool.shutdownNow()
        assertEquals(setOf("accepted", "replay"), results.toSet())
    }

    @Test
    fun expiredTimestamp() {
        val body = JSONObject()
            .put("op", "join")
            .put("at", SignalCodec.nowSeconds() - 11)
            .put("nonce", SignalCodec.randomHex(16))
            .toString().toByteArray()
        val sig = SignalCodec.macHex(keys.getValue("device"), body)
        expect("expired_timestamp") { state.operate("device", body, sig) }
    }

    @Test
    fun roleSeparation() {
        expect("role") { call(role = "server", op = "join") }
    }

    @Test
    fun wrongChallenge() {
        val s = call()
        expect("session_or_challenge") {
            call(op = "ready", extra = JSONObject().put("session", s.getString("session")).put("challenge", "0".repeat(32)))
        }
    }

    @Test
    fun barrierAndBudget() {
        val s = call()
        val ids = JSONObject().put("session", s.getString("session")).put("challenge", s.getString("challenge"))
        val waiting = call(op = "ready", extra = JSONObject(ids.toString()))
        assertEquals("waiting", waiting.getString("state"))
        val armed = call(role = "server", op = "ready", extra = JSONObject(ids.toString()))
        assertEquals("armed", armed.getString("state"))
        val epoch = armed.getDouble("epoch")
        assertTrue(epoch - SignalCodec.nowSeconds() in 0.0..1.0)
        call(op = "done", extra = JSONObject(ids.toString()))
        call(role = "server", op = "done", extra = JSONObject(ids.toString()))
        expect("session_budget") { call() }
    }

    @Test
    fun joinWaitsForPeerCleanup() {
        val s = call()
        val ids = JSONObject().put("session", s.getString("session")).put("challenge", s.getString("challenge"))
        call(op = "ready", extra = JSONObject(ids.toString()))
        call(role = "server", op = "ready", extra = JSONObject(ids.toString()))
        call(op = "done", extra = JSONObject(ids.toString()))
        assertEquals("busy", call().getString("state"))
    }

    @Test
    fun expiredSession() {
        val s = call()
        state.session!!.expires = SignalCodec.nowSeconds() - 1
        val ids = JSONObject().put("session", s.getString("session")).put("challenge", s.getString("challenge"))
        expect("session_closed") { call(op = "ready", extra = JSONObject(ids.toString())) }
    }

    @Test
    fun rateCap() {
        repeat(40) { call() }
        expect("rate_or_nonce_cap") { call() }
    }

    @Test
    fun fixedPlanNotOverridden() {
        val s = call(extra = JSONObject().put("peer", "arbitrary.invalid:22"))
        assertEquals("loopback-control", s.getJSONObject("plan").getString("mode"))
        assertNotEquals("arbitrary.invalid:22", s.getJSONObject("plan").optString("peer"))
    }

    @Test
    fun sizeLimit() {
        expect("identity_or_size") {
            state.operate("device", ByteArray(2049) { 'x'.code.toByte() }, "0".repeat(64))
        }
    }

    @Test
    fun clockRejectsLateEpoch() {
        try {
            DirectTcpClock.startDelay(SignalCodec.nowSeconds() - 1, SignalCodec.nowSeconds(), 0.0)
            throw AssertionError("expected timeout")
        } catch (e: java.util.concurrent.TimeoutException) {
            assertEquals("start window", e.message)
        }
    }
}
