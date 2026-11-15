package com.a55.wifiradar.feature.networks

import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import java.util.Locale

object NetworksTestTags {
    const val SCREEN_ROOT = "networks_screen_root"
    const val LIST = "networks_lazy_list"
    const val DISCOVERY_STATUS_SUMMARY = "networks_discovery_status_summary"
    const val MANUAL_SCAN_BUTTON = "networks_manual_scan_button"
    const val WIFI_OFF_SCANNING_BANNER = "networks_wifi_off_scanning_banner"
    const val SIX_GHZ_HARDWARE_BANNER = "networks_6ghz_hardware_banner"
    const val CONNECTED_SPOTLIGHT_CARD = "networks_connected_spotlight_card"
    const val CONNECTED_DETAILS_BUTTON = "networks_connected_details_button"
    const val CONNECTED_PROXIMITY_BUTTON = "networks_connected_proximity_button"
    const val SEARCH_INPUT = "networks_search_input"
    const val SEARCH_CLEAR_BUTTON = "networks_search_clear_button"
    const val SORT_SELECTOR_BUTTON = "networks_sort_selector_button"
    const val EMPTY_FILTER_CARD = "networks_empty_filter_card"
    const val RESET_FILTERS_BUTTON = "networks_reset_filters_button"

    const val DETAIL_SCRIM = "networks_detail_scrim"
    const val DETAIL_SHEET = "networks_detail_sheet"
    const val DETAIL_CLOSE_BUTTON = "networks_detail_close_button"
    const val DETAIL_DROPOUT_BANNER = "networks_detail_dropout_banner"
    const val DETAIL_MEASURE_PROXIMITY_CTA = "networks_detail_measure_proximity_cta"
    const val DETAIL_COPY_SUMMARY_CTA = "networks_detail_copy_summary_cta"
    const val DETAIL_NICKNAME_INPUT = "networks_detail_nickname_input"
    const val DETAIL_NICKNAME_SAVE_CTA = "networks_detail_nickname_save_cta"
    const val DETAIL_NICKNAME_CLEAR_CTA = "networks_detail_nickname_clear_cta"

    fun viewModeTab(mode: NetworkViewMode): String =
        "networks_view_mode_${mode.name.lowercase(Locale.US)}"

    fun filterChip(chip: NetworkFilterChip): String =
        "networks_filter_${chip.name.lowercase(Locale.US)}"

    fun sortOptionChip(option: NetworkSortOption): String =
        "networks_sort_${option.name.lowercase(Locale.US)}"

    fun groupCard(groupKey: String): String = "networks_group_card_$groupKey"

    fun groupExpandButton(groupKey: String): String = "networks_group_expand_$groupKey"

    fun groupDetailsButton(groupKey: String): String = "networks_group_details_$groupKey"

    fun groupProximityButton(groupKey: String): String = "networks_group_proximity_$groupKey"

    fun radioCard(bssid: String): String =
        "networks_radio_card_${MacAddressUtils.normalizeBssid(bssid)}"

    fun radioDetailsButton(bssid: String): String =
        "networks_radio_details_${MacAddressUtils.normalizeBssid(bssid)}"

    fun groupSubRadioRow(bssid: String): String =
        "networks_sub_radio_${MacAddressUtils.normalizeBssid(bssid)}"

    fun proximityButton(bssid: String): String =
        "networks_proximity_btn_${MacAddressUtils.normalizeBssid(bssid)}"
}
