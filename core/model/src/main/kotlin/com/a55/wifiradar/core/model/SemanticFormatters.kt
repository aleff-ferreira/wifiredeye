package com.a55.wifiradar.core.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object SemanticFormatters {

    private val RAW_ENUM_LEAK_REGEX = Regex(
        """\b(WIFI_STANDARD_[A-Z0-9_]+|SECURITY_TYPE_[A-Z0-9_]+|CHANNEL_WIDTH_[A-Z0-9_]+|SCAN_RESULTS_[A-Z0-9_]+|WIFI_BAND_[A-Z0-9_]+|BAND_[0-9]_[0-9]_GHZ|BAND_[0-9]+_GHZ|WIDTH_[0-9]+(_PLUS_[0-9]+)?_MHZ|A_PLUS|A_MINUS|DEAD_ZONE|MOVING_AWAY|HOME_OFFICE|HEAVY_WALLS|OPEN_OR_WEAK_SECURITY|ALL_RADIOS_BSSID|GROUPED_MESH)\b"""
    )

    fun containsRawEnumLeak(text: String): Boolean =
        RAW_ENUM_LEAK_REGEX.containsMatchIn(text)

    /**
     * Maps RSSI dBm to 0..100% quality (`-100 dBm -> 0%`, `-50 dBm -> 100%`).
     */
    fun signalQualityPct(rssiDbm: Double): Int =
        (2.0 * (rssiDbm + 100.0)).roundToInt().coerceIn(0, 100)

    fun signalQualityPct(rssiDbm: Int): Int =
        (2 * (rssiDbm + 100)).coerceIn(0, 100)

    /**
     * Example: "-48 dBm · Excellent · Very Close" or "-72 dBm · Fair · Good for Web, Slower for 4K"
     */
    fun formatRssiForDiscovery(rssiDbm: Int): String {
        val tier = SignalQualityTier.fromRssiDbm(rssiDbm)
        return when (tier) {
            SignalQualityTier.EXCELLENT -> "$rssiDbm dBm · ${tier.shortLabel} · ${tier.proximityPhrase}"
            SignalQualityTier.GOOD -> "$rssiDbm dBm · ${tier.shortLabel} · ${tier.speedPhrase}"
            SignalQualityTier.FAIR -> "$rssiDbm dBm · ${tier.shortLabel} · ${tier.speedPhrase}"
            SignalQualityTier.WEAK -> "$rssiDbm dBm · ${tier.shortLabel} · ${tier.proximityPhrase}"
            SignalQualityTier.DEAD_ZONE -> "$rssiDbm dBm · ${tier.shortLabel} · ${tier.proximityPhrase}"
        }
    }

    /**
     * Example: "-51 dBm · Excellent · Very Close"
     */
    fun formatRssiForProximity(smoothedRssiDbm: Double?): String {
        if (smoothedRssiDbm == null) return "— dBm · Signal Lost"
        val rounded = smoothedRssiDbm.roundToInt()
        val tier = SignalQualityTier.fromRssiDbm(rounded)
        return "$rounded dBm · ${tier.shortLabel} · ${tier.proximityPhrase}"
    }

    /**
     * Example: "96% · Excellent"
     */
    fun formatQualityPct(qualityPct: Int, tier: SignalQualityTier): String =
        "${qualityPct.coerceIn(0, 100)}% · ${tier.shortLabel}"

    /**
     * Example: "5 GHz · Fast" or "2.4 GHz · Longer Range"
     */
    fun formatBandWithTrait(band: WifiBand): String =
        "${band.displayLabel} · ${band.traitLabel}"

    /**
     * Example: "80 MHz · Wide Fast Lane"
     */
    fun formatChannelWidthWithTrait(width: ChannelWidth): String =
        "${width.displayLabel} · ${width.plainEnglishTrait}"

    /**
     * Example: "Ch 36 (5180 MHz) · 80 MHz · Wide Open" or "Ch 6 (2437 MHz) · 20 MHz · Crowded"
     */
    fun formatChannelSummary(
        descriptor: ChannelDescriptor,
        congestionScore0To100: Int? = null,
    ): String {
        val chPart = if (descriptor.primaryChannel > 0) {
            "Ch ${descriptor.primaryChannel} (${descriptor.primaryFreqMhz} MHz)"
        } else {
            "${descriptor.primaryFreqMhz} MHz"
        }
        val flags = buildList {
            add(descriptor.width.displayLabel)
            if (descriptor.isTdwr) add("Weather Radar DFS")
            else if (descriptor.isDfs) add("DFS")
            if (descriptor.isPsc) add("PSC")
            if (congestionScore0To100 != null) add(congestionQualifier(congestionScore0To100))
        }.joinToString(" · ")
        return "$chPart · $flags"
    }

    fun congestionQualifier(score0To100: Int): String = when {
        score0To100 >= 80 -> "Wide Open"
        score0To100 >= 60 -> "Light Traffic"
        score0To100 >= 40 -> "Moderate Load"
        else -> "Crowded"
    }

    /**
     * Example: "92 / 100 · Wide Open (4.5★)"
     */
    fun formatChannelScore(score0To100: Int, stars0To5: Double): String {
        val clamped = score0To100.coerceIn(0, 100)
        val starStr = String.format(Locale.US, "%.1f", stars0To5.coerceIn(0.0, 5.0))
        return "$clamped / 100 · ${congestionQualifier(clamped)} ($starStr★)"
    }

    /**
     * Example: "Wi-Fi 6 (802.11ax) · High-Efficiency Modern"
     */
    fun formatWifiGeneration(gen: WifiGeneration): String = when (gen) {
        WifiGeneration.UNKNOWN -> "Standard Wi-Fi (802.11)"
        else -> "${gen.shortBadge} (${gen.ieeeAmendment}) · ${gen.plainEnglishTrait}"
    }

    /**
     * Example: "A+ · WPA3-Personal · Strong Modern Password Protection"
     */
    fun formatSecuritySummary(security: SecurityAssessment): String =
        "${security.grade.badgeText} · ${security.protocol.displayLabel} · ${security.protocol.plainEnglishSummary}"

    /**
     * Example: "802.11k/v/r · Seamless Mesh Roaming"
     */
    fun formatRoamingSummary(roaming: RoamingCapabilities): String {
        val letters = buildList {
            if (roaming.supports80211k) add("k")
            if (roaming.supports80211v) add("v")
            if (roaming.supports80211r) add("r")
        }
        return when {
            letters.size == 3 -> "802.11k/v/r · Seamless Mesh Roaming"
            letters.isNotEmpty() -> "802.11${letters.joinToString("/")} · Assisted Roaming"
            else -> "Standard Client Roaming"
        }
    }

    /**
     * Example: "14 clients · 28% airtime busy · Light Load"
     */
    fun formatBssLoad(bssLoad: BssLoadInfo?): String {
        if (bssLoad == null) return "Client load not advertised by router"
        val loadTier = when {
            bssLoad.channelUtilizationPct < 35 -> "Light Load"
            bssLoad.channelUtilizationPct < 70 -> "Moderate Airtime Load"
            else -> "Heavy Airtime Congestion"
        }
        val clientNoun = if (bssLoad.stationCount == 1) "client" else "clients"
        return "${bssLoad.stationCount} $clientNoun · ${bssLoad.channelUtilizationPct}% airtime busy · $loadTier"
    }

    /**
     * Example: "Approaching ↑ (+1.8 dB/s)" or "Steady · Holding Position"
     */
    fun formatVelocityTrend(trend: VelocityTrend, slopeDbPerSec: Double): String = when (trend) {
        VelocityTrend.APPROACHING -> String.format(
            Locale.US,
            "Approaching ↑ (%+.1f dB/s)",
            abs(slopeDbPerSec),
        )
        VelocityTrend.MOVING_AWAY -> String.format(
            Locale.US,
            "Moving Away ↓ (-%.1f dB/s)",
            abs(slopeDbPerSec),
        )
        VelocityTrend.STEADY -> "Steady · Holding Position"
        VelocityTrend.CALIBRATING -> "Calibrating trend..."
    }

    /**
     * Matches `survey_3` thresholds: <= 2.0 dB Rock Solid, <= 4.5 dB Steady, > 4.5 dB Fluctuating.
     */
    fun stabilityQualifier(stdDevDb: Double): String = when {
        stdDevDb <= 2.0 -> "Rock Solid"
        stdDevDb <= 4.5 -> "Steady"
        else -> "Fluctuating"
    }

    /**
     * Example: "94% · Rock Solid (±1.1 dB)"
     */
    fun formatStability(stabilityPct: Int, stdDevDb: Double): String = String.format(
        Locale.US,
        "%d%% · %s (±%.1f dB)",
        stabilityPct.coerceIn(0, 100),
        stabilityQualifier(stdDevDb),
        stdDevDb.coerceAtLeast(0.0),
    )

    /**
     * Formats distance estimates with realistic confidence bounds; clamps < 0.5m and > 50m.
     */
    fun formatDistanceRange(
        meters: Double,
        rangeMinMeters: Double,
        rangeMaxMeters: Double,
        stdDevMeters: Double? = null,
        method: DistanceMethod = DistanceMethod.PATH_LOSS_ESTIMATE,
    ): String {
        if (method.isHardwareRtt) {
            if (meters < 0.3) return "< 0.3 m · Right Next to Router (${method.badgeLabel})"
            val sd = stdDevMeters ?: 0.5
            return String.format(Locale.US, "%.1f m ± %.1f m · %s", meters, sd, method.badgeLabel)
        }
        return when {
            meters < 0.5 -> "< 0.5 m · Right Next to Router"
            meters > 50.0 -> "> 50 m · Far Edge of Reach"
            else -> String.format(
                Locale.US,
                "Est. ~%.1f – %.1f m",
                rangeMinMeters.coerceAtLeast(0.3),
                rangeMaxMeters.coerceAtMost(60.0),
            )
        }
    }

    /**
     * Example: "Just now", "12s ago", "2m 5s ago"
     */
    fun formatAge(ageMs: Long): String {
        val sec = (ageMs.coerceAtLeast(0L) / 1000L)
        return when {
            sec <= 2L -> "Just now"
            sec < 60L -> "${sec}s ago"
            else -> "${sec / 60L}m ${sec % 60L}s ago"
        }
    }

    /**
     * Top scan freshness & Android throttle status pill (`F20`, `F24`).
     */
    fun formatScanStatusPill(networkCount: Int, telemetry: ScanTelemetry): String {
        if (telemetry.lastScanTimestampMs == null) {
            return "Scanning nearby wireless spectrum..."
        }
        val netNoun = if (networkCount == 1) "Network" else "Networks"
        val updatedPart = "Updated ${formatAge(telemetry.lastScanAgeMs)}"
        return when {
            !telemetry.isScanThrottleEnabled ->
                "Unthrottled Live · $networkCount $netNoun · $updatedPart"
            telemetry.isPassiveOnlyRestrictedProfile ->
                "Passive Scan Mode · $networkCount $netNoun · $updatedPart"
            telemetry.isCurrentlyThrottled || telemetry.availableTokens == 0 -> {
                val waitSec = ((telemetry.nextTokenRechargeInMs + 999L) / 1000L).coerceAtLeast(1L)
                "OS Throttle Active · Next active scan in ${waitSec}s (Passive Live)"
            }
            telemetry.isBurstModeActive ->
                "Burst Homing (${telemetry.availableTokens}/${telemetry.maxTokens} pulses) · $updatedPart"
            else ->
                "Live · $networkCount $netNoun · $updatedPart"
        }
    }

    /**
     * Example: "Signal Lost · Last seen 12s ago at -86 dBm"
     */
    fun formatDropoutBanner(lastSeenAgeMs: Long, lastKnownRssiDbm: Int?): String {
        val agePart = formatAge(lastSeenAgeMs)
        return if (lastKnownRssiDbm != null) {
            "Signal Lost · Last seen $agePart at $lastKnownRssiDbm dBm"
        } else {
            "Signal Lost · Last seen $agePart"
        }
    }

    /**
     * Example: "84 / 100 · Healthy Wireless Environment" or "Waiting for scan data"
     */
    fun formatHealthScoreHeadline(score0To100: Int?): String {
        if (score0To100 == null) return "Waiting for scan data"
        val clamped = score0To100.coerceIn(0, 100)
        val tier = when {
            clamped >= 80 -> "Healthy Wireless Environment"
            clamped >= 60 -> "Good · Minor Tuning Available"
            clamped >= 40 -> "Moderate Interference or Risk"
            else -> "Attention Needed · High Congestion or Risk"
        }
        return "$clamped / 100 · $tier"
    }

    /**
     * Example: "38 dB SNR · Very Clean Channel"
     */
    fun formatSnr(snrDb: Double): String {
        val rounded = snrDb.roundToInt()
        val qualifier = when {
            rounded >= 35 -> "Very Clean Channel"
            rounded >= 25 -> "Clean Signal"
            rounded >= 15 -> "Moderate Noise"
            else -> "Noisy Channel"
        }
        return "$rounded dB SNR · $qualifier"
    }
}
