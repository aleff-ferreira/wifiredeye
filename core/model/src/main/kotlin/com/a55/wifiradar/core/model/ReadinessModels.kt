package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class ReadinessStepState {
    SATISFIED,
    WARNING_SCAN_STILL_ACTIVE,
    ACTION_REQUIRED,
}

@Serializable
enum class ReadinessAction {
    NONE,
    REQUEST_RUNTIME_PERMISSIONS,
    UPGRADE_TO_PRECISE_LOCATION,
    OPEN_APP_PERMISSION_SETTINGS,
    OPEN_LOCATION_SETTINGS,
    OPEN_WIFI_SETTINGS_PANEL,
    OPEN_SCAN_ALWAYS_AVAILABLE,
}

@Immutable
@Serializable
data class ReadinessStep(
    val stepNumber: Int,
    val title: String,
    val plainEnglishDetail: String,
    val state: ReadinessStepState,
    val action: ReadinessAction = ReadinessAction.NONE,
    val actionButtonLabel: String? = null,
)

@Immutable
@Serializable
data class ReadinessChecklist(
    val permissionStep: ReadinessStep,
    val locationStep: ReadinessStep,
    val wifiStep: ReadinessStep,
) {
    val steps: List<ReadinessStep>
        get() = listOf(permissionStep, locationStep, wifiStep)

    companion object {
        fun allReady(): ReadinessChecklist = ReadinessChecklist(
            permissionStep = ReadinessStep(
                stepNumber = 1,
                title = "Nearby Wi-Fi & Precise Location Permission",
                plainEnglishDetail = "Granted — 100% offline local Wi-Fi scanning enabled.",
                state = ReadinessStepState.SATISFIED,
            ),
            locationStep = ReadinessStep(
                stepNumber = 2,
                title = "Device Location Services",
                plainEnglishDetail = "Turned on — Android allows Wi-Fi scan results.",
                state = ReadinessStepState.SATISFIED,
            ),
            wifiStep = ReadinessStep(
                stepNumber = 3,
                title = "Wi-Fi Radio",
                plainEnglishDetail = "Turned on and actively listening across bands.",
                state = ReadinessStepState.SATISFIED,
            ),
        )
    }
}

@Immutable
@Serializable
sealed interface ScannerReadinessState {
    val canScanNow: Boolean
    val headline: String
    val plainEnglishExplanation: String
    val primaryAction: ReadinessAction
    val primaryActionLabel: String?
    val checklist: ReadinessChecklist

    @Immutable
    @Serializable
    data class Ready(
        override val checklist: ReadinessChecklist = ReadinessChecklist.allReady(),
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = true
        override val headline: String = "Scanner Ready"
        override val plainEnglishExplanation: String =
            "All permissions and wireless radios are active."
        override val primaryAction: ReadinessAction = ReadinessAction.NONE
        override val primaryActionLabel: String? = null
    }

    @Immutable
    @Serializable
    data class WifiDisabledScanAlwaysOn(
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = true
        override val headline: String = "Wi-Fi Off · Background Scanning Active"
        override val plainEnglishExplanation: String =
            "Nearby networks are still detected via Android Wi-Fi Scanning. Turn on Wi-Fi to associate or measure high-rate link telemetry."
        override val primaryAction: ReadinessAction = ReadinessAction.OPEN_WIFI_SETTINGS_PANEL
        override val primaryActionLabel: String = "Turn On Wi-Fi"
    }

    @Immutable
    @Serializable
    data class MissingPermission(
        val missingPermissions: List<String>,
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = false
        override val headline: String = "Allow Wi-Fi & Precise Location Access"
        override val plainEnglishExplanation: String =
            "Android requires Precise Location & Nearby Wi-Fi permission to read nearby router names. This app works 100% offline and never records or uploads your GPS location."
        override val primaryAction: ReadinessAction = ReadinessAction.REQUEST_RUNTIME_PERMISSIONS
        override val primaryActionLabel: String = "Grant Scan Permission"
    }

    @Immutable
    @Serializable
    data class ApproximateOnlyNeedsPrecise(
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = false
        override val headline: String = "Upgrade to Precise Location"
        override val plainEnglishExplanation: String =
            "Approximate location was selected, which blocks Wi-Fi router scanning on Android 12+. Tap below and select Precise so networks can appear."
        override val primaryAction: ReadinessAction = ReadinessAction.UPGRADE_TO_PRECISE_LOCATION
        override val primaryActionLabel: String = "Upgrade to Precise Location"
    }

    @Immutable
    @Serializable
    data class PermanentDenial(
        val deniedPermissions: List<String>,
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = false
        override val headline: String = "Enable Permission in App Settings"
        override val plainEnglishExplanation: String =
            "Wi-Fi scanning permission was permanently declined. Open App Settings -> Permissions -> Location -> Allow Precise while using the app."
        override val primaryAction: ReadinessAction = ReadinessAction.OPEN_APP_PERMISSION_SETTINGS
        override val primaryActionLabel: String = "Open App Permission Settings"
    }

    @Immutable
    @Serializable
    data class LocationDisabled(
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = false
        override val headline: String = "Turn On Device Location"
        override val plainEnglishExplanation: String =
            "Phone Location is currently turned off. Android hides all nearby Wi-Fi networks while Location is disabled."
        override val primaryAction: ReadinessAction = ReadinessAction.OPEN_LOCATION_SETTINGS
        override val primaryActionLabel: String = "Open Location Settings"
    }

    @Immutable
    @Serializable
    data class WifiAndScanDisabled(
        val isAirplaneMode: Boolean = false,
        override val checklist: ReadinessChecklist,
    ) : ScannerReadinessState {
        override val canScanNow: Boolean = false
        override val headline: String =
            if (isAirplaneMode) "Wi-Fi Disabled in Airplane Mode" else "Turn On Wi-Fi to Scan"
        override val plainEnglishExplanation: String =
            "Both Wi-Fi and background Wi-Fi Scanning are turned off, so the radio cannot listen for nearby routers."
        override val primaryAction: ReadinessAction = ReadinessAction.OPEN_WIFI_SETTINGS_PANEL
        override val primaryActionLabel: String = "Turn On Wi-Fi"
    }
}
