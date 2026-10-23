// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package engine.singbox.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import engine.vpn.toStringIterator
import features.logs.AndroidAppLogger
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * 独立测速服务，运行在独立的 `:tester` 进程中。
 *
 * 独立测速需要启动一个完整的 libbox CommandServer。CommandServer 会在 Libbox basePath 下
 * 重建并在关闭时删除 `command.sock`，若与 `:bg` 进程中的 VPN 内核共用 basePath，就会抢走
 * VPN 的控制通道（面板/分组/流量通知全部失联），并覆盖其配置快照。因此测速必须放在拥有独立
 * basePath（见 [TesterLibboxBaseDirName]）的进程中执行。
 */
class StandaloneUrlTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()

    private val messenger = Messenger(
        object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what != StandaloneUrlTestProtocol.MsgRun) return
                val replyTo = msg.replyTo ?: return
                // Message 会在 handleMessage 返回后被回收，需先复制数据
                val data = Bundle(msg.data)
                scope.launch { handleRun(data, replyTo) }
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handleRun(data: Bundle, replyTo: Messenger) = runMutex.withLock {
        val configPath = data.getString(StandaloneUrlTestProtocol.KeyConfigPath).orEmpty()
        val tags = data.getStringArrayList(StandaloneUrlTestProtocol.KeyTags).orEmpty()
        val groupTag = data.getString(StandaloneUrlTestProtocol.KeyGroupTag).orEmpty()
        val timeoutMillis = data.getLong(StandaloneUrlTestProtocol.KeyTimeoutMillis, 6_000L)

        val results = runCatching {
            val configContent = File(configPath).readText()
            runStandaloneUrlTest(applicationContext, configContent, groupTag, tags.toSet(), timeoutMillis) { tag, delay ->
                replyTo.sendSafely(
                    Message.obtain(null, StandaloneUrlTestProtocol.MsgProgress).apply {
                        this.data = Bundle().apply {
                            putString(StandaloneUrlTestProtocol.KeyTag, tag)
                            putLong(StandaloneUrlTestProtocol.KeyDelay, delay)
                        }
                    },
                )
            }
        }.getOrElse { error ->
            AndroidAppLogger.warn(LogTag, "Standalone URL test failed", error)
            emptyMap()
        }

        val resultTags = ArrayList(results.keys)
        replyTo.sendSafely(
            Message.obtain(null, StandaloneUrlTestProtocol.MsgDone).apply {
                this.data = Bundle().apply {
                    putStringArrayList(StandaloneUrlTestProtocol.KeyTags, resultTags)
                    putLongArray(StandaloneUrlTestProtocol.KeyDelays, resultTags.map { results.getValue(it) }.toLongArray())
                }
            },
        )
    }

    private fun Messenger.sendSafely(message: Message) {
        runCatching { send(message) }
    }

    private companion object {
        const val LogTag = "StandaloneUrlTestService"
    }
}

internal object StandaloneUrlTestProtocol {
    const val MsgRun = 1
    const val MsgProgress = 2
    const val MsgDone = 3

    const val KeyConfigPath = "config_path"
    const val KeyGroupTag = "group_tag"
    const val KeyTags = "tags"
    const val KeyTimeoutMillis = "timeout_millis"
    const val KeyTag = "tag"
    const val KeyDelay = "delay"
    const val KeyDelays = "delays"
}

/** `:tester` 进程专用的 Libbox basePath 目录名，必须与主进程/`:bg` 进程的 `sing-box` 区分开。 */
internal const val TesterLibboxBaseDirName = "sing-box-tester"

/** 仅允许在 `:tester` 进程中调用。返回 tag -> 延迟(ms)，仅包含测速成功的节点。 */
private suspend fun runStandaloneUrlTest(
    context: Context,
    configContent: String,
    groupTag: String,
    targetTags: Set<String>,
    timeoutMillis: Long,
    onProgress: (tag: String, delayMillis: Long) -> Unit,
): Map<String, Long> {
    if (targetTags.isEmpty()) return emptyMap()
    val results = ConcurrentHashMap<String, Long>()
    val server = Libbox.newCommandServer(StandaloneServerHandler(), StandalonePlatformInterface(context))
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
                        if (item.tag in targetTags && delay > 0) {
                            val delayLong = delay.toLong()
                            val previous = results.put(item.tag, delayLong)
                            if (previous == null || previous != delayLong) {
                                onProgress(item.tag, delayLong)
                            }
                        }
                    }
                }
                if (results.size >= targetTags.size) {
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
            urlTest(groupTag)
        }

        withTimeoutOrNull(timeoutMillis.milliseconds) {
            allDone.await()
        }
    } finally {
        runCatching { client?.disconnect() }
        runCatching { server.closeService() }
        runCatching { server.close() }
    }
    return HashMap(results)
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
    override fun createAutoRedirectListener(inet6: Boolean): Int = error("auto redirect listener not supported")
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
