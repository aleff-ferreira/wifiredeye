@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
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
 * Tier 2 Boundary, Corner, Dropout & Negative E2E Tests for Features `F27`–`F29` (`18` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F27ToF29ProximityHomingEdgeE2eTest {

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
    // F27: 1-Tap Proximity Launch, Target Picker & BSSID/SSID Lock — Edge (6)
    // =========================================================================

    @Test
    fun f27_t2_01_launchingProximityForHiddenNetworkLocksStrictlyByBssidNeverMatchingOtherHiddenAps() {
        val targetHidden = "04:D4:C4:27:11:01"
        val decoyHidden = "FC:EC:DA:27:11:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = targetHidden, rssiDbm = -68),
                ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = decoyHidden, rssiDbm = -44),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.switchToAllRadiosMode()
        networks.launchProximityFor(targetHidden)

        // Hidden network cannot use Follow-SSID by empty string; must stay locked to targetHidden!
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Locked to exact hidden BSSID", "27:11:01")
    }

    @Test
    fun f27_t2_02_followMeshSsidModeUsesHysteresisPreventingRapidPingPongBetween1DbEqualNodes() {
        val nodeA = "04:D4:C4:27:12:01"
        val nodeB = "04:D4:C4:27:12:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "EqualMesh", bssid = nodeA, rssiDbm = -56, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "EqualMesh", bssid = nodeB, rssiDbm = -62, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.switchToFollowMeshMode()

        //Tiny 1 dB inversion (-58 vs -57) within hysteresis band -> should hold stable lock without jitter!
        robot.advanceClockMs(1_000L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "EqualMesh", bssid = nodeA, rssiDbm = -58, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "EqualMesh", bssid = nodeB, rssiDbm = -57, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F27-MeshHysteresis")
    }

    @Test
    fun f27_t2_03_switchingTargetMidSessionResetsPreviousRouterKalmanAndSparklineHistory() {
        val strongA = "04:D4:C4:27:13:01"
        val weakB = "FC:EC:DA:27:13:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "StrongRouterA", bssid = strongA, rssiDbm = -38),
                ScanResultFactory.createScanResult(ssid = "WeakRouterB", bssid = weakB, rssiDbm = -82),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Initial StrongRouterA", "-38 dBm")

        // Switch to WeakRouterB (-82 dBm) -> must immediately display ~-82 dBm, NOT slowly bleed down from -38 dBm!
        prox.selectTargetViaPicker("WeakRouterB")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Immediate reset to WeakRouterB -82 dBm", "-82 dBm", "Weak")
    }

    @Test
    fun f27_t2_04_ssidContainingColonsAndPipesRoundTripsThroughSavedStateTargetKeyWithoutCorruption() {
        val colonSsid = "Cafe:Guest:5G|2F"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = colonSsid, bssid = "04:D4:C4:27:14:01", rssiDbm = -53)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().launchProximityFor(colonSsid)
        robot.recreateApp()
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Colon SSID preserved across recreate", colonSsid)
    }

    @Test
    fun f27_t2_05_singleRadioNonMeshNetworkHandlesFollowMeshToggleGracefully() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SoloRouterOnly", bssid = "04:D4:C4:27:15:01", rssiDbm = -55)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        runCatching { prox.switchToFollowMeshMode() }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "SoloRouterOnly still tracked", "SoloRouterOnly")
    }

    @Test
    fun f27_t2_06_rapidFiveTargetSwitchesSettleStrictlyOnFinalChosenRouter() {
        val routers = (1..5).map { i ->
            ScanResultFactory.createScanResult(
                ssid = "RapidPick_$i",
                bssid = "04:D4:C4:27:16:0$i",
                rssiDbm = -45 - i * 5,
            )
        }
        robot.launchApp()
        robot.injectScanBatch(routers)
        val prox = NavigationRobot(composeRule).openProximity()
        for (i in 1..5) {
            prox.selectTargetViaPicker("RapidPick_$i")
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Settled on final RapidPick_5", "RapidPick_5")
    }

    // =========================================================================
    // F28: 120Hz Radial Sonar Proximity Gauge & Sparkline Screen — Edge (6)
    // =========================================================================

    @Test
    fun f28_t2_01_extremeCloseContactMinus22DbmClampsGaugeAt100PercentAndSubHalfMeterPhrase() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RightBesideRouter", bssid = "04:D4:C4:28:11:01", rssiDbm = -22, frequencyMhz = 5180)),
        )
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "100% max quality", "100%")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "< 0.5 m clamped near", "0.5 m", "Next to Router")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F28-CloseClamp")
    }

    @Test
    fun f28_t2_02_extremeFaintMinus97DbmClampsFarAbove50MetersAndZeroPercent() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "FarEdgeRouter", bssid = "04:D4:C4:28:12:01", rssiDbm = -97, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.selectEnvironment("Open Space", "Open")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "> 50 m clamped far", "50 m", "Far Edge")
    }

    @Test
    fun f28_t2_03_singlePointSparklineRendersCleanlyWithoutZeroWidthPathDivisionCrash() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SinglePointSpark", bssid = "04:D4:C4:28:13:01", rssiDbm = -58)),
        )
        NavigationRobot(composeRule).openProximity()
        val (_, bmp) = robot.captureRootBitmap("f28_t2_03_single_point_sparkline.png")
        assertTrue(bmp.width > 0 && bmp.height > 0)
    }

    @Test
    fun f28_t2_04_flatConstantSignalZeroVarianceRendersSparklineWithoutZeroYRangeDivisionByZero() {
        val bssid = "04:D4:C4:28:14:01"
        robot.launchApp()
        for (step in 1..6) {
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "FlatLine60", bssid = bssid, rssiDbm = -60, explicitTimestampMicros = step * 1_000_000L)),
            )
            if (step == 1) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)
        }
        val (_, bmp) = robot.captureRootBitmap("f28_t2_04_flat_sparkline.png")
        assertTrue(bmp.width > 0)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F28-FlatSparkline")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun f28_t2_05_proximityScreenAt200PercentFontScaleKeepsControlsScrollableAndUncropped() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LargeFontSonar_5G", bssid = "04:D4:C4:28:15:01", rssiDbm = -54)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.scrollUntilVisible("Calibrate", "Haptic", "Reset")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F28-FontScale200")
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-450dpi")
    fun f28_t2_06_proximityScreenInLandscapeViewportRendersSonarGaugeAndControlsCleanly() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LandscapeSonar_5G", bssid = "04:D4:C4:28:16:01", rssiDbm = -51)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.scrollUntilVisible("Calibrate", "Home")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F28-Landscape")
    }

    // =========================================================================
    // F29: Graceful Target Dropout & Auto Re-Acquisition — Edge Cases (6)
    // =========================================================================

    @Test
    fun f29_t2_01_repeatedDropoutAndReacquisitionCyclesMaintainCleanSparklineGapsWithoutCrash() {
        val bssid = "04:D4:C4:29:11:01"
        val keepAlive = "B0:BE:76:29:11:02"
        robot.launchApp()

        repeat(3) { cycle ->
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "IntermittentDoorAP", bssid = bssid, rssiDbm = -55 - cycle * 3),
                    ScanResultFactory.createScanResult(ssid = "NeighborKeep", bssid = keepAlive, rssiDbm = -62),
                ),
            )
            if (cycle == 0) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)

            repeat(3) {
                robot.advanceClockMs(15_000L)
                robot.injectScanBatch(
                    listOf(ScanResultFactory.createScanResult(ssid = "NeighborKeep", bssid = keepAlive, rssiDbm = -62)),
                )
            }
        }
        // Final re-acquisition
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "IntermittentDoorAP", bssid = bssid, rssiDbm = -47)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Re-acquired after 3 dropout cycles", "-47 dBm", "Excellent", "Strong")
    }

    @Test
    fun f29_t2_02_followMeshModeDropsOnlyActiveNodeAndSeamlesslyFailsOverToSurvivingSiblingNode() {
        val unpluggedNode = "04:D4:C4:29:12:01"
        val survivingNode = "04:D4:C4:29:12:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "FailoverMesh", bssid = unpluggedNode, rssiDbm = -48, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "FailoverMesh", bssid = survivingNode, rssiDbm = -66, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.switchToFollowMeshMode()

        // Unplug primary node completely (> 50s); survivingNode remains online!
        repeat(4) {
            robot.advanceClockMs(15_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "FailoverMesh", bssid = survivingNode, rssiDbm = -64, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Follow Mesh fails over to surviving sibling node 29:12:02",
            "29:12:02",
            "-64 dBm",
        )
    }

    @Test
    fun f29_t2_03_followMeshModeEntersSignalLostCleanlyWhenEntireMeshPowerFails() {
        val node1 = "04:D4:C4:29:13:01"
        val node2 = "04:D4:C4:29:13:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "PowerOutageMesh", bssid = node1, rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "PowerOutageMesh", bssid = node2, rssiDbm = -64),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.switchToFollowMeshMode()

        repeat(4) {
            robot.advanceClockMs(15_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "CellHotspotNeighbor", bssid = "DA:A1:19:29:13:99", rssiDbm = -70)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Whole-mesh outage -> Signal Lost", "Signal Lost")
    }

    @Test
    fun f29_t2_04_calibrating1mDisabledOrIgnoredSafelyWhileTargetIsInSignalLostState() {
        val bssid = "04:D4:C4:29:14:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LostCalibrateGuard", bssid = bssid, rssiDbm = -55)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        repeat(4) {
            robot.advanceClockMs(15_000L)
            robot.injectScanBatch(emptyList())
        }
        runCatching { prox.clickCalibrateOneMeter() }
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F29-CalibrateWhileLost")
    }

    @Test
    fun f29_t2_05_turningOffSystemWifiWhileTrackingTargetTransitionsCleanlyWithoutStaleLiveLock() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WifiOffMidTrack", bssid = "04:D4:C4:29:15:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openProximity()
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = false, broadcastChange = true)

        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must not claim 'Live Signal Lock' while Wi-Fi & Scanning are disabled", dump.contains("Live Signal Lock") && !dump.contains("Wi-Fi"))
    }

    @Test
    fun f29_t2_06_activityRecreateDuringSignalLostPreservesLockedTargetIdentityForFutureReacquisition() {
        val bssid = "04:D4:C4:29:16:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RotateWhileLost_AP", bssid = bssid, rssiDbm = -68)),
        )
        NavigationRobot(composeRule).openProximity()
        repeat(4) {
            robot.advanceClockMs(15_000L)
            robot.injectScanBatch(emptyList())
        }

        // Rotate phone while router is still unplugged, then plug router back in!
        robot.recreateApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RotateWhileLost_AP", bssid = bssid, rssiDbm = -49)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Re-acquired after rotate during dropout", "RotateWhileLost_AP", "-49 dBm")
    }
}
