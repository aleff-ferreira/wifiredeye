package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
sealed interface ProximityTargetKey {
    fun toSavedStateString(): String

    @Immutable
    @Serializable
    @SerialName("bssid_lock")
    data class BssidLock(
        val bssid: String,
        val ssidHint: String? = null,
    ) : ProximityTargetKey {
        override fun toSavedStateString(): String =
            "bssid:${MacAddressUtils.normalizeBssid(bssid)}:${ssidHint.orEmpty()}"
    }

    @Immutable
    @Serializable
    @SerialName("follow_mesh_ssid")
    data class FollowMeshSsid(
        val ssid: String,
        val preferredBssid: String? = null,
    ) : ProximityTargetKey {
        override fun toSavedStateString(): String =
            "ssid:${preferredBssid?.let(MacAddressUtils::normalizeBssid).orEmpty()}:$ssid"
    }

    companion object {
        fun fromSavedStateString(encoded: String?): ProximityTargetKey? {
            if (encoded.isNullOrBlank()) return null
            return when {
                encoded.startsWith("bssid:") -> {
                    val rest = encoded.removePrefix("bssid:")
                    if (rest.length < 17) return null
                    val mac = rest.substring(0, 17)
                    if (!MacAddressUtils.isValidMac(mac)) return null
                    val hint = if (rest.length > 18 && rest[17] == ':') rest.substring(18).ifEmpty { null } else null
                    BssidLock(bssid = MacAddressUtils.normalizeBssid(mac), ssidHint = hint)
                }
                encoded.startsWith("ssid:") -> {
                    val rest = encoded.removePrefix("ssid:")
                    val hasPreferred = rest.length >= 18 && rest[17] == ':' && MacAddressUtils.isValidMac(rest.substring(0, 17))
                    if (hasPreferred) {
                        val pref = MacAddressUtils.normalizeBssid(rest.substring(0, 17))
                        val ssid = rest.substring(18)
                        if (ssid.isEmpty()) null else FollowMeshSsid(ssid = ssid, preferredBssid = pref)
                    } else {
                        val ssid = rest.removePrefix(":")
                        if (ssid.isEmpty()) null else FollowMeshSsid(ssid = ssid, preferredBssid = null)
                    }
                }
                MacAddressUtils.isValidMac(encoded) -> BssidLock(MacAddressUtils.normalizeBssid(encoded))
                else -> null
            }
        }
    }
}

@Serializable
enum class PropagationEnvironment(
    val pathLossExponent: Double,
    val displayLabel: String,
    val shortDescription: String,
) {
    OPEN(
        pathLossExponent = 2.0,
        displayLabel = "Open Space",
        shortDescription = "Clear line-of-sight · n = 2.0",
    ),
    HOME_OFFICE(
        pathLossExponent = 2.7,
        displayLabel = "Home / Office",
        shortDescription = "Standard indoor walls · n = 2.7",
    ),
    HEAVY_WALLS(
        pathLossExponent = 3.4,
        displayLabel = "Heavy Concrete",
        shortDescription = "Thick concrete / floors · n = 3.4",
    ),
}

@Serializable
enum class VelocityTrend(val plainEnglishLabel: String, val chevronSymbol: String) {
    APPROACHING("Approaching", "▲"),
    STEADY("Steady · Holding Position", "●"),
    MOVING_AWAY("Moving Away", "▼"),
    CALIBRATING("Calibrating trend...", "⋯"),
}

@Serializable
enum class SignalLockStatus(val displayLabel: String) {
    LOCKED("Live Signal Lock"),
    STALE("Signal Aging · Waiting for Next Sweep"),
    SIGNAL_LOST("Signal Lost"),
    SEARCHING("Searching for Target Router..."),
}

@Serializable
enum class DistanceMethod(val badgeLabel: String, val isHardwareRtt: Boolean) {
    RTT_80211AZ("Wi-Fi RTT (802.11az)", true),
    RTT_80211MC("Wi-Fi RTT (802.11mc)", true),
    USER_CALIBRATED_PATH_LOSS("1m Calibrated Estimate", false),
    PATH_LOSS_ESTIMATE("Signal Path-Loss Estimate", false),
}

@Immutable
@Serializable
data class DistanceEstimate(
    val meters: Double,
    val rangeMinMeters: Double,
    val rangeMaxMeters: Double,
    val stdDevMeters: Double? = null,
    val method: DistanceMethod,
    val referenceRssiAt1mDbm: Double,
    val environment: PropagationEnvironment,
    val clampedNear: Boolean = false,
    val clampedFar: Boolean = false,
    val plainEnglishFormatted: String,
)

@Immutable
@Serializable
data class SparklinePoint(
    val timestampElapsedMs: Long,
    val rawRssiDbm: Int? = null,
    val smoothedRssiDbm: Double? = null,
    val emaRssiDbm: Double? = null,
    val isDropoutGap: Boolean = false,
)

@Immutable
@Serializable
data class ProximityTelemetry(
    val targetKey: ProximityTargetKey,
    val resolvedBssid: String? = null,
    val displayTitle: String,
    val displaySubtitle: String,
    val band: WifiBand = WifiBand.UNKNOWN,
    val channel: Int = -1,
    val frequencyMhz: Int = 0,
    val vendorLabel: String? = null,
    val lockStatus: SignalLockStatus,
    val smoothedRssiDbm: Double? = null,
    val emaRssiDbm: Double? = null,
    val rawRssiDbm: Int? = null,
    val lastKnownRssiDbm: Int? = null,
    val peakHoldRssiDbm: Double? = null,
    val signalQualityPct: Int = 0,
    val signalTier: SignalQualityTier = SignalQualityTier.DEAD_ZONE,
    val kalmanInnovationDb: Double = 0.0,
    val kalmanVarianceDb2: Double = 6.0,
    val distanceEstimate: DistanceEstimate? = null,
    val isUserCalibrated1m: Boolean = false,
    val velocitySlopeDbPerSec: Double = 0.0,
    val velocityTrend: VelocityTrend = VelocityTrend.CALIBRATING,
    val stabilityPct: Int = 100,
    val stdDevDb: Double = 0.0,
    val minRssiDbm: Int? = null,
    val maxRssiDbm: Int? = null,
    val avgRssiDbm: Double? = null,
    val sampleCount: Int = 0,
    val sparkline: List<SparklinePoint> = emptyList(),
    val geigerIntervalMs: Long = 1400L,
    val geigerToneHz: Double = 320.0,
    val normalizedProximity0To1: Double = 0.0,
    val lastSeenElapsedMs: Long? = null,
    val lastSeenAgeMs: Long = 0L,
    val isConnectedFastPath: Boolean = false,
    val isRttActive: Boolean = false,
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
)
