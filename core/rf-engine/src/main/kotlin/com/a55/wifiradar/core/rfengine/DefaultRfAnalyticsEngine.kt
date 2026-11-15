package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.MloLinkInfo
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.RfAnalyticsEngine
import com.a55.wifiradar.core.model.RoamEvent
import com.a55.wifiradar.core.model.RssiSample
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.roundToInt

/**
 * Production, 100% pure-Kotlin JVM implementation of [RfAnalyticsEngine] (`F05–F17`, `F29`).
 *
 * Architectural Guarantees:
 * - Zero `android.*` imports and zero wall-clock calls (`System.currentTimeMillis()` / `SystemClock`).
 * - Thread-safe under concurrent `Dispatchers.Default` invocations via internal reentrant lock.
 * - Flat `O(1)` memory bounds: `<= 256` LRU tracked BSSIDs, `<= 120` samples per BSSID ring buffer,
 *   and `<= 64` roam events.
 * - Every `Double` field is finite-guarded against `NaN` / `Infinity`.
 */
class DefaultRfAnalyticsEngine : RfAnalyticsEngine {

    companion object {
        const val MAX_TRACKED_BSSIDS: Int = 256
        const val MAX_ROAM_EVENTS: Int = 64
        const val ANTI_FLICKER_RETENTION_MS: Long = 45_000L
        const val STALE_BADGE_THRESHOLD_MS: Long = 15_000L
        const val OS_STALE_EVICTION_MS: Long = 90_000L
    }

    private class BssidHistoryEntry(
        val bssid: String,
        val kalman: KalmanSignalFilter = KalmanSignalFilter(),
        val timelineBuffer: FixedRssiRingBuffer = FixedRssiRingBuffer(FixedRssiRingBuffer.MAX_SAMPLES_PER_BSSID),
        var lastSeenElapsedMs: Long = 0L,
        var lastSeenTimestampMicros: Long = 0L,
        var lastGapRecordedElapsedMs: Long = Long.MIN_VALUE,
        var wpsUuidHex: String? = null,
        var lastRadioSnapshot: BssidRadio? = null,
    )

    private data class ConnectedAnchor(
        val bssid: String,
        val ssid: String?,
        val band: WifiBand,
        val channel: Int,
        val rssiDbm: Int,
        val elapsedMs: Long,
    )

    private val stateLock = Any()

    private val bssidHistory = LinkedHashMap<String, BssidHistoryEntry>(128, 0.75f, true)
    private val roamEvents = ArrayDeque<RoamEvent>(MAX_ROAM_EVENTS)
    private var lastConnectedAnchor: ConnectedAnchor? = null
    private val proximityTracker = ProximitySessionTracker()

