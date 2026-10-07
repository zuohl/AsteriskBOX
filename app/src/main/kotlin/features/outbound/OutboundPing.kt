// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import android.content.Context
import app.AppState
import app.OutboundState
import engine.singbox.config.APP_ALL_NODES_TEST_SELECTOR
import engine.singbox.config.SingBoxJson
import engine.singbox.runtime.SingBoxRuntimeRepository
import engine.singbox.runtime.SingBoxStandaloneUrlTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

internal interface OutboundPinger {
    suspend fun ping(outbound: OutboundState): Long

    suspend fun pingBatch(
        outbounds: List<OutboundState>,
        onProgress: (outboundId: Int, latencyMillis: Long) -> Unit,
    ): Map<Int, Long> {
        return outbounds.associate { outbound ->
            val latency = ping(outbound)
            onProgress(outbound.id, latency)
            outbound.id to latency
        }
    }
}

internal class RealDelayPinger(
    private val context: Context,
    private val singBoxRuntime: SingBoxRuntimeRepository,
    private val getAppState: () -> AppState,
) : OutboundPinger {
    override suspend fun ping(outbound: OutboundState): Long {
        val appState = getAppState()
        return if (appState.proxyRunning) {
            val result = singBoxRuntime.testProxyDelay(appState, outbound.tag)
            val delay = result.getOrNull()?.delays?.get(outbound.tag)
            if (delay != null && delay > 0) {
                delay.toLong()
            } else {
                val detours = appState.outboundGroups.associate { it.id to it.detour }
                val results = SingBoxStandaloneUrlTester.testOutbounds(
                    context = context,
                    outbounds = listOf(outbound),
                    groupDetours = detours,
                )
                results[outbound.id] ?: FailedPingMillis
            }
        } else {
            val detours = appState.outboundGroups.associate { it.id to it.detour }
            val results = SingBoxStandaloneUrlTester.testOutbounds(
                context = context,
                outbounds = listOf(outbound),
                groupDetours = detours,
            )
            results[outbound.id] ?: FailedPingMillis
        }
    }

    override suspend fun pingBatch(
        outbounds: List<OutboundState>,
        onProgress: (outboundId: Int, latencyMillis: Long) -> Unit,
    ): Map<Int, Long> {
        val appState = getAppState()
        return if (appState.proxyRunning) {
            // 代理运行中：仅测试传入的目标节点，不波及未选择的分组
            val targetGroupId = outbounds.map { it.groupId }.distinct().singleOrNull()
            val targetGroup = if (targetGroupId != null) {
                appState.outboundGroups.firstOrNull { it.id == targetGroupId }
            } else null
            val targetGroupTag = targetGroup?.let { app.managedOutboundGroupSelectorTag(it.id, it.name) }
            val groupSelector = targetGroupTag?.takeIf { tag ->
                singBoxRuntime.state.value.proxies.groups.any { it.name == tag }
            } ?: APP_ALL_NODES_TEST_SELECTOR.takeIf { selector ->
                singBoxRuntime.state.value.proxies.groups.any { it.name == selector }
            }

            if (groupSelector != null) {
                // 如果当前测速的节点全都在同一个管理分组，且内核中有该分组的选择器，则仅测试该分组
                val targetByTag = outbounds.associateBy { it.tag }
                val reportedIds = mutableSetOf<Int>()
                val resultsMap = mutableMapOf<Int, Long>()

                val groupResult = singBoxRuntime.testGroupDelay(appState, groupSelector) { nodeName, delay ->
                    targetByTag[nodeName]?.let { ob ->
                        val delayLong = delay.takeIf { it > 0 }?.toLong() ?: FailedPingMillis
                        reportedIds += ob.id
                        resultsMap[ob.id] = delayLong
                        onProgress(ob.id, delayLong)
                    }
                }
                if (groupResult.isSuccess) {
                    val groupDelays = groupResult.getOrNull()?.delays.orEmpty()
                    outbounds.forEach { ob ->
                        if (ob.id !in reportedIds) {
                            val delay = groupDelays[ob.tag]?.takeIf { it > 0 }?.toLong() ?: FailedPingMillis
                            resultsMap[ob.id] = delay
                            onProgress(ob.id, delay)
                        }
                    }
                    resultsMap
                } else {
                    // 若整组测试失败，fallback 到独立实例进行真实 URLTest
                    val detours = appState.outboundGroups.associate { it.id to it.detour }
                    SingBoxStandaloneUrlTester.testOutbounds(
                        context = context,
                        outbounds = outbounds,
                        groupDetours = detours,
                        onProgress = onProgress,
                    )
                }
            } else {
                // 单节点极简模式或无对应分组选择器：直接通过独立实例进行真实 URLTest
                val detours = appState.outboundGroups.associate { it.id to it.detour }
                SingBoxStandaloneUrlTester.testOutbounds(
                    context = context,
                    outbounds = outbounds,
                    groupDetours = detours,
                    onProgress = onProgress,
                )
            }
        } else {
            // 代理未运行：通过独立无 TUN 实例进行真实 URLTest
            val detours = appState.outboundGroups.associate { it.id to it.detour }
            SingBoxStandaloneUrlTester.testOutbounds(
                context = context,
                outbounds = outbounds,
                groupDetours = detours,
                onProgress = onProgress,
            )
        }
    }
}

internal class AndroidOutboundPinger(
    private val delegate: OutboundPinger? = null,
) : OutboundPinger {
    override suspend fun ping(outbound: OutboundState): Long =
        delegate?.ping(outbound) ?: FailedPingMillis

    override suspend fun pingBatch(
        outbounds: List<OutboundState>,
        onProgress: (outboundId: Int, latencyMillis: Long) -> Unit,
    ): Map<Int, Long> =
        delegate?.pingBatch(outbounds, onProgress) ?: outbounds.associate { it.id to FailedPingMillis }
}

internal const val FailedPingMillis = -1L
