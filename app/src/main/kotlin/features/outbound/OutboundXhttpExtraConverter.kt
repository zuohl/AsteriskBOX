// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import engine.singbox.config.SingBoxJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import org.json.JSONObject

internal object OutboundXhttpExtraConverter {

    private val FIELD_MAPPINGS = arrayOf(
        "headers" to "headers",
        "xPaddingBytes" to "x_padding_bytes",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "serverMaxHeaderBytes" to "server_max_header_bytes",
        "xPaddingObfsMode" to "x_padding_obfs_mode",
        "xPaddingKey" to "x_padding_key",
        "xPaddingHeader" to "x_padding_header",
        "xPaddingPlacement" to "x_padding_placement",
        "xPaddingMethod" to "x_padding_method",
        "uplinkHTTPMethod" to "uplink_http_method",
        "sessionIDPlacement" to "session_placement",
        "sessionIDKey" to "session_key",
        "sessionIDTable" to "session_id_table",
        "sessionIDLength" to "session_id_length",
        "seqPlacement" to "seq_placement",
        "seqKey" to "seq_key",
        "uplinkDataPlacement" to "uplink_data_placement",
        "uplinkDataKey" to "uplink_data_key",
        "uplinkChunkSize" to "uplink_chunk_size",
    )

    private val XMUX_MAPPINGS = arrayOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    val KNOWN_XHTTP_BASE_FIELDS = setOf(
        "type",
        "host",
        "path",
        "mode",
        "headers",
        "x_padding_bytes",
        "no_grpc_header",
        "extra",
    )

