// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.singbox

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import app.LocalAppServices
import app.LocalAppStateStore
import app.collectAppState
import features.outbound.OutboundListPage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One page scaffold at a time; each mode keeps its own saved search and scroll state. */
@Composable
internal fun SingBoxProxyDestination(padding: PaddingValues) {
    val appState by LocalAppStateStore.current.collectAppState()
    val runtime = LocalAppServices.current.singBoxRuntime
    val ready by remember(runtime) {
        runtime.state.map { state ->
            state.running && !state.proxiesRefreshing && state.proxies.groups.isNotEmpty()
        }.distinctUntilChanged()
    }.collectAsState(initial = false)
    val pageStateHolder = rememberSaveableStateHolder()
    var runtimePageShown by remember { mutableStateOf(false) }
    var managementInteractionActive by remember { mutableStateOf(false) }

    LaunchedEffect(appState.proxyRunning, ready, managementInteractionActive) {
        if (!appState.proxyRunning) {
            runtimePageShown = false
        } else if (ready && !managementInteractionActive) {
            runtimePageShown = true
        }
    }
    // Once shown, keep runtime errors/empty results on the runtime page until service stops.
    val showRuntime = appState.proxyRunning && runtimePageShown
    pageStateHolder.SaveableStateProvider(if (showRuntime) "runtime" else "outbounds") {
        if (showRuntime) {
            SingBoxProxyPage(padding)
        } else {
            OutboundListPage(
                padding = padding,
                embeddedInProxyTab = true,
                onInteractionActiveChange = { managementInteractionActive = it },
            )
        }
    }
}
