package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class SpectralCurve(
    val bssid: String,
    val ssid: String,
    val displayLabel: String,
    val band: WifiBand,
    val primaryChannel: Int,
    val primaryFreqMhz: Int,
    val centerFreq0Mhz: Int,
    val centerFreq1Mhz: Int = 0,
    val width: ChannelWidth,
    val rssiDbm: Int,
    val linearPowerMw: Double,
    val lobeStartFreqMhz: Int,
    val lobeEndFreqMhz: Int,
    val secondaryLobeStartFreqMhz: Int? = null,
    val secondaryLobeEndFreqMhz: Int? = null,
    val puncturedSubchannelBitmap: Int? = null,
    val isConnected: Boolean = false,
    val isOwnMeshSibling: Boolean = false,
    val utilizationPct: Int? = null,
    val bssColor: Int? = null,
)

@Serializable
enum class InterferenceType(val title: String, val plainEnglishExplanation: String) {
    CO_CHANNEL_CCI(
        title = "Shared-Channel Traffic (Co-Channel / CCI)",
        plainEnglishExplanation = "Polite turn-taking on the exact same primary channel — slows peak speed when busy.",
    ),
    ADJACENT_CHANNEL_ACI(
        title = "Overlapping-Channel Noise (Adjacent-Channel / ACI)",
        plainEnglishExplanation = "Sideband RF collision from off-center channels — causes uncoordinated packet retries and lag.",
    ),
}

@Serializable
enum class InterfererRelationship(val displayLabel: String) {
    NEIGHBOR_NETWORK("Neighbor Interference"),
    OWN_MESH_SELF_CONTENTION("Mesh Self-Contention"),
}

@Immutable
@Serializable
data class ChannelInterferer(
    val bssid: String,
    val ssid: String,
    val displayLabel: String,
    val rssiDbm: Int,
    val primaryChannel: Int,
    val width: ChannelWidth,
    val overlapRatio0To1: Double,
    val penaltyMultiplier: Double,
    val weightedInterferenceMw: Double,
    val interferenceType: InterferenceType,
    val relationship: InterfererRelationship,
    val isActionable: Boolean,
)

@Immutable
@Serializable
data class CciAciBreakdown(
    val targetChannel: Int,
    val targetBand: WifiBand,
    val targetBssid: String? = null,
    val cciInterferers: List<ChannelInterferer> = emptyList(),
    val aciInterferers: List<ChannelInterferer> = emptyList(),
    val actionableCciCount: Int = 0,
    val actionableAciCount: Int = 0,
    val totalCciPowerMw: Double = 0.0,
    val totalAciPowerMw: Double = 0.0,
    val cciPlainEnglishSummary: String,
    val aciPlainEnglishSummary: String,
    val hasSevereAciWarning: Boolean = false,
)

@Immutable
@Serializable
data class ChannelScore(
    val band: WifiBand,
    val channel: Int,
    val centerFreqMhz: Int,
    val evaluatedWidth: ChannelWidth,
    val score0To100: Int,
    val starRating0To5: Double,
    val equivalentNoiseFloorDbm: Double,
    val cciCount: Int,
    val aciCount: Int,
    val isCanonical: Boolean,
    val isDfs: Boolean = false,
    val isTdwr: Boolean = false,
    val isPsc: Boolean = false,
    val uniiSubBand: UniiSubBand = UniiSubBand.UNKNOWN,
    val plainEnglishRating: String,
)

@Immutable
@Serializable
data class ChannelRecommendation(
    val band: WifiBand,
    val recommendedPrimaryChannel: Int,
    val recommendedCenterFreqMhz: Int,
    val recommendedWidth: ChannelWidth,
    val score0To100: Int,
    val starRating0To5: Double,
    val currentConnectedChannel: Int? = null,
    val currentConnectedScore0To100: Int? = null,
    val estimatedScoreGain: Int? = null,
    val isCurrentChannelAlreadyOptimal: Boolean = false,
    val plainEnglishReason: String,
)

