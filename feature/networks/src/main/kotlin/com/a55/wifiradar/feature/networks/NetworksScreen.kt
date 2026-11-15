package com.a55.wifiradar.feature.networks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a55.wifiradar.core.designsystem.component.BandPill
import com.a55.wifiradar.core.designsystem.component.InlineLegendBar
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistCard
import com.a55.wifiradar.core.designsystem.component.SecurityGradeBadge
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiRadarRepository

private val DISCOVERY_LEGEND_TOPICS = listOf(
    LegendTopic.DBM,
    LegendTopic.SIGNAL_QUALITY,
    LegendTopic.OS_SCAN_THROTTLE,
    LegendTopic.LAA_RANDOM_MAC,
    LegendTopic.BSS_LOAD,
    LegendTopic.PMF_80211W,
)

@Composable
fun NetworksRoute(
    repository: WifiRadarRepository,
    onOpenProximity: (ProximityTargetKey) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: NetworksViewModel = viewModel(factory = NetworksViewModel.factory(repository)),
    onOverrideReadinessAction: ((ReadinessAction) -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onScreenResumed()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantMap ->
        NetworksSystemActions.handlePermissionGrantResult(
            context = context,
            grantResults = grantMap,
            priorReadiness = uiState.readinessState,
            onReportDenial = viewModel::reportPermissionDenial,
            onRefreshReadiness = viewModel::refreshReadiness,
            onPermissionGrantedTriggerScan = viewModel::requestManualScan,
        )
    }

    val handleReadinessAction: (ReadinessAction) -> Unit = { action ->
        if (onOverrideReadinessAction != null) {
            onOverrideReadinessAction(action)
        } else {
            when (action) {
                ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
                ReadinessAction.UPGRADE_TO_PRECISE_LOCATION ->
                    permissionLauncher.launch(NetworksSystemActions.requiredWifiScanPermissions())
                else ->
                    NetworksSystemActions.launchReadinessSettingsAction(context, action)
            }
        }
    }

    NetworksScreen(
        uiState = uiState,
        onSearchQueryChange = viewModel::setSearchQuery,
        onSelectViewMode = viewModel::setViewMode,
        onToggleFilterChip = viewModel::toggleFilterChip,
        onSelectSortOption = viewModel::setSortOption,
        onResetFilters = viewModel::resetFilters,
        onToggleGroupExpanded = viewModel::toggleGroupExpanded,
        onRequestManualScan = viewModel::requestManualScan,
        onReadinessAction = handleReadinessAction,
        onOpenDetail = viewModel::openDetailSheet,
        onDismissDetail = viewModel::dismissDetailSheet,
        onSaveNickname = viewModel::saveBssidNickname,
        onOpenProximity = onOpenProximity,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
fun NetworksScreen(
    uiState: NetworksUiState,
    onSearchQueryChange: (String) -> Unit,
    onSelectViewMode: (NetworkViewMode) -> Unit,
    onToggleFilterChip: (NetworkFilterChip) -> Unit,
    onSelectSortOption: (NetworkSortOption) -> Unit,
    onResetFilters: () -> Unit,
    onToggleGroupExpanded: (String) -> Unit,
    onRequestManualScan: () -> Unit,
    onReadinessAction: (ReadinessAction) -> Unit,
    onOpenDetail: (String) -> Unit,
    onDismissDetail: () -> Unit,
    onSaveNickname: (String, String?) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    var isSortExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .testTag(NetworksTestTags.SCREEN_ROOT),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .testTag(NetworksTestTags.LIST),
            contentPadding = PaddingValues(
                horizontal = dimens.screenHorizontalPadding,
                vertical = dimens.cardVerticalSpacing,
            ),
            verticalArrangement = Arrangement.spacedBy(dimens.cardVerticalSpacing),
        ) {
            if (!uiState.readinessState.canScanNow) {
                item(key = "blocking_readiness_card") {
                    ReadinessChecklistCard(
                        readinessState = uiState.readinessState,
                        onAction = onReadinessAction,
                    )
                }
                item(key = "blocking_legend_bar") {
                    RadarCard(accentTone = SemanticTone.INFO) {
                        Text(
                            text = "Tap Any Wireless Term for Plain-English Meaning",
                            style = RadarTheme.typography.badgeLabel,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineLegendBar(topics = DISCOVERY_LEGEND_TOPICS)
                    }
                }
            } else {
                // Non-blocking WifiDisabledScanAlwaysOn banner ABOVE live scan results (F19)
                if (uiState.readinessState is ScannerReadinessState.WifiDisabledScanAlwaysOn) {
                    item(key = "wifi_off_scanning_banner") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(NetworksTestTags.WIFI_OFF_SCANNING_BANNER),
                        ) {
                            ReadinessChecklistCard(
                                readinessState = uiState.readinessState,
                                onAction = onReadinessAction,
                            )
                        }
                    }
                }

                // Discovery Summary + Manual Scan Bar (NO duplicate RadarTestTags.SCAN_STATUS_PILL!)
                item(key = "discovery_summary_bar") {
                    DiscoverySummaryAndScanBar(
                        uiState = uiState,
                        onRequestManualScan = onRequestManualScan,
                    )
                }

                // Connected-AP Emerald Spotlight (F21, F24)
                val connected = uiState.connectedWifi
                if (connected != null && !MacAddressUtils.isAndroidRedactedMac(connected.bssid)) {
                    item(key = "connected_spotlight_card") {
                        ConnectedSpotlightCard(
                            connectedWifi = connected,
                            connectedRadio = uiState.connectedRadio,
                            onOpenDetail = onOpenDetail,
                            onOpenProximity = onOpenProximity,
                        )
                    }
                }

                // Search, View Mode, Filter Chips, Sort & InlineLegendBar (F04, F24, F25)
                item(key = "discovery_controls_card") {
                    DiscoveryControlsCard(
                        uiState = uiState,
                        isSortExpanded = isSortExpanded,
                        onToggleSortExpanded = { isSortExpanded = !isSortExpanded },
                        onSearchQueryChange = onSearchQueryChange,
                        onSelectViewMode = onSelectViewMode,
                        onToggleFilterChip = onToggleFilterChip,
                        onSelectSortOption = { option ->
                            onSelectSortOption(option)
                            isSortExpanded = false
                        },
                    )
                }

                // Galaxy A55 5G 6 GHz Hardware & RNR Banner (F18, F10)
                if (uiState.showSixGhzHardwareBanner) {
                    item(key = "six_ghz_hardware_banner") {
                        SixGhzHardwareAndRnrBanner(
                            capabilities = uiState.radioCapabilities,
                            rnrItems = uiState.rnr6GhzNeighbors,
                            onOpenReportingRadioDetail = onOpenDetail,
                        )
                    }
                }

                // Empty Filter State vs Empty Scan State vs Live Network Cards
                if (uiState.visibleRadioCount == 0) {
                    if (uiState.hasNonDefaultSearchOrFilter) {
                        item(key = "empty_filter_state") {
                            EmptyFilterStateCard(onResetFilters = onResetFilters)
                        }
                    } else if (!uiState.showSixGhzHardwareBanner) {
                        item(key = "empty_listening_state") {
                            CalmListeningEmptyCard(onRequestManualScan = onRequestManualScan)
                        }
                    }
                } else if (uiState.viewMode == NetworkViewMode.GROUPED_MESH) {
                    items(
                        items = uiState.groupedItems,
                        key = { group -> "group:${group.groupKey}" },
                    ) { group ->
                        if (group.radios.size > 1 && !group.ssid.isEffectivelyHidden()) {
                            EssNetworkGroupCard(
                                group = group,
                                isExpanded = group.groupKey in uiState.expandedGroupKeys,
                                onToggleExpanded = { onToggleGroupExpanded(group.groupKey) },
                                onOpenDetail = onOpenDetail,
                                onOpenProximity = onOpenProximity,
                            )
                        } else {
                            val singleRadio = group.strongestRadio
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(NetworksTestTags.groupCard(group.groupKey)),
                            ) {
                                BssidRadioCard(
                                    radio = singleRadio,
                                    onOpenDetail = onOpenDetail,
                                    onOpenProximity = onOpenProximity,
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = uiState.flatRadioItems,
                        key = { radio -> "radio:${MacAddressUtils.normalizeBssid(radio.bssid)}" },
                    ) { radio ->
                        BssidRadioCard(
                            radio = radio,
                            onOpenDetail = onOpenDetail,
                            onOpenProximity = onOpenProximity,
                        )
                    }
                }

                // Inline Plain-English Wireless Glossary & Legends (F24)
                item(key = "discovery_inline_legends_card") {
                    RadarCard(accentTone = SemanticTone.INFO) {
                        Text(
                            text = "Wireless Metrics & Security Glossary",
                            style = RadarTheme.typography.badgeLabel,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        InlineLegendBar(topics = DISCOVERY_LEGEND_TOPICS)
                    }
                }
            }
        }

        // In-tree AMOLED Modal Bottom Sheet Overlay (F23, F26)
        val detailSheet = uiState.detailSheet
        if (detailSheet != null) {
            NetworkDetailBottomSheetOverlay(
                detailSheet = detailSheet,
                onDismiss = onDismissDetail,
                onSaveNickname = onSaveNickname,
                onOpenProximity = onOpenProximity,
                topClearancePadding = contentPadding.calculateTopPadding(),
                bottomClearancePadding = contentPadding.calculateBottomPadding(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverySummaryAndScanBar(
    uiState: NetworksUiState,
    onRequestManualScan: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val summaryHeadline = if (uiState.totalUnfilteredRadioCount != uiState.visibleRadioCount) {
        "Showing ${uiState.visibleRadioCount} of ${uiState.totalUnfilteredRadioCount} radios"
    } else {
        val pulses = "${uiState.scanTelemetry.availableTokens}/${uiState.scanTelemetry.maxTokens} pulses"
        "${uiState.visibleRadioCount} radios · $pulses"
    }
    val dotColor = when {
        uiState.readinessState is ScannerReadinessState.WifiDisabledScanAlwaysOn -> colors.cyan
        uiState.scanTelemetry.isCurrentlyThrottled || uiState.scanTelemetry.availableTokens == 0 -> colors.amber
        else -> colors.emerald
    }

    RadarCard(accentTone = SemanticTone.GOOD) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .testTag(NetworksTestTags.DISCOVERY_STATUS_SUMMARY),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = summaryHeadline,
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }

            OutlinedButton(
                onClick = onRequestManualScan,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .align(Alignment.CenterVertically)
                    .testTag(NetworksTestTags.MANUAL_SCAN_BUTTON),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.cyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.toneSpec(SemanticTone.GOOD).container,
                    contentColor = colors.cyan,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Refresh,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Scan Now",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.cyan,
                )
            }
        }

        val feedback = uiState.lastScanFeedbackMessage
        if (!feedback.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = feedback,
                style = RadarTheme.typography.explanationBody,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedSpotlightCard(
    connectedWifi: ConnectedWifiObservation,
    connectedRadio: BssidRadio?,
    onOpenDetail: (String) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val emeraldSpec = colors.toneSpec(SemanticTone.EXCELLENT)
    val normBssid = MacAddressUtils.normalizeBssid(connectedWifi.bssid)
    val cleanConnectedSsid = connectedWifi.ssid
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf {
            it.isNotEmpty() &&
                it != MacAddressUtils.UNKNOWN_SSID_SENTINEL &&
                it != DecodedSsid.HIDDEN_NETWORK_LABEL
        }
    val displayTitle = connectedRadio?.let(NetworksProjector::radioDisplayTitle)
        ?: cleanConnectedSsid
        ?: "Connected Wi-Fi Router"

    val ssidHint = connectedRadio?.ssid?.nonHiddenSsidOrNull() ?: cleanConnectedSsid

    RadarElevatedCard(
        modifier = Modifier.testTag(NetworksTestTags.CONNECTED_SPOTLIGHT_CARD),
        accentTone = SemanticTone.EXCELLENT,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = emeraldSpec.container,
                border = BorderStroke(1.dp, emeraldSpec.border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = RadarIcons.CheckCircle,
                        contentDescription = null,
                        tint = emeraldSpec.foreground,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Connected · 300ms Live Link",
                        style = RadarTheme.typography.badgeLabel,
                        color = emeraldSpec.foreground,
                    )
                }
            }
            SignalBadge(rssiDbm = connectedWifi.rssiDbm, prefixLabel = "Live")
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = displayTitle,
            style = RadarTheme.typography.metricLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))

        val linkMeta = buildList {
            add(normBssid)
            if (connectedWifi.txLinkSpeedMbps > 0 || connectedWifi.rxLinkSpeedMbps > 0) {
                add("Rx ${connectedWifi.rxLinkSpeedMbps} / Tx ${connectedWifi.txLinkSpeedMbps} Mbps")
            }
            connectedWifi.ipAddressV4?.takeIf { it.isNotBlank() }?.let { add("IPv4 $it") }
        }.joinToString(" · ")
        Text(
            text = linkMeta,
            style = RadarTheme.typography.monoAddress,
            color = colors.textSecondary,
        )

        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connectedRadio != null) {
                BandPill(
                    descriptor = connectedRadio.channelDescriptor,
                    congestionScore0To100 = connectedRadio.channelCongestionScore0To100,
                )
            } else if (connectedWifi.frequencyMhz > 0) {
                BandPill(
                    frequencyMhz = connectedWifi.frequencyMhz,
                    channel = 0,
                    widthMhz = 20,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (connectedRadio != null) {
                OutlinedButton(
                    onClick = { onOpenDetail(normBssid) },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(NetworksTestTags.CONNECTED_DETAILS_BUTTON),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surfaceL1,
                        contentColor = colors.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Details",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }
            }

            Button(
                onClick = {
                    onOpenProximity(
                        ProximityTargetKey.BssidLock(
                            bssid = normBssid,
                            ssidHint = ssidHint,
                        )
                    )
                },
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.CONNECTED_PROXIMITY_BUTTON),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.emerald,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Proximity,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Proximity",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.onAccentDark,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryControlsCard(
    uiState: NetworksUiState,
    isSortExpanded: Boolean,
    onToggleSortExpanded: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectViewMode: (NetworkViewMode) -> Unit,
    onToggleFilterChip: (NetworkFilterChip) -> Unit,
    onSelectSortOption: (NetworkSortOption) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    RadarCard {
        // Instant Search Field
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(NetworksTestTags.SEARCH_INPUT),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = RadarIcons.Search,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(18.dp),
                )
            },
            trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                {
                    Surface(
                        onClick = { onSearchQueryChange("") },
                        modifier = Modifier
                            .size(dimens.minTouchTarget)
                            .semantics {
                                role = Role.Button
                                contentDescription = "Clear search"
                            }
                            .testTag(NetworksTestTags.SEARCH_CLEAR_BUTTON),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceL2,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = RadarIcons.Close,
                                contentDescription = null,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            } else {
                null
            },
            placeholder = {
                Text(
                    text = "Search SSID, BSSID, vendor, channel, WPA3...",
                    style = RadarTheme.typography.explanationBody,
                    color = colors.textMuted,
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedContainerColor = colors.background,
                unfocusedContainerColor = colors.background,
                focusedBorderColor = colors.cyan,
                unfocusedBorderColor = colors.activeBorder,
                cursorColor = colors.cyan,
            ),
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Grouped Networks vs All Radios (BSSID) segmented FlowRow
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkViewMode.entries.forEach { mode ->
                val selected = uiState.viewMode == mode
                val spec = colors.toneSpec(SemanticTone.GOOD)
                Surface(
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = selected,
                            onClick = { onSelectViewMode(mode) },
                            role = Role.Tab,
                        )
                        .testTag(NetworksTestTags.viewModeTab(mode)),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) spec.container else colors.surfaceL2,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) spec.foreground else colors.border,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode.displayLabel,
                            style = RadarTheme.typography.badgeLabel,
                            color = if (selected) spec.foreground else colors.textSecondary,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // All 7 Filter Chips in a wrapping FlowRow (48.dp touch target)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkFilterChip.entries.forEach { chip ->
                val selected = chip in uiState.activeFilters
                val chipTone = when (chip) {
                    NetworkFilterChip.BAND_6_GHZ -> SemanticTone.SPECTRUM_6GHZ
                    NetworkFilterChip.OPEN_OR_WEAK_SECURITY -> SemanticTone.FAIR
                    NetworkFilterChip.STRONG_SIGNAL -> SemanticTone.EXCELLENT
                    else -> SemanticTone.GOOD
                }
                val spec = colors.toneSpec(chipTone)
                Surface(
                    onClick = { onToggleFilterChip(chip) },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .semantics {
                            role = Role.Checkbox
                            stateDescription = if (selected) "Selected" else "Not selected"
                        }
                        .testTag(NetworksTestTags.filterChip(chip)),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) spec.container else colors.surfaceL2,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) spec.foreground else colors.border,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = chip.displayLabel,
                            style = RadarTheme.typography.badgeLabel,
                            color = if (selected) spec.foreground else colors.textSecondary,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Inline Expandable Sort Selector (avoids off-root Popup window quirks)
        OutlinedButton(
            onClick = onToggleSortExpanded,
            modifier = Modifier
                .heightIn(min = dimens.minTouchTarget)
                .testTag(NetworksTestTags.SORT_SELECTOR_BUTTON),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.activeBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.surfaceL2,
                contentColor = colors.textPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Sort: ${uiState.sortOption.displayLabel}",
                style = RadarTheme.typography.badgeLabel,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = if (isSortExpanded) RadarIcons.ChevronUp else RadarIcons.ChevronDown,
                contentDescription = null,
                tint = colors.cyan,
                modifier = Modifier.size(16.dp),
            )
        }

        if (isSortExpanded) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NetworkSortOption.entries.forEach { option ->
                    val selected = uiState.sortOption == option
                    val spec = colors.toneSpec(SemanticTone.GOOD)
                    Surface(
                        modifier = Modifier
                            .heightIn(min = dimens.minTouchTarget)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = selected,
                                onClick = { onSelectSortOption(option) },
                                role = Role.RadioButton,
                            )
                            .testTag(NetworksTestTags.sortOptionChip(option)),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) spec.container else colors.surfaceL2,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) spec.foreground else colors.border,
                        ),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = option.displayLabel,
                                style = RadarTheme.typography.badgeLabel,
                                color = if (selected) spec.foreground else colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SixGhzHardwareAndRnrBanner(
    capabilities: DeviceRadioCapabilities,
    rnrItems: List<Colocated6GhzRnrItem>,
    onOpenReportingRadioDetail: (String) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val violetSpec = colors.toneSpec(SemanticTone.SPECTRUM_6GHZ)

    RadarElevatedCard(
        modifier = Modifier.testTag(NetworksTestTags.SIX_GHZ_HARDWARE_BANNER),
        accentTone = SemanticTone.SPECTRUM_6GHZ,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RadarIcons.Info,
                contentDescription = null,
                tint = colors.violet,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Dual-Band Wi-Fi 6 Hardware (${capabilities.deviceModel})",
                style = RadarTheme.typography.metricMedium,
                color = colors.violet,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Samsung Galaxy A55 5G features Dual-Band 2.4 GHz & 5 GHz Wi-Fi 6 (802.11ax) hardware and does not contain a direct 6 GHz radio receiver. However, nearby Wi-Fi 6E/7 routers still advertise their co-located 6 GHz radios inside 2.4/5 GHz Reduced Neighbor Report (IE 201) beacons below.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))
        if (rnrItems.isEmpty()) {
            Text(
                text = "No co-located 6 GHz neighbor reports advertised by nearby 2.4 / 5 GHz routers yet.",
                style = RadarTheme.typography.badgeLabel,
                color = colors.textMuted,
            )
        } else {
            Text(
                text = "Out-of-Band 6 GHz Radios Discovered via RNR (${rnrItems.size})",
                style = RadarTheme.typography.badgeLabel,
                color = colors.violet,
            )
            Spacer(modifier = Modifier.height(8.dp))
            rnrItems.forEach { item ->
                val n = item.neighbor
                Surface(
                    onClick = { onOpenReportingRadioDetail(item.reportingRadioBssid) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.minTouchTarget),
                    shape = RoundedCornerShape(12.dp),
                    color = violetSpec.container,
                    border = BorderStroke(1.dp, violetSpec.border),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "6 GHz · Ch ${n.channelNumber} (${n.centerFreqMhz} MHz · ${n.widthMhz} MHz)",
                                style = RadarTheme.typography.metricMedium,
                                color = violetSpec.foreground,
                            )
                            if (n.isPscChannel) {
                                Text(
                                    text = "PSC Preferred",
                                    style = RadarTheme.typography.badgeLabel,
                                    color = colors.emerald,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Reported by ${item.reportingRadioDisplayName} (${item.reportingRadioRssiDbm} dBm · ${item.reportingRadioBssid})",
                            style = RadarTheme.typography.explanationBody,
                            color = colors.textSecondary,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun EmptyFilterStateCard(
    onResetFilters: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    RadarElevatedCard(
        modifier = Modifier.testTag(NetworksTestTags.EMPTY_FILTER_CARD),
        accentTone = SemanticTone.FAIR,
    ) {
        Text(
            text = "No networks match current filters",
            style = RadarTheme.typography.metricLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Clear your active search text and filter chips in one tap to view all nearby wireless networks again.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onResetFilters,
            modifier = Modifier
                .heightIn(min = dimens.minTouchTarget)
                .testTag(NetworksTestTags.RESET_FILTERS_BUTTON),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.amber,
                contentColor = colors.onAccentDark,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Reset Filters",
                style = RadarTheme.typography.metricMedium,
                color = colors.onAccentDark,
            )
        }
    }
}

@Composable
private fun CalmListeningEmptyCard(
    onRequestManualScan: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    RadarCard(accentTone = SemanticTone.INFO) {
        Text(
            text = "Listening for nearby wireless networks...",
            style = RadarTheme.typography.metricLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Your Galaxy A55 5G radio is ready across 2.4 GHz and 5 GHz bands. Tap Scan Now to trigger an immediate active sweep.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onRequestManualScan,
            modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.cyan),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.surfaceL2,
                contentColor = colors.cyan,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = "Trigger Sweep",
                style = RadarTheme.typography.badgeLabel,
                color = colors.cyan,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EssNetworkGroupCard(
    group: EssNetworkGroup,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val strongest = group.strongestRadio
    val strongestBssid = MacAddressUtils.normalizeBssid(strongest.bssid)
    val bandSummary = group.bands
        .sortedBy { it.ordinal }
        .joinToString(" & ") { it.displayLabel }

    val meshSubtitle = if (group.isMeshOrMultiAp) {
        "${group.radios.size} Mesh Radios (${group.physicalClusterCount} Routers) · $bandSummary"
    } else {
        "${group.radios.size} Dual/Tri-Band Radios · $bandSummary"
    }

    RadarCard(
        modifier = Modifier.testTag(NetworksTestTags.groupCard(group.groupKey)),
        accentTone = if (group.hasEvilTwinWarning) {
            SemanticTone.WEAK
        } else {
            strongest.signalTier.toSemanticTone()
        },
        highlighted = group.containsConnectedRadio,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = group.displayName,
                    style = RadarTheme.typography.metricLarge,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$meshSubtitle · ${strongest.vendorInfo.displayLabel}",
                    style = RadarTheme.typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            if (group.containsConnectedRadio) {
                val emeraldSpec = colors.toneSpec(SemanticTone.EXCELLENT)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = emeraldSpec.container,
                    border = BorderStroke(1.dp, emeraldSpec.border),
                ) {
                    Text(
                        text = "Connected",
                        style = RadarTheme.typography.badgeLabel,
                        color = emeraldSpec.foreground,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        if (group.hasEvilTwinWarning) {
            Spacer(modifier = Modifier.height(8.dp))
            val coralSpec = colors.toneSpec(SemanticTone.WEAK)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = coralSpec.container,
                border = BorderStroke(1.dp, coralSpec.border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = RadarIcons.Warning,
                        contentDescription = null,
                        tint = coralSpec.foreground,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Evil-Twin Warning · Open Clone Detected Nearby",
                        style = RadarTheme.typography.badgeLabel,
                        color = coralSpec.foreground,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignalBadge(radio = strongest, prefixLabel = "Best")
            BandPill(
                descriptor = strongest.channelDescriptor,
                congestionScore0To100 = strongest.channelCongestionScore0To100,
            )
            SecurityGradeBadge(assessment = group.worstSecurityAssessment)
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onToggleExpanded,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.groupExpandButton(group.groupKey)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surfaceL2,
                    contentColor = colors.textPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = if (isExpanded) RadarIcons.ChevronUp else RadarIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isExpanded) "Collapse" else "Expand (${group.radios.size} Radios)",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }

            OutlinedButton(
                onClick = { onOpenDetail(strongestBssid) },
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.groupDetailsButton(group.groupKey)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surfaceL2,
                    contentColor = colors.textPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Details",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }

            Button(
                onClick = {
                    onOpenProximity(
                        ProximityTargetKey.BssidLock(
                            bssid = strongestBssid,
                            ssidHint = group.ssid.nonHiddenSsidOrNull(),
                        )
                    )
                },
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.groupProximityButton(group.groupKey)),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cyan,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Proximity,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Proximity",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.onAccentDark,
                )
            }
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = colors.border)
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.radios.forEach { radio ->
                    ExpandedSubRadioRow(
                        radio = radio,
                        onOpenDetail = onOpenDetail,
                        onOpenProximity = onOpenProximity,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandedSubRadioRow(
    radio: BssidRadio,
    onOpenDetail: (String) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val normBssid = MacAddressUtils.normalizeBssid(radio.bssid)

    Surface(
        onClick = { onOpenDetail(normBssid) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.minTouchTarget)
            .testTag(NetworksTestTags.groupSubRadioRow(normBssid)),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceL2,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column {
                    val rowTitle = buildString {
                        if (!radio.customNickname.isNullOrBlank()) {
                            append("${radio.customNickname} · ")
                        }
                        append(
                            "${radio.channelDescriptor.band.displayLabel} Ch ${radio.channelDescriptor.primaryChannel} (${radio.channelDescriptor.width.displayLabel})"
                        )
                    }
                    Text(
                        text = rowTitle,
                        style = RadarTheme.typography.metricMedium,
                        color = colors.textPrimary,
                    )
                    val laaNote = if (radio.vendorInfo.isLocallyAdministered) " · Virtual MAC" else ""
                    val staleNote = if (radio.isMissedInLatestSweep || radio.isStale) {
                        " · Seen ${SemanticFormatters.formatAge(radio.ageMs)}"
                    } else {
                        ""
                    }
                    Text(
                        text = "$normBssid · ${radio.vendorInfo.displayLabel}$laaNote$staleNote",
                        style = RadarTheme.typography.monoAddress,
                        color = colors.textMuted,
                    )
                }
                SignalBadge(radio = radio)
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onOpenDetail(normBssid) },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(NetworksTestTags.radioDetailsButton(normBssid)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surfaceL1,
                        contentColor = colors.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Details",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }

                Button(
                    onClick = {
                        onOpenProximity(
                            ProximityTargetKey.BssidLock(
                                bssid = normBssid,
                                ssidHint = radio.ssid.nonHiddenSsidOrNull(),
                            )
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(NetworksTestTags.proximityButton(normBssid)),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.cyan,
                        contentColor = colors.onAccentDark,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        imageVector = RadarIcons.Proximity,
                        contentDescription = null,
                        tint = colors.onAccentDark,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Proximity",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.onAccentDark,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BssidRadioCard(
    radio: BssidRadio,
    onOpenDetail: (String) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val normBssid = MacAddressUtils.normalizeBssid(radio.bssid)
    val isHidden = radio.ssid.isEffectivelyHidden()
    val title = NetworksProjector.radioDisplayTitle(radio)

    RadarCard(
        modifier = Modifier.testTag(NetworksTestTags.radioCard(normBssid)),
        accentTone = if (radio.security.isEvilTwinSuspect) {
            SemanticTone.WEAK
        } else {
            radio.signalTier.toSemanticTone()
        },
        highlighted = radio.isConnected,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = title,
                    style = RadarTheme.typography.metricLarge,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                val subtitle = if (isHidden) {
                    "Name not broadcast · Radio $normBssid · ${radio.vendorInfo.displayLabel}"
                } else {
                    val laaPart = if (radio.vendorInfo.isLocallyAdministered) " · Virtual MAC" else ""
                    "$normBssid · ${radio.vendorInfo.displayLabel}$laaPart"
                }
                Text(
                    text = subtitle,
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textSecondary,
                )
                val wpsModel = radio.beaconDetails.wpsDevice?.modelName?.takeIf { it.isNotBlank() }
                if (wpsModel != null) {
                    Text(
                        text = "Model: $wpsModel · ${SemanticFormatters.formatWifiGeneration(radio.wifiGeneration)}",
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textMuted,
                    )
                } else {
                    Text(
                        text = "${SemanticFormatters.formatWifiGeneration(radio.wifiGeneration)} · ${
                            formatEstimatedRadioRange(
                                meters = radio.estimatedDistanceMeters,
                                isCalibrated = radio.oneMeterCalibrationDbm != null,
                            )
                        }",
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textMuted,
                    )
                }
            }

            if (radio.isConnected) {
                val emeraldSpec = colors.toneSpec(SemanticTone.EXCELLENT)
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = emeraldSpec.container,
                    border = BorderStroke(1.dp, emeraldSpec.border),
                ) {
                    Text(
                        text = "Connected",
                        style = RadarTheme.typography.badgeLabel,
                        color = emeraldSpec.foreground,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignalBadge(radio = radio)
            BandPill(
                descriptor = radio.channelDescriptor,
                congestionScore0To100 = radio.channelCongestionScore0To100,
            )
            SecurityGradeBadge(assessment = radio.security)
        }

        if (radio.beaconDetails.bssLoad != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = SemanticFormatters.formatBssLoad(radio.beaconDetails.bssLoad),
                style = RadarTheme.typography.badgeLabel,
                color = colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onOpenDetail(normBssid) },
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.radioDetailsButton(normBssid)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surfaceL2,
                    contentColor = colors.textPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Details",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }

            Button(
                onClick = {
                    onOpenProximity(
                        ProximityTargetKey.BssidLock(
                            bssid = normBssid,
                            ssidHint = radio.ssid.nonHiddenSsidOrNull(),
                        )
                    )
                },
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(NetworksTestTags.proximityButton(normBssid)),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cyan,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Proximity,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Proximity",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.onAccentDark,
                )
            }
        }
    }
}
