// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package app.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.AppState
import app.hasSingleNodeKernelChanged
import app.selectedSingleOutboundOrNull
import data.AndroidAppStateStore
import engine.singbox.runtime.SingBoxRuntimeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feedback.AndroidToastTipNotifier

@Composable
internal fun SingBoxRuntimeSynchronizer(
    stateStore: AndroidAppStateStore,
    singBoxRuntime: SingBoxRuntimeRepository,
    tipNotifier: AndroidToastTipNotifier? = null,
) {
    LaunchedEffect(stateStore, singBoxRuntime, tipNotifier) {
        var previousState: AppState? = null
        var reloadJob: Job? = null
        stateStore.state
            .collect { currentAppState ->
                val prev = previousState
                previousState = currentAppState

                singBoxRuntime.start(currentAppState)

                if (prev != null && prev.proxyRunning && currentAppState.proxyRunning) {
                    if (prev.hasKernelConfigurationChanged(currentAppState)) {
                        reloadJob?.cancel()
                        reloadJob = launch {
                            delay(400)
                            singBoxRuntime.applyConfigurationChange(currentAppState, tipNotifier)
                        }
                    }
                }
            }
    }
}

private fun AppState.hasKernelConfigurationChanged(other: AppState): Boolean {
    val currentSingle = selectedSingleOutboundOrNull()
    val nextSingle = other.selectedSingleOutboundOrNull()
    if (currentSingle != null && nextSingle != null && currentSingle.tag == nextSingle.tag) {
        return hasSingleNodeKernelChanged(other, currentSingle)
    }

    return outbounds != other.outbounds ||
        outboundGroups != other.outboundGroups ||
        endpoints != other.endpoints ||
        selectors != other.selectors ||
        routeRules != other.routeRules ||
        routeFinal != other.routeFinal ||
        routeAutoDetectInterface != other.routeAutoDetectInterface ||
        routeOverrideAndroidVpn != other.routeOverrideAndroidVpn ||
        routeFindProcess != other.routeFindProcess ||
        routeDefaultNetworkStrategy != other.routeDefaultNetworkStrategy ||
        routeDefaultNetworkTypes != other.routeDefaultNetworkTypes ||
        dnsRules != other.dnsRules ||
        dnsServers != other.dnsServers ||
        dnsFinal != other.dnsFinal ||
        dnsCacheCapacity != other.dnsCacheCapacity ||
        dnsOptimisticCache != other.dnsOptimisticCache ||
        dnsDisableCache != other.dnsDisableCache ||
        dnsDisableExpire != other.dnsDisableExpire ||
        dnsTimeout != other.dnsTimeout ||
        enableLocalDns != other.enableLocalDns ||
        enableIpv6 != other.enableIpv6 ||
        configOverrideScript != other.configOverrideScript ||
        enableConfigOverrideScript != other.enableConfigOverrideScript
}
