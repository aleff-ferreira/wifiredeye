package com.a55.wifiradar.feature.spectrum

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.SavedStateKeys
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class SpectrumViewModel(
    private val repository: WifiRadarRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private data class SpectrumQuery(
        val band: WifiBand,
        val includeDfs: Boolean,
        val excludeOwnSsid: Boolean,
    )

    private data class SelectionSlice(
        val selectedBssid: String?,
        val highlightedChannel: Int?,
        val scanFeedback: String?,
    )

    private val selectedBandNameFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.SPECTRUM_SELECTED_BAND,
        initialValue = WifiBand.BAND_2_4_GHZ.name,
    )

    private val selectedBandFlow = selectedBandNameFlow
        .map(::parseSelectableBand)
        .distinctUntilChanged()

    private val includeDfsFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.SPECTRUM_INCLUDE_DFS,
        initialValue = false,
    )

    private val excludeOwnSsidFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.SPECTRUM_EXCLUDE_OWN_SSID,
        initialValue = false,
    )

    private val highlightedChannelFlow: StateFlow<Int?> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL,
        initialValue = null,
    )

    private val selectedBssidFlow: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = SAVED_KEY_SELECTED_BSSID,
        initialValue = null,
    )

    private val scanActionFeedbackFlow = MutableStateFlow<String?>(null)

    private val queryFlow = combine(
        selectedBandFlow,
        includeDfsFlow,
        excludeOwnSsidFlow,
    ) { band, includeDfs, excludeOwnSsid ->
        SpectrumQuery(
            band = band,
            includeDfs = includeDfs,
            excludeOwnSsid = excludeOwnSsid,
        )
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val analysisFlow = queryFlow.flatMapLatest { query ->
        repository.computeSpectrum(
            band = query.band,
            includeDfs = query.includeDfs,
            excludeOwnSsid = query.excludeOwnSsid,
        ).catch {
            val spec = SpectrumDomainResolver.axisSpecFor(query.band)
            emit(
                SpectrumAnalysis(
                    band = query.band,
                    includeDfs = query.includeDfs,
                    excludeConnectedSsid = query.excludeOwnSsid,
                    minFreqMhz = spec.canonicalMinFreqMhz,
                    maxFreqMhz = spec.canonicalMaxFreqMhz,
                    isBandSupportedByHardware = query.band != WifiBand.BAND_6_GHZ ||
                        repository.radarSnapshot.value.radioCapabilities.has6Ghz,
                )
            )
        }
    }

    private val selectionSliceFlow = combine(
        selectedBssidFlow,
        highlightedChannelFlow,
        scanActionFeedbackFlow,
    ) { selectedBssid, highlightedChannel, scanFeedback ->
        SelectionSlice(
            selectedBssid = selectedBssid,
            highlightedChannel = highlightedChannel,
            scanFeedback = scanFeedback,
        )
    }

    val uiState: StateFlow<SpectrumUiState> = combine(
        queryFlow,
        selectionSliceFlow,
        repository.radarSnapshot,
        repository.readinessState,
        analysisFlow,
    ) { query, selection, snapshot, readiness, analysis ->
        SpectrumDomainResolver.buildUiState(
            selectedBand = query.band,
            includeDfs = query.includeDfs,
            excludeConnectedSsid = query.excludeOwnSsid,
            selectedBssid = selection.selectedBssid,
            highlightedChannel = selection.highlightedChannel,
            snapshot = snapshot,
            readinessState = readiness,
            analysis = analysis,
            scanActionFeedback = selection.scanFeedback,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildInitialState(),
    )

    private fun buildInitialState(): SpectrumUiState {
        val band = parseSelectableBand(
            savedStateHandle.get<String>(SavedStateKeys.SPECTRUM_SELECTED_BAND)
        )
        val includeDfs = savedStateHandle.get<Boolean>(SavedStateKeys.SPECTRUM_INCLUDE_DFS) ?: false
        val excludeOwn = savedStateHandle.get<Boolean>(SavedStateKeys.SPECTRUM_EXCLUDE_OWN_SSID) ?: false
        val selectedBssid = savedStateHandle.get<String?>(SAVED_KEY_SELECTED_BSSID)
        val highlightedChannel = savedStateHandle.get<Int?>(SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL)
        return SpectrumDomainResolver.buildUiState(
            selectedBand = band,
            includeDfs = includeDfs,
            excludeConnectedSsid = excludeOwn,
            selectedBssid = selectedBssid,
            highlightedChannel = highlightedChannel,
            snapshot = repository.radarSnapshot.value,
            readinessState = repository.readinessState.value,
            analysis = null,
            scanActionFeedback = null,
        )
    }

    fun selectBand(band: WifiBand) {
        if (band !in SPECTRUM_SELECTABLE_BANDS) return
        val current = parseSelectableBand(savedStateHandle.get<String>(SavedStateKeys.SPECTRUM_SELECTED_BAND))
        if (band == current) return
        savedStateHandle[SavedStateKeys.SPECTRUM_SELECTED_BAND] = band.name
        savedStateHandle[SAVED_KEY_SELECTED_BSSID] = null
        savedStateHandle[SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL] = null
    }

    fun setIncludeDfs(enabled: Boolean) {
        savedStateHandle[SavedStateKeys.SPECTRUM_INCLUDE_DFS] = enabled
    }

    fun setExcludeConnectedSsid(enabled: Boolean) {
        savedStateHandle[SavedStateKeys.SPECTRUM_EXCLUDE_OWN_SSID] = enabled
    }

    fun selectBssid(bssid: String?) {
        val normalized = bssid?.let(MacAddressUtils::normalizeBssid)?.ifEmpty { null }
        val current = savedStateHandle.get<String?>(SAVED_KEY_SELECTED_BSSID)
            ?.let(MacAddressUtils::normalizeBssid)
            ?.ifEmpty { null }
        if (normalized == null || normalized == current) {
            savedStateHandle[SAVED_KEY_SELECTED_BSSID] = null
        } else {
            savedStateHandle[SAVED_KEY_SELECTED_BSSID] = normalized
            savedStateHandle[SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL] = null
        }
    }

    fun selectChannel(channel: Int?) {
        val current = savedStateHandle.get<Int?>(SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL)
        if (channel == null || channel == current) {
            savedStateHandle[SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL] = null
        } else {
            savedStateHandle[SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL] = channel
            savedStateHandle[SAVED_KEY_SELECTED_BSSID] = null
        }
    }

    fun applyExternalDeepLink(
        band: WifiBand,
        highlightChannel: Int? = null,
        requestNonce: Long = 0L,
    ) {
        if (band !in SPECTRUM_SELECTABLE_BANDS) return
        val signature = "$requestNonce:${band.name}:${highlightChannel ?: -1}"
        if (requestNonce > 0L && savedStateHandle.get<String>(SAVED_KEY_LAST_EXTERNAL_SIG) == signature) {
            return
        }
        if (requestNonce > 0L) {
            savedStateHandle[SAVED_KEY_LAST_EXTERNAL_SIG] = signature
        }
        savedStateHandle[SavedStateKeys.SPECTRUM_SELECTED_BAND] = band.name
        savedStateHandle[SAVED_KEY_SELECTED_BSSID] = null
        savedStateHandle[SavedStateKeys.SPECTRUM_HIGHLIGHT_CHANNEL] = highlightChannel
    }

    fun requestManualScan(): ScanTriggerResult {
        val result = runCatching {
            repository.requestManualScan(burstMode = false)
        }.getOrElse {
            ScanTriggerResult.RejectedByOs(nextRetryInMs = 5_000L)
        }
        scanActionFeedbackFlow.value = when (result) {
            is ScanTriggerResult.Triggered -> if (result.unthrottledMode) {
                "Active Wi-Fi sweep triggered · Unthrottled live mode"
            } else {
                "Active Wi-Fi sweep triggered · ${result.remainingTokens} burst pulses left"
            }
            is ScanTriggerResult.ThrottledByTokenBucket -> {
                val waitSec = ((result.nextTokenInMs + 999L) / 1000L).coerceAtLeast(1L)
                "Android battery guard active · Next active sweep in ${waitSec}s (passive live)"
            }
            is ScanTriggerResult.RejectedByOs -> {
                val waitSec = ((result.nextRetryInMs + 999L) / 1000L).coerceAtLeast(1L)
                "System busy · Retrying sweep in ${waitSec}s (passive listening active)"
            }
            is ScanTriggerResult.BlockedByReadiness ->
                result.readinessState.headline
        }
        return result
    }

    fun dismissScanFeedback() {
        scanActionFeedbackFlow.value = null
    }

    fun refreshReadiness() {
        runCatching { repository.refreshReadiness() }
    }

    fun reportPermissionDenial(permanentlyDenied: Boolean) {
        runCatching { repository.reportPermissionDenial(permanentlyDenied) }
    }

    companion object {
        const val SAVED_KEY_SELECTED_BSSID = "spectrum_selected_bssid"
        private const val SAVED_KEY_LAST_EXTERNAL_SIG = "spectrum_last_external_sig"

        internal fun parseSelectableBand(raw: String?): WifiBand {
            val parsed = WifiBand.entries.firstOrNull { it.name == raw }
            return if (parsed != null && parsed in SPECTRUM_SELECTABLE_BANDS) {
                parsed
            } else {
                WifiBand.BAND_2_4_GHZ
            }
        }

        fun provideFactory(
            repository: WifiRadarRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SpectrumViewModel(
                    repository = repository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }
}
