package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Authoritative 4-tab bottom navigation destinations (`PROJECT.md § Interface Contracts 4`).
 */
@Serializable
enum class RadarDestination(val tabTitle: String, val route: String) {
    NETWORKS(tabTitle = "Networks", route = "networks"),
    PROXIMITY(tabTitle = "Proximity", route = "proximity"),
    SPECTRUM(tabTitle = "Spectrum", route = "spectrum"),
    INSIGHTS(tabTitle = "Insights", route = "insights");

    val label: String
        get() = tabTitle

    val routeKey: String
        get() = route
}

@Serializable
enum class WifiBand(val displayLabel: String, val traitLabel: String) {
    BAND_2_4_GHZ(displayLabel = "2.4 GHz", traitLabel = "Longer Range"),
    BAND_5_GHZ(displayLabel = "5 GHz", traitLabel = "Fast"),
    BAND_6_GHZ(displayLabel = "6 GHz", traitLabel = "Ultra-Fast"),
    BAND_60_GHZ(displayLabel = "60 GHz", traitLabel = "Short-Range Multi-Gig"),
    UNKNOWN(displayLabel = "Custom Band", traitLabel = "Unclassified Frequency"),
}

@Serializable
enum class ChannelWidth(
    val widthMhz: Int,
    val primarySpanMhz: Int,
    val androidCode: Int,
    val displayLabel: String,
    val plainEnglishTrait: String,
) {
    WIDTH_20_MHZ(20, 20, 0, "20 MHz", "Standard Reliable Lane"),
    WIDTH_40_MHZ(40, 40, 1, "40 MHz", "Dual Bonded Lane"),
    WIDTH_80_MHZ(80, 80, 2, "80 MHz", "Wide Fast Lane"),
    WIDTH_160_MHZ(160, 160, 3, "160 MHz", "Ultra-Wide Lane"),
    WIDTH_80_PLUS_80_MHZ(160, 80, 4, "80+80 MHz", "Split Dual-Wide Lane"),
    WIDTH_320_MHZ(320, 320, 5, "320 MHz", "Extreme Wi-Fi 7 Lane"),
    UNKNOWN(20, 20, -1, "20 MHz", "Standard Reliable Lane");

    companion object {
        fun fromAndroidCode(code: Int): ChannelWidth =
            entries.firstOrNull { it.androidCode == code } ?: UNKNOWN

        fun fromWidthMhz(mhz: Int, centerFreq1Mhz: Int = 0): ChannelWidth = when {
            mhz == 160 && centerFreq1Mhz > 0 -> WIDTH_80_PLUS_80_MHZ
            mhz == 320 -> WIDTH_320_MHZ
            mhz == 160 -> WIDTH_160_MHZ
            mhz == 80 -> WIDTH_80_MHZ
            mhz == 40 -> WIDTH_40_MHZ
            mhz == 20 -> WIDTH_20_MHZ
            else -> UNKNOWN
        }
    }
}

@Serializable
enum class WifiGeneration(
    val rankOrder: Int,
    val shortBadge: String,
    val ieeeAmendment: String,
    val plainEnglishTrait: String,
) {
    UNKNOWN(0, "Wi-Fi", "802.11", "Standard Wireless"),
    LEGACY_11B(1, "Legacy 11b", "802.11b", "Very Old 2.4 GHz Legacy"),
    LEGACY_11A(2, "Legacy 11a", "802.11a", "Old 5 GHz Legacy"),
    LEGACY_11G(3, "Legacy 11g", "802.11g", "Old 2.4 GHz Legacy"),
    WIFI_4(4, "Wi-Fi 4", "802.11n", "Basic Older Generation"),
    WIFI_5(5, "Wi-Fi 5", "802.11ac", "Fast 5 GHz Standard"),
    WIGIG_11AD(6, "WiGig", "802.11ad", "Short-Range 60 GHz"),
    WIFI_6(7, "Wi-Fi 6", "802.11ax", "High-Efficiency Modern"),
    WIFI_6E(8, "Wi-Fi 6E", "802.11ax (6 GHz)", "Clean 6 GHz Tri-Band"),
    WIFI_7(9, "Wi-Fi 7", "802.11be", "Next-Gen Multi-Link");

    val isWifi6OrNewer: Boolean
        get() = this == WIFI_6 || this == WIFI_6E || this == WIFI_7
}

