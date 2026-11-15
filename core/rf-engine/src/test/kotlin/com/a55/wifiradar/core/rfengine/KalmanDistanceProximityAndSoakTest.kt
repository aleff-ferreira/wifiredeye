package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.VelocityTrend
import com.a55.wifiradar.core.model.WifiBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

class KalmanDistanceProximityAndSoakTest {

    @Test
    fun kalmanFilter_dampsStationaryMultipathConvergesFastOnStepJumpAndResetsAfter15sGap() {
        val filter = KalmanSignalFilter()
        val stationarySamples = listOf(-60, -65, -56, -61, -59)
        val smoothedOutputs = stationarySamples.mapIndexed { idx, rssi ->
            filter.update(rssi, timestampElapsedMs = idx * 1_000L).smoothedDbm
        }

        // First sample initializes immediately without lag from 0 or -100
        assertEquals(-60.0, smoothedOutputs[0], 1e-6)

        val rawMean = stationarySamples.average()
        val rawVariance = stationarySamples.sumOf { (it - rawMean) * (it - rawMean) } / stationarySamples.size
        val smoothMean = smoothedOutputs.average()
        val smoothVariance = smoothedOutputs.sumOf { (it - smoothMean) * (it - smoothMean) } / smoothedOutputs.size
        assertTrue(
            "Expected Kalman smoothed variance ($smoothVariance) < raw variance ($rawVariance)",
            smoothVariance < rawVariance,
        )
        assertTrue(abs(smoothedOutputs.last() - (-60.0)) < 1.5)

        // Rapid step jump to -42 dBm (|innovation| > 6 dB) -> converges within <= 3 samples!
        var stepOut = 0.0
        for (k in 1..3) {
            stepOut = filter.update(-42, timestampElapsedMs = (4 + k) * 1_000L).smoothedDbm
        }
        assertTrue("Expected step convergence near -42 dBm within 3 samples, got $stepOut", abs(stepOut - (-42.0)) <= 1.0)

        // > 15s gap resets filter state directly to the new reading
        val afterGap = filter.update(-77, timestampElapsedMs = 30_000L)
        assertEquals(-77.0, afterGap.smoothedDbm, 1e-6)
        assertEquals(KalmanSignalFilter.MEASUREMENT_NOISE_R_DB2, afterGap.varianceDb2, 1e-6)
    }

    @Test
    fun distanceEstimator_scalesMonotonicallyWithEnvironmentHonors1mCalibrationAndUpgradesToRtt() {
        val openEst = DistanceEstimator.estimatePathLoss(
            smoothedRssiDbm = -70.0,
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            environment = PropagationEnvironment.OPEN,
        )
        val homeEst = DistanceEstimator.estimatePathLoss(
            smoothedRssiDbm = -70.0,
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            environment = PropagationEnvironment.HOME_OFFICE,
        )
        val heavyEst = DistanceEstimator.estimatePathLoss(
            smoothedRssiDbm = -70.0,
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            environment = PropagationEnvironment.HEAVY_WALLS,
        )

        assertTrue(openEst.meters > homeEst.meters)
        assertTrue(homeEst.meters > heavyEst.meters)
        assertEquals(DistanceMethod.PATH_LOSS_ESTIMATE, homeEst.method)

        // User 1m calibration at -49.0 dBm -> reading -49.0 dBm yields exactly 1.0 m!
        val calibratedAt1m = DistanceEstimator.estimatePathLoss(
            smoothedRssiDbm = -49.0,
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            environment = PropagationEnvironment.HOME_OFFICE,
            userCalibrated1mDbm = -49.0,
        )
        assertEquals(DistanceMethod.USER_CALIBRATED_PATH_LOSS, calibratedAt1m.method)
        assertEquals(1.0, calibratedAt1m.meters, 0.01)

        // Negative RTT rawDistanceMm (-180 mm right beside AP) clamps safely to < 0.3 m without throwing
        val rttNear = DistanceEstimator.estimate(
            smoothedRssiDbm = -32.0,
            frequencyMhz = 5180,
            band = WifiBand.BAND_5_GHZ,
            resolvedBssid = "04:92:26:00:00:01",
            rttMeasurement = RttMeasurement(
                bssid = "04:92:26:00:00:01",
                distanceMeters = -0.18,
                rawDistanceMm = -180,
                distanceStdDevMeters = 0.25,
                rssiDbm = -32,
                is80211azNtb = true,
                timestampElapsedMs = 5_000L,
            ),
            currentElapsedMs = 5_200L,
        )
        assertEquals(DistanceMethod.RTT_80211AZ, rttNear.method)
        assertTrue(rttNear.clampedNear)
        assertTrue(rttNear.plainEnglishFormatted.contains("< 0.3 m"))
    }

