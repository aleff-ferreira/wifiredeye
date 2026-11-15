package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class RawInformationElement(
    val id: Int,
    val idExt: Int = 0,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawInformationElement) return false
        return id == other.id &&
            idExt == other.idExt &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + idExt
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RawInformationElement(id=$id, idExt=$idExt, len=${bytes.size})"
}

@Immutable
@Serializable
data class MloLinkInfo(
    val linkId: Int,
    val bssid: String? = null,
    val band: WifiBand = WifiBand.UNKNOWN,
    val channel: Int = -1,
    val frequencyMhz: Int = 0,
    val rssiDbm: Int? = null,
    val txLinkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    val isActive: Boolean = true,
)

@Immutable
@Serializable
data class RawScanEntry(
    val bssid: String,
    val rawSsidBytes: ByteArray? = null,
    val legacySsid: String? = null,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val centerFreq0Mhz: Int = 0,
    val centerFreq1Mhz: Int = 0,
    val channelWidthCode: Int = 0,
    val wifiStandardCode: Int = 0,
    val capabilities: String = "",
    val securityTypes: List<Int> = emptyList(),
    val timestampMicros: Long = 0L,
    val is80211mcResponder: Boolean = false,
    val is80211azResponder: Boolean = false,
    val apMldMacAddress: String? = null,
    val informationElements: List<RawInformationElement> = emptyList(),
    val apMloLinkId: Int = -1,
    val affiliatedMloLinks: List<MloLinkInfo> = emptyList(),
    val venueName: String? = null,
    val operatorFriendlyName: String? = null,
    val isPasspointNetwork: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawScanEntry) return false
        return bssid == other.bssid &&
            rawSsidBytes.contentEquals(other.rawSsidBytes) &&
            legacySsid == other.legacySsid &&
            rssiDbm == other.rssiDbm &&
            frequencyMhz == other.frequencyMhz &&
            centerFreq0Mhz == other.centerFreq0Mhz &&
            centerFreq1Mhz == other.centerFreq1Mhz &&
            channelWidthCode == other.channelWidthCode &&
            wifiStandardCode == other.wifiStandardCode &&
            capabilities == other.capabilities &&
            securityTypes == other.securityTypes &&
            timestampMicros == other.timestampMicros &&
            is80211mcResponder == other.is80211mcResponder &&
            is80211azResponder == other.is80211azResponder &&
            apMldMacAddress == other.apMldMacAddress &&
            informationElements == other.informationElements &&
            apMloLinkId == other.apMloLinkId &&
            affiliatedMloLinks == other.affiliatedMloLinks &&
            venueName == other.venueName &&
            operatorFriendlyName == other.operatorFriendlyName &&
            isPasspointNetwork == other.isPasspointNetwork
    }

    override fun hashCode(): Int {
        var result = bssid.hashCode()
        result = 31 * result + (rawSsidBytes?.contentHashCode() ?: 0)
        result = 31 * result + (legacySsid?.hashCode() ?: 0)
        result = 31 * result + rssiDbm
        result = 31 * result + frequencyMhz
        result = 31 * result + centerFreq0Mhz
        result = 31 * result + centerFreq1Mhz
        result = 31 * result + channelWidthCode
        result = 31 * result + wifiStandardCode
        result = 31 * result + capabilities.hashCode()
        result = 31 * result + securityTypes.hashCode()
        result = 31 * result + timestampMicros.hashCode()
        result = 31 * result + is80211mcResponder.hashCode()
        result = 31 * result + is80211azResponder.hashCode()
        result = 31 * result + (apMldMacAddress?.hashCode() ?: 0)
        result = 31 * result + informationElements.hashCode()
        result = 31 * result + apMloLinkId
        result = 31 * result + affiliatedMloLinks.hashCode()
        result = 31 * result + (venueName?.hashCode() ?: 0)
        result = 31 * result + (operatorFriendlyName?.hashCode() ?: 0)
        result = 31 * result + isPasspointNetwork.hashCode()
        return result
    }
}

