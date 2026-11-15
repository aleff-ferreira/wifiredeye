@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.obj
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.FrameSpec
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F12`–`F14` (`15` `@Test` methods: `5` per feature).
 * - `F12` 1D Motion-Adaptive Kalman + Time-Aware EMA Filter (`5` tests)
 * - `F13` Handset-Calibrated Path-Loss & RTT Distance Engine (`5` tests)
 * - `F14` Velocity Trend, Stability Score & Bounded Ring Buffers (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F12ToF14ProximityPhysicsEdgeE2eTest {

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
    // F12: 1D Motion-Adaptive Kalman + Time-Aware EMA Filter — Edge Cases (5)
    // =========================================================================

    @Test
    fun f12_t2_01_zeroDeltaTAndBackwardsElapsedClockSkewNeverProduceNanOrInfinityInEmaOrKalman() {
        val bssid = "04:d4:c4:12:11:01"
        val frames = listOf(
            FrameSpec(elapsedMs = 5_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60))),
            FrameSpec(elapsedMs = 5_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -54))), // dt = 0 ms!
            FrameSpec(elapsedMs = 3_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -50))), // dt = -2000 ms backwards!
        )
        val traj = CliJsonLens.proximityFrames(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        for (frame in traj) {
            val s = frame.dbl("smoothedRssiDbm")!!
            val e = frame.dbl("emaRssiDbm")!!
            assertTrue("Smoothed RSSI must be finite ($s)", s.isFinite())
            assertTrue("EMA RSSI must be finite ($e)", e.isFinite())
        }
    }

    @Test
    fun f12_t2_02_androidMinus127DbmDisconnectSentinelRejectedWithoutDraggingKalmanStateDown() {
        val bssid = "04:d4:c4:12:12:01"
        val frames = listOf(
            FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -52))),
            FrameSpec(elapsedMs = 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -52))),
            FrameSpec(elapsedMs = 3_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -127))), // Sentinel glitch!
            FrameSpec(elapsedMs = 4_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -53))),
        )
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        val smooth = latest.dbl("smoothedRssiDbm")!!
        assertTrue("Sentinel -127 dBm must be rejected or clamped so smoothed ($smooth) stays near -52..-58 dBm", smooth > -62.0)
    }

    @Test
    fun f12_t2_03_singleFrameOutlierSpikeDampedMoreThanSustainedGenuineWalk() {
        val bssid = "04:d4:c4:12:13:01"
        val glitchScenario = CliScenarioBuilder.multiFrame(
            listOf(
                FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60))),
                FrameSpec(elapsedMs = 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60))),
                FrameSpec(elapsedMs = 3_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -88))), // 1-frame hand occlusion
                FrameSpec(elapsedMs = 4_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60))),
            ),
        )
        val traj = CliJsonLens.proximityFrames(
            CliRunnerHarness.analyzeStdin(glitchScenario, targetBssid = bssid).assertSucceeded().parseJsonRoot(),
        )
        val duringGlitch = traj[2].dbl("smoothedRssiDbm")!!
        val recovered = traj[3].dbl("smoothedRssiDbm")!!
        assertTrue("1-frame occlusion ($duringGlitch) must not drop all the way to -88 dBm", duringGlitch > -85.0)
        assertTrue("Must recover close to -60 dBm on frame 4 ($recovered)", recovered > -68.0)
    }

    @Test
    fun f12_t2_04_longTwoMinuteOsThrottleGapReconvergesPromptlyToFreshMeasurement() {
        val bssid = "04:d4:c4:12:14:01"
        val frames = listOf(
            FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -82))),
            FrameSpec(elapsedMs = 125_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -46))),
        )
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        val smooth = latest.dbl("smoothedRssiDbm")!!
        assertTrue("After 124s gap, Kalman variance inflation must snap close to -46 dBm, got $smooth", smooth > -54.0)
    }

    @Test
    fun f12_t2_05_constantIdenticalRssiStreamConvergesWithZeroOscillationOrVarianceUnderflow() {
        val bssid = "04:d4:c4:12:15:01"
        val frames = (1..25).map { step ->
            FrameSpec(elapsedMs = step * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -58)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals(-58.0, latest.dbl("smoothedRssiDbm")!!, 0.05)
        assertTrue("Kalman variance must remain strictly positive (> 0)", latest.dbl("kalmanVarianceDb2")!! > 0.0)
    }

    // =========================================================================
    // F13: Handset-Calibrated Path-Loss & RTT Distance Engine — Edge Cases (5)
    // =========================================================================

    @Test
    fun f13_t2_01_closeContactStrongMinus20DbmSignalClampsNearBelowHalfMeter() {
        val bssid = "04:d4:c4:13:11:01"
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -20, frequencyMhz = 5180))),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!

        assertTrue("Meters at -20 dBm (${dist.dbl("meters")}) must clamp <= 0.5 m", dist.dbl("meters")!! <= 0.5)
        assertEquals(true, dist.bool("clampedNear"))
        assertTrue(dist.str("plainEnglishFormatted").orEmpty().contains("0.5") || dist.str("plainEnglishFormatted").orEmpty().contains("Next to Router", ignoreCase = true))
    }

    @Test
    fun f13_t2_02_extremeFaintMinus98DbmSignalClampsFarAt50MetersWithoutAstronomicalKilometers() {
        val bssid = "04:d4:c4:13:12:01"
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -98, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20))),
                targetBssid = bssid,
                env = "OPEN",
            ).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!

        assertTrue("Far distance (${dist.dbl("meters")}) must cap cleanly rather than exploding to 10,000 m", dist.dbl("meters")!! in 40.0..65.0)
        assertEquals(true, dist.bool("clampedFar"))
        assertTrue(dist.str("plainEnglishFormatted").orEmpty().contains("50 m"))
    }

    @Test
    fun f13_t2_03_negativeHardwareRttDistanceMmFromCloseCalibrationOffsetClampsNonNegative() {
        val bssid = "04:d4:c4:13:13:01"
        val scenario = CliScenarioBuilder.singleFrame(
            rttDistanceMm = -180, // Known Android 802.11mc close-range calibration offset < 0 mm!
            rttStdDevMm = 200,
            scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -28, is80211mcResponder = true)),
        )
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(scenario, targetBssid = bssid).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!

        assertTrue("Negative RTT mm must NEVER produce negative meters (${dist.dbl("meters")})", dist.dbl("meters")!! >= 0.0)
        assertFalse("Formatted distance must never show minus sign", dist.str("plainEnglishFormatted").orEmpty().contains("-0."))
    }

    @Test
    fun f13_t2_04_implausibleOutOfRangeTpcReportValuesClampedToSafeHardwareTxBounds() {
        val bssid = "04:d4:c4:13:14:01"
        // IE 35 reporting +90 dBm (1 Megawatt!) bogus Tx power
        val bogusTpc = CliScenarioBuilder.IeSpec(id = 35, hex = "5a00")
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60, frequencyMhz = 5180, ies = listOf(bogusTpc)))),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!

        assertTrue("Clamped TPC must keep indoor distance realistic (${dist.dbl("meters")} m)", dist.dbl("meters")!! in 0.5..60.0)
    }

    @Test
    fun f13_t2_05_6GhzCarrierFrequencyPathLossProducesValidPositiveConfidenceInterval() {
        val bssid = "04:d4:c4:13:15:01"
        val dist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(
                    deviceHas6Ghz = true,
                    scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -64, frequencyMhz = 6135, centerFreq0Mhz = 6185, channelWidthMhz = 160)),
                ),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!

        assertTrue(dist.dbl("meters")!! in 0.5..30.0)
        assertTrue(dist.dbl("rangeMinMeters")!! < dist.dbl("rangeMaxMeters")!!)
    }

    // =========================================================================
    // F14: Velocity Trend, Stability Score & Bounded Ring Buffers — Edge Cases (5)
    // =========================================================================

    @Test
    fun f14_t2_01_singleSampleAndIdenticalTimestampSamplesReportCalibratingOrZeroSlopeWithoutDivideByZero() {
        val bssid = "04:d4:c4:14:11:01"
        val frames = listOf(
            FrameSpec(elapsedMs = 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -60))),
            FrameSpec(elapsedMs = 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -50))),
        )
        val traj = CliJsonLens.proximityFrames(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertTrue("Frame 0 must be CALIBRATING or STEADY", traj[0].str("velocityTrend") in setOf("CALIBRATING", "STEADY"))
        assertTrue("Zero-dt slope must be finite, never NaN/Infinity", traj[1].dbl("velocitySlopeDbPerSec")!!.isFinite())
    }

    @Test
    fun f14_t2_02_subDeadbandSlowDriftStaysSteadyWithoutFlickeringBetweenApproachingAndMovingAway() {
        val bssid = "04:d4:c4:14:12:01"
        val frames = listOf(-60, -60, -61, -60, -60, -59, -60).mapIndexed { i, r ->
            FrameSpec(elapsedMs = (i + 1) * 2_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = r)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals("STEADY", latest.str("velocityTrend"))
        assertTrue(abs(latest.dbl("velocitySlopeDbPerSec")!!) < 0.5)
    }

    @Test
    fun f14_t2_03_wildlyOscillatingMultipathProducesLowStabilityPercentageAndFluctuatingStdDev() {
        val bssid = "04:d4:c4:14:13:01"
        val wild = listOf(-45, -82, -46, -84, -44, -83, -45, -85)
        val frames = wild.mapIndexed { i, r ->
            FrameSpec(elapsedMs = (i + 1) * 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = r)))
        }
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertTrue("Wild 38 dB swings must report stdDevDb > 4.5 dB (Fluctuating), got ${latest.dbl("stdDevDb")}", latest.dbl("stdDevDb")!! > 4.5)
        assertTrue("Stability score must drop well below 70%, got ${latest.int("stabilityPct")}", latest.int("stabilityPct")!! < 70)
    }

    @Test
    fun f14_t2_04_missingTargetFramesRecordExplicitDropoutGapWithoutCorruptingMinMax() {
        val bssid = "04:d4:c4:14:14:01"
        val neighbor = "b0:be:76:14:14:02"
        val frames = listOf(
            FrameSpec(elapsedMs = 1_000L, scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -56), ScanEntrySpec(bssid = neighbor, rssiDbm = -65))),
            FrameSpec(elapsedMs = 15_000L, scans = listOf(ScanEntrySpec(bssid = neighbor, rssiDbm = -65))),
            FrameSpec(elapsedMs = 30_000L, scans = listOf(ScanEntrySpec(bssid = neighbor, rssiDbm = -65))),
            FrameSpec(elapsedMs = 50_000L, scans = listOf(ScanEntrySpec(bssid = neighbor, rssiDbm = -65))),
        )
        val latest = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = bssid)
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals(-56, latest.int("minRssiDbm"))
        assertEquals(-56, latest.int("maxRssiDbm"))
        assertEquals("SIGNAL_LOST", latest.str("lockStatus"))
    }

    @Test
    fun f14_t2_05_fourHundredRotatingTransientStreetBssidsEnforceStrictLruCapWithoutUnboundedGrowth() {
        val anchor = "04:d4:c4:14:15:01"
        val frames = (1..40).map { batch ->
            val streetBssids = (1..10).map { idx ->
                val n = (batch - 1) * 10 + idx
                val hi = (n shr 8) and 0xFF
                val lo = n and 0xFF
                ScanEntrySpec(
                    ssid = "StreetAP_$n",
                    bssid = String.format("b0:be:76:99:%02x:%02x", hi, lo),
                    rssiDbm = -75,
                    timestampMicros = batch * 10_000_000L,
                )
            }
            FrameSpec(
                elapsedMs = batch * 10_000L,
                scans = listOf(ScanEntrySpec(ssid = "AnchorAP", bssid = anchor, rssiDbm = -52, timestampMicros = batch * 10_000_000L)) + streetBssids,
            )
        }
        val root = CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames), targetBssid = anchor)
            .assertSucceeded()
            .parseJsonRoot()
        assertTrue(
            "Active retained BSSID radios (${CliJsonLens.radios(root).size}) must stay bounded <= 256",
            CliJsonLens.radios(root).size <= 256,
        )
    }
}
