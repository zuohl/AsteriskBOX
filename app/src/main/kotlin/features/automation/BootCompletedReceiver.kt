// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.AsteriskApplication
import app.modes.RunModeVpnService
import data.AndroidAppStateStore
import engine.proxy.ProxyServiceResult
import engine.proxy.ProxyServiceUseCase
import features.logs.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        operationScope.launch {
            try {
                operationMutex.withLock {
                    BootCompletedHandler(appContext).handle()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                AndroidAppLogger.error(LogTag, "Boot auto-start failed unexpectedly", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val operationMutex = Mutex()
    }
}

private class BootCompletedHandler(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val stateStore by lazy { AndroidAppStateStore.get(appContext) }
    private val proxyEngine by lazy { (appContext as AsteriskApplication).proxyEngine }
    private val proxyServiceUseCase by lazy {
        ProxyServiceUseCase(
            proxyEngine = proxyEngine,
            requestVpnPermission = {
                AndroidAppLogger.warn(LogTag, "Boot auto-start cannot request VPN permission in background")
                false
            },
        )
    }

    suspend fun handle() {
        val state = stateStore.state.value
        if (!state.autoStartProxyOnAppLaunch) {
            AndroidAppLogger.info(LogTag, "Boot completed received but auto-start proxy is disabled")
            return
        }

        // Only handle VPN service mode as requested (ROOT modes use service.d scripts)
        if (state.runMode != RunModeVpnService) {
            AndroidAppLogger.info(LogTag, "Boot completed received for non-VPN mode (${state.runMode}), skipping")
            return
        }

        val running = runCatching { proxyEngine.status(RunModeVpnService, state).running }
            .getOrElse { false }
        if (running) {
            AndroidAppLogger.info(LogTag, "VPN proxy is already running on boot")
            return
        }

        AndroidAppLogger.info(LogTag, "Triggering VPN proxy auto-start on boot...")
        when (val result = proxyServiceUseCase.start(state.copy(proxyRunning = false))) {
            is ProxyServiceResult.Success -> {
                stateStore.update { current ->
                    current.copy(
                        proxyRunning = result.proxyRunning,
                        localProxyPort = result.appState?.localProxyPort ?: current.localProxyPort,
                        singBoxControlPort = result.appState?.singBoxControlPort ?: current.singBoxControlPort,
                    )
                }
                AndroidAppLogger.info(LogTag, "VPN proxy auto-started successfully on boot")
            }
            is ProxyServiceResult.Failed -> {
                AndroidAppLogger.error(LogTag, "VPN proxy auto-start failed on boot", result.error)
            }
        }
    }
}

private const val LogTag = "BootCompletedReceiver"
