package com.a55.wifiradar.feature.proximity

import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PropagationEnvironment
import java.util.Locale

object ProximityTestTags {
    const val SCREEN_ROOT = "proximity_screen_root"
    const val EMPTY_TARGET_CARD = "proximity_empty_target_card"
    const val EMPTY_SCAN_CTA = "proximity_empty_scan_cta"
    const val WIFI_OFF_SCANNING_BANNER = "proximity_wifi_off_scanning_banner"

    const val TARGET_HEADER_CARD = "proximity_target_header_card"
    const val TARGET_SWITCHER_BUTTON = "proximity_target_switcher_button"
    const val TARGET_PICKER_SCRIM = "proximity_target_picker_scrim"
    const val TARGET_PICKER_SHEET = "proximity_target_picker_sheet"
    const val TARGET_PICKER_LIST = "proximity_target_picker_list"
    const val TARGET_PICKER_CLOSE_BUTTON = "proximity_target_picker_close_button"
    const val LOCK_MODE_SINGLE_BSSID = "proximity_lock_mode_bssid"
    const val LOCK_MODE_FOLLOW_MESH = "proximity_lock_mode_mesh_ssid"

    const val SIGNAL_LOST_BANNER = "proximity_signal_lost_banner"
    const val SIX_GHZ_HARDWARE_BANNER = "proximity_6ghz_hardware_banner"

    const val SONAR_GAUGE = "proximity_sonar_gauge_canvas"
    const val HERO_DBM_TEXT = "proximity_hero_dbm_text"
    const val ZONE_PILL = "proximity_zone_pill"
    const val RAW_DBM_TEXT = "proximity_raw_dbm_text"
    const val QUALITY_PCT_TEXT = "proximity_quality_pct_text"
    const val PEAK_DBM_TEXT = "proximity_peak_dbm_text"

    const val VELOCITY_BANNER = "proximity_velocity_banner"
    const val DISTANCE_VALUE_TEXT = "proximity_distance_value_text"
    const val DISTANCE_METHOD_BADGE = "proximity_distance_method_badge"
    const val CALIBRATE_1M_BUTTON = "proximity_calibrate_1m_button"
    const val CLEAR_CALIBRATION_BUTTON = "proximity_clear_calibration_button"

    const val SPARKLINE_CANVAS = "proximity_sparkline_canvas"
    const val STAT_MIN = "proximity_stat_min"
    const val STAT_MAX = "proximity_stat_max"
    const val STAT_AVG = "proximity_stat_avg"
    const val STAT_STABILITY = "proximity_stat_stability"
    const val RESET_STATS_BUTTON = "proximity_reset_stats_button"

    const val BURST_TOKEN_RING = "proximity_burst_token_ring"
    const val BURST_SCAN_BUTTON = "proximity_burst_scan_button"
    const val HAPTIC_TOGGLE = "proximity_haptic_toggle"
    const val AUDIO_TOGGLE = "proximity_audio_toggle"

    fun environmentChip(env: PropagationEnvironment): String =
        "proximity_env_${env.name.lowercase(Locale.US)}"

    fun pickerRadioItem(bssid: String): String =
        "proximity_picker_radio_${MacAddressUtils.normalizeBssid(bssid).lowercase(Locale.US)}"

    fun pickerMeshItem(ssid: String): String =
        "proximity_picker_mesh_${ssid.lowercase(Locale.US).replace(' ', '_')}"
}
