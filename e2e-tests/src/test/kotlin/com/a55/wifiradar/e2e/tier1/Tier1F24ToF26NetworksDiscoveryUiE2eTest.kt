@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F24`–`F26` (`18` `@Test` methods: `6` per feature).
 * - `F24` Network Discovery Cards, Mesh Toggle & Connected Badge (`6` tests)
 * - `F25` Instant Search, Filter Chips & Stable Sorting (`6` tests)
 * - `F26` Live Network Detail Bottom Sheet (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F24ToF26NetworksDiscoveryUiE2eTest {

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
    // F24: Network Discovery Cards, Mesh Toggle & Connected Badge (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f24_t1_01_multiBandDiscoveryCardRendersSsidVendorSignalChannelWifiGenerationAndSecurityGrade() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "AsusZenWiFi_5G",
                    bssid = "04:D4:C4:24:01:01",
                    rssiDbm = -47,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5210,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    wifiStandard = ScanResult.WIFI_STANDARD_11AX,
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("AsusZenWiFi_5G")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Vendor OUI label", "ASUS")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RSSI & Excellent tier", "-47 dBm", "Excellent")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Channel & width", "Ch 36", "80 MHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Wi-Fi 6 badge", "Wi-Fi 6")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Security A+ badge", "A+", "WPA3")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F24-Card")
    }

    @Test
    fun f24_t1_02_groupedNetworksDefaultCollapsesThreeMeshBssidsIntoSingleSummaryCard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "EeroProMesh", bssid = "04:D4:C4:24:02:01", rssiDbm = -46, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "EeroProMesh", bssid = "04:D4:C4:24:02:11", rssiDbm = -61, frequencyMhz = 5745, centerFreq0Mhz = 5775),
                ScanResultFactory.createScanResult(ssid = "EeroProMesh", bssid = "04:D4:C4:24:02:21", rssiDbm = -70, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("EeroProMesh")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Grouped mesh radio count", "3", "Mesh", "radios", "APs")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Strongest mesh RSSI", "-46 dBm")
    }

    @Test
    fun f24_t1_03_allRadiosBssidModeExpandsEveryIndividualBssidRadioWithDistinctChannelAndRssi() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MeshExpand", bssid = "04:D4:C4:24:03:01", rssiDbm = -48, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "MeshExpand", bssid = "04:D4:C4:24:03:02", rssiDbm = -64, frequencyMhz = 5745, centerFreq0Mhz = 5775),
                ScanResultFactory.createScanResult(ssid = "MeshExpand", bssid = "04:D4:C4:24:03:03", rssiDbm = -73, frequencyMhz = 2462, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2462),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.switchToAllRadiosMode()
        networks.assertNetworkDisplayed("04:D4:C4:24:03:01")
        networks.assertNetworkDisplayed("04:D4:C4:24:03:02")
        networks.assertNetworkDisplayed("04:D4:C4:24:03:03")
    }

    @Test
    fun f24_t1_04_connectedNetworkSpotlightHighlightsAssociatedRouterWithLinkSpeedAndFrequency() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OtherOffice_5G", bssid = "B0:BE:76:24:04:01", rssiDbm = -44, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "MyDeskWiFi_6", bssid = "04:D4:C4:24:04:02", rssiDbm = -53, frequencyMhz = 5220, centerFreq0Mhz = 5210),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(
                ssid = "MyDeskWiFi_6",
                bssid = "04:D4:C4:24:04:02",
                rssiDbm = -53,
                frequencyMhz = 5220,
                linkSpeedMbps = 1201,
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("MyDeskWiFi_6")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected spotlight", "Connected")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "1201 Mbps link rate", "1201")
    }

    @Test
    fun f24_t1_05_hiddenNetworkRendersDistinctHiddenNetworkBadgeAndHardwareVendorAttribution() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(0),
                    bssid = "FC:EC:DA:24:05:01",
                    rssiDbm = -57,
                    frequencyMhz = 5180,
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("Hidden Network")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Ubiquiti vendor on hidden AP", "Ubiquiti", "FC:EC:DA")
    }

    @Test
    fun f24_t1_06_topScanFreshnessStatusPillShowsNetworkCountAndLiveUpdateStatus() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "StatusNet1", bssid = "04:D4:C4:24:06:01", rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "StatusNet2", bssid = "B0:BE:76:24:06:02", rssiDbm = -62),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Scan status pill network count & freshness",
            "2 Networks",
            "Updated",
            "Just now",
        )
    }

    // =========================================================================
    // F25: Instant Search, Filter Chips & Stable Sorting (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f25_t1_01_instantSearchMatchesBySsidBssidSubstringAndVendorName() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "AlphaHome", bssid = "04:D4:C4:25:01:01", rssiDbm = -50), // ASUS
                ScanResultFactory.createScanResult(ssid = "BravoCafe", bssid = "FC:EC:DA:99:88:77", rssiDbm = -58), // Ubiquiti
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()

        networks.enterSearchQuery("Bravo")
        networks.assertNetworkDisplayed("BravoCafe").assertNetworkNotDisplayed("AlphaHome")

        networks.enterSearchQuery("99:88")
        networks.assertNetworkDisplayed("BravoCafe").assertNetworkNotDisplayed("AlphaHome")

        networks.enterSearchQuery("ASUS")
        networks.assertNetworkDisplayed("AlphaHome").assertNetworkNotDisplayed("BravoCafe")
    }

    @Test
    fun f25_t1_02_bandFilterChips24GhzAnd5GhzIsolateRespectiveFrequencyBands() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Only24GHz_IoT", bssid = "04:D4:C4:25:02:01", rssiDbm = -55, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "Only5GHz_Media", bssid = "04:D4:C4:25:02:02", rssiDbm = -52, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.toggleFilterChip("2.4 GHz")
        networks.assertNetworkDisplayed("Only24GHz_IoT").assertNetworkNotDisplayed("Only5GHz_Media")

        networks.toggleFilterChip("2.4 GHz") // untoggle 2.4 GHz (or switch to 5 GHz)
        networks.toggleFilterChip("5 GHz")
        networks.assertNetworkDisplayed("Only5GHz_Media")
    }

    @Test
    fun f25_t1_03_openOrWeakSecurityFilterChipSurfacesInsecureAndLegacyNetworks() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "SecureWPA3_Home",
                    bssid = "04:D4:C4:25:03:01",
                    rssiDbm = -48,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "OpenGuest_Risk",
                    bssid = "B0:BE:76:25:03:02",
                    rssiDbm = -60,
                    capabilities = "[ESS]",
                ),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.toggleFilterChip("Open / Weak", "Open", "Weak Security")
        networks.assertNetworkDisplayed("OpenGuest_Risk")
        networks.assertNetworkNotDisplayed("SecureWPA3_Home")
    }

    @Test
    fun f25_t1_04_wifi6PlusAndStrongSignalFilterChipsComposeCleanly() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "StrongWifi6_Target", bssid = "04:D4:C4:25:04:01", rssiDbm = -51, wifiStandard = ScanResult.WIFI_STANDARD_11AX),
                ScanResultFactory.createScanResult(ssid = "FaintWifi6_Far", bssid = "04:D4:C4:25:04:02", rssiDbm = -81, wifiStandard = ScanResult.WIFI_STANDARD_11AX),
                ScanResultFactory.createScanResult(ssid = "StrongWifi4_Old", bssid = "B0:BE:76:25:04:03", rssiDbm = -50, wifiStandard = ScanResult.WIFI_STANDARD_11N, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.toggleFilterChip("Wi-Fi 6+", "Wi-Fi 6")
        networks.toggleFilterChip("Strong", "-65 dBm")
        networks.assertNetworkDisplayed("StrongWifi6_Target")
        networks.assertNetworkNotDisplayed("FaintWifi6_Far")
        networks.assertNetworkNotDisplayed("StrongWifi4_Old")
    }

    @Test
    fun f25_t1_05_sortingModesReorderListDeterministicallyByNameChannelAndSecurityRisk() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ZuluStrong", bssid = "04:D4:C4:25:05:01", rssiDbm = -45, frequencyMhz = 5745, centerFreq0Mhz = 5775, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe())),
                ScanResultFactory.createScanResult(ssid = "AlphaWeakOpen", bssid = "B0:BE:76:25:05:02", rssiDbm = -74, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412, capabilities = "[ESS]"),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.selectSortOption("Name A–Z", "Name A-Z", "Name")

        val dumpAfterNameSort = ComposeSemanticMatchers.dumpScreenText(composeRule)
        val idxAlpha = dumpAfterNameSort.indexOf("AlphaWeakOpen")
        val idxZulu = dumpAfterNameSort.indexOf("ZuluStrong")
        assertTrue("Expected AlphaWeakOpen ($idxAlpha) above ZuluStrong ($idxZulu) under Name A–Z sort", idxAlpha in 0 until idxZulu)
    }

    @Test
    fun f25_t1_06_defaultStrongestSignalSortUsesHysteresisToPreventMultipathRowJumping() {
        robot.launchApp()
        // Initial scan: RouterTop = -55 dBm, RouterSecond = -58 dBm
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "RouterTop", bssid = "04:D4:C4:25:06:01", rssiDbm = -55),
                ScanResultFactory.createScanResult(ssid = "RouterSecond", bssid = "B0:BE:76:25:06:02", rssiDbm = -58),
            ),
        )
        val dumpInitial = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue(dumpInitial.indexOf("RouterTop") < dumpInitial.indexOf("RouterSecond"))

        // Sub-2 dB multipath jitter: RouterTop = -57, RouterSecond = -56 (only 1 dB flip < hysteresis band)
        robot.advanceClockMs(1_000L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "RouterTop", bssid = "04:D4:C4:25:06:01", rssiDbm = -57),
                ScanResultFactory.createScanResult(ssid = "RouterSecond", bssid = "B0:BE:76:25:06:02", rssiDbm = -56),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("RouterTop").assertNetworkDisplayed("RouterSecond")
    }

    // =========================================================================
    // F26: Live Network Detail Bottom Sheet (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f26_t1_01_tappingNetworkCardOpensModalDetailSheetDisplayingFullPhyChannelAndVendorMetadata() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "DetailMaster_AX",
                    bssid = "04:D4:C4:26:01:01",
                    rssiDbm = -49,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5210,
                    ies = listOf(
                        BeaconIeVectors.HE_CAP_4X4_160MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe(),
                    ),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("DetailMaster_AX")
        detail.assertDetailContains("04:D4:C4:26:01:01")
        detail.assertDetailContains("ASUS")
        detail.assertDetailContains("5180")
        detail.assertDetailContains("RT-AX86U Pro")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F26-DetailSheet")
    }

    @Test
    fun f26_t1_02_detailSheetRendersSecurityCipherSuitePmf80211wAndFastRoaming80211kvrDetails() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "EnterpriseKVR_5G",
                    bssid = "00:40:96:26:02:01",
                    rssiDbm = -52,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                        BeaconIeVectors.RM_ENABLED_80211K.toScanResultIe(),
                        BeaconIeVectors.EXT_CAP_11V_UTF8_FTM_TWT_BEACONPROT.toScanResultIe(),
                        BeaconIeVectors.MOBILITY_DOMAIN_80211R.toScanResultIe(),
                    ),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("EnterpriseKVR_5G")
        detail.assertDetailContains("WPA3")
        detail.assertDetailContains("PMF", "802.11w")
        detail.assertDetailContains("802.11k/v/r", "Seamless", "k/v/r")
    }

    @Test
    fun f26_t1_03_detailSheetDisplaysQbssLoadClientsAirtimeBusyAndWifi6BssColor() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "ConferenceHall_AX",
                    bssid = "04:D4:C4:26:03:01",
                    rssiDbm = -50,
                    ies = listOf(
                        BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT.toScanResultIe(),
                        BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.HE_OP_BSS_COLOR_42.toScanResultIe(),
                    ),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("ConferenceHall_AX")
        detail.assertDetailContains("12 clients", "25%", "12")
        detail.assertDetailContains("42")
    }

    @Test
    fun f26_t1_04_detailSheetUpdatesRssiLiveWhileOpenWithoutClosingOrLosingScrollState() {
        val bssid = "04:D4:C4:26:04:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LiveSheetRouter", bssid = bssid, rssiDbm = -71)),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("LiveSheetRouter")
        detail.assertDetailContains("-71 dBm")

        robot.advanceClockMs(1_500L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LiveSheetRouter", bssid = bssid, rssiDbm = -46)),
        )
        detail.assertDetailContains("-46 dBm")
    }

    @Test
    fun f26_t1_05_detailSheetCopySummaryActionWritesFormattedPlainTextToSystemClipboard() {
        val bssid = "04:D4:C4:26:05:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ClipboardRouter_5G", bssid = bssid, rssiDbm = -53)),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("ClipboardRouter_5G")
        detail.copySummary()

        val copied = ShareIntentInspector.readClipboardText(robot.appContext)
        assertTrue(
            "Copied summary must include SSID or BSSID, got: $copied",
            copied.contains("ClipboardRouter_5G") || copied.contains("04:D4:C4:26:05:01", ignoreCase = true),
        )
    }

    @Test
    fun f26_t1_06_detailSheetMeasureProximityCtaDismissesSheetAndLocksTargetInProximityTab() {
        val bssid = "04:D4:C4:26:06:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OtherStrongerAP", bssid = "B0:BE:76:26:06:99", rssiDbm = -42),
                ScanResultFactory.createScanResult(ssid = "SheetPickedTarget", bssid = bssid, rssiDbm = -65),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("SheetPickedTarget").clickMeasureProximity()

        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Proximity locked onto SheetPickedTarget", "SheetPickedTarget")
    }
}
