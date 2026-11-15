package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class CliInformationElement(
    val id: Int,
    val idExt: Int = 0,
    val hex: String = "",
)

@Immutable
@Serializable
data class CliScanEntry(
    val ssid: String? = null,
    val rawSsidHex: String? = null,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val centerFreq0Mhz: Int = 0,
    val centerFreq1Mhz: Int = 0,
    val channelWidthMhz: Int = 20,
    val wifiStandard: String = "11ax",
    val capabilities: String = "",
    val securityTypes: List<Int> = emptyList(),
    val timestampMicros: Long? = null,
    val is80211mcResponder: Boolean = false,
    val is80211azResponder: Boolean = false,
    val apMldMacAddress: String? = null,
    val ies: List<CliInformationElement> = emptyList(),
)

@Immutable
@Serializable
data class CliScanFrame(
    val elapsedMs: Long,
    val connectedBssid: String? = null,
    val connectedTxMbps: Int = 866,
    val connectedRxMbps: Int = 866,
    val rttDistanceMm: Int? = null,
    val rttStdDevMm: Int? = null,
    val scans: List<CliScanEntry> = emptyList(),
)

@Immutable
@Serializable
data class CliScenarioInput(
    val deviceModel: String = "SM-A556B",
    val deviceHas6Ghz: Boolean = false,
    val bssidNicknames: Map<String, String> = emptyMap(),
    val oneMeterCalibrationsDbm: Map<String, Double> = emptyMap(),
    val frames: List<CliScanFrame> = emptyList(),
)

@Immutable
@Serializable
data class DiagnosticExportDocument(
    val schemaVersion: String = "1.0",
    val generatedAtEpochMs: Long,
    val redactedMacs: Boolean,
    val deviceCapabilities: DeviceRadioCapabilities,
    val healthReport: EnvironmentHealthReport,
    val spectrum24Ghz: SpectrumAnalysis,
    val spectrum5Ghz: SpectrumAnalysis,
    val spectrum6Ghz: SpectrumAnalysis,
    val connectedWifi: ConnectedWifiObservation? = null,
    val detectedRadios: List<BssidRadio>,
    val groupedNetworks: List<EssNetworkGroup>,
    val physicalClusters: List<PhysicalApCluster>,
    val roamEvents: List<RoamEvent> = emptyList(),
    val proximityTrajectory: List<ProximityTelemetry> = emptyList(),
)
