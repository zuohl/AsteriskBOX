// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import app.DefaultSingBoxUrlTestUrl
import app.OutboundState
import engine.singbox.config.APP_DIRECT_OUTBOUND
import engine.singbox.config.encodeSingBoxJson
import engine.singbox.config.inheritedGroupDetour
import engine.singbox.config.normalizeWireGuardEndpointJson
import engine.singbox.config.parseSingBoxJson
import engine.singbox.config.shouldRetainRawGroupedOutbound
import engine.vpn.toStringIterator
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

internal object SingBoxStandaloneUrlTester {
    private const val LogTag = "SingBoxStandaloneTester"
    private const val TestGroupName = "__standalone_test_group__"
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
            val results = ConcurrentHashMap<Int, Long>()
            val targetByTag = distinctTargets.associateBy { it.tag }
            val configContent = buildTestConfig(distinctTargets, groupDetours, testUrl)

            val platformInterface = StandalonePlatformInterface(context.applicationContext)
            val serverHandler = StandaloneServerHandler()
            val server = runCatching {
                Libbox.newCommandServer(serverHandler, platformInterface)
            }.getOrElse { error ->
                AndroidAppLogger.error(LogTag, "Failed to create standalone CommandServer", error)
                return@withLock distinctTargets.associate { it.id to -1L }
            }

            var client: CommandClient? = null
            try {
                server.start()
                server.startOrReloadService(configContent, OverrideOptions())

                val allDone = CompletableDeferred<Unit>()
                val clientHandler = object : CommandClientHandler {
                    override fun connected() = Unit
                    override fun disconnected(message: String?) = Unit
                    override fun clearLogs() = Unit
                    override fun writeLogs(messageList: LogIterator?) = Unit
                    override fun setDefaultLogLevel(level: Int) = Unit
                    override fun writeStatus(message: StatusMessage?) = Unit
                    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
                    override fun updateClashMode(newMode: String?) = Unit
                    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
                    override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit

                    override fun writeGroups(message: OutboundGroupIterator?) {
                        if (message == null) return
                        while (message.hasNext()) {
                            val group = message.next()
                            val items = group.items
                            while (items.hasNext()) {
                                val item = items.next()
                                val delay = item.urlTestDelay
                                val matched = targetByTag[item.tag]
                                if (matched != null && delay > 0) {
                                    val delayLong = delay.toLong()
                                    val previous = results.put(matched.id, delayLong)
                                    if (previous == null || previous != delayLong) {
                                        onProgress?.invoke(matched.id, delayLong)
                                    }
                                }
                            }
                        }
                        if (results.size >= distinctTargets.size) {
                            allDone.complete(Unit)
                        }
                    }
                }

                val options = CommandClientOptions().apply {
                    addCommand(Libbox.CommandGroup)
                    statusInterval = 200_000_000L // 200ms
                }
                client = Libbox.newCommandClient(clientHandler, options).apply {
                    connect()
                    urlTest(TestGroupName)
                }

                withTimeoutOrNull(timeoutMillis.milliseconds) {
                    allDone.await()
                }
            } catch (error: Throwable) {
                AndroidAppLogger.warn(LogTag, "Standalone URL test encountered an issue", error)
            } finally {
                runCatching { client?.disconnect() }
                runCatching { server.closeService() }
                runCatching { server.close() }
            }

            distinctTargets.associate { target ->
                target.id to (results[target.id] ?: -1L)
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
                    addJsonObject {
                        put("tag", "dns-direct")
                        put("address", "223.5.5.5")
                        put("detour", APP_DIRECT_OUTBOUND)
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
            }
        }
        return encodeSingBoxJson(root)
    }
}

private class StandaloneServerHandler : CommandServerHandler {
    override fun serviceReload() = Unit
    override fun serviceStop() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) = Unit
    override fun writeDebugMessage(message: String?) = Unit
    override fun connectSSHAgent(): Int = -1
    override fun triggerNativeCrash() = Unit
}

private class StandalonePlatformInterface(
    private val context: Context,
) : PlatformInterface {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = Unit
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("shell not supported")

    override fun readSystemSSHHostKey(): String = error("ssh not supported")
    override fun lookupSFTPServer(): String = error("sftp not supported")
    override fun lookupUser(username: String?): PlatformUser = error("user lookup not supported")
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession =
        error("auto redirect not supported")

    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("bridge not supported")
    override fun registerMyInterface(name: String?) = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun tailscaleHostname(): String = ""
    override fun sendNotification(notification: Notification?) = Unit
    override fun cancelNotification(identifier: String?, typeID: Int) = Unit
    override fun autoDetectInterfaceControl(fd: Int) = Unit
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

    override fun openTun(options: TunOptions?): Int =
        error("TUN interface is not supported in standalone test")

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner = error("connection owner not supported")

    @Suppress("DEPRECATION")
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val physicalNetwork = connectivityManager?.allNetworks?.firstOrNull { net ->
            val caps = connectivityManager.getNetworkCapabilities(net)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } ?: connectivityManager?.activeNetwork

        val interfaceName = physicalNetwork
            ?.let { connectivityManager.getLinkProperties(it) }
            ?.interfaceName
            .orEmpty()
        val index = runCatching { NetworkInterface.getByName(interfaceName)?.index ?: -1 }.getOrDefault(-1)
        listener?.updateDefaultInterface(interfaceName, index, false, false)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) = Unit

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { networkInterface ->
                LibboxNetworkInterface().apply {
                    index = networkInterface.index
                    name = networkInterface.name
                    mtu = runCatching { networkInterface.mtu }.getOrDefault(0)
                    addresses = emptyList<String>().toStringIterator()
                    dnsServer = emptyList<String>().toStringIterator()
                    dnsSearchDomain = emptyList<String>().toStringIterator()
                    flags = 0
                    type = Libbox.InterfaceTypeOther
                    metered = false
                }
            }
        }.getOrDefault(emptyList())

        val iterator = interfaces.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): LibboxNetworkInterface = iterator.next()
        }
    }
}
