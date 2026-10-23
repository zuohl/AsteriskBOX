// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import app.DefaultSingBoxUrlTestUrl
import app.OutboundState
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.encodeSingBoxJson
import engine.singbox.config.inheritedGroupDetour
import engine.singbox.config.normalizeWireGuardEndpointJson
import engine.singbox.config.parseSingBoxJson
import engine.singbox.config.shouldRetainRawGroupedOutbound
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * 独立测速客户端（运行在调用方进程）。
 *
 * 负责生成测速配置并委托 `:tester` 进程中的 [StandaloneUrlTestService] 执行，
 * 避免与 `:bg` 进程的 VPN 内核争抢 `command.sock`。
 */
internal object SingBoxStandaloneUrlTester {
    private const val LogTag = "SingBoxStandaloneTester"
    private const val TestGroupName = "__standalone_test_group__"
    // 预留给 :tester 进程冷启动与 libbox 初始化的额外时间
    private const val RemoteStartupGraceMillis = 5_000L
    private val runMutex = Mutex()

    suspend fun testOutbounds(
        context: Context,
        outbounds: List<OutboundState>,
        groupDetours: Map<Int, String> = emptyMap(),
        testUrl: String = DefaultSingBoxUrlTestUrl,
        timeoutMillis: Long = 6_000L,
        onProgress: ((outboundId: Int, delayMillis: Long) -> Unit)? = null,
    ): Map<Int, Long> = withContext(Dispatchers.IO) {
        val distinctTargets = outbounds.distinctBy { it.id }
        if (distinctTargets.isEmpty()) return@withContext emptyMap()

        runMutex.withLock {
            val appContext = context.applicationContext
            val targetByTag = distinctTargets.associateBy { it.tag }
            val delaysByTag = runCatching {
                val configFile = File(File(appContext.cacheDir, TesterLibboxBaseDirName), "urltest.json").apply {
                    parentFile?.mkdirs()
                    writeText(buildTestConfig(distinctTargets, groupDetours, testUrl))
                }
                runInTesterProcess(appContext, configFile, ArrayList(targetByTag.keys), timeoutMillis) { tag, delay ->
                    targetByTag[tag]?.let { target -> onProgress?.invoke(target.id, delay) }
                }
            }.getOrElse { error ->
                AndroidAppLogger.warn(LogTag, "Standalone URL test encountered an issue", error)
                emptyMap()
            }

            distinctTargets.associate { target ->
                target.id to (delaysByTag[target.tag] ?: -1L)
            }
        }
    }