@Immutable
@Serializable
data class RawScanBatch(
    val entries: List<RawScanEntry>,
    val receivedElapsedMs: Long,
    val receivedEpochMs: Long = 0L,
    val isFreshHardwareSweep: Boolean = true,
)

@Immutable
@Serializable
data class ScanTelemetry(
    val lastScanTimestampMs: Long? = null,
    val lastScanAgeMs: Long = 0L,
    val totalActiveScansTriggered: Int = 0,
    val totalPassiveScansReceived: Int = 0,
    val isScanThrottleEnabled: Boolean = true,
    val isCurrentlyThrottled: Boolean = false,
    val availableTokens: Int = 4,
    val maxTokens: Int = 4,
    val tokenWindowMs: Long = 120_000L,
    val nextTokenRechargeInMs: Long = 0L,
    val nextScheduledScanInMs: Long = 0L,
    val isBurstModeActive: Boolean = false,
    val lastTriggerRejectedByOs: Boolean = false,
    val isPassiveOnlyRestrictedProfile: Boolean = false,
)

@Immutable
@Serializable
sealed interface ScanTriggerResult {
    @Immutable
    @Serializable
    data class Triggered(
        val remainingTokens: Int,
        val unthrottledMode: Boolean,
    ) : ScanTriggerResult

    @Immutable
    @Serializable
    data class ThrottledByTokenBucket(
        val nextTokenInMs: Long,
    ) : ScanTriggerResult

    @Immutable
    @Serializable
    data class RejectedByOs(
        val nextRetryInMs: Long,
    ) : ScanTriggerResult

    @Immutable
    @Serializable
    data class BlockedByReadiness(
        val readinessState: ScannerReadinessState,
    ) : ScanTriggerResult
}

@Immutable
@Serializable
data class ConnectedWifiObservation(
    val bssid: String,
    val ssid: String? = null,
    val isLocationRedacted: Boolean = false,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    val txLinkSpeedMbps: Int = 0,
    val rxLinkSpeedMbps: Int = 0,
    val maxSupportedTxLinkSpeedMbps: Int? = null,
    val maxSupportedRxLinkSpeedMbps: Int? = null,
    val wifiStandardCode: Int = 0,
    val securityTypeCode: Int? = null,
    val ipAddressV4: String? = null,
    val apMldMacAddress: String? = null,
    val mloLinks: List<MloLinkInfo> = emptyList(),
    val timestampElapsedMs: Long = 0L,
)

@Immutable
@Serializable
data class DeviceRadioCapabilities(
    val deviceModel: String = "SM-A556B",
    val manufacturer: String = "samsung",
    val sdkInt: Int = 35,
    val has24Ghz: Boolean = true,
    val has5Ghz: Boolean = true,
    val has6Ghz: Boolean = false,
    val has60Ghz: Boolean = false,
    val maxSupportedWifiGeneration: WifiGeneration = WifiGeneration.WIFI_6,
    val supportedStandards: Set<WifiGeneration> = setOf(
        WifiGeneration.LEGACY_11B,
        WifiGeneration.LEGACY_11G,
        WifiGeneration.LEGACY_11A,
        WifiGeneration.WIFI_4,
        WifiGeneration.WIFI_5,
        WifiGeneration.WIFI_6,
    ),
    val supportsWifiRtt: Boolean = false,
    val isRttCurrentlyAvailable: Boolean = false,
    val supportsDualBandSimultaneous: Boolean = true,
    val isScanThrottleEnabled: Boolean = true,
)

@Immutable
@Serializable
data class RttMeasurement(
    val bssid: String,
    val distanceMeters: Double,
    val rawDistanceMm: Int,
    val distanceStdDevMeters: Double,
    val rssiDbm: Int,
    val numAttemptedMeasurements: Int = 1,
    val numSuccessfulMeasurements: Int = 1,
    val is80211azNtb: Boolean = false,
    val timestampElapsedMs: Long,
)

@Immutable
@Serializable
data class GeigerFeedbackSettings(
    val hapticsEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
)
