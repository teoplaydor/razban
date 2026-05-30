package com.razban.app.bg

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Subscribes to the in-process sing-box command server (the same one
 * [RazbanVpnService] creates) and mirrors its live status + connections into
 * volatile state the [com.razban.app.bridge.WebUiBridge] reads.
 *
 * This is the Android equivalent of the desktop `ClashApiClient` traffic stream
 * + `ThroughputTracker`: it turns the UI's `vpn.stats`, throughput bars and the
 * Apps/connections views from hard-coded zeros into real data. Connects over the
 * unix `command.sock` under filesDir (`commandServerListenPort = 0`), so no TCP
 * port is opened on the device.
 *
 * Subscribing to CommandConnections is also what switches ON traffic accounting
 * server-side (the TrafficManager only tracks once something subscribes), so
 * even the aggregate up/down in the status message depends on it.
 */
object CoreStatus : CommandClientHandler {

    // ── aggregate status (vpn.stats) ──
    @Volatile var connected = false;     private set
    @Volatile var uplink = 0L;           private set   // current up speed, bytes/s
    @Volatile var downlink = 0L;         private set   // current down speed, bytes/s
    @Volatile var uplinkTotal = 0L;      private set
    @Volatile var downlinkTotal = 0L;    private set
    @Volatile var memory = 0L;           private set

    // ── per-connection state, keyed by libbox connection id ──
    private class Conn(
        var host: String, var process: String, var network: String,
        var outbound: String, var up: Long, var down: Long,
    )
    private val conns = ConcurrentHashMap<String, Conn>()

    // computed once per status tick (~1s): host/process byte-rates (bytes/s)
    @Volatile private var hostRates: Map<String, LongArray> = emptyMap()      // host -> [up,down]
    @Volatile private var procRates: Map<String, LongArray> = emptyMap()      // process -> [up,down]
    private var prevHostTotals = HashMap<String, LongArray>()
    private var prevProcTotals = HashMap<String, LongArray>()

