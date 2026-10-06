// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.content.Context
import android.os.SystemClock
import features.logs.AndroidAppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class CachedProxyDelay(
    val delay: Int,
    val updatedAtEpochSeconds: Long,
)

internal class SingBoxProxyDelayCache(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
    private val memoryCache = ConcurrentHashMap<String, CachedProxyDelay>()
    private val lock = Any()
    private var dirty = false
    private var lastPersistedAtElapsedMillis = 0L

    init {
        loadFromPreferences()
    }

    private fun loadFromPreferences() {
        val jsonString = preferences.getString(KeyDelays, null) ?: return
        runCatching {
            val map = DelayCacheJson.decodeFromString<Map<String, CachedProxyDelay>>(jsonString)
            val nowSeconds = System.currentTimeMillis() / 1000L
            map.forEach { (nodeName, entry) ->
                if (nowSeconds - entry.updatedAtEpochSeconds < TtlSeconds && entry.delay > 0) {
                    memoryCache[nodeName] = entry
                }
            }
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to decode cached proxy delays", error)
        }
    }

    fun record(proxies: SingBoxProxiesState) {
        var updated = false
        val nowSeconds = System.currentTimeMillis() / 1000L
        for (node in proxies.nodes) {
            val delay = node.delay ?: continue
            val updatedAt = node.delayUpdatedAtEpochSeconds ?: continue
            if (delay > 0 && updatedAt > 0L) {
                val existing = memoryCache[node.name]
                if (existing == null || updatedAt >= existing.updatedAtEpochSeconds) {
                    memoryCache[node.name] = CachedProxyDelay(delay, updatedAt)
                    updated = true
                }
            }
        }
        if (updated) {
            synchronized(lock) {
                dirty = true
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersistedAtElapsedMillis >= PersistIntervalMillis) {
                    persistLocked(nowElapsedMillis = now)
                }
            }
        }
    }

    fun enrich(proxies: SingBoxProxiesState): SingBoxProxiesState {
        if (memoryCache.isEmpty() || proxies.nodes.isEmpty()) return proxies

        val nowSeconds = System.currentTimeMillis() / 1000L
        var enrichedAny = false
        val newNodes = proxies.nodes.map { node ->
            if (node.delay != null && (node.delayUpdatedAtEpochSeconds ?: 0L) > 0L) {
                node
            } else {
                val cached = memoryCache[node.name]
                if (cached != null && nowSeconds - cached.updatedAtEpochSeconds < TtlSeconds) {
                    enrichedAny = true
                    node.copy(
                        delay = cached.delay,
                        delayUpdatedAtEpochSeconds = cached.updatedAtEpochSeconds,
                    )
                } else {
                    node
                }
            }
        }

        if (!enrichedAny) return proxies

        val newNodeByName = newNodes.associateBy { it.name }
        return proxies.copy(
            nodes = newNodes,
            nodeByName = newNodeByName,
        )
    }

    fun flush() {
        synchronized(lock) {
            if (dirty) {
                persistLocked(nowElapsedMillis = SystemClock.elapsedRealtime())
            }
        }
    }

    private fun persistLocked(nowElapsedMillis: Long) {
        val nowSeconds = System.currentTimeMillis() / 1000L
        // Evict expired entries
        memoryCache.entries.removeIf { (_, entry) ->
            nowSeconds - entry.updatedAtEpochSeconds >= TtlSeconds
        }
        val encoded = runCatching {
            DelayCacheJson.encodeToString(memoryCache.toMap())
        }.onFailure { error ->
            AndroidAppLogger.warn(LogTag, "Failed to encode proxy delay cache", error)
        }.getOrNull() ?: return

        preferences.edit().putString(KeyDelays, encoded).apply()
        dirty = false
        lastPersistedAtElapsedMillis = nowElapsedMillis
    }

    private companion object {
        const val PreferencesName = "asteriskbox_proxy_delays"
        const val KeyDelays = "cached_delays"
        const val TtlSeconds = 7 * 24 * 3600L // 7 days TTL
        const val PersistIntervalMillis = 5_000L
        const val LogTag = "ProxyDelayCache"

        val DelayCacheJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
