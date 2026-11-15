@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier3

import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack

/**
 * Tier 3 Cross-Feature Pairwise Interaction E2E Tests: Proximity, Physics, Geiger & Roaming (`T3_08`–`T3_15` = `8` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier3ProximityPhysicsInteractionsE2eTest {

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

    @Test
    fun t3_08_f12_f13_f23_f28_kalmanSmoothedCalibrationAnchors1mAndScalesAcrossPropagationEnvironments() {
        val bssid = "04:D4:C4:73:08:01"
        robot.launchApp()
        for ((idx, rssi) in listOf(-56, -54, -55).withIndex()) {
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "CalPhysics_5G", bssid = bssid, rssiDbm = rssi, explicitTimestampMicros = (idx + 1) * 1_000_000L)),
            )
            if (idx == 0) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)
        }
        val prox = NavigationRobot(composeRule).openProximity()
        prox.clickCalibrateOneMeter()
        val at1m = prox.readDisplayedDistanceMeters()
        assertTrue("Calibrated 1m distance ($at1m m) must anchor around 1.0 m", at1m in 0.65..1.45)

        // Step back 14 dB to -69 dBm -> Open Space > Home/Office > Heavy Concrete
        for (step in 4..7) {
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "CalPhysics_5G", bssid = bssid, rssiDbm = -69, explicitTimestampMicros = step * 1_000_000L)),
            )
            robot.advanceClockMs(1_000L)
        }
        prox.selectEnvironment("Open Space", "Open")
        val dOpen = prox.readDisplayedDistanceMeters()
        prox.selectEnvironment("Heavy Concrete", "Heavy")
        val dHeavy = prox.readDisplayedDistanceMeters()
        assertTrue("Expected calibrated Open ($dOpen m) > Heavy Concrete ($dHeavy m) at -69 dBm", dOpen > dHeavy)
    }

    @Test
    fun t3_09_f13_f18_f28_f34_rttPrecisionActiveThenMidWalkRttFailureFallsBackSeamlesslyToPathLoss() {
        val bssid = "04:D4:C4:73:09:01"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = bssid, distanceMm = 4_100, distanceStdDevMm = 300)),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RttThenPathLoss", bssid = bssid, rssiDbm = -64, is80211mcResponder = true)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Initial RTT precision", "4.1 m", "RTT")

        // Mid-walk RTT responder fails ranging -> must seamlessly fall back to path-loss without crash!
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = bssid, distanceMm = 0, status = RangingResult.STATUS_FAIL)),
        )
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RttThenPathLoss", bssid = bssid, rssiDbm = -64, is80211mcResponder = true)),
        )
        robot.advanceClockMs(1_500L)
        assertTrue("Fallback path-loss distance must remain finite positive", prox.readDisplayedDistanceMeters() in 0.5..35.0)
    }

    @Test
    fun t3_10_f12_f14_f22_f28_approachingWalkAcceleratesGeigerPulsesAndDisplaysApproachingVelocity() {
        val audioBursts = AtomicInteger(0)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) audioBursts.incrementAndGet()
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val bssid = "04:D4:C4:73:10:01"
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "GeigerWalkAP", bssid = bssid, rssiDbm = -82, explicitTimestampMicros = 1_000_000L)),
            )
            val prox = NavigationRobot(composeRule).openProximity()
            prox.toggleHapticGeiger()
            prox.toggleAudioGeiger()

            audioBursts.set(0)
            robot.advanceClockMs(1_600L)
            val burstsAtWeak82 = audioBursts.get()

            for ((i, r) in listOf(-72, -62, -50, -38).withIndex()) {
                robot.injectScanBatch(
                    listOf(ScanResultFactory.createScanResult(ssid = "GeigerWalkAP", bssid = bssid, rssiDbm = r, explicitTimestampMicros = (i + 2) * 1_000_000L)),
                )
                robot.advanceClockMs(800L)
            }
            audioBursts.set(0)
            robot.advanceClockMs(1_600L)
            val burstsAtClose38 = audioBursts.get()

            assertTrue(
                "Close -38 dBm Geiger burst rate ($burstsAtClose38) must strictly exceed weak -82 dBm rate ($burstsAtWeak82)",
                burstsAtClose38 > burstsAtWeak82,
            )
            ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Approaching trend", "Approaching", "Excellent")
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun t3_11_f14_f22_f28_f29_targetDropoutMutesGeigerPreservesMinMaxAndResumesPulsesOnReacquisition() {
        val audioBursts = AtomicInteger(0)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) audioBursts.incrementAndGet()
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val bssid = "04:D4:C4:73:11:01"
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "DropoutCycleAP", bssid = bssid, rssiDbm = -50)),
            )
            val prox = NavigationRobot(composeRule).openProximity()
            prox.toggleAudioGeiger()
            robot.advanceClockMs(800L)

            repeat(4) {
                robot.advanceClockMs(14_000L)
                robot.injectScanBatch(emptyList())
            }
            ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Signal Lost", "Signal Lost")
            audioBursts.set(0)
            robot.advanceClockMs(2_000L)
            assertEquals("Geiger must remain silent during Signal Lost", 0, audioBursts.get())

            // Router returns -> auto re-acquisition + Geiger resumes automatically!
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "DropoutCycleAP", bssid = bssid, rssiDbm = -44)),
            )
            robot.advanceClockMs(1_500L)
            assertTrue("Geiger pulses must resume automatically on re-acquisition", audioBursts.get() > 0)
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun t3_12_f20_f21_f27_f28_connectedTargetContinues300msLiveProximityUpdatesWhileOsScanThrottleExhausted() {
        val bssid = "AC:84:C6:73:12:01"
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ThrottledFastPath_5G", bssid = bssid, rssiDbm = -76, frequencyMhz = 5180)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "ThrottledFastPath_5G", bssid = bssid, rssiDbm = -76, frequencyMhz = 5180),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks()
        repeat(4) { runCatching { networks.triggerManualRefresh() } }
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)

        nav.openProximity()
        for (rssi in listOf(-66, -56, -46, -44)) {
            robot.simulateConnectedWifi(
                ScanResultFactory.createConnectedWifiInfo(ssid = "ThrottledFastPath_5G", bssid = bssid, rssiDbm = rssi, frequencyMhz = 5180),
            )
            robot.advanceClockMs(350L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Live 300ms update despite OS throttle", "Excellent", "-44 dBm", "-45 dBm", "-46 dBm")
    }

    @Test
    fun t3_13_f08_f21_f27_f31_followMeshWalkWithConnectedBssidRoamUpdatesProximityAndLogsRoamTimeline() {
        val livingRoom = "04:D4:C4:73:13:01"
        val upstairs = "04:D4:C4:73:13:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "WholeHouseMesh", bssid = livingRoom, rssiDbm = -50, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "WholeHouseMesh", bssid = upstairs, rssiDbm = -76, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "WholeHouseMesh", bssid = livingRoom, rssiDbm = -50, frequencyMhz = 5180),
        )
        val nav = NavigationRobot(composeRule)
        nav.openProximity().switchToFollowMeshMode()

        // Walk upstairs: upstairs node becomes -47 dBm and phone roams to upstairs!
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "WholeHouseMesh", bssid = livingRoom, rssiDbm = -79, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "WholeHouseMesh", bssid = upstairs, rssiDbm = -47, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "WholeHouseMesh", bssid = upstairs, rssiDbm = -47, frequencyMhz = 5745),
        )
        robot.advanceClockMs(1_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Proximity followed upstairs node", "73:13:02")

        nav.openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Roam event logged", "73:13:02")
    }

    @Test
    fun t3_14_f10_f13_f26_f28_lowPowerCiscoTpcIe35AdjustsProximityDistanceCloserThanHighPowerConsumerAp() {
        val lowTxCisco = "00:40:96:73:14:01"
        val highTxHome = "04:D4:C4:73:14:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "CiscoMicroCell_11dBm",
                    bssid = lowTxCisco,
                    rssiDbm = -64,
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.TPC_REPORT_11_DBM.toScanResultIe(), BeaconIeVectors.VENDOR_CISCO_CCX_AP_NAME.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "HomeHighPower_20dBm",
                    bssid = highTxHome,
                    rssiDbm = -64,
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.TPC_REPORT_20_DBM.toScanResultIe()),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val detail = nav.openNetworks().openDetailSheetFor("CiscoMicroCell_11dBm")
        detail.assertDetailContains("HQ-3F-AP12")
        val prox = detail.clickMeasureProximity()
        val distLowTx = prox.readDisplayedDistanceMeters()

        prox.selectTargetViaPicker("HomeHighPower_20dBm")
        val distHighTx = prox.readDisplayedDistanceMeters()

        assertTrue(
            "11 dBm Cisco micro-cell ($distLowTx m) at -64 dBm must estimate closer than 20 dBm AP ($distHighTx m)",
            distLowTx < distHighTx,
        )
    }

    @Test
    fun t3_15_f22_f27_f33_f35_lockedTargetSurvivesRotationWhileLifecycleBackgroundingImmediatelyHaltsGeiger() {
        val writtenInBg = AtomicInteger(0)
        var inBg = false
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (inBg) writtenInBg.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "StrongOther", bssid = "B0:BE:76:73:15:01", rssiDbm = -44),
                    ScanResultFactory.createScanResult(ssid = "LockedLifecycleAP", bssid = "04:D4:C4:73:15:02", rssiDbm = -60),
                ),
            )
            val nav = NavigationRobot(composeRule)
            val prox = nav.openNetworks().launchProximityFor("LockedLifecycleAP")
            prox.toggleAudioGeiger()
            robot.recreateApp()
            ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Target survived rotation", "LockedLifecycleAP")

            robot.moveToLifecycle(Lifecycle.State.CREATED)
            inBg = true
            robot.advanceClockMs(4_000L)
            assertEquals("Backgrounding must halt Geiger audio completely", 0, writtenInBg.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }
}
