// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import engine.network.DnsEventUpdate
import engine.network.DnsEventsUnavailable
import engine.network.decodeDnsEvent
import io.nekohasekai.libbox.CommandClient
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch

private sealed interface DnsCallbackUpdate {
    data object Ready : DnsCallbackUpdate
    data class Received(val json: String) : DnsCallbackUpdate
}

internal fun CommandClient.streamDnsEvents(token: String): Flow<DnsEventUpdate> =
    dnsEventCallbacks(token).transform { update ->
        when (update) {
            DnsCallbackUpdate.Ready -> emit(DnsEventUpdate.Ready)
            is DnsCallbackUpdate.Received -> {
                for (line in update.json.lineSequence()) {
                    if (line.isEmpty()) continue
                    decodeDnsEvent(line)?.let { emit(DnsEventUpdate.Received(it)) }
                }
            }
        }
    }

private fun CommandClient.dnsEventCallbacks(token: String): Flow<DnsCallbackUpdate> = callbackFlow {
    val handlerClass = runCatching {
        Class.forName("io.nekohasekai.libbox.DnsEventsHandler")
    }.getOrNull()
    val method = handlerClass?.let { cls ->
        runCatching {
            javaClass.getMethod("subscribeDnsEventBatches", String::class.java, cls)
        }.getOrNull()
    }
    if (handlerClass == null || method == null) {
        close(DnsEventsUnavailable())
        return@callbackFlow
    }

    val handshake = launch {
        delay(DnsHandshakeTimeoutMillis)
        close(IOException("DNS events handshake timed out"))
    }
    val handler = java.lang.reflect.Proxy.newProxyInstance(
        handlerClass.classLoader,
        arrayOf(handlerClass),
    ) { _, called, args ->
        when (called.name) {
            "onReady" -> {
                handshake.cancel()
                trySend(DnsCallbackUpdate.Ready)
            }
            "onEvent" -> {
                val payload = args?.firstOrNull() as? String ?: ""
                trySendBlocking(DnsCallbackUpdate.Received(payload))
            }
            "onError" -> {
                val code = args?.firstOrNull() as? String ?: "UNKNOWN"
                close(
                    if (code == "UNSUPPORTED") DnsEventsUnavailable()
                    else IOException("DNS events command ended ($code)"),
                )
            }
        }
        null
    }
    val subscription = try {
        method.invoke(this@dnsEventCallbacks, token, handler)
    } catch (e: java.lang.reflect.InvocationTargetException) {
        handshake.cancel()
        throw e.targetException ?: e
    } ?: run {
        handshake.cancel()
        throw IOException("Missing DNS events session")
    }
    awaitClose {
        handshake.cancel()
        runCatching {
            if (subscription is AutoCloseable) {
                subscription.close()
            } else {
                subscription.javaClass.getMethod("close").invoke(subscription)
            }
        }
    }
}.buffer(DnsBatchBufferCapacity)

private const val DnsBatchBufferCapacity = 8
private const val DnsHandshakeTimeoutMillis = 3_000L