    fun xrayToSingBox(xrayExtra: String): String {
        if (xrayExtra.isBlank()) return ""
        return try {
            val xray = JSONObject(xrayExtra)
            if (isSingBoxFormat(xray)) return xrayExtra
            val singBox = JSONObject()

            convertFields(xray, singBox, FIELD_MAPPINGS)
            if (xray.has("xmux")) {
                convertXmux(xray, singBox)
            }

            if (xray.has("downloadSettings")) {
                val xrayDown = xray.getJSONObject("downloadSettings")
                val singBoxDown = JSONObject()

                xrayDown.optJSONObject("xhttpSettings")?.let { xhttpSettings ->
                    convertField(xhttpSettings, singBoxDown, "mode", "mode")
                    convertField(xhttpSettings, singBoxDown, "host", "host")
                    convertField(xhttpSettings, singBoxDown, "path", "path")
                    convertFields(xhttpSettings, singBoxDown, FIELD_MAPPINGS)
                    if (xhttpSettings.has("xmux")) {
                        convertXmux(xhttpSettings, singBoxDown)
                    }
                    xhttpSettings.optJSONObject("extra")?.let { extra ->
                        convertFields(extra, singBoxDown, FIELD_MAPPINGS)
                        if (extra.has("xmux")) {
                            convertXmux(extra, singBoxDown)
                        }
                    }
                }
                convertField(xrayDown, singBoxDown, "address", "server")
                convertField(xrayDown, singBoxDown, "port", "server_port")

                if (xrayDown.has("security")) {
                    val tls = JSONObject().apply { put("enabled", true) }
                    when (xrayDown.getString("security")) {
                        "tls" -> {
                            xrayDown.optJSONObject("tlsSettings")?.let { tlsSettings ->
                                convertField(tlsSettings, tls, "serverName", "server_name")
                                convertField(tlsSettings, tls, "alpn", "alpn")
                                convertField(tlsSettings, tls, "allowInsecure", "insecure")
                                tlsSettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                        "reality" -> {
                            xrayDown.optJSONObject("realitySettings")?.let { realitySettings ->
                                convertField(realitySettings, tls, "serverName", "server_name")
                                val reality = JSONObject().apply {
                                    put("enabled", true)
                                    convertField(realitySettings, this, "publicKey", "public_key")
                                    convertField(realitySettings, this, "shortId", "short_id")
                                }
                                tls.put("reality", reality)
                                realitySettings.optString("fingerprint")?.let { fp ->
                                    if (fp.isNotBlank()) {
                                        val utls = JSONObject().apply {
                                            put("enabled", true)
                                            put("fingerprint", fp)
                                        }
                                        tls.put("utls", utls)
                                    }
                                }
                            }
                        }
                    }
                    singBoxDown.put("tls", tls)
                }

                if (singBoxDown.length() > 0) singBox.put("download", singBoxDown)
            }

            singBox.toString(2).replace("\\/", "/")
        } catch (_: Exception) {
            xrayExtra
        }
    }

    fun singBoxToXray(singBoxExtra: String): String {
        if (singBoxExtra.isBlank()) return ""
        return try {
            val singBox = JSONObject(singBoxExtra)
            if (isXrayFormat(singBox)) return singBoxExtra
            val xray = JSONObject()

            convertFieldsReverse(singBox, xray, FIELD_MAPPINGS)
            if (singBox.has("xmux")) {
                convertXmuxReverse(singBox, xray)
            }

            if (singBox.has("download")) {
                val singBoxDown = singBox.getJSONObject("download")
                val xrayDown = JSONObject()

                convertField(singBoxDown, xrayDown, "server", "address")
                convertField(singBoxDown, xrayDown, "server_port", "port")
                xrayDown.put("network", "xhttp")

                if (singBoxDown.has("tls")) {
                    val tls = singBoxDown.getJSONObject("tls")
                    if (tls.has("reality") && tls.getJSONObject("reality").optBoolean("enabled", false)) {
                        xrayDown.put("security", "reality")
                        val reality = tls.getJSONObject("reality")
                        val realitySettings = JSONObject()
                        convertField(tls, realitySettings, "server_name", "serverName")
                        convertField(reality, realitySettings, "public_key", "publicKey")
                        convertField(reality, realitySettings, "short_id", "shortId")
                        if (tls.has("utls")) {
                            val utls = tls.getJSONObject("utls")
                            convertField(utls, realitySettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("realitySettings", realitySettings)
                    } else {
                        xrayDown.put("security", "tls")
                        val tlsSettings = JSONObject()
                        convertField(tls, tlsSettings, "server_name", "serverName")
                        convertField(tls, tlsSettings, "alpn", "alpn")
                        convertField(tls, tlsSettings, "insecure", "allowInsecure")
                        if (tls.has("utls")) {
                            val utls = tls.getJSONObject("utls")
                            convertField(utls, tlsSettings, "fingerprint", "fingerprint")
                        }
                        xrayDown.put("tlsSettings", tlsSettings)
                    }
                }

                val xhttpSettings = JSONObject()
                convertField(singBoxDown, xhttpSettings, "mode", "mode")
                convertField(singBoxDown, xhttpSettings, "host", "host")
                convertField(singBoxDown, xhttpSettings, "path", "path")
                convertFieldsReverse(singBoxDown, xhttpSettings, FIELD_MAPPINGS)
                if (singBoxDown.has("xmux")) {
                    convertXmuxReverse(singBoxDown, xhttpSettings)
                }
                xrayDown.put("xhttpSettings", xhttpSettings)

                if (xrayDown.length() > 0) xray.put("downloadSettings", xrayDown)
            }

            xray.toString(2).replace("\\/", "/")
        } catch (_: Exception) {
            singBoxExtra
        }
    }

    fun extractExtraFromTransport(transport: JsonObject): String? {
        val extraMap = transport.filterKeys { it !in KNOWN_XHTTP_BASE_FIELDS }
        if (extraMap.isEmpty()) return null
        return runCatching {
            val jsonElement = JsonObject(extraMap)
            val singBoxStr = SingBoxJson.encodeToString(JsonElement.serializer(), jsonElement)
            val xrayStr = singBoxToXray(singBoxStr)
            xrayStr.ifBlank { singBoxStr }
        }.getOrNull()
    }

    fun mergeExtraIntoBuilder(builder: JsonObjectBuilder, extraJsonString: String) {
        if (extraJsonString.isBlank()) return
        val sanitized = sanitizeMalformedExtraJson(extraJsonString)
        val normalized = xrayToSingBox(sanitized)
        runCatching {
            val parsed = SingBoxJson.parseToJsonElement(normalized) as? JsonObject ?: return
            parsed.forEach { (key, value) ->
                builder.put(key, value)
            }
        }
    }

    private fun sanitizeMalformedExtraJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{+") || trimmed.contains(":+") || trimmed.contains(",+")) {
            return trimmed.replace("+", " ")
        }
        return trimmed
    }

    private fun isSingBoxFormat(json: JSONObject): Boolean {
        return json.has("x_padding_bytes") || json.has("sc_max_each_post_bytes") ||
            json.has("sc_min_posts_interval_ms") || json.has("sc_stream_up_server_secs") ||
            json.has("session_id_table") || json.has("session_id_length") ||
            json.has("download")
    }

    private fun isXrayFormat(json: JSONObject): Boolean {
        return json.has("xPaddingBytes") || json.has("scMaxEachPostBytes") ||
            json.has("scMinPostsIntervalMs") || json.has("scStreamUpServerSecs") ||
            json.has("sessionIDTable") || json.has("sessionIDLength") ||
            json.has("downloadSettings")
    }

    private fun convertField(from: JSONObject, to: JSONObject, fromKey: String, toKey: String) {
        if (from.has(fromKey)) {
            to.put(toKey, from.get(fromKey))
        }
    }

    private fun convertFields(from: JSONObject, to: JSONObject, mappings: Array<Pair<String, String>>) {
        for ((fromKey, toKey) in mappings) {
            convertField(from, to, fromKey, toKey)
        }
    }

    private fun convertFieldsReverse(from: JSONObject, to: JSONObject, mappings: Array<Pair<String, String>>) {
        for ((fromKey, toKey) in mappings) {
            convertField(from, to, toKey, fromKey)
        }
    }

    private fun convertXmux(from: JSONObject, to: JSONObject) {
        val xrayXmux = from.optJSONObject("xmux") ?: return
        val singBoxXmux = JSONObject()
        convertFields(xrayXmux, singBoxXmux, XMUX_MAPPINGS)
        if (singBoxXmux.length() > 0) to.put("xmux", singBoxXmux)
    }

    private fun convertXmuxReverse(from: JSONObject, to: JSONObject) {
        val singBoxXmux = from.optJSONObject("xmux") ?: return
        val xrayXmux = JSONObject()
        convertFieldsReverse(singBoxXmux, xrayXmux, XMUX_MAPPINGS)
        if (xrayXmux.length() > 0) to.put("xmux", xrayXmux)
    }
}