    override fun processScanSnapshot(
        rawBatch: RawScanBatch?,
        connectedWifi: ConnectedWifiObservation?,
        scanTelemetry: ScanTelemetry,
        radioCapabilities: DeviceRadioCapabilities,
        bssidNicknames: Map<String, String>,
        oneMeterCalibrationsDbm: Map<String, Double>,
        currentElapsedMs: Long,
        currentEpochMs: Long,
    ): RadarSnapshot = synchronized(stateLock) {
        if (rawBatch == null) {
            if (connectedWifi == null) {
                lastConnectedAnchor = null
            }
            return RadarSnapshot(
                timestampElapsedMs = currentElapsedMs,
                timestampEpochMs = currentEpochMs,
                radios = emptyList(),
                groupedNetworks = emptyList(),
                physicalClusters = emptyList(),
                scanTelemetry = scanTelemetry,
                connectedWifi = connectedWifi,
                radioCapabilities = radioCapabilities,
                roamEvents = roamEvents.toList(),
                rssiTimelines = emptyMap(),
            )
        }

        val normNicknames = normalizeStringMap(bssidNicknames)
        val normCalibrations = normalizeDoubleMap(oneMeterCalibrationsDbm)

        val validConnected = connectedWifi?.takeIf {
            !it.isLocationRedacted &&
                MacAddressUtils.isValidMac(it.bssid) &&
                !MacAddressUtils.isAndroidRedactedMac(it.bssid)
        }
        val connectedNormBssid = validConnected?.let { MacAddressUtils.normalizeBssid(it.bssid) }

        // 1. Deduplicate batch entries by normalized BSSID (freshest timestampMicros, then strongest RSSI)
        val deduplicatedEntries = LinkedHashMap<String, RawScanEntry>(rawBatch.entries.size)
        for (entry in rawBatch.entries) {
            val normMac = MacAddressUtils.normalizeBssid(entry.bssid)
            if (normMac.isEmpty()) continue
            val existing = deduplicatedEntries[normMac]
            if (existing == null ||
                entry.timestampMicros > existing.timestampMicros ||
                (entry.timestampMicros == existing.timestampMicros && entry.rssiDbm > existing.rssiDbm)
            ) {
                deduplicatedEntries[normMac] = entry
            }
        }

        val activeRadiosPreCluster = ArrayList<BssidRadio>(deduplicatedEntries.size + 8)
        val seenInThisBatch = HashSet<String>(deduplicatedEntries.size * 2)
        val wpsUuidByBssid = HashMap<String, String>()

        // 2. Process all fresh / batch entries
        for ((normBssid, entry) in deduplicatedEntries) {
            val isConnectedEntry = connectedNormBssid != null && normBssid == connectedNormBssid

            val (entryLastSeenMs, entryAgeMs, shouldEvictOsStale) = resolveEntryTiming(
                entryTimestampMicros = entry.timestampMicros,
                batchReceivedElapsedMs = rawBatch.receivedElapsedMs,
                currentElapsedMs = currentElapsedMs,
                isConnectedEntry = isConnectedEntry,
            )
            if (shouldEvictOsStale) {
                bssidHistory.remove(normBssid)
                continue
            }

            seenInThisBatch.add(normBssid)

            val prelimChannelDesc = ChannelAndFrequencyResolver.resolve(
                primaryFreqMhz = entry.frequencyMhz,
                centerFreq0Mhz = entry.centerFreq0Mhz,
                centerFreq1Mhz = entry.centerFreq1Mhz,
                channelWidthCode = entry.channelWidthCode,
                eht320MhzOverride = false,
            )

            val decodedSsid = SsidDecoder.decode(
                rawSsidBytes = entry.rawSsidBytes,
                legacySsid = entry.legacySsid,
                normalizedBssid = normBssid,
                connectedWifi = validConnected,
            )

            val ssidBytesForRnr = entry.rawSsidBytes
                ?: decodedSsid.rawUtf8OrNull?.encodeToByteArray()

            val ieInspection = BeaconIeInspector.inspect(
                informationElements = entry.informationElements,
                sourceBssid = normBssid,
                sourceSsidDisplayName = decodedSsid.displayName,
                sourceSsidBytes = ssidBytesForRnr,
                wifiStandardCode = entry.wifiStandardCode,
                channelDescriptor = prelimChannelDesc,
                entryApMldMacAddress = entry.apMldMacAddress,
                entryApMloLinkId = entry.apMloLinkId,
            )

            val finalChannelDesc = if (ieInspection.eht320MhzActive &&
                prelimChannelDesc.band == WifiBand.BAND_6_GHZ &&
                prelimChannelDesc.width.widthMhz < 320
            ) {
                ChannelAndFrequencyResolver.resolve(
                    primaryFreqMhz = entry.frequencyMhz,
                    centerFreq0Mhz = entry.centerFreq0Mhz,
                    centerFreq1Mhz = entry.centerFreq1Mhz,
                    channelWidthCode = entry.channelWidthCode,
                    eht320MhzOverride = true,
                )
            } else {
                prelimChannelDesc
            }

            val vendorInfo = OuiVendorResolver.resolve(
                bssid = normBssid,
                wpsManufacturer = ieInspection.beaconDetails.wpsDevice?.manufacturer,
            )

            val security = SecurityAnalyzer.assess(
                capabilities = entry.capabilities,
                securityTypes = entry.securityTypes,
                ieResult = ieInspection,
            )

            val connFastPathObs = if (isConnectedEntry &&
                KalmanSignalFilter.sanitizeRssiDbm(validConnected.rssiDbm) != null &&
                validConnected.timestampElapsedMs >= entryLastSeenMs
            ) {
                validConnected
            } else {
                null
            }

            val rawRssi = connFastPathObs?.rssiDbm ?: entry.rssiDbm
            val clampedRawRssi = rawRssi.coerceIn(-115, -5)
            val obsElapsedMs = if (connFastPathObs != null) {
                maxOf(connFastPathObs.timestampElapsedMs, entryLastSeenMs)
            } else {
                entryLastSeenMs
            }

            val histEntry = bssidHistory.getOrPut(normBssid) { BssidHistoryEntry(bssid = normBssid) }
            val filtered = histEntry.kalman.update(clampedRawRssi, obsElapsedMs)
            histEntry.timelineBuffer.appendLiveSample(
                timestampElapsedMs = obsElapsedMs,
                rawRssiDbm = clampedRawRssi,
                smoothedRssiDbm = filtered.smoothedDbm,
                emaRssiDbm = filtered.emaDbm,
                isConnectedSample = isConnectedEntry,
            )
            histEntry.lastSeenElapsedMs = obsElapsedMs
            histEntry.lastSeenTimestampMicros = entry.timestampMicros
            if (ieInspection.wpsUuidHex != null) {
                histEntry.wpsUuidHex = ieInspection.wpsUuidHex
            }
            histEntry.wpsUuidHex?.let { wpsUuidByBssid[normBssid] = it }

            val customNick = normNicknames[normBssid]
            val effectiveName = customNick ?: decodedSsid.displayName
            val user1mCal = normCalibrations[normBssid]

            val distanceEst = DistanceEstimator.estimatePathLoss(
                smoothedRssiDbm = filtered.smoothedDbm,
                frequencyMhz = finalChannelDesc.primaryFreqMhz,
                band = finalChannelDesc.band,
                tpcApTxPowerDbm = ieInspection.beaconDetails.tpcApTxPowerDbm,
                environment = PropagationEnvironment.HOME_OFFICE,
                userCalibrated1mDbm = user1mCal,
            )

            val mldMac = resolveEffectiveMldMac(
                entryMld = entry.apMldMacAddress,
                ieMld = ieInspection.beaconDetails.ehtMlo?.apMldMacAddress,
                connMld = if (isConnectedEntry) validConnected.apMldMacAddress else null,
            )

            val mloLinks = mergeMloLinks(
                scanLinks = entry.affiliatedMloLinks,
                connLinks = if (isConnectedEntry) validConnected.mloLinks else emptyList(),
            )

            val qualityPct = SemanticFormatters.signalQualityPct(clampedRawRssi)
            val signalTier = SignalQualityTier.fromRssiDbm(clampedRawRssi)
            val effAgeMs = if (connFastPathObs != null) {
                (currentElapsedMs - obsElapsedMs).coerceAtLeast(0L)
            } else {
                entryAgeMs
            }

            val radio = BssidRadio(
                bssid = normBssid,
                ssid = decodedSsid,
                customNickname = customNick,
                effectiveDisplayName = effectiveName,
                rssiDbm = clampedRawRssi,
                smoothedRssiDbm = filtered.smoothedDbm,
                signalQualityPct = qualityPct,
                signalTier = signalTier,
                estimatedSnrDb = (clampedRawRssi.toDouble() - (-95.0)).finiteOr(25.0),
                effectiveNoiseFloorDbm = -95.0,
                channelDescriptor = finalChannelDesc,
                wifiGeneration = ieInspection.wifiGeneration,
                wifiStandardRawCode = entry.wifiStandardCode,
                vendorInfo = vendorInfo,
                security = security,
                beaconDetails = ieInspection.beaconDetails,
                physicalClusterId = "",
                apMldMacAddress = mldMac,
                mloLinks = mloLinks,
                is80211mcResponder = entry.is80211mcResponder || ieInspection.beaconDetails.roaming.ftmResponder,
                is80211azResponder = entry.is80211azResponder,
                isConnected = isConnectedEntry,
                connectedLinkInfo = if (isConnectedEntry) validConnected else null,
                oneMeterCalibrationDbm = user1mCal,
                estimatedDistanceMeters = distanceEst.meters,
                channelCongestionScore0To100 = 100,
                lastSeenTimestampMicros = entry.timestampMicros,
                lastSeenElapsedMs = obsElapsedMs,
                ageMs = effAgeMs,
                isMissedInLatestSweep = false,
                isStale = effAgeMs > STALE_BADGE_THRESHOLD_MS,
            )

            activeRadiosPreCluster.add(radio)
        }

        // 3. Retain missed-beacon radios within 45s anti-flicker window & evict >90s stale entries
        val historySnapshotEntries = bssidHistory.entries.toList()
        for ((cachedBssid, histEntry) in historySnapshotEntries) {
            if (cachedBssid in seenInThisBatch) continue

            val missedAgeMs = currentElapsedMs - histEntry.lastSeenElapsedMs
            if (missedAgeMs > OS_STALE_EVICTION_MS) {
                bssidHistory.remove(cachedBssid)
                continue
            }

            val prevRadio = histEntry.lastRadioSnapshot ?: continue
            val isStillConnected = connectedNormBssid != null && cachedBssid == connectedNormBssid

            if (isStillConnected &&
                KalmanSignalFilter.sanitizeRssiDbm(validConnected.rssiDbm) != null &&
                validConnected.timestampElapsedMs > histEntry.lastSeenElapsedMs
            ) {
                // Connected fast-path kept alive even though missed in scan sweep
                val clampedConnRssi = validConnected.rssiDbm.coerceIn(-115, -5)
                val obsTs = validConnected.timestampElapsedMs
                val filtered = histEntry.kalman.update(clampedConnRssi, obsTs)
                histEntry.timelineBuffer.appendLiveSample(
                    timestampElapsedMs = obsTs,
                    rawRssiDbm = clampedConnRssi,
                    smoothedRssiDbm = filtered.smoothedDbm,
                    emaRssiDbm = filtered.emaDbm,
                    isConnectedSample = true,
                )
                histEntry.lastSeenElapsedMs = obsTs
                val customNick = normNicknames[cachedBssid]
                val user1mCal = normCalibrations[cachedBssid]
                val distEst = DistanceEstimator.estimatePathLoss(
                    smoothedRssiDbm = filtered.smoothedDbm,
                    frequencyMhz = prevRadio.channelDescriptor.primaryFreqMhz,
                    band = prevRadio.channelDescriptor.band,
                    tpcApTxPowerDbm = prevRadio.beaconDetails.tpcApTxPowerDbm,
                    environment = PropagationEnvironment.HOME_OFFICE,
                    userCalibrated1mDbm = user1mCal,
                )
                val ageConn = (currentElapsedMs - obsTs).coerceAtLeast(0L)
                activeRadiosPreCluster.add(
                    prevRadio.copy(
                        customNickname = customNick,
                        effectiveDisplayName = customNick ?: prevRadio.ssid.displayName,
                        rssiDbm = clampedConnRssi,
                        smoothedRssiDbm = filtered.smoothedDbm,
                        signalQualityPct = SemanticFormatters.signalQualityPct(clampedConnRssi),
                        signalTier = SignalQualityTier.fromRssiDbm(clampedConnRssi),
                        isConnected = true,
                        connectedLinkInfo = validConnected,
                        oneMeterCalibrationDbm = user1mCal,
                        estimatedDistanceMeters = distEst.meters,
                        lastSeenElapsedMs = obsTs,
                        ageMs = ageConn,
                        isMissedInLatestSweep = true,
                        isStale = ageConn > STALE_BADGE_THRESHOLD_MS,
                    )
                )
                histEntry.wpsUuidHex?.let { wpsUuidByBssid[cachedBssid] = it }
            } else if (missedAgeMs in 0L..ANTI_FLICKER_RETENTION_MS) {
                if (rawBatch.isFreshHardwareSweep && currentElapsedMs > histEntry.lastGapRecordedElapsedMs) {
                    histEntry.timelineBuffer.appendOrUpdateDropoutGap(currentElapsedMs)
                    histEntry.lastGapRecordedElapsedMs = currentElapsedMs
                }
                val customNick = normNicknames[cachedBssid]
                val user1mCal = normCalibrations[cachedBssid]
                val distEst = DistanceEstimator.estimatePathLoss(
                    smoothedRssiDbm = prevRadio.smoothedRssiDbm,
                    frequencyMhz = prevRadio.channelDescriptor.primaryFreqMhz,
                    band = prevRadio.channelDescriptor.band,
                    tpcApTxPowerDbm = prevRadio.beaconDetails.tpcApTxPowerDbm,
                    environment = PropagationEnvironment.HOME_OFFICE,
                    userCalibrated1mDbm = user1mCal,
                )
                activeRadiosPreCluster.add(
                    prevRadio.copy(
                        customNickname = customNick,
                        effectiveDisplayName = customNick ?: prevRadio.ssid.displayName,
                        isConnected = isStillConnected,
                        connectedLinkInfo = if (isStillConnected) validConnected else null,
                        oneMeterCalibrationDbm = user1mCal,
                        estimatedDistanceMeters = distEst.meters,
                        ageMs = missedAgeMs,
                        isMissedInLatestSweep = true,
                        isStale = missedAgeMs > STALE_BADGE_THRESHOLD_MS,
                    )
                )
                histEntry.wpsUuidHex?.let { wpsUuidByBssid[cachedBssid] = it }
            }
        }

        // 4. Enforce strict <= 256 LRU BSSID history bound
        enforceLruBound(protectedConnectedBssid = connectedNormBssid)

        // 5. Co-channel BSS Color Collision Post-Pass (`F09`)
        val colorAnnotatedRadios = ApClusteringEngine.annotateBssColorCollisions(activeRadiosPreCluster)

        // 6. Tier-2 Physical AP Clustering (`F08`)
        val (clusteredRadios, prelimClusters) = ApClusteringEngine.clusterPhysicalAccessPoints(
            radios = colorAnnotatedRadios,
            wpsUuidByBssid = wpsUuidByBssid,
        )

        // 7. Cross-Cluster Open Downgrade Evil-Twin Detection (`F11`)
        val (evilTwinAnnotatedRadios, _) = SecurityAnalyzer.detectEvilTwinsAndAnnotate(clusteredRadios)

        // 8. Per-Radio Effective Noise Floor, SNR, and Channel Congestion Enrichment (`F15`)
        val enrichedRadiosUnsorted = SpectrumAnalyzer.enrichRadiosWithChannelMetrics(evilTwinAnnotatedRadios)
        val finalRadios = ApClusteringEngine.sortRadiosDeterministically(enrichedRadiosUnsorted)

        // 9. Rebuild Physical Clusters & Tier-3 ESS Network Groups with final enriched radios
        val finalClusters = ApClusteringEngine.rebuildPhysicalClustersWithEnrichedRadios(
            previousClusters = prelimClusters,
            enrichedRadios = finalRadios,
        )
        val finalGroups = ApClusteringEngine.buildEssNetworkGroups(finalRadios)

        // Update cached lastRadioSnapshot in bssidHistory
        for (radio in finalRadios) {
            bssidHistory[radio.bssid]?.lastRadioSnapshot = radio
        }

        // 10. Roam Event Tracking (`F08`, `F21`)
        updateRoamEvents(
            validConnected = validConnected,
            connectedNormBssid = connectedNormBssid,
            finalRadios = finalRadios,
            currentElapsedMs = currentElapsedMs,
            currentEpochMs = currentEpochMs,
        )

        // 11. Materialize bounded `<= 120` RSSI timelines for active snapshot radios
        val timelines = LinkedHashMap<String, List<RssiSample>>(finalRadios.size)
        for (radio in finalRadios) {
            val hist = bssidHistory[radio.bssid]
            if (hist != null) {
                timelines[radio.bssid] = hist.timelineBuffer.toRssiSampleList()
            }
        }

        val resolvedScanTelemetry = if (scanTelemetry.lastScanTimestampMs == null) {
            scanTelemetry.copy(
                lastScanTimestampMs = rawBatch.receivedElapsedMs,
                lastScanAgeMs = (currentElapsedMs - rawBatch.receivedElapsedMs).coerceAtLeast(0L),
            )
        } else {
            scanTelemetry
        }

        return RadarSnapshot(
            timestampElapsedMs = currentElapsedMs,
            timestampEpochMs = currentEpochMs,
            radios = finalRadios,
            groupedNetworks = finalGroups,
            physicalClusters = finalClusters,
            scanTelemetry = resolvedScanTelemetry,
            connectedWifi = connectedWifi,
            radioCapabilities = radioCapabilities,
            roamEvents = roamEvents.toList(),
            rssiTimelines = timelines,
        )
    }