    private suspend fun runInTesterProcess(
        context: Context,
        configFile: File,
        tags: ArrayList<String>,
        timeoutMillis: Long,
        onProgress: (tag: String, delayMillis: Long) -> Unit,
    ): Map<String, Long> {
        val partial = ConcurrentHashMap<String, Long>()
        val done = CompletableDeferred<Map<String, Long>>()

        val replyMessenger = Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    val data = msg.data
                    when (msg.what) {
                        StandaloneUrlTestProtocol.MsgProgress -> {
                            val tag = data.getString(StandaloneUrlTestProtocol.KeyTag) ?: return
                            val delay = data.getLong(StandaloneUrlTestProtocol.KeyDelay)
                            partial[tag] = delay
                            onProgress(tag, delay)
                        }

                        StandaloneUrlTestProtocol.MsgDone -> {
                            val resultTags = data.getStringArrayList(StandaloneUrlTestProtocol.KeyTags).orEmpty()
                            val delays = data.getLongArray(StandaloneUrlTestProtocol.KeyDelays) ?: LongArray(0)
                            val final = HashMap<String, Long>(partial)
                            resultTags.forEachIndexed { index, tag ->
                                delays.getOrNull(index)?.let { final[tag] = it }
                            }
                            done.complete(final)
                        }
                    }
                }
            },
        )

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    done.complete(HashMap(partial))
                    return
                }
                val request = Message.obtain(null, StandaloneUrlTestProtocol.MsgRun).apply {
                    data = Bundle().apply {
                        putString(StandaloneUrlTestProtocol.KeyConfigPath, configFile.absolutePath)
                        putString(StandaloneUrlTestProtocol.KeyGroupTag, TestGroupName)
                        putStringArrayList(StandaloneUrlTestProtocol.KeyTags, tags)
                        putLong(StandaloneUrlTestProtocol.KeyTimeoutMillis, timeoutMillis)
                    }
                    replyTo = replyMessenger
                }
                runCatching { Messenger(service).send(request) }.onFailure { error ->
                    AndroidAppLogger.warn(LogTag, "Failed to send URL test request to tester process", error)
                    done.complete(HashMap(partial))
                }
            }

            // :tester 进程崩溃或被系统回收
            override fun onServiceDisconnected(name: ComponentName?) {
                done.complete(HashMap(partial))
            }

            override fun onBindingDied(name: ComponentName?) {
                done.complete(HashMap(partial))
            }

            override fun onNullBinding(name: ComponentName?) {
                done.complete(HashMap(partial))
            }
        }

        val bound = withContext(Dispatchers.Main) {
            context.bindService(
                Intent(context, StandaloneUrlTestService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }
        if (!bound) {
            AndroidAppLogger.warn(LogTag, "Unable to bind StandaloneUrlTestService")
            withContext(NonCancellable + Dispatchers.Main) { runCatching { context.unbindService(connection) } }
            return emptyMap()
        }
        try {
            return withTimeoutOrNull((timeoutMillis + RemoteStartupGraceMillis).milliseconds) {
                done.await()
            } ?: HashMap(partial)
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { context.unbindService(connection) }
            }
        }
    }

    private fun buildTestConfig(
        targets: List<OutboundState>,
        groupDetours: Map<Int, String>,
        testUrl: String,
    ): String {
        val wireguardTargets = targets.filter { it.type == "wireguard" }
        val normalTargets = targets.filter { it.type != "wireguard" }
        val compiledEndpoints = wireguardTargets.mapNotNull { outbound ->
            normalizeWireGuardEndpointJson(outbound.json, outbound.tag)
        }
        val compiledOutbounds = normalTargets.mapNotNull { outbound ->
            val parsedJson = runCatching { parseSingBoxJson(outbound.json) }.getOrNull()
            if (parsedJson != null && outbound.shouldRetainRawGroupedOutbound(parsedJson)) {
                JsonObject(
                    buildMap {
                        putAll(parsedJson)
                        put("type", JsonPrimitive(outbound.type))
                        put("tag", JsonPrimitive(outbound.tag))
                        outbound.inheritedGroupDetour(groupDetours[outbound.groupId].orEmpty(), parsedJson)
                            ?.let { detour -> put("detour", JsonPrimitive(detour)) }
                    },
                )
            } else {
                buildJsonObject {
                    put("type", outbound.type)
                    put("tag", outbound.tag)
                }
            }
        }
        val targetTags = targets.map { it.tag }

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    // sing-box 1.14+ 已移除旧版 address 写法，必须使用带 type 的新格式
                    addJsonObject {
                        put("type", "udp")
                        put("tag", "dns-direct")
                        put("server", "223.5.5.5")
                    }
                }
                put("strategy", "prefer_ipv4")
            }
            putJsonArray("inbounds") {}
            if (compiledEndpoints.isNotEmpty()) {
                putJsonArray("endpoints") {
                    compiledEndpoints.forEach { add(it) }
                }
            }
            putJsonArray("outbounds") {
                compiledOutbounds.forEach { add(it) }
                if (APP_DIRECT_OUTBOUND !in targetTags) {
                    addJsonObject {
                        put("type", "direct")
                        put("tag", APP_DIRECT_OUTBOUND)
                    }
                }
                if ("direct" !in targetTags && APP_DIRECT_OUTBOUND != "direct") {
                    addJsonObject {
                        put("type", "direct")
                        put("tag", "direct")
                    }
                }
                addJsonObject {
                    put("type", "urltest")
                    put("tag", TestGroupName)
                    putJsonArray("outbounds") {
                        targetTags.forEach(::add)
                    }
                    put("url", testUrl)
                    put("interval", "10m")
                    put("tolerance", 50)
                }
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    addJsonObject {
                        put("protocol", "dns")
                        put("outbound", APP_DIRECT_OUTBOUND)
                    }
                }
                put("final", APP_DIRECT_OUTBOUND)
                put("default_domain_resolver", "dns-direct")
            }
        }
        return encodeSingBoxJson(root)
    }
}
