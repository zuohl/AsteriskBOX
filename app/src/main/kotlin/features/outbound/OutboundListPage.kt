// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.outbound

import ui.components.AsteriskSearchTopAppBar
import ui.components.AsteriskTopBarControls
import ui.components.AsteriskDropdownMenuItem
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import ui.components.AsteriskScaffold
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.LocalAppServices
import app.LocalAppStateStore
import app.LocalIsWideScreen
import app.LocalMainDestinationState
import app.LocalNavigator
import app.LocalUpdateAppState
import app.OutboundGroupState
import app.OutboundState
import app.collectAppState
import app.modes.OutboundListLayoutAuto
import app.modes.OutboundListLayoutDouble
import app.modes.OutboundListLayoutMultiple
import app.modes.OutboundListLayoutSingle
import app.modes.OutboundListSortDefault
import app.modes.OutboundListSortLatency
import app.modes.OutboundListSortName
import app.modes.OutboundListSortRealLatency
import app.modes.OutboundListSortType
import app.navigation.Route
import app.navigation.MainDestination
import engine.singbox.config.APP_ALL_NODES_TEST_SELECTOR
import engine.singbox.config.validateSingBoxRuntimeConfiguration
import features.importing.ImportOperation
import features.importing.ImportResultDialog
import features.importing.ImportResultPresentation
import features.importing.ImportSource
import features.importing.ImportStage
import features.importing.importFailureContext
import features.importing.importFailureResultPresentation
import features.importing.readImportUtf8WithinLimit
import features.importing.reportImportFailure
import features.importing.toImportResultPresentation
import features.logs.FailureLogContext
import features.singbox.displaySingBoxProtocolName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.R
import sh.calvin.reorderable.ReorderableItem
import ui.clipboard.getPlainText
import ui.clipboard.setPlainText
import ui.isInDarkTheme
import ui.components.AsteriskExpressiveCard
import ui.components.AsteriskFilterChip
import ui.components.AsteriskInfoChip
import ui.components.WarningConfirmDialog
import ui.components.draggedCardShadow
import ui.components.rememberReorderPreview
import ui.components.longPressReorderDragHandle
import ui.components.rememberAsteriskReorderableLazyGridState
import ui.components.singBoxOptionLabel
import ui.components.verticalReorderScrollThresholdPadding
import ui.layout.pageContentPaddingWithCutout
import ui.layout.pageListPadding
import ui.theme.AsteriskMotion
import ui.theme.AsteriskShapeTokens
import app.AppState
import app.SingBoxSelectorState
import app.LocalHomeServiceControl
import app.ManagedGlobalSelectorTag
import app.ManagedDirectOutboundTag
import app.managedOutboundGroupSelectorTag
import app.withSelectorSelection
import app.SingBoxSelectorTypeSelector
import app.SingBoxSelectorTypeUrlTest
import ui.components.AsteriskModalBottomSheet
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.grid.items as gridItems
import ui.icons.AsteriskIcons as Icons
import engine.singbox.runtime.SingBoxRuntimeState

private enum class OutboundImportMenuLevel {
    MAIN,
    MANUAL,
}

private enum class OutboundOptionsMenuLevel {
    MAIN,
    LAYOUT,
    SORT,
    DELETE,
}

private data class OutboundBatchDeletion(
    val action: OutboundBatchDeleteAction,
    val groupName: String,
    val outbounds: List<OutboundState>,
    val groupOutbounds: List<OutboundState>,
)

private val OutboundBatchDeleteAction.titleResource: Int
    get() = when (this) {
        OutboundBatchDeleteAction.DUPLICATES -> R.string.outbound_delete_duplicates
        OutboundBatchDeleteAction.INVALID -> R.string.outbound_delete_invalid
        OutboundBatchDeleteAction.ALL -> R.string.outbound_delete_all
    }

private val OutboundBatchDeleteAction.emptyResource: Int
    get() = when (this) {
        OutboundBatchDeleteAction.DUPLICATES -> R.string.outbound_no_duplicates
        OutboundBatchDeleteAction.INVALID -> R.string.outbound_no_invalid
        OutboundBatchDeleteAction.ALL -> R.string.outbound_no_proxy_servers_to_delete
    }

private enum class OutboundCardMenuLevel {
    MAIN,
    SHARE,
}

private data class OutboundQrDialogState(
    val title: String,
    val url: String,
)

