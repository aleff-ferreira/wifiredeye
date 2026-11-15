package com.a55.wifiradar.feature.insights

import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.WifiBand
import java.util.Locale

object InsightsTestTags {
    const val SCREEN_ROOT = "insights_screen_root"
    const val HEADER_SECTION = "insights_header_section"
    const val HEADER_SCAN_NOW_BUTTON = "insights_scan_now_button"
    const val STATUS_BANNER = "insights_status_banner"
    const val LEGEND_BAR = "insights_legend_bar"

    // Section 1: Wireless Environment Health Score
    const val HEALTH_CARD = "insights_health_card"
    const val HEALTH_SCORE_RING = "insights_health_score_ring"
    const val HEALTH_WAITING_RING = "insights_health_waiting_ring"
    const val HEALTH_HEADLINE = "insights_health_headline"
    const val DEVICE_RADIO_SUMMARY = "insights_device_radio_summary"

    fun subScoreRow(subScoreKey: String): String =
        "insights_subscore_${subScoreKey.lowercase(Locale.US)}"

    fun bestChannelJumpChip(band: WifiBand): String =
        "insights_best_channel_jump_${band.displayLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")}"

    // Section 2: Actionable Insights
    const val INSIGHTS_LIST_SECTION = "insights_actionable_section"
    const val INSIGHTS_EMPTY_CARD = "insights_actionable_empty"

    fun insightCard(id: String): String =
        "insights_card_${id.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_")}"

    fun insightActionCta(id: String): String =
        "insights_cta_${id.lowercase(Locale.US).replace(Regex("[^a-z0-9_]+"), "_")}"

    // Section 3: Multi-Network RSSI Time Graph & Mesh Roam Log
    const val TIME_GRAPH_CARD = "insights_time_graph_card"
    const val RSSI_TIME_GRAPH_CANVAS = "insights_rssi_time_graph_canvas"
    const val ROAM_LOG_CARD = "insights_roam_log_card"
    const val ROAM_LOG_EMPTY = "insights_roam_log_empty"
    const val ROAM_SHOW_ALL_TOGGLE = "insights_roam_show_all_toggle"

    fun historyWindowChip(window: RssiHistoryWindow): String =
        "insights_window_chip_${window.displayLabel.lowercase(Locale.US)}"

    fun timelineSeriesRow(bssid: String): String =
        "insights_timeline_series_${MacAddressUtils.normalizeBssid(bssid).lowercase(Locale.US).replace(':', '_')}"

    fun roamEventRow(index: Int): String = "insights_roam_event_$index"

    // Section 4: Security Posture & Evil-Twin Audit
    const val SECURITY_SECTION_CARD = "insights_security_section_card"
    const val SECURITY_SHOW_ALL_TOGGLE = "insights_security_show_all_toggle"

    fun evilTwinAlertCard(ssid: String): String =
        "insights_evil_twin_${ssid.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")}"

    fun evilTwinLocateCta(ssid: String): String =
        "insights_evil_twin_locate_${ssid.trim().lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "_")}"

    fun securityAuditRow(bssid: String): String =
        "insights_security_row_${MacAddressUtils.normalizeBssid(bssid).lowercase(Locale.US).replace(':', '_')}"

    fun securityInspectCta(bssid: String): String =
        "insights_security_inspect_${MacAddressUtils.normalizeBssid(bssid).lowercase(Locale.US).replace(':', '_')}"

    // Section 5: Diagnostic Export & Share
    const val EXPORT_CARD = "insights_export_card"
    const val EXPORT_REDACT_TOGGLE = "insights_export_redact_toggle"
    const val EXPORT_PREVIEW_TOGGLE = "insights_export_preview_toggle"
    const val EXPORT_PREVIEW_BOX = "insights_export_preview_box"
    const val COPY_EXPORT_BUTTON = "insights_copy_export_button"
    const val SHARE_MARKDOWN_BUTTON = "insights_share_markdown_button"
    const val SHARE_JSON_BUTTON = "insights_share_json_button"
    const val EXPORT_COPY_CONFIRMATION = "insights_export_copy_confirmation"

    fun exportFormatButton(format: ExportFormat): String =
        "insights_export_format_${format.fileExtension.lowercase(Locale.US)}"
}
