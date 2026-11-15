package com.a55.wifiradar.feature.proximity

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.a55.wifiradar.core.designsystem.component.InlineLegendBar
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiRadarRepository
import com.a55.wifiradar.feature.proximity.component.PROXIMITY_LEGEND_TOPICS
import com.a55.wifiradar.feature.proximity.component.ProximityBurstAndGeigerCard
import com.a55.wifiradar.feature.proximity.component.ProximityPhysicsAndVelocityCard
import com.a55.wifiradar.feature.proximity.component.ProximitySignalLostBanner
import com.a55.wifiradar.feature.proximity.component.ProximitySonarGaugeCard
import com.a55.wifiradar.feature.proximity.component.ProximitySparklineCard
import com.a55.wifiradar.feature.proximity.component.ProximityTargetHeaderCard
import com.a55.wifiradar.feature.proximity.component.ProximityTargetPickerSheetOverlay

@Composable
fun ProximityRoute(
    repository: WifiRadarRepository,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    externalTargetKey: ProximityTargetKey? = null,
    onExternalTargetConsumed: (() -> Unit)? = null,
    viewModel: ProximityViewModel = viewModel(
        factory = ProximityViewModel.factory(
            repository = repository,
            initialTargetKey = externalTargetKey,
        )
    ),
    onOverrideReadinessAction: ((ReadinessAction) -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Recreation-safe external target handoff backed by ViewModel SavedStateHandle (F27, F35)
    LaunchedEffect(externalTargetKey) {
        if (viewModel.applyExternalTargetIfNew(externalTargetKey)) {
            onExternalTargetConsumed?.invoke()
        }
    }

    // Quadruple-layered Geiger lifecycle driver (F22, F29, F33)
    ProximityGeigerLifecycleEffect(
        readinessCanScan = uiState.readinessState.canScanNow,
        telemetry = uiState.telemetry,
        feedbackSettings = uiState.feedbackSettings,
        onUpdateSignal = viewModel::updateGeigerSignal,
        onStopAll = viewModel::stopAllGeigerFeedback,
        onResumeRefreshReadiness = viewModel::onScreenResumed,
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantMap ->
        ProximitySystemActions.handlePermissionGrantResult(
            context = context,
            grantResults = grantMap,
            priorReadiness = uiState.readinessState,
            onReportDenial = viewModel::reportPermissionDenial,
            onRefreshReadiness = viewModel::refreshReadiness,
            onPermissionGrantedTriggerScan = viewModel::triggerBurstScan,
        )
    }

    val handleReadinessAction: (ReadinessAction) -> Unit = { action ->
        if (onOverrideReadinessAction != null) {
            onOverrideReadinessAction(action)
        } else {
            when (action) {
                ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
                ReadinessAction.UPGRADE_TO_PRECISE_LOCATION ->
                    permissionLauncher.launch(ProximitySystemActions.requiredWifiScanPermissions())
                else ->
                    ProximitySystemActions.launchReadinessSettingsAction(context, action)
            }
        }
    }

    ProximityScreen(
        uiState = uiState,
        onSelectTarget = viewModel::selectTarget,
        onOpenTargetPicker = viewModel::openTargetPicker,
        onDismissTargetPicker = viewModel::dismissTargetPicker,
        onSelectLockMode = viewModel::setLockMode,
        onSelectEnvironment = viewModel::setEnvironment,
        onCalibrateOneMeter = viewModel::calibrateOneMeter,
        onClearCalibration = viewModel::clearOneMeterCalibration,
        onResetStats = viewModel::resetStats,
        onTriggerBurstScan = viewModel::triggerBurstScan,
        onToggleHaptics = viewModel::setHapticsEnabled,
        onToggleAudio = viewModel::setAudioEnabled,
        onReadinessAction = handleReadinessAction,
        modifier = modifier,
        contentPadding = contentPadding,
    )
}

@Composable
internal fun ProximityGeigerLifecycleEffect(
    readinessCanScan: Boolean,
    telemetry: ProximityTelemetry?,
    feedbackSettings: GeigerFeedbackSettings,
    onUpdateSignal: (smoothedRssiDbm: Double?, isSignalLost: Boolean) -> Unit,
    onStopAll: () -> Unit,
    onResumeRefreshReadiness: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isStarted = true
                Lifecycle.Event.ON_RESUME -> {
                    isStarted = true
                    onResumeRefreshReadiness()
                }
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> {
                    isStarted = false
                    onStopAll()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopAll()
        }
    }

    val isSignalUnavailable = !readinessCanScan ||
        telemetry == null ||
        telemetry.lockStatus == SignalLockStatus.SIGNAL_LOST ||
        telemetry.lockStatus == SignalLockStatus.SEARCHING ||
        telemetry.smoothedRssiDbm == null

    val smoothedDbm = if (isSignalUnavailable) null else telemetry.smoothedRssiDbm
    val anyFeedbackEnabled = feedbackSettings.hapticsEnabled || feedbackSettings.audioEnabled

    LaunchedEffect(isStarted, readinessCanScan, anyFeedbackEnabled, smoothedDbm, isSignalUnavailable) {
        when {
            !isStarted || !readinessCanScan -> onStopAll()
            isSignalUnavailable -> {
                onUpdateSignal(null, true)
                onStopAll()
            }
            anyFeedbackEnabled -> onUpdateSignal(smoothedDbm, false)
            else -> onStopAll()
        }
    }
}

