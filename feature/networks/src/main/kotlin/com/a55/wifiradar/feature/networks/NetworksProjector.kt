package com.a55.wifiradar.feature.networks

import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.WifiBand
import java.util.Locale

internal fun DecodedSsid.isEffectivelyHidden(): Boolean =
    isHidden || displayName.isBlank() || displayName == DecodedSsid.HIDDEN_NETWORK_LABEL

internal fun DecodedSsid.nonHiddenSsidOrNull(): String? =
    if (isEffectivelyHidden()) null else displayName

internal data class ProjectedNetworksResult(
    val totalUnfilteredRadioCount: Int,
    val visibleRadioCount: Int,
    val flatRadioItems: List<BssidRadio>,
    val groupedItems: List<EssNetworkGroup>,
    val connectedRadio: BssidRadio?,
    val showSixGhzHardwareBanner: Boolean,
    val rnr6GhzNeighbors: List<Colocated6GhzRnrItem>,
)

internal object NetworksProjector {

    private val BAND_FILTER_CHIPS = setOf(
        NetworkFilterChip.BAND_2_4_GHZ,
        NetworkFilterChip.BAND_5_GHZ,
        NetworkFilterChip.BAND_6_GHZ,
    )

    fun toggleFilterSelection(
        current: Set<NetworkFilterChip>,
        toggled: NetworkFilterChip,
    ): Set<NetworkFilterChip> {
        if (toggled == NetworkFilterChip.ALL) {
            return setOf(NetworkFilterChip.ALL)
        }
        val withoutAll = current - NetworkFilterChip.ALL
        val updated = if (toggled in withoutAll) {
            withoutAll - toggled
        } else {
            withoutAll + toggled
        }
        return updated.ifEmpty { setOf(NetworkFilterChip.ALL) }
    }

    fun project(
        snapshot: RadarSnapshot,
        searchQuery: String,
        activeFilters: Set<NetworkFilterChip>,
        sortOption: NetworkSortOption,
    ): ProjectedNetworksResult {
        val deduplicatedRadios = snapshot.radios.distinctBy {
            MacAddressUtils.normalizeBssid(it.bssid)
        }
        val normalizedFilters = activeFilters.ifEmpty { setOf(NetworkFilterChip.ALL) }
        val tokens = tokenizeSearchQuery(searchQuery)

        val matchingRadios = deduplicatedRadios.filter { radio ->
            matchesSearchTokens(radio, tokens) && matchesFilters(radio, normalizedFilters)
        }

        val sortedFlatRadios = matchingRadios.sortedWith(radioComparator(sortOption))
        val groupedItems = buildGroupedItems(
            snapshotGroupedNetworks = snapshot.groupedNetworks,
            matchingRadios = matchingRadios,
            sortOption = sortOption,
        )

        val connectedBssidNorm = snapshot.connectedWifi
            ?.bssid
            ?.takeIf { MacAddressUtils.isValidMac(it) && !MacAddressUtils.isAndroidRedactedMac(it) }
            ?.let(MacAddressUtils::normalizeBssid)

        val connectedRadio = deduplicatedRadios.firstOrNull { radio ->
            radio.isConnected ||
                (connectedBssidNorm != null && MacAddressUtils.normalizeBssid(radio.bssid) == connectedBssidNorm)
        }

        val showSixGhzHardwareBanner =
            (NetworkFilterChip.BAND_6_GHZ in normalizedFilters) && !snapshot.radioCapabilities.has6Ghz

        val rnrNeighbors = deduplicatedRadios
            .flatMap { radio ->
                val normSource = MacAddressUtils.normalizeBssid(radio.bssid)
                radio.beaconDetails.colocated6GhzNeighbors.map { neighbor ->
                    val normNeighbor = neighbor.neighborBssid
                        ?.takeIf { it.isNotBlank() }
                        ?.let(MacAddressUtils::normalizeBssid)
                        .orEmpty()
                    Colocated6GhzRnrItem(
                        key = "$normSource:${neighbor.operatingClass}:${neighbor.channelNumber}:$normNeighbor",
                        neighbor = neighbor,
                        reportingRadioDisplayName = radio.effectiveDisplayName.ifBlank {
                            if (radio.ssid.isEffectivelyHidden()) {
                                DecodedSsid.HIDDEN_NETWORK_LABEL
                            } else {
                                radio.ssid.displayName
                            }
                        },
                        reportingRadioBssid = normSource,
                        reportingRadioRssiDbm = radio.rssiDbm,
                    )
                }
            }
            .distinctBy { it.key }
            .sortedWith(
                compareByDescending<Colocated6GhzRnrItem> { it.reportingRadioRssiDbm }
                    .thenBy { it.neighbor.channelNumber }
                    .thenBy { it.reportingRadioBssid }
            )

        return ProjectedNetworksResult(
            totalUnfilteredRadioCount = deduplicatedRadios.size,
            visibleRadioCount = sortedFlatRadios.size,
            flatRadioItems = sortedFlatRadios,
            groupedItems = groupedItems,
            connectedRadio = connectedRadio,
            showSixGhzHardwareBanner = showSixGhzHardwareBanner,
            rnr6GhzNeighbors = rnrNeighbors,
        )
    }

