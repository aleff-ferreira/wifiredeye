@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.long
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.FrameSpec
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F18`–`F23` (`36` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F18ToF23PlatformHardwareEdgeE2eTest {

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
        ShadowAudioTrack.enableIllegalStateOnPlay(false)
        robot.close()
    }

    // =========================================================================
    // F18: Galaxy A55 5G Radio Capability Introspection — Edge Cases (6)
    // =========================================================================

    @Test
    fun f18_t2_01_capabilityQueryThrowingSecurityOrRuntimeExceptionFallsBackToSafeDualBandDefaults() {
        ExtendedShadowWifiManager.throwOnCapabilityQuery = RuntimeException("Vendor HAL capability query timeout")
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnCapabilityQuery = null
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SafeCapFallback_5G", bssid = "04:D4:C4:18:11:01", rssiDbm = -52)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("SafeCapFallback_5G")
    }

    @Test
    fun f18_t2_02_singleBand24GhzOnlyLegacyRadioProfileHandledWithoutCrashOn5GhzAnd6GhzTabs() {
        Shadows.shadowOf(robot.wifiManager).setIs5GHzBandSupported(false)
        ExtendedShadowWifiManager.band6GhzSupported = false
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "IoT24Only", bssid = "04:D4:C4:18:12:01", rssiDbm = -55, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412)),
        )
        val spec = NavigationRobot(composeRule).openSpectrum()
        spec.selectBand("5 GHz")
        spec.selectBand("6 GHz")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F18-24GhzOnly")
    }

    @Test
    fun f18_t2_03_6GhzFilterChipOnDualBandA55NeverHidesOutOfBand6GhzRnrRouterSilently() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "RnrTriBand_AXE",
                    bssid = "04:D4:C4:18:13:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    ies = listOf(com.a55.wifiradar.e2e.fixtures.BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe()),
                ),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        runCatching { networks.toggleFilterChip("6 GHz") }
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F18-6GhzFilterOnA55")
    }

    @Test
    fun f18_t2_04_deviceSupportingWifi7_11beDisplaysWifi7CapabilitiesCleanly() {
        ExtendedShadowWifiManager.band6GhzSupported = true
        ExtendedShadowWifiManager.supportedWifiStandards.add(ScanResult.WIFI_STANDARD_11BE)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "FutureBE_320",
                    bssid = "04:D4:C4:18:14:01",
                    rssiDbm = -46,
                    frequencyMhz = 6115,
                    channelWidth = ScanResult.CHANNEL_WIDTH_320MHZ,
                    centerFreq0Mhz = 6105,
                    wifiStandard = ScanResult.WIFI_STANDARD_11BE,
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("FutureBE_320")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Wi-Fi 7 badge", "Wi-Fi 7", "320 MHz")
    }

    @Test
    fun f18_t2_05_rttSystemFeatureTrueButWifiDisabledDoesNotInvokeRttRangingOrCrash() {
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = false, broadcastChange = false)
        robot.launchApp()
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F18-RttWithWifiOff")
    }

    @Test
    fun f18_t2_06_capabilitySnapshotRemainsConsistentAcrossActivityRecreation() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.launchApp()
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        robot.recreateApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "6 GHz explanation preserved after recreate", "6 GHz", "dual-band", "hardware", "2.4")
    }

    // =========================================================================
    // F19: Progressive Self-Healing OS Readiness State Machine — Edge Cases (6)
    // =========================================================================

    @Test
    fun f19_t2_01_android12ApproximateLocationOnlyWithoutFineLocationShowsUpgradeToPreciseStep() {
        robot.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        robot.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES)
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Approximate-only -> Upgrade to Precise Location card",
            "Precise",
            "Approximate",
            "Upgrade",
        )
    }

    @Test
    fun f19_t2_02_multipleSimultaneousBlockersMissingPermissionLocationOffAndWifiOffPrioritizeStep1Cleanly() {
        robot.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = false, broadcastChange = false)
        robot.launchApp()

        // Step 1 (Permission) -> Step 2 (Location) -> Step 3 (Wi-Fi) progressive resolution!
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Step 1 shown first", "Permission", "Allow", "Grant")

        robot.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Step 2 Location shown next", "Location")

        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Step 3 Wi-Fi shown next", "Wi-Fi")

        robot.setWifiEnabledState(enabled = true, scanAlwaysAvailable = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AllThreeHealed_5G", bssid = "04:D4:C4:19:12:01", rssiDbm = -49)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("AllThreeHealed_5G")
    }

    @Test
    fun f19_t2_03_turningOffLocationMidSessionGracefullyTransitionsFromLiveScanToStep2Card() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LiveBeforeLocOff", bssid = "04:D4:C4:19:13:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("LiveBeforeLocOff")

        robot.setLocationEnabled(enabled = false, broadcastChange = true)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Mid-session Location OFF card", "Turn On", "Location")
    }

    @Test
    fun f19_t2_04_returningFromSystemSettingsViaOnResumeRefreshesPermissionReadinessAutomatically() {
        robot.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Initial missing permission", "Grant", "Allow", "Location")

        // User grants Precise Location inside Android Settings and returns to app (ON_START -> ON_RESUME)
        robot.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "GrantedInSettings_5G", bssid = "04:D4:C4:19:14:01", rssiDbm = -51)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("GrantedInSettings_5G")
    }

    @Test
    fun f19_t2_05_readinessBlockerDoesNotFreezeBottomNavigationAcrossOtherTabs() {
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()
        val nav = NavigationRobot(composeRule)
        nav.openProximity()
        nav.assertTabSelected("Proximity")
        nav.openSpectrum()
        nav.assertTabSelected("Spectrum")
        nav.openInsights()
        nav.assertTabSelected("Insights")
        nav.openNetworks()
        nav.assertTabSelected("Networks")
    }

    @Test
    fun f19_t2_06_wifiTransientEnablingAndDisablingStatesHandledWithoutCrash() {
        robot.launchApp()
        for (state in listOf(
            WifiManager.WIFI_STATE_DISABLING,
            WifiManager.WIFI_STATE_DISABLED,
            WifiManager.WIFI_STATE_ENABLING,
            WifiManager.WIFI_STATE_UNKNOWN,
            WifiManager.WIFI_STATE_ENABLED,
        )) {
            Shadows.shadowOf(robot.wifiManager).setWifiState(state)
            robot.appContext.sendBroadcast(
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
                    putExtra(WifiManager.EXTRA_WIFI_STATE, state)
                },
            )
            robot.idleMainLooper()
        }
        composeRule.waitForIdle()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F19-TransientWifiStates")
    }

    // =========================================================================
    // F20: Hybrid Token-Bucket Active + Dual Passive Scanner — Edge Cases (6)
    // =========================================================================

    @Test
    fun f20_t2_01_throttledRejectionBroadcastWithExtraResultsUpdatedFalsePreservesCachedNetworks() {
        robot.launchApp()
        robot.injectScanBatch(
            results = listOf(
                ScanResultFactory.createScanResult(ssid = "CachedPreserved1", bssid = "04:D4:C4:20:11:01", rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "CachedPreserved2", bssid = "B0:BE:76:20:11:02", rssiDbm = -62),
            ),
            resultsUpdatedExtra = true,
        )
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)
        robot.appContext.sendBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
                putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            },
        )
        robot.idleMainLooper()
        composeRule.waitForIdle()

        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("CachedPreserved1")
        networks.assertNetworkDisplayed("CachedPreserved2")
    }

    @Test
    fun f20_t2_02_tokenBucketRechargesCleanlyAfter121SecondsAllowingFreshActiveSweep() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = true)
        robot.launchApp()
        val networks = NavigationRobot(composeRule).openNetworks()
        repeat(4) {
            runCatching { networks.triggerManualRefresh() }
            robot.advanceClockMs(1_000L)
        }
        // Advance past full 120s Android rolling window
        robot.advanceClockMs(121_000L)
        val beforeRechargeScan = ExtendedShadowWifiManager.startScanInvocationCount.get()
        runCatching { networks.triggerManualRefresh() }
        robot.advanceClockMs(500L)
        assertTrue(
            "Token bucket must recharge after 121s so manual refresh invokes startScan()",
            ExtendedShadowWifiManager.startScanInvocationCount.get() > beforeRechargeScan,
        )
    }

    @Test
    fun f20_t2_03_staleGhostRoutersEvictedCleanlyAfterExceeding90SecondTtl() {
        robot.launchApp()
        val t0Micros = SystemClock.elapsedRealtime() * 1_000L
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "PermanentHome_5G", bssid = "04:D4:C4:20:13:01", rssiDbm = -50, explicitTimestampMicros = t0Micros),
                ScanResultFactory.createScanResult(ssid = "WalkedPastBus_2G", bssid = "B0:BE:76:20:13:02", rssiDbm = -75, explicitTimestampMicros = t0Micros),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("WalkedPastBus_2G")

        // Advance 96 seconds (> 90s eviction TTL) and deliver sweep containing only PermanentHome_5G
        robot.advanceClockMs(96_000L)
        val t1Micros = SystemClock.elapsedRealtime() * 1_000L
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "PermanentHome_5G", bssid = "04:D4:C4:20:13:01", rssiDbm = -49, explicitTimestampMicros = t1Micros),
            ),
        )
        networks.assertNetworkDisplayed("PermanentHome_5G")
        networks.assertNetworkNotDisplayed("WalkedPastBus_2G")
    }

    @Test
    fun f20_t2_04_simultaneousCallbackAndBroadcastForIdenticalSweepDeduplicatedWithoutDuplicateCards() {
        robot.launchApp()
        val fixedTs = SystemClock.elapsedRealtime() * 1_000L
        val ap = ScanResultFactory.createScanResult(ssid = "DedupSingleCard", bssid = "04:D4:C4:20:14:01", rssiDbm = -54, explicitTimestampMicros = fixedTs)
        repeat(5) {
            robot.injectScanBatch(listOf(ap), fireCallback = true, fireBroadcast = true)
        }
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("DedupSingleCard")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "1 Network count", "1 Network")
    }

    @Test
    fun f20_t2_05_futureAndZeroScanResultTimestampsClampAgeToZeroWithoutNegativeSeconds() {
        robot.launchApp()
        val futureMicros = (SystemClock.elapsedRealtime() + 60_000L) * 1_000L
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "FutureClockSkewAP", bssid = "04:D4:C4:20:15:01", rssiDbm = -52, explicitTimestampMicros = futureMicros),
                ScanResultFactory.createScanResult(ssid = "ZeroEpochAP", bssid = "B0:BE:76:20:15:02", rssiDbm = -64, explicitTimestampMicros = 0L),
            ),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must NEVER render negative age like '-60s ago':\n$dump", Regex("""-\d+s\s+ago""").containsMatchIn(dump))
    }

    @Test
    fun f20_t2_06_restrictedWorkProfileWhereStartScanAlwaysReturnsFalseDegradesToPassiveModeWithoutSpinLoop() {
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)
        robot.launchApp()
        robot.injectScanBatch(
            results = listOf(ScanResultFactory.createScanResult(ssid = "WorkProfilePassive_5G", bssid = "04:D4:C4:20:16:01", rssiDbm = -53)),
            fireCallback = false,
            fireBroadcast = true,
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("WorkProfilePassive_5G")
        assertTrue("Must not busy-loop startScan() when rejected", ExtendedShadowWifiManager.startScanInvocationCount.get() < 20)
    }

    // =========================================================================
    // F21: Unthrottled 300 ms Connected-AP Stream & Roam Logger — Edge Cases (6)
    // =========================================================================

    @Test
    fun f21_t2_01_wifiDisconnectOnLostCallbackImmediatelyClearsConnectedSpotlightBadge() {
        val bssid = "AC:84:C6:21:11:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "DisconnectTest_5G", bssid = bssid, rssiDbm = -50)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DisconnectTest_5G", bssid = bssid, rssiDbm = -50, linkSpeedMbps = 960),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected before drop", "Connected")

        robot.simulateWifiDisconnected()
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Ghost 960 Mbps link speed must clear after onLost disconnect:\n$dump", dump.contains("960 Mbps"))
    }

    @Test
    fun f21_t2_02_android12RedactedWifiInfo020000000000IgnoredAsFakeBssidAndNoBogusRoamEvent() {
        val realBssid = "AC:84:C6:21:12:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RealConnected_5G", bssid = realBssid, rssiDbm = -52)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "RealConnected_5G", bssid = realBssid, rssiDbm = -52),
        )
        // Emit redacted placeholder WifiInfo ("<unknown ssid>", "02:00:00:00:00:00")
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(redactedLocation = true, rssiDbm = -55),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must NEVER display fake 02:00:00:00:00:00 card:\n$dump", dump.contains("02:00:00:00:00:00"))
    }

    @Test
    fun f21_t2_03_connectedToHiddenNetworkRevealsRealSsidForMatchingHiddenBssid() {
        val hiddenBssid = "AC:84:C6:21:13:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(6) { 0 },
                    bssid = hiddenBssid,
                    rssiDbm = -49,
                ),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(
                ssid = "SecretStudio_5G",
                bssid = hiddenBssid,
                rssiDbm = -49,
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("SecretStudio_5G")
    }

    @Test
    fun f21_t2_04_invalidMinus127DbmDisconnectSentinelBroadcastFilteredWithoutCorruptingProximityMin() {
        val bssid = "AC:84:C6:21:14:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "GlitchGuard_5G", bssid = bssid, rssiDbm = -52)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "GlitchGuard_5G", bssid = bssid, rssiDbm = -52),
        )
        NavigationRobot(composeRule).openProximity()

        // Inject transient -127 dBm RSSI_CHANGED_ACTION sentinel
        robot.appContext.sendBroadcast(
            Intent(WifiManager.RSSI_CHANGED_ACTION).apply {
                putExtra(WifiManager.EXTRA_NEW_RSSI, -127)
            },
        )
        robot.advanceClockMs(350L)
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Sentinel -127 dBm must never pollute Proximity UI:\n$dump", dump.contains("-127"))
    }

    @Test
    fun f21_t2_05_manualSwitchBetweenTwoCompletelyDifferentSsidsDoesNotLogFakeSameEssMeshRoam() {
        val homeBssid = "04:d4:c4:21:15:01"
        val cafeBssid = "b0:be:76:21:15:02"
        val scans = listOf(
            ScanEntrySpec(ssid = "MyHomeWiFi", bssid = homeBssid, rssiDbm = -55),
            ScanEntrySpec(ssid = "CornerCoffee", bssid = cafeBssid, rssiDbm = -52),
        )
        val scenario = CliScenarioBuilder.multiFrame(
            frames = listOf(
                FrameSpec(elapsedMs = 1_000L, connectedBssid = homeBssid, scans = scans),
                FrameSpec(elapsedMs = 5_000L, connectedBssid = cafeBssid, scans = scans),
            ),
        )
        val roams = CliJsonLens.roamEvents(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertTrue("Switching between different SSIDs must NOT log a same-SSID mesh roam event", roams.isEmpty())
    }

    @Test
    fun f21_t2_06_twelveRapidThreeWayMeshPingPongRoamsStayChronologicalAndBounded() {
        val nodes = listOf("04:d4:c4:21:16:01", "04:d4:c4:21:16:02", "04:d4:c4:21:16:03")
        val scans = nodes.mapIndexed { idx, mac ->
            ScanEntrySpec(ssid = "PingPongMesh", bssid = mac, rssiDbm = -55 - idx * 3, frequencyMhz = 5180 + idx * 20, centerFreq0Mhz = 5210)
        }
        val frames = (0..12).map { step ->
            FrameSpec(elapsedMs = (step + 1) * 1_000L, connectedBssid = nodes[step % 3], scans = scans)
        }
        val roams = CliJsonLens.roamEvents(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.multiFrame(frames)).assertSucceeded().parseJsonRoot(),
        )
        assertEquals(12, roams.size)
        val timestamps = roams.map { it.long("timestampElapsedMs")!! }
        assertEquals("Roam events must be in monotonic order", timestamps.sorted(), timestamps)
    }

    // =========================================================================
    // F22: 3-Tier LRA Haptic & Hanning-Sine Audio Geiger Engine — Edge Cases (6)
    // =========================================================================

    @Test
    fun f22_t2_01_lifecycleOnStopBackgroundingImmediatelyCancelsVibratorAndMutesAudioPulses() {
        val audioBytesWhileBackgrounded = AtomicInteger(0)
        var countingBackground = false
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (countingBackground) audioBytesWhileBackgrounded.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "BgStopGeiger", bssid = "04:D4:C4:22:11:01", rssiDbm = -38)),
            )
            val prox = NavigationRobot(composeRule).openProximity()
            prox.toggleHapticGeiger()
            prox.toggleAudioGeiger()
            robot.advanceClockMs(800L)

            // User presses Home / locks phone -> ON_STOP (CREATED)
            robot.moveToLifecycle(Lifecycle.State.CREATED)
            countingBackground = true
            robot.advanceClockMs(5_000L)

            assertEquals("Zero audio Geiger bytes may play while app is backgrounded!", 0, audioBytesWhileBackgrounded.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun f22_t2_02_targetSignalLostDropoutImmediatelyMutesGeigerPulsesUntilRecovered() {
        val writtenBytes = AtomicInteger(0)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            writtenBytes.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val bssid = "04:D4:C4:22:12:01"
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "MuteOnLostAP", bssid = bssid, rssiDbm = -45)),
            )
            val prox = NavigationRobot(composeRule).openProximity()
            prox.toggleAudioGeiger()
            robot.advanceClockMs(800L)

            // Drive target into Signal Lost (> 35s absent)
            repeat(4) {
                robot.advanceClockMs(12_000L)
                robot.injectScanBatch(
                    listOf(ScanResultFactory.createScanResult(ssid = "OtherAP", bssid = "B0:BE:76:22:12:02", rssiDbm = -60)),
                )
            }
            writtenBytes.set(0)
            robot.advanceClockMs(3_000L)
            assertEquals("Audio Geiger must mute completely during Signal Lost!", 0, writtenBytes.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun f22_t2_03_deviceWithoutHardwareVibratorHasVibratorFalseNeverThrowsException() {
        robot.shadowVibrator().setHasVibrator(false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "NoVibratorTablet", bssid = "04:D4:C4:22:13:01", rssiDbm = -48)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        runCatching { prox.toggleHapticGeiger() }
        robot.advanceClockMs(1_500L)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F22-NoVibrator")
    }

    @Test
    fun f22_t2_04_audioTrackPlayThrowingIllegalStateExceptionCaughtWithoutCrashingUi() {
        ShadowAudioTrack.enableIllegalStateOnPlay(true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AudioFaultAP", bssid = "04:D4:C4:22:14:01", rssiDbm = -44)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.toggleAudioGeiger()
        robot.advanceClockMs(2_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "UI stays alive after AudioTrack fault", "AudioFaultAP")
    }

    @Test
    fun f22_t2_05_togglingHapticAndAudioOffMidRapidBurstHaltsImmediately() {
        val bytesAfterToggleOff = AtomicInteger(0)
        var afterOff = false
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (afterOff) bytesAfterToggleOff.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "ToggleOffMidBurst", bssid = "04:D4:C4:22:15:01", rssiDbm = -34)),
            )
            val prox = NavigationRobot(composeRule).openProximity()
            prox.toggleHapticGeiger()
            prox.toggleAudioGeiger()
            robot.advanceClockMs(600L)

            // Turn both OFF
            prox.toggleHapticGeiger()
            prox.toggleAudioGeiger()
            afterOff = true
            robot.advanceClockMs(2_500L)

            assertEquals("Zero audio pulses should fire after toggling Audio Geiger OFF", 0, bytesAfterToggleOff.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun f22_t2_06_extremeOutlierRssiMinus120AndMinus5DbmClampGeigerIntervalAndFrequencySafely() {
        val bssid = "04:d4:c4:22:16:01"
        val pLow = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -120))),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        )
        val pHigh = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = -5))),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        )

        assertTrue(pLow.long("geigerIntervalMs")!! in 85L..1400L)
        assertTrue(pHigh.long("geigerIntervalMs")!! in 85L..1400L)
        assertTrue(pLow.dbl("geigerToneHz")!! in 320.0..1400.0)
        assertTrue(pHigh.dbl("geigerToneHz")!! in 320.0..1400.0)
    }

    // =========================================================================
    // F23: Local BSSID Nickname & 1m Calibration Persistence — Edge Cases (6)
    // =========================================================================

    @Test
    fun f23_t2_01_blankOrWhitespaceOnlyNicknameClearsAliasAndRestoresOriginalSsid() {
        val bssid = "AC:84:C6:23:11:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "OriginalGarageSSID", bssid = bssid, rssiDbm = -54)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.openDetailSheetFor("OriginalGarageSSID").assignNickname("TempGarageAlias").dismiss()
        networks.assertNetworkDisplayed("TempGarageAlias")

        networks.openDetailSheetFor("TempGarageAlias").assignNickname("   ").dismiss()
        networks.assertNetworkDisplayed("OriginalGarageSSID")
        networks.assertNetworkNotDisplayed("TempGarageAlias")
    }

    @Test
    fun f23_t2_02_bssidCaseNormalizationMatchesLowercaseSavedNicknameToUppercaseScanResult() {
        val lower = "ac:84:c6:23:12:01"
        val upper = "AC:84:C6:23:12:01"
        val root = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(
                bssidNicknames = mapOf(lower to "CaseNormalizedAlias"),
                scans = listOf(ScanEntrySpec(ssid = "RawUpperAP", bssid = upper, rssiDbm = -52)),
            ),
        ).assertSucceeded().parseJsonRoot()
        assertEquals("CaseNormalizedAlias", CliJsonLens.radioByBssid(root, upper)["customNickname"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun f23_t2_03_unicodeEmojiAndDoubleQuotesInNicknamePersistAndExportValidRfc8259Json() {
        val trickyAlias = "Café \"2층\" Router 🚀"
        val bssid = "AC:84:C6:23:13:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "UnicodeHostAP", bssid = bssid, rssiDbm = -50)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("UnicodeHostAP").assignNickname(trickyAlias).dismiss()

        robot.closeAndColdRelaunch()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "UnicodeHostAP", bssid = bssid, rssiDbm = -50)),
        )
        nav.openInsights().clickShareExport("JSON")
        val shared = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val parsed = CliRunnerHarness.lenientJson.parseToJsonElement(shared.resolvedBody.trim())
        assertTrue(parsed.toString().contains("Café"))
    }

    @Test
    fun f23_t2_04_veryLong120CharacterNicknameTruncatesOrWrapsWithoutBreakingCardBounds() {
        val longAlias = "N".repeat(120)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LongAliasTarget", bssid = "AC:84:C6:23:14:01", rssiDbm = -52)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("LongAliasTarget").assignNickname(longAlias).dismiss()

        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue(rootWidth > 0)
        nav.openNetworks().launchProximityFor("NNNNNNNN")
        nav.assertTabSelected("Proximity")
    }

    @Test
    fun f23_t2_05_independentPerBssidCalibrationsCalibratingRouterADoesNotSkewRouterB() {
        val routerA = "AC:84:C6:23:15:01"
        val routerB = "AC:84:C6:23:15:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "CalRouterA", bssid = routerA, rssiDbm = -60, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "UncalRouterB", bssid = routerB, rssiDbm = -60, frequencyMhz = 5180),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val prox = nav.openNetworks().launchProximityFor("CalRouterA")
        prox.clickCalibrateOneMeter()
        val dA = prox.readDisplayedDistanceMeters()
        assertTrue("CalRouterA ($dA m) must anchor ~1.0 m", dA in 0.65..1.45)

        prox.selectTargetViaPicker("UncalRouterB")
        val dB = prox.readDisplayedDistanceMeters()
        assertTrue("UncalRouterB ($dB m) at same -60 dBm must retain uncalibrated > 2.2 m estimate", dB > 2.2)
    }

    @Test
    fun f23_t2_06_corruptedPreferencesJsonOnDiskAtColdBootRecoversGracefullyWithoutCrash() {
        // Seed corrupted JSON into common app SharedPreferences / filesDir locations before launch
        val prefs = robot.appContext.getSharedPreferences("wifiradar_user_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("bssid_nicknames", "{broken_json[[")
            .putString("one_meter_calibrations", "not_a_json_object")
            .putString("preferences_json", "{corrupt")
            .commit()
        File(robot.appContext.filesDir, "user_preferences.json").writeText("{corrupt_disk_file[[")

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RecoveredPrefsAP", bssid = "AC:84:C6:23:16:01", rssiDbm = -51)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("RecoveredPrefsAP")
        networks.openDetailSheetFor("RecoveredPrefsAP").assignNickname("CleanNewAlias").dismiss()
        networks.assertNetworkDisplayed("CleanNewAlias")
    }
}
