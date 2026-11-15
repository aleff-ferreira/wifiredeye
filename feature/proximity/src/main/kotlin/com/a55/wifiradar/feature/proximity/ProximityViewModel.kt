package com.a55.wifiradar.feature.proximity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SavedStateKeys
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal fun DecodedSsid.isHiddenOrUnnamed(): Boolean =
    isHidden || displayName.isBlank() || displayName == DecodedSsid.HIDDEN_NETWORK_LABEL

class ProximityViewModel(
    private val repository: WifiRadarRepository,
    private val savedStateHandle: SavedStateHandle,
    initialTargetKey: ProximityTargetKey? = null,
) : ViewModel() {

    init {
        val existingTarget = ProximityTargetKey.fromSavedStateString(
            savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY]
        )
        if (existingTarget == null) {
            if (initialTargetKey != null) {
                savedStateHandle[KEY_LAST_APPLIED_EXTERNAL_TARGET] = initialTargetKey.toSavedStateString()
                selectTarget(initialTargetKey)
            } else {
                val fallback = resolveSmartFallbackTarget(repository.radarSnapshot.value)
                if (fallback != null) {
                    savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY] = fallback.toSavedStateString()
                }
            }
        }

        viewModelScope.launch {
            repository.radarSnapshot.collect { snapshot ->
                val currentSaved = ProximityTargetKey.fromSavedStateString(
                    savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY]
                )
                if (currentSaved == null) {
                    val fallback = resolveSmartFallbackTarget(snapshot)
                    if (fallback != null) {
                        savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY] = fallback.toSavedStateString()
                    }
                }
            }
        }
    }

    private val targetKeyRawFlow: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.PROXIMITY_TARGET_KEY,
        initialValue = savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY],
    )

    private val environmentRawFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.PROXIMITY_ENVIRONMENT,
        initialValue = savedStateHandle[SavedStateKeys.PROXIMITY_ENVIRONMENT]
            ?: PropagationEnvironment.HOME_OFFICE.name,
    )

    private val targetPickerOpenFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = KEY_TARGET_PICKER_OPEN,
        initialValue = false,
    )

    private val burstFeedbackState = MutableStateFlow<Pair<String?, ScanTriggerResult?>>(null to null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeTelemetryFlow: Flow<ProximityTelemetry?> = combine(
        targetKeyRawFlow.map(ProximityTargetKey::fromSavedStateString),
        environmentRawFlow.map(::decodeEnvironment),
    ) { targetKey, environment ->
        targetKey to environment
    }
        .distinctUntilChanged()
        .flatMapLatest { (targetKey, environment) ->
            if (targetKey == null) {
                flowOf(null)
            } else {
                repository.observeProximity(targetKey, environment)
                    .map<ProximityTelemetry, ProximityTelemetry?> { it }
            }
        }

    private data class SavedControlsTuple(
        val targetKey: ProximityTargetKey?,
        val environment: PropagationEnvironment,
        val isPickerOpen: Boolean,
        val burstFeedback: String?,
        val lastTriggerResult: ScanTriggerResult?,
    )

    private val controlsTupleFlow = combine(
        targetKeyRawFlow,
        environmentRawFlow,
        targetPickerOpenFlow,
        burstFeedbackState,
    ) { targetRaw, envRaw, pickerOpen, (burstMsg, triggerResult) ->
        SavedControlsTuple(
            targetKey = ProximityTargetKey.fromSavedStateString(targetRaw),
            environment = decodeEnvironment(envRaw),
            isPickerOpen = pickerOpen,
            burstFeedback = burstMsg,
            lastTriggerResult = triggerResult,
        )
    }

    val uiState: StateFlow<ProximityUiState> = combine(
        repository.radarSnapshot,
        repository.readinessState,
        repository.feedbackController.feedbackSettings,
        controlsTupleFlow,
        activeTelemetryFlow,
    ) { snapshot, readiness, feedbackSettings, controls, telemetry ->
        buildUiState(
            snapshot = snapshot,
            readiness = readiness,
            feedbackSettings = feedbackSettings,
            controls = controls,
            telemetry = telemetry,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildUiState(
            snapshot = repository.radarSnapshot.value,
            readiness = repository.readinessState.value,
            feedbackSettings = repository.feedbackController.feedbackSettings.value,
            controls = SavedControlsTuple(
                targetKey = ProximityTargetKey.fromSavedStateString(targetKeyRawFlow.value),
                environment = decodeEnvironment(environmentRawFlow.value),
                isPickerOpen = targetPickerOpenFlow.value,
                burstFeedback = burstFeedbackState.value.first,
                lastTriggerResult = burstFeedbackState.value.second,
            ),
            telemetry = null,
        ),
    )

    private fun buildUiState(
        snapshot: RadarSnapshot,
        readiness: ScannerReadinessState,
        feedbackSettings: GeigerFeedbackSettings,
        controls: SavedControlsTuple,
        telemetry: ProximityTelemetry?,
    ): ProximityUiState {
        val sortedRadios = snapshot.radios
            .filter { MacAddressUtils.isValidMac(it.bssid) && !MacAddressUtils.isAndroidRedactedMac(it.bssid) }
            .distinctBy { MacAddressUtils.normalizeBssid(it.bssid) }
            .sortedWith(
                compareByDescending<BssidRadio> { it.isConnected }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }
            )

        val meshGroups = buildAvailableMeshGroups(snapshot, sortedRadios)
        val targetKey = controls.targetKey
        val lockMode = when (targetKey) {
            is ProximityTargetKey.FollowMeshSsid -> ProximityLockMode.FOLLOW_MESH_SSID
            else -> ProximityLockMode.SINGLE_ROUTER_BSSID
        }

        val (resolvedMeshSsid, canFollowMesh) = resolveMeshEligibility(
            targetKey = targetKey,
            availableRadios = sortedRadios,
        )

        val calibrationBssid: String? = when (targetKey) {
            is ProximityTargetKey.BssidLock ->
                MacAddressUtils.normalizeBssid(targetKey.bssid).takeIf(MacAddressUtils::isValidMac)
            is ProximityTargetKey.FollowMeshSsid ->
                (telemetry?.resolvedBssid ?: targetKey.preferredBssid)
                    ?.takeIf(MacAddressUtils::isValidMac)
                    ?.let(MacAddressUtils::normalizeBssid)
            null -> null
        }

        val hasVibrator = repository.feedbackController.hasHardwareVibrator
        val effectiveFeedback = if (hasVibrator) {
            feedbackSettings
        } else {
            feedbackSettings.copy(hapticsEnabled = false)
        }

        return ProximityUiState(
            readinessState = readiness,
            radioCapabilities = snapshot.radioCapabilities,
            scanTelemetry = telemetry?.scanTelemetry ?: snapshot.scanTelemetry,
            visibleNetworkCount = sortedRadios.size,
            activeTargetKey = targetKey,
            lockMode = lockMode,
            canFollowMeshSsid = canFollowMesh,
            resolvedMeshSsid = resolvedMeshSsid,
            calibrationTargetBssid = calibrationBssid,
            environment = controls.environment,
            telemetry = telemetry,
            availableRadios = sortedRadios,
            availableMeshGroups = meshGroups,
            isTargetPickerOpen = controls.isPickerOpen,
            feedbackSettings = effectiveFeedback,
            hasHardwareVibrator = hasVibrator,
            burstStatusFeedback = controls.burstFeedback,
            lastScanTriggerResult = controls.lastTriggerResult,
        )
    }

    fun selectTarget(targetKey: ProximityTargetKey) {
        val sanitized = when (targetKey) {
            is ProximityTargetKey.BssidLock -> {
                val norm = MacAddressUtils.normalizeBssid(targetKey.bssid)
                if (!MacAddressUtils.isValidMac(norm)) return
                val cleanHint = targetKey.ssidHint?.takeIf {
                    it.isNotBlank() &&
                        it != DecodedSsid.HIDDEN_NETWORK_LABEL &&
                        it != MacAddressUtils.UNKNOWN_SSID_SENTINEL
                }
                ProximityTargetKey.BssidLock(bssid = norm, ssidHint = cleanHint)
            }
            is ProximityTargetKey.FollowMeshSsid -> {
                val cleanSsid = targetKey.ssid.trim()
                if (cleanSsid.isEmpty() ||
                    cleanSsid == DecodedSsid.HIDDEN_NETWORK_LABEL ||
                    cleanSsid == MacAddressUtils.UNKNOWN_SSID_SENTINEL
                ) {
                    val fallbackMac = targetKey.preferredBssid
                        ?.takeIf(MacAddressUtils::isValidMac)
                        ?.let(MacAddressUtils::normalizeBssid)
                        ?: return
                    ProximityTargetKey.BssidLock(bssid = fallbackMac, ssidHint = null)
                } else {
                    val prefNorm = targetKey.preferredBssid
                        ?.takeIf(MacAddressUtils::isValidMac)
                        ?.let(MacAddressUtils::normalizeBssid)
                    ProximityTargetKey.FollowMeshSsid(ssid = cleanSsid, preferredBssid = prefNorm)
                }
            }
        }
        savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY] = sanitized.toSavedStateString()
        savedStateHandle[KEY_TARGET_PICKER_OPEN] = false
    }

    fun applyExternalTargetIfNew(externalTargetKey: ProximityTargetKey?): Boolean {
        if (externalTargetKey == null) {
            savedStateHandle[KEY_LAST_APPLIED_EXTERNAL_TARGET] = null
            return false
        }
        val encoded = externalTargetKey.toSavedStateString()
        val lastApplied = savedStateHandle.get<String>(KEY_LAST_APPLIED_EXTERNAL_TARGET)
        if (encoded != lastApplied) {
            savedStateHandle[KEY_LAST_APPLIED_EXTERNAL_TARGET] = encoded
            selectTarget(externalTargetKey)
            return true
        }
        return false
    }

    fun setLockMode(mode: ProximityLockMode) {
        val currentState = uiState.value
        val currentTarget = ProximityTargetKey.fromSavedStateString(
            savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY]
        ) ?: currentState.activeTargetKey ?: return
        val snapshotRadios = repository.radarSnapshot.value.radios
            .filter { MacAddressUtils.isValidMac(it.bssid) && !MacAddressUtils.isAndroidRedactedMac(it.bssid) }
        val (meshSsid, canFollowMesh) = resolveMeshEligibility(
            targetKey = currentTarget,
            availableRadios = snapshotRadios,
        )

        when (mode) {
            ProximityLockMode.FOLLOW_MESH_SSID -> {
                // Hidden SSID guard (F27): never allow Follow Mesh (SSID) on hidden networks.
                if (!canFollowMesh || meshSsid.isNullOrBlank()) {
                    return
                }
                val preferredMac = when (currentTarget) {
                    is ProximityTargetKey.BssidLock -> MacAddressUtils.normalizeBssid(currentTarget.bssid)
                    is ProximityTargetKey.FollowMeshSsid -> currentState.telemetry?.resolvedBssid ?: currentTarget.preferredBssid
                }?.takeIf(MacAddressUtils::isValidMac)
                savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY] =
                    ProximityTargetKey.FollowMeshSsid(
                        ssid = meshSsid,
                        preferredBssid = preferredMac,
                    ).toSavedStateString()
            }

            ProximityLockMode.SINGLE_ROUTER_BSSID -> {
                if (currentTarget is ProximityTargetKey.BssidLock) return
                val ssidTarget = currentTarget as? ProximityTargetKey.FollowMeshSsid ?: return
                val resolvedMac = (
                    currentState.telemetry?.resolvedBssid
                        ?: ssidTarget.preferredBssid
                        ?: snapshotRadios
                            .firstOrNull { !it.ssid.isHiddenOrUnnamed() && it.ssid.displayName == ssidTarget.ssid }
                            ?.bssid
                    )
                    ?.takeIf(MacAddressUtils::isValidMac)
                    ?.let(MacAddressUtils::normalizeBssid)
                    ?: return

                savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY] =
                    ProximityTargetKey.BssidLock(
                        bssid = resolvedMac,
                        ssidHint = ssidTarget.ssid,
                    ).toSavedStateString()
            }
        }
    }

    fun setEnvironment(environment: PropagationEnvironment) {
        savedStateHandle[SavedStateKeys.PROXIMITY_ENVIRONMENT] = environment.name
    }

    fun openTargetPicker() {
        savedStateHandle[KEY_TARGET_PICKER_OPEN] = true
    }

    fun dismissTargetPicker() {
        savedStateHandle[KEY_TARGET_PICKER_OPEN] = false
    }

    fun calibrateOneMeter() {
        val state = uiState.value
        val targetBssid = state.calibrationTargetBssid ?: return
        val telemetry = state.telemetry ?: return
        val smoothedDbm = telemetry.smoothedRssiDbm ?: return
        if (telemetry.lockStatus == SignalLockStatus.SIGNAL_LOST ||
            telemetry.lockStatus == SignalLockStatus.SEARCHING
        ) {
            return
        }
        viewModelScope.launch {
            repository.calibrateOneMeter(targetBssid, smoothedDbm)
        }
    }

    fun clearOneMeterCalibration() {
        val targetBssid = uiState.value.calibrationTargetBssid ?: return
        viewModelScope.launch {
            repository.clearOneMeterCalibration(targetBssid)
        }
    }

    fun triggerBurstScan() {
        val result = repository.requestManualScan(burstMode = true)
        val message = when (result) {
            is ScanTriggerResult.Triggered ->
                if (result.unthrottledMode) {
                    "Unthrottled burst sweep triggered"
                } else {
                    "Burst sweep triggered (${result.remainingTokens} active pulses left)"
                }
            is ScanTriggerResult.ThrottledByTokenBucket -> {
                val waitSec = ((result.nextTokenInMs + 999L) / 1000L).coerceAtLeast(1L)
                "OS scan quota recharging — next active sweep in ${waitSec}s (passive live)"
            }
            is ScanTriggerResult.RejectedByOs -> {
                val waitSec = ((result.nextRetryInMs + 999L) / 1000L).coerceAtLeast(1L)
                "OS deferred active scan — retrying in ${waitSec}s"
            }
            is ScanTriggerResult.BlockedByReadiness ->
                result.readinessState.headline
        }
        burstFeedbackState.value = message to result
    }

    fun resetStats() {
        val target = ProximityTargetKey.fromSavedStateString(
            savedStateHandle[SavedStateKeys.PROXIMITY_TARGET_KEY]
        ) ?: uiState.value.activeTargetKey
        repository.resetProximityStats(target)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        if (enabled && !repository.feedbackController.hasHardwareVibrator) {
            return
        }
        repository.feedbackController.setHapticsEnabled(enabled)
    }

    fun setAudioEnabled(enabled: Boolean) {
        repository.feedbackController.setAudioEnabled(enabled)
    }

    fun updateGeigerSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) {
        repository.feedbackController.updateSignal(smoothedRssiDbm, isSignalLost)
    }

    fun stopAllGeigerFeedback() {
        repository.feedbackController.stopAll()
    }

    fun onScreenResumed() {
        repository.refreshReadiness()
    }

    fun refreshReadiness() {
        repository.refreshReadiness()
    }

    fun reportPermissionDenial(permanentlyDenied: Boolean) {
        repository.reportPermissionDenial(permanentlyDenied)
    }

    override fun onCleared() {
        repository.feedbackController.stopAll()
        super.onCleared()
    }

    companion object {
        private const val KEY_TARGET_PICKER_OPEN = "proximity_target_picker_open"
        private const val KEY_LAST_APPLIED_EXTERNAL_TARGET = "proximity_last_external_target"

        fun factory(
            repository: WifiRadarRepository,
            initialTargetKey: ProximityTargetKey? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProximityViewModel(
                    repository = repository,
                    savedStateHandle = createSavedStateHandle(),
                    initialTargetKey = initialTargetKey,
                )
            }
        }

        private fun decodeEnvironment(raw: String): PropagationEnvironment =
            PropagationEnvironment.entries.firstOrNull { it.name == raw }
                ?: PropagationEnvironment.HOME_OFFICE

        internal fun resolveSmartFallbackTarget(snapshot: RadarSnapshot): ProximityTargetKey? {
            // Priority 1: Connected radio in snapshot.radios
            val connectedRadio = snapshot.radios.firstOrNull { radio ->
                radio.isConnected &&
                    MacAddressUtils.isValidMac(radio.bssid) &&
                    !MacAddressUtils.isAndroidRedactedMac(radio.bssid)
            }
            if (connectedRadio != null) {
                return ProximityTargetKey.BssidLock(
                    bssid = MacAddressUtils.normalizeBssid(connectedRadio.bssid),
                    ssidHint = connectedRadio.ssid.takeUnless { it.isHiddenOrUnnamed() }?.displayName,
                )
            }

            // Priority 2: ConnectedWifiObservation with valid non-redacted MAC
            val connectedObs = snapshot.connectedWifi
            if (connectedObs != null &&
                MacAddressUtils.isValidMac(connectedObs.bssid) &&
                !MacAddressUtils.isAndroidRedactedMac(connectedObs.bssid)
            ) {
                val cleanHint = connectedObs.ssid
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?.takeIf {
                        it.isNotEmpty() &&
                            it != MacAddressUtils.UNKNOWN_SSID_SENTINEL &&
                            it != DecodedSsid.HIDDEN_NETWORK_LABEL
                    }
                return ProximityTargetKey.BssidLock(
                    bssid = MacAddressUtils.normalizeBssid(connectedObs.bssid),
                    ssidHint = cleanHint,
                )
            }

            // Priority 3: Strongest visible radio (lexical BSSID tie-break)
            val strongestRadio = snapshot.radios
                .filter {
                    MacAddressUtils.isValidMac(it.bssid) &&
                        !MacAddressUtils.isAndroidRedactedMac(it.bssid)
                }
                .minWithOrNull(
                    compareByDescending<BssidRadio> { it.rssiDbm }
                        .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }
                )
            if (strongestRadio != null) {
                return ProximityTargetKey.BssidLock(
                    bssid = MacAddressUtils.normalizeBssid(strongestRadio.bssid),
                    ssidHint = strongestRadio.ssid.takeUnless { it.isHiddenOrUnnamed() }?.displayName,
                )
            }

            // Priority 4: Zero radios and disconnected -> calm empty state
            return null
        }

        private fun resolveMeshEligibility(
            targetKey: ProximityTargetKey?,
            availableRadios: List<BssidRadio>,
        ): Pair<String?, Boolean> = when (targetKey) {
            null -> null to false
            is ProximityTargetKey.FollowMeshSsid -> {
                val clean = targetKey.ssid.takeIf {
                    it.isNotBlank() &&
                        it != DecodedSsid.HIDDEN_NETWORK_LABEL &&
                        it != MacAddressUtils.UNKNOWN_SSID_SENTINEL
                }
                clean to (clean != null)
            }
            is ProximityTargetKey.BssidLock -> {
                val normTarget = MacAddressUtils.normalizeBssid(targetKey.bssid)
                val matched = availableRadios.firstOrNull {
                    MacAddressUtils.normalizeBssid(it.bssid) == normTarget
                }
                if (matched != null) {
                    if (matched.ssid.isHiddenOrUnnamed()) {
                        null to false
                    } else {
                        matched.ssid.displayName to true
                    }
                } else {
                    val hint = targetKey.ssidHint?.takeIf {
                        it.isNotBlank() &&
                            it != DecodedSsid.HIDDEN_NETWORK_LABEL &&
                            it != MacAddressUtils.UNKNOWN_SSID_SENTINEL
                    }
                    hint to (hint != null)
                }
            }
        }

        private fun buildAvailableMeshGroups(
            snapshot: RadarSnapshot,
            sortedRadios: List<BssidRadio>,
        ): List<EssNetworkGroup> {
            val fromSnapshot = snapshot.groupedNetworks
                .filter { group -> !group.ssid.isHiddenOrUnnamed() && group.radios.size >= 2 }
                .distinctBy { it.groupKey }
            if (fromSnapshot.isNotEmpty()) {
                return fromSnapshot.sortedWith(
                    compareByDescending<EssNetworkGroup> { it.containsConnectedRadio }
                        .thenByDescending { it.strongestRadio.rssiDbm }
                        .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }
                )
            }

            return sortedRadios
                .filterNot { it.ssid.isHiddenOrUnnamed() }
                .groupBy { "${it.ssid.displayName}:${it.security.securityFamily.name}" }
                .values
                .filter { it.size >= 2 }
                .map { radios ->
                    val strongest = radios.first()
                    val clusters = radios.map { it.physicalClusterId }.distinct()
                    EssNetworkGroup(
                        groupKey = "mesh:${strongest.ssid.displayName}",
                        ssid = strongest.ssid,
                        displayName = strongest.ssid.displayName,
                        isMeshOrMultiAp = true,
                        physicalClusterCount = clusters.size.coerceAtLeast(1),
                        radios = radios,
                        physicalClusterIds = clusters,
                        strongestRadio = strongest,
                        bands = radios.map { it.channelDescriptor.band }.toSet(),
                        maxWifiGeneration = radios.maxBy { it.wifiGeneration.rankOrder }.wifiGeneration,
                        worstSecurityAssessment = radios.minBy { it.security.score0To100 }.security,
                        bestSecurityAssessment = radios.maxBy { it.security.score0To100 }.security,
                        hasEvilTwinWarning = radios.any { it.security.isEvilTwinSuspect },
                        containsConnectedRadio = radios.any { it.isConnected },
                    )
                }
                .sortedWith(
                    compareByDescending<EssNetworkGroup> { it.containsConnectedRadio }
                        .thenByDescending { it.strongestRadio.rssiDbm }
                        .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }
                )
        }
    }
}
