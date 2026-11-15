package com.a55.wifiradar.feature.insights

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.WifiRadarRepository
import com.a55.wifiradar.feature.insights.component.ActionableInsightsSection
import com.a55.wifiradar.feature.insights.component.DiagnosticExportSectionCard
import com.a55.wifiradar.feature.insights.component.HealthScoreSectionCard
import com.a55.wifiradar.feature.insights.component.RssiTimeGraphSectionCard
import com.a55.wifiradar.feature.insights.component.SecurityAuditSectionCard

private const val ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE =
    "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE"

private val INSIGHTS_LEGEND_TOPICS = listOf(
    LegendTopic.PMF_80211W,
    LegendTopic.FAST_ROAMING_KVR,
    LegendTopic.BSS_LOAD,
    LegendTopic.CCI_VS_ACI,
    LegendTopic.DBM,
    LegendTopic.OS_SCAN_THROTTLE,
)

@Composable
fun InsightsRoute(
    repository: WifiRadarRepository,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    onNavigateToActionTarget: (InsightActionTarget) -> Unit = {},
    onShareExport: ((ExportPayload) -> Unit)? = null,
    onReadinessAction: ((ReadinessAction) -> Unit)? = null,
    scrollable: Boolean = true,
    viewModel: InsightsViewModel = viewModel(
        factory = InsightsViewModel.provideFactory(repository = repository),
    ),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshReadiness()
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
            handleInsightsReadinessActionFallback(
                context = context,
                action = action,
                launchPermissions = { perms ->
                    runCatching { permissionLauncher.launch(perms) }
                },
            )
        }
    }

    val dispatchSharePayload: (ExportFormat) -> Unit = { format ->
        viewModel.selectExportFormat(format)
        val freshPayload = viewModel.buildFreshExportPayload(formatOverride = format)
        if (onShareExport != null) {
            onShareExport(freshPayload)
        } else {
            InsightsExportShareHelper.launchDefaultShareSheet(context, freshPayload)
        }
    }

    InsightsScreen(
        uiState = uiState,
        onSelectHistoryWindow = viewModel::selectHistoryWindow,
        onToggleHighlightTimelineBssid = viewModel::toggleHighlightTimelineBssid,
        onToggleShowAllRoamEvents = viewModel::toggleShowAllRoamEvents,
        onToggleShowAllSecurityRows = viewModel::toggleShowAllSecurityRows,
        onSelectExportFormat = viewModel::selectExportFormat,
        onRedactMacsChanged = viewModel::setRedactMacs,
        onExportPreviewExpandedChanged = viewModel::setExportPreviewExpanded,
        onCopyExportClick = {
            val freshPayload = viewModel.buildFreshExportPayload()
            val copied = InsightsExportShareHelper.copyPayloadToClipboard(context, freshPayload)
            viewModel.onExportCopiedToClipboard(freshPayload, copied)
        },
        onShareMarkdownClick = { dispatchSharePayload(ExportFormat.MARKDOWN) },
        onShareJsonClick = { dispatchSharePayload(ExportFormat.JSON) },
        onNavigateToActionTarget = onNavigateToActionTarget,
        onReadinessAction = effectiveReadinessHandler,
        onRequestManualScan = { viewModel.requestManualScan() },
        onDismissStatusBanner = viewModel::dismissStatusBanner,
        modifier = modifier,
        contentPadding = contentPadding,
        scrollable = scrollable,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightsScreen(
    uiState: InsightsUiState,
    onSelectHistoryWindow: (RssiHistoryWindow) -> Unit,
    onToggleHighlightTimelineBssid: (String) -> Unit,
    onToggleShowAllRoamEvents: () -> Unit,
    onToggleShowAllSecurityRows: () -> Unit,
    onSelectExportFormat: (ExportFormat) -> Unit,
    onRedactMacsChanged: (Boolean) -> Unit,
    onExportPreviewExpandedChanged: (Boolean) -> Unit,
    onCopyExportClick: () -> Unit,
    onShareMarkdownClick: () -> Unit,
    onShareJsonClick: () -> Unit,
    onNavigateToActionTarget: (InsightActionTarget) -> Unit,
    onReadinessAction: (ReadinessAction) -> Unit,
    onRequestManualScan: () -> Unit,
    modifier: Modifier = Modifier,
    onDismissStatusBanner: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    scrollable: Boolean = true,
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
            .testTag(InsightsTestTags.SCREEN_ROOT),
        verticalArrangement = Arrangement.spacedBy(dimens.cardVerticalSpacing),
    ) {
        // Header & Scan Status Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(InsightsTestTags.HEADER_SECTION),
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
                        imageVector = RadarIcons.Insights,
                        contentDescription = null,
                        tint = colors.emerald,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Wireless Health & Security Insights",
                        style = typography.metricLarge,
                        color = colors.textPrimary,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScanStatusTopPill(
                        networkCount = uiState.detectedNetworkCount,
                        telemetry = uiState.scanTelemetry,
                        readinessState = uiState.readinessState,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    OutlinedButton(
                        onClick = onRequestManualScan,
                        modifier = Modifier
                            .heightIn(min = dimens.minTouchTarget)
                            .testTag(InsightsTestTags.HEADER_SCAN_NOW_BUTTON),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.cyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
                            style = typography.badgeLabel,
                            color = colors.cyan,
                        )
                    }
                }
            }

            Text(
                text = "Real-time environment health scoring, multi-network signal history, mesh roam logging, security auditing, and offline diagnostic export.",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )

            if (!uiState.statusBannerMessage.isNullOrBlank()) {
                Surface(
                    onClick = onDismissStatusBanner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(InsightsTestTags.STATUS_BANNER),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.surfaceL2,
                    border = BorderStroke(1.dp, colors.activeBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = uiState.statusBannerMessage,
                            style = typography.badgeLabel,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        // Progressive Self-Healing OS Readiness Checklist Card
        ReadinessChecklistCard(
            readinessState = uiState.readinessState,
            onAction = onReadinessAction,
        )

        // 1. Wireless Environment Health Score (0-100) & 4 Sub-Scores
        HealthScoreSectionCard(
            uiState = uiState,
            onNavigateToActionTarget = onNavigateToActionTarget,
        )

        // 2. Prioritized Actionable Insights
        ActionableInsightsSection(
            insights = uiState.actionableInsights,
            isWaitingForScanData = uiState.isWaitingForScanData,
            onNavigateToActionTarget = onNavigateToActionTarget,
        )

        // 3. Multi-Network Real-Time RSSI Time Graph (1m / 5m / 15m) & Mesh Roam Log
        RssiTimeGraphSectionCard(
            uiState = uiState,
            onSelectHistoryWindow = onSelectHistoryWindow,
            onToggleHighlightTimelineBssid = onToggleHighlightTimelineBssid,
            onToggleShowAllRoamEvents = onToggleShowAllRoamEvents,
        )

        // 4. Security Posture & Evil-Twin Audit
        SecurityAuditSectionCard(
            uiState = uiState,
            onNavigateToActionTarget = onNavigateToActionTarget,
            onToggleShowAllSecurityRows = onToggleShowAllSecurityRows,
        )

        // 5. Diagnostic Snapshot Preview, Clipboard & 1-Tap Share
        DiagnosticExportSectionCard(
            uiState = uiState,
            onSelectExportFormat = onSelectExportFormat,
            onRedactMacsChanged = onRedactMacsChanged,
            onExportPreviewExpandedChanged = onExportPreviewExpandedChanged,
            onCopyExportClick = onCopyExportClick,
            onShareMarkdownClick = onShareMarkdownClick,
            onShareJsonClick = onShareJsonClick,
        )

        // 6. Tap-to-Explain Inline Legend Bar
        RadarCard {
            Text(
                text = "Tap Any Security or Telemetry Term for Plain-English Meaning",
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            InlineLegendBar(
                topics = INSIGHTS_LEGEND_TOPICS,
                modifier = Modifier.testTag(InsightsTestTags.LEGEND_BAR),
            )
        }
    }
}

private fun handleInsightsReadinessActionFallback(
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
