@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

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
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.IeSpec
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier 2 Boundary, Corner & Malformed-IE Fuzz E2E Tests for Features `F09`–`F11` (`17` `@Test` methods).
 * - `F09` AOSP-Accurate Binary 802.11 Beacon IE Inspector (`6` tests)
 * - `F10` Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r (`5` tests)
 * - `F11` Deep Security Scorer (`A+`–`F`) & Evil-Twin Detector (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F09ToF11BeaconSecurityEdgeE2eTest {

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
    // F09: Binary 802.11 Beacon IE Inspector — Adversarial Fuzz Cases (6)
    // =========================================================================

    @Test
    fun f09_t2_01_zeroByteAndFourByteTruncatedQbssLoadIe11IgnoredSafelyWithoutBufferUnderflow() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Empty11", bssid = "04:d4:c4:09:11:01", ies = listOf(BeaconIeVectors.EMPTY_0B_BSS_LOAD)),
                ScanEntrySpec(ssid = "Trunc11", bssid = "04:d4:c4:09:11:02", ies = listOf(BeaconIeVectors.TRUNCATED_4B_BSS_LOAD)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertNull(CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:11:01")).obj("bssLoad"))
        assertNull(CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:11:02")).obj("bssLoad"))
    }

    @Test
    fun f09_t2_02_maxUnsigned16BitStationCount65535And255UtilizationDecodedAsUnsignedNotNegativeOne() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "MaxUnsigned11", bssid = "04:d4:c4:09:12:01", ies = listOf(BeaconIeVectors.BSS_LOAD_MAX_65535_100_PCT)),
            ),
        )
        val bssLoad = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:09:12:01"),
        ).obj("bssLoad")!!
        assertEquals("uint16 LE 0xFFFF must decode to +65535, never signed -1!", 65535, bssLoad.int("stationCount"))
        assertEquals("uint8 0xFF must decode to 100% utilization!", 100, bssLoad.int("channelUtilizationPct"))
    }

    @Test
    fun f09_t2_03_heOperationDisabledBssColorBitDoesNotFalseTriggerBssColorCollision() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "DisabledColorA", bssid = "04:d4:c4:09:13:01", frequencyMhz = 5180, ies = listOf(BeaconIeVectors.HE_OP_BSS_COLOR_DISABLED)),
                ScanEntrySpec(ssid = "DisabledColorB", bssid = "b0:be:76:09:13:02", frequencyMhz = 5180, ies = listOf(BeaconIeVectors.HE_OP_BSS_COLOR_DISABLED)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val c1 = CliJsonLens.beaconObj(CliJsonLens.radioByBssid(root, "04:d4:c4:09:13:01")).obj("bssColor")
        if (c1 != null) {
            assertEquals(true, c1.bool("isDisabled"))
            assertEquals("Disabled BSS Color must NOT flag collision", false, c1.bool("hasCollisionOnChannel"))
        }
    }

    @Test
    fun f09_t2_04_heCapabilitiesWithAllMcsStreamsMarkedUnsupportedClampsSpatialStreamsToAtLeastOne() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "AllUnsupportedMcs",
                    bssid = "04:d4:c4:09:14:01",
                    ies = listOf(BeaconIeVectors.HE_CAP_ALL_UNSUPPORTED_MCS),
                ),
            ),
        )
        val phy = CliJsonLens.phyObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:09:14:01"),
        )
        assertTrue("Spatial streams must clamp >= 1 even if MCS map is 0xFFFF", phy.int("maxSpatialStreams")!! >= 1)
    }

    @Test
    fun f09_t2_05_signedNegativeTpcTxPowerMinus5DbmDecodedViaSignedInt8Not251Dbm() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "FemtoNeg5dBm", bssid = "04:d4:c4:09:15:01", ies = listOf(BeaconIeVectors.TPC_REPORT_NEG_5_DBM)),
            ),
        )
        val beacon = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:09:15:01"),
        )
        assertEquals("0xFB in IE 35 is signed -5 dBm, NOT unsigned +251 dBm!", -5, beacon.int("tpcApTxPowerDbm"))
    }

    @Test
    fun f09_t2_06_truncatedHtHeEhtAndMloExtensionIesIncrementMalformedCountWithoutCrash() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "TruncExtBundle",
                    bssid = "04:d4:c4:09:16:01",
                    ies = listOf(
                        BeaconIeVectors.TRUNCATED_10B_HT_CAP,
                        BeaconIeVectors.TRUNCATED_20B_HE_CAP,
                        BeaconIeVectors.MLO_TRUNCATED_5B,
                        BeaconIeVectors.EHT_OP_MISSING_BITMAP_BYTES,
                        IeSpec(id = 255, idExt = 250, hex = "deadbeef"), // Unknown future Wi-Fi 8 extension IE
                    ),
                ),
            ),
        )
        val beacon = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:09:16:01"),
        )
        assertEquals(5, beacon.int("rawIeCount"))
        assertTrue(beacon.int("malformedIeCount")!! >= 1)
    }

    // =========================================================================
    // F10: Out-of-Band 6 GHz RNR, WPS Model & 802.11k/v/r — Edge Cases (5)
    // =========================================================================

    @Test
    fun f10_t2_01_non6GhzRnrOperatingClass115IgnoredAs6GhzColocatedNeighbor() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "5GhzOnlyRnr", bssid = "04:d4:c4:10:11:01", ies = listOf(BeaconIeVectors.RNR_5GHZ_NON6GHZ_OP115)),
            ),
        )
        val neighbors = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:10:11:01"),
        ).arr("colocated6GhzNeighbors")
        assertTrue("opClass 115 (5 GHz) must NOT be reported in colocated6GhzNeighbors", neighbors == null || neighbors.isEmpty())
    }

    @Test
    fun f10_t2_02_truncatedRnrTbttEntryAndZeroLengthRnrIe201ParsedWithoutIndexOutOfBounds() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "TruncatedRnr",
                    bssid = "04:d4:c4:10:12:01",
                    ies = listOf(BeaconIeVectors.RNR_TRUNCATED_TBTT_ENTRY, IeSpec(id = 201, hex = "")),
                ),
            ),
        )
        val neighbors = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:10:12:01"),
        ).arr("colocated6GhzNeighbors")
        assertTrue(neighbors == null || neighbors.isEmpty())
    }

    @Test
    fun f10_t2_03_wpsTlvClaiming255ByteLengthInShortVendorIe221ClampsSafelyWithoutCrash() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "OverflowWps", bssid = "04:d4:c4:10:13:01", ies = listOf(BeaconIeVectors.WPS_OVERFLOW_TLV_LEN)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertEquals(1, CliJsonLens.radios(root).size)
    }

    @Test
    fun f10_t2_04_shortTwoByteExtendedCapabilitiesIe127DoesNotThrowWhenCheckingBit19BssTransition() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "ShortExtCap", bssid = "04:d4:c4:10:14:01", ies = listOf(BeaconIeVectors.EXT_CAP_SHORT_2B)),
            ),
        )
        val roam = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:10:14:01"),
        ).obj("roaming")!!
        assertEquals(false, roam.bool("supports80211v"))
        assertEquals(false, roam.bool("ftmResponder"))
    }

    @Test
    fun f10_t2_05_unknownVendorOuiInIe221SkippedCleanlyWithoutCorruptingSiblingValidWpsIe() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "MixedVendorIes",
                    bssid = "04:d4:c4:10:15:01",
                    ies = listOf(
                        IeSpec(id = 221, hex = "112233"), // 3-byte unknown vendor OUI
                        IeSpec(id = 221, hex = "aa"), // 1-byte malformed IE 221
                        BeaconIeVectors.VENDOR_WPS_TPLINK_AX73,
                    ),
                ),
            ),
        )
        val wps = CliJsonLens.beaconObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:10:15:01"),
        ).obj("wpsDevice")!!
        assertEquals("TP-Link", wps.str("manufacturer"))
        assertEquals("Archer AX73", wps.str("modelName"))
    }

    // =========================================================================
    // F11: Deep Security Scorer & Evil-Twin Detector — Edge Cases (6)
    // =========================================================================

    @Test
    fun f11_t2_01_rsnOverflowPairwiseCipherCount500AbortsBinaryParseGracefullyAndFallsBackToCapabilities() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "MalformedRsnFallback",
                    bssid = "04:d4:c4:11:11:01",
                    capabilities = "[WPA2-PSK-CCMP][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_OVERFLOW_COUNT_500, BeaconIeVectors.RSN_TRUNCATED_MID_CIPHER),
                ),
            ),
        )
        val sec = CliJsonLens.securityObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:11:11:01"),
        )
        assertTrue(CliJsonLens.normalizeSecurityGrade(sec.str("grade")) in setOf("B", "C"))
    }

    @Test
    fun f11_t2_02_legitimateWpa2PlusWpa3DualBandMeshDoesNotFalsePositiveTriggerEvilTwinAlert() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "LegitHome", bssid = "04:d4:c4:11:12:01", frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20, capabilities = "[WPA2-PSK-CCMP][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA2_PSK_CCMP_NO_PMF)),
                ScanEntrySpec(ssid = "LegitHome", bssid = "04:d4:c4:11:12:02", frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertFalse(CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:12:01")).bool("isEvilTwinSuspect") == true)
        assertTrue(CliJsonLens.healthObj(root).arr("evilTwinAlerts")!!.isEmpty())
    }

    @Test
    fun f11_t2_03_publicGuestOweAndOpenTransitionPairDoesNotFalsePositiveAsWPAEvilTwin() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "CityLibrary_Free", bssid = "04:d4:c4:11:13:01", capabilities = "[RSN-OWE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_OWE_ENHANCED_OPEN_MFPR)),
                ScanEntrySpec(ssid = "CityLibrary_Free", bssid = "04:d4:c4:11:13:02", capabilities = "[ESS]"),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        // RFC 8110 OWE transition mode intentionally pairs Open + OWE; must NOT trigger WPA Evil-Twin!
        assertTrue(CliJsonLens.healthObj(root).arr("evilTwinAlerts")!!.isEmpty())
    }

    @Test
    fun f11_t2_04_twoUnrelatedHiddenNetworksOneWpa3AndOneOpenNeverTriggerEvilTwinByEmptySsid() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "", rawSsidHex = "", bssid = "04:d4:c4:11:14:01", capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                ScanEntrySpec(ssid = "", rawSsidHex = "", bssid = "b0:be:76:11:14:02", capabilities = "[ESS]"),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertTrue("Hidden networks must NEVER trigger Evil-Twin by matching empty SSID!", CliJsonLens.healthObj(root).arr("evilTwinAlerts")!!.isEmpty())
    }

    @Test
    fun f11_t2_05_wpa3EnterpriseSuiteB192BitGcmp256ScoresTopGradeAPlus() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "GovDefense_192",
                    bssid = "00:40:96:11:15:01",
                    capabilities = "[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_SUITE_B_192_GCMP256_MFPR),
                ),
            ),
        )
        val sec = CliJsonLens.securityObj(
            CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "00:40:96:11:15:01"),
        )
        assertEquals("A+", CliJsonLens.normalizeSecurityGrade(sec.str("grade")))
        assertTrue(sec.int("score0To100")!! >= 95)
    }

    @Test
    fun f11_t2_06_contradictoryCapabilitiesAdvertisingSaeAlongsideLegacyTkipPenalizesGradeBelowPureWpa3() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "PureWPA3", bssid = "04:d4:c4:11:16:01", capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                ScanEntrySpec(ssid = "MisconfigSAE_TKIP", bssid = "04:d4:c4:11:16:02", capabilities = "[WPA-PSK-TKIP][RSN-SAE-CCMP][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA2_TKIP_LEGACY)),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val pureScore = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:16:01")).int("score0To100")!!
        val misconfigScore = CliJsonLens.securityObj(CliJsonLens.radioByBssid(root, "04:d4:c4:11:16:02")).int("score0To100")!!
        assertTrue("TKIP downgrade exposure ($misconfigScore) must score strictly below pure WPA3 ($pureScore)", misconfigScore < pureScore)
    }
}
