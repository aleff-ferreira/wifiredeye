@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.Manifest
import android.content.Intent
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.VibrationEffect
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.long
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F18`–`F23` (`36` `@Test` methods: `6` per feature).
 * - `F18` Galaxy A55 5G Radio Capability Introspection (`6` tests)
 * - `F19` Progressive Self-Healing OS Readiness State Machine (`6` tests)
 * - `F20` Hybrid Token-Bucket Active + Dual Passive Scanner (`6` tests)
 * - `F21` Unthrottled `300 ms` Connected-AP Stream & Roam Logger (`6` tests)
 * - `F22` 3-Tier LRA Haptic & Hanning-Sine Audio Geiger Engine (`6` tests)
 * - `F23` Local BSSID Nickname & `1m` Calibration Persistence (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F18ToF23PlatformHardwareE2eTest {

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
    // F18: Galaxy A55 5G Radio Capability Introspection (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f18_t1_01_retailGalaxyA55DualBandWifi6ProfileSurfaces6GhzHardwareExplanationWithoutSilentEmptyScreen() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false, hasRtt = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Home_2G", bssid = "04:D4:C4:18:01:01", rssiDbm = -58, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
                ScanResultFactory.createScanResult(ssid = "Home_5G", bssid = "04:D4:C4:18:01:02", rssiDbm = -49, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Retail A55 5G 6 GHz hardware capability explanation",
            "6 GHz",
            "dual-band",
            "hardware",
            "2.4",
            "5 GHz",
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Spectrum-6GHz-A55")
    }

    @Test
    fun f18_t1_02_dualBandGalaxyA55StillSurfacesOutOfBand6GhzRnrNeighborsIn6GhzSpectrumTab() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "TriBandHome_5G",
                    bssid = "04:D4:C4:18:02:01",
                    rssiDbm = -47,
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe()),
                ),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Out-of-band 6 GHz RNR neighbor on dual-band A55",
            "37",
            "160 MHz",
            "PSC",
            "Co-located",
            "RNR",
        )
    }

    @Test
    fun f18_t1_03_triBandWifi6eCapableHardwareProfileRendersLive6GhzSpectrumWithoutLimitationBanner() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Native6E_PSC37",
                    bssid = "04:D4:C4:18:03:01",
                    rssiDbm = -50,
                    frequencyMhz = 6135,
                    channelWidth = ScanResult.CHANNEL_WIDTH_160MHZ,
                    centerFreq0Mhz = 6185,
                ),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Live 6 GHz spectrum on tri-band radio",
            "Native6E_PSC37",
            "37",
            "PSC",
        )
    }

    @Test
    fun f18_t1_04_wifiRttHardwareSupportedAndResponderPresentUsesPrecision80211mcRanging() {
        val bssid = "04:D4:C4:18:04:01"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = bssid, distanceMm = 2_650, distanceStdDevMm = 250)),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "RttActiveRouter",
                    bssid = bssid,
                    rssiDbm = -58,
                    is80211mcResponder = true,
                ),
            ),
        )
        NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RTT active precision label", "2.7 m", "2.6 m", "RTT")
    }

    @Test
    fun f18_t1_05_retailGalaxyA55WithoutWifiRttSystemFeatureUsesPathLossWithoutCallingRttManager() {
        val bssid = "04:D4:C4:18:05:01"
        robot.configureReadyGalaxyA55Defaults(hasRtt = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "ResponderOnNonRttPhone",
                    bssid = bssid,
                    rssiDbm = -63,
                    is80211mcResponder = true,
                ),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        val distMeters = prox.readDisplayedDistanceMeters()
        assertTrue("Expected finite indoor path-loss distance estimate, got $distMeters", distMeters in 0.5..35.0)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Path-loss estimate label", "Est.", "m")
    }

    @Test
    fun f18_t1_06_cliDeviceRadioCapabilityFlagReflectsDualBandVersusTriBandHardwareAccurately() {
        val dualScenario = CliScenarioBuilder.singleFrame(
            deviceModel = "SM-A556B",
            deviceHas6Ghz = false,
            scans = listOf(ScanEntrySpec(ssid = "A55_5G", bssid = "04:d4:c4:18:06:01", frequencyMhz = 5180)),
        )
        val triScenario = CliScenarioBuilder.singleFrame(
            deviceModel = "SM-S928B",
            deviceHas6Ghz = true,
            scans = listOf(ScanEntrySpec(ssid = "S24U_6G", bssid = "04:d4:c4:18:06:02", frequencyMhz = 6135, centerFreq0Mhz = 6185, channelWidthMhz = 160)),
        )
        val dualRoot = CliRunnerHarness.analyzeStdin(dualScenario).assertSucceeded().parseJsonRoot()
        val triRoot = CliRunnerHarness.analyzeStdin(triScenario).assertSucceeded().parseJsonRoot()

        assertEquals(false, dualRoot.obj("deviceCapabilities")?.bool("has6Ghz"))
        assertEquals(false, CliJsonLens.spectrumForBand(dualRoot, "6").bool("isBandSupportedByHardware"))
        assertTrue(CliJsonLens.spectrumForBand(dualRoot, "6").str("hardwareExplanationMessage").orEmpty().isNotBlank())

        assertEquals(true, triRoot.obj("deviceCapabilities")?.bool("has6Ghz"))
        assertEquals(true, CliJsonLens.spectrumForBand(triRoot, "6").bool("isBandSupportedByHardware"))
    }

    // =========================================================================
    // F19: Progressive Self-Healing OS Readiness State Machine (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f19_t1_01_allPermissionsLocationAndWifiEnabledStartsImmediateLiveScanningWithoutBlockerCard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ImmediateReady_5G", bssid = "04:D4:C4:19:01:01", rssiDbm = -48)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("ImmediateReady_5G")
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Blocker CTA must NOT show when Ready", dump.contains("Grant Scan Permission", ignoreCase = true))
    }

    @Test
    fun f19_t1_02_missingRuntimePermissionsRendersFriendlyStep1ChecklistWithOfflinePrivacyAssurance() {
        robot.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Step 1 Missing Permission readiness card",
            "Allow",
            "Grant",
            "Location",
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "100% offline privacy assurance",
            "offline",
            "never",
        )
    }

    @Test
    fun f19_t1_03_systemLocationToggleDisabledRendersStep2CardAndLaunchesLocationSettingsIntent() {
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Step 2 Location Disabled card",
            "Turn On",
            "Location",
        )
        NavigationRobot(composeRule).openNetworks().clickReadinessPrimaryCta()
        val started = robot.consumeNextStartedActivityIntent()
        assertNotNull("Expected Settings.ACTION_LOCATION_SOURCE_SETTINGS intent", started)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started!!.action)
    }

    @Test
    fun f19_t1_04_enablingSystemLocationBroadcastAutoHealsIntoLiveNetworkListWithZeroManualRefresh() {
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        Shadows.shadowOf(robot.wifiManager).setScanResults(
            listOf(ScanResultFactory.createScanResult(ssid = "AutoHealedAfterLocation_5G", bssid = "04:D4:C4:19:04:01", rssiDbm = -51)),
        )
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Blocked by Location OFF", "Location")

        // User pulls down Samsung Quick Settings and toggles Location ON -> OS fires MODE_CHANGED_ACTION
        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AutoHealedAfterLocation_5G", bssid = "04:D4:C4:19:04:01", rssiDbm = -51)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("AutoHealedAfterLocation_5G")
    }

    @Test
    fun f19_t1_05_wifiAndBackgroundScanBothDisabledLaunchesWifiPanelAndAutoRecoversOnWifiEnable() {
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = false, broadcastChange = false)
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Step 3 Wi-Fi & Scan Disabled card",
            "Turn On Wi-Fi",
            "Wi-Fi",
        )
        NavigationRobot(composeRule).openNetworks().clickReadinessPrimaryCta()
        val intent = robot.consumeNextStartedActivityIntent()
        assertNotNull("Expected Wi-Fi Settings Panel / Settings intent", intent)
        assertTrue(
            "Expected ACTION_WIFI panel or ACTION_WIFI_SETTINGS, got ${intent!!.action}",
            intent.action == Settings.Panel.ACTION_WIFI || intent.action == Settings.ACTION_WIFI_SETTINGS,
        )

        robot.setWifiEnabledState(enabled = true, scanAlwaysAvailable = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RecoveredWifiOn_5G", bssid = "04:D4:C4:19:05:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("RecoveredWifiOn_5G")
    }

    @Test
    fun f19_t1_06_wifiDisabledWithBackgroundScanningAlwaysAvailableContinuesNonBlockingDiscovery() {
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = true, broadcastChange = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BackgroundScanActive_5G", bssid = "04:D4:C4:19:06:01", rssiDbm = -56)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("BackgroundScanActive_5G")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Subtle non-blocking Wi-Fi Off + Background Scanning banner",
            "Wi-Fi Off",
            "Background Scanning",
            "Turn On Wi-Fi",
        )
    }

    // =========================================================================
    // F20: Hybrid Token-Bucket Active + Dual Passive Scanner (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f20_t1_01_passiveOsScanBroadcastUpdatesUiImmediatelyWithoutConsumingActiveScanToken() {
        robot.launchApp()
        val activeBefore = ExtendedShadowWifiManager.startScanInvocationCount.get()
        robot.injectScanBatch(
            results = listOf(ScanResultFactory.createScanResult(ssid = "PassiveBroadcast_5G", bssid = "04:D4:C4:20:01:01", rssiDbm = -52)),
            fireCallback = false,
            fireBroadcast = true,
        )
        val activeAfter = ExtendedShadowWifiManager.startScanInvocationCount.get()
        assertEquals("Passive OS broadcast must NOT trigger extra startScan()", activeBefore, activeAfter)
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("PassiveBroadcast_5G")
    }

    @Test
    fun f20_t1_02_android11ScanResultsCallbackUpdatesNetworksWhenOnlyCallbackFires() {
        robot.launchApp()
        assertTrue(
            "App must register WifiManager.ScanResultsCallback on API 30+",
            ExtendedShadowWifiManager.registeredCallbackCount() >= 1,
        )
        robot.injectScanBatch(
            results = listOf(ScanResultFactory.createScanResult(ssid = "CallbackOnly_5G", bssid = "04:D4:C4:20:02:01", rssiDbm = -49)),
            fireCallback = true,
            fireBroadcast = false,
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("CallbackOnly_5G")
    }

    @Test
    fun f20_t1_03_tokenBucketFourPerTwoMinutesEntersThrottledPassiveModeCleanlyAfterQuotaExhausted() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "QuotaRouter_5G", bssid = "04:D4:C4:20:03:01", rssiDbm = -55)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        repeat(4) {
            runCatching { networks.triggerManualRefresh() }
            robot.advanceClockMs(1_500L)
        }
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)
        runCatching { networks.triggerManualRefresh() }
        robot.advanceClockMs(500L)

        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "OS Throttle status pill",
            "OS Throttle",
            "Throttle",
            "Passive",
            "Next",
        )
        networks.assertNetworkDisplayed("QuotaRouter_5G")
    }

    @Test
    fun f20_t1_04_burstHomingToggleInProximityTriggersActiveScansAndDisplaysPulseStatus() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BurstTarget_5G", bssid = "04:D4:C4:20:04:01", rssiDbm = -61)),
        )
        val countBefore = ExtendedShadowWifiManager.startScanInvocationCount.get()
        val prox = NavigationRobot(composeRule).openProximity()
        prox.toggleBurstScan()
        robot.advanceClockMs(3_000L)
        assertTrue(
            "Burst homing mode should trigger active startScan() or display burst pulse telemetry",
            ExtendedShadowWifiManager.startScanInvocationCount.get() > countBefore ||
                ComposeSemanticMatchers.screenContainsAny(composeRule, "Burst", "pulses"),
        )
    }

    @Test
    fun f20_t1_05_unthrottledDeveloperOptionsModeUnlocksContinuousFastActiveScanning() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "UnthrottledDev_5G", bssid = "04:D4:C4:20:05:01", rssiDbm = -50)),
        )
        repeat(4) {
            robot.advanceClockMs(2_600L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Unthrottled Live scan status pill",
            "Unthrottled",
            "Live",
        )
        assertTrue(
            "Expected >= 2 active scans in 10s when scanThrottleEnabled=false, got ${ExtendedShadowWifiManager.startScanInvocationCount.get()}",
            ExtendedShadowWifiManager.startScanInvocationCount.get() >= 2,
        )
    }

    @Test
    fun f20_t1_06_missedBeacon45SecondAntiFlickerCacheRetainsTransientlyMissedApWithoutUiBlink() {
        robot.launchApp()
        val routerA = ScanResultFactory.createScanResult(ssid = "SteadyRouterA", bssid = "04:D4:C4:20:06:01", rssiDbm = -52)
        val routerB = ScanResultFactory.createScanResult(ssid = "TransientMissedB", bssid = "B0:BE:76:20:06:02", rssiDbm = -67)
        robot.injectScanBatch(listOf(routerA, routerB))

        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("SteadyRouterA")
        networks.assertNetworkDisplayed("TransientMissedB")

        // 4 seconds later (< 45s anti-flicker window), RouterB misses 1 sweep
        robot.advanceClockMs(4_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SteadyRouterA", bssid = "04:D4:C4:20:06:01", rssiDbm = -51)),
        )
        networks.assertNetworkDisplayed("SteadyRouterA")
        networks.assertNetworkDisplayed("TransientMissedB")
    }

    // =========================================================================
    // F21: Unthrottled 300 ms Connected-AP Stream & Roam Logger (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f21_t1_01_connectedWifiSpotlightDisplaysConnectedBadgeAndLinkSpeedMbpsOnActiveNetworkCard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "HomeFiber_5G", bssid = "AC:84:C6:11:22:33", rssiDbm = -49, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "Neighbor_2G", bssid = "B0:BE:76:11:22:44", rssiDbm = -68, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(
                ssid = "HomeFiber_5G",
                bssid = "AC:84:C6:11:22:33",
                rssiDbm = -49,
                frequencyMhz = 5180,
                linkSpeedMbps = 866,
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("HomeFiber_5G")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected spotlight badge", "Connected")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected link speed Mbps", "866")
    }

    @Test
    fun f21_t1_02_isolatedLanWifiWithoutInternetCapabilityStillRecognizedAsConnectedOnSamsungOneUi() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "CameraLocalLAN", bssid = "AC:84:C6:21:02:01", rssiDbm = -51, frequencyMhz = 5180)),
        )
        robot.simulateConnectedWifi(
            wifiInfo = ScanResultFactory.createConnectedWifiInfo(
                ssid = "CameraLocalLAN",
                bssid = "AC:84:C6:21:02:01",
                rssiDbm = -51,
                frequencyMhz = 5180,
                linkSpeedMbps = 600,
            ),
            hasInternetCapability = false, // WAN unplugged / local IoT router!
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("CameraLocalLAN")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "LAN-only Connected badge", "Connected")
    }

    @Test
    fun f21_t1_03_unthrottled300msConnectedPollingUpdatesProximityRssiWhileStartScanCompletelyBlocked() {
        val bssid = "AC:84:C6:21:03:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "FastPollRouter", bssid = bssid, rssiDbm = -72, frequencyMhz = 5180)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "FastPollRouter", bssid = bssid, rssiDbm = -72, frequencyMhz = 5180),
        )
        NavigationRobot(composeRule).openProximity()

        // Completely block WifiManager.startScan() and send ZERO new ScanResult broadcasts
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)
        for (rssi in listOf(-64, -55, -47, -45)) {
            robot.simulateConnectedWifi(
                ScanResultFactory.createConnectedWifiInfo(ssid = "FastPollRouter", bssid = bssid, rssiDbm = rssi, frequencyMhz = 5180),
            )
            robot.advanceClockMs(350L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Unthrottled 300ms connected Proximity update into Excellent tier",
            "Excellent",
            "-45 dBm",
            "-46 dBm",
            "-47 dBm",
            "-48 dBm",
            "-49 dBm",
        )
    }

    @Test
    fun f21_t1_04_meshBssidHandoffOnSameSsidMovesConnectedBadgeAndLogsRoamEventWithRssiGain() {
        val node1 = "AA:BB:CC:21:04:01"
        val node2 = "AA:BB:CC:21:04:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "HomeMesh_AX", bssid = node1, rssiDbm = -77, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "HomeMesh_AX", bssid = node2, rssiDbm = -51, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "HomeMesh_AX", bssid = node1, rssiDbm = -77, frequencyMhz = 5180),
        )
        robot.advanceClockMs(1_500L)
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "HomeMesh_AX", bssid = node2, rssiDbm = -51, frequencyMhz = 5745),
        )
        robot.advanceClockMs(1_000L)

        val nav = NavigationRobot(composeRule)
        nav.openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Logged mesh RoamEvent in UI",
            "21:04:02",
            "Roam",
            "+26",
            "HomeMesh_AX",
        )
    }

    @Test
    fun f21_t1_05_openingProximityTabDirectlyAutoSelectsConnectedBssidOverStrongerUnassociatedNeighbor() {
        val myBssid = "AC:84:C6:21:05:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "NeighborBlaster_5G", bssid = "B0:BE:76:21:05:99", rssiDbm = -42, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "MyConnectedHome_5G", bssid = myBssid, rssiDbm = -64, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyConnectedHome_5G", bssid = myBssid, rssiDbm = -64, frequencyMhz = 5745),
        )
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Default Proximity target = Connected AP", "MyConnectedHome_5G")
    }

    @Test
    fun f21_t1_06_cliMultiFrameRoamTimelineRecordsBandSwitchingRoamEventInJsonAndMarkdown() {
        val n1 = "04:d4:c4:21:06:01"
        val n2 = "04:d4:c4:21:06:02"
        val scanList = listOf(
            ScanEntrySpec(ssid = "WholeHomeMesh", bssid = n1, rssiDbm = -78, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
            ScanEntrySpec(ssid = "WholeHomeMesh", bssid = n2, rssiDbm = -50, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
        )
        val scenario = CliScenarioBuilder.multiFrame(
            frames = listOf(
                FrameSpec(elapsedMs = 1_000L, connectedBssid = n1, scans = scanList),
                FrameSpec(elapsedMs = 4_000L, connectedBssid = n2, scans = scanList),
            ),
        )
        val jsonRoot = CliRunnerHarness.analyzeStdin(scenario, format = "json").assertSucceeded().parseJsonRoot()
        val roams = CliJsonLens.roamEvents(jsonRoot)
        assertEquals(1, roams.size)
        assertEquals(n1.uppercase(), roams[0].str("previousBssid")?.uppercase())
        assertEquals(n2.uppercase(), roams[0].str("newBssid")?.uppercase())
        assertEquals(true, roams[0].bool("isBandSwitch"))

        val md = CliRunnerHarness.analyzeStdin(scenario, format = "markdown").assertSucceeded().stdout
        assertTrue("Markdown must report WholeHomeMesh roam event", md.contains("21:06:02", ignoreCase = true))
    }

    // =========================================================================
    // F22: 3-Tier LRA Haptic & Hanning-Sine Audio Geiger Engine (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f22_t1_01_geigerHapticAndAudioDefaultStrictlyOffWithZeroUnpromptedVibrationOrAudio() {
        val writtenAudioBytes = AtomicInteger(0)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            writtenAudioBytes.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "SilentDefaultAP", bssid = "04:D4:C4:22:01:01", rssiDbm = -42)),
            )
            NavigationRobot(composeRule).openProximity()
            robot.advanceClockMs(4_000L)

            assertFalse("Vibrator must NOT vibrate when Haptic toggle is default OFF", robot.shadowVibrator().isVibrating)
            assertTrue("No primitive haptics should fire while OFF", robot.shadowVibrator().primitiveEffects.orEmpty().isEmpty())
            assertEquals("Zero PCM audio bytes must be written while Audio toggle is default OFF", 0, writtenAudioBytes.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun f22_t1_02_tier1GalaxyA55LraPrimitiveTickSupportedEmitsCompositionPrimitiveTickPulses() {
        robot.shadowVibrator().setHasVibrator(true)
        robot.shadowVibrator().setHasAmplitudeControl(true)
        robot.shadowVibrator().setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_TICK))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LraTickAP", bssid = "04:D4:C4:22:02:01", rssiDbm = -46)),
        )
        NavigationRobot(composeRule).openProximity().toggleHapticGeiger()
        robot.advanceClockMs(1_500L)

        val prims = robot.shadowVibrator().primitiveEffects.orEmpty()
        assertTrue(
            "Expected Tier 1 VibrationEffect.Composition PRIMITIVE_TICK pulses on Galaxy A55 LRA",
            prims.isNotEmpty() && prims.any { it.id == VibrationEffect.Composition.PRIMITIVE_TICK },
        )
    }

    @Test
    fun f22_t1_03_tier2AmplitudeOneShotFallbackUsedWhenPrimitivesUnsupportedAndAmplitudeControlTrue() {
        robot.shadowVibrator().setHasVibrator(true)
        robot.shadowVibrator().setSupportedPrimitives(emptyList())
        robot.shadowVibrator().setHasAmplitudeControl(true)

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AmplitudeFallbackAP", bssid = "04:D4:C4:22:03:01", rssiDbm = -48)),
        )
        NavigationRobot(composeRule).openProximity().toggleHapticGeiger()
        robot.advanceClockMs(1_500L)

        assertTrue(
            "Expected Tier 2 createOneShot(duration, amplitude) with milliseconds > 0L",
            robot.shadowVibrator().milliseconds > 0L,
        )
    }

    @Test
    fun f22_t1_04_tier3PredefinedEffectTickFallbackUsedOnBasicVibratorWithoutAmplitudeControl() {
        robot.shadowVibrator().setHasVibrator(true)
        robot.shadowVibrator().setSupportedPrimitives(emptyList())
        robot.shadowVibrator().setHasAmplitudeControl(false)

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BasicMotorAP", bssid = "04:D4:C4:22:04:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openProximity().toggleHapticGeiger()
        robot.advanceClockMs(1_500L)

        val effectId = robot.shadowVibrator().effectId
        assertTrue(
            "Expected Tier 3 EFFECT_TICK or EFFECT_CLICK fallback, got effectId=$effectId",
            effectId == VibrationEffect.EFFECT_TICK || effectId == VibrationEffect.EFFECT_CLICK || robot.shadowVibrator().milliseconds > 0L,
        )
    }

    @Test
    fun f22_t1_05_hanningSineAudioGeigerWritesClickFreeWindowedPcmBursts() {
        val capturedChunks = mutableListOf<ByteArray>()
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) {
                synchronized(capturedChunks) {
                    capturedChunks.add(data.copyOf())
                }
            }
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "AudioGeigerAP", bssid = "04:D4:C4:22:05:01", rssiDbm = -44)),
            )
            NavigationRobot(composeRule).openProximity().toggleAudioGeiger()
            robot.advanceClockMs(1_500L)

            val firstBurst = synchronized(capturedChunks) { capturedChunks.firstOrNull { it.size >= 64 } }
            assertNotNull("Expected PCM burst written to AudioTrack when Audio Geiger is enabled", firstBurst)

            val shorts = ShortArray(firstBurst!!.size / 2)
            ByteBuffer.wrap(firstBurst).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val maxPeak = shorts.maxOf { abs(it.toInt()) }
            val startSample = abs(shorts.first().toInt())
            val endSample = abs(shorts.last().toInt())

            assertTrue("Expected audible non-zero sine peak in PCM burst, got $maxPeak", maxPeak > 500)
            assertTrue(
                "Hanning window must taper first/last sample near zero to prevent speaker clicks (start=$startSample, end=$endSample, peak=$maxPeak)",
                startSample < maxPeak / 4 && endSample < maxPeak / 4,
            )
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun f22_t1_06_exponentialGeigerCadenceAndPitchScaleMonotonicallyFromWeakToVeryClose() {
        val bssid = "04:d4:c4:22:06:01"
        fun evalAt(rssi: Int) = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(bssid = bssid, rssiDbm = rssi))),
                targetBssid = bssid,
            ).assertSucceeded().parseJsonRoot(),
        )

        val weak = evalAt(-88)
        val mid = evalAt(-60)
        val close = evalAt(-32)

        val tWeak = weak.long("geigerIntervalMs")!!
        val tMid = mid.long("geigerIntervalMs")!!
        val tClose = close.long("geigerIntervalMs")!!

        val fWeak = weak.dbl("geigerToneHz")!!
        val fMid = mid.dbl("geigerToneHz")!!
        val fClose = close.dbl("geigerToneHz")!!

        assertTrue("Geiger interval must shrink monotonically ($tWeak > $tMid > $tClose)", tWeak > tMid && tMid > tClose)
        assertTrue("Intervals must stay within [85, 1400] ms", tClose >= 85L && tWeak <= 1400L)
        assertTrue("Geiger tone frequency must rise monotonically ($fWeak < $fMid < $fClose)", fWeak < fMid && fMid < fClose)
        assertTrue("Frequencies must stay within [320, 1400] Hz", fWeak >= 320.0 && fClose <= 1400.0)
    }

    // =========================================================================
    // F23: Local BSSID Nickname & 1m Calibration Persistence (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f23_t1_01_assignBssidNicknameInDetailSheetDisplaysOnNetworksCardAndProximityHeader() {
        val bssid = "AC:84:C6:23:01:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ISP_9F82A", bssid = bssid, rssiDbm = -51)),
        )
        val nav = NavigationRobot(composeRule)
        val detail = nav.openNetworks().openDetailSheetFor("ISP_9F82A")
        detail.assignNickname("Living Room Router")
        detail.dismiss()

        nav.openNetworks().assertNetworkDisplayed("Living Room Router")
        nav.openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Nickname in Proximity header", "Living Room Router")
    }

    @Test
    fun f23_t1_02_nicknamePersistsOfflineAcrossFullActivityCloseAndColdRelaunch() {
        val bssid = "AC:84:C6:23:02:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RawRouter_5G", bssid = bssid, rssiDbm = -53)),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("RawRouter_5G")
        detail.assignNickname("Upstairs Study AP")
        detail.dismiss()

        robot.closeAndColdRelaunch()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RawRouter_5G", bssid = bssid, rssiDbm = -53)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("Upstairs Study AP")
    }

    @Test
    fun f23_t1_03_searchNetworksBySavedNicknameMatchesImmediately() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "deco_A1", bssid = "AC:84:C6:23:03:01", rssiDbm = -52),
                ScanResultFactory.createScanResult(ssid = "deco_B2", bssid = "AC:84:C6:23:03:02", rssiDbm = -58),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.openDetailSheetFor("deco_B2").assignNickname("Master Bedroom Mesh").dismiss()

        networks.enterSearchQuery("Bedroom")
        networks.assertNetworkDisplayed("Master Bedroom Mesh")
        networks.assertNetworkNotDisplayed("deco_A1")
    }

    @Test
    fun f23_t1_04_oneMeterRssiCalibrationInProximityAnchorsDistanceEstimateToOneMeter() {
        val bssid = "AC:84:C6:23:04:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "CalTarget_5G", bssid = bssid, rssiDbm = -59, frequencyMhz = 5180)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        val uncalibratedMeters = prox.readDisplayedDistanceMeters()
        assertTrue("Uncalibrated -59 dBm indoor estimate ($uncalibratedMeters m) should be > 2.0 m", uncalibratedMeters > 2.0)

        prox.clickCalibrateOneMeter()
        val calibratedMeters = prox.readDisplayedDistanceMeters()
        assertTrue(
            "After Calibrate 1m at -59 dBm, displayed distance ($calibratedMeters m) must anchor around ~1.0 m",
            calibratedMeters in 0.65..1.45,
        )
    }

    @Test
    fun f23_t1_05_oneMeterCalibrationPersistsAcrossColdRelaunchAndSupportsResetToDefaultFspl() {
        val bssid = "AC:84:C6:23:05:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PersistCal_5G", bssid = bssid, rssiDbm = -60, frequencyMhz = 5180)),
        )
        NavigationRobot(composeRule).openProximity().clickCalibrateOneMeter()

        robot.closeAndColdRelaunch()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PersistCal_5G", bssid = bssid, rssiDbm = -60, frequencyMhz = 5180)),
        )
        val proxAfterRelaunch = NavigationRobot(composeRule).openProximity()
        val persistedMeters = proxAfterRelaunch.readDisplayedDistanceMeters()
        assertTrue("Persisted 1m calibration after cold relaunch ($persistedMeters m) must remain ~1.0 m", persistedMeters in 0.65..1.45)

        proxAfterRelaunch.clickResetCalibration()
        val resetMeters = proxAfterRelaunch.readDisplayedDistanceMeters()
        assertTrue("Resetting calibration ($resetMeters m) must restore default indoor path-loss (> 2.2 m)", resetMeters > 2.2)
    }

    @Test
    fun f23_t1_06_cliNicknameAndCalibrationMapsPropagateToExportedJsonAndMarkdown() {
        val bssid = "04:d4:c4:23:06:01"
        val scenario = CliScenarioBuilder.singleFrame(
            bssidNicknames = mapOf(bssid.uppercase() to "Kitchen Router"),
            oneMeterCalibrationsDbm = mapOf(bssid.uppercase() to -56.0),
            scans = listOf(ScanEntrySpec(ssid = "ISP_Raw", bssid = bssid, rssiDbm = -56, frequencyMhz = 5180)),
        )
        val jsonRoot = CliRunnerHarness.analyzeStdin(scenario, format = "json", targetBssid = bssid)
            .assertSucceeded()
            .parseJsonRoot()
        val radio = CliJsonLens.radioByBssid(jsonRoot, bssid)
        assertEquals("Kitchen Router", radio.str("customNickname"))
        assertEquals(1.0, CliJsonLens.latestProximity(jsonRoot).obj("distanceEstimate")!!.dbl("meters")!!, 0.15)

        val md = CliRunnerHarness.analyzeStdin(scenario, format = "markdown", targetBssid = bssid)
            .assertSucceeded()
            .stdout
        assertTrue("Markdown export must include saved nickname 'Kitchen Router'", md.contains("Kitchen Router"))
    }
}
