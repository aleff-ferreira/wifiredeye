package com.a55.wifiradar.feature.proximity

import androidx.compose.runtime.Immutable
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SignalLockStatus

enum class ProximityLockMode(val displayLabel: String) {
    SINGLE_ROUTER_BSSID("Single Router (BSSID)"),
    FOLLOW_MESH_SSID("Follow Mesh (SSID)"),
}

@Immutable
data class ProximityUiState(
    val readinessState: ScannerReadinessState = ScannerReadinessState.Ready(),
    val radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
    val visibleNetworkCount: Int = 0,
    val activeTargetKey: ProximityTargetKey? = null,
    val lockMode: ProximityLockMode = ProximityLockMode.SINGLE_ROUTER_BSSID,
    val canFollowMeshSsid: Boolean = false,
    val resolvedMeshSsid: String? = null,
    val calibrationTargetBssid: String? = null,
    val environment: PropagationEnvironment = PropagationEnvironment.HOME_OFFICE,
    val telemetry: ProximityTelemetry? = null,
    val availableRadios: List<BssidRadio> = emptyList(),
    val availableMeshGroups: List<EssNetworkGroup> = emptyList(),
    val isTargetPickerOpen: Boolean = false,
    val feedbackSettings: GeigerFeedbackSettings = GeigerFeedbackSettings(),
    val hasHardwareVibrator: Boolean = true,
    val burstStatusFeedback: String? = null,
    val lastScanTriggerResult: ScanTriggerResult? = null,
) {
    val canCalibrateOneMeter: Boolean
        get() = calibrationTargetBssid != null &&
            telemetry?.smoothedRssiDbm != null &&
            telemetry.lockStatus != SignalLockStatus.SIGNAL_LOST &&
            telemetry.lockStatus != SignalLockStatus.SEARCHING

    val isCalibratedOneMeter: Boolean
        get() = calibrationTargetBssid != null && (telemetry?.isUserCalibrated1m == true)
}