    @Test
    fun proximitySession_tracksWalkVelocityGeigerDropoutRecoveryAndFollowMeshHysteresis() {
        val engine = DefaultRfAnalyticsEngine()
        val targetMac = "04:92:26:11:22:01"
        val targetLock = ProximityTargetKey.BssidLock(targetMac, "RouterLab")

        // Before target ever appears -> SEARCHING
        val emptySnap = engine.processScanSnapshot(rawBatch = RawScanBatch(emptyList(), 0L), currentElapsedMs = 0L)
        val searching = engine.updateProximityTarget(targetLock, emptySnap, currentElapsedMs = 0L)
        assertEquals(SignalLockStatus.SEARCHING, searching.lockStatus)
        assertNull(searching.smoothedRssiDbm)

        // Approaching walk: -82 -> -74 -> -65 -> -54 -> -44 dBm (1s apart)
        val walkRssis = listOf(-82, -74, -65, -54, -44)
        var lastTel = searching
        walkRssis.forEachIndexed { idx, rssi ->
            val tMs = (idx + 1) * 1_000L
            val snap = engine.processScanSnapshot(
                rawBatch = RawScanBatch(
                    entries = listOf(
                        RawScanEntry(
                            bssid = targetMac,
                            rawSsidBytes = "RouterLab".encodeToByteArray(),
                            rssiDbm = rssi,
                            frequencyMhz = 5180,
                            timestampMicros = tMs * 1_000L,
                        )
                    ),
                    receivedElapsedMs = tMs,
                ),
                currentElapsedMs = tMs,
            )
            lastTel = engine.updateProximityTarget(targetLock, snap, currentElapsedMs = tMs)
        }

        assertEquals(SignalLockStatus.LOCKED, lastTel.lockStatus)
        assertEquals(VelocityTrend.APPROACHING, lastTel.velocityTrend)
        assertTrue(lastTel.velocitySlopeDbPerSec > 0.55)
        assertEquals(-82, lastTel.minRssiDbm)
        assertEquals(-44, lastTel.maxRssiDbm)
        assertEquals(5, lastTel.sampleCount)
        assertTrue(lastTel.geigerIntervalMs in 85L..400L)
        assertTrue(lastTel.geigerToneHz > 700.0)

        // 1 missed sweep at t = 10s -> STALE
        val snapMissed1 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(emptyList(), 10_000L),
            currentElapsedMs = 10_000L,
        )
        val staleTel = engine.updateProximityTarget(targetLock, snapMissed1, currentElapsedMs = 10_000L)
        assertEquals(SignalLockStatus.STALE, staleTel.lockStatus)

        // 2nd missed sweep at t = 15s & prolonged dropout through t = 25s -> SIGNAL_LOST without losing Min/Max or evicting sparkline!
        var lostTel = staleTel
        for (tMs in listOf(15_000L, 20_000L, 25_000L)) {
            val snapMissed = engine.processScanSnapshot(
                rawBatch = RawScanBatch(emptyList(), tMs),
                currentElapsedMs = tMs,
            )
            lostTel = engine.updateProximityTarget(targetLock, snapMissed, currentElapsedMs = tMs)
        }
        assertEquals(SignalLockStatus.SIGNAL_LOST, lostTel.lockStatus)
        assertNull(lostTel.smoothedRssiDbm)
        assertEquals(-44, lostTel.lastKnownRssiDbm)
        assertEquals(-82, lostTel.minRssiDbm)
        assertEquals(-44, lostTel.maxRssiDbm)
        assertEquals("Only 1 gap marker should exist across prolonged dropout", 1, lostTel.sparkline.count { it.isDropoutGap })
        assertEquals(5, lostTel.sparkline.count { !it.isDropoutGap })

