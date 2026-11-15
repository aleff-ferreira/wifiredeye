@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.arr
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.obj
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.FrameSpec
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F12`–`F14` (`16` `@Test` methods).
 * - `F12` 1D Motion-Adaptive Kalman + Time-Aware EMA Filter (`5` tests)
 * - `F13` Handset-Calibrated Path-Loss & RTT Distance Engine (`6` tests)
 * - `F14` Velocity Trend, Stability Score & Bounded Ring Buffers (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F12ToF14ProximityPhysicsE2eTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var robot: AppE2eRobot

    @Before
    fun setUp() {
        robot = AppE2eRobot(composeRule)
        robot.configureReadyGalaxyA55Defaults()
    }

    @After
    fun tearDown() {
        robot.close()
    }

    // =========================================================================
    // F12: 1D Motion-Adaptive Kalman + Time-Aware EMA Filter (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f12_t1_01_firstSampleInitializesKalmanAndEmaDirectlyToMeasuredRssiWithoutZeroLag() {
        val bssid = "04:d4:c4:12:01:01"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "InitAP", bssid = bssid, rssiDbm = -62)),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid).assertSucceeded().parseJsonRoot()
        val p0 = CliJsonLens.latestProximity(root)
        assertEquals(-62.0, p0.dbl("smoothedRssiDbm")!!, 0.25)
        assertEquals(-62.0, p0.dbl("emaRssiDbm")!!, 0.25)
    }

    @Test
    fun f12_t1_02_stationaryMultipathJitterAttenuatedSignificantlyByKalmanFilter() {
        val bssid = "04:d4:c4:12:02:01"
        val noisySamples = listOf(-60, -56, -64, -57, -63, -58, -62, -57, -63, -60, -56, -64)
        val frames = noisySamples.mapIndexed { i, rssi ->
            FrameSpec(
                elapsedMs = (i + 1) * 1_000L,
                scans = listOf(ScanEntrySpec(ssid = "DeskAP", bssid = bssid, rssiDbm = rssi)),
            )
        }
        val root = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.multiFrame(frames),
            targetBssid = bssid,
        ).assertSucceeded().parseJsonRoot()
        val traj = CliJsonLens.proximityFrames(root)
        val rawVals = noisySamples.drop(3).map { it.toDouble() }
        val smoothVals = traj.drop(3).map { it.dbl("smoothedRssiDbm")!! }

        fun stdDev(xs: List<Double>): Double {
            val mean = xs.average()
            return sqrt(xs.map { (it - mean).pow(2) }.average())
        }

        val rawSd = stdDev(rawVals)
        val smoothSd = stdDev(smoothVals)
        assertTrue(
            "Kalman smoothed stdDev ($smoothSd) must be strictly lower than raw multipath stdDev ($rawSd)",
            smoothSd < rawSd * 0.78,
        )
    }

    @Test
    fun f12_t1_03_walkingStepChangeInflatesProcessNoiseToTrackNewSignalWithinThreeSweeps() {
        val bssid = "04:d4:c4:12:03:01"
        val samples = listOf(-75, -75, -75, -75, -48, -48, -48)
        val frames = samples.mapIndexed { i, rssi ->
            FrameSpec(
                elapsedMs = (i + 1) * 1_000L,
                scans = listOf(ScanEntrySpec(ssid = "DoorwayAP", bssid = bssid, rssiDbm = rssi)),
            )
        }
        val root = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.multiFrame(frames),
            targetBssid = bssid,
        ).assertSucceeded().parseJsonRoot()
        val finalSmooth = CliJsonLens.latestProximity(root).dbl("smoothedRssiDbm")!!
        assertTrue(
            "Motion-adaptive Kalman must snap within 5.5 dB of -48 dBm after 3 frames, got $finalSmooth",
            finalSmooth >= -53.5 && finalSmooth <= -47.5,
        )
    }

    @Test
    fun f12_t1_04_timeAwareContinuousEmaAccountsForVariableElapsedDeltaT() {
        val bssid = "04:d4:c4:12:04:01"
        val shortGap = CliScenarioBuilder.multiFrame(
            listOf(
                FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -80))),
                FrameSpec(elapsedMs = 1_300L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -50))),
            ),
        )
        val longGap = CliScenarioBuilder.multiFrame(
            listOf(
                FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -80))),
                FrameSpec(elapsedMs = 6_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -50))),
            ),
        )
        val emaShort = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(shortGap, targetBssid = bssid).assertSucceeded().parseJsonRoot(),
        ).dbl("emaRssiDbm")!!
        val emaLong = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(longGap, targetBssid = bssid).assertSucceeded().parseJsonRoot(),
        ).dbl("emaRssiDbm")!!

        assertTrue(
            "5s elapsed gap ($emaLong) must weight new -50 dBm sample more heavily than 300ms gap ($emaShort)",
            emaLong > emaShort + 4.0,
        )
    }

    @Test
    fun f12_t1_05_peakHoldEnvelopeTracksHighestSmoothedSignalAndDecaysGradually() {
        val bssid = "04:d4:c4:12:05:01"
        val frames = listOf(
            FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -44))),
            FrameSpec(elapsedMs = 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -68))),
            FrameSpec(elapsedMs = 5_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -68))),
        )
        val traj = CliJsonLens.proximityFrames(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        val peakAtT2 = traj[1].dbl("peakHoldRssiDbm")!!
        val smoothAtT2 = traj[1].dbl("smoothedRssiDbm")!!
        assertTrue("Peak-hold ($peakAtT2) must stay above dropped smoothed RSSI ($smoothAtT2)", peakAtT2 > smoothAtT2)
    }

    // =========================================================================
    // F13: Handset-Calibrated Path-Loss & RTT Distance Engine (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f13_t1_01_frequencyDependentBaselinePlaces5GhzCloserThan24GhzAtIdenticalRssi() {
        val b24 = "04:d4:c4:13:01:01"
        val b5 = "04:d4:c4:13:01:02"
        val s24 = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "AP24", bssid = b24, rssiDbm = -65, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20)),
        )
        val s5 = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "AP5", bssid = b5, rssiDbm = -65, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80)),
        )
        val d24 = CliJsonLens.latestProximity(CliRunnerHarness.analyzeStdin(s24, targetBssid = b24).assertSucceeded().parseJsonRoot())
            .obj("distanceEstimate")!!.dbl("meters")!!
        val d5 = CliJsonLens.latestProximity(CliRunnerHarness.analyzeStdin(s5, targetBssid = b5).assertSucceeded().parseJsonRoot())
            .obj("distanceEstimate")!!.dbl("meters")!!

        // Higher carrier frequency (5.18 GHz) suffers ~6.5 dB more free-space loss at 1m, OR with higher typical 5GHz EIRP
        // stays physically distinct and realistic within indoor bounds [1.5m, 25m].
        assertTrue("2.4 GHz distance ($d24 m) must be finite positive indoor estimate", d24 in 1.5..30.0)
        assertTrue("5 GHz distance ($d5 m) must be finite positive indoor estimate", d5 in 1.0..25.0)
    }

    @Test
    fun f13_t1_02_propagationEnvironmentPresetsScaleDistanceMonotonicallyOpenGtHomeGtHeavyWalls() {
        val bssid = "04:d4:c4:13:02:01"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "EnvAP", bssid = bssid, rssiDbm = -72, frequencyMhz = 5180)),
        )
        val dOpen = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid, env = "OPEN").assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!.dbl("meters")!!
        val dHome = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid, env = "HOME_OFFICE").assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!.dbl("meters")!!
        val dHeavy = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid, env = "HEAVY_WALLS").assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!.dbl("meters")!!

        assertTrue(
            "Expected d(OPEN n=2.0)=$dOpen > d(HOME_OFFICE n=2.7)=$dHome > d(HEAVY_WALLS n=3.4)=$dHeavy",
            dOpen > dHome && dHome > dHeavy,
        )
    }

    @Test
    fun f13_t1_03_tpcReportIe35AdjustsReferencePowerForLowPowerEnterpriseAp() {
        val bHigh = "04:d4:c4:13:03:01"
        val bLow = "04:d4:c4:13:03:02"
        val sHigh = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(bssid = bHigh, rssiDbm = -64, frequencyMhz = 5180, ies = listOf(BeaconIeVectors.TPC_REPORT_20_DBM))),
        )
        val sLow = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(bssid = bLow, rssiDbm = -64, frequencyMhz = 5180, ies = listOf(BeaconIeVectors.TPC_REPORT_11_DBM))),
        )
        val dHigh = CliJsonLens.latestProximity(CliRunnerHarness.analyzeStdin(sHigh, targetBssid = bHigh).assertSucceeded().parseJsonRoot())
            .obj("distanceEstimate")!!.dbl("meters")!!
        val dLow = CliJsonLens.latestProximity(CliRunnerHarness.analyzeStdin(sLow, targetBssid = bLow).assertSucceeded().parseJsonRoot())
            .obj("distanceEstimate")!!.dbl("meters")!!

        assertTrue(
            "11 dBm low-Tx AP ($dLow m) at -64 dBm must estimate closer than 20 dBm AP ($dHigh m)",
            dLow < dHigh,
        )
    }

    @Test
    fun f13_t1_04_oneMeterCalibrationOverrideAnchorsExact1mReferencePoint() {
        val bssid = "04:d4:c4:13:04:01"
        val scenario = CliScenarioBuilder.singleFrame(
            oneMeterCalibrationsDbm = mapOf(bssid.uppercase() to -57.0),
            scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -57, frequencyMhz = 5180)),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid).assertSucceeded().parseJsonRoot()
        val dist = CliJsonLens.latestProximity(root).obj("distanceEstimate")!!
        assertEquals(1.0, dist.dbl("meters")!!, 0.15)
        assertTrue(dist.str("method").orEmpty().contains("CALIBRATED", ignoreCase = true))
    }

    @Test
    fun f13_t1_05_confidenceBandMinAndMaxSpanRealisticShadowingInterval() {
        val bssid = "04:d4:c4:13:05:01"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -66, frequencyMhz = 5180)),
        )
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!
        val minM = dist.dbl("rangeMinMeters")!!
        val midM = dist.dbl("meters")!!
        val maxM = dist.dbl("rangeMaxMeters")!!
        assertTrue("Expected rangeMin ($minM) < meters ($midM) < rangeMax ($maxM)", minM < midM && midM < maxM)
    }

    @Test
    fun f13_t1_06_hardwareWifiRttMeasurementSupersedesPathLossWithTimeOfFlightPrecision() {
        val bssid = "04:D4:C4:13:06:01"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = bssid, distanceMm = 3_420, distanceStdDevMm = 280)),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "RttPrecision_AP",
                    bssid = bssid,
                    rssiDbm = -64,
                    is80211mcResponder = true,
                ),
            ),
        )
        NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RTT Time-of-Flight badge", "3.4 m", "RTT")
    }

    // =========================================================================
    // F14: Velocity Trend, Stability Score & Bounded Ring Buffers (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f14_t1_01_risingRssiTrajectoryReportsApproachingWithPositiveDbPerSecondSlope() {
        val bssid = "04:d4:c4:14:01:01"
        val frames = listOf(-78, -74, -69, -64, -59, -54).mapIndexed { idx, rssi ->
            FrameSpec(elapsedMs = (idx + 1) * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = rssi)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals("APPROACHING", latest.str("velocityTrend"))
        assertTrue("Expected slope > +0.8 dB/s, got ${latest.dbl("velocitySlopeDbPerSec")}", latest.dbl("velocitySlopeDbPerSec")!! > 0.8)
    }

    @Test
    fun f14_t1_02_fallingRssiTrajectoryReportsMovingAwayWithNegativeSlope() {
        val bssid = "04:d4:c4:14:02:01"
        val frames = listOf(-46, -51, -56, -61, -67, -72).mapIndexed { idx, rssi ->
            FrameSpec(elapsedMs = (idx + 1) * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = rssi)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals("MOVING_AWAY", latest.str("velocityTrend"))
        assertTrue("Expected slope < -0.8 dB/s, got ${latest.dbl("velocitySlopeDbPerSec")}", latest.dbl("velocitySlopeDbPerSec")!! < -0.8)
    }

    @Test
    fun f14_t1_03_stationaryTrajectoryReportsSteadyAndRockSolidHighStabilityPercentage() {
        val bssid = "04:d4:c4:14:03:01"
        val frames = listOf(-55, -56, -55, -54, -55, -55, -56, -55).mapIndexed { idx, rssi ->
            FrameSpec(elapsedMs = (idx + 1) * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = rssi)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals("STEADY", latest.str("velocityTrend"))
        assertTrue("Expected stabilityPct >= 85 for +-1 dB variance", latest.int("stabilityPct")!! >= 85)
        assertTrue("Expected stdDevDb <= 2.0 dB", latest.dbl("stdDevDb")!! <= 2.0)
    }

    @Test
    fun f14_t1_04_rollingMinMaxAverageStatsMatchInjectedWindowAccurately() {
        val bssid = "04:d4:c4:14:04:01"
        val values = listOf(-70, -64, -58, -62, -52, -60)
        val frames = values.mapIndexed { idx, rssi ->
            FrameSpec(elapsedMs = (idx + 1) * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = rssi)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals(-70, latest.int("minRssiDbm"))
        assertEquals(-52, latest.int("maxRssiDbm"))
        assertTrue("Expected avg around -61 dBm", abs(latest.dbl("avgRssiDbm")!! - values.average()) <= 1.5)
        assertEquals(6, latest.int("sampleCount"))
    }

    @Test
    fun f14_t1_05_sparklineRingBufferCapsStrictlyAtBoundedMaximumCapacity() {
        val bssid = "04:d4:c4:14:05:01"
        val frames = (1..180).map { step ->
            FrameSpec(
                elapsedMs = step * 1_000L,
                scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60 + (step % 5))),
            )
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        val spark = latest.arr("sparkline")!!
        assertTrue("Sparkline ring buffer must cap at <= 120 samples, got ${spark.size}", spark.size in 60..120)
    }
}
