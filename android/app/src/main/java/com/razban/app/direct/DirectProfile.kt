package com.razban.app.direct

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Runtime-only Direct TCP profile. Credentials stay in private app storage,
 * never in the APK, CI YAML, or logs. A missing/invalid document fails closed
 * — this is not a VLESS URI and there is no fake fallback.
 */
data class DirectProfile(
    val name: String,
    val signalUrl: String,
    val signalHostname: String,
    val caPem: String,
    val role: String,
    val hmacKey: ByteArray,
    val plan: FixedPlan,
    val adapterPort: Int,
    val xrayJson: String,
) {
    fun settingsJson(): JSONObject {
        return JSONObject()
            .put("razbanProfile", KIND)
            .put("name", name)
            .put("signal", JSONObject()
                .put("url", signalUrl)
                .put("hostname", signalHostname)
                .put("caPem", caPem)
                .put("role", role))
            .put("plan", plan.toJson())
            .put("adapterPort", adapterPort)
            .put("xray", JSONObject(xrayJson))
    }

    companion object {
        const val KIND = "direct-tcp-experimental"
        const val DEFAULT_NAME = "DirectTCPexperimental"
        const val DEFAULT_ADAPTER_PORT = 19444

        fun parse(raw: String): DirectProfile {
            val root = try { JSONObject(raw) } catch (e: Exception) {
                throw IllegalArgumentException("direct_profile_not_json")
            }
            if (root.optString("razbanProfile") != KIND)
                throw IllegalArgumentException("direct_profile_kind")
            if (raw.contains("BEGIN PRIVATE KEY") || raw.contains("BEGIN RSA PRIVATE KEY") ||
                raw.contains("BEGIN EC PRIVATE KEY") || raw.contains("BEGIN OPENSSH PRIVATE KEY")
            ) throw IllegalArgumentException("private_key_forbidden")

            val signal = root.optJSONObject("signal") ?: throw IllegalArgumentException("signal_missing")
            val url = signal.optString("url")
            if (!url.startsWith("https://")) throw IllegalArgumentException("signal_url")
            val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("signal_url") }
            val host = signal.optString("hostname").ifBlank { uri.host ?: "" }
            if (host.isBlank()) throw IllegalArgumentException("signal_hostname")
            val caPem = signal.optString("caPem")
            if (!caPem.contains("BEGIN CERTIFICATE")) throw IllegalArgumentException("signal_ca")
            val role = signal.optString("role", "device")
            if (role != "device" && role != "server") throw IllegalArgumentException("signal_role")
            val hmacHex = signal.optString("hmacKeyHex")
            if (hmacHex.length != 64 || hmacHex.any { it !in "0123456789abcdefABCDEF" })
                throw IllegalArgumentException("signal_hmac")
            val plan = try { FixedPlan.from(root.getJSONObject("plan")) } catch (_: Exception) {
                throw IllegalArgumentException("plan")
            }
            if (plan.mode != "active" && plan.mode != "loopback-control")
                throw IllegalArgumentException("plan_mode")
            if (role == "device" && plan.devicePeer == null) throw IllegalArgumentException("device_peer")
            val adapterPort = root.optInt("adapterPort", DEFAULT_ADAPTER_PORT)
            if (adapterPort < 0 || adapterPort > 65535) throw IllegalArgumentException("adapter_port")

            val xrayJson = when {
                root.has("xray") -> validateXray(root.getJSONObject("xray").toString(), adapterPort)
                root.has("dataTls") -> stockClientXray(root.getJSONObject("dataTls"), adapterPort)
                else -> throw IllegalArgumentException("xray_or_data_tls")
            }
            val name = root.optString("name").ifBlank { DEFAULT_NAME }
            return DirectProfile(
                name = name,
                signalUrl = url,
                signalHostname = host,
                caPem = caPem,
                role = role,
                hmacKey = SignalCodec.hexDecode(hmacHex.lowercase()),
                plan = plan,
                adapterPort = adapterPort,
                xrayJson = xrayJson,
            )
        }

        fun tryParse(raw: String): DirectProfile? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{") || !trimmed.contains("\"razbanProfile\"")) return null
            return parse(trimmed)
        }

        fun stockClientXray(dataTls: JSONObject, adapterPort: Int): String {
            val uuid = dataTls.optString("uuid")
            val serverName = dataTls.optString("serverName").ifBlank { "127.0.0.1" }
            val certPem = dataTls.optString("certificatePem")
            if (uuid.length != 36) throw IllegalArgumentException("data_uuid")
            if (!certPem.contains("BEGIN CERTIFICATE")) throw IllegalArgumentException("data_cert")
            if (dataTls.has("privateKey") || certPem.contains("BEGIN PRIVATE KEY"))
                throw IllegalArgumentException("private_key_forbidden")
            val certLines = JSONArray()
            certPem.trim().lines().forEach { certLines.put(it) }
            val user = JSONObject().put("id", uuid).put("encryption", "none")
            val vnext = JSONObject().put("address", "127.0.0.1").put("port", adapterPort)
                .put("users", JSONArray().put(user))
            val tls = JSONObject()
                .put("serverName", serverName)
                .put("allowInsecure", false)
                .put("disableSystemRoot", true)
                .put("certificates", JSONArray().put(JSONObject().put("usage", "verify").put("certificate", certLines)))
            val stream = JSONObject().put("network", "tcp").put("security", "tls").put("tlsSettings", tls)
            // One physical VLESS parent — the DE edge only active-opens the planned
            // 4-tuple and does not accept extra ordinary/ephemeral connects.
            val mux = JSONObject().put("enabled", true).put("concurrency", 32)
                .put("xudpConcurrency", -1).put("xudpProxyUDP443", "reject")
            val outbound = JSONObject().put("protocol", "vless")
                .put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))
                .put("streamSettings", stream)
                .put("mux", mux)
            val inbound = JSONObject().put("listen", "127.0.0.1").put("port", 10808)
                .put("protocol", "socks")
                .put("settings", JSONObject().put("auth", "noauth").put("udp", false))
            return JSONObject()
                .put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", JSONArray().put(inbound))
                .put("outbounds", JSONArray().put(outbound))
                .toString()
        }

        fun validateXray(json: String, adapterPort: Int): String {
            if (json.contains("BEGIN PRIVATE KEY") || json.contains("BEGIN RSA PRIVATE KEY"))
                throw IllegalArgumentException("private_key_forbidden")
            val root = JSONObject(json)
            val obs = root.optJSONArray("outbounds") ?: throw IllegalArgumentException("xray_outbounds")
            var ok = false
            for (i in 0 until obs.length()) {
                val o = obs.optJSONObject(i) ?: continue
                if (o.optString("protocol") != "vless") continue
                val vnext = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)
                    ?: throw IllegalArgumentException("xray_vnext")
                val address = vnext.optString("address")
                val port = vnext.optInt("port")
                if (address != "127.0.0.1" && address != "localhost")
                    throw IllegalArgumentException("xray_must_target_adapter")
                if (port != adapterPort) throw IllegalArgumentException("xray_adapter_port")
                val tls = o.optJSONObject("streamSettings")?.optJSONObject("tlsSettings")
                    ?: throw IllegalArgumentException("xray_tls")
                if (tls.optBoolean("allowInsecure", false)) throw IllegalArgumentException("allowInsecure_forbidden")
                ok = true
            }
            if (!ok) throw IllegalArgumentException("xray_vless")
            return root.toString()
        }

        fun singboxViaSocks(): String {
            return JSONObject()
                .put("log", JSONObject().put("level", "warn"))
                .put("dns", JSONObject()
                    .put("servers", JSONArray().put(JSONObject()
                        .put("tag", "dns-remote").put("type", "tcp").put("server", "8.8.8.8")))
                    .put("final", "dns-remote"))
                .put("inbounds", JSONArray().put(JSONObject()
                    .put("type", "tun").put("tag", "tun-in")
                    .put("address", JSONArray().put("172.19.0.1/30"))
                    .put("mtu", 1420).put("auto_route", true).put("stack", "gvisor")))
                .put("outbounds", JSONArray()
                    .put(JSONObject().put("type", "socks").put("tag", "proxy")
                        .put("server", "127.0.0.1").put("server_port", 10808).put("version", "5"))
                    .put(JSONObject().put("type", "direct").put("tag", "direct")))
                .put("route", JSONObject()
                    .put("rules", JSONArray()
                        .put(JSONObject().put("action", "sniff"))
                        .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
                        // TUN self-address (172.19.0.1/30). Android/apps may
                        // send DNS/DoT there; proxying it to the remote
                        // freedom hits Xray 26's private-IP block (and would
                        // also fail on the DE edge). Handle locally.
                        .put(JSONObject()
                            .put("ip_cidr", JSONArray().put("172.19.0.0/30"))
                            .put("port", 53)
                            .put("action", "hijack-dns"))
                        .put(JSONObject()
                            .put("ip_cidr", JSONArray().put("172.19.0.0/30"))
                            .put("action", "reject")))
                    .put("final", "proxy")
                    .put("auto_detect_interface", true))
                .toString()
        }
    }
}
