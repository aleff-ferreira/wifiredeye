@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier4

import android.net.wifi.ScanResult
import android.os.DeadObjectException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.ApkBinaryInspector
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.FrameSpec
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.LegendPillRobot
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
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
 * Tier 4 Real-World Samsung Galaxy A55 5G User Journeys — Part 2 (`T4_J11`–`T4_J20` = `10` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier4GalaxyA55RealWorldJourneysPart2E2eTest {

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
    fun t4_j11_enterpriseItAdminWalkingFloorplanCiscoArubaHostnames80211kvrAndDfsTdwrPlanning() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "CorpHQ_192",
                    bssid = "00:40:96:74:11:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_SUITE_B_192_GCMP256_MFPR.toScanResultIe(),
                        BeaconIeVectors.VENDOR_CISCO_CCX_AP_NAME.toScanResultIe(),
                        BeaconIeVectors.RM_ENABLED_80211K.toScanResultIe(),
                        BeaconIeVectors.EXT_CAP_11V_UTF8_FTM_TWT_BEACONPROT.toScanResultIe(),
                        BeaconIeVectors.MOBILITY_DOMAIN_80211R.toScanResultIe(),
                    ),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "CorpHQ_192",
                    bssid = "00:0B:86:74:11:02",
                    rssiDbm = -54,
                    frequencyMhz = 5745,
                    centerFreq0Mhz = 5775,
                    capabilities = "[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_SUITE_B_192_GCMP256_MFPR.toScanResultIe(),
                        BeaconIeVectors.VENDOR_ARUBA_AP_NAME.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks().switchToAllRadiosMode()
        val ciscoDetail = networks.openDetailSheetFor("00:40:96:74:11:01")
        ciscoDetail.assertDetailContains("HQ-3F-AP12", "802.11k/v/r", "A+")
        ciscoDetail.dismiss()

        val spec = nav.openSpectrum().selectBand("5 GHz").toggleIncludeDfs()
        spec.scrollUntilVisible("52", "100", "DFS")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Clean non-TDWR UNII-2 DFS channel planned", "52", "60", "100", "132")
    }

    @Test
    fun t4_j12_smartHomeInstallerDebugging24GhzIotPlugDeadZoneThroughConcreteWalls() {
        val iotRouter = "B0:BE:76:74:12:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Home5G_Fast", bssid = "04:D4:C4:74:12:02", rssiDbm = -49, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "SmartPlug_24GHz", bssid = iotRouter, rssiDbm = -62, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks().toggleFilterChip("2.4 GHz")
        networks.assertNetworkDisplayed("SmartPlug_24GHz").assertNetworkNotDisplayed("Home5G_Fast")

        val prox = networks.launchProximityFor("SmartPlug_24GHz")
        prox.selectEnvironment("Heavy Concrete", "Heavy")

        // Walk out through reinforced garage wall -> signal drops to -85 dBm
        for ((idx, r) in listOf(-72, -79, -85).withIndex()) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "SmartPlug_24GHz", bssid = iotRouter, rssiDbm = r, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412, explicitTimestampMicros = (idx + 2) * 1_000_000L),
                ),
            )
            robot.advanceClockMs(1_000L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Moving Away & Weak/Very Weak garage dead zone", "Moving Away", "Weak", "Very Weak")

        // Copy summary from Detail Sheet for installer work order
        nav.openNetworks().toggleFilterChip("All").openDetailSheetFor("SmartPlug_24GHz").copySummary()
        assertTrue(ShareIntentInspector.readClipboardText(robot.appContext).contains("SmartPlug_24GHz"))
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun t4_j13_accessibilityLowVisionUser200PercentFontScaleFullFourTabDiagnosticTour() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "AccessibleHome_AX",
                    bssid = "04:D4:C4:74:13:01",
                    rssiDbm = -50,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("AccessibleHome_AX")
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.dismissLegendCard(composeRule, "dbm")

        nav.openProximity().assertAccessibleCanvasPresent()
        nav.openSpectrum().assertAccessibleCanvasPresent()
        nav.openInsights().assertAccessibleHealthRingPresent()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T4_J13-Accessibility200")
    }

    @Test
    fun t4_j14_commuterWalkingThrough60UrbanNetworksBackgroundingLifecycleAndOrientationChanges() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = false)
        robot.launchApp()
        val sixtyStreet = (1..60).map { i ->
            ScanResultFactory.createScanResult(
                ssid = String.format("StreetBlock_%02d", i),
                bssid = String.format("04:D4:C4:74:14:%02X", i),
                rssiDbm = -44 - (i / 2),
                frequencyMhz = if (i % 2 == 0) 5180 else 2437,
                channelWidth = if (i % 2 == 0) ScanResult.CHANNEL_WIDTH_80MHZ else ScanResult.CHANNEL_WIDTH_20MHZ,
                centerFreq0Mhz = if (i % 2 == 0) 5210 else 2437,
            )
        }
        robot.injectScanBatch(sixtyStreet)

        val nav = NavigationRobot(composeRule)
        nav.openNetworks().enterSearchQuery("StreetBlock_42")
        robot.recreateApp()
        nav.openNetworks().assertNetworkDisplayed("StreetBlock_42")

        // Commuter puts Galaxy A55 in pocket (ON_STOP) for 30s -> zero active scans!
        robot.moveToLifecycle(Lifecycle.State.CREATED)
        val countAtPocket = ExtendedShadowWifiManager.startScanInvocationCount.get()
        robot.advanceClockMs(30_000L)
        assertEquals(countAtPocket, ExtendedShadowWifiManager.startScanInvocationCount.get())

        // Pulls phone back out (RESUMED) -> immediate resumption
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        nav.openNetworks().assertNetworkDisplayed("StreetBlock_42")
    }

    @Test
    fun t4_j15_hostileDefconRfEnvironmentFuzzedBeaconsAndAndroidIpcFaultResilience() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = null,
                    rawSsidBytes = byteArrayOf(0xC3.toByte(), 0x28, 0xFE.toByte(), 0xFF.toByte()),
                    bssid = "04:D4:C4:74:15:01",
                    rssiDbm = 0, // Spoofed 0 dBm!
                    ies = listOf(
                        BeaconIeVectors.EMPTY_0B_BSS_LOAD.toScanResultIe(),
                        BeaconIeVectors.RSN_OVERFLOW_COUNT_500.toScanResultIe(),
                        BeaconIeVectors.WPS_OVERFLOW_TLV_LEN.toScanResultIe(),
                        BeaconIeVectors.RNR_TRUNCATED_TBTT_ENTRY.toScanResultIe(),
                        BeaconIeVectors.TRUNCATED_20B_HE_CAP.toScanResultIe(),
                    ),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "DefconSurvivor_AX",
                    bssid = "FC:EC:DA:74:15:02",
                    rssiDbm = -112,
                    ies = listOf(BeaconIeVectors.BSS_LOAD_MAX_65535_100_PCT.toScanResultIe()),
                ),
            ),
        )
        // Transient Binder glitch mid-conference
        ExtendedShadowWifiManager.throwOnStartScan = RuntimeException(DeadObjectException("HAL hiccup"))
        val nav = NavigationRobot(composeRule)
        runCatching { nav.openNetworks().triggerManualRefresh() }
        ExtendedShadowWifiManager.throwOnStartScan = null

        nav.openNetworks().assertNetworkDisplayed("DefconSurvivor_AX")
        nav.openProximity()
        nav.openSpectrum()
        nav.openInsights()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T4_J15-DefconFuzz")
    }

    @Test
    fun t4_j16_isolatedLocalCameraDroneLanWifiWithoutInternetOnSamsungOneUi() {
        val droneBssid = "AC:84:C6:74:16:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SonyAlpha_Direct5G", bssid = droneBssid, rssiDbm = -56, frequencyMhz = 5745, centerFreq0Mhz = 5775)),
        )
        // Connected to local Wi-Fi AP with NO internet capability (NET_CAPABILITY_INTERNET = false)
        robot.simulateConnectedWifi(
            wifiInfo = ScanResultFactory.createConnectedWifiInfo(
                ssid = "SonyAlpha_Direct5G",
                bssid = droneBssid,
                rssiDbm = -56,
                frequencyMhz = 5745,
                linkSpeedMbps = 866,
            ),
            hasInternetCapability = false,
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("SonyAlpha_Direct5G")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "LAN-only Connected spotlight", "Connected", "866")

        nav.openProximity()
        for (r in listOf(-50, -45)) {
            robot.simulateConnectedWifi(
                wifiInfo = ScanResultFactory.createConnectedWifiInfo(ssid = "SonyAlpha_Direct5G", bssid = droneBssid, rssiDbm = r, frequencyMhz = 5745),
                hasInternetCapability = false,
            )
            robot.advanceClockMs(350L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Live 300ms LAN telemetry", "Excellent", "-45 dBm", "-46 dBm")
    }

    @Test
    fun t4_j17_hiddenHomeSecurityVlanAuditNullBytePaddingVersusConnectedReveal() {
        val camVlanBssid = "04:D4:C4:74:17:01"
        val nvrVlanBssid = "FC:EC:DA:74:17:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(8) { 0 },
                    bssid = camVlanBssid,
                    rssiDbm = -52,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(0),
                    bssid = nvrVlanBssid,
                    rssiDbm = -64,
                    capabilities = "[ESS]",
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        // Must NOT false-trigger Evil-Twin across unrelated hidden APs!
        nav.openInsights()
        assertFalse(ComposeSemanticMatchers.dumpScreenText(composeRule).contains("Evil Twin", ignoreCase = true))

        // Connect to camVlanBssid -> real VLAN SSID revealed!
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "SecCam_VLAN40", bssid = camVlanBssid, rssiDbm = -52),
        )
        nav.openNetworks().assertNetworkDisplayed("SecCam_VLAN40")
    }

    @Test
    fun t4_j18_wifiRtt80211mcCapableVenuePrecisionWalkAndPathLossComparison() {
        val rttAp = "04:D4:C4:74:18:01"
        val legacyAp = "B0:BE:76:74:18:02"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = rttAp, distanceMm = 2_400, distanceStdDevMm = 200)),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Museum_FTM_AP", bssid = rttAp, rssiDbm = -56, is80211mcResponder = true),
                ScanResultFactory.createScanResult(ssid = "GiftShop_StandardAP", bssid = legacyAp, rssiDbm = -66, is80211mcResponder = false),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Precision 2.4 m RTT", "2.4 m", "RTT")

        prox.selectTargetViaPicker("GiftShop_StandardAP")
        assertTrue(prox.readDisplayedDistanceMeters() in 1.0..25.0)
    }

    @Test
    fun t4_j19_headlessCiPipelineCliBatchAuditMatchingAndroidAppEngineOutput() {
        val b1 = "04:D4:C4:74:19:01"
        val b2 = "04:D4:C4:74:19:02"
        val cliScenario = CliScenarioBuilder.multiFrame(
            frames = listOf(
                FrameSpec(
                    elapsedMs = 1_000L,
                    connectedBssid = b1,
                    scans = listOf(
                        ScanEntrySpec(ssid = "CiAuditMesh", bssid = b1, rssiDbm = -72, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                        ScanEntrySpec(ssid = "CiAuditMesh", bssid = b2, rssiDbm = -48, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                    ),
                ),
                FrameSpec(
                    elapsedMs = 3_000L,
                    connectedBssid = b2,
                    scans = listOf(
                        ScanEntrySpec(ssid = "CiAuditMesh", bssid = b1, rssiDbm = -76, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                        ScanEntrySpec(ssid = "CiAuditMesh", bssid = b2, rssiDbm = -46, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                    ),
                ),
            ),
        )
        val tmpFile = CliRunnerHarness.createTempScenarioFile("t4_j19_", cliScenario)
        val jsonRoot = CliRunnerHarness.analyzeFile(tmpFile, format = "json", targetBssid = b2, env = "HEAVY_WALLS", includeDfs = true, redactMacs = true)
            .assertSucceeded()
            .parseJsonRoot()
        val mdReport = CliRunnerHarness.analyzeFile(tmpFile, format = "markdown", targetBssid = b2, env = "HEAVY_WALLS", includeDfs = true, redactMacs = true)
            .assertSucceeded()
            .stdout

        assertEquals(1, CliJsonLens.roamEvents(jsonRoot).size)
        assertTrue(CliJsonLens.healthObj(jsonRoot).int("overallScore0To100")!! in 60..100)
        assertFalse(mdReport.contains("74:19:02", ignoreCase = true))
        assertTrue(mdReport.contains("CiAuditMesh"))
    }

    @Test
    fun t4_j20_productionReleaseApkSideloadReadinessAuditForSamsungGalaxyA55_5G() {
        val releaseApk = ApkBinaryInspector.resolveSignedReleaseApk()
        assertTrue("Signed installable release APK must exist", releaseApk.isFile && releaseApk.length() > 100_000L)

        // 1. Cryptographic signature v2 + v3 verification
        val sig = ApkBinaryInspector.verifySignature(releaseApk)
        assertEquals(0, sig.exitCode)
        assertTrue(sig.stdout.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))

        // 2. Manifest minSdk 30 / targetSdk 35 & 100% offline zero-INTERNET verification
        val badging = ApkBinaryInspector.dumpBadging(releaseApk)
        assertTrue(badging.stdout.contains("sdkVersion:'30'"))
        assertTrue(badging.stdout.contains("targetSdkVersion:'35'"))
        val perms = ApkBinaryInspector.dumpPermissions(releaseApk)
        assertFalse(perms.stdout.contains("android.permission.INTERNET"))

        // 3. Cold launcher boot and 4-tab visual capture tour on Galaxy A55 5G viewport
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "GalaxyA55_ProductionReady",
                    bssid = "04:D4:C4:74:20:01",
                    rssiDbm = -46,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("GalaxyA55_ProductionReady")
        robot.captureRootBitmap("t4_j20_01_networks.png")
        nav.openProximity()
        robot.captureRootBitmap("t4_j20_02_proximity.png")
        nav.openSpectrum()
        robot.captureRootBitmap("t4_j20_03_spectrum.png")
        nav.openInsights()
        robot.captureRootBitmap("t4_j20_04_insights.png")
    }
}