    internal fun tokenizeSearchQuery(query: String): List<String> {
        val trimmed = query.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    internal fun matchesSearch(radio: BssidRadio, query: String): Boolean =
        matchesSearchTokens(radio, tokenizeSearchQuery(query))

    private fun matchesSearchTokens(radio: BssidRadio, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val corpus = buildSearchableCorpus(radio)
        return tokens.all { token -> corpus.contains(token) }
    }

    private fun buildSearchableCorpus(radio: BssidRadio): String {
        val normBssid = MacAddressUtils.normalizeBssid(radio.bssid)
        val bareBssid = normBssid.replace(":", "")
        val ch = radio.channelDescriptor
        val wps = radio.beaconDetails.wpsDevice
        return buildString(320) {
            append(radio.effectiveDisplayName).append(' ')
            append(radio.ssid.displayName).append(' ')
            if (radio.ssid.isEffectivelyHidden()) {
                append(DecodedSsid.HIDDEN_NETWORK_LABEL).append(' ')
                append("hidden").append(' ')
            }
            radio.ssid.rawUtf8OrNull?.let { append(it).append(' ') }
            radio.ssid.rawHex?.let { append(it).append(' ') }
            radio.customNickname?.let { append(it).append(' ') }
            append(normBssid).append(' ')
            append(bareBssid).append(' ')
            append(radio.vendorInfo.displayLabel).append(' ')
            radio.vendorInfo.vendorName?.let { append(it).append(' ') }
            radio.vendorInfo.inferredParentVendor?.let { append(it).append(' ') }
            radio.vendorInfo.ouiPrefix?.let {
                append(it).append(' ')
                append(it.replace(":", "")).append(' ')
            }
            if (radio.vendorInfo.isLocallyAdministered) {
                append("virtual randomized laa ").append(' ')
            }
            wps?.manufacturer?.let { append(it).append(' ') }
            wps?.modelName?.let { append(it).append(' ') }
            wps?.modelNumber?.let { append(it).append(' ') }
            wps?.deviceName?.let { append(it).append(' ') }
            wps?.advertisedApHostname?.let { append(it).append(' ') }
            append("ch").append(ch.primaryChannel).append(' ')
            append("ch ").append(ch.primaryChannel).append(' ')
            append("channel ").append(ch.primaryChannel).append(' ')
            append(ch.primaryFreqMhz).append(" mhz ")
            append(ch.band.displayLabel).append(' ')
            append(ch.band.traitLabel).append(' ')
            append(ch.width.displayLabel).append(' ')
            append(ch.uniiSubBand.displayLabel).append(' ')
            if (ch.isDfs) append("dfs ")
            if (ch.isTdwr) append("tdwr ")
            if (ch.isPsc) append("psc ")
            append(radio.wifiGeneration.shortBadge).append(' ')
            append(radio.wifiGeneration.ieeeAmendment).append(' ')
            append(radio.security.grade.badgeText).append(' ')
            append(radio.security.protocol.displayLabel).append(' ')
            append(radio.security.plainEnglishLabel).append(' ')
            if (radio.security.isEvilTwinSuspect) append("evil twin clone ")
        }.lowercase(Locale.US)
    }

    internal fun matchesFilters(
        radio: BssidRadio,
        activeFilters: Set<NetworkFilterChip>,
    ): Boolean {
        if (activeFilters.isEmpty() || NetworkFilterChip.ALL in activeFilters) {
            return true
        }

        val selectedBandChips = activeFilters intersect BAND_FILTER_CHIPS
        if (selectedBandChips.isNotEmpty()) {
            val matchesBand = selectedBandChips.any { chip ->
                when (chip) {
                    NetworkFilterChip.BAND_2_4_GHZ -> radio.channelDescriptor.band == WifiBand.BAND_2_4_GHZ
                    NetworkFilterChip.BAND_5_GHZ -> radio.channelDescriptor.band == WifiBand.BAND_5_GHZ
                    NetworkFilterChip.BAND_6_GHZ -> radio.channelDescriptor.band == WifiBand.BAND_6_GHZ
                    else -> false
                }
            }
            if (!matchesBand) return false
        }

        if (NetworkFilterChip.OPEN_OR_WEAK_SECURITY in activeFilters) {
            val isWeak = radio.security.grade.isWeakOrInsecure || radio.security.isEvilTwinSuspect
            if (!isWeak) return false
        }

        if (NetworkFilterChip.WIFI_6_PLUS in activeFilters) {
            if (!radio.wifiGeneration.isWifi6OrNewer) return false
        }

        if (NetworkFilterChip.STRONG_SIGNAL in activeFilters) {
            if (radio.rssiDbm < -65) return false
        }

        return true
    }

    internal fun radioComparator(sortOption: NetworkSortOption): Comparator<BssidRadio> =
        when (sortOption) {
            NetworkSortOption.STRONGEST_SIGNAL ->
                compareByDescending<BssidRadio> { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }

            NetworkSortOption.NAME_A_TO_Z ->
                compareBy<BssidRadio> { it.ssid.isEffectivelyHidden() && it.customNickname.isNullOrBlank() }
                    .thenBy { radioDisplayTitle(it).lowercase(Locale.US) }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }

            NetworkSortOption.CHANNEL_LOW_TO_HIGH ->
                compareBy<BssidRadio> { it.channelDescriptor.primaryFreqMhz }
                    .thenBy { it.channelDescriptor.primaryChannel }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }

