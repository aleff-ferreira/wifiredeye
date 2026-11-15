package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.VelocityTrend
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Stateful proximity homing engine managing `BssidLock` & `FollowMeshSsid` sessions,
 * OLS velocity trend with hysteresis, Geiger exponential pulse curves, session statistics,
 * and the `SEARCHING -> LOCKED -> STALE -> SIGNAL_LOST -> LOCKED` dropout state machine
 * (`F12`, `F13`, `F14`, `F29`).
 */
internal class ProximitySessionTracker {

    companion object {
        const val MAX_PROXIMITY_SESSIONS: Int = 16
        private const val MESH_ROAM_HYSTERESIS_DB: Int = 4
        private const val OLS_WINDOW_MS: Long = 6_000L
        private const val OLS_MAX_REGRESSION_POINTS: Int = 24
        private const val STALE_THRESHOLD_MS: Long = 15_000L
        private const val SIGNAL_LOST_THRESHOLD_MS: Long = 30_000L
    }

    private data class RegressionPoint(
        val timestampMs: Long,
        val smoothedDbm: Double,
    )

    private class SessionState {
        var resolvedBssid: String? = null
        var displayTitle: String = DecodedSsid.HIDDEN_NETWORK_LABEL
        var band: WifiBand = WifiBand.UNKNOWN
        var channel: Int = -1
        var frequencyMhz: Int = 0
        var vendorLabel: String? = null
        var tpcApTxPowerDbm: Int? = null

        val kalman = KalmanSignalFilter()
        val sparklineBuffer = FixedRssiRingBuffer(FixedRssiRingBuffer.MAX_SAMPLES_PER_BSSID)
        val regressionPoints = ArrayDeque<RegressionPoint>(OLS_MAX_REGRESSION_POINTS)

        var lastIngestedBssid: String? = null
        var lastIngestedObsMs: Long = Long.MIN_VALUE
        var lastEvaluatedSnapshotMs: Long = Long.MIN_VALUE

        var consecutiveMissedSweeps: Int = 0
        var lastSeenElapsedMs: Long = Long.MIN_VALUE
        var lastRawRssiDbm: Int? = null
        var peakHoldSmoothedDbm: Double? = null

        var sessionMinRssi: Int? = null
        var sessionMaxRssi: Int? = null
        var sessionSumRssi: Long = 0L
        var sessionSampleCount: Int = 0

        var velocitySlopeDbPerSec: Double = 0.0
        var velocityTrend: VelocityTrend = VelocityTrend.CALIBRATING
        var isConnectedFastPath: Boolean = false
    }

