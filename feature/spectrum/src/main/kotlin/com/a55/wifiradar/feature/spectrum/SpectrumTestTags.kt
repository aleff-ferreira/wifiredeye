package com.a55.wifiradar.feature.spectrum

import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.WifiBand
import java.util.Locale

object SpectrumTestTags {
    const val SCREEN_ROOT = "spectrum_screen_root"
    const val HEADER_SECTION = "spectrum_header_section"
    const val BAND_SELECTOR_ROW = "spectrum_band_selector_row"
    const val TOGGLE_INCLUDE_DFS = "spectrum_toggle_include_dfs"
    const val TOGGLE_EXCLUDE_CONNECTED = "spectrum_toggle_exclude_connected"
    const val BUTTON_SCAN_NOW = "spectrum_button_scan_now"
    const val SCAN_FEEDBACK_PILL = "spectrum_scan_feedback_pill"

    const val HARDWARE_6GHZ_BANNER = "spectrum_6ghz_hardware_banner"
    const val RNR_NEIGHBORS_CARD = "spectrum_6ghz_rnr_neighbors_card"

    const val SPECTRUM_CANVAS_CARD = "spectrum_canvas_card"
    const val SPECTRUM_CANVAS = "spectrum_overlap_canvas"
    const val AGGREGATED_WEAK_PILL = "spectrum_aggregated_weak_pill"
    const val CURVE_CHIPS_GROUP = "spectrum_curve_chips_group"
    const val CURVE_CHIPS_EXPAND_BUTTON = "spectrum_curve_chips_expand_button"
    const val CLEAR_SELECTION_CHIP = "spectrum_clear_selection_chip"
    const val SELECTED_CURVE_INSPECTOR = "spectrum_selected_curve_inspector"
    const val SELECTED_CURVE_PROXIMITY_CTA = "spectrum_selected_curve_proximity_cta"
    const val SELECTED_CURVE_DETAIL_CTA = "spectrum_selected_curve_detail_cta"

    const val BEST_CHANNEL_CARD = "spectrum_best_channel_card"
    const val BEST_CHANNEL_REASON = "spectrum_best_channel_reason"

    const val CHANNEL_SCORES_CARD = "spectrum_channel_scores_card"
    const val CHANNEL_SCORES_EXPAND_BUTTON = "spectrum_channel_scores_expand_button"

    const val CCI_ACI_SECTION = "spectrum_cci_aci_section"
    const val CCI_CARD = "spectrum_cci_card"
    const val ACI_CARD = "spectrum_aci_card"
    const val ACI_SEVERE_WARNING = "spectrum_aci_severe_warning"
    const val LEGEND_BAR = "spectrum_inline_legend_bar"

    fun bandTab(band: WifiBand): String = when (band) {
        WifiBand.BAND_2_4_GHZ -> "spectrum_band_2_4_ghz"
        WifiBand.BAND_5_GHZ -> "spectrum_band_5_ghz"
        WifiBand.BAND_6_GHZ -> "spectrum_band_6_ghz"
        else -> "spectrum_band_other"
    }

    fun curveChip(bssid: String): String =
        "spectrum_curve_chip_${MacAddressUtils.normalizeBssid(bssid).lowercase(Locale.US).replace(':', '_')}"

    fun channelScoreRow(channel: Int): String = "spectrum_channel_row_$channel"

    fun rnrNeighborRow(index: Int): String = "spectrum_rnr_neighbor_row_$index"
}
