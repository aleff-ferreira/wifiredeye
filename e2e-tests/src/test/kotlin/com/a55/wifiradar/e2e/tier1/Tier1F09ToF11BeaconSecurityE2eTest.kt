@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.arr
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.obj
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import kotlinx.serialization.json.jsonObject
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F09`–`F11` (`18` `@Test` methods).
 * - `F09` AOSP-Accurate Binary 802.11 Beacon IE Inspector (`6` tests)
 * - `F10` Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r (`6` tests)
 * - `F11` Deep Security Scorer (`A+`–`F`) & Evil-Twin Detector (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F09ToF11BeaconSecurityE2eTest {

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
    // F09: AOSP-Accurate Binary 802.11 Beacon IE Inspector (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f09_t1_01_qbssLoadIe11DecodesStationCountAndChannelUtilizationPercentage() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Auditorium_5G",
                    bssid = "04:d4:c4:09:01:01",
                    ies = listOf(BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val bssLoad = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:01:01")).obj("bssLoad")
        assertNotNull("Expected bssLoad decoded from IE 11", bssLoad)
        assertEquals(45, bssLoad!!.int("stationCount"))
        assertEquals(78, bssLoad.int("channelUtilizationPct"))
    }

    @Test
    fun f09_t1_02_ht45AndVht191CapabilitiesDecodeSpatialStreams160MhzAndMuMimo() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Studio_AC160",
                    bssid = "04:d4:c4:09:02:01",
                    channelWidthMhz = 160,
                    centerFreq0Mhz = 5250,
                    wifiStandard = "11ac",
                    ies = listOf(BeaconIeVectors.HT_CAP_2X2_40MHZ, BeaconIeVectors.VHT_CAP_4X4_160MHZ_MUMIMO),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val phy = CliJsonLens.phyObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:02:01"))
        assertEquals(4, phy.int("maxSpatialStreams"))
        assertEquals(true, phy.bool("supports160Mhz"))
        assertEquals(true, phy.bool("muMimoSupported"))
    }

    @Test
    fun f09_t1_03_aospIdExtStrippedHeCapabilities35AndHeOperation36Decode2x2TwtAndBssColor42() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "WiFi6_Color42",
                    bssid = "04:d4:c4:09:03:01",
                    wifiStandard = "11ax",
                    ies = listOf(BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT, BeaconIeVectors.HE_OP_BSS_COLOR_42),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val radio = CliJsonLens.radioByBssid(root, "04:d4:c4:09:03:01")
        val phy = CliJsonLens.phyObj(radio)
        val bssColor = CliJsonLens.beaconObj(radio).obj("bssColor")
        assertEquals(2, phy.int("maxSpatialStreams"))
        assertEquals(true, phy.bool("twtResponder"))
        assertNotNull("Expected bssColor decoded from id=255,idExt=36", bssColor)
        assertEquals(42, bssColor!!.int("colorCode"))
        assertEquals(false, bssColor.bool("isDisabled"))
    }

    @Test
    fun f09_t1_04_coChannelSameBssColor9RoutersFlagBssColorCollisionInCliAndDetailSheet() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Floor1_AX",
                    bssid = "04:d4:c4:09:04:01",
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 5210,
                    ies = listOf(BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT, BeaconIeVectors.HE_OP_BSS_COLOR_9),
                ),
                ScanEntrySpec(
                    ssid = "Neighbor_AX",
                    bssid = "b0:be:76:09:04:02",
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 5210,
                    ies = listOf(BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT, BeaconIeVectors.HE_OP_BSS_COLOR_9),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val color1 = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:04:01")).obj("bssColor")!!
        assertEquals(9, color1.int("colorCode"))
        assertEquals(true, color1.bool("hasCollisionOnChannel"))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Floor1_AX",
                    bssid = "04:D4:C4:09:04:01",
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(), BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "Neighbor_AX",
                    bssid = "B0:BE:76:09:04:02",
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(), BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe()),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("Floor1_AX")
        detail.assertDetailContains("9", "Collision")
    }

    @Test
    fun f09_t1_05_wifi7EhtCapabilities108AndOperation106Decode320MhzAndPreamblePuncturing() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(
                    ssid = "WiFi7_Punctured",
                    bssid = "04:d4:c4:09:05:01",
                    frequencyMhz = 6115,
                    centerFreq0Mhz = 6105,
                    channelWidthMhz = 320,
                    wifiStandard = "11be",
                    ies = listOf(
                        BeaconIeVectors.EHT_CAP_4X4_320MHZ,
                        BeaconIeVectors.EHT_OP_320MHZ_PUNCTURED_SUB2,
                        BeaconIeVectors.MLO_BASIC_MLD_MAC,
                    ),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val radio = CliJsonLens.radioByBssid(root, "04:d4:c4:09:05:01")
        val phy = CliJsonLens.phyObj(radio)
        val ehtMlo = CliJsonLens.beaconObj(radio).obj("ehtMlo")
        assertEquals(true, phy.bool("ehtSupported"))
        assertEquals(true, phy.bool("supports320Mhz"))
        assertNotNull("Expected ehtMlo decoded from EHT Op / MLO IEs", ehtMlo)
        assertEquals(true, ehtMlo!!.bool("isPreamblePunctured"))
    }

    @Test
    fun f09_t1_06_timCountryTpcMultipleBssidAndBssMembershipSelectorsFilteredCorrectly() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "FullBeacon_DE",
                    bssid = "04:d4:c4:09:06:01",
                    ies = listOf(
                        BeaconIeVectors.RATES_OFDM_WITH_SAE_H2E_ONLY,
                        BeaconIeVectors.EXT_RATES_54M_WITH_HT_VHT_HE_SELECTORS,
                        BeaconIeVectors.TIM_DTIM_3_MULTICAST,
                        BeaconIeVectors.COUNTRY_DE_INDOOR,
                        BeaconIeVectors.TPC_REPORT_14_DBM,
                        BeaconIeVectors.MULTIPLE_BSSID_IND_3,
                    ),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val beacon = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:06:01"))
        assertEquals(3, beacon.obj("timDtim")?.int("dtimPeriod"))
        assertEquals("DE", beacon.obj("country")?.str("countryCode"))
        assertEquals(14, beacon.int("tpcApTxPowerDbm"))
        assertEquals(3, beacon.int("maxBssidIndicator"))
        assertEquals(true, beacon.bool("hasSaeH2eBssMembershipSelector"))
    }

    // =========================================================================
    // F10: Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f10_t1_01_rnrIe201On5GhzBeaconExtractsColocated6GhzPsc37_160MhzRadioOnDualBandA55() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = false, // Retail Galaxy A55 5G dual-band!
            scans = listOf(
                ScanEntrySpec(
                    ssid = "AsusTriBand_5G",
                    bssid = "04:d4:c4:10:01:01",
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 5210,
                    channelWidthMhz = 80,
                    ies = listOf(BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val neighbors = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:01:01"))
            .arr("colocated6GhzNeighbors")
        assertNotNull("Expected colocated6GhzNeighbors from IE 201 RNR", neighbors)
        assertEquals(1, neighbors!!.size)
        val n0 = neighbors[0].jsonObject
        assertEquals(37, n0.int("channelNumber"))
        assertEquals(160, n0.int("widthMhz"))
        assertEquals(true, n0.bool("isPscChannel"))
        assertEquals("04:D4:C4:6E:37:01", n0.str("neighborBssid")?.uppercase())
    }

    @Test
    fun f10_t1_02_rnrOperatingClasses131_133_137MapExact20_80_320MhzWidths() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Rnr20", bssid = "04:d4:c4:10:02:01", ies = listOf(BeaconIeVectors.RNR_6GHZ_PSC5_20MHZ_7B)),
                ScanEntrySpec(ssid = "Rnr80", bssid = "04:d4:c4:10:02:02", ies = listOf(BeaconIeVectors.RNR_6GHZ_PSC37_80MHZ_13B)),
                ScanEntrySpec(ssid = "Rnr320", bssid = "04:d4:c4:10:02:03", ies = listOf(BeaconIeVectors.RNR_6GHZ_PSC69_320MHZ_13B)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val n20 = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:02:01")).arr("colocated6GhzNeighbors")!![0].jsonObject
        val n80 = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:02:02")).arr("colocated6GhzNeighbors")!![0].jsonObject
        val n320 = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:02:03")).arr("colocated6GhzNeighbors")!![0].jsonObject

        assertEquals(20, n20.int("widthMhz"))
        assertEquals(5, n20.int("channelNumber"))
        assertEquals(80, n80.int("widthMhz"))
        assertEquals(37, n80.int("channelNumber"))
        assertEquals(320, n320.int("widthMhz"))
        assertEquals(69, n320.int("channelNumber"))
    }

    @Test
    fun f10_t1_03_wpsBigEndianTlvsExtractExactRouterManufacturerModelAndDeviceName() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "MyHomeAsus",
                    bssid = "04:d4:c4:10:03:01",
                    ies = listOf(BeaconIeVectors.VENDOR_WPS_ASUS_AX86U),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val wps = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:03:01")).obj("wpsDevice")
        assertNotNull("Expected wpsDevice parsed from IE 221 WPS TLVs", wps)
        assertEquals("ASUSTeK", wps!!.str("manufacturer"))
        assertEquals("RT-AX86U Pro", wps.str("modelName"))
        assertEquals("LivingRoomAX", wps.str("deviceName"))
    }

    @Test
    fun f10_t1_04_enterpriseCiscoAndArubaVendorIesExtractAdvertisedApHostnames() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "CorpCisco", bssid = "00:40:96:10:04:01", ies = listOf(BeaconIeVectors.VENDOR_CISCO_CCX_AP_NAME)),
                ScanEntrySpec(ssid = "CorpAruba", bssid = "00:0b:86:10:04:02", ies = listOf(BeaconIeVectors.VENDOR_ARUBA_AP_NAME)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ciscoHost = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "00:40:96:10:04:01"))
            .obj("wpsDevice")?.str("advertisedApHostname").orEmpty()
        val arubaHost = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "00:0b:86:10:04:02"))
            .obj("wpsDevice")?.str("advertisedApHostname").orEmpty()
        assertEquals("HQ-3F-AP12", ciscoHost)
        assertEquals("ARUBA-635-E1", arubaHost)
    }

    @Test
    fun f10_t1_05_fastRoaming80211kvrMboFtmAndBeaconProtectionDecodedCorrectly() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "SeamlessMesh_AX",
                    bssid = "04:d4:c4:10:05:01",
                    ies = listOf(
                        BeaconIeVectors.RM_ENABLED_80211K,
                        BeaconIeVectors.EXT_CAP_11V_UTF8_FTM_TWT_BEACONPROT,
                        BeaconIeVectors.MOBILITY_DOMAIN_80211R,
                        BeaconIeVectors.VENDOR_MBO_OCE,
                    ),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val roam = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:10:05:01")).obj("roaming")!!
        assertEquals(true, roam.bool("supports80211k"))
        assertEquals(true, roam.bool("supports80211v"))
        assertEquals(true, roam.bool("supports80211r"))
        assertEquals(true, roam.bool("supportsMbo"))
        assertEquals(true, roam.bool("ftmResponder"))
        assertEquals(true, roam.bool("beaconProtection"))
    }

    @Test
    fun f10_t1_06_uiSurfacesOutOfBand6GhzRnrAndWpsRouterModelInDetailAndSpectrum() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "HomeTriBand_AXE",
                    bssid = "04:D4:C4:10:06:01",
                    rssiDbm = -49,
                    frequencyMhz = 5180,
                    ies = listOf(
                        BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe(),
                        BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val detail = nav.openNetworks().openDetailSheetFor("HomeTriBand_AXE")
        detail.assertDetailContains("RT-AX86U Pro", "6 GHz", "37")
        detail.dismiss()

        nav.openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "6 GHz RNR note on A55", "6 GHz", "37", "RNR", "Co-located")
    }

    // =========================================================================
    // F11: Deep Security Scorer (A+–F) & Evil-Twin Detector (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f11_t1_01_wpa3SaeWithMfprAndH2eScoresGradeAPlus() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "Fortress_WPA3",
                    bssid = "04:d4:c4:11:01:01",
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR, BeaconIeVectors.RSNX_SAE_H2E),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val sec = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:01:01"))
        assertEquals("A+", CliJsonLens.normalizeSecurityGrade(sec.str("grade")))
        assertEquals("REQUIRED", sec.str("pmfState"))
        assertEquals(true, sec.bool("saeH2eSupported"))
    }

    @Test
    fun f11_t1_02_wpa2Wpa3TransitionalScoresStrongModernGradeAndFlagsTransitionMode() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "HomeTransition",
                    bssid = "04:d4:c4:11:02:01",
                    capabilities = "[WPA2-PSK-CCMP][RSN-PSK+SAE-CCMP][MFPC][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA2_WPA3_TRANSITION_MFPC),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val sec = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:02:01"))
        val grade = CliJsonLens.normalizeSecurityGrade(sec.str("grade"))
        assertTrue("Expected A or A- for WPA2/WPA3 transitional, got $grade", grade == "A-" || grade == "A")
        assertEquals(true, sec.bool("isTransitionMode"))
    }

    @Test
    fun f11_t1_03_oweEnhancedOpenScoresProtectedGradeFarAboveUnencryptedOpen() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "ModernAirport_OWE",
                    bssid = "04:d4:c4:11:03:01",
                    capabilities = "[RSN-OWE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_OWE_ENHANCED_OPEN_MFPR),
                ),
                ScanEntrySpec(
                    ssid = "LegacyCafe_Open",
                    bssid = "b0:be:76:11:03:02",
                    capabilities = "[ESS]",
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val owe = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:03:01"))
        val open = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "b0:be:76:11:03:02"))
        assertTrue("OWE grade must be A/A-/B, not F", CliJsonLens.normalizeSecurityGrade(owe.str("grade")) in setOf("A", "A-", "B"))
        assertEquals("F", CliJsonLens.normalizeSecurityGrade(open.str("grade")))
        assertTrue(owe.int("score0To100")!! > open.int("score0To100")!! + 40)
    }

    @Test
    fun f11_t1_04_wpa2WithWpsEnabledPenalizesGradeAndEmitsWpsWarning() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "CleanWpa2",
                    bssid = "04:d4:c4:11:04:01",
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA2_PSK_CCMP_NO_PMF),
                ),
                ScanEntrySpec(
                    ssid = "WpsExposedWpa2",
                    bssid = "04:d4:c4:11:04:02",
                    capabilities = "[WPA2-PSK-CCMP][WPS][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA2_PSK_CCMP_NO_PMF, BeaconIeVectors.VENDOR_WPS_TPLINK_AX73),
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val clean = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:04:01"))
        val wps = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:04:02"))
        assertEquals(true, wps.bool("wpsEnabled"))
        assertTrue(
            "WPS exposure must reduce security score (${wps.int("score0To100")} vs ${clean.int("score0To100")})",
            wps.int("score0To100")!! < clean.int("score0To100")!!,
        )
    }

    @Test
    fun f11_t1_05_tkipWpa1WepAndOpenNetworksGradedWeakAndInsecure() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "LegacyTKIP", bssid = "04:d4:c4:11:05:01", capabilities = "[WPA-PSK-TKIP][ESS]", ies = listOf(BeaconIeVectors.VENDOR_WPA1_LEGACY_TKIP)),
                ScanEntrySpec(ssid = "BrokenWEP", bssid = "04:d4:c4:11:05:02", capabilities = "[WEP][ESS]"),
                ScanEntrySpec(ssid = "WideOpen", bssid = "04:d4:c4:11:05:03", capabilities = "[ESS]"),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val tkipGrade = CliJsonLens.normalizeSecurityGrade(CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:05:01")).str("grade"))
        val wepGrade = CliJsonLens.normalizeSecurityGrade(CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:05:02")).str("grade"))
        val openGrade = CliJsonLens.normalizeSecurityGrade(CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:05:03")).str("grade"))

        assertTrue("TKIP must be D or F, got $tkipGrade", tkipGrade in setOf("D", "F"))
        assertEquals("F", wepGrade)
        assertEquals("F", openGrade)
    }

    @Test
    fun f11_t1_06_openEvilTwinSpoofingEncryptedCorpSsidTriggersCriticalAlertInCliAndUi() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "CorpVault_WiFi",
                    bssid = "00:40:96:11:06:01",
                    rssiDbm = -58,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR),
                ),
                ScanEntrySpec(
                    ssid = "CorpVault_WiFi",
                    bssid = "de:ad:be:ef:06:99",
                    rssiDbm = -44,
                    capabilities = "[ESS]",
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val rogueSec = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "de:ad:be:ef:06:99"))
        assertEquals(true, rogueSec.bool("isEvilTwinSuspect"))
        val alerts = CliJsonLens.healthObj(root).arr("evilTwinAlerts")
        assertTrue("Expected EvilTwinAlert in healthReport", alerts != null && alerts.isNotEmpty())

        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "CorpVault_WiFi",
                    bssid = "00:40:96:11:06:01",
                    rssiDbm = -58,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "CorpVault_WiFi",
                    bssid = "DE:AD:BE:EF:06:99",
                    rssiDbm = -44,
                    capabilities = "[ESS]",
                ),
            ),
        )
        NavigationRobot(composeRule).openInsights()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Evil-Twin alert on Insights",
            "Evil Twin",
            "Spoof",
            "Unencrypted",
            "CorpVault_WiFi",
        )
    }
}