    override fun updateProximityTarget(
        targetKey: ProximityTargetKey,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment,
        rttMeasurement: RttMeasurement?,
        oneMeterCalibrationsDbm: Map<String, Double>,
        currentElapsedMs: Long,
    ): ProximityTelemetry = synchronized(stateLock) {
        proximityTracker.update(
            targetKey = targetKey,
            snapshot = snapshot,
            environment = environment,
            rttMeasurement = rttMeasurement,
            oneMeterCalibrationsDbm = oneMeterCalibrationsDbm,
            currentElapsedMs = currentElapsedMs,
        )
    }

    override fun resetProximityTarget(targetKey: ProximityTargetKey?): Unit = synchronized(stateLock) {
        proximityTracker.reset(targetKey)
    }

    override fun computeSpectrumAnalysis(
        networks: List<BssidRadio>,
        band: WifiBand,
        includeDfs: Boolean,
        excludeConnectedSsid: Boolean,
        radioCapabilities: DeviceRadioCapabilities,
    ): SpectrumAnalysis = SpectrumAnalyzer.analyze(
        networks = networks,
        band = band,
        includeDfs = includeDfs,
        excludeConnectedSsid = excludeConnectedSsid,
        radioCapabilities = radioCapabilities,
    )

    override fun computeEnvironmentHealth(snapshot: RadarSnapshot): EnvironmentHealthReport =
        EnvironmentHealthEvaluator.evaluate(snapshot)