    private val sessions = object : LinkedHashMap<String, SessionState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionState>?): Boolean =
            size > MAX_PROXIMITY_SESSIONS
    }

    fun activeLockedBssids(): Set<String> =
        sessions.values.mapNotNullTo(HashSet()) { it.resolvedBssid }

    fun reset(targetKey: ProximityTargetKey?) {
        if (targetKey == null) {
            sessions.clear()
        } else {
            sessions.remove(canonicalSessionKey(targetKey))
        }
    }

    fun update(
        targetKey: ProximityTargetKey,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment,
        rttMeasurement: RttMeasurement?,
        oneMeterCalibrationsDbm: Map<String, Double>,
        currentElapsedMs: Long,
    ): ProximityTelemetry {
        val sessionKey = canonicalSessionKey(targetKey)
        val session = sessions.getOrPut(sessionKey) { SessionState() }
        val normCalibrations = normalizeCalibrationKeys(oneMeterCalibrationsDbm)

        return when (targetKey) {
            is ProximityTargetKey.BssidLock -> updateBssidLock(
                targetKey = targetKey,
                session = session,
                snapshot = snapshot,
                environment = environment,
                rttMeasurement = rttMeasurement,
                normCalibrations = normCalibrations,
                currentElapsedMs = currentElapsedMs,
            )
            is ProximityTargetKey.FollowMeshSsid -> updateFollowMeshSsid(
                targetKey = targetKey,
                session = session,
                snapshot = snapshot,
                environment = environment,
                rttMeasurement = rttMeasurement,
                normCalibrations = normCalibrations,
                currentElapsedMs = currentElapsedMs,
            )
        }
    }

    private fun updateBssidLock(
        targetKey: ProximityTargetKey.BssidLock,
        session: SessionState,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment,
        rttMeasurement: RttMeasurement?,
        normCalibrations: Map<String, Double>,
        currentElapsedMs: Long,
    ): ProximityTelemetry {
        val normBssid = MacAddressUtils.normalizeBssid(targetKey.bssid)
        session.resolvedBssid = normBssid.ifEmpty { null }

        val conn = snapshot.connectedWifi?.takeIf {
            !it.isLocationRedacted &&
                normBssid.isNotEmpty() &&
                MacAddressUtils.normalizeBssid(it.bssid) == normBssid
        }
        val radio = snapshot.radios.firstOrNull { it.bssid == normBssid }

        if (radio != null) {
            session.displayTitle = radio.effectiveDisplayName
            session.band = radio.channelDescriptor.band
            session.channel = radio.channelDescriptor.primaryChannel
            session.frequencyMhz = radio.channelDescriptor.primaryFreqMhz
            session.vendorLabel = radio.vendorInfo.displayLabel
            session.tpcApTxPowerDbm = radio.beaconDetails.tpcApTxPowerDbm
        } else if (conn != null) {
            val connSsid = SsidDecoder.extractConnectedSsid(conn.ssid)
            session.displayTitle = connSsid
                ?: targetKey.ssidHint?.takeIf { it.isNotBlank() }
                ?: session.displayTitle
            val (b, ch) = ChannelAndFrequencyResolver.freqToBandAndChannel(conn.frequencyMhz)
            session.band = b
            session.channel = ch
            session.frequencyMhz = conn.frequencyMhz
            if (session.vendorLabel == null) {
                session.vendorLabel = OuiVendorResolver.resolve(normBssid).displayLabel
            }
        } else if (session.sessionSampleCount == 0) {
            session.displayTitle = targetKey.ssidHint?.takeIf { it.isNotBlank() }
                ?: DecodedSsid.HIDDEN_NETWORK_LABEL
            if (normBssid.isNotEmpty() && session.vendorLabel == null) {
                session.vendorLabel = OuiVendorResolver.resolve(normBssid).displayLabel
            }
        }

        val subtitle = if (normBssid.isNotEmpty()) {
            "Tracking single physical router ($normBssid)"
        } else {
            "Tracking single physical router"
        }

        val snapshotAdvanced = snapshot.timestampElapsedMs > session.lastEvaluatedSnapshotMs
        val freshConn = conn?.takeIf {
            KalmanSignalFilter.sanitizeRssiDbm(it.rssiDbm) != null &&
                (it.timestampElapsedMs > session.lastIngestedObsMs || session.sessionSampleCount == 0)
        }
        val freshRadio = radio?.takeIf {
            !it.isMissedInLatestSweep &&
                (session.sessionSampleCount == 0 ||
                    it.lastSeenElapsedMs > session.lastIngestedObsMs ||
                    snapshotAdvanced)
        }

        if (freshConn != null && (freshRadio == null || freshConn.timestampElapsedMs >= freshRadio.lastSeenElapsedMs)) {
            ingestFreshObservation(
                session = session,
                bssid = normBssid,
                rawRssiDbm = freshConn.rssiDbm,
                obsTimestampMs = maxOf(freshConn.timestampElapsedMs, currentElapsedMs),
                isConnectedFastPath = true,
                roamOccurred = false,
            )
        } else if (freshRadio != null) {
            ingestFreshObservation(
                session = session,
                bssid = normBssid,
                rawRssiDbm = freshRadio.rssiDbm,
                obsTimestampMs = maxOf(freshRadio.lastSeenElapsedMs, snapshot.timestampElapsedMs),
                isConnectedFastPath = conn != null,
                roamOccurred = false,
            )
        } else if (snapshotAdvanced && conn == null && (radio == null || radio.isMissedInLatestSweep)) {
            if (session.sessionSampleCount > 0) {
                session.consecutiveMissedSweeps++
            }
        }

        if (snapshot.timestampElapsedMs > session.lastEvaluatedSnapshotMs) {
            session.lastEvaluatedSnapshotMs = snapshot.timestampElapsedMs
        }

        return materializeTelemetry(
            targetKey = targetKey,
            session = session,
            subtitle = subtitle,
            snapshot = snapshot,
            environment = environment,
            rttMeasurement = rttMeasurement,
            normCalibrations = normCalibrations,
            currentElapsedMs = currentElapsedMs,
        )
    }

    private fun updateFollowMeshSsid(
        targetKey: ProximityTargetKey.FollowMeshSsid,
        session: SessionState,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment,
        rttMeasurement: RttMeasurement?,
        normCalibrations: Map<String, Double>,
        currentElapsedMs: Long,
    ): ProximityTelemetry {
        val targetSsid = targetKey.ssid
        session.displayTitle = targetSsid.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL }

        val liveRadios = snapshot.radios.filter {
            !it.isMissedInLatestSweep &&
                (!it.ssid.isHidden || it.ssid.isRevealedFromConnection) &&
                it.ssid.displayName == targetSsid
        }

        val connSsidMatch = snapshot.connectedWifi?.takeIf { conn ->
            !conn.isLocationRedacted &&
                MacAddressUtils.isValidMac(conn.bssid) &&
                !MacAddressUtils.isAndroidRedactedMac(conn.bssid) &&
                (SsidDecoder.extractConnectedSsid(conn.ssid) == targetSsid ||
                    liveRadios.any { it.bssid == MacAddressUtils.normalizeBssid(conn.bssid) })
        }

        val chosenRadio: BssidRadio? = when {
            connSsidMatch != null -> {
                val connBssid = MacAddressUtils.normalizeBssid(connSsidMatch.bssid)
                liveRadios.firstOrNull { it.bssid == connBssid }
                    ?: snapshot.radios.firstOrNull { it.bssid == connBssid }
            }
            liveRadios.isNotEmpty() -> {
                val currentBssid = session.resolvedBssid
                val currentInLive = currentBssid?.let { cur -> liveRadios.firstOrNull { it.bssid == cur } }
                val strongest = selectStrongestMeshCandidate(liveRadios, targetKey.preferredBssid)
                if (currentInLive != null && strongest != null &&
                    strongest.bssid != currentInLive.bssid &&
                    strongest.rssiDbm < currentInLive.rssiDbm + MESH_ROAM_HYSTERESIS_DB
                ) {
                    currentInLive
                } else {
                    strongest
                }
            }
            else -> null
        }

        val snapshotAdvanced = snapshot.timestampElapsedMs > session.lastEvaluatedSnapshotMs

        if (connSsidMatch != null || chosenRadio != null) {
            val newBssid = chosenRadio?.bssid ?: MacAddressUtils.normalizeBssid(connSsidMatch!!.bssid)
            val prevBssid = session.resolvedBssid
            val roamOccurred = prevBssid != null && prevBssid != newBssid
            session.resolvedBssid = newBssid

            if (chosenRadio != null) {
                session.band = chosenRadio.channelDescriptor.band
                session.channel = chosenRadio.channelDescriptor.primaryChannel
                session.frequencyMhz = chosenRadio.channelDescriptor.primaryFreqMhz
                session.vendorLabel = chosenRadio.vendorInfo.displayLabel
                session.tpcApTxPowerDbm = chosenRadio.beaconDetails.tpcApTxPowerDbm
            } else if (connSsidMatch != null) {
                val (b, ch) = ChannelAndFrequencyResolver.freqToBandAndChannel(connSsidMatch.frequencyMhz)
                session.band = b
                session.channel = ch
                session.frequencyMhz = connSsidMatch.frequencyMhz
                session.vendorLabel = OuiVendorResolver.resolve(newBssid).displayLabel
            }

            val freshConn = connSsidMatch?.takeIf {
                KalmanSignalFilter.sanitizeRssiDbm(it.rssiDbm) != null &&
                    (roamOccurred || it.timestampElapsedMs > session.lastIngestedObsMs || session.sessionSampleCount == 0)
            }
            val freshRadio = chosenRadio?.takeIf {
                !it.isMissedInLatestSweep &&
                    (roamOccurred ||
                        session.sessionSampleCount == 0 ||
                        it.lastSeenElapsedMs > session.lastIngestedObsMs ||
                        snapshotAdvanced)
            }

            if (freshConn != null && (freshRadio == null || freshConn.timestampElapsedMs >= freshRadio.lastSeenElapsedMs)) {
                ingestFreshObservation(
                    session = session,
                    bssid = newBssid,
                    rawRssiDbm = freshConn.rssiDbm,
                    obsTimestampMs = maxOf(freshConn.timestampElapsedMs, currentElapsedMs),
                    isConnectedFastPath = true,
                    roamOccurred = roamOccurred,
                )
            } else if (freshRadio != null) {
                ingestFreshObservation(
                    session = session,
                    bssid = newBssid,
                    rawRssiDbm = freshRadio.rssiDbm,
                    obsTimestampMs = maxOf(freshRadio.lastSeenElapsedMs, snapshot.timestampElapsedMs),
                    isConnectedFastPath = connSsidMatch != null,
                    roamOccurred = roamOccurred,
                )
            }
        } else if (snapshotAdvanced && session.sessionSampleCount > 0) {
            session.consecutiveMissedSweeps++
        }

        if (snapshot.timestampElapsedMs > session.lastEvaluatedSnapshotMs) {
            session.lastEvaluatedSnapshotMs = snapshot.timestampElapsedMs
        }

        val resolved = session.resolvedBssid
        val subtitle = if (resolved != null) {
            "Following strongest mesh node ($resolved)"
        } else {
            "Searching mesh network ($targetSsid)"
        }

        return materializeTelemetry(
            targetKey = targetKey,
            session = session,
            subtitle = subtitle,
            snapshot = snapshot,
            environment = environment,
            rttMeasurement = rttMeasurement,
            normCalibrations = normCalibrations,
            currentElapsedMs = currentElapsedMs,
        )
    }

    private fun ingestFreshObservation(
        session: SessionState,
        bssid: String,
        rawRssiDbm: Int,
        obsTimestampMs: Long,
        isConnectedFastPath: Boolean,
        roamOccurred: Boolean,
    ) {
        val clampedRaw = rawRssiDbm.coerceIn(-115, -5)
        if (roamOccurred) {
            session.kalman.reset()
            session.regressionPoints.clear()
            session.velocitySlopeDbPerSec = 0.0
            session.velocityTrend = VelocityTrend.CALIBRATING
        }

        val filtered = session.kalman.update(clampedRaw, obsTimestampMs)
        session.sparklineBuffer.appendLiveSample(
            timestampElapsedMs = obsTimestampMs,
            rawRssiDbm = clampedRaw,
            smoothedRssiDbm = filtered.smoothedDbm,
            emaRssiDbm = filtered.emaDbm,
            isConnectedSample = isConnectedFastPath,
        )

        if (session.regressionPoints.size >= OLS_MAX_REGRESSION_POINTS) {
            session.regressionPoints.removeFirst()
        }
        session.regressionPoints.addLast(
            RegressionPoint(timestampMs = obsTimestampMs, smoothedDbm = filtered.smoothedDbm)
        )

        val (slope, trend) = computeOlsVelocityAndTrend(
            points = session.regressionPoints,
            previousTrend = session.velocityTrend,
        )
        session.velocitySlopeDbPerSec = slope
        session.velocityTrend = trend

        session.sessionMinRssi = session.sessionMinRssi?.let { minOf(it, clampedRaw) } ?: clampedRaw
        session.sessionMaxRssi = session.sessionMaxRssi?.let { maxOf(it, clampedRaw) } ?: clampedRaw
        session.sessionSumRssi += clampedRaw.toLong()
        session.sessionSampleCount += 1
        session.peakHoldSmoothedDbm = session.peakHoldSmoothedDbm?.let {
            maxOf(it, filtered.smoothedDbm)
        } ?: filtered.smoothedDbm

        session.lastRawRssiDbm = clampedRaw
        session.lastSeenElapsedMs = obsTimestampMs
        session.lastIngestedBssid = bssid
        session.lastIngestedObsMs = obsTimestampMs
        session.consecutiveMissedSweeps = 0
        session.isConnectedFastPath = isConnectedFastPath
    }

    private fun materializeTelemetry(
        targetKey: ProximityTargetKey,
        session: SessionState,
        subtitle: String,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment,
        rttMeasurement: RttMeasurement?,
        normCalibrations: Map<String, Double>,
        currentElapsedMs: Long,
    ): ProximityTelemetry {
        if (session.sessionSampleCount == 0) {
            return ProximityTelemetry(
                targetKey = targetKey,
                resolvedBssid = session.resolvedBssid,
                displayTitle = session.displayTitle,
                displaySubtitle = subtitle,
                band = session.band,
                channel = session.channel,
                frequencyMhz = session.frequencyMhz,
                vendorLabel = session.vendorLabel,
                lockStatus = SignalLockStatus.SEARCHING,
                scanTelemetry = snapshot.scanTelemetry,
            )
        }

        val effectiveNowMs = maxOf(currentElapsedMs, snapshot.timestampElapsedMs)
        val ageMs = (effectiveNowMs - session.lastSeenElapsedMs).coerceAtLeast(0L)

        val lockStatus = when {
            session.consecutiveMissedSweeps >= 2 || ageMs > SIGNAL_LOST_THRESHOLD_MS ->
                SignalLockStatus.SIGNAL_LOST
            session.consecutiveMissedSweeps == 1 || ageMs > STALE_THRESHOLD_MS ->
                SignalLockStatus.STALE
            else -> SignalLockStatus.LOCKED
        }

        val (stabilityPct, stdDevDb) = session.sparklineBuffer.computeRollingRawStability()
        val avgRssi = (session.sessionSumRssi.toDouble() / session.sessionSampleCount.toDouble())
            .finiteOr(session.lastRawRssiDbm?.toDouble() ?: -70.0)
        val user1m = session.resolvedBssid?.let { normCalibrations[it] }

        if (lockStatus == SignalLockStatus.SIGNAL_LOST) {
            session.sparklineBuffer.appendOrUpdateDropoutGap(effectiveNowMs)
            return ProximityTelemetry(
                targetKey = targetKey,
                resolvedBssid = session.resolvedBssid,
                displayTitle = session.displayTitle,
                displaySubtitle = subtitle,
                band = session.band,
                channel = session.channel,
                frequencyMhz = session.frequencyMhz,
                vendorLabel = session.vendorLabel,
                lockStatus = SignalLockStatus.SIGNAL_LOST,
                smoothedRssiDbm = null,
                emaRssiDbm = null,
                rawRssiDbm = null,
                lastKnownRssiDbm = session.lastRawRssiDbm,
                peakHoldRssiDbm = session.peakHoldSmoothedDbm?.finiteOr(-70.0),
                signalQualityPct = 0,
                signalTier = SignalQualityTier.DEAD_ZONE,
                kalmanInnovationDb = 0.0,
                kalmanVarianceDb2 = session.kalman.varianceDb2.finiteOr(6.0),
                distanceEstimate = null,
                isUserCalibrated1m = user1m != null,
                velocitySlopeDbPerSec = session.velocitySlopeDbPerSec.finiteOr(0.0),
                velocityTrend = session.velocityTrend,
                stabilityPct = stabilityPct,
                stdDevDb = stdDevDb,
                minRssiDbm = session.sessionMinRssi,
                maxRssiDbm = session.sessionMaxRssi,
                avgRssiDbm = avgRssi,
                sampleCount = session.sessionSampleCount,
                sparkline = session.sparklineBuffer.toSparklinePoints(),
                geigerIntervalMs = 1400L,
                geigerToneHz = 320.0,
                normalizedProximity0To1 = 0.0,
                lastSeenElapsedMs = session.lastSeenElapsedMs,
                lastSeenAgeMs = ageMs,
                isConnectedFastPath = false,
                isRttActive = false,
                scanTelemetry = snapshot.scanTelemetry,
            )
        }

        val smoothed = session.kalman.xHatDbm.finiteOr(-70.0)
        val ema = session.kalman.emaDbm.finiteOr(smoothed)
        val distanceEstimate = DistanceEstimator.estimate(
            smoothedRssiDbm = smoothed,
            frequencyMhz = session.frequencyMhz,
            band = session.band,
            tpcApTxPowerDbm = session.tpcApTxPowerDbm,
            environment = environment,
            userCalibrated1mDbm = user1m,
            resolvedBssid = session.resolvedBssid,
            rttMeasurement = rttMeasurement,
            currentElapsedMs = effectiveNowMs,
        )
        val isRtt = distanceEstimate.method.isHardwareRtt
        val qualityPct = SemanticFormatters.signalQualityPct(smoothed)
        val tier = SignalQualityTier.fromRssiDbm(smoothed.roundToInt())

        val u = ((smoothed - (-92.0)) / (-28.0 - (-92.0))).finiteCoerceIn(0.0, 1.0, 0.0)
        val geigerIntervalMs = (1400.0 * (85.0 / 1400.0).pow(u))
            .finiteOr(1400.0)
            .roundToLong()
            .coerceIn(85L, 1400L)
        val geigerToneHz = (320.0 + 1080.0 * u.pow(1.35)).finiteCoerceIn(320.0, 1400.0, 320.0)

        return ProximityTelemetry(
            targetKey = targetKey,
            resolvedBssid = session.resolvedBssid,
            displayTitle = session.displayTitle,
            displaySubtitle = subtitle,
            band = session.band,
            channel = session.channel,
            frequencyMhz = session.frequencyMhz,
            vendorLabel = session.vendorLabel,
            lockStatus = lockStatus,
            smoothedRssiDbm = smoothed,
            emaRssiDbm = ema,
            rawRssiDbm = session.lastRawRssiDbm,
            lastKnownRssiDbm = session.lastRawRssiDbm,
            peakHoldRssiDbm = session.peakHoldSmoothedDbm?.finiteOr(smoothed),
            signalQualityPct = qualityPct,
            signalTier = tier,
            kalmanInnovationDb = session.kalman.lastInnovationDb.finiteOr(0.0),
            kalmanVarianceDb2 = session.kalman.varianceDb2.finiteOr(6.0),
            distanceEstimate = distanceEstimate,
            isUserCalibrated1m = user1m != null,
            velocitySlopeDbPerSec = session.velocitySlopeDbPerSec.finiteOr(0.0),
            velocityTrend = session.velocityTrend,
            stabilityPct = stabilityPct,
            stdDevDb = stdDevDb,
            minRssiDbm = session.sessionMinRssi,
            maxRssiDbm = session.sessionMaxRssi,
            avgRssiDbm = avgRssi,
            sampleCount = session.sessionSampleCount,
            sparkline = session.sparklineBuffer.toSparklinePoints(),
            geigerIntervalMs = geigerIntervalMs,
            geigerToneHz = geigerToneHz,
            normalizedProximity0To1 = u,
            lastSeenElapsedMs = session.lastSeenElapsedMs,
            lastSeenAgeMs = ageMs,
            isConnectedFastPath = session.isConnectedFastPath && lockStatus == SignalLockStatus.LOCKED,
            isRttActive = isRtt,
            scanTelemetry = snapshot.scanTelemetry,
        )
    }

    private fun computeOlsVelocityAndTrend(
        points: ArrayDeque<RegressionPoint>,
        previousTrend: VelocityTrend,
    ): Pair<Double, VelocityTrend> {
        if (points.size < 2) return 0.0 to VelocityTrend.CALIBRATING

        val latestMs = points.last().timestampMs
        val recent6s = points.filter { latestMs - it.timestampMs in 0L..OLS_WINDOW_MS }
        val selected = if (recent6s.size >= 4) {
            recent6s
        } else {
            val takeCount = min(points.size, 4)
            points.toList().takeLast(takeCount)
        }

        if (selected.size < 2) return 0.0 to VelocityTrend.CALIBRATING
        val totalSpanMs = selected.last().timestampMs - selected.first().timestampMs
        if (totalSpanMs <= 0L) {
            return if (previousTrend == VelocityTrend.CALIBRATING) {
                0.0 to VelocityTrend.CALIBRATING
            } else {
                0.0 to previousTrend
            }
        }

        val meanStepSec = (totalSpanMs.toDouble() / 1000.0) / (selected.size - 1).toDouble()
        val taus = DoubleArray(selected.size)
        if (meanStepSec > 8.0) {
            for (i in selected.indices) {
                taus[i] = i * 2.5
            }
        } else {
            val t0 = selected.first().timestampMs
            for (i in selected.indices) {
                taus[i] = (selected[i].timestampMs - t0).toDouble() / 1000.0
            }
        }

        val tauMean = taus.average()
        val xMean = selected.sumOf { it.smoothedDbm } / selected.size.toDouble()
        var num = 0.0
        var den = 0.0
        for (i in selected.indices) {
            val dt = taus[i] - tauMean
            val dx = selected[i].smoothedDbm - xMean
            num += dt * dx
            den += dt * dt
        }

        val slope = (num / (den + 1e-6)).finiteCoerceIn(-50.0, 50.0, 0.0)
        val nextTrend = when (previousTrend) {
            VelocityTrend.APPROACHING -> when {
                slope >= 0.25 -> VelocityTrend.APPROACHING
                slope <= -0.55 -> VelocityTrend.MOVING_AWAY
                else -> VelocityTrend.STEADY
            }
            VelocityTrend.MOVING_AWAY -> when {
                slope <= -0.25 -> VelocityTrend.MOVING_AWAY
                slope >= 0.55 -> VelocityTrend.APPROACHING
                else -> VelocityTrend.STEADY
            }
            VelocityTrend.STEADY, VelocityTrend.CALIBRATING -> when {
                slope >= 0.55 -> VelocityTrend.APPROACHING
                slope <= -0.55 -> VelocityTrend.MOVING_AWAY
                else -> VelocityTrend.STEADY
            }
        }

        return slope to nextTrend
    }

    private fun selectStrongestMeshCandidate(
        liveRadios: List<BssidRadio>,
        preferredBssid: String?,
    ): BssidRadio? {
        val normPref = preferredBssid?.let(MacAddressUtils::normalizeBssid)?.takeIf { it.isNotEmpty() }
        return liveRadios.maxWithOrNull(
            compareBy<BssidRadio> { it.rssiDbm }
                .thenBy { normPref != null && it.bssid == normPref }
                .thenByDescending { it.bssid }
        )
    }

    private fun canonicalSessionKey(targetKey: ProximityTargetKey): String = when (targetKey) {
        is ProximityTargetKey.BssidLock -> "bssid:${MacAddressUtils.normalizeBssid(targetKey.bssid)}"
        is ProximityTargetKey.FollowMeshSsid -> "ssid:${targetKey.ssid}"
    }

    private fun normalizeCalibrationKeys(raw: Map<String, Double>): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, Double>(raw.size)
        for ((k, v) in raw) {
            if (v.isFinite()) {
                val norm = MacAddressUtils.normalizeBssid(k)
                if (norm.isNotEmpty()) out[norm] = v
            }
        }
        return out
    }
}