            NetworkSortOption.LEAST_CONGESTED_CHANNEL ->
                compareByDescending<BssidRadio> { it.channelCongestionScore0To100 }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }

            NetworkSortOption.SECURITY_RISK ->
                compareBy<BssidRadio> { it.security.score0To100 }
                    .thenByDescending { it.security.isEvilTwinSuspect }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }
        }

    internal fun groupComparator(sortOption: NetworkSortOption): Comparator<EssNetworkGroup> =
        when (sortOption) {
            NetworkSortOption.STRONGEST_SIGNAL ->
                compareByDescending<EssNetworkGroup> { it.strongestRadio.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }

            NetworkSortOption.NAME_A_TO_Z ->
                compareBy<EssNetworkGroup> {
                    it.ssid.isEffectivelyHidden() &&
                        it.radios.all { r -> r.customNickname.isNullOrBlank() }
                }
                    .thenBy { it.displayName.lowercase(Locale.US) }
                    .thenByDescending { it.strongestRadio.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }

            NetworkSortOption.CHANNEL_LOW_TO_HIGH ->
                compareBy<EssNetworkGroup> { group ->
                    group.radios.minOfOrNull { it.channelDescriptor.primaryFreqMhz } ?: Int.MAX_VALUE
                }
                    .thenBy { group ->
                        group.radios.minOfOrNull { it.channelDescriptor.primaryChannel } ?: Int.MAX_VALUE
                    }
                    .thenByDescending { it.strongestRadio.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }

            NetworkSortOption.LEAST_CONGESTED_CHANNEL ->
                compareByDescending<EssNetworkGroup> { group ->
                    group.radios.maxOfOrNull { it.channelCongestionScore0To100 } ?: 0
                }
                    .thenByDescending { it.strongestRadio.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }

            NetworkSortOption.SECURITY_RISK ->
                compareBy<EssNetworkGroup> { it.worstSecurityAssessment.score0To100 }
                    .thenByDescending { it.hasEvilTwinWarning || it.worstSecurityAssessment.isEvilTwinSuspect }
                    .thenByDescending { it.strongestRadio.rssiDbm }
                    .thenBy { MacAddressUtils.normalizeBssid(it.strongestRadio.bssid) }
        }

    private fun buildGroupedItems(
        snapshotGroupedNetworks: List<EssNetworkGroup>,
        matchingRadios: List<BssidRadio>,
        sortOption: NetworkSortOption,
    ): List<EssNetworkGroup> {
        if (matchingRadios.isEmpty()) return emptyList()

        val hiddenRadios = matchingRadios.filter { it.ssid.isEffectivelyHidden() }
        val nonHiddenRadios = matchingRadios.filterNot { it.ssid.isEffectivelyHidden() }

        // Mandatory F06/F08 invariant: Hidden SSIDs NEVER merge together in Grouped mode.
        val hiddenGroups = hiddenRadios.map { radio ->
            val normBssid = MacAddressUtils.normalizeBssid(radio.bssid)
            EssNetworkGroup(
                groupKey = "hidden:$normBssid",
                ssid = radio.ssid.copy(
                    displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                    isHidden = true,
                ),
                displayName = radioDisplayTitle(radio),
                isMeshOrMultiAp = false,
                physicalClusterCount = 1,
                radios = listOf(radio),
                physicalClusterIds = listOf(radio.physicalClusterId),
                strongestRadio = radio,
                bands = setOf(radio.channelDescriptor.band),
                maxWifiGeneration = radio.wifiGeneration,
                worstSecurityAssessment = radio.security,
                bestSecurityAssessment = radio.security,
                hasEvilTwinWarning = radio.security.isEvilTwinSuspect,
                containsConnectedRadio = radio.isConnected,
            )
        }

        val nonHiddenByBssid = nonHiddenRadios.associateBy { MacAddressUtils.normalizeBssid(it.bssid) }
        val assignedBssids = HashSet<String>()
        val projectedNonHiddenGroups = ArrayList<EssNetworkGroup>()
        val internalRadioComp = radioComparator(sortOption)
        val strongestComp = radioComparator(NetworkSortOption.STRONGEST_SIGNAL)

        if (snapshotGroupedNetworks.isNotEmpty()) {
            for (sourceGroup in snapshotGroupedNetworks) {
                if (sourceGroup.ssid.isEffectivelyHidden()) {
                    continue
                }
                val groupRadios = sourceGroup.radios
                    .mapNotNull { r -> nonHiddenByBssid[MacAddressUtils.normalizeBssid(r.bssid)] }
                    .distinctBy { MacAddressUtils.normalizeBssid(it.bssid) }
                if (groupRadios.isEmpty()) continue

                groupRadios.forEach { r -> assignedBssids.add(MacAddressUtils.normalizeBssid(r.bssid)) }
                val sortedGroupRadios = groupRadios.sortedWith(internalRadioComp)
                val strongest = groupRadios.sortedWith(strongestComp).first()
                val clusterIds = sortedGroupRadios.map { it.physicalClusterId }.distinct()
                val worstSecurity = sortedGroupRadios.minWith(
                    compareBy<BssidRadio> { it.security.score0To100 }
                        .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }
                ).security
                val bestSecurity = sortedGroupRadios.maxWith(
                    compareBy<BssidRadio> { it.security.score0To100 }
                        .thenByDescending { MacAddressUtils.normalizeBssid(it.bssid) }
                ).security
                val maxGen = sortedGroupRadios.maxBy { it.wifiGeneration.rankOrder }.wifiGeneration
                val isMeshOrMulti = clusterIds.size >= 2 ||
                    (sortedGroupRadios.size >= 2 &&
                        sortedGroupRadios.map { it.channelDescriptor.band }.distinct().size < sortedGroupRadios.size)

                projectedNonHiddenGroups.add(
                    sourceGroup.copy(
                        displayName = sourceGroup.displayName.ifBlank { strongest.ssid.displayName },
                        isMeshOrMultiAp = sourceGroup.isMeshOrMultiAp || isMeshOrMulti,
                        physicalClusterCount = clusterIds.size.coerceAtLeast(1),
                        radios = sortedGroupRadios,
                        physicalClusterIds = clusterIds,
                        strongestRadio = strongest,
                        bands = sortedGroupRadios.map { it.channelDescriptor.band }.toSet(),
                        maxWifiGeneration = maxGen,
                        worstSecurityAssessment = worstSecurity,
                        bestSecurityAssessment = bestSecurity,
                        hasEvilTwinWarning = sourceGroup.hasEvilTwinWarning ||
                            sortedGroupRadios.any { it.security.isEvilTwinSuspect },
                        containsConnectedRadio = sortedGroupRadios.any { it.isConnected },
                    )
                )
            }
        }

        val unassignedNonHidden = nonHiddenRadios.filter {
            MacAddressUtils.normalizeBssid(it.bssid) !in assignedBssids
        }
        if (unassignedNonHidden.isNotEmpty()) {
            val fallbackBuckets = unassignedNonHidden.groupBy { radio ->
                "ess:${radio.ssid.displayName}:${radio.security.securityFamily.name}"
            }
            for ((bucketKey, bucketRadios) in fallbackBuckets) {
                val sortedGroupRadios = bucketRadios.sortedWith(internalRadioComp)
                val strongest = bucketRadios.sortedWith(strongestComp).first()
                val clusterIds = sortedGroupRadios.map { it.physicalClusterId }.distinct()
                val worstSecurity = sortedGroupRadios.minWith(
                    compareBy<BssidRadio> { it.security.score0To100 }
                        .thenBy { MacAddressUtils.normalizeBssid(it.bssid) }
                ).security
                val bestSecurity = sortedGroupRadios.maxWith(
                    compareBy<BssidRadio> { it.security.score0To100 }
                        .thenByDescending { MacAddressUtils.normalizeBssid(it.bssid) }
                ).security
                val maxGen = sortedGroupRadios.maxBy { it.wifiGeneration.rankOrder }.wifiGeneration
                val isMeshOrMulti = clusterIds.size >= 2 ||
                    (sortedGroupRadios.size >= 2 &&
                        sortedGroupRadios.map { it.channelDescriptor.band }.distinct().size < sortedGroupRadios.size)

                projectedNonHiddenGroups.add(
                    EssNetworkGroup(
                        groupKey = bucketKey,
                        ssid = strongest.ssid,
                        displayName = strongest.ssid.displayName,
                        isMeshOrMultiAp = isMeshOrMulti,
                        physicalClusterCount = clusterIds.size.coerceAtLeast(1),
                        radios = sortedGroupRadios,
                        physicalClusterIds = clusterIds,
                        strongestRadio = strongest,
                        bands = sortedGroupRadios.map { it.channelDescriptor.band }.toSet(),
                        maxWifiGeneration = maxGen,
                        worstSecurityAssessment = worstSecurity,
                        bestSecurityAssessment = bestSecurity,
                        hasEvilTwinWarning = sortedGroupRadios.any { it.security.isEvilTwinSuspect },
                        containsConnectedRadio = sortedGroupRadios.any { it.isConnected },
                    )
                )
            }
        }

        return (projectedNonHiddenGroups + hiddenGroups)
            .distinctBy { it.groupKey }
            .sortedWith(groupComparator(sortOption))
    }

    internal fun radioDisplayTitle(radio: BssidRadio): String =
        radio.customNickname?.takeIf { it.isNotBlank() }
            ?: if (radio.ssid.isEffectivelyHidden()) {
                DecodedSsid.HIDDEN_NETWORK_LABEL
            } else {
                radio.effectiveDisplayName.ifBlank { radio.ssid.displayName }
            }
}
