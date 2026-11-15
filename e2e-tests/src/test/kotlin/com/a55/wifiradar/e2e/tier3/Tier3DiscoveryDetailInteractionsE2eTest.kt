@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier3

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.LegendPillRobot
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 3 Cross-Feature Pairwise Interaction E2E Tests: Discovery, RF Core & Detail Sheet (`T3_01`–`T3_07` = `7` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier3DiscoveryDetailInteractionsE2eTest {

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
    fun t3_01_f23_f24_f25_f26_nicknameAssignedInDetailSheetUpdatesDiscoveryCardAndMatchesInstantSearch() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ISP_Box_A1", bssid = "04:D4:C4:73:01:01", rssiDbm = -48),
                ScanResultFactory.createScanResult(ssid = "ISP_Box_B2", bssid = "B0:BE:76:73:01:02", rssiDbm = -61),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.openDetailSheetFor("ISP_Box_B2")
            .assignNickname("Nursery Mesh Node")
            .dismiss()

        networks.assertNetworkDisplayed("Nursery Mesh Node")
        networks.enterSearchQuery("Nursery")
        networks.assertNetworkDisplayed("Nursery Mesh Node")
        networks.assertNetworkNotDisplayed("ISP_Box_A1")
    }

    @Test
    fun t3_02_f06_f21_f24_f26_hiddenNullByteBeaconRevealedViaConnectedWifiInfoUpdatesCardAndDetailSheet() {
        val bssid = "04:D4:C4:73:02:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(10) { 0 },
                    bssid = bssid,
                    rssiDbm = -50,
                    frequencyMhz = 5180,
                ),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("Hidden Network")

        // Connect to that hidden BSSID -> Android WifiInfo reveals actual SSID "PrivateLab_5G"
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(
                ssid = "PrivateLab_5G",
                bssid = bssid,
                rssiDbm = -50,
                frequencyMhz = 5180,
                linkSpeedMbps = 1201,
            ),
        )
        networks.assertNetworkDisplayed("PrivateLab_5G")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected spotlight", "Connected", "1201")

        val detail = networks.openDetailSheetFor("PrivateLab_5G")
        detail.assertDetailContains("PrivateLab_5G", bssid)
    }

    @Test
    fun t3_03_f07_f08_f10_f24_wifi7MloClusterWithLaaVirtualMacAndWpsModelGroupsAndExpandsCleanly() {
        val mld = "04:D4:C4:73:03:00"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "AsusMloHome",
                    bssid = "04:D4:C4:73:03:01",
                    rssiDbm = -47,
                    frequencyMhz = 5180,
                    wifiStandard = ScanResult.WIFI_STANDARD_11BE,
                    apMldMac = mld,
                    ies = listOf(BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "AsusMloHome",
                    bssid = "06:D4:C4:73:03:02", // LAA virtual radio on same MLD!
                    rssiDbm = -58,
                    frequencyMhz = 2437,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2437,
                    wifiStandard = ScanResult.WIFI_STANDARD_11BE,
                    apMldMac = mld,
                ),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("AsusMloHome")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "ASUS vendor attribution", "ASUS")

        networks.switchToAllRadiosMode()
        networks.assertNetworkDisplayed("04:D4:C4:73:03:01")
        networks.assertNetworkDisplayed("06:D4:C4:73:03:02")
    }

    @Test
    fun t3_04_f09_f11_f24_f26_rsnWpa3QbssLoadAndBssColorCollisionSurfaceAcrossCardAndDetailSheet() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "ExecFloor_AX",
                    bssid = "04:D4:C4:73:04:01",
                    rssiDbm = -49,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                        BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT.toScanResultIe(),
                        BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe(),
                    ),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "NeighborCollide_AX",
                    bssid = "B0:BE:76:73:04:02",
                    rssiDbm = -56,
                    frequencyMhz = 5180,
                    ies = listOf(
                        BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe(),
                    ),
                ),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("ExecFloor_AX")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "A+ badge on card", "A+", "WPA3")

        val detail = networks.openDetailSheetFor("ExecFloor_AX")
        detail.assertDetailContains("45", "78%", "Collision", "PMF")
    }

    @Test
    fun t3_05_f05_f10_f18_f26_dualBandGalaxyA55Scans5Ghz80MhzRnrBeaconAndDisplaysColocated6GhzPscInDetailSheet() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "TriBandBridge_5G",
                    bssid = "04:D4:C4:73:05:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5210,
                    ies = listOf(BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe()),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("TriBandBridge_5G")
        detail.assertDetailContains("Ch 36", "80 MHz")
        detail.assertDetailContains("6 GHz", "37", "160 MHz")
    }

    @Test
    fun t3_06_f03_f04_f24_f26_plainEnglishTranslationsAndInlineLegendPillsExpandInsideDetailSheetWithoutDismissing() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "LegendSheet_AX",
                    bssid = "04:D4:C4:73:06:01",
                    rssiDbm = -51,
                    ies = listOf(BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT.toScanResultIe()),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("LegendSheet_AX")
        LegendPillRobot.tapLegendPill(composeRule, "bss_load", "BSS Load")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "BSS Load explanation inside sheet", "airtime", "70%")
        LegendPillRobot.dismissLegendCard(composeRule, "bss_load")
        detail.assertDetailContains("LegendSheet_AX", "04:D4:C4:73:06:01")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T3_06-LegendSheet")
    }

    @Test
    fun t3_07_f25_f26_f27_f35_filteredSearchToDetailSheetToProximitySurvivesRotationAndPreservesNetworksFilter() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "TargetStudio_5G", bssid = "04:D4:C4:73:07:01", rssiDbm = -55, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "Distractor_2G", bssid = "B0:BE:76:73:07:02", rssiDbm = -44, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks()
        networks.enterSearchQuery("TargetStudio")
        networks.openDetailSheetFor("TargetStudio_5G").clickMeasureProximity()

        nav.assertTabSelected("Proximity")
        robot.recreateApp()
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "TargetStudio_5G locked after rotate", "TargetStudio_5G")

        nav.openNetworks()
        networks.assertNetworkDisplayed("TargetStudio_5G").assertNetworkNotDisplayed("Distractor_2G")
    }
}
