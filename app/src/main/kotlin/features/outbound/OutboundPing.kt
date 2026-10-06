// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import app.OutboundState
import engine.singbox.config.SingBoxJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

internal fun OutboundState.pingHostOrNull(): String? {
    return pingTargetOrNull()?.first
}

internal fun OutboundState.pingTargetOrNull(): Pair<String, Int>? {
    val outbound = runCatching {
        SingBoxJson.parseToJsonElement(json) as? JsonObject
    }.getOrNull() ?: return null
    val host = (outbound["server"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.removeSurrounding("[", "]")
        ?.takeIf(String::isNotEmpty) ?: return null
    val port = (outbound["server_port"] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.toIntOrNull()
        ?: (outbound["port"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
        ?: 443
    return host to port
}

internal suspend fun pingOrFailure(ping: suspend () -> Long): Long {
    return try {
        ping()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        FailedPingMillis
    }
}

internal fun interface OutboundPinger {
    suspend fun ping(outbound: OutboundState): Long
}

internal class AndroidOutboundPinger : OutboundPinger {
    override suspend fun ping(outbound: OutboundState): Long {
        val (host, port) = outbound.pingTargetOrNull() ?: return FailedPingMillis
        var bestMillis = FailedPingMillis
        repeat(PingAttempts) {
            currentCoroutineContext().ensureActive()
            val elapsedMillis = tcpPingOnce(host, port)
            if (elapsedMillis >= 0L && (bestMillis !in 0L..elapsedMillis)) {
                bestMillis = elapsedMillis
            }
        }
        return bestMillis
    }

    private suspend fun tcpPingOnce(host: String, port: Int): Long {
        return withTimeoutOrNull(PingTimeoutMillis.milliseconds) {
            withContext(Dispatchers.IO) {
                var socket: java.net.Socket? = null
                try {
                    val start = System.nanoTime()
                    socket = java.net.Socket()
                    socket.soTimeout = PingTimeoutMillis.toInt()
                    socket.connect(java.net.InetSocketAddress(host, port), PingTimeoutMillis.toInt())
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                } catch (_: Throwable) {
                    FailedPingMillis
                } finally {
                    runCatching { socket?.close() }
                }
            }
        } ?: FailedPingMillis
    }
}

internal const val FailedPingMillis = -1L
private const val PingAttempts = 2
private const val PingTimeoutMillis = 3_000L
