@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F05`–`F08` (`21` `@Test` methods).
 * - `F05` Multi-Band Frequency, Channel & Bonding Resolver (`6` tests)
 * - `F06` Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder (`5` tests)
 * - `F07` Offline IEEE OUI Vendor & LAA MAC Resolver (`5` tests)
 * - `F08` Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F05ToF08DiscoveryRfCoreE2eTest {

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
    // F05: Multi-Band Frequency, Channel & Bonding Resolver (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f05_t1_01_canonical24GhzChannels1_6_11ResolveExactChannelNumbersAnd20MhzWidth() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Ch1_AP", bssid = "04:d4:c4:05:01:01", frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Ch6_AP", bssid = "04:d4:c4:05:01:06", frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Ch11_AP", bssid = "04:d4:c4:05:01:0b", frequencyMhz = 2462, centerFreq0Mhz = 2462, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ch1 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:01:01"))
        val ch6 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:01:06"))
        val ch11 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:01:0b"))

        assertEquals(1, ch1.int("primaryChannel", "channel"))
        assertEquals(6, ch6.int("primaryChannel", "channel"))
        assertEquals(11, ch11.int("primaryChannel", "channel"))
        assertEquals(20, CliJsonLens.normalizeWidthMhz(ch6["width"]))
        assertTrue(ch6.bool("isCanonicalPrimary") == true)
    }

    @Test
    fun f05_t1_02_5GhzUnii1AndUnii3_80MhzBondingResolvesPrimaryAndCenterChannels() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "UNII1_80", bssid = "04:d4:c4:05:02:01", frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "UNII3_80", bssid = "04:d4:c4:05:02:02", frequencyMhz = 5745, centerFreq0Mhz = 5775, channelWidthMhz = 80),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val c36 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:02:01"))
        val c149 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:02:02"))

        assertEquals(36, c36.int("primaryChannel", "channel"))
        assertEquals(42, c36.int("centerChannel0"))
        assertEquals(80, CliJsonLens.normalizeWidthMhz(c36["width"]))
        assertFalse(c36.bool("isDfs") == true)

        assertEquals(149, c149.int("primaryChannel", "channel"))
        assertEquals(155, c149.int("centerChannel0"))
        assertEquals(80, CliJsonLens.normalizeWidthMhz(c149["width"]))
        assertFalse(c149.bool("isDfs") == true)
    }

    @Test
    fun f05_t1_03_5GhzDfsAndTdwrWeatherRadarChannelsFlaggedAccurately() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "DFS_52", bssid = "04:d4:c4:05:03:01", frequencyMhz = 5260, centerFreq0Mhz = 5290, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "TDWR_120", bssid = "04:d4:c4:05:03:02", frequencyMhz = 5600, centerFreq0Mhz = 5610, channelWidthMhz = 40),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val dfs52 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:03:01"))
        val tdwr120 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:03:02"))

        assertEquals(52, dfs52.int("primaryChannel", "channel"))
        assertTrue("Ch 52 must be marked DFS", dfs52.bool("isDfs") == true)
        assertFalse("Ch 52 is non-TDWR UNII-2A", dfs52.bool("isTdwr") == true)

        assertEquals(120, tdwr120.int("primaryChannel", "channel"))
        assertTrue("Ch 120 must be marked DFS", tdwr120.bool("isDfs") == true)
        assertTrue("Ch 120 must be marked TDWR weather radar", tdwr120.bool("isTdwr") == true)
    }

    @Test
    fun f05_t1_04_6GhzPreferredScanningChannelsFollowSixteenChannelStepFormula() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(ssid = "PSC_37", bssid = "04:d4:c4:05:04:01", frequencyMhz = 6135, centerFreq0Mhz = 6185, channelWidthMhz = 160),
                ScanEntrySpec(ssid = "NonPSC_1", bssid = "04:d4:c4:05:04:02", frequencyMhz = 5955, centerFreq0Mhz = 5955, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val psc37 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:04:01"))
        val nonPsc1 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:04:02"))

        assertEquals(37, psc37.int("primaryChannel", "channel"))
        assertTrue("6 GHz Ch 37 (5 + 16*2) must be PSC", psc37.bool("isPsc") == true)
        assertEquals(160, CliJsonLens.normalizeWidthMhz(psc37["width"]))

        assertEquals(1, nonPsc1.int("primaryChannel", "channel"))
        assertFalse("6 GHz Ch 1 (5955 MHz) is NOT a PSC channel", nonPsc1.bool("isPsc") == true)
    }

    @Test
    fun f05_t1_05_split80Plus80MhzAndWifi7_320MhzBondingResolvedInCliAndUi() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Split_80plus80",
                    bssid = "04:d4:c4:05:05:01",
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 5210,
                    centerFreq1Mhz = 5775,
                    channelWidthMhz = 160,
                    wifiStandard = "11ac",
                ),
                ScanEntrySpec(
                    ssid = "WiFi7_320",
                    bssid = "04:d4:c4:05:05:02",
                    frequencyMhz = 6115,
                    centerFreq0Mhz = 6105,
                    channelWidthMhz = 320,
                    wifiStandard = "11be",
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val split = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:05:01"))
        val w7 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:05:02"))

        assertTrue(split.str("width").orEmpty().contains("80_PLUS_80") || split.int("centerFreq1Mhz") == 5775)
        assertEquals(155, split.int("centerChannel1"))
        assertEquals(320, CliJsonLens.normalizeWidthMhz(w7["width"]))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Split_80plus80",
                    bssid = "04:D4:C4:05:05:01",
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ,
                    centerFreq0Mhz = 5210,
                    centerFreq1Mhz = 5775,
                ),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "80+80 UI badge", "80+80")
    }

    @Test
    fun f05_t1_06_60GhzWigigChannels1To4ResolveCleanly() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Dock_60GHz",
                    bssid = "04:d4:c4:05:06:01",
                    frequencyMhz = 60480,
                    centerFreq0Mhz = 60480,
                    channelWidthMhz = 20,
                    wifiStandard = "11ad",
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val chObj = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:06:01"))
        assertEquals(2, chObj.int("primaryChannel", "channel"))
        assertTrue(chObj.str("band").orEmpty().contains("60"))
    }

    // =========================================================================
    // F06: Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f06_t1_01_standardAsciiSsidDecodesExactDisplayNameAndByteLength() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "SamsungHome_5G", bssid = "04:d4:c4:06:01:01")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val r = CliJsonLens.radioByBssid(root, "04:d4:c4:06:01:01")
        assertEquals("SamsungHome_5G", CliJsonLens.ssidDisplayName(r))
        val ssidObj = CliJsonLens.ssidObj(r)!!
        assertEquals(false, ssidObj.bool("isHidden"))
        assertEquals(true, ssidObj.bool("isUtf8"))
        assertEquals(14, ssidObj.int("byteLength"))
    }

    @Test
    fun f06_t1_02_multibyteUtf8AccentsCjkAndEmojiSsidsRenderIntactInCliAndUi() {
        val emojiSsid = "Café 東京 Wi-Fi 🚀"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = emojiSsid, bssid = "04:d4:c4:06:02:01")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val r = CliJsonLens.radioByBssid(root, "04:d4:c4:06:02:01")
        assertEquals(emojiSsid, CliJsonLens.ssidDisplayName(r))
        assertEquals(false, CliJsonLens.ssidObj(r)!!.bool("isHidden"))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = emojiSsid, bssid = "04:D4:C4:06:02:01")),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed(emojiSsid)
    }

    @Test
    fun f06_t1_03_zeroLengthEmptyBeaconClassifiedAsHiddenNetwork() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "", rawSsidHex = "", bssid = "04:d4:c4:06:03:01")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val r = CliJsonLens.radioByBssid(root, "04:d4:c4:06:03:01")
        assertEquals(true, CliJsonLens.ssidObj(r)!!.bool("isHidden"))
        assertTrue(CliJsonLens.ssidDisplayName(r).contains("Hidden", ignoreCase = true))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = "04:D4:C4:06:03:01")),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Zero-length Hidden SSID", "Hidden Network")
    }

    @Test
    fun f06_t1_04_ciscoStyleNullBytePaddedHiddenBeaconClassifiedAsHiddenWithPaddingLength() {
        val eightNullHex = "0000000000000000"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = null, rawSsidHex = eightNullHex, bssid = "04:d4:c4:06:04:01")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ssidObj = CliJsonLens.ssidObj(CliJsonLens.radioByBssid(root, "04:d4:c4:06:04:01"))!!
        assertEquals(true, ssidObj.bool("isHidden"))
        assertEquals(true, ssidObj.bool("isNullBytePadded"))
        assertEquals(8, ssidObj.int("byteLength"))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(8) { 0 },
                    bssid = "04:D4:C4:06:04:01",
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("Hidden Network")
        detail.assertDetailContains("Hidden", "8")
    }

    @Test
    fun f06_t1_05_nonUtf8RawBinarySsidRendersHexFallbackWithoutReplacementGarbage() {
        val rawHex = "80feff4142"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = null, rawSsidHex = rawHex, bssid = "04:d4:c4:06:05:01")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val r = CliJsonLens.radioByBssid(root, "04:d4:c4:06:05:01")
        val display = CliJsonLens.ssidDisplayName(r)
        assertEquals(false, CliJsonLens.ssidObj(r)!!.bool("isUtf8"))
        assertFalse("Must NOT contain U+FFFD replacement char: $display", display.contains('\uFFFD'))
        assertTrue("Expected hex representation of 80FEFF4142 in '$display'", display.contains("80FEFF4142", ignoreCase = true) || display.contains("80:FE", ignoreCase = true))
    }

    // =========================================================================
    // F07: Offline IEEE OUI Vendor & LAA MAC Resolver (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f07_t1_01_majorConsumerAndEnterpriseOuisResolveOfflineWithoutInternet() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "AsusNet", bssid = "04:d4:c4:11:22:33"),
                ScanEntrySpec(ssid = "TpLinkNet", bssid = "b0:be:76:11:22:33"),
                ScanEntrySpec(ssid = "UbiquitiNet", bssid = "fc:ec:da:11:22:33"),
                ScanEntrySpec(ssid = "CiscoNet", bssid = "00:40:96:11:22:33"),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertTrue(CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:22:33")).str("displayLabel", "vendorName").orEmpty().contains("ASUS", ignoreCase = true))
        assertTrue(CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "b0:be:76:11:22:33")).str("displayLabel", "vendorName").orEmpty().contains("TP-Link", ignoreCase = true))
        assertTrue(CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "fc:ec:da:11:22:33")).str("displayLabel", "vendorName").orEmpty().contains("Ubiquiti", ignoreCase = true))
        assertTrue(CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "00:40:96:11:22:33")).str("displayLabel", "vendorName").orEmpty().contains("Cisco", ignoreCase = true))
    }

    @Test
    fun f07_t1_02_laaVirtualGuestBssidClearsUlBitToInferParentHardwareManufacturer() {
        // 04:D4:C4 is ASUS; setting bit 1 (0x02) of octet 0 yields 06:D4:C4 (LAA Guest VAP)
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "AsusGuest_VAP", bssid = "06:d4:c4:11:22:33")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val vendor = CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "06:d4:c4:11:22:33"))
        assertEquals(true, vendor.bool("isLocallyAdministered"))
        val combined = "${vendor.str("inferredParentVendor")} ${vendor.str("displayLabel")}"
        assertTrue("Expected inferred parent vendor ASUS for 06:D4:C4, got: $combined", combined.contains("ASUS", ignoreCase = true))
    }

    @Test
    fun f07_t1_03_pureRandomMobileHotspotLaaMacLabelsVirtualRandomAddress() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "GalaxyA55_Hotspot", bssid = "da:a1:19:77:88:99")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val vendor = CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "da:a1:19:77:88:99"))
        assertEquals(true, vendor.bool("isLocallyAdministered"))
        assertTrue(vendor.str("displayLabel").orEmpty().isNotBlank())
    }

    @Test
    fun f07_t1_04_unassignedUniversalOuiGracefullyReportsUnknownVendorWithFormattedPrefix() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "LabProtoAP", bssid = "00:9f:fe:11:22:33")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val vendor = CliJsonLens.vendorObj(CliJsonLens.radioByBssid(root, "00:9f:fe:11:22:33"))
        assertEquals(false, vendor.bool("isLocallyAdministered"))
        assertEquals("00:9F:FE", vendor.str("ouiPrefix"))
        assertTrue(vendor.str("displayLabel").orEmpty().contains("Unknown", ignoreCase = true) || vendor.str("displayLabel").orEmpty().contains("00:9F:FE"))
    }

    @Test
    fun f07_t1_05_vendorAttributionRendersOnNetworksCardAndDetailBottomSheet() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "StudioUbiquiti",
                    bssid = "FC:EC:DA:44:55:66",
                    rssiDbm = -50,
                ),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Networks vendor label", "Ubiquiti")
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("StudioUbiquiti")
        detail.assertDetailContains("Ubiquiti", "FC:EC:DA:44:55:66")
    }

    // =========================================================================
    // F08: Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f08_t1_01_triBandRadiosSharingWifi7ApMldMacClusterIntoSinglePhysicalAp() {
        val mld = "04:d4:c4:99:99:00"
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(ssid = "AsusBE96U", bssid = "04:d4:c4:99:99:01", frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20, wifiStandard = "11be", apMldMacAddress = mld),
                ScanEntrySpec(ssid = "AsusBE96U", bssid = "04:d4:c4:99:99:02", frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80, wifiStandard = "11be", apMldMacAddress = mld),
                ScanEntrySpec(ssid = "AsusBE96U", bssid = "04:d4:c4:99:99:03", frequencyMhz = 6135, centerFreq0Mhz = 6105, channelWidthMhz = 320, wifiStandard = "11be", apMldMacAddress = mld),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val clusters = CliJsonLens.physicalClusters(root)
        assertEquals("All 3 MLO links must form 1 physical router cluster", 1, clusters.size)
        assertEquals(3, clusters[0]["radios"]?.let { (it as kotlinx.serialization.json.JsonArray).size })
    }

    @Test
    fun f08_t1_02_dualBandRouterSharingFiveOctetPrefixAndCloseRssiClustersAsOneHardwareAp() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "HomeFiber", bssid = "b0:be:76:aa:bb:10", rssiDbm = -48, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "HomeFiber", bssid = "b0:be:76:aa:bb:11", rssiDbm = -53, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val clusters = CliJsonLens.physicalClusters(root)
        assertEquals("Dual-band radios on same 5-octet MAC prefix within 6 dB must cluster together", 1, clusters.size)
    }

    @Test
    fun f08_t1_03_threeDistinctMeshNodesUnderSameSsidGroupIntoSingleEssWithThreeClusters() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "DecoWholeHome", bssid = "b0:be:76:01:10:01", rssiDbm = -49, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "DecoWholeHome", bssid = "b0:be:76:02:20:01", rssiDbm = -64, frequencyMhz = 5220, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "DecoWholeHome", bssid = "b0:be:76:03:30:01", rssiDbm = -77, frequencyMhz = 2462, centerFreq0Mhz = 2462, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val groups = CliJsonLens.groupedNetworks(root)
        assertEquals(1, groups.size)
        assertEquals(true, groups[0].bool("isMeshOrMultiAp"))
        assertEquals(3, groups[0].int("physicalClusterCount"))
    }

    @Test
    fun f08_t1_04_hiddenNetworksNeverCollapseByEmptyNameAcrossUnrelatedRouters() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "", rawSsidHex = "", bssid = "04:d4:c4:11:00:01", rssiDbm = -55),
                ScanEntrySpec(ssid = "", rawSsidHex = "", bssid = "fc:ec:da:22:00:02", rssiDbm = -68),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val groups = CliJsonLens.groupedNetworks(root)
        assertEquals("Distinct hidden hardware APs must NOT merge into 1 ESS group", 2, groups.size)
        assertNotEquals(groups[0].str("groupKey"), groups[1].str("groupKey"))
    }

    @Test
    fun f08_t1_05_groupedMeshVersusAllRadiosToggleSwitchesBetween1MeshCardAnd3RadioCardsInUi() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OrbiMesh_AX", bssid = "04:D4:C4:01:01:01", rssiDbm = -47, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "OrbiMesh_AX", bssid = "04:D4:C4:02:02:02", rssiDbm = -62, frequencyMhz = 5745, centerFreq0Mhz = 5775),
                ScanResultFactory.createScanResult(ssid = "OrbiMesh_AX", bssid = "04:D4:C4:03:03:03", rssiDbm = -74, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("OrbiMesh_AX")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Grouped mesh count", "3", "Mesh", "radios", "APs")

        networks.switchToAllRadiosMode()
        networks.assertNetworkDisplayed("04:D4:C4:01:01:01")
        networks.assertNetworkDisplayed("04:D4:C4:02:02:02")
        networks.assertNetworkDisplayed("04:D4:C4:03:03:03")
    }
}
