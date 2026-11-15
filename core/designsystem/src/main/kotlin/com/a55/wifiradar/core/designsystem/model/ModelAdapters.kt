package com.a55.wifiradar.core.designsystem.model

import com.a55.wifiradar.core.designsystem.component.ReadinessBlockerKind
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistUiModel
import com.a55.wifiradar.core.designsystem.component.ReadinessStepStatus
import com.a55.wifiradar.core.designsystem.component.ReadinessStepUi
import com.a55.wifiradar.core.designsystem.component.ScanPillMode
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ReadinessStep
import com.a55.wifiradar.core.model.ReadinessStepState
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.VelocityTrend
import com.a55.wifiradar.core.model.WifiBand

fun SignalQualityTier.toSemanticTone(): SemanticTone = when (this) {
    SignalQualityTier.EXCELLENT -> SemanticTone.EXCELLENT
    SignalQualityTier.GOOD -> SemanticTone.GOOD
    SignalQualityTier.FAIR -> SemanticTone.FAIR
    SignalQualityTier.WEAK -> SemanticTone.WEAK
    SignalQualityTier.DEAD_ZONE -> SemanticTone.WEAK
}

fun WifiBand.toSemanticTone(): SemanticTone = when (this) {
    WifiBand.BAND_2_4_GHZ -> SemanticTone.GOOD
    WifiBand.BAND_5_GHZ -> SemanticTone.EXCELLENT
    WifiBand.BAND_6_GHZ -> SemanticTone.SPECTRUM_6GHZ
    WifiBand.BAND_60_GHZ -> SemanticTone.INFO
    WifiBand.UNKNOWN -> SemanticTone.NEUTRAL
}

fun SecurityGrade.toSemanticTone(): SemanticTone = when (this) {
    SecurityGrade.A_PLUS, SecurityGrade.A, SecurityGrade.A_MINUS -> SemanticTone.EXCELLENT
    SecurityGrade.B -> SemanticTone.GOOD
    SecurityGrade.C, SecurityGrade.D -> SemanticTone.FAIR
    SecurityGrade.F -> SemanticTone.WEAK
}

fun VelocityTrend.toSemanticTone(): SemanticTone = when (this) {
    VelocityTrend.APPROACHING -> SemanticTone.EXCELLENT
    VelocityTrend.STEADY -> SemanticTone.GOOD
    VelocityTrend.MOVING_AWAY -> SemanticTone.FAIR
    VelocityTrend.CALIBRATING -> SemanticTone.NEUTRAL
}

fun InsightSeverity.toSemanticTone(): SemanticTone = when (this) {
    InsightSeverity.CRITICAL -> SemanticTone.WEAK
    InsightSeverity.WARNING -> SemanticTone.FAIR
    InsightSeverity.SUGGESTION -> SemanticTone.INFO
    InsightSeverity.GOOD_INFO -> SemanticTone.EXCELLENT
}

fun LegendTopic.toSemanticTone(): SemanticTone = when (this) {
    LegendTopic.DBM -> SemanticTone.GOOD
    LegendTopic.SIGNAL_QUALITY -> SemanticTone.EXCELLENT
    LegendTopic.CCI_VS_ACI -> SemanticTone.FAIR
    LegendTopic.DFS_TDWR -> SemanticTone.FAIR
    LegendTopic.PSC_6GHZ -> SemanticTone.SPECTRUM_6GHZ
    LegendTopic.PMF_80211W -> SemanticTone.EXCELLENT
    LegendTopic.OS_SCAN_THROTTLE -> SemanticTone.FAIR
    LegendTopic.KALMAN_SMOOTHING -> SemanticTone.GOOD
    LegendTopic.BSS_LOAD -> SemanticTone.INFO
    LegendTopic.FAST_ROAMING_KVR -> SemanticTone.EXCELLENT
    LegendTopic.MLO_WIFI7 -> SemanticTone.SPECTRUM_6GHZ
    LegendTopic.LAA_RANDOM_MAC -> SemanticTone.INFO
    LegendTopic.PATH_LOSS_DISTANCE -> SemanticTone.GOOD
    LegendTopic.BSS_COLOR -> SemanticTone.INFO
}

fun ReadinessStepState.toUiStatus(): ReadinessStepStatus = when (this) {
    ReadinessStepState.SATISFIED -> ReadinessStepStatus.COMPLETED
    ReadinessStepState.WARNING_SCAN_STILL_ACTIVE -> ReadinessStepStatus.ACTION_REQUIRED
    ReadinessStepState.ACTION_REQUIRED -> ReadinessStepStatus.ACTION_REQUIRED
}

fun ReadinessStep.toUiStep(stepId: String): ReadinessStepUi = ReadinessStepUi(
    id = stepId,
    stepNumber = stepNumber,
    title = title,
    detail = plainEnglishDetail,
    status = state.toUiStatus(),
)

fun ScannerReadinessState.toUiModel(): ReadinessChecklistUiModel? = when (this) {
    is ScannerReadinessState.Ready -> null
    is ScannerReadinessState.MissingPermission -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.MISSING_PERMISSION,
        isNonBlockingInfo = false,
    )
    is ScannerReadinessState.ApproximateOnlyNeedsPrecise -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.APPROXIMATE_ONLY_NEEDS_PRECISE,
        isNonBlockingInfo = false,
    )
    is ScannerReadinessState.PermanentDenial -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.PERMANENT_PERMISSION_DENIAL,
        isNonBlockingInfo = false,
    )
    is ScannerReadinessState.LocationDisabled -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.LOCATION_DISABLED,
        isNonBlockingInfo = false,
    )
    is ScannerReadinessState.WifiAndScanDisabled -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.WIFI_AND_SCAN_DISABLED,
        isNonBlockingInfo = false,
    )
    is ScannerReadinessState.WifiDisabledScanAlwaysOn -> ReadinessChecklistUiModel(
        headline = headline,
        privacyNote = plainEnglishExplanation,
        steps = listOf(
            checklist.permissionStep.toUiStep("permissions"),
            checklist.locationStep.toUiStep("location"),
            checklist.wifiStep.toUiStep("wifi"),
        ),
        primaryCtaLabel = primaryActionLabel,
        blockerKind = ReadinessBlockerKind.WIFI_DISABLED_SCAN_ALWAYS_ON,
        isNonBlockingInfo = true,
    )
}

fun resolveScanPillMode(
    telemetry: ScanTelemetry,
    readinessState: ScannerReadinessState,
): ScanPillMode = when {
    !readinessState.canScanNow -> ScanPillMode.PAUSED_BLOCKED
    readinessState is ScannerReadinessState.WifiDisabledScanAlwaysOn -> ScanPillMode.WIFI_OFF_SCANNING
    telemetry.isScanThrottleEnabled && (telemetry.isCurrentlyThrottled || telemetry.availableTokens == 0) ->
        ScanPillMode.THROTTLED_PASSIVE
    else -> ScanPillMode.LIVE
}