@Serializable
enum class UniiSubBand(val displayLabel: String) {
    ISM_2_4_GHZ("2.4 GHz ISM"),
    JAPAN_2_4_GHZ_CH14("2.4 GHz Ch 14 (JP)"),
    UNII_1("U-NII-1 (Indoor / Non-DFS)"),
    UNII_2A("U-NII-2A (DFS Radar Shared)"),
    UNII_2C("U-NII-2C (DFS Extended)"),
    UNII_3("U-NII-3 (Upper Non-DFS)"),
    UNII_4("U-NII-4 (5.9 GHz Extended)"),
    UNII_5("U-NII-5 (6 GHz Lower)"),
    UNII_6("U-NII-6 (6 GHz Indoor)"),
    UNII_7("U-NII-7 (6 GHz Standard)"),
    UNII_8("U-NII-8 (6 GHz Upper)"),
    WIGIG_60_GHZ("60 GHz WiGig"),
    UNKNOWN("Unclassified Sub-Band"),
}

@Serializable
enum class SignalQualityTier(
    val shortLabel: String,
    val proximityPhrase: String,
    val speedPhrase: String,
) {
    EXCELLENT("Excellent", "Very Close", "Maximum Speed"),
    GOOD("Strong", "Close", "Full Streaming & Gaming Speed"),
    FAIR("Fair", "Moderate Distance", "Good for Web, Slower for 4K"),
    WEAK("Weak", "Far / Obstructed", "Spotty — Packet Retries Likely"),
    DEAD_ZONE("Very Weak", "Edge of Reach", "Dropouts Expected");

    companion object {
        fun fromRssiDbm(rssiDbm: Int): SignalQualityTier = when {
            rssiDbm >= -50 -> EXCELLENT
            rssiDbm >= -65 -> GOOD
            rssiDbm >= -75 -> FAIR
            rssiDbm >= -85 -> WEAK
            else -> DEAD_ZONE
        }
    }
}

@Immutable
@Serializable
data class ChannelDescriptor(
    val band: WifiBand,
    val primaryFreqMhz: Int,
    val primaryChannel: Int,
    val centerFreq0Mhz: Int,
    val centerChannel0: Int,
    val centerFreq1Mhz: Int = 0,
    val centerChannel1: Int = -1,
    val width: ChannelWidth,
    val occupiedStartFreqMhz: Int,
    val occupiedEndFreqMhz: Int,
    val secondaryOccupiedStartFreqMhz: Int? = null,
    val secondaryOccupiedEndFreqMhz: Int? = null,
    val uniiSubBand: UniiSubBand = UniiSubBand.UNKNOWN,
    val isCanonicalPrimary: Boolean = false,
    val isDfs: Boolean = false,
    val isTdwr: Boolean = false,
    val isPsc: Boolean = false,
)

@Serializable
enum class NetworkViewMode(val displayLabel: String) {
    GROUPED_MESH("Grouped Networks"),
    ALL_RADIOS_BSSID("All Radios (BSSID)"),
}

@Serializable
enum class NetworkFilterChip(val displayLabel: String) {
    ALL("All"),
    BAND_2_4_GHZ("2.4 GHz"),
    BAND_5_GHZ("5 GHz"),
    BAND_6_GHZ("6 GHz"),
    OPEN_OR_WEAK_SECURITY("Open / Weak Security"),
    WIFI_6_PLUS("Wi-Fi 6+"),
    STRONG_SIGNAL("Strong >= -65 dBm"),
}

@Serializable
enum class NetworkSortOption(val displayLabel: String) {
    STRONGEST_SIGNAL("Strongest Signal"),
    NAME_A_TO_Z("Name A–Z"),
    CHANNEL_LOW_TO_HIGH("Channel Low–High"),
    LEAST_CONGESTED_CHANNEL("Least Congested Channel"),
    SECURITY_RISK("Security Risk"),
}

@Serializable
enum class RssiHistoryWindow(val durationMs: Long, val displayLabel: String) {
    ONE_MINUTE(60_000L, "1m"),
    FIVE_MINUTES(300_000L, "5m"),
    FIFTEEN_MINUTES(900_000L, "15m"),
}

object SavedStateKeys {
    const val ACTIVE_TAB = "active_tab"
    const val NETWORK_SEARCH_QUERY = "network_search_query"
    const val NETWORK_VIEW_MODE = "network_view_mode"
    const val NETWORK_ACTIVE_FILTERS = "network_active_filters"
    const val NETWORK_SORT_OPTION = "network_sort_option"
    const val SELECTED_DETAIL_BSSID = "selected_detail_bssid"
    const val PROXIMITY_TARGET_KEY = "proximity_target_key"
    const val PROXIMITY_ENVIRONMENT = "proximity_environment"
    const val SPECTRUM_SELECTED_BAND = "spectrum_selected_band"
    const val SPECTRUM_INCLUDE_DFS = "spectrum_include_dfs"
    const val SPECTRUM_EXCLUDE_OWN_SSID = "spectrum_exclude_own_ssid"
    const val SPECTRUM_HIGHLIGHT_CHANNEL = "spectrum_highlight_channel"
    const val INSIGHTS_HISTORY_WINDOW = "insights_history_window"
    const val INSIGHTS_REDACT_MACS = "insights_redact_macs"
}
