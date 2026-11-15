package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ClusterMatchReason
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PhysicalApCluster
import com.a55.wifiradar.core.model.WifiBand
import java.util.Locale
import kotlin.math.abs

/**
 * Deterministic 3-tier wireless topology clustering (`F08`):
 * - Co-channel `BSS Color` (`IE 255/36`) collision post-pass (`F09`)
 * - Tier 1 -> Tier 2: `BssidRadio` -> `PhysicalApCluster` Union-Find (Wi-Fi 7 MLD MAC,
 *   6 GHz RNR co-location, universal 5-octet MAC prefix + band-compensated proximity,
 *   and 4-octet prefix + WPS hardware identity, with same-SSID same-band mesh separation guard)
 * - Tier 2 -> Tier 3: `PhysicalApCluster` -> `EssNetworkGroup` (`isMeshOrMultiAp = physicalClusterCount >= 2`,
 *   strictly keeping unrevealed hidden networks ungrouped).
 */
object ApClusteringEngine {

    private val RADIO_DISPLAY_COMPARATOR: Comparator<BssidRadio> =
        compareByDescending<BssidRadio> { it.isConnected }
            .thenByDescending { it.rssiDbm }
            .thenBy { it.bssid }

    /**
     * Populates co-channel `BSS Color` (`1..63`) collisions across radios sharing the same
     * `(band, primaryChannel, colorCode)` while ignoring Multiple-BSSID virtual VAP siblings
     * transmitted from the same physical radio.
     */
    fun annotateBssColorCollisions(radios: List<BssidRadio>): List<BssidRadio> {
        if (radios.size < 2) return radios

        val activeColored = radios.filter { radio ->
            val color = radio.beaconDetails.bssColor
            color != null && !color.isDisabled && color.colorCode in 1..63 && radio.channelDescriptor.primaryChannel > 0
        }
        if (activeColored.size < 2) return radios

        val groups = activeColored.groupBy { radio ->
            Triple(
                radio.channelDescriptor.band,
                radio.channelDescriptor.primaryChannel,
                radio.beaconDetails.bssColor!!.colorCode,
            )
        }

        val collisionsByBssid = HashMap<String, List<String>>()
        for (group in groups.values) {
            if (group.size < 2) continue
            for (r in group) {
                val prefixR = MacAddressUtils.extractUniversalFiveOctetPrefix(r.bssid)
                val colliding = group.asSequence()
                    .filter { other ->
                        other.bssid != r.bssid &&
                            !(r.beaconDetails.maxBssidIndicator != null &&
                                other.beaconDetails.maxBssidIndicator != null &&
                                prefixR != null &&
                                prefixR == MacAddressUtils.extractUniversalFiveOctetPrefix(other.bssid))
                    }
                    .map { it.bssid }
                    .sorted()
                    .toList()
                if (colliding.isNotEmpty()) {
                    collisionsByBssid[r.bssid] = colliding
                }
            }
        }

        if (collisionsByBssid.isEmpty()) return radios

        return radios.map { radio ->
            val colliding = collisionsByBssid[radio.bssid]
            val existingColor = radio.beaconDetails.bssColor
            if (colliding == null || existingColor == null) {
                radio
            } else {
                radio.copy(
                    beaconDetails = radio.beaconDetails.copy(
                        bssColor = existingColor.copy(
                            hasCollisionOnChannel = true,
                            collidingBssids = colliding,
                        )
                    )
                )
            }
        }
    }