        // Beacon reappears at t = 28s -> automatic re-acquisition to LOCKED!
        val snapRecovered = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(
                        bssid = targetMac,
                        rawSsidBytes = "RouterLab".encodeToByteArray(),
                        rssiDbm = -58,
                        frequencyMhz = 5180,
                        timestampMicros = 28_000_000L,
                    )
                ),
                receivedElapsedMs = 28_000L,
            ),
            currentElapsedMs = 28_000L,
        )
        val recoveredTel = engine.updateProximityTarget(targetLock, snapRecovered, currentElapsedMs = 28_000L)
        assertEquals(SignalLockStatus.LOCKED, recoveredTel.lockStatus)
        assertNotNull(recoveredTel.smoothedRssiDbm)
        assertEquals(6, recoveredTel.sampleCount)

        // FollowMeshSsid 4 dB roam hysteresis test
        val meshKey = ProximityTargetKey.FollowMeshSsid("MeshWalk")
        val snapMesh1 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(bssid = "04:92:26:AA:00:01", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -60, frequencyMhz = 5180, timestampMicros = 30_000_000L),
                    RawScanEntry(bssid = "04:92:26:AA:00:02", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -62, frequencyMhz = 5745, timestampMicros = 30_000_000L),
                ),
                receivedElapsedMs = 30_000L,
            ),
            currentElapsedMs = 30_000L,
        )
        val meshTel1 = engine.updateProximityTarget(meshKey, snapMesh1, currentElapsedMs = 30_000L)
        assertEquals("04:92:26:AA:00:01", meshTel1.resolvedBssid)

        // Node 2 rises by +2 dB over Node 1 (-58 vs -60) -> < 4 dB hysteresis, stays on Node 1!
        val snapMesh2 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(bssid = "04:92:26:AA:00:01", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -60, frequencyMhz = 5180, timestampMicros = 32_000_000L),
                    RawScanEntry(bssid = "04:92:26:AA:00:02", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -58, frequencyMhz = 5745, timestampMicros = 32_000_000L),
                ),
                receivedElapsedMs = 32_000L,
            ),
            currentElapsedMs = 32_000L,
        )
        val meshTel2 = engine.updateProximityTarget(meshKey, snapMesh2, currentElapsedMs = 32_000L)
        assertEquals("04:92:26:AA:00:01", meshTel2.resolvedBssid)

        // Node 2 rises to -54 dB (>= +4 dB stronger than -60) -> roams cleanly to Node 2!
        val snapMesh3 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(bssid = "04:92:26:AA:00:01", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -60, frequencyMhz = 5180, timestampMicros = 34_000_000L),
                    RawScanEntry(bssid = "04:92:26:AA:00:02", rawSsidBytes = "MeshWalk".encodeToByteArray(), rssiDbm = -54, frequencyMhz = 5745, timestampMicros = 34_000_000L),
                ),
                receivedElapsedMs = 34_000L,
            ),
            currentElapsedMs = 34_000L,
        )
        val meshTel3 = engine.updateProximityTarget(meshKey, snapMesh3, currentElapsedMs = 34_000L)
        assertEquals("04:92:26:AA:00:02", meshTel3.resolvedBssid)

        engine.resetProximityTarget(targetLock)
        val afterReset = engine.updateProximityTarget(targetLock, emptySnap, currentElapsedMs = 35_000L)
        assertEquals(SignalLockStatus.SEARCHING, afterReset.lockStatus)
        assertEquals(0, afterReset.sampleCount)
    }

    @Test
    fun tier5SoakAndConcurrencyTest_10000ScanBatchesAcross2500RotatingBssids_strictlyBoundedAndFinite() {
        val engine = DefaultRfAnalyticsEngine()
        val totalUniqueBssids = 2_500
        val bssidPool = Array(totalUniqueBssids) { idx ->
            String.format(
                Locale.US,
                "04:92:26:%02X:%02X:%02X",
                (idx ushr 16) and 0xFF,
                (idx ushr 8) and 0xFF,
                idx and 0xFF,
            )
        }
        val ssidBytes = "AirportWalk_5G".encodeToByteArray()

        var latestSnapshot = engine.processScanSnapshot(null)
        for (tick in 0 until 10_000) {
            val tMs = (tick + 1) * 1_000L
            val baseIdx = (tick * 3) % totalUniqueBssids
            val entries = List(3) { offset ->
                val mac = bssidPool[(baseIdx + offset) % totalUniqueBssids]
                val extremeRssi = when {
                    tick % 500 == 0 && offset == 0 -> Int.MIN_VALUE
                    tick % 500 == 1 && offset == 0 -> 0
                    tick % 500 == 2 && offset == 0 -> -127
                    else -> -45 - ((tick + offset * 7) % 45)
                }
                RawScanEntry(
                    bssid = mac,
                    rawSsidBytes = ssidBytes,
                    rssiDbm = extremeRssi,
                    frequencyMhz = if (offset == 0) 2412 else 5180,
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    wifiStandardCode = 6,
                    timestampMicros = tMs * 1_000L,
                )
            }
            latestSnapshot = engine.processScanSnapshot(
                rawBatch = RawScanBatch(entries = entries, receivedElapsedMs = tMs),
                connectedWifi = ConnectedWifiObservation(
                    bssid = bssidPool[0],
                    ssid = "\"AirportWalk_5G\"",
                    rssiDbm = -58,
                    frequencyMhz = 2412,
                    timestampElapsedMs = tMs,
                ),
                currentElapsedMs = tMs,
            )

            if (tick % 1_000 == 999) {
                assertTrue(
                    "LRU cache exceeded 256 at tick $tick: ${engine.trackedBssidCacheSizeForTest()}",
                    engine.trackedBssidCacheSizeForTest() <= DefaultRfAnalyticsEngine.MAX_TRACKED_BSSIDS,
                )
            }
        }

        assertTrue(engine.trackedBssidCacheSizeForTest() <= DefaultRfAnalyticsEngine.MAX_TRACKED_BSSIDS)
        assertTrue(latestSnapshot.rssiTimelines.size <= DefaultRfAnalyticsEngine.MAX_TRACKED_BSSIDS)
        for ((_, samples) in latestSnapshot.rssiTimelines) {
            assertTrue("Timeline exceeded 120 samples: ${samples.size}", samples.size <= 120)
            samples.forEach { s ->
                assertFalse(s.smoothedRssiDbm?.isNaN() == true || s.smoothedRssiDbm?.isInfinite() == true)
            }
        }

        // Concurrent multi-coroutine stress across Dispatchers.Default
        runBlocking(Dispatchers.Default) {
            val jobs = (1..8).map { workerId ->
                async {
                    repeat(50) { step ->
                        val ts = 20_000_000L + (workerId * 100L + step)
                        val snap = engine.processScanSnapshot(
                            rawBatch = RawScanBatch(
                                entries = listOf(
                                    RawScanEntry(
                                        bssid = bssidPool[(workerId * 10 + step) % 50],
                                        rawSsidBytes = ssidBytes,
                                        rssiDbm = -55 - (step % 20),
                                        frequencyMhz = 5180,
                                        timestampMicros = ts * 1_000L,
                                    )
                                ),
                                receivedElapsedMs = ts,
                            ),
                            currentElapsedMs = ts,
                        )
                        val prox = engine.updateProximityTarget(
                            targetKey = ProximityTargetKey.BssidLock(bssidPool[workerId]),
                            snapshot = snap,
                            currentElapsedMs = ts,
                        )
                        assertTrue(prox.kalmanVarianceDb2.isFinite())
                    }
                }
            }
            jobs.awaitAll()
        }
        assertTrue(engine.trackedBssidCacheSizeForTest() <= DefaultRfAnalyticsEngine.MAX_TRACKED_BSSIDS)
    }
}
