package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ClusterMatchReason(val plainEnglishLabel: String) {
    MLD_MAC("Wi-Fi 7 Multi-Link Hardware Address"),
    RNR_COLOCATED("Co-located 6 GHz Neighbor Report"),
    BSSID_PREFIX_AND_PROXIMITY("Matching Router MAC Prefix & Signal"),
    WPS_IDENTITY("Matching WPS Hardware Identity"),
    SINGLE_RADIO("Single Radio"),
}

@Immutable
@Serializable
data class BssidRadio(
    val bssid: String,
    val ssid: DecodedSsid,
    val customNickname: String? = null,
    val effectiveDisplayName: String,
    val rssiDbm: Int,
    val smoothedRssiDbm: Double,
    val signalQualityPct: Int,
    val signalTier: SignalQualityTier,
    val estimatedSnrDb: Double,
    val effectiveNoiseFloorDbm: Double = -95.0,
    val channelDescriptor: ChannelDescriptor,
    val wifiGeneration: WifiGeneration,
    val wifiStandardRawCode: Int = 0,
    val vendorInfo: VendorInfo,
    val security: SecurityAssessment,
    val beaconDetails: BeaconIeDetails,
    val physicalClusterId: String,
    val apMldMacAddress: String? = null,
    val mloLinks: List<MloLinkInfo> = emptyList(),
    val is80211mcResponder: Boolean = false,
    val is80211azResponder: Boolean = false,
    val isConnected: Boolean = false,
    val connectedLinkInfo: ConnectedWifiObservation? = null,
    val oneMeterCalibrationDbm: Double? = null,
    val estimatedDistanceMeters: Double,
    val channelCongestionScore0To100: Int = 100,
    val lastSeenTimestampMicros: Long = 0L,
    val lastSeenElapsedMs: Long = 0L,
    val ageMs: Long = 0L,
    val isMissedInLatestSweep: Boolean = false,
    val isStale: Boolean = false,
)

@Immutable
@Serializable
data class PhysicalApCluster(
    val clusterId: String,
    val primaryLabel: String,
    val vendorInfo: VendorInfo,
    val wpsDeviceInfo: WpsDeviceInfo? = null,
    val apMldMacAddress: String? = null,
    val matchReason: ClusterMatchReason,
    val radios: List<BssidRadio>,
    val colocated6GhzNeighbors: List<Colocated6GhzNeighbor> = emptyList(),
    val strongestRssiDbm: Int,
    val bands: Set<WifiBand>,
    val containsConnectedRadio: Boolean = false,
)

@Immutable
@Serializable
data class EssNetworkGroup(
    val groupKey: String,
    val ssid: DecodedSsid,
    val displayName: String,
    val isMeshOrMultiAp: Boolean,
    val physicalClusterCount: Int,
    val radios: List<BssidRadio>,
    val physicalClusterIds: List<String>,
    val strongestRadio: BssidRadio,
    val bands: Set<WifiBand>,
    val maxWifiGeneration: WifiGeneration,
    val worstSecurityAssessment: SecurityAssessment,
    val bestSecurityAssessment: SecurityAssessment,
    val hasEvilTwinWarning: Boolean = false,
    val containsConnectedRadio: Boolean = false,
)

@Immutable
@Serializable
data class RoamEvent(
    val timestampElapsedMs: Long,
    val epochTimeMs: Long = 0L,
    val ssid: String,
    val previousBssid: String,
    val newBssid: String,
    val previousBand: WifiBand,
    val newBand: WifiBand,
    val previousChannel: Int,
    val newChannel: Int,
    val previousRssiDbm: Int,
    val newRssiDbm: Int,
    val rssiDeltaDb: Int,
    val isBandSwitch: Boolean,
)

@Immutable
@Serializable
data class RssiSample(
    val timestampElapsedMs: Long,
    val rssiDbm: Int?,
    val smoothedRssiDbm: Double? = null,
    val isConnectedSample: Boolean = false,
)

@Immutable
@Serializable
data class RadarSnapshot(
    val timestampElapsedMs: Long = 0L,
    val timestampEpochMs: Long = 0L,
    val radios: List<BssidRadio> = emptyList(),
    val groupedNetworks: List<EssNetworkGroup> = emptyList(),
    val physicalClusters: List<PhysicalApCluster> = emptyList(),
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
    val connectedWifi: ConnectedWifiObservation? = null,
    val radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    val roamEvents: List<RoamEvent> = emptyList(),
    val rssiTimelines: Map<String, List<RssiSample>> = emptyMap(),
)
