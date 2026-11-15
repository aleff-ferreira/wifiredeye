package com.a55.wifiradar.core.designsystem.component

object RadarTestTags {
    const val TOP_BAR = "radar_top_bar"
    const val SCAN_STATUS_PILL = "radar_scan_status_pill"
    const val BOTTOM_NAV_BAR = "radar_bottom_nav_bar"
    const val READINESS_CARD = "readiness_checklist_card"
    const val READINESS_PRIMARY_CTA = "readiness_primary_cta"

    fun bottomNavTab(routeKey: String): String = "radar_bottom_tab_${routeKey.lowercase()}"
    fun legendPill(topicId: String): String = "legend_pill_${topicId.lowercase()}"
    fun legendCard(topicId: String): String = "legend_card_${topicId.lowercase()}"
    fun legendDismiss(topicId: String): String = "legend_dismiss_${topicId.lowercase()}"
    fun readinessStep(stepId: String): String = "readiness_step_${stepId.lowercase()}"
}