@Immutable
@Serializable
data class SpectrumAnalysis(
    val band: WifiBand,
    val includeDfs: Boolean = false,
    val excludeConnectedSsid: Boolean = false,
    val minFreqMhz: Int,
    val maxFreqMhz: Int,
    val curves: List<SpectralCurve> = emptyList(),
    val aggregatedWeakCurveCount: Int = 0,
    val channelScores: List<ChannelScore> = emptyList(),
    val recommendation: ChannelRecommendation? = null,
    val selectedOrConnectedBreakdown: CciAciBreakdown? = null,
    val perChannelBreakdowns: Map<Int, CciAciBreakdown> = emptyMap(),
    val colocated6GhzNeighborsFromRnr: List<Colocated6GhzNeighbor> = emptyList(),
    val isBandSupportedByHardware: Boolean = true,
    val hardwareExplanationMessage: String? = null,
)

@Immutable
@Serializable
data class HealthSubScores(
    val signalCoverage0To100: Int,
    val channelCrowding0To100: Int,
    val interferencePurity0To100: Int,
    val securityHygiene0To100: Int,
)

@Serializable
enum class InsightSeverity(val displayLabel: String) {
    CRITICAL("Action Needed"),
    WARNING("Attention"),
    SUGGESTION("Optimization Tip"),
    GOOD_INFO("Healthy"),
}

@Serializable
enum class InsightCategory(val displayLabel: String) {
    SECURITY("Security Hygiene"),
    INTERFERENCE_ACI_CCI("Interference Purity"),
    CHANNEL_OPTIMIZATION("Channel Planning"),
    SIGNAL_COVERAGE("Signal Coverage"),
    ROAMING_MESH("Mesh & Roaming"),
    HARDWARE_CAPABILITY("Device Radio Info"),
}

@Immutable
@Serializable
sealed interface InsightActionTarget {
    @Immutable
    @Serializable
    @SerialName("open_network_detail")
    data class OpenNetworkDetail(val bssid: String) : InsightActionTarget

    @Immutable
    @Serializable
    @SerialName("launch_proximity")
    data class LaunchProximity(val targetKey: ProximityTargetKey) : InsightActionTarget

    @Immutable
    @Serializable
    @SerialName("open_spectrum_band")
    data class OpenSpectrumBand(
        val band: WifiBand,
        val highlightChannel: Int? = null,
    ) : InsightActionTarget

    @Immutable
    @Serializable
    @SerialName("no_action")
    data object NoAction : InsightActionTarget
}

@Immutable
@Serializable
data class ActionableInsight(
    val id: String,
    val severity: InsightSeverity,
    val category: InsightCategory,
    val title: String,
    val plainEnglishBody: String,
    val metricBadge: String? = null,
    val actionLabel: String? = null,
    val actionTarget: InsightActionTarget = InsightActionTarget.NoAction,
)

@Immutable
@Serializable
data class EvilTwinAlert(
    val ssid: String,
    val legitimateBssids: List<String>,
    val suspectOpenBssids: List<String>,
    val legitimateProtocol: SecurityProtocol,
    val suspectRssiDbm: Int,
    val plainEnglishWarning: String,
)

@Serializable
enum class EnvironmentHealthState {
    WAITING_FOR_DATA,
    SCORED,
}

@Immutable
@Serializable
data class EnvironmentHealthReport(
    val state: EnvironmentHealthState = EnvironmentHealthState.WAITING_FOR_DATA,
    val overallScore0To100: Int? = null,
    val headlineLabel: String = "Waiting for scan data",
    val summarySentence: String = "Listening for nearby wireless networks...",
    val subScores: HealthSubScores? = null,
    val insights: List<ActionableInsight> = emptyList(),
    val evilTwinAlerts: List<EvilTwinAlert> = emptyList(),
    val securityAuditRadios: List<BssidRadio> = emptyList(),
    val best24GhzRecommendation: ChannelRecommendation? = null,
    val best5GhzRecommendation: ChannelRecommendation? = null,
    val best6GhzRecommendation: ChannelRecommendation? = null,
    val computedAtTimestampMs: Long = 0L,
)

@Serializable
enum class ExportFormat(
    val displayLabel: String,
    val mimeType: String,
    val fileExtension: String,
) {
    JSON(displayLabel = "JSON Diagnostic", mimeType = "application/json", fileExtension = "json"),
    MARKDOWN(displayLabel = "Markdown Summary", mimeType = "text/markdown", fileExtension = "md"),
}

@Immutable
@Serializable
data class ExportPayload(
    val format: ExportFormat,
    val fileName: String,
    val mimeType: String,
    val content: String,
    val redactedMacs: Boolean,
    val generatedAtEpochMs: Long,
    val networkCount: Int,
    val sizeBytes: Int,
)
