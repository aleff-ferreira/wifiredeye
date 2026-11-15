package com.a55.wifiradar.feature.insights

import androidx.compose.runtime.Immutable
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.InsightCategory
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.WifiBand

@Immutable
data class SubScoreBarUi(
    val key: String,
    val label: String,
    val score0To100: Int?,
    val formattedValue: String,
    val tone: SemanticTone,
)

@Immutable
data class ActionableInsightCardUi(
    val id: String,
    val severity: InsightSeverity,
    val severityLabel: String,
    val category: InsightCategory,
    val categoryLabel: String,
    val tone: SemanticTone,
    val title: String,
    val body: String,
    val metricBadge: String?,
    val actionLabel: String?,
    val actionTarget: InsightActionTarget,
)

@Immutable
data class TimelinePointUi(
    val xFraction0To1: Float,
    val rssiDbm: Float,
    val timestampElapsedMs: Long,
    val connectWithDashedGapFromPrev: Boolean,
)

@Immutable
data class RssiTimelineSeriesUi(
    val bssid: String,
    val formattedBssid: String,
    val displayName: String,
    val bandLabel: String,
    val channel: Int,
    val paletteIndex: Int,
    val isConnected: Boolean,
    val isHighlighted: Boolean,
    val isDimmed: Boolean,
    val points: List<TimelinePointUi>,
    val gapSegmentCount: Int,
    val latestRssiDbm: Int,
    val minRssiDbm: Int,
    val maxRssiDbm: Int,
    val avgRssiDbm: Double,
    val formattedStatsLine: String,
)

@Immutable
data class RoamMarkerUi(
    val xFraction0To1: Float,
    val timestampElapsedMs: Long,
    val label: String,
)

@Immutable
data class RoamEventItemUi(
    val ssid: String,
    val formattedOldBssid: String,
    val formattedNewBssid: String,
    val channelTransitionLabel: String,
    val deltaFormatted: String,
    val deltaTone: SemanticTone,
    val relativeAgeLabel: String,
    val isBandSwitch: Boolean,
    val isWithinSelectedWindow: Boolean,
)

@Immutable
data class EvilTwinAlertCardUi(
    val ssid: String,
    val plainEnglishWarning: String,
    val legitimateProtocolLabel: String,
    val formattedLegitimateBssids: List<String>,
    val formattedSuspectOpenBssids: List<String>,
    val firstRawSuspectBssid: String?,
    val suspectSignalSummary: String,
)

@Immutable
data class SecurityAuditRowUi(
    val rawBssid: String,
    val formattedBssid: String,
    val displayName: String,
    val vendorLabel: String,
    val channelSummary: String,
    val band: WifiBand,
    val rssiDbm: Int,
    val isConnected: Boolean,
    val security: SecurityAssessment,
    val pmfLabel: String,
    val pmfNote: String,
    val pmfTone: SemanticTone,
    val cipherSummary: String?,
    val hasWeakCipher: Boolean,
    val wpsWarningLabel: String?,
    val featureBadges: List<String>,
    val warnings: List<String>,
)

@Immutable
data class ExportPreviewUi(
    val format: ExportFormat,
    val fileName: String,
    val networkCount: Int,
    val formattedSize: String,
    val previewText: String,
    val isPreviewTruncated: Boolean,
    val isStructuredJsonSummary: Boolean = false,
)

@Immutable
data class InsightsUiState(
    val readinessState: ScannerReadinessState = ScannerReadinessState.Ready(),
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
    val detectedNetworkCount: Int = 0,
    val deviceRadioSummary: String = "SM-A556B · 2.4 & 5 GHz Dual-Band Radio · Up to Wi-Fi 6",
    val colocated6GhzHint: String? = null,

    // 1. Health Score
    val isWaitingForScanData: Boolean = true,
    val overallScore0To100: Int? = null,
    val ringShortQualifier: String = "Waiting",
    val healthHeadline: String = "Waiting for scan data",
    val healthSummarySentence: String = "Listening for nearby wireless networks...",
    val healthTone: SemanticTone = SemanticTone.NEUTRAL,
    val subScores: List<SubScoreBarUi> = emptyList(),
    val best24GhzRec: ChannelRecommendation? = null,
    val best5GhzRec: ChannelRecommendation? = null,
    val best6GhzRec: ChannelRecommendation? = null,

    // 2. Actionable Insights
    val actionableInsights: List<ActionableInsightCardUi> = emptyList(),

    // 3. Multi-Network RSSI Time Graph & Roam Log
    val selectedHistoryWindow: RssiHistoryWindow = RssiHistoryWindow.FIVE_MINUTES,
    val highlightedTimelineBssid: String? = null,
    val timelineSeries: List<RssiTimelineSeriesUi> = emptyList(),
    val chartRoamMarkers: List<RoamMarkerUi> = emptyList(),
    val roamEvents: List<RoamEventItemUi> = emptyList(),
    val totalRoamEventCount: Int = 0,
    val showAllRoamEvents: Boolean = false,

    // 4. Security Posture & Evil-Twin Audit
    val evilTwinAlerts: List<EvilTwinAlertCardUi> = emptyList(),
    val securityAuditRows: List<SecurityAuditRowUi> = emptyList(),
    val totalSecurityRadioCount: Int = 0,
    val weakSecurityCount: Int = 0,
    val wpsExposedCount: Int = 0,
    val modernProtectedCount: Int = 0,
    val showAllSecurityRows: Boolean = false,

    // 5. Diagnostic Export & Share
    val selectedExportFormat: ExportFormat = ExportFormat.MARKDOWN,
    val redactMacs: Boolean = false,
    val isExportPreviewExpanded: Boolean = true,
    val exportPreview: ExportPreviewUi? = null,
    val copyConfirmationMessage: String? = null,
    val statusBannerMessage: String? = null,
)