    override fun exportDiagnosticJson(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean,
        proximityHistory: List<ProximityTelemetry>,
    ): String = DiagnosticExporter.exportJson(
        snapshot = snapshot,
        health = health,
        redactMacs = redactMacs,
        proximityHistory = proximityHistory,
    )

    override fun exportDiagnosticMarkdown(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean,
        proximityHistory: List<ProximityTelemetry>,
    ): String = DiagnosticExporter.exportMarkdown(
        snapshot = snapshot,
        health = health,
        redactMacs = redactMacs,
        proximityHistory = proximityHistory,
    )

    /** Exposed strictly for internal unit & soak test assertions on bounded LRU sizing. */
    internal fun trackedBssidCacheSizeForTest(): Int = synchronized(stateLock) {
        bssidHistory.size
    }

    private fun resolveEntryTiming(
        entryTimestampMicros: Long,
        batchReceivedElapsedMs: Long,
        currentElapsedMs: Long,
        isConnectedEntry: Boolean,
    ): Triple<Long, Long, Boolean> {
        val refMs = maxOf(currentElapsedMs, batchReceivedElapsedMs)
        if (entryTimestampMicros <= 0L || refMs <= 0L) {
            val age = (currentElapsedMs - batchReceivedElapsedMs).coerceAtLeast(0L)
            return Triple(batchReceivedElapsedMs.coerceAtMost(refMs), age, false)
        }
        val entryMs = entryTimestampMicros / 1_000L
        val rawAgeMs = refMs - entryMs
        if (rawAgeMs > OS_STALE_EVICTION_MS && !isConnectedEntry) {
            return Triple(entryMs, rawAgeMs, true)
        }
        val clampedAge = rawAgeMs.coerceAtLeast(0L)
        val clampedLastSeen = entryMs.coerceAtMost(refMs).coerceAtLeast(0L)
        return Triple(clampedLastSeen, clampedAge, false)
    }

