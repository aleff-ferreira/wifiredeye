@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F27`–`F29` (`18` `@Test` methods: `6` per feature).
 * - `F27` 1-Tap Proximity Launch, Target Picker & BSSID/SSID Lock (`6` tests)
 * - `F28` 120Hz Radial Sonar Proximity Gauge & Sparkline Screen (`6` tests)
 * - `F29` Graceful Target Dropout & Auto Re-Acquisition (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F27ToF29ProximityHomingUiE2eTest {

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
    // F27: 1-Tap Proximity Launch, Target Picker & BSSID/SSID Lock (6 Tests)
    // =========================================================================

    @Test
    fun f27_t1_01_oneTapProximityLaunchFromAnyUnassociatedNetworkCardLocksTargetImmediately() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "LobbyGuest_2G", bssid = "B0:BE:76:27:01:01", rssiDbm = -45, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "HallwayRepeater_5G", bssid = "04:D4:C4:27:01:02", rssiDbm = -68, frequencyMhz = 5180),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().launchProximityFor("HallwayRepeater_5G")
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Locked onto HallwayRepeater_5G", "HallwayRepeater_5G", "27:01:02")
    }

    @Test
    fun f27_t1_02_directProximityTabOpenWithoutConnectedWifiAutoSelectsStrongestDetectedRouter() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "FaintAttic_2G", bssid = "B0:BE:76:27:02:01", rssiDbm = -80, frequencyMhz = 2462, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2462),
                ScanResultFactory.createScanResult(ssid = "StrongLivingRoom_5G", bssid = "04:D4:C4:27:02:02", rssiDbm = -46, frequencyMhz = 5180),
            ),
        )
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Auto-selected strongest AP", "StrongLivingRoom_5G")
    }

    @Test
    fun f27_t1_03_inScreenTargetPickerSwitchesTrackedRouterWithoutLeavingProximityTab() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "PickerRouterOne", bssid = "04:D4:C4:27:03:01", rssiDbm = -49),
                ScanResultFactory.createScanResult(ssid = "PickerRouterTwo", bssid = "FC:EC:DA:27:03:02", rssiDbm = -67),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val prox = nav.openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Initial target", "PickerRouterOne")

        prox.selectTargetViaPicker("PickerRouterTwo")
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Switched target", "PickerRouterTwo")
    }

    @Test
    fun f27_t1_04_singleRouterBssidLockRemainsPinnedToExactHardwareNodeWhenSiblingMeshNodeGrowsStronger() {
        val pinnedNode = "04:D4:C4:27:04:01"
        val siblingNode = "04:D4:C4:27:04:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MeshHome", bssid = pinnedNode, rssiDbm = -52, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "MeshHome", bssid = siblingNode, rssiDbm = -75, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.switchToAllRadiosMode()
        val prox = networks.launchProximityFor(pinnedNode)
        runCatching { prox.switchToSingleRouterBssidMode() }

        // Walk toward siblingNode (-43 dBm) while pinnedNode weakens to -76 dBm
        robot.advanceClockMs(1_500L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MeshHome", bssid = pinnedNode, rssiDbm = -76, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "MeshHome", bssid = siblingNode, rssiDbm = -43, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Pinned BSSID stays on 27:04:01 despite stronger sibling",
            "27:04:01",
        )
    }

    @Test
    fun f27_t1_05_followMeshSsidModeAutomaticallyTracksStrongestMeshBssidDuringWalk() {
        val floor1 = "04:D4:C4:27:05:01"
        val floor2 = "04:D4:C4:27:05:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SeamlessMesh", bssid = floor1, rssiDbm = -49, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "SeamlessMesh", bssid = floor2, rssiDbm = -78, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.switchToFollowMeshMode()

        robot.advanceClockMs(1_500L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SeamlessMesh", bssid = floor1, rssiDbm = -81, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "SeamlessMesh", bssid = floor2, rssiDbm = -45, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Follow Mesh SSID tracks stronger floor2 BSSID",
            "27:05:02",
            "Excellent",
        )
    }

    @Test
    fun f27_t1_06_lockedProximityTargetPersistsAcrossBottomTabNavigationRoundTrips() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DefaultStrongAP", bssid = "B0:BE:76:27:06:01", rssiDbm = -44),
                ScanResultFactory.createScanResult(ssid = "PinnedGarageAP", bssid = "04:D4:C4:27:06:02", rssiDbm = -70),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().launchProximityFor("PinnedGarageAP")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Initial lock", "PinnedGarageAP")

        nav.openSpectrum()
        nav.openInsights()
        nav.openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Preserved lock after tab round-trip", "PinnedGarageAP")
    }

    // =========================================================================
    // F28: 120Hz Radial Sonar Proximity Gauge & Sparkline Screen (6 Tests)
    // =========================================================================

    @Test
    fun f28_t1_01_radialSonarGaugeExposesAccessibleSemanticsAndDisplaysSmoothedRawAndQualityPct() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SonarHero_5G", bssid = "04:D4:C4:28:01:01", rssiDbm = -52, frequencyMhz = 5180)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.assertAccessibleCanvasPresent()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Smoothed dBm", "-52 dBm")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Quality %", "96%", "%")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F28-SonarGauge")
    }

    @Test
    fun f28_t1_02_walkingCloserTransitionsVelocityIndicatorFromSteadyToApproachingWithPositiveSlope() {
        val bssid = "04:D4:C4:28:02:01"
        robot.launchApp()
        val walkSequence = listOf(-78, -73, -67, -61, -54, -48)
        for ((idx, rssi) in walkSequence.withIndex()) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(
                        ssid = "ApproachWalk_5G",
                        bssid = bssid,
                        rssiDbm = rssi,
                        explicitTimestampMicros = (idx + 1) * 1_000_000L,
                    ),
                ),
            )
            if (idx == 0) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Approaching trend indicator", "Approaching")
    }

    @Test
    fun f28_t1_03_walkingAwayTransitionsVelocityIndicatorToMovingAwayWithNegativeSlope() {
        val bssid = "04:D4:C4:28:03:01"
        robot.launchApp()
        val walkAwaySequence = listOf(-45, -51, -57, -64, -71, -78)
        for ((idx, rssi) in walkAwaySequence.withIndex()) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(
                        ssid = "RetreatWalk_5G",
                        bssid = bssid,
                        rssiDbm = rssi,
                        explicitTimestampMicros = (idx + 1) * 1_000_000L,
                    ),
                ),
            )
            if (idx == 0) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Moving Away trend indicator", "Moving Away")
    }

    @Test
    fun f28_t1_04_propagationEnvironmentSelectorUpdatesDisplayedDistanceMonotonically() {
        val bssid = "04:D4:C4:28:04:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WallModelAP", bssid = bssid, rssiDbm = -72, frequencyMhz = 5180)),
        )
        val prox = NavigationRobot(composeRule).openProximity()

        prox.selectEnvironment("Open Space", "Open")
        val dOpen = prox.readDisplayedDistanceMeters()

        prox.selectEnvironment("Home / Office", "Home")
        val dHome = prox.readDisplayedDistanceMeters()

        prox.selectEnvironment("Heavy Concrete", "Heavy")
        val dHeavy = prox.readDisplayedDistanceMeters()

        assertTrue(
            "Expected Open Space ($dOpen m) > Home/Office ($dHome m) > Heavy Concrete ($dHeavy m)",
            dOpen > dHome && dHome > dHeavy,
        )
    }

    @Test
    fun f28_t1_05_sparklineDisplaysRollingMinMaxAvgAndStabilitySummaryCard() {
        val bssid = "04:D4:C4:28:05:01"
        robot.launchApp()
        for ((idx, rssi) in listOf(-66, -60, -54, -58, -50).withIndex()) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(
                        ssid = "SparkStatsAP",
                        bssid = bssid,
                        rssiDbm = rssi,
                        explicitTimestampMicros = (idx + 1) * 1_000_000L,
                    ),
                ),
            )
            if (idx == 0) NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(1_000L)
        }
        val prox = NavigationRobot(composeRule).openProximity()
        prox.scrollUntilVisible("Min", "Max", "Avg", "Stability")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Min RSSI stat", "-66")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Max RSSI stat", "-50")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Stability qualifier", "Rock Solid", "Steady", "Fluctuating")
    }

    @Test
    fun f28_t1_06_resetStatsActionClearsHistoricalMinMaxWithoutDroppingTargetLock() {
        val bssid = "04:D4:C4:28:06:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ResetStatsAP", bssid = bssid, rssiDbm = -82, explicitTimestampMicros = 1_000_000L)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ResetStatsAP", bssid = bssid, rssiDbm = -50, explicitTimestampMicros = 2_000_000L)),
        )

        prox.clickResetStats()
        robot.advanceClockMs(1_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ResetStatsAP", bssid = bssid, rssiDbm = -50, explicitTimestampMicros = 3_000_000L)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Target remains locked after Reset Stats", "ResetStatsAP")
    }

    // =========================================================================
    // F29: Graceful Target Dropout & Auto Re-Acquisition (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f29_t1_01_singleMissedScanSweepRetainsSmoothedLockWithAgingNoteInsteadOfSignalLostPanic() {
        val bssid = "04:D4:C4:29:01:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "TargetHoldAP", bssid = bssid, rssiDbm = -56),
                ScanResultFactory.createScanResult(ssid = "BackgroundAP", bssid = "B0:BE:76:29:01:02", rssiDbm = -65),
            ),
        )
        NavigationRobot(composeRule).openProximity()

        // 1 missed sweep 3 seconds later
        robot.advanceClockMs(3_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BackgroundAP", bssid = "B0:BE:76:29:01:02", rssiDbm = -64)),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue("TargetHoldAP must remain displayed on 1 missed sweep", dump.contains("TargetHoldAP"))
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F29-SingleMiss")
    }

    @Test
    fun f29_t1_02_sustainedDropoutEntersSignalLostBannerPreservingLastKnownDbmAndElapsedAge() {
        val bssid = "04:D4:C4:29:02:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "UnpluggedRouter", bssid = bssid, rssiDbm = -84),
                ScanResultFactory.createScanResult(ssid = "OtherLiveAP", bssid = "B0:BE:76:29:02:02", rssiDbm = -55),
            ),
        )
        NavigationRobot(composeRule).openProximity()

        // 4 consecutive sweeps over > 35 seconds without UnpluggedRouter
        repeat(4) {
            robot.advanceClockMs(12_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "OtherLiveAP", bssid = "B0:BE:76:29:02:02", rssiDbm = -55)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Signal Lost dropout banner",
            "Signal Lost",
            "Last seen",
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Preserves last known -84 dBm context",
            "-84 dBm",
            "UnpluggedRouter",
        )
    }

    @Test
    fun f29_t1_03_autoReAcquisitionImmediatelyRestoresLiveGaugeLockWhenDroppedTargetReturns() {
        val bssid = "04:D4:C4:29:03:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RebootingRouter", bssid = bssid, rssiDbm = -70)),
        )
        NavigationRobot(composeRule).openProximity()

        repeat(4) {
            robot.advanceClockMs(12_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "NeighborKeepAlive", bssid = "B0:BE:76:29:03:02", rssiDbm = -60)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Signal Lost during router reboot", "Signal Lost")

        // Router finishes rebooting and reappears at -51 dBm -> zero-tap re-acquisition!
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RebootingRouter", bssid = bssid, rssiDbm = -51)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Auto re-acquired live signal",
            "-51 dBm",
            "Strong",
            "Excellent",
            "Live",
        )
    }

    @Test
    fun f29_t1_04_dropoutNeverFabricatesSyntheticMinus100DbmSamplesIntoMinOrAverageStats() {
        val bssid = "04:D4:C4:29:04:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "NoFakeMinus100", bssid = bssid, rssiDbm = -55)),
        )
        NavigationRobot(composeRule).openProximity()

        repeat(4) {
            robot.advanceClockMs(12_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "Neighbor", bssid = "B0:BE:76:29:04:02", rssiDbm = -62)),
            )
        }
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue(
            "Dropout must NEVER inject fake -100 dBm or -127 dBm into Min/Avg stats:\n$dump",
            !dump.contains("-100 dBm") && !dump.contains("-127 dBm"),
        )
    }

    @Test
    fun f29_t1_05_userCanSwitchToAnotherLiveTargetDirectlyFromSignalLostState() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DroppedAP", bssid = "04:D4:C4:29:05:01", rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "BackupLiveAP", bssid = "FC:EC:DA:29:05:02", rssiDbm = -61),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        repeat(4) {
            robot.advanceClockMs(12_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "BackupLiveAP", bssid = "FC:EC:DA:29:05:02", rssiDbm = -61)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Dropped state", "Signal Lost")

        prox.selectTargetViaPicker("BackupLiveAP")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Switched to BackupLiveAP", "BackupLiveAP", "-61 dBm")
    }

    @Test
    fun f29_t1_06_openingProximityBeforeFirstScanCompletesShowsCalmSearchingStateWithoutCrash() {
        robot.launchApp()
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Calm Searching / Waiting state when 0 APs scanned yet",
            "Searching",
            "Scanning",
            "Waiting",
            "No wireless",
            "No target",
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F29-PreScan")
    }
}