@Composable
internal fun OutboundListPage(
    padding: PaddingValues,
    embeddedInProxyTab: Boolean = false,
    onInteractionActiveChange: (Boolean) -> Unit = {},
) {
    val stateStore = LocalAppStateStore.current
    val appState by stateStore.collectAppState()
    val updateAppState = LocalUpdateAppState.current
    val navigator = LocalNavigator.current
    val mainDestinationState = LocalMainDestinationState.current
    val services = LocalAppServices.current
    val isWideScreen = LocalIsWideScreen.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var activeOperations by remember { mutableIntStateOf(0) }
    var activeChildInteractions by remember { mutableIntStateOf(0) }
    val interactionCallback by rememberUpdatedState(onInteractionActiveChange)
    val onChildInteractionChange: (Int) -> Unit = remember {
        { delta ->
            activeChildInteractions += delta
            if (delta > 0) interactionCallback(true)
        }
    }
    fun launchOperation(block: suspend CoroutineScope.() -> Unit) =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            activeOperations += 1
            interactionCallback(true)
            try {
                block()
            } finally {
                activeOperations -= 1
            }
        }
    val pingState by services.outboundPingRuntime.state.collectAsState()
    val outboundIndex = remember(appState.outbounds) {
        services.outboundListProjectionCache.build(appState.outbounds)
    }
    val groups = appState.outboundGroups
    val pagerState = rememberPagerState(pageCount = { groups.size.coerceAtLeast(1) })
    var importMenuExpanded by remember { mutableStateOf(false) }
    var importMenuLevel by remember { mutableStateOf(OutboundImportMenuLevel.MAIN) }
    val manualImportMenuScrollState = rememberScrollState()
    val manualImportMenuHeight = with(LocalDensity.current) {
        outboundImportManualMenuHeightDp(
            windowHeightDp = LocalWindowInfo.current.containerSize.height.toDp().value.toInt(),
        ).dp
    }
    var query by rememberSaveable { mutableStateOf("") }
    val homeServiceControl = LocalHomeServiceControl.current
    val activeTarget = appState.selectorSelections[ManagedGlobalSelectorTag]?.trim().orEmpty()
    val runtimeState by remember(services.singBoxRuntime) {
        services.singBoxRuntime.state
    }.collectAsState()
    val isCustomSelector = appState.selectors.any { it.tag == activeTarget }
    val isGroupSelector = activeTarget.startsWith("outbound_group_")
    val isSelectorMode = isCustomSelector || isGroupSelector
    val activeSelector = if (isCustomSelector) appState.selectors.firstOrNull { it.tag == activeTarget } else null
    val activeGroup = if (isGroupSelector) groups.firstOrNull { managedOutboundGroupSelectorTag(it.id, it.name) == activeTarget } else null
    val selectedSingleOutbound = if (!isSelectorMode && activeTarget.isNotEmpty() && activeTarget != ManagedDirectOutboundTag) {
        appState.outbounds.firstOrNull { it.tag == activeTarget }
    } else null

    val effectiveActiveNodeTag = when {
        selectedSingleOutbound != null -> selectedSingleOutbound.tag
        isSelectorMode -> {
            val runtimeGroup = runtimeState.proxies.groups.firstOrNull { it.name == activeTarget }
            val runtimeNow = runtimeGroup?.now?.takeIf(String::isNotBlank)
            val persistedSelection = appState.selectorSelections[activeTarget]?.takeIf(String::isNotBlank)
            val defaultCandidate = if (isCustomSelector) {
                activeSelector?.outbounds?.firstOrNull()
            } else {
                activeGroup?.let { g -> appState.outbounds.firstOrNull { it.groupId == g.id }?.tag }
            }
            runtimeNow ?: persistedSelection ?: defaultCandidate.orEmpty()
        }
        else -> ""
    }
    val activeNodeRemarks = appState.outbounds.firstOrNull { it.tag == effectiveActiveNodeTag }?.remarks
        ?: effectiveActiveNodeTag.takeIf(String::isNotBlank)
    var showGlobalSelectorSheet by remember { mutableStateOf(false) }
    var locateTargetTag by remember { mutableStateOf<String?>(null) }
    var locateTrigger by remember { mutableStateOf(0L) }
    var pendingDelete by remember { mutableStateOf<OutboundState?>(null) }
    var deletingOutboundId by remember { mutableStateOf<Int?>(null) }
    var pendingBatchDelete by remember { mutableStateOf<OutboundBatchDeletion?>(null) }
    var deletingBatch by remember { mutableStateOf(false) }
    val reorderMutex = remember { Mutex() }
    var qrCodeDialogState by remember { mutableStateOf<OutboundQrDialogState?>(null) }
    var importResultPresentation by remember {
        mutableStateOf<ImportResultPresentation?>(null)
    }
    val interactionActive = activeOperations > 0 || activeChildInteractions > 0 ||
        importMenuExpanded || pendingDelete != null || pendingBatchDelete != null || qrCodeDialogState != null ||
        importResultPresentation != null
    SideEffect {
        // Nested effects can acquire interaction during apply, after this composition read.
        interactionCallback(interactionActive || activeChildInteractions > 0)
    }
    DisposableEffect(Unit) {
        onDispose { interactionCallback(false) }
    }
    val selectedGroup = groups.getOrNull(pagerState.currentPage) ?: groups.firstOrNull()
    val selectedOutbounds = outboundIndex.visible(
        groupId = selectedGroup?.id ?: Int.MIN_VALUE,
        query = "",
        sort = OutboundListSortDefault,
        pingState = pingState,
    ).map(OutboundListItem::outbound)
    val visibleCount = outboundIndex.visible(
        groupId = selectedGroup?.id ?: Int.MIN_VALUE,
        query = query,
        sort = appState.outboundListSort,
        pingState = pingState,
        proxiesState = runtimeState.proxies,
    ).size
    val columns = resolveOutboundListColumns(appState.outboundListLayout, isWideScreen)
    val importFailedMessage = stringResource(R.string.outbound_import_failed)
    val stateChangedMessage = stringResource(R.string.outbound_group_sync_failed)
    val emptyClipboardMessage = stringResource(R.string.outbound_import_empty_clipboard)
    val copiedMessage = stringResource(R.string.common_copied)
    val noPingTargetsMessage = stringResource(R.string.outbound_ping_no_targets)

    LaunchedEffect(appState.outbounds) {
        services.outboundPingRuntime.reconcile(appState.outbounds)
    }

    LaunchedEffect(groups.map(OutboundGroupState::id)) {
        if (pagerState.currentPage > groups.lastIndex && groups.isNotEmpty()) {
            pagerState.scrollToPage(groups.lastIndex)
        }
    }

    suspend fun importContent(content: String, source: ImportSource) {
        val targetGroupId = selectedGroup?.id ?: return
        var stage = ImportStage.PARSE
        try {
            val result = withContext(Dispatchers.Default) {
                parseOutboundImportContent(content)
            }
            val snapshot = stateStore.state.value
            val plan = snapshot.planOutboundImport(
                groupId = targetGroupId,
                parsed = result,
                replaceGroup = false,
                strict = false,
            )
            if (!plan.committed) {
                importResultPresentation = plan.outcome.toImportResultPresentation(
                    committed = false,
                )
                return
            }
            val candidateState = plan.state
            stage = ImportStage.VALIDATE
            withContext(Dispatchers.IO) {
                validateSingBoxRuntimeConfiguration(context, candidateState)
            }
            stage = ImportStage.COMMIT
            when (val persistResult = services.outboundRepository.persistImport(snapshot, candidateState)) {
                OutboundCommandResult.ImportPersisted -> {
                    val presentation = plan.outcome.toImportResultPresentation(committed = true)
                    if (presentation.showDialog) {
                        importResultPresentation = presentation
                    } else {
                        services.tipNotifier.show(
                            resources.getQuantityString(
                                R.plurals.outbound_import_success,
                                plan.outcome.accepted.size,
                                plan.outcome.accepted.size,
                            ),
                        )
                    }
                }
                OutboundCommandResult.Conflict -> {
                    reportImportFailure(
                        operation = ImportOperation.OUTBOUND,
                        source = source,
                        stage = ImportStage.COMMIT,
                    )
                    importResultPresentation = importFailureResultPresentation(stateChangedMessage)
                }
                is OutboundCommandResult.Invalid -> {
                    reportImportFailure(
                        operation = ImportOperation.OUTBOUND,
                        source = source,
                        stage = ImportStage.VALIDATE,
                        error = persistResult.error,
                    )
                    importResultPresentation = importFailureResultPresentation(
                        persistResult.error.message ?: importFailedMessage,
                    )
                }
                is OutboundCommandResult.PersistenceFailed -> {
                    reportImportFailure(
                        operation = ImportOperation.OUTBOUND,
                        source = source,
                        stage = ImportStage.COMMIT,
                        error = persistResult.error,
                    )
                    importResultPresentation = importFailureResultPresentation(
                        persistResult.error.message ?: importFailedMessage,
                    )
                }
                else -> error("Unexpected outbound import result: $persistResult")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportImportFailure(
                operation = ImportOperation.OUTBOUND,
                source = source,
                stage = stage,
                error = error,
            )
            importResultPresentation = importFailureResultPresentation(
                error.message ?: importFailedMessage,
            )
        }
    }

    fun importQrCode() {
        launchOperation {
            try {
                services.qrCodeScanner()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { importContent(it, ImportSource.QR_CODE) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.QR_CODE,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun importClipboard() {
        launchOperation {
            try {
                val content = clipboard.getPlainText().orEmpty()
                require(content.isNotBlank()) { emptyClipboardMessage }
                importContent(content, ImportSource.CLIPBOARD)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.CLIPBOARD,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun importFile() {
        launchOperation {
            try {
                val uri = services.importFilePicker() ?: return@launchOperation
                val content = withContext(Dispatchers.IO) { context.readOutboundImportFile(uri) }
                importContent(content, ImportSource.FILE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                services.tipNotifier.showError(
                    error,
                    importFailedMessage,
                    importFailureContext(
                        ImportOperation.OUTBOUND,
                        ImportSource.FILE,
                        ImportStage.READ,
                    ),
                )
            }
        }
    }

    fun pingOutbounds(
        targets: List<OutboundState>,
    ) {
        val testable = targets.filter { outbound ->
            outboundIndex.item(outbound.id)?.pingHost != null
        }
        if (testable.isEmpty()) {
            launchOperation { services.tipNotifier.show(noPingTargetsMessage) }
            return
        }
        services.outboundPingRuntime.start(testable)
    }

    suspend fun handleOutboundCommandResult(
        result: OutboundCommandResult,
        expectedSuccess: OutboundCommandResult,
        operation: String,
        onSuccess: suspend () -> Unit = {},
    ) {
        when (result) {
            expectedSuccess -> onSuccess()
            OutboundCommandResult.Conflict -> services.tipNotifier.show(stateChangedMessage)
            is OutboundCommandResult.Invalid -> services.tipNotifier.show(importFailedMessage)
            is OutboundCommandResult.PersistenceFailed -> services.tipNotifier.showError(
                result.error,
                importFailedMessage,
                FailureLogContext(operation = operation, stage = "persist"),
            )
            else -> error("Unexpected $operation result: $result")
        }
    }

    AsteriskScaffold(
        topBar = {
            Column {
                AsteriskSearchTopAppBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = stringResource(R.string.outbound_search),
                    title = {
                        Column {
                            Text(stringResource(R.string.outbound_management))
                            val countEffectsMotion = AsteriskMotion.fastEffects<Float>()
                            AnimatedContent(
                                targetState = visibleCount,
                                transitionSpec = AsteriskMotion.fadeThrough(countEffectsMotion),
                                label = "outbound-count",
                            ) { count ->
                                Text(
                                    text = pluralStringResource(R.plurals.outbound_count, count, count),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        if (!embeddedInProxyTab) {
                            IconButton(onClick = navigator::pop) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    stringResource(R.string.common_back),
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = {
                                    importMenuLevel = OutboundImportMenuLevel.MAIN
                                    importMenuExpanded = true
                                },
                                enabled = selectedGroup != null,
                            ) {
                                Icon(Icons.Rounded.Add, stringResource(R.string.outbound_import))
                            }
                            val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
                            val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
                            val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
                            DropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                                modifier = Modifier.width(OutboundImportMenuWidth),
                            ) {
                                AnimatedContent(
                                    targetState = importMenuLevel,
                                    modifier = Modifier.fillMaxWidth(),
                                    transitionSpec = AsteriskMotion.horizontalSlideFade(
                                        spatialSpec = menuSpatialMotion,
                                        effectsSpec = menuEffectsMotion,
                                        sizeSpec = menuSizeMotion,
                                    ) {
                                        if (targetState == OutboundImportMenuLevel.MAIN) -1 else 1
                                    },
                                    contentAlignment = Alignment.TopStart,
                                    label = "outbound-import-level",
                                ) { level ->
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        when (level) {
                                            OutboundImportMenuLevel.MAIN -> {
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_qr),
                                                    icon = Icons.Rounded.QrCodeScanner,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importQrCode()
                                                    },
                                                )
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_clipboard),
                                                    icon = Icons.Rounded.ContentPaste,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importClipboard()
                                                    },
                                                )
                                                OutboundMenuItem(
                                                    text = stringResource(R.string.outbound_import_file),
                                                    icon = Icons.Rounded.FileDownload,
                                                    onClick = {
                                                        importMenuExpanded = false
                                                        importFile()
                                                    },
                                                )
                                                HorizontalDivider()
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.outbound_import_manual),
                                                        )
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.EditNote, contentDescription = null)
                                                    },
                                                    trailingIcon = {
                                                        Icon(
                                                            Icons.Rounded.ChevronRight,
                                                            contentDescription = null,
                                                        )
                                                    },
                                                    onClick = {
                                                        launchOperation {
                                                            manualImportMenuScrollState.scrollTo(0)
                                                            importMenuLevel =
                                                                OutboundImportMenuLevel.MANUAL
                                                        }
                                                    },
                                                )
                                            }
                                            OutboundImportMenuLevel.MANUAL -> {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(manualImportMenuHeight),
                                                ) {
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                stringResource(
                                                                    R.string.outbound_import_manual,
                                                                ),
                                                            )
                                                        },
                                                        leadingIcon = {
                                                            Icon(
                                                                Icons.Rounded.ChevronLeft,
                                                                contentDescription = null,
                                                            )
                                                        },
                                                        onClick = {
                                                            importMenuLevel =
                                                                OutboundImportMenuLevel.MAIN
                                                        },
                                                    )
                                                    HorizontalDivider()
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .verticalScroll(
                                                                manualImportMenuScrollState,
                                                            ),
                                                    ) {
                                                        OutboundEditorRegistry.descriptors.forEach { descriptor ->
                                                            OutboundMenuItem(
                                                                text = singBoxOptionLabel(
                                                                    descriptor.title,
                                                                    descriptor.type,
                                                                ),
                                                                icon = null,
                                                                onClick = {
                                                                    importMenuExpanded = false
                                                                    selectedGroup?.let { group ->
                                                                        navigator.push(
                                                                            Route.OutboundEdit(
                                                                                groupId = group.id,
                                                                                type = descriptor.type,
                                                                            ),
                                                                        )
                                                                    }
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val currentActiveTag = if (isSelectorMode) effectiveActiveNodeTag else activeTarget
                        val activeOutbound = appState.outbounds.firstOrNull { it.tag == currentActiveTag }
                        IconButton(
                            onClick = {
                                if (activeOutbound != null) {
                                    val targetPageIndex = groups.indexOfFirst { it.id == activeOutbound.groupId }
                                    scope.launch {
                                        if (targetPageIndex >= 0 && targetPageIndex != pagerState.currentPage) {
                                            pagerState.animateScrollToPage(targetPageIndex)
                                        }
                                        locateTargetTag = activeOutbound.tag
                                        locateTrigger = System.currentTimeMillis()
                                        services.tipNotifier.show(
                                            context.getString(
                                                R.string.outbound_locate_success,
                                                activeOutbound.remarks.ifBlank { activeOutbound.tag },
                                            ),
                                        )
                                    }
                                } else {
                                    scope.launch {
                                        services.tipNotifier.show(context.getString(R.string.outbound_locate_not_found))
                                    }
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MyLocation,
                                contentDescription = stringResource(R.string.outbound_locate_active),
                            )
                        }
                        val isTestingDelay = (runtimeState.delayTestingTarget != null) ||
                            selectedOutbounds.any { it.id in pingState.runningIds }
                        IconButton(
                            onClick = {
                                pingOutbounds(targets = selectedOutbounds)
                            },
                            enabled = selectedOutbounds.isNotEmpty() && !isTestingDelay,
                        ) {
                            if (isTestingDelay) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Speed,
                                    contentDescription = stringResource(R.string.outbound_ping),
                                )
                            }
                        }
                        OutboundOptionsMenu(
                            onInteractionCountChange = onChildInteractionChange,
                            layout = appState.outboundListLayout,
                            sort = appState.outboundListSort,
                            pingRunning = selectedOutbounds.any { outbound ->
                                outbound.id in pingState.runningIds
                            },
                            onPing = {
                                pingOutbounds(
                                    targets = selectedOutbounds,
                                )
                            },
                            onLayoutChange = { layout ->
                                updateAppState { state -> state.copy(outboundListLayout = layout) }
                            },
                            onSortChange = { sort ->
                                updateAppState { state -> state.copy(outboundListSort = sort) }
                            },
                            toolsEnabled = selectedGroup != null && activeOperations == 0,
                            onCopyAllUrls = {
                                val groupId = selectedGroup?.id
                                val targets = stateStore.state.value.outbounds.filter { it.groupId == groupId }
                                launchOperation {
                                    val urls = withContext(Dispatchers.Default) { outboundUrls(targets) }
                                    if (urls.isBlank()) {
                                        services.tipNotifier.show(resources.getString(R.string.outbound_no_urls))
                                    } else {
                                        clipboard.setPlainText(urls)
                                        services.tipNotifier.show(copiedMessage)
                                    }
                                }
                            },
                            onDeleteOutbounds = { action ->
                                if (selectedGroup != null) {
                                    val snapshot = stateStore.state.value
                                    val targets = snapshot.outbounds.filter { it.groupId == selectedGroup.id }
                                    val selectedTags = snapshot.selectorSelections.values.toSet() +
                                        services.singBoxRuntime.state.value.proxies.groups.map { it.now }
                                    launchOperation {
                                        val deletions = withContext(Dispatchers.Default) {
                                            when (action) {
                                                OutboundBatchDeleteAction.DUPLICATES ->
                                                    duplicateOutbounds(targets, selectedTags)
                                                OutboundBatchDeleteAction.INVALID ->
                                                    targets.filter { isInvalidOutbound(it) }
                                                OutboundBatchDeleteAction.ALL -> targets
                                            }
                                        }
                                        if (deletions.isEmpty()) {
                                            services.tipNotifier.show(resources.getString(action.emptyResource))
                                        } else {
                                            pendingBatchDelete = OutboundBatchDeletion(action, selectedGroup.name, deletions, targets)
                                        }
                                    }
                                }
                            },
                        )
                    },
                )
                AsteriskTopBarControls {
                    val isServiceRunning = appState.proxyRunning
                    val (globalTitle, globalSubtitle, globalIcon) = remember(
                        activeTarget,
                        effectiveActiveNodeTag,
                        activeNodeRemarks,
                        appState.outbounds,
                        appState.selectors,
                        appState.outboundGroups,
                    ) {
                        when {
                            activeTarget == ManagedDirectOutboundTag -> Triple(
                                "直连 (Direct)",
                                "绕过代理直接访问互联网",
                                Icons.Rounded.Route,
                            )
                            isCustomSelector -> {
                                val sel = appState.selectors.first { it.tag == activeTarget }
                                val typeName = if (sel.type == SingBoxSelectorTypeUrlTest) "自动优选" else "策略组"
                                Triple(
                                    sel.remarks.ifBlank { sel.tag },
                                    if (activeNodeRemarks != null) "当前节点: $activeNodeRemarks" else "策略组调度 · $typeName",
                                    if (sel.type == SingBoxSelectorTypeUrlTest) Icons.Rounded.Speed else Icons.Rounded.Tune,
                                )
                            }
                            isGroupSelector -> {
                                val grp = appState.outboundGroups.first { managedOutboundGroupSelectorTag(it.id, it.name) == activeTarget }
                                Triple(
                                    grp.name,
                                    if (activeNodeRemarks != null) "当前节点: $activeNodeRemarks" else "分组策略调度",
                                    Icons.Rounded.Folder,
                                )
                            }
                            selectedSingleOutbound != null -> {
                                Triple(
                                    selectedSingleOutbound.remarks.ifBlank { selectedSingleOutbound.tag },
                                    "单节点直连 (${selectedSingleOutbound.type}) · 极简配置",
                                    Icons.Rounded.Dns,
                                )
                            }
                            else -> Triple(
                                if (activeTarget.isNotBlank()) activeTarget else "全局出站",
                                "点击切换出站策略或单节点",
                                Icons.Rounded.Language,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlobalOutboundStatusCard(
                            title = globalTitle,
                            subtitle = globalSubtitle,
                            icon = globalIcon,
                            isRunning = isServiceRunning,
                            onClick = { showGlobalSelectorSheet = true },
                        )
                        if (groups.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(groups, key = OutboundGroupState::id) { group ->
                                    val index = groups.indexOfFirst { it.id == group.id }
                                    val selected = pagerState.currentPage == index
                                    AsteriskFilterChip(
                                        selected = selected,
                                        onClick = {
                                            launchOperation { pagerState.animateScrollToPage(index) }
                                        },
                                        label = buildString {
                                            append(group.displayName())
                                            append(" · ")
                                            append(outboundIndex.count(group.id))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val contentPadding = pageContentPaddingWithCutout(
            innerPadding = innerPadding,
            outerPadding = padding,
            isWideScreen = isWideScreen,
        )
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
            ) {
                OutboundGroupEmptyState(
                    onAdd = {
                        if (embeddedInProxyTab && mainDestinationState != null) {
                            mainDestinationState.select(MainDestination.Groups)
                        } else {
                            navigator.push(Route.OutboundGroupCreate)
                        }
                    },
                )
            }
            return@AsteriskScaffold
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            val group = groups.getOrNull(page)
            val groupOutbounds = outboundIndex.visible(
                groupId = group?.id ?: Int.MIN_VALUE,
                query = query,
                sort = appState.outboundListSort,
                pingState = pingState,
                proxiesState = runtimeState.proxies,
            )
            val reorderEnabled =
                appState.outboundListSort == OutboundListSortDefault && query.isBlank()
            val preview = rememberReorderPreview(
                items = groupOutbounds,
                key = OutboundListItem::id,
                enabled = reorderEnabled,
                listKey = group?.id,
                commitScope = scope,
            ) { ids ->
                val groupId = group?.id
                if (groupId == null) {
                    false
                } else {
                    activeOperations += 1
                    interactionCallback(true)
                    try {
                        reorderMutex.withLock {
                            val result = services.outboundRepository.reorder(groupId, ids)
                            handleOutboundCommandResult(
                                result = result,
                                expectedSuccess = OutboundCommandResult.Reordered,
                                operation = "outbound_reorder",
                                onSuccess = {},
                            )
                            result is OutboundCommandResult.Reordered
                        }
                    } finally {
                        activeOperations -= 1
                    }
                }
            }
            val dragScrollThresholdBottomPadding =
                pageListPadding(contentPadding).calculateBottomPadding()
            OutboundPage(
                onInteractionCountChange = onChildInteractionChange,
                outbounds = preview.items,
                contentPadding = pageListPadding(
                    contentPadding = contentPadding,
                    bottomExtra = outboundListBottomExtraDp().dp,
                ),
                dragScrollThresholdBottomPadding = dragScrollThresholdBottomPadding,
                hasQuery = query.isNotBlank(),
                columns = columns,
                activeTarget = activeTarget,
                reorderEnabled = reorderEnabled,
                pingState = pingState,
                runtimeState = runtimeState,
                isSelectorMode = isSelectorMode,
                effectiveActiveNodeTag = effectiveActiveNodeTag,
                onMove = preview.onMove,
                onDragStarted = preview.onDragStarted,
                onDragStopped = preview.onDragStopped,
                onSelect = { outbound ->
                    if (isSelectorMode) {
                        val activeGroupTag = activeTarget
                        val wasSelected = effectiveActiveNodeTag == outbound.tag
                        if (!wasSelected) {
                            updateAppState { state ->
                                state.withSelectorSelection(activeGroupTag, outbound.tag)
                            }
                            if (appState.proxyRunning) {
                                scope.launch {
                                    services.singBoxRuntime.selectProxy(appState, activeGroupTag, outbound.tag)
                                        .onSuccess {
                                            services.tipNotifier.show("已切换至 ${outbound.remarks.ifBlank { outbound.tag }}")
                                        }
                                        .onFailure {
                                            services.tipNotifier.show(it.message ?: "切换失败")
                                        }
                                }
                            } else {
                                scope.launch {
                                    services.tipNotifier.show("已选择 ${outbound.remarks.ifBlank { outbound.tag }}")
                                }
                            }
                        }
                    } else {
                        val wasSelected = activeTarget == outbound.tag
                        updateAppState { state ->
                            state.withSelectorSelection(ManagedGlobalSelectorTag, outbound.tag)
                        }
                        if (!appState.proxyRunning) {
                            if (!homeServiceControl.busy) {
                                homeServiceControl.toggleService()
                            }
                        } else if (!wasSelected) {
                            if (!homeServiceControl.busy) {
                                homeServiceControl.restartService()
                            }
                        }
                    }
                },
                onSingleConnect = { outbound ->
                    val wasSelected = activeTarget == outbound.tag
                    updateAppState { state ->
                        state.withSelectorSelection(ManagedGlobalSelectorTag, outbound.tag)
                    }
                    if (!appState.proxyRunning) {
                        if (!homeServiceControl.busy) {
                            homeServiceControl.toggleService()
                        }
                    } else if (!wasSelected) {
                        if (!homeServiceControl.busy) {
                            homeServiceControl.restartService()
                        }
                    }
                    scope.launch {
                        services.tipNotifier.show(
                            context.getString(
                                R.string.outbound_single_connect_success,
                                outbound.remarks.ifBlank { outbound.tag },
                            ),
                        )
                    }
                },
                onEdit = { outbound ->
                    navigator.push(
                        Route.OutboundEdit(
                            outboundId = outbound.id,
                            groupId = outbound.groupId,
                            type = outbound.type,
                        ),
                    )
                },
                onShare = { outbound, action, shareUrlResult ->
                    when (action) {
                        OutboundShareAction.QR_CODE -> {
                            outboundShareUrlPayload(action, shareUrlResult)?.let { url ->
                                qrCodeDialogState = OutboundQrDialogState(
                                    title = outbound.remarks.ifBlank { outbound.type },
                                    url = url,
                                )
                            }
                        }

                        OutboundShareAction.URL -> {
                            outboundShareUrlPayload(action, shareUrlResult)?.let { url ->
                                launchOperation {
                                    clipboard.setPlainText(url)
                                    services.tipNotifier.show(copiedMessage)
                                }
                            }
                        }

                        OutboundShareAction.JSON -> {
                            launchOperation {
                                clipboard.setPlainText(
                                    outboundJsonWithoutManagedIdentity(outbound.json),
                                )
                                services.tipNotifier.show(copiedMessage)
                            }
                        }
                    }
                },
                onPing = { outbound ->
                    val inKernel = appState.proxyRunning && runtimeState.proxies.nodeByName.containsKey(outbound.tag)
                    if (inKernel) {
                        scope.launch {
                            services.singBoxRuntime.testProxyDelay(appState, outbound.tag)
                                .onSuccess {
                                    services.tipNotifier.show("测速完成: ${outbound.remarks.ifBlank { outbound.tag }}")
                                }
                                .onFailure {
                                    services.tipNotifier.show(it.message ?: "测速失败")
                                }
                        }
                    } else {
                        pingOutbounds(targets = listOf(outbound))
                    }
                },
                onDelete = { pendingDelete = it },
                locateTargetTag = locateTargetTag,
                locateTrigger = locateTrigger,
            )
        }
    }

    WarningConfirmDialog(
        show = pendingDelete != null,
        title = stringResource(R.string.outbound_delete_title),
        summary = stringResource(R.string.outbound_delete_message, pendingDelete?.remarks.orEmpty()),
        dismissText = stringResource(R.string.common_cancel),
        confirmText = stringResource(R.string.common_delete),
        onDismissRequest = { pendingDelete = null },
        onConfirm = {
            val id = pendingDelete?.id ?: return@WarningConfirmDialog
            if (deletingOutboundId != null) return@WarningConfirmDialog
            deletingOutboundId = id
            launchOperation {
                try {
                    handleOutboundCommandResult(
                        result = services.outboundRepository.delete(id),
                        expectedSuccess = OutboundCommandResult.Deleted,
                        operation = "outbound_delete",
                        onSuccess = {
                            if (pendingDelete?.id == id) {
                                pendingDelete = null
                            }
                        },
                    )
                } finally {
                    if (deletingOutboundId == id) {
                        deletingOutboundId = null
                    }
                }
            }
        },
        busy = deletingOutboundId != null,
    )

    pendingBatchDelete?.let { deletion ->
        WarningConfirmDialog(
            show = true,
            title = stringResource(deletion.action.titleResource),
            summary = pluralStringResource(
                R.plurals.outbound_batch_delete_message,
                deletion.outbounds.size,
                deletion.groupName,
                deletion.outbounds.size,
            ),
            dismissText = stringResource(R.string.common_cancel),
            confirmText = stringResource(R.string.common_delete),
            onDismissRequest = { if (!deletingBatch) pendingBatchDelete = null },
            onConfirm = {
                if (!deletingBatch) {
                    deletingBatch = true
                    launchOperation {
                        try {
                            handleOutboundCommandResult(
                                result = services.outboundRepository.delete(deletion.outbounds, deletion.groupOutbounds),
                                expectedSuccess = OutboundCommandResult.Deleted,
                                operation = "outbound_batch_delete",
                                onSuccess = {
                                    services.tipNotifier.show(
                                        resources.getQuantityString(
                                            R.plurals.outbound_proxy_servers_deleted,
                                            deletion.outbounds.size,
                                            deletion.outbounds.size,
                                        ),
                                    )
                                },
                            )
                        } finally {
                            pendingBatchDelete = null
                            deletingBatch = false
                        }
                    }
                }
            },
            busy = deletingBatch,
        )
    }

    qrCodeDialogState?.let { state ->
        OutboundQrCodeDialog(
            title = state.title,
            url = state.url,
            onDismissRequest = { qrCodeDialogState = null },
        )
    }

    importResultPresentation?.let { presentation ->
        ImportResultDialog(
            presentation = presentation,
            onDismissRequest = { importResultPresentation = null },
        )
    }

    val enabledSelectorGroups = remember(appState.outboundGroups) {
        appState.outboundGroups.filter { it.enabled }
    }
    val enabledSelectorGroupIds = remember(enabledSelectorGroups) {
        enabledSelectorGroups.mapTo(mutableSetOf()) { it.id }
    }
    val enabledSelectorOutbounds = remember(appState.outbounds, enabledSelectorGroupIds) {
        appState.outbounds.filter { it.groupId in enabledSelectorGroupIds }
    }

    GlobalSelectorSheet(
        show = showGlobalSelectorSheet,
        onDismissRequest = { showGlobalSelectorSheet = false },
        activeTarget = activeTarget,
        selectors = appState.selectors,
        groups = enabledSelectorGroups,
        outbounds = enabledSelectorOutbounds,
        onSelectTarget = { tag ->
            val wasSelected = activeTarget == tag
            updateAppState { state ->
                state.withSelectorSelection(ManagedGlobalSelectorTag, tag)
            }
            showGlobalSelectorSheet = false
            if (appState.proxyRunning && !wasSelected) {
                if (!homeServiceControl.busy) {
                    homeServiceControl.restartService()
                }
            }
        },
        onAddSelector = {
            showGlobalSelectorSheet = false
            navigator.push(Route.SelectorEdit(0))
        },
    )
}

@Composable
private fun OutboundPage(
    outbounds: List<OutboundListItem>,
    contentPadding: PaddingValues,
    dragScrollThresholdBottomPadding: androidx.compose.ui.unit.Dp,
    hasQuery: Boolean,
    columns: Int,
    activeTarget: String,
    reorderEnabled: Boolean,
    pingState: OutboundPingRuntimeState,
    runtimeState: SingBoxRuntimeState,
    isSelectorMode: Boolean,
    effectiveActiveNodeTag: String,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragStarted: () -> Unit,
    onDragStopped: () -> Unit,
    onSelect: (OutboundState) -> Unit,
    onSingleConnect: (OutboundState) -> Unit,
    onEdit: (OutboundState) -> Unit,
    onShare: (OutboundState, OutboundShareAction, OutboundShareUrlResult) -> Unit,
    onPing: (OutboundState) -> Unit,
    onDelete: (OutboundState) -> Unit,
    locateTargetTag: String? = null,
    locateTrigger: Long = 0L,
    onInteractionCountChange: (Int) -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(locateTrigger) {
        if (locateTrigger > 0L && !locateTargetTag.isNullOrBlank()) {
            val targetIndex = outbounds.indexOfFirst { it.outbound.tag == locateTargetTag }
            if (targetIndex >= 0) {
                gridState.animateScrollToItem(targetIndex)
            }
        }
    }
    val reorderableState = rememberAsteriskReorderableLazyGridState(
        lazyGridState = gridState,
        itemCount = outbounds.size,
        scrollThresholdPadding = verticalReorderScrollThresholdPadding(
            contentPadding = contentPadding,
            bottomPadding = dragScrollThresholdBottomPadding,
        ),
        onMove = onMove,
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(OutboundGridSpacing),
        horizontalArrangement = Arrangement.spacedBy(OutboundGridSpacing),
    ) {
        if (outbounds.isEmpty()) {
            item(
                key = "empty",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = OutboundEmptyStateMinHeight)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (hasQuery) Icons.Rounded.SearchOff else Icons.Rounded.Router,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        text = stringResource(
                            if (hasQuery) R.string.outbound_search_empty else R.string.outbound_empty,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = stringResource(
                            if (hasQuery) R.string.outbound_search_empty_summary
                            else R.string.outbound_empty_summary,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        } else {
            gridItems(
                items = outbounds,
                key = OutboundListItem::id,
                contentType = { "outbound" },
            ) { item ->
                val outbound = item.outbound
                ReorderableItem(
                    state = reorderableState.reorderableState,
                    key = outbound.id,
                    enabled = reorderEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    animateItemModifier = Modifier.animateItem(),
                ) { isDragging ->
                    val isNodeSelected = if (isSelectorMode) {
                        outbound.tag == effectiveActiveNodeTag
                    } else {
                        outbound.tag == activeTarget
                    }
                    OutboundCard(
                        onInteractionCountChange = onInteractionCountChange,
                        item = item,
                        compact = columns > 1,
                        pingState = pingState,
                        runtimeState = runtimeState,
                        isDragging = isDragging && reorderEnabled,
                        isSelected = isNodeSelected,
                        onSelect = { onSelect(outbound) },
                        onSingleConnect = { onSingleConnect(outbound) },
                        onEdit = { onEdit(outbound) },
                        onShare = { action, result -> onShare(outbound, action, result) },
                        onPing = { onPing(outbound) },
                        onDelete = { onDelete(outbound) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .longPressReorderDragHandle(
                                scope = this,
                                enabled = reorderEnabled && outbounds.size > 1,
                                state = reorderableState,
                                onDragStarted = onDragStarted,
                                onDragStopped = onDragStopped,
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun OutboundCard(
    item: OutboundListItem,
    compact: Boolean,
    pingState: OutboundPingRuntimeState,
    runtimeState: SingBoxRuntimeState,
    isDragging: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onSingleConnect: () -> Unit,
    onEdit: () -> Unit,
    onShare: (OutboundShareAction, OutboundShareUrlResult) -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onInteractionCountChange: (Int) -> Unit = {},
) {
    TrackOutboundInteraction(isDragging, onInteractionCountChange)
    val outbound = item.outbound
    val runtimeNode = runtimeState.proxies.nodeByName[outbound.tag]
    val fallbackLatency = item.pingLatencyMillis(pingState)
    val realLatencyMillis = runtimeNode?.delay?.takeIf { it > 0 }?.toLong() ?: fallbackLatency
    val isTesting = (runtimeState.delayTestingTarget != null &&
        (runtimeState.delayTestingTarget == outbound.tag ||
            runtimeState.proxies.groups.any { it.name == runtimeState.delayTestingTarget && outbound.tag in it.all })) ||
        (outbound.id in pingState.runningIds)
    val isFailed = (runtimeNode != null && runtimeNode.name in runtimeState.delayFailureBaselines) ||
        (fallbackLatency == FailedPingMillis)
    val shareUrlResult = remember(outbound.json, outbound.remarks) {
        encodeOutboundShareUrl(outbound.json, outbound.remarks)
    }
    val containerColor by animateColorAsState(
        targetValue = when {
            isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        animationSpec = AsteriskMotion.effects(),
        label = "outbound-card-color",
    )
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.025f else 1f,
        animationSpec = AsteriskMotion.fastSpatial(),
        label = "outbound-drag-scale",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (isDragging) 1f else 0f,
        animationSpec = AsteriskMotion.fastEffects(),
        label = "outbound-drag-shadow",
    )
    AsteriskExpressiveCard(
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .height(OutboundCardHeight)
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .draggedCardShadow(
                alpha = shadowAlpha,
                color = MaterialTheme.colorScheme.primary,
                cornerRadius = AsteriskShapeTokens.PageCardRadius,
            ),
        containerColor = containerColor,
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(OutboundCardPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f).padding(top = 2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = outbound.remarks.ifBlank { outbound.type },
                            style = if (compact) {
                                MaterialTheme.typography.titleSmall.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 17.sp,
                                )
                            } else {
                                MaterialTheme.typography.titleMedium
                            },
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            maxLines = if (compact) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    item.endpointSummary?.takeUnless { compact }?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                OutboundCardMenu(
                    onInteractionCountChange = onInteractionCountChange,
                    pingEnabled = !isTesting,
                    shareUrlResult = shareUrlResult,
                    onSingleConnect = onSingleConnect,
                    onEdit = onEdit,
                    onShare = onShare,
                    onPing = onPing,
                    onDelete = onDelete,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AsteriskInfoChip(
                    text = outbound.type.displaySingBoxProtocolName(compact = compact),
                    modifier = Modifier.weight(1f, fill = false),
                    textStyle = if (compact) {
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            enabled = !isTesting,
                            onClick = onPing,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OutboundPingStatus(
                        latencyMillis = if (isFailed) FailedPingMillis else realLatencyMillis,
                        pinging = isTesting,
                    )
                }
            }
        }
    }
}

@Composable
private fun OutboundCardMenu(
    pingEnabled: Boolean,
    shareUrlResult: OutboundShareUrlResult,
    onSingleConnect: () -> Unit,
    onEdit: () -> Unit,
    onShare: (OutboundShareAction, OutboundShareUrlResult) -> Unit,
    onPing: () -> Unit,
    onDelete: () -> Unit,
    onInteractionCountChange: (Int) -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    TrackOutboundInteraction(menuExpanded, onInteractionCountChange)
    var level by remember { mutableStateOf(OutboundCardMenuLevel.MAIN) }
    val dismissMenu = {
        menuExpanded = false
        level = OutboundCardMenuLevel.MAIN
    }
    val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
    val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
    val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
    Box(
        modifier = Modifier.offset(
            x = OutboundCardMenuIconOffset.x.dp,
            y = OutboundCardMenuIconOffset.y.dp,
        ),
    ) {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.common_more),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = dismissMenu,
            modifier = Modifier.width(OutboundCardMenuWidth),
        ) {
            AnimatedContent(
                targetState = level,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = AsteriskMotion.horizontalSlideFade(
                    spatialSpec = menuSpatialMotion,
                    effectsSpec = menuEffectsMotion,
                    sizeSpec = menuSizeMotion,
                ) {
                    if (targetState == OutboundCardMenuLevel.MAIN) -1 else 1
                },
                contentAlignment = Alignment.TopStart,
                label = "outbound-card-menu-level",
            ) { currentLevel ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentLevel) {
                        OutboundCardMenuLevel.MAIN -> {
                            OutboundMenuItem(
                                text = stringResource(R.string.outbound_action_single_connect),
                                icon = Icons.Rounded.PlayArrow,
                                onClick = {
                                    dismissMenu()
                                    onSingleConnect()
                                },
                            )
                            HorizontalDivider()
                            OutboundMenuItem(
                                text = stringResource(R.string.outbound_ping),
                                icon = Icons.Rounded.Speed,
                                enabled = pingEnabled,
                                onClick = {
                                    dismissMenu()
                                    onPing()
                                },
                            )
                            HorizontalDivider()
                            OutboundMenuItem(
                                text = stringResource(R.string.common_edit),
                                icon = Icons.Rounded.Edit,
                                onClick = {
                                    dismissMenu()
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_share)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.Link, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                },
                                onClick = { level = OutboundCardMenuLevel.SHARE },
                            )
                            OutboundMenuItem(
                                text = stringResource(R.string.common_delete),
                                icon = Icons.Rounded.Delete,
                                onClick = {
                                    dismissMenu()
                                    onDelete()
                                },
                            )
                        }

                        OutboundCardMenuLevel.SHARE -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.common_share)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                },
                                onClick = { level = OutboundCardMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            outboundShareActions(shareUrlResult).forEach { action ->
                                val label = when (action) {
                                    OutboundShareAction.QR_CODE -> R.string.outbound_share_qr
                                    OutboundShareAction.URL -> R.string.outbound_share_url
                                    OutboundShareAction.JSON -> R.string.outbound_share_json
                                }
                                val icon = when (action) {
                                    OutboundShareAction.QR_CODE -> Icons.Rounded.QrCodeScanner
                                    OutboundShareAction.URL -> Icons.Rounded.Link
                                    OutboundShareAction.JSON -> Icons.Rounded.ContentCopy
                                }
                                OutboundMenuItem(
                                    text = stringResource(label),
                                    icon = icon,
                                    onClick = {
                                        dismissMenu()
                                        onShare(action, shareUrlResult)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboundPingStatus(
    latencyMillis: Long?,
    pinging: Boolean,
) {
    if (pinging) {
        val description = stringResource(R.string.outbound_ping_running)
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .semantics { contentDescription = description },
            strokeWidth = 2.dp,
        )
        return
    }
    if (latencyMillis == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = stringResource(R.string.outbound_ping),
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = "-- ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        return
    }
    Text(
        text = if (latencyMillis >= 0L) {
            stringResource(R.string.outbound_ping_latency, latencyMillis)
        } else {
            stringResource(R.string.outbound_ping_failed)
        },
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = outboundPingColor(latencyMillis),
        maxLines = 1,
    )
}

@Composable
private fun outboundPingColor(latencyMillis: Long): Color {
    val darkTheme = isInDarkTheme()
    return when {
        latencyMillis < 0L -> if (darkTheme) Color(0xFFF12522) else Color(0xFFE94634)
        latencyMillis < 100L -> if (darkTheme) Color(0xFF6BD58A) else Color(0xFF128A3C)
        latencyMillis < 200L -> if (darkTheme) Color(0xFFFFC857) else Color(0xFFD18A00)
        latencyMillis < 300L -> if (darkTheme) Color(0xFFFF9B63) else Color(0xFFE06400)
        else -> if (darkTheme) Color(0xFFF12522) else Color(0xFFE94634)
    }
}

@Composable
private fun OutboundOptionsMenu(
    layout: Int,
    sort: Int,
    pingRunning: Boolean,
    onPing: () -> Unit,
    onLayoutChange: (Int) -> Unit,
    onSortChange: (Int) -> Unit,
    toolsEnabled: Boolean,
    onCopyAllUrls: () -> Unit,
    onDeleteOutbounds: (OutboundBatchDeleteAction) -> Unit,
    onInteractionCountChange: (Int) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TrackOutboundInteraction(expanded, onInteractionCountChange)
    var level by rememberSaveable { mutableStateOf(OutboundOptionsMenuLevel.MAIN) }
    val dismissMenu = {
        expanded = false
        level = OutboundOptionsMenuLevel.MAIN
    }
    val layoutLabel = stringResource(
        when (layout) {
            OutboundListLayoutSingle -> R.string.outbound_option_layout_single
            OutboundListLayoutDouble -> R.string.outbound_option_layout_double
            OutboundListLayoutMultiple -> R.string.outbound_option_layout_multiple
            else -> R.string.outbound_option_layout_auto
        },
    )
    val sortLabel = stringResource(
        when (sort) {
            OutboundListSortName -> R.string.outbound_sort_remarks
            OutboundListSortLatency, OutboundListSortRealLatency -> R.string.sing_box_proxies_option_sort_delay
            OutboundListSortType -> R.string.outbound_sort_type
            else -> R.string.outbound_sort_original
        },
    )
    val menuSpatialMotion = AsteriskMotion.fastSpatial<IntOffset>()
    val menuSizeMotion = AsteriskMotion.fastSpatial<IntSize>()
    val menuEffectsMotion = AsteriskMotion.fastEffects<Float>()
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, stringResource(R.string.outbound_options))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = dismissMenu,
            modifier = Modifier.width(OutboundOptionsMenuWidth),
        ) {
            AnimatedContent(
                targetState = level,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = AsteriskMotion.horizontalSlideFade(
                    spatialSpec = menuSpatialMotion,
                    effectsSpec = menuEffectsMotion,
                    sizeSpec = menuSizeMotion,
                ) {
                    if (targetState == OutboundOptionsMenuLevel.MAIN) -1 else 1
                },
                contentAlignment = Alignment.TopStart,
                label = "outbound-options-level",
            ) { currentLevel ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (currentLevel) {
                        OutboundOptionsMenuLevel.MAIN -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_ping_group)) },
                                leadingIcon = {
                                    if (pingRunning) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Speed, contentDescription = null)
                                    }
                                },
                                enabled = !pingRunning,
                                onClick = {
                                    dismissMenu()
                                    onPing()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.outbound_option_layout))
                                        Text(
                                            layoutLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ViewModule, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.LAYOUT },
                            )
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(R.string.outbound_sort))
                                        Text(
                                            sortLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
                                },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.SORT },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_copy_all_urls)) },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                enabled = toolsEnabled,
                                onClick = {
                                    dismissMenu()
                                    onCopyAllUrls()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_delete_proxy_servers)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                trailingIcon = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                                enabled = toolsEnabled,
                                onClick = { level = OutboundOptionsMenuLevel.DELETE },
                            )
                        }

                        OutboundOptionsMenuLevel.DELETE -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_delete_proxy_servers)) },
                                leadingIcon = { Icon(Icons.Rounded.ChevronLeft, contentDescription = null) },
                                onClick = { level = OutboundOptionsMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            OutboundBatchDeleteAction.entries.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(action.titleResource)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = when (action) {
                                                OutboundBatchDeleteAction.DUPLICATES -> Icons.Rounded.ContentCopy
                                                OutboundBatchDeleteAction.INVALID -> Icons.Rounded.ErrorOutline
                                                OutboundBatchDeleteAction.ALL -> Icons.Rounded.Delete
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    enabled = toolsEnabled,
                                    onClick = {
                                        dismissMenu()
                                        onDeleteOutbounds(action)
                                    },
                                )
                            }
                        }

                        OutboundOptionsMenuLevel.LAYOUT -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_option_layout)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            listOf(
                                Triple(
                                    OutboundListLayoutAuto,
                                    R.string.outbound_option_layout_auto,
                                    Icons.Rounded.AutoAwesome,
                                ),
                                Triple(
                                    OutboundListLayoutSingle,
                                    R.string.outbound_option_layout_single,
                                    Icons.Rounded.ViewAgenda,
                                ),
                                Triple(
                                    OutboundListLayoutDouble,
                                    R.string.outbound_option_layout_double,
                                    Icons.Rounded.ViewColumn,
                                ),
                                Triple(
                                    OutboundListLayoutMultiple,
                                    R.string.outbound_option_layout_multiple,
                                    Icons.Rounded.GridView,
                                ),
                            ).forEach { (value, label, _) ->
                                AsteriskDropdownMenuItem(
                                    text = stringResource(label),
                                    selected = layout == value,
                                    onClick = {
                                        dismissMenu()
                                        onLayoutChange(value)
                                    },
                                )
                            }
                        }

                        OutboundOptionsMenuLevel.SORT -> {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.outbound_sort)) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                },
                                onClick = { level = OutboundOptionsMenuLevel.MAIN },
                            )
                            HorizontalDivider()
                            listOf(
                                Triple(
                                    OutboundListSortDefault,
                                    R.string.outbound_sort_original,
                                    Icons.AutoMirrored.Rounded.Sort,
                                ),
                                Triple(
                                    OutboundListSortLatency,
                                    R.string.sing_box_proxies_option_sort_delay,
                                    Icons.Rounded.Speed,
                                ),
                                Triple(
                                    OutboundListSortName,
                                    R.string.outbound_sort_remarks,
                                    Icons.Rounded.SortByAlpha,
                                ),
                                Triple(
                                    OutboundListSortType,
                                    R.string.outbound_sort_type,
                                    Icons.Rounded.Tune,
                                ),
                            ).forEach { (value, label, _) ->
                                val isSelected = sort == value || (value == OutboundListSortLatency && sort == OutboundListSortRealLatency)
                                AsteriskDropdownMenuItem(
                                    text = stringResource(label),
                                    selected = isSelected,
                                    onClick = {
                                        dismissMenu()
                                        onSortChange(value)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OutboundMenuItem(
    text: String,
    icon: ImageVector?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = {
            if (icon == null) {
                Spacer(Modifier.size(24.dp))
            } else {
                Icon(icon, contentDescription = null)
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

private val OutboundImportMenuWidth = 224.dp
private val OutboundOptionsMenuWidth = 224.dp
private val OutboundCardMenuWidth = 224.dp
private val OutboundGridSpacing = OutboundGridSpacingDp.dp
private val OutboundCardHeight = OutboundCardHeightDp.dp
private val OutboundCardPadding =
    PaddingValues(start = 10.dp, top = 14.dp, end = 10.dp, bottom = 10.dp)
private val OutboundCardMenuIconOffset = outboundCardMenuIconOffsetDp(
    touchTargetDp = 48,
    iconSizeDp = 24,
)
private val OutboundEmptyStateMinHeight = 320.dp
private const val OutboundImportManualMenuMaxHeightDp = 500
// Mirrors Material3's 48dp window margin and 8dp content padding on both vertical edges.
private const val OutboundImportMenuVerticalChromeDp = 2 * (48 + 8)

internal fun outboundImportManualMenuHeightDp(windowHeightDp: Int): Int =
    minOf(
        OutboundImportManualMenuMaxHeightDp,
        (windowHeightDp - OutboundImportMenuVerticalChromeDp).coerceAtLeast(0),
    )

@Composable
private fun OutboundGroupState.displayName(): String = name

private fun Context.readOutboundImportFile(uri: Uri): String {
    val content = contentResolver.openInputStream(uri)?.use { input ->
        input.readImportUtf8WithinLimit()
    } ?: error("Unable to open outbound file")
    require(content.isNotBlank()) { "Outbound file is empty" }
    return content
}

/** Keep the embedded manager present while a nested menu or drag owns interaction. */
@Composable
private fun TrackOutboundInteraction(active: Boolean, onCountChange: (Int) -> Unit) {
    val callback by rememberUpdatedState(onCountChange)
    DisposableEffect(active) {
        if (active) callback(1)
        onDispose { if (active) callback(-1) }
    }
}

@Composable
private fun GlobalOutboundStatusCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    AsteriskExpressiveCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (isRunning) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (isRunning) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (isRunning) "已连接" else "未连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun GlobalSelectorSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    activeTarget: String,
    selectors: List<SingBoxSelectorState>,
    groups: List<OutboundGroupState>,
    outbounds: List<OutboundState>,
    onSelectTarget: (String) -> Unit,
    onAddSelector: () -> Unit,
) {
    AsteriskModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "出站策略选择",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = onAddSelector,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.selector_add),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            // 直连 Direct
            SelectorChoiceItem(
                title = "直连 (Direct)",
                subtitle = "绕过代理直接访问互联网",
                icon = Icons.Rounded.Route,
                selected = activeTarget == ManagedDirectOutboundTag,
                onClick = { onSelectTarget(ManagedDirectOutboundTag) },
            )

            // 自定义策略组
            if (selectors.isNotEmpty()) {
                Text(
                    text = "自定义策略组",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                selectors.forEach { selector ->
                    val isUrlTest = selector.type == SingBoxSelectorTypeUrlTest
                    SelectorChoiceItem(
                        title = selector.remarks.ifBlank { selector.tag },
                        subtitle = if (isUrlTest) "自动优选 (${selector.outbounds.size} 个节点)" else "手动策略组 (${selector.outbounds.size} 个节点)",
                        icon = if (isUrlTest) Icons.Rounded.Speed else Icons.Rounded.Tune,
                        selected = activeTarget == selector.tag,
                        onClick = { onSelectTarget(selector.tag) },
                    )
                }
            }

            // 分组策略组
            if (groups.isNotEmpty()) {
                Text(
                    text = "分组策略",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                groups.forEach { group ->
                    val tag = managedOutboundGroupSelectorTag(group.id, group.name)
                    SelectorChoiceItem(
                        title = group.name,
                        subtitle = "该分组节点集合策略",
                        icon = Icons.Rounded.Folder,
                        selected = activeTarget == tag,
                        onClick = { onSelectTarget(tag) },
                    )
                }
            }

            // 单节点直连
            if (outbounds.isNotEmpty()) {
                val activeNode = outbounds.firstOrNull { it.tag == activeTarget }
                Text(
                    text = stringResource(R.string.selector_section_single),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.selector_section_single_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (activeNode != null) {
                    SelectorChoiceItem(
                        title = activeNode.remarks.ifBlank { activeNode.tag },
                        subtitle = "当前正在以单节点极简模式直连 (${activeNode.type.displaySingBoxProtocolName()})",
                        icon = Icons.Rounded.Dns,
                        selected = true,
                        onClick = { onSelectTarget(activeNode.tag) },
                    )
                }

                var showNodePicker by rememberSaveable { mutableStateOf(false) }
                AsteriskExpressiveCard(
                    onClick = { showNodePicker = !showNodePicker },
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = if (showNodePicker) "收起节点列表" else "选择节点切换为单节点直连 (${outbounds.size} 个可用)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Icon(
                            imageVector = if (showNodePicker) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = showNodePicker) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val outboundsByGroup = remember(outbounds) { outbounds.groupBy { it.groupId } }
                        groups.forEach { group ->
                            val groupNodes = outboundsByGroup[group.id].orEmpty()
                            if (groupNodes.isNotEmpty()) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                                )
                                groupNodes.forEach { node ->
                                    val isCurrent = activeTarget == node.tag
                                    SelectorChoiceItem(
                                        title = node.remarks.ifBlank { node.tag },
                                        subtitle = "${node.type.displaySingBoxProtocolName()} · 单节点极简启动",
                                        icon = Icons.Rounded.Dns,
                                        selected = isCurrent,
                                        onClick = { onSelectTarget(node.tag) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SelectorChoiceItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    AsteriskExpressiveCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
        }
    }
}
