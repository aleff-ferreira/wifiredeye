package com.a55.wifiradar.feature.networks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SavedStateKeys
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class NetworksViewModel(
    private val repository: WifiRadarRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val searchQueryFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.NETWORK_SEARCH_QUERY,
        initialValue = "",
    )

    private val viewModeRawFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.NETWORK_VIEW_MODE,
        initialValue = NetworkViewMode.GROUPED_MESH.name,
    )

    private val activeFiltersRawFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.NETWORK_ACTIVE_FILTERS,
        initialValue = NetworkFilterChip.ALL.name,
    )

    private val sortOptionRawFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.NETWORK_SORT_OPTION,
        initialValue = NetworkSortOption.STRONGEST_SIGNAL.name,
    )

    private val selectedDetailBssidFlow: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.SELECTED_DETAIL_BSSID,
        initialValue = null,
    )

    private val expandedGroupKeysRawFlow: StateFlow<String> = savedStateHandle.getStateFlow(
        key = EXPANDED_GROUP_KEYS,
        initialValue = "[]",
    )

    private val lastScanFeedbackFlow = MutableStateFlow<String?>(null)

    private val cacheLock = Any()
    private val lastSeenRadioByBssid = object : LinkedHashMap<String, BssidRadio>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BssidRadio>?): Boolean =
            size > MAX_CACHED_RADIOS
    }

    private data class FilterTuple(
        val searchQuery: String,
        val viewMode: NetworkViewMode,
        val activeFilters: Set<NetworkFilterChip>,
        val sortOption: NetworkSortOption,
    )

    private data class SelectionTuple(
        val selectedDetailBssid: String?,
        val expandedGroupKeys: Set<String>,
        val lastScanFeedback: String?,
    )

    private val filterTupleFlow = combine(
        searchQueryFlow,
        viewModeRawFlow,
        activeFiltersRawFlow,
        sortOptionRawFlow,
    ) { query, viewModeRaw, filtersRaw, sortRaw ->
        FilterTuple(
            searchQuery = query,
            viewMode = decodeViewMode(viewModeRaw),
            activeFilters = decodeActiveFilters(filtersRaw),
            sortOption = decodeSortOption(sortRaw),
        )
    }

    private val selectionTupleFlow = combine(
        selectedDetailBssidFlow,
        expandedGroupKeysRawFlow,
        lastScanFeedbackFlow,
    ) { detailBssid, expandedRaw, scanFeedback ->
        SelectionTuple(
            selectedDetailBssid = detailBssid
                ?.takeIf { MacAddressUtils.isValidMac(it) }
                ?.let(MacAddressUtils::normalizeBssid),
            expandedGroupKeys = decodeExpandedGroupKeys(expandedRaw),
            lastScanFeedback = scanFeedback,
        )
    }

    val uiState: StateFlow<NetworksUiState> = combine(
        repository.radarSnapshot,
        repository.readinessState,
        filterTupleFlow,
        selectionTupleFlow,
    ) { snapshot, readiness, filters, selections ->
        buildUiState(
            snapshot = snapshot,
            readiness = readiness,
            filters = filters,
            selections = selections,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildUiState(
            snapshot = repository.radarSnapshot.value,
            readiness = repository.readinessState.value,
            filters = FilterTuple(
                searchQuery = searchQueryFlow.value,
                viewMode = decodeViewMode(viewModeRawFlow.value),
                activeFilters = decodeActiveFilters(activeFiltersRawFlow.value),
                sortOption = decodeSortOption(sortOptionRawFlow.value),
            ),
            selections = SelectionTuple(
                selectedDetailBssid = selectedDetailBssidFlow.value
                    ?.takeIf { MacAddressUtils.isValidMac(it) }
                    ?.let(MacAddressUtils::normalizeBssid),
                expandedGroupKeys = decodeExpandedGroupKeys(expandedGroupKeysRawFlow.value),
                lastScanFeedback = lastScanFeedbackFlow.value,
            ),
        ),
    )

    private fun buildUiState(
        snapshot: RadarSnapshot,
        readiness: ScannerReadinessState,
        filters: FilterTuple,
        selections: SelectionTuple,
    ): NetworksUiState {
        synchronized(cacheLock) {
            for (radio in snapshot.radios) {
                val norm = MacAddressUtils.normalizeBssid(radio.bssid)
                if (MacAddressUtils.isValidMac(norm)) {
                    lastSeenRadioByBssid[norm] = radio
                }
            }
        }

        val projected = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = filters.searchQuery,
            activeFilters = filters.activeFilters,
            sortOption = filters.sortOption,
        )

        val detailSheetState = selections.selectedDetailBssid?.let { targetBssid ->
            val liveRadio = snapshot.radios.firstOrNull {
                MacAddressUtils.normalizeBssid(it.bssid) == targetBssid
            }
            val cachedRadio = synchronized(cacheLock) { lastSeenRadioByBssid[targetBssid] }
            when {
                liveRadio != null -> NetworkDetailSheetState(
                    requestedBssid = targetBssid,
                    radio = liveRadio,
                    isCurrentlyInRange = true,
                    dropoutAgeMs = liveRadio.ageMs,
                )
                cachedRadio != null -> {
                    val computedAge = (snapshot.timestampElapsedMs - cachedRadio.lastSeenElapsedMs)
                        .coerceAtLeast(cachedRadio.ageMs)
                        .coerceAtLeast(1_000L)
                    NetworkDetailSheetState(
                        requestedBssid = targetBssid,
                        radio = cachedRadio,
                        isCurrentlyInRange = false,
                        dropoutAgeMs = computedAge,
                    )
                }
                else -> NetworkDetailSheetState(
                    requestedBssid = targetBssid,
                    radio = null,
                    isCurrentlyInRange = false,
                    dropoutAgeMs = 0L,
                )
            }
        }

        val cleanConnectedWifi = snapshot.connectedWifi?.takeUnless {
            MacAddressUtils.isAndroidRedactedMac(it.bssid) || !MacAddressUtils.isValidMac(it.bssid)
        }

        return NetworksUiState(
            readinessState = readiness,
            scanTelemetry = snapshot.scanTelemetry,
            radioCapabilities = snapshot.radioCapabilities,
            connectedWifi = cleanConnectedWifi,
            connectedRadio = projected.connectedRadio,
            viewMode = filters.viewMode,
            searchQuery = filters.searchQuery,
            activeFilters = filters.activeFilters,
            sortOption = filters.sortOption,
            expandedGroupKeys = selections.expandedGroupKeys,
            totalUnfilteredRadioCount = projected.totalUnfilteredRadioCount,
            visibleRadioCount = projected.visibleRadioCount,
            groupedItems = projected.groupedItems,
            flatRadioItems = projected.flatRadioItems,
            showSixGhzHardwareBanner = projected.showSixGhzHardwareBanner,
            rnr6GhzNeighbors = projected.rnr6GhzNeighbors,
            detailSheet = detailSheetState,
            lastScanFeedbackMessage = selections.lastScanFeedback,
        )
    }

    fun setSearchQuery(query: String) {
        savedStateHandle[SavedStateKeys.NETWORK_SEARCH_QUERY] = query
    }

    fun setViewMode(mode: NetworkViewMode) {
        savedStateHandle[SavedStateKeys.NETWORK_VIEW_MODE] = mode.name
    }

    fun toggleFilterChip(chip: NetworkFilterChip) {
        val current = decodeActiveFilters(activeFiltersRawFlow.value)
        val next = NetworksProjector.toggleFilterSelection(current, chip)
        savedStateHandle[SavedStateKeys.NETWORK_ACTIVE_FILTERS] =
            next.joinToString(",") { it.name }
    }

    fun setSortOption(option: NetworkSortOption) {
        savedStateHandle[SavedStateKeys.NETWORK_SORT_OPTION] = option.name
    }

    fun resetFilters() {
        savedStateHandle[SavedStateKeys.NETWORK_SEARCH_QUERY] = ""
        savedStateHandle[SavedStateKeys.NETWORK_ACTIVE_FILTERS] = NetworkFilterChip.ALL.name
    }

    fun toggleGroupExpanded(groupKey: String) {
        val current = decodeExpandedGroupKeys(expandedGroupKeysRawFlow.value)
        val next = if (groupKey in current) current - groupKey else current + groupKey
        savedStateHandle[EXPANDED_GROUP_KEYS] = Json.encodeToString(next.toList())
    }

    fun openDetailSheet(bssid: String) {
        val normalized = MacAddressUtils.normalizeBssid(bssid)
        if (MacAddressUtils.isValidMac(normalized)) {
            savedStateHandle[SavedStateKeys.SELECTED_DETAIL_BSSID] = normalized
        }
    }

    fun dismissDetailSheet() {
        savedStateHandle[SavedStateKeys.SELECTED_DETAIL_BSSID] = null
    }

    fun saveBssidNickname(bssid: String, nickname: String?) {
        val normalized = MacAddressUtils.normalizeBssid(bssid)
        if (!MacAddressUtils.isValidMac(normalized)) return
        val cleaned = nickname?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(cacheLock) {
            val cached = lastSeenRadioByBssid[normalized]
            if (cached != null) {
                val fallbackSsid = if (cached.ssid.isEffectivelyHidden()) {
                    com.a55.wifiradar.core.model.DecodedSsid.HIDDEN_NETWORK_LABEL
                } else {
                    cached.ssid.displayName
                }
                lastSeenRadioByBssid[normalized] = cached.copy(
                    customNickname = cleaned,
                    effectiveDisplayName = cleaned ?: fallbackSsid,
                )
            }
        }
        viewModelScope.launch {
            repository.setBssidNickname(normalized, cleaned)
        }
    }

    fun requestManualScan() {
        val result = repository.requestManualScan(burstMode = false)
        lastScanFeedbackFlow.value = when (result) {
            is ScanTriggerResult.Triggered ->
                if (result.unthrottledMode) {
                    "Unthrottled active scan sweep triggered"
                } else {
                    "Active scan triggered (${result.remainingTokens} active sweeps left in 2m window)"
                }
            is ScanTriggerResult.ThrottledByTokenBucket -> {
                val waitSec = ((result.nextTokenInMs + 999L) / 1000L).coerceAtLeast(1L)
                "OS scan quota recharging — next active sweep in ${waitSec}s (passive scans still live)"
            }
            is ScanTriggerResult.RejectedByOs -> {
                val waitSec = ((result.nextRetryInMs + 999L) / 1000L).coerceAtLeast(1L)
                "OS deferred active sweep — retrying in ${waitSec}s"
            }
            is ScanTriggerResult.BlockedByReadiness ->
                result.readinessState.headline
        }
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

    companion object {
        internal const val EXPANDED_GROUP_KEYS = "network_expanded_group_keys"
        private const val MAX_CACHED_RADIOS = 256

        fun factory(repository: WifiRadarRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                NetworksViewModel(
                    repository = repository,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }

        private fun decodeViewMode(raw: String): NetworkViewMode =
            NetworkViewMode.entries.firstOrNull { it.name == raw } ?: NetworkViewMode.GROUPED_MESH

        private fun decodeSortOption(raw: String): NetworkSortOption =
            NetworkSortOption.entries.firstOrNull { it.name == raw } ?: NetworkSortOption.STRONGEST_SIGNAL

        private fun decodeActiveFilters(raw: String): Set<NetworkFilterChip> {
            if (raw.isBlank()) return setOf(NetworkFilterChip.ALL)
            val parsed = raw.split(',')
                .mapNotNull { token ->
                    val trimmed = token.trim()
                    NetworkFilterChip.entries.firstOrNull { it.name == trimmed }
                }
                .toSet()
            return when {
                parsed.isEmpty() -> setOf(NetworkFilterChip.ALL)
                parsed.size > 1 && NetworkFilterChip.ALL in parsed -> parsed - NetworkFilterChip.ALL
                else -> parsed
            }
        }

        private fun decodeExpandedGroupKeys(raw: String): Set<String> =
            runCatching { Json.decodeFromString<List<String>>(raw).toSet() }
                .getOrDefault(emptySet())
    }
}
