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

/** Unified proxy destination combining node management, runtime state and selector switching. */
@Composable
internal fun SingBoxProxyDestination(padding: PaddingValues) {
    OutboundListPage(
        padding = padding,
        embeddedInProxyTab = true,
    )
}
