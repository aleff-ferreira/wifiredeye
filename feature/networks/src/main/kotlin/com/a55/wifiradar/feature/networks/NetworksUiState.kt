package com.a55.wifiradar.feature.networks

import androidx.compose.runtime.Immutable
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScannerReadinessState

@Immutable
data class Colocated6GhzRnrItem(
    val key: String,
    val neighbor: Colocated6GhzNeighbor,
    val reportingRadioDisplayName: String,
    val reportingRadioBssid: String,
    val reportingRadioRssiDbm: Int,
)

@Immutable
data class NetworkDetailSheetState(
    val requestedBssid: String,
    val radio: BssidRadio?,
    val isCurrentlyInRange: Boolean,
    val dropoutAgeMs: Long = 0L,
)

@Immutable
data class NetworksUiState(
    val readinessState: ScannerReadinessState = ScannerReadinessState.Ready(),
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
    val radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    val connectedWifi: ConnectedWifiObservation? = null,
    val connectedRadio: BssidRadio? = null,
    val viewMode: NetworkViewMode = NetworkViewMode.GROUPED_MESH,
    val searchQuery: String = "",
    val activeFilters: Set<NetworkFilterChip> = setOf(NetworkFilterChip.ALL),
    val sortOption: NetworkSortOption = NetworkSortOption.STRONGEST_SIGNAL,
    val expandedGroupKeys: Set<String> = emptySet(),
    val totalUnfilteredRadioCount: Int = 0,
    val visibleRadioCount: Int = 0,
    val groupedItems: List<EssNetworkGroup> = emptyList(),
    val flatRadioItems: List<BssidRadio> = emptyList(),
    val showSixGhzHardwareBanner: Boolean = false,
    val rnr6GhzNeighbors: List<Colocated6GhzRnrItem> = emptyList(),
    val detailSheet: NetworkDetailSheetState? = null,
    val lastScanFeedbackMessage: String? = null,
) {
    val hasNonDefaultSearchOrFilter: Boolean
        get() = searchQuery.isNotBlank() ||
            (activeFilters.isNotEmpty() && activeFilters != setOf(NetworkFilterChip.ALL))
}