    private var client: CommandClient? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        running = true
        worker = Thread({
            var attempt = 0
            while (running && attempt < 60) {
                try {
                    val opts = CommandClientOptions().apply {
                        addCommand(Libbox.CommandStatus)
                        // Subscribing to connections installs the TrafficManager
                        // event hook server-side → turns ON traffic accounting and
                        // streams per-connection deltas.
                        addCommand(Libbox.CommandConnections)
                        statusInterval = 1_000_000_000L   // Go time.Duration → 1s
                    }
                    val c = Libbox.newCommandClient(this@CoreStatus, opts)
                    c.connect()
                    client = c
                    return@Thread
                } catch (e: Exception) {
                    attempt++
                    try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                }
            }
        }, "razban-core-status").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        try { client?.disconnect() } catch (_: Exception) {}
        client = null
        worker?.interrupt(); worker = null
        connected = false; uplink = 0; downlink = 0
        conns.clear(); hostRates = emptyMap(); procRates = emptyMap()
        prevHostTotals = HashMap(); prevProcTotals = HashMap()
    }

    // ── data the bridge serves ──

    /** {connected, uploadBytes, downloadBytes, uploadSpeed, downloadSpeed} */
    fun statsJson(connectedState: Boolean): JSONObject = JSONObject()
        .put("connected", connectedState)
        .put("uploadBytes", uplinkTotal).put("downloadBytes", downlinkTotal)
        .put("uploadSpeed", uplink).put("downloadSpeed", downlink)

    /** {hosts:[{host,upBps,downBps}], processes:[{process,upBps,downBps}]} */
    fun throughputJson(): JSONObject {
        val hosts = JSONArray()
        for ((h, r) in hostRates) {
            if (r[0] == 0L && r[1] == 0L) continue
            hosts.put(JSONObject().put("host", h).put("upBps", r[0]).put("downBps", r[1]))
        }
        val procs = JSONArray()
        for ((p, r) in procRates) {
            if (r[0] == 0L && r[1] == 0L) continue
            procs.put(JSONObject().put("process", p).put("upBps", r[0]).put("downBps", r[1]))
        }
        return JSONObject().put("hosts", hosts).put("processes", procs)
    }

    /** [{process,host,network,outbound,uploadBytes,downloadBytes}] — for the Apps tab. */
    fun connectionsJson(): JSONArray {
        val arr = JSONArray()
        for (c in conns.values) {
            arr.put(JSONObject()
                .put("process", c.process).put("host", c.host)
                .put("network", c.network).put("outbound", c.outbound)
                .put("uploadBytes", c.up).put("downloadBytes", c.down))
        }
        return arr
    }

    // ───────────────────── CommandClientHandler ─────────────────────

    override fun connected() {}
    override fun disconnected(message: String?) { uplink = 0; downlink = 0 }

    override fun writeStatus(message: StatusMessage) {
        connected = true
        uplink = message.uplink
        downlink = message.downlink
        uplinkTotal = message.uplinkTotal
        downlinkTotal = message.downlinkTotal
        memory = message.memory
        computeRates()
    }

    override fun writeConnectionEvents(events: ConnectionEvents) {
        try {
            if (events.reset) conns.clear()
            val it = events.iterator()
            while (it.hasNext()) {
                val ev = it.next()
                val id = ev.getID() ?: continue
                // A non-zero ClosedAt means the connection ended → drop it.
                if (ev.closedAt != 0L) { conns.remove(id); continue }
                val c: Connection? = ev.connection
                if (c != null) {
                    // created / full update — upsert with the connection's totals
                    val host = c.domain?.takeIf { d -> d.isNotEmpty() } ?: hostOf(c.destination)
                    conns[id] = Conn(
                        host = host,
                        process = processName(c),
                        network = c.network ?: "tcp",
                        outbound = c.outbound ?: "",
                        up = c.uplinkTotal,
                        down = c.downlinkTotal,
                    )
                } else {
                    // delta-only update (Connection omitted) — bump the existing row
                    // so per-host / per-process rates actually move.
                    conns[id]?.let { it.up += ev.uplinkDelta; it.down += ev.downlinkDelta }
                }
            }
        } catch (_: Exception) { /* keep prior snapshot */ }
    }

    // Unused streams.
    override fun setDefaultLogLevel(level: Int) {}
    override fun clearLogs() {}
    override fun writeLogs(messageList: LogIterator) {}
    override fun writeGroups(message: OutboundGroupIterator) {}
    override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
    override fun updateClashMode(newMode: String) {}

    // ───────────────────────── helpers ─────────────────────────

    /** Diff per-host/per-process cumulative byte totals against the previous
     *  ~1s snapshot to get a byte-rate. Called once per status tick. */
    private fun computeRates() {
        val hostTot = HashMap<String, LongArray>()
        val procTot = HashMap<String, LongArray>()
        for (c in conns.values) {
            hostTot.getOrPut(c.host) { LongArray(2) }.let { it[0] += c.up; it[1] += c.down }
            if (c.process.isNotEmpty())
                procTot.getOrPut(c.process) { LongArray(2) }.let { it[0] += c.up; it[1] += c.down }
        }
        hostRates = diff(hostTot, prevHostTotals)
        procRates = diff(procTot, prevProcTotals)
        prevHostTotals = hostTot
        prevProcTotals = procTot
    }

    private fun diff(cur: Map<String, LongArray>, prev: Map<String, LongArray>): Map<String, LongArray> {
        val out = HashMap<String, LongArray>(cur.size)
        for ((k, c) in cur) {
            val p = prev[k]
            val up = if (p != null) (c[0] - p[0]).coerceAtLeast(0) else 0
            val dn = if (p != null) (c[1] - p[1]).coerceAtLeast(0) else 0
            out[k] = longArrayOf(up, dn)
        }
        return out
    }

    /** ip:port (or [v6]:port) → host part. */
    private fun hostOf(dest: String?): String {
        if (dest.isNullOrEmpty()) return "?"
        val d = dest.trim()
        if (d.startsWith("[")) return d.substring(1, d.indexOf(']').coerceAtLeast(1))
        val colon = d.lastIndexOf(':')
        return if (colon > 0) d.substring(0, colon) else d
    }

    /** On Android the connection's ProcessInfo carries the package name in
     *  userName (set by ConnectionResolver); fall back to the path basename. */
    private fun processName(c: Connection): String {
        val pi = try { c.processInfo } catch (_: Exception) { null } ?: return ""
        val u = try { pi.userName } catch (_: Exception) { null }
        if (!u.isNullOrEmpty()) return u
        val path = try { pi.processPath } catch (_: Exception) { null } ?: return ""
        val slash = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
        return if (slash >= 0) path.substring(slash + 1) else path
    }
}