    private fun enforceLruBound(protectedConnectedBssid: String?) {
        if (bssidHistory.size <= MAX_TRACKED_BSSIDS) return
        val lockedProximityBssids = proximityTracker.activeLockedBssids()

        val iter = bssidHistory.entries.iterator()
        while (bssidHistory.size > MAX_TRACKED_BSSIDS && iter.hasNext()) {
            val candidate = iter.next().key
            if (candidate != protectedConnectedBssid && candidate !in lockedProximityBssids) {
                iter.remove()
            }
        }
        // Hard cap safety if somehow >256 entries were protected
        val hardIter = bssidHistory.entries.iterator()
        while (bssidHistory.size > MAX_TRACKED_BSSIDS && hardIter.hasNext()) {
            hardIter.next()
            hardIter.remove()
        }
    }

    private fun updateRoamEvents(
        validConnected: ConnectedWifiObservation?,
        connectedNormBssid: String?,
        finalRadios: List<BssidRadio>,
        currentElapsedMs: Long,
        currentEpochMs: Long,
    ) {
        if (validConnected == null || connectedNormBssid == null) {
            lastConnectedAnchor = null
            return
        }

        val matchingRadio = finalRadios.firstOrNull { it.bssid == connectedNormBssid }
        val resolvedSsid = SsidDecoder.extractConnectedSsid(validConnected.ssid)
            ?: matchingRadio?.ssid?.rawUtf8OrNull
            ?: matchingRadio?.ssid?.takeIf { !it.isHidden }?.displayName

        val (resolvedBand, resolvedChannel) = if (matchingRadio != null && matchingRadio.channelDescriptor.primaryChannel > 0) {
            matchingRadio.channelDescriptor.band to matchingRadio.channelDescriptor.primaryChannel
        } else {
            ChannelAndFrequencyResolver.freqToBandAndChannel(validConnected.frequencyMhz)
        }

        val prev = lastConnectedAnchor
        if (prev != null &&
            prev.bssid != connectedNormBssid &&
            resolvedSsid != null &&
            prev.ssid == resolvedSsid
        ) {
            if (roamEvents.size >= MAX_ROAM_EVENTS) {
                roamEvents.removeFirst()
            }
            roamEvents.addLast(
                RoamEvent(
                    timestampElapsedMs = currentElapsedMs,
                    epochTimeMs = currentEpochMs,
                    ssid = resolvedSsid,
                    previousBssid = prev.bssid,
                    newBssid = connectedNormBssid,
                    previousBand = prev.band,
                    newBand = resolvedBand,
                    previousChannel = prev.channel,
                    newChannel = resolvedChannel,
                    previousRssiDbm = prev.rssiDbm,
                    newRssiDbm = validConnected.rssiDbm,
                    rssiDeltaDb = validConnected.rssiDbm - prev.rssiDbm,
                    isBandSwitch = prev.band != resolvedBand,
                )
            )
        }

        lastConnectedAnchor = ConnectedAnchor(
            bssid = connectedNormBssid,
            ssid = resolvedSsid,
            band = resolvedBand,
            channel = resolvedChannel,
            rssiDbm = validConnected.rssiDbm,
            elapsedMs = currentElapsedMs,
        )
    }