@Composable
fun ProximityScreen(
    uiState: ProximityUiState,
    onSelectTarget: (ProximityTargetKey) -> Unit,
    onOpenTargetPicker: () -> Unit,
    onDismissTargetPicker: () -> Unit,
    onSelectLockMode: (ProximityLockMode) -> Unit,
    onSelectEnvironment: (PropagationEnvironment) -> Unit,
    onCalibrateOneMeter: () -> Unit,
    onClearCalibration: () -> Unit,
    onResetStats: () -> Unit,
    onTriggerBurstScan: () -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleAudio: (Boolean) -> Unit,
    onReadinessAction: (ReadinessAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(
                    horizontal = dimens.screenHorizontalPadding,
                    vertical = dimens.cardVerticalSpacing,
                )
                .verticalScroll(rememberScrollState())
                .testTag(ProximityTestTags.SCREEN_ROOT),
            verticalArrangement = Arrangement.spacedBy(dimens.cardVerticalSpacing),
        ) {
            if (!uiState.readinessState.canScanNow) {
                ReadinessChecklistCard(
                    readinessState = uiState.readinessState,
                    onAction = onReadinessAction,
                )
                RadarCard(accentTone = SemanticTone.INFO) {
                    Text(
                        text = "Tap Any Proximity Term for Plain-English Meaning",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InlineLegendBar(topics = PROXIMITY_LEGEND_TOPICS)
                }
            } else {
                // Non-blocking WifiDisabledScanAlwaysOn banner ABOVE live radar (F19)
                if (uiState.readinessState is ScannerReadinessState.WifiDisabledScanAlwaysOn) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ProximityTestTags.WIFI_OFF_SCANNING_BANNER),
                    ) {
                        ReadinessChecklistCard(
                            readinessState = uiState.readinessState,
                            onAction = onReadinessAction,
                        )
                    }
                }

                if (uiState.activeTargetKey == null) {
                    CalmEmptyProximityTargetCard(
                        onTriggerBurstScan = onTriggerBurstScan,
                        onOpenTargetPicker = onOpenTargetPicker,
                        hasAvailableRadios = uiState.availableRadios.isNotEmpty(),
                    )
                    RadarCard(accentTone = SemanticTone.INFO) {
                        InlineLegendBar(topics = PROXIMITY_LEGEND_TOPICS)
                    }
                } else {
                    val telemetry = uiState.telemetry

                    ProximityTargetHeaderCard(
                        uiState = uiState,
                        onOpenTargetPicker = onOpenTargetPicker,
                        onSelectLockMode = onSelectLockMode,
                    )

                    if (telemetry?.band == WifiBand.BAND_6_GHZ && !uiState.radioCapabilities.has6Ghz) {
                        ProximitySixGhzHardwareBanner(capabilities = uiState.radioCapabilities)
                    }

                    if (telemetry?.lockStatus == SignalLockStatus.SIGNAL_LOST) {
                        ProximitySignalLostBanner(telemetry = telemetry)
                    }

                    ProximitySonarGaugeCard(telemetry = telemetry)

                    ProximityPhysicsAndVelocityCard(
                        uiState = uiState,
                        onSelectEnvironment = onSelectEnvironment,
                        onCalibrateOneMeter = onCalibrateOneMeter,
                        onClearCalibration = onClearCalibration,
                    )

                    ProximitySparklineCard(
                        telemetry = telemetry,
                        onResetStats = onResetStats,
                    )

                    ProximityBurstAndGeigerCard(
                        uiState = uiState,
                        onTriggerBurstScan = onTriggerBurstScan,
                        onToggleHaptics = onToggleHaptics,
                        onToggleAudio = onToggleAudio,
                    )
                }
            }
        }

        // In-tree AMOLED Target Picker Bottom Sheet Overlay (F27)
        if (uiState.isTargetPickerOpen) {
            ProximityTargetPickerSheetOverlay(
                availableMeshGroups = uiState.availableMeshGroups,
                availableRadios = uiState.availableRadios,
                activeTargetKey = uiState.activeTargetKey,
                resolvedBssid = uiState.telemetry?.resolvedBssid,
                onSelectTarget = onSelectTarget,
                onDismiss = onDismissTargetPicker,
                topClearancePadding = contentPadding.calculateTopPadding(),
                bottomClearancePadding = contentPadding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun CalmEmptyProximityTargetCard(
    onTriggerBurstScan: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    hasAvailableRadios: Boolean,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    RadarElevatedCard(
        modifier = Modifier.testTag(ProximityTestTags.EMPTY_TARGET_CARD),
        accentTone = SemanticTone.GOOD,
    ) {
        Text(
            text = "Ready to Home In on Any Router",
            style = RadarTheme.typography.metricLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "No nearby routers have been locked yet. Trigger an active wireless sweep or pick a detected network to activate the 120Hz proximity sonar gauge.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = if (hasAvailableRadios) onOpenTargetPicker else onTriggerBurstScan,
            modifier = Modifier
                .heightIn(min = dimens.minTouchTarget)
                .testTag(ProximityTestTags.EMPTY_SCAN_CTA),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.cyan,
                contentColor = colors.onAccentDark,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = if (hasAvailableRadios) RadarIcons.Networks else RadarIcons.Refresh,
                contentDescription = null,
                tint = colors.onAccentDark,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (hasAvailableRadios) "Choose Target Router" else "Scan for Nearby Routers",
                style = RadarTheme.typography.metricMedium,
                color = colors.onAccentDark,
            )
        }
    }
}

@Composable
private fun ProximitySixGhzHardwareBanner(
    capabilities: DeviceRadioCapabilities,
) {
    val colors = RadarTheme.colors
    RadarElevatedCard(
        modifier = Modifier.testTag(ProximityTestTags.SIX_GHZ_HARDWARE_BANNER),
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
                text = "6 GHz Target on Dual-Band Hardware (${capabilities.deviceModel})",
                style = RadarTheme.typography.metricMedium,
                color = colors.violet,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Galaxy A55 5G features Dual-Band 2.4/5 GHz Wi-Fi 6 hardware. Lock onto this router's 2.4 GHz or 5 GHz BSSID for direct real-time proximity homing.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
    }
}
