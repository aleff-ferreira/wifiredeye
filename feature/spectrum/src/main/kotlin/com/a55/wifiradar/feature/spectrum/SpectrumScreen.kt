package com.a55.wifiradar.feature.spectrum

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a55.wifiradar.core.designsystem.component.InlineLegendBar
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistCard
import com.a55.wifiradar.core.designsystem.component.ScanStatusTopPill
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiRadarRepository

private const val ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE =
    "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE"

private val SPECTRUM_LEGEND_TOPICS = listOf(
    LegendTopic.CCI_VS_ACI,
    LegendTopic.DFS_TDWR,
    LegendTopic.PSC_6GHZ,
    LegendTopic.BSS_COLOR,
    LegendTopic.DBM,
)

@Composable
fun SpectrumRoute(
    repository: WifiRadarRepository,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    externalBandRequest: WifiBand? = null,
    externalHighlightChannel: Int? = null,
    externalRequestNonce: Long = 0L,
    onExternalRequestConsumed: (() -> Unit)? = null,
    onOpenProximity: ((ProximityTargetKey) -> Unit)? = null,
    onOpenNetworkDetail: ((String) -> Unit)? = null,
    onReadinessAction: ((ReadinessAction) -> Unit)? = null,
    scrollable: Boolean = true,
    viewModel: SpectrumViewModel = viewModel(
        factory = SpectrumViewModel.provideFactory(repository = repository),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshReadiness()
    }

    LaunchedEffect(externalBandRequest, externalHighlightChannel, externalRequestNonce) {
        if (externalBandRequest != null) {
            viewModel.applyExternalDeepLink(
                band = externalBandRequest,
                highlightChannel = externalHighlightChannel,
                requestNonce = externalRequestNonce,
            )
            onExternalRequestConsumed?.invoke()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantMap ->
        val fineGranted = grantMap[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted) {
            viewModel.refreshReadiness()
        } else {
            val activity = context as? Activity
            val canShowRationale = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            viewModel.reportPermissionDenial(permanentlyDenied = !canShowRationale)
            viewModel.refreshReadiness()
        }
    }

    val effectiveReadinessHandler: (ReadinessAction) -> Unit = { action ->
        if (onReadinessAction != null) {
            onReadinessAction(action)
        } else {
            handleSpectrumReadinessActionFallback(
                context = context,
                action = action,
                launchPermissions = { perms ->
                    runCatching { permissionLauncher.launch(perms) }
                },
            )
        }
    }

    SpectrumScreen(
        uiState = uiState,
        onSelectBand = viewModel::selectBand,
        onToggleIncludeDfs = viewModel::setIncludeDfs,
        onToggleExcludeConnected = viewModel::setExcludeConnectedSsid,
        onSelectBssid = viewModel::selectBssid,
        onSelectChannel = viewModel::selectChannel,
        onRequestManualScan = { viewModel.requestManualScan() },
        onDismissScanFeedback = viewModel::dismissScanFeedback,
        modifier = modifier,
        contentPadding = contentPadding,
        scrollable = scrollable,
        onOpenProximity = onOpenProximity,
        onOpenNetworkDetail = onOpenNetworkDetail,
        onReadinessAction = effectiveReadinessHandler,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumScreen(
    uiState: SpectrumUiState,
    onSelectBand: (WifiBand) -> Unit,
    onToggleIncludeDfs: (Boolean) -> Unit,
    onToggleExcludeConnected: (Boolean) -> Unit,
    onSelectBssid: (String?) -> Unit,
    onSelectChannel: (Int?) -> Unit,
    onRequestManualScan: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissScanFeedback: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    scrollable: Boolean = true,
    onOpenProximity: ((ProximityTargetKey) -> Unit)? = null,
    onOpenNetworkDetail: ((String) -> Unit)? = null,
    onReadinessAction: (ReadinessAction) -> Unit = {},
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val dimens = RadarTheme.dimens
    val scrollModifier = if (scrollable) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .then(scrollModifier)
            .padding(contentPadding)
            .testTag(SpectrumTestTags.SCREEN_ROOT),
        verticalArrangement = Arrangement.spacedBy(dimens.cardVerticalSpacing),
    ) {
        // 1. Top Header & Live Scan Status
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SpectrumTestTags.HEADER_SECTION),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = RadarIcons.Spectrum,
                        contentDescription = null,
                        tint = colors.cyan,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Channel Overlap & Spectrum Analyzer",
                        style = typography.metricLarge,
                        color = colors.textPrimary,
                    )
                }
                ScanStatusTopPill(
                    networkCount = uiState.totalNetworkCount,
                    telemetry = uiState.scanTelemetry,
                    readinessState = uiState.readinessState,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            Text(
                text = "Visualize live channel widths, eliminate sideband collisions, and lock onto the cleanest wireless lane.",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
        }

        // 2. Progressive Self-Healing Readiness Checklist Card (auto-returns when Ready)
        ReadinessChecklistCard(
            readinessState = uiState.readinessState,
            onAction = onReadinessAction,
        )

        // 3. Band Switcher (2.4 / 5 / 6 GHz), Toggles & Manual Scan
        SpectrumControlsCard(
            uiState = uiState,
            onSelectBand = onSelectBand,
            onToggleIncludeDfs = onToggleIncludeDfs,
            onToggleExcludeConnected = onToggleExcludeConnected,
            onRequestManualScan = onRequestManualScan,
            onDismissScanFeedback = onDismissScanFeedback,
        )

        // 4. Galaxy A55 5G 6 GHz Hardware Capability Explanation & RNR Neighbor Discovery
        if (uiState.show6GhzHardwareBanner && uiState.hardwareExplanationBody != null) {
            SixGhzHardwareCapabilityBanner(
                headline = uiState.hardwareExplanationHeadline,
                explanationBody = uiState.hardwareExplanationBody,
            )
        }

        if (uiState.selectedBand == WifiBand.BAND_6_GHZ &&
            (uiState.show6GhzHardwareBanner || uiState.colocated6GhzNeighbors.isNotEmpty())
        ) {
            Colocated6GhzRnrCard(
                neighbors = uiState.colocated6GhzNeighbors,
            )
        }

        // 5. Hardware-Accelerated Spectral Overlap Canvas + Hit-Testing + AP Curve Chips
        SpectrumCanvasCard(
            uiState = uiState,
            onSelectBssid = onSelectBssid,
            onOpenProximity = onOpenProximity,
            onOpenNetworkDetail = onOpenNetworkDetail,
        )

        // 6. Best-Channel Recommendation Card
        if (uiState.recommendation != null) {
            BestChannelRecommendationCard(
                selectedBand = uiState.selectedBand,
                recommendation = uiState.recommendation,
                onSelectRecommendedChannel = { ch -> onSelectChannel(ch) },
            )
        }

        // 7. Accessible Per-Channel Congestion Score & Star Rating List
        ChannelCongestionScoresCard(
            selectedBand = uiState.selectedBand,
            channelScores = uiState.channelScores,
            recommendedChannel = uiState.recommendation?.recommendedPrimaryChannel,
            highlightedChannel = uiState.highlightedChannel,
            onSelectChannel = { ch -> onSelectChannel(ch) },
        )

        // 8. Co-Channel (CCI) vs Adjacent-Channel (ACI) Interference Breakdown Cards
        CciVsAciBreakdownSection(
            targetLabel = uiState.activeBreakdownTargetLabel,
            breakdown = uiState.activeBreakdown,
        )

        // 9. Tap-to-Explain Inline Legend Bar
        RadarCard {
            Text(
                text = "Tap Any RF Term for Plain-English Meaning",
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            InlineLegendBar(
                topics = SPECTRUM_LEGEND_TOPICS,
                modifier = Modifier.testTag(SpectrumTestTags.LEGEND_BAR),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpectrumControlsCard(
    uiState: SpectrumUiState,
    onSelectBand: (WifiBand) -> Unit,
    onToggleIncludeDfs: (Boolean) -> Unit,
    onToggleExcludeConnected: (Boolean) -> Unit,
    onRequestManualScan: () -> Unit,
    onDismissScanFeedback: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val fontScale = LocalDensity.current.fontScale

    RadarCard {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SpectrumTestTags.BAND_SELECTOR_ROW),
        ) {
            val useFlow = fontScale >= 1.35f || maxWidth < 330.dp
            if (useFlow) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SPECTRUM_SELECTABLE_BANDS.forEach { band ->
                        BandSelectorTabPill(
                            band = band,
                            isSelected = band == uiState.selectedBand,
                            countBadge = formatBandCountBadge(band, uiState),
                            onClick = { onSelectBand(band) },
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SPECTRUM_SELECTABLE_BANDS.forEach { band ->
                        BandSelectorTabPill(
                            band = band,
                            isSelected = band == uiState.selectedBand,
                            countBadge = formatBandCountBadge(band, uiState),
                            onClick = { onSelectBand(band) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        AccessibleSwitchRow(
            title = "Include DFS Channels",
            subtitle = "Evaluate radar-shared 5 GHz lanes (Ch 52–144)",
            checked = uiState.includeDfs,
            onCheckedChange = onToggleIncludeDfs,
            testTag = SpectrumTestTags.TOGGLE_INCLUDE_DFS,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val ownSubtitle = if (!uiState.connectedSsid.isNullOrBlank()) {
            "Hide \"${uiState.connectedSsid}\" radios to isolate neighbor interference"
        } else {
            "Hide your connected network's own mesh radios"
        }
        AccessibleSwitchRow(
            title = "Exclude Connected Network",
            subtitle = ownSubtitle,
            checked = uiState.excludeConnectedSsid,
            onCheckedChange = onToggleExcludeConnected,
            testTag = SpectrumTestTags.TOGGLE_EXCLUDE_CONNECTED,
        )

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRequestManualScan,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(SpectrumTestTags.BUTTON_SCAN_NOW),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.cyan),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Refresh,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Scan Now",
                    style = typography.badgeLabel,
                    color = colors.cyan,
                )
            }

            if (uiState.scanActionFeedback != null) {
                Surface(
                    onClick = onDismissScanFeedback,
                    modifier = Modifier
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(SpectrumTestTags.SCAN_FEEDBACK_PILL),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceL2,
                    border = BorderStroke(1.dp, colors.activeBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.scanActionFeedback,
                            style = typography.badgeLabel,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

private fun formatBandCountBadge(
    band: WifiBand,
    uiState: SpectrumUiState,
): String {
    val directCount = uiState.bandNetworkCounts[band] ?: 0
    return if (band == WifiBand.BAND_6_GHZ && !uiState.radioCapabilities.has6Ghz && directCount == 0) {
        if (uiState.colocated6GhzNeighbors.isNotEmpty()) {
            "${uiState.colocated6GhzNeighbors.size} RNR"
        } else {
            "RNR"
        }
    } else {
        "$directCount"
    }
}

@Composable
private fun BandSelectorTabPill(
    band: WifiBand,
    isSelected: Boolean,
    countBadge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val toneSpec = colors.toneSpec(band.toSemanticTone())

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = onClick,
            )
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .testTag(SpectrumTestTags.bandTab(band)),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) toneSpec.container else colors.surfaceL2,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) toneSpec.foreground else colors.border,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = band.displayLabel,
                style = RadarTheme.typography.metricMedium,
                color = if (isSelected) toneSpec.foreground else colors.textPrimary,
            )
            Text(
                text = "${band.traitLabel} · $countBadge",
                style = RadarTheme.typography.badgeLabel,
                color = if (isSelected) toneSpec.foreground else colors.textSecondary,
            )
        }
    }
}

@Composable
private fun AccessibleSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val activeSpec = colors.toneSpec(SemanticTone.GOOD)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = if (checked) activeSpec.container else colors.surfaceL2,
        border = BorderStroke(
            width = 1.dp,
            color = if (checked) activeSpec.foreground else colors.border,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = subtitle,
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccentDark,
                    checkedTrackColor = activeSpec.foreground,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.surfaceL1,
                    uncheckedBorderColor = colors.activeBorder,
                ),
            )
        }
    }
}

private fun handleSpectrumReadinessActionFallback(
    context: Context,
    action: ReadinessAction,
    launchPermissions: (Array<String>) -> Unit,
) {
    when (action) {
        ReadinessAction.NONE -> Unit
        ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
        ReadinessAction.UPGRADE_TO_PRECISE_LOCATION -> {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
            }.toTypedArray()
            launchPermissions(perms)
        }
        ReadinessAction.OPEN_APP_PERMISSION_SETTINGS -> {
            launchSystemIntentSafely(
                context = context,
                intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
        }
        ReadinessAction.OPEN_LOCATION_SETTINGS -> {
            launchSystemIntentSafely(
                context = context,
                intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            )
        }
        ReadinessAction.OPEN_WIFI_SETTINGS_PANEL -> {
            val panelLaunched = launchSystemIntentSafely(
                context = context,
                intent = Intent(Settings.Panel.ACTION_WIFI),
            )
            if (!panelLaunched) {
                launchSystemIntentSafely(
                    context = context,
                    intent = Intent(Settings.ACTION_WIFI_SETTINGS),
                )
            }
        }
        ReadinessAction.OPEN_SCAN_ALWAYS_AVAILABLE -> {
            val scanLaunched = launchSystemIntentSafely(
                context = context,
                intent = Intent(ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE),
            )
            if (!scanLaunched) {
                launchSystemIntentSafely(
                    context = context,
                    intent = Intent(Settings.ACTION_WIFI_SETTINGS),
                )
            }
        }
    }
}

private fun launchSystemIntentSafely(
    context: Context,
    intent: Intent,
): Boolean = runCatching {
    if (context !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
    true
}.getOrDefault(false)