    private fun resolveEffectiveMldMac(
        entryMld: String?,
        ieMld: String?,
        connMld: String?,
    ): String? {
        for (cand in listOf(entryMld, ieMld, connMld)) {
            if (cand.isNullOrBlank()) continue
            val norm = MacAddressUtils.normalizeBssid(cand)
            if (MacAddressUtils.isValidMac(norm) && !MacAddressUtils.isAndroidRedactedMac(norm)) {
                return norm
            }
        }
        return null
    }

    private fun mergeMloLinks(
        scanLinks: List<MloLinkInfo>,
        connLinks: List<MloLinkInfo>,
    ): List<MloLinkInfo> {
        if (scanLinks.isEmpty() && connLinks.isEmpty()) return emptyList()
        val combined = LinkedHashMap<Int, MloLinkInfo>()
        for (link in scanLinks) {
            val normLink = normalizeMloLink(link)
            combined[normLink.linkId] = normLink
        }
        for (link in connLinks) {
            val normLink = normalizeMloLink(link)
            combined[normLink.linkId] = normLink
        }
        return combined.values.sortedBy { it.linkId }
    }

    private fun normalizeMloLink(link: MloLinkInfo): MloLinkInfo {
        val normBssid = link.bssid?.takeIf { it.isNotBlank() }?.let(MacAddressUtils::normalizeBssid)
        val (band, ch) = if (link.frequencyMhz > 0 && (link.band == WifiBand.UNKNOWN || link.channel <= 0)) {
            ChannelAndFrequencyResolver.freqToBandAndChannel(link.frequencyMhz)
        } else {
            link.band to link.channel
        }
        return link.copy(
            bssid = normBssid,
            band = band,
            channel = ch,
        )
    }

    private fun normalizeStringMap(raw: Map<String, String>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(raw.size)
        for ((k, v) in raw) {
            val trimmed = v.trim()
            if (trimmed.isNotEmpty()) {
                val normKey = MacAddressUtils.normalizeBssid(k)
                if (normKey.isNotEmpty()) out[normKey] = trimmed
            }
        }
        return out
    }

    private fun normalizeDoubleMap(raw: Map<String, Double>): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>(raw.size)
        for ((k, v) in raw) {
            if (v.isFinite()) {
                val normKey = MacAddressUtils.normalizeBssid(k)
                if (normKey.isNotEmpty()) out[normKey] = v
            }
        }
        return out
    }
}