    /**
     * Runs deterministic Union-Find to assign `physicalClusterId` onto each `BssidRadio`
     * and construct `PhysicalApCluster` objects.
     */
    fun clusterPhysicalAccessPoints(
        radios: List<BssidRadio>,
        wpsUuidByBssid: Map<String, String> = emptyMap(),
    ): Pair<List<BssidRadio>, List<PhysicalApCluster>> {
        if (radios.isEmpty()) return emptyList<BssidRadio>() to emptyList()

        val sortedByBssid = radios.sortedBy { it.bssid }
        val n = sortedByBssid.size
        val parent = IntArray(n) { it }
        val bestReason = Array(n) { ClusterMatchReason.SINGLE_RADIO }

        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]
                cur = parent[cur]
            }
            return cur
        }

        fun union(a: Int, b: Int, reason: ClusterMatchReason) {
            val ra = find(a)
            val rb = find(b)
            val topReason = higherPriorityReason(
                higherPriorityReason(bestReason[ra], bestReason[rb]),
                reason,
            )
            if (ra == rb) {
                bestReason[ra] = topReason
                return
            }
            if (ra < rb) {
                parent[rb] = ra
                bestReason[ra] = topReason
            } else {
                parent[ra] = rb
                bestReason[rb] = topReason
            }
        }

        val mldMacs = Array(n) { idx -> effectiveValidMldMac(sortedByBssid[idx]) }
        val fiveOctetPrefixes = Array(n) { idx ->
            val bssid = sortedByBssid[idx].bssid
            if (MacAddressUtils.isAndroidRedactedMac(bssid)) {
                null
            } else {
                MacAddressUtils.extractUniversalFiveOctetPrefix(bssid)
            }
        }
        val bssidToIndex = HashMap<String, Int>(n * 2)
        for (idx in 0 until n) {
            bssidToIndex[sortedByBssid[idx].bssid] = idx
        }

        // Rule A: Wi-Fi 7 MLD MAC exact match
        for (i in 0 until n) {
            val mldI = mldMacs[i] ?: continue
            for (j in (i + 1) until n) {
                if (mldI == mldMacs[j]) {
                    union(i, j, ClusterMatchReason.MLD_MAC)
                }
            }
        }

        // Rule B: Out-of-band 6 GHz RNR co-located neighbor link
        val rnrTargetToSources = HashMap<String, MutableList<Int>>()
        for (i in 0 until n) {
            for (neighbor in sortedByBssid[i].beaconDetails.colocated6GhzNeighbors) {
                if (!neighbor.isColocatedAp) continue
                val nbMac = neighbor.neighborBssid
                    ?.takeIf { MacAddressUtils.isValidMac(it) && !MacAddressUtils.isAndroidRedactedMac(it) }
                    ?.let(MacAddressUtils::normalizeBssid)
                    ?: continue
                val targetIdx = bssidToIndex[nbMac]
                if (targetIdx != null && targetIdx != i) {
                    union(i, targetIdx, ClusterMatchReason.RNR_COLOCATED)
                }
                rnrTargetToSources.getOrPut(nbMac) { ArrayList(2) }.add(i)
            }
        }
        for (sources in rnrTargetToSources.values) {
            if (sources.size >= 2) {
                val first = sources[0]
                for (k in 1 until sources.size) {
                    if (sources[k] != first) {
                        union(first, sources[k], ClusterMatchReason.RNR_COLOCATED)
                    }
                }
            }
        }

        // Rule C1 & C2: Prefix + Proximity + WPS Hardware Identity
        // Sort candidate edges by tightest RF proximity & last-octet distance and verify
        // component-level mesh separation so transitive 2.4 GHz bridges never merge two 5 GHz mesh nodes.
        data class HeuristicEdge(
            val i: Int,
            val j: Int,
            val reason: ClusterMatchReason,
            val deltaDb: Double,
            val lastOctetDist: Int,
        )

        val candidateEdges = ArrayList<HeuristicEdge>()
        for (i in 0 until n) {
            val rI = sortedByBssid[i]
            val p5I = fiveOctetPrefixes[i] ?: continue
            val p4I = p5I.substring(0, 11)
            val compRssiI = bandCompensatedRssi(rI)
            val lastOctetI = rI.bssid.takeLast(2).toIntOrNull(16) ?: 0

            for (j in (i + 1) until n) {
                val rJ = sortedByBssid[j]
                val p5J = fiveOctetPrefixes[j] ?: continue

                if (areSeparateSameBandMeshNodes(rI, rJ)) {
                    continue
                }

                val compRssiJ = bandCompensatedRssi(rJ)
                val deltaDb = abs(compRssiI - compRssiJ)
                val lastOctetJ = rJ.bssid.takeLast(2).toIntOrNull(16) ?: 0
                val octetDist = abs(lastOctetI - lastOctetJ)

                if (p5I == p5J && deltaDb <= 18.0) {
                    candidateEdges.add(
                        HeuristicEdge(
                            i = i,
                            j = j,
                            reason = ClusterMatchReason.BSSID_PREFIX_AND_PROXIMITY,
                            deltaDb = deltaDb,
                            lastOctetDist = octetDist,
                        )
                    )
                    continue
                }

                val p4J = p5J.substring(0, 11)
                if (p4I == p4J && deltaDb <= 14.0 && hasMatchingWpsHardwareIdentity(rI, rJ, wpsUuidByBssid)) {
                    candidateEdges.add(
                        HeuristicEdge(
                            i = i,
                            j = j,
                            reason = ClusterMatchReason.WPS_IDENTITY,
                            deltaDb = deltaDb,
                            lastOctetDist = octetDist,
                        )
                    )
                }
            }
        }

        candidateEdges.sortWith(
            compareBy<HeuristicEdge> { reasonPriority(it.reason) }
                .thenBy { it.deltaDb }
                .thenBy { it.lastOctetDist }
                .thenBy { it.i }
                .thenBy { it.j }
        )

        for (edge in candidateEdges) {
            val ra = find(edge.i)
            val rb = find(edge.j)
            if (ra == rb) {
                union(edge.i, edge.j, edge.reason)
                continue
            }
            var compatible = true
            for (a in 0 until n) {
                if (find(a) != ra) continue
                for (b in 0 until n) {
                    if (find(b) != rb) continue
                    if (areSeparateSameBandMeshNodes(sortedByBssid[a], sortedByBssid[b])) {
                        compatible = false
                        break
                    }
                }
                if (!compatible) break
            }
            if (compatible) {
                union(edge.i, edge.j, edge.reason)
            }
        }

        val components = LinkedHashMap<Int, MutableList<BssidRadio>>()
        for (i in 0 until n) {
            val root = find(i)
            components.getOrPut(root) { ArrayList(3) }.add(sortedByBssid[i])
        }

        val updatedRadiosMap = HashMap<String, BssidRadio>(n * 2)
        val clusters = ArrayList<PhysicalApCluster>(components.size)

        for ((rootIdx, compRadiosRaw) in components) {
            val mldMac = compRadiosRaw.firstNotNullOfOrNull { effectiveValidMldMac(it) }
            val minBssid = compRadiosRaw.minOf { it.bssid }
            val clusterId = if (mldMac != null) {
                "cluster:mld:$mldMac"
            } else {
                "cluster:$minBssid"
            }

            val compRadiosWithId = compRadiosRaw
                .map { radio ->
                    radio.copy(
                        physicalClusterId = clusterId,
                        apMldMacAddress = radio.apMldMacAddress ?: mldMac,
                    )
                }
                .sortedWith(RADIO_DISPLAY_COMPARATOR)

            for (r in compRadiosWithId) {
                updatedRadiosMap[r.bssid] = r
            }

            val reason = if (compRadiosWithId.size == 1) {
                ClusterMatchReason.SINGLE_RADIO
            } else {
                bestReason[rootIdx].takeIf { it != ClusterMatchReason.SINGLE_RADIO }
                    ?: ClusterMatchReason.BSSID_PREFIX_AND_PROXIMITY
            }

            clusters.add(buildPhysicalClusterFromRadios(clusterId, mldMac, reason, compRadiosWithId))
        }

        val sortedRadios = updatedRadiosMap.values.sortedWith(RADIO_DISPLAY_COMPARATOR)
        val sortedClusters = sortPhysicalClusters(clusters)
        return sortedRadios to sortedClusters
    }

    /**
     * Rebuilds `PhysicalApCluster` objects after `BssidRadio` items have been enriched with
     * Evil-Twin flags and per-band SNR / channel congestion scores so every nested `BssidRadio`
     * inside `PhysicalApCluster` matches `snapshot.radios` identically.
     */
    fun rebuildPhysicalClustersWithEnrichedRadios(
        previousClusters: List<PhysicalApCluster>,
        enrichedRadios: List<BssidRadio>,
    ): List<PhysicalApCluster> {
        if (previousClusters.isEmpty() || enrichedRadios.isEmpty()) return emptyList()
        val byBssid = enrichedRadios.associateBy { it.bssid }
        val rebuilt = previousClusters.mapNotNull { cluster ->
            val updatedComp = cluster.radios.mapNotNull { byBssid[it.bssid] }
                .sortedWith(RADIO_DISPLAY_COMPARATOR)
            if (updatedComp.isEmpty()) {
                null
            } else {
                buildPhysicalClusterFromRadios(
                    clusterId = cluster.clusterId,
                    mldMac = cluster.apMldMacAddress,
                    reason = cluster.matchReason,
                    compRadiosSorted = updatedComp,
                )
            }
        }
        return sortPhysicalClusters(rebuilt)
    }

    /**
     * Builds Tier-3 `EssNetworkGroup` cards (`F08`), keeping unrevealed hidden networks
     * strictly ungrouped (`groupKey = "ess:hidden:${radio.bssid}"`).
     */
    fun buildEssNetworkGroups(radios: List<BssidRadio>): List<EssNetworkGroup> {
        if (radios.isEmpty()) return emptyList()

        val groupedBuckets = LinkedHashMap<String, MutableList<BssidRadio>>()
        for (radio in radios) {
            val key = when {
                radio.ssid.isHidden && !radio.ssid.isRevealedFromConnection ->
                    "ess:hidden:${radio.bssid}"
                radio.ssid.isUtf8 ->
                    "ess:ssid:${radio.ssid.displayName}"
                else ->
                    "ess:hex:${radio.ssid.rawHex.orEmpty()}"
            }
            groupedBuckets.getOrPut(key) { ArrayList(2) }.add(radio)
        }

        val groups = ArrayList<EssNetworkGroup>(groupedBuckets.size)
        for ((groupKey, bucket) in groupedBuckets) {
            val sortedBucket = bucket.sortedWith(RADIO_DISPLAY_COMPARATOR)
            val strongest = sortedBucket.first()
            val isUnrevealedHidden = strongest.ssid.isHidden && !strongest.ssid.isRevealedFromConnection

            val clusterIds = sortedBucket.map { it.physicalClusterId }.distinct()
            val clusterCount = clusterIds.size
            val isMeshOrMultiAp = !isUnrevealedHidden && clusterCount >= 2

            val displayName = if (sortedBucket.size == 1 && strongest.customNickname != null) {
                strongest.effectiveDisplayName
            } else {
                strongest.ssid.displayName
            }

            val bands = sortedBucket.mapTo(LinkedHashSet()) { it.channelDescriptor.band }
            val maxGen = sortedBucket.maxBy { it.wifiGeneration.rankOrder }.wifiGeneration
            val worstSec = sortedBucket.minWith(
                compareBy<BssidRadio> { it.security.score0To100 }.thenBy { it.bssid }
            ).security
            val bestSec = sortedBucket.maxWith(
                compareBy<BssidRadio> { it.security.score0To100 }.thenByDescending { it.bssid }
            ).security
            val hasEvilTwin = sortedBucket.any { it.security.isEvilTwinSuspect }
            val containsConnected = sortedBucket.any { it.isConnected }

            groups.add(
                EssNetworkGroup(
                    groupKey = groupKey,
                    ssid = strongest.ssid,
                    displayName = displayName,
                    isMeshOrMultiAp = isMeshOrMultiAp,
                    physicalClusterCount = clusterCount,
                    radios = sortedBucket,
                    physicalClusterIds = clusterIds,
                    strongestRadio = strongest,
                    bands = bands,
                    maxWifiGeneration = maxGen,
                    worstSecurityAssessment = worstSec,
                    bestSecurityAssessment = bestSec,
                    hasEvilTwinWarning = hasEvilTwin,
                    containsConnectedRadio = containsConnected,
                )
            )
        }

        return groups.sortedWith(
            compareByDescending<EssNetworkGroup> { it.containsConnectedRadio }
                .thenByDescending { it.strongestRadio.rssiDbm }
                .thenBy { it.strongestRadio.bssid }
        )
    }

    fun sortRadiosDeterministically(radios: List<BssidRadio>): List<BssidRadio> =
        radios.sortedWith(RADIO_DISPLAY_COMPARATOR)

    private fun buildPhysicalClusterFromRadios(
        clusterId: String,
        mldMac: String?,
        reason: ClusterMatchReason,
        compRadiosSorted: List<BssidRadio>,
    ): PhysicalApCluster {
        val lead = compRadiosSorted.first()
        val vendor = compRadiosSorted.firstOrNull { !it.vendorInfo.isLocallyAdministered && it.vendorInfo.vendorName != null }?.vendorInfo
            ?: lead.vendorInfo
        val wpsInfo = compRadiosSorted.firstNotNullOfOrNull { it.beaconDetails.wpsDevice }

        val customNick = compRadiosSorted.firstNotNullOfOrNull { it.customNickname }
        val visibleSsid = compRadiosSorted.firstOrNull { !it.ssid.isHidden || it.ssid.isRevealedFromConnection }?.ssid?.displayName
        val wpsLabel = wpsInfo?.modelName ?: wpsInfo?.deviceName ?: wpsInfo?.advertisedApHostname
        val primaryLabel = customNick
            ?: visibleSsid
            ?: wpsLabel
            ?: "${vendor.displayLabel} (${lead.bssid})"

        val colocated6Ghz = compRadiosSorted
            .flatMap { it.beaconDetails.colocated6GhzNeighbors }
            .distinctBy { Triple(it.neighborBssid.orEmpty(), it.operatingClass, it.channelNumber) }

        val strongestRssi = compRadiosSorted.maxOf { it.rssiDbm }
        val bands = compRadiosSorted.mapTo(LinkedHashSet()) { it.channelDescriptor.band }
        val containsConnected = compRadiosSorted.any { it.isConnected }

        return PhysicalApCluster(
            clusterId = clusterId,
            primaryLabel = primaryLabel,
            vendorInfo = vendor,
            wpsDeviceInfo = wpsInfo,
            apMldMacAddress = mldMac,
            matchReason = reason,
            radios = compRadiosSorted,
            colocated6GhzNeighbors = colocated6Ghz,
            strongestRssiDbm = strongestRssi,
            bands = bands,
            containsConnectedRadio = containsConnected,
        )
    }

    private fun sortPhysicalClusters(clusters: List<PhysicalApCluster>): List<PhysicalApCluster> =
        clusters.sortedWith(
            compareByDescending<PhysicalApCluster> { it.containsConnectedRadio }
                .thenByDescending { it.strongestRssiDbm }
                .thenBy { it.clusterId }
        )

    /**
     * Mesh separation guard: two radios broadcasting the exact same non-hidden SSID on the
     * exact same `WifiBand` (e.g., downstairs Ch 36 + upstairs Ch 149 both on 5 GHz `"HomeMesh"`)
     * are separate mesh nodes and MUST NOT be merged by heuristic prefix rules C1/C2.
     */
    private fun areSeparateSameBandMeshNodes(a: BssidRadio, b: BssidRadio): Boolean {
        val aVisible = !a.ssid.isHidden || a.ssid.isRevealedFromConnection
        val bVisible = !b.ssid.isHidden || b.ssid.isRevealedFromConnection
        if (!aVisible || !bVisible) return false
        if (a.channelDescriptor.band == WifiBand.UNKNOWN) return false
        return a.channelDescriptor.band == b.channelDescriptor.band &&
            a.ssid.displayName == b.ssid.displayName &&
            a.security.securityFamily == b.security.securityFamily
    }

    private fun hasMatchingWpsHardwareIdentity(
        a: BssidRadio,
        b: BssidRadio,
        wpsUuidByBssid: Map<String, String>,
    ): Boolean {
        val uuidA = wpsUuidByBssid[a.bssid]
        val uuidB = wpsUuidByBssid[b.bssid]
        if (!uuidA.isNullOrBlank() && uuidA == uuidB) return true

        val wpsA = a.beaconDetails.wpsDevice ?: return false
        val wpsB = b.beaconDetails.wpsDevice ?: return false

        val modelA = wpsA.modelName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val modelB = wpsB.modelName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        if (modelA != null && modelA == modelB) return true

        val devA = wpsA.deviceName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val devB = wpsB.deviceName?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val numA = wpsA.modelNumber?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val numB = wpsB.modelNumber?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        return devA != null && devA == devB && numA != null && numA == numB
    }

    private fun bandCompensatedRssi(radio: BssidRadio): Double {
        val offset = when (radio.channelDescriptor.band) {
            WifiBand.BAND_2_4_GHZ -> 0.0
            WifiBand.BAND_5_GHZ -> 6.5
            WifiBand.BAND_6_GHZ -> 8.0
            WifiBand.BAND_60_GHZ -> 28.0
            WifiBand.UNKNOWN -> 0.0
        }
        return radio.rssiDbm.coerceIn(-115, 0).toDouble() + offset
    }

    private fun effectiveValidMldMac(radio: BssidRadio): String? {
        val candidate = radio.apMldMacAddress ?: radio.beaconDetails.ehtMlo?.apMldMacAddress ?: return null
        val norm = MacAddressUtils.normalizeBssid(candidate)
        return if (MacAddressUtils.isValidMac(norm) && !MacAddressUtils.isAndroidRedactedMac(norm)) {
            norm
        } else {
            null
        }
    }

    private fun higherPriorityReason(a: ClusterMatchReason, b: ClusterMatchReason): ClusterMatchReason =
        if (reasonPriority(a) <= reasonPriority(b)) a else b

    private fun reasonPriority(reason: ClusterMatchReason): Int = when (reason) {
        ClusterMatchReason.MLD_MAC -> 0
        ClusterMatchReason.RNR_COLOCATED -> 1
        ClusterMatchReason.WPS_IDENTITY -> 2
        ClusterMatchReason.BSSID_PREFIX_AND_PROXIMITY -> 3
        ClusterMatchReason.SINGLE_RADIO -> 4
    }
}
