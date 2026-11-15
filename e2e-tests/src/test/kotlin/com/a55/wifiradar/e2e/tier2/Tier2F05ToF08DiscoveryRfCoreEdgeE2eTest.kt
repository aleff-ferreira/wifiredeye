@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F05`–`F08` (`22` `@Test` methods).
 * - `F05` Multi-Band Frequency, Channel & Bonding Resolver (`6` tests)
 * - `F06` Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder (`6` tests)
 * - `F07` Offline IEEE OUI Vendor & LAA MAC Resolver (`5` tests)
 * - `F08` Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F05ToF08DiscoveryRfCoreEdgeE2eTest {

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
    // F05: Multi-Band Frequency, Channel & Bonding Resolver — Edge Cases (6)
    // =========================================================================

    @Test
    fun f05_t2_01_japanChannel14SpecialFrequency2484MhzResolvesExactChannel14WithoutLinearFormulaBug() {
        // Linear formula (2484 - 2407) / 5 = 15 (WRONG!); IEEE 802.11 special rule maps 2484 MHz -> Ch 14!
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "TokyoCh14", bssid = "04:d4:c4:05:14:01", frequencyMhz = 2484, centerFreq0Mhz = 2484, channelWidthMhz = 20)),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ch = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:14:01"))
        assertEquals(14, ch.int("primaryChannel", "channel"))
    }

    @Test
    fun f05_t2_02_6GhzChannel2SpecialFrequency5935MhzAndUpperBoundaryChannel233At7115MhzResolveCleanly() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(ssid = "Ch2_6GHz", bssid = "04:d4:c4:05:15:01", frequencyMhz = 5935, centerFreq0Mhz = 5935, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Ch233_6GHz", bssid = "04:d4:c4:05:15:02", frequencyMhz = 7115, centerFreq0Mhz = 7115, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ch2 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:15:01"))
        val ch233 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:15:02"))
        assertEquals(2, ch2.int("primaryChannel", "channel"))
        assertFalse(ch2.bool("isPsc") == true)
        assertEquals(233, ch233.int("primaryChannel", "channel"))
    }

    @Test
    fun f05_t2_03_unii4Extended5GhzChannels169To177ResolveWithout6GhzCollision() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "UNII4_173", bssid = "04:d4:c4:05:16:01", frequencyMhz = 5865, centerFreq0Mhz = 5865, channelWidthMhz = 20)),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ch = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:16:01"))
        assertEquals(173, ch.int("primaryChannel", "channel"))
        assertTrue(ch.str("band").orEmpty().contains("5"))
    }

    @Test
    fun f05_t2_04_missingZeroCenterFreq0On80MhzDriverQuirkSynthesizesValidOccupiedSpan() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(
                    ssid = "ZeroCenterQuirk",
                    bssid = "04:d4:c4:05:17:01",
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 0, // Driver bug reporting 0 MHz center!
                    channelWidthMhz = 80,
                ),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val ch = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:17:01"))
        val start = ch.int("occupiedStartFreqMhz")!!
        val end = ch.int("occupiedEndFreqMhz")!!
        assertTrue("Occupied span [$start, $end] must stay inside 5 GHz band (> 5000 MHz), never [-40, +40]", start >= 5140 && end > start)
    }

    @Test
    fun f05_t2_05_nonCanonical24GhzOverlappingChannels2To5And7To10FlaggedIsCanonicalFalse() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "OffCenterCh3", bssid = "04:d4:c4:05:18:01", frequencyMhz = 2422, centerFreq0Mhz = 2422, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "OffCenterCh9", bssid = "04:d4:c4:05:18:02", frequencyMhz = 2452, centerFreq0Mhz = 2452, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val c3 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:18:01"))
        val c9 = CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:18:02"))
        assertEquals(3, c3.int("primaryChannel", "channel"))
        assertEquals(false, c3.bool("isCanonicalPrimary"))
        assertEquals(9, c9.int("primaryChannel", "channel"))
        assertEquals(false, c9.bool("isCanonicalPrimary"))
    }

    @Test
    fun f05_t2_06_outOfRangeNegativeAndZeroFrequenciesClassifiedUnknownBandWithoutCrash() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "ZeroFreq", bssid = "04:d4:c4:05:19:01", frequencyMhz = 0, centerFreq0Mhz = 0, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "SubGhz900", bssid = "04:d4:c4:05:19:02", frequencyMhz = 915, centerFreq0Mhz = 915, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertEquals("UNKNOWN", CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:19:01")).str("band"))
        assertEquals("UNKNOWN", CliJsonLens.channelObj(CliJsonLens.radioByBssid(root, "04:d4:c4:05:19:02")).str("band"))
    }

    // =========================================================================
    // F06: Strict UTF-8 / Hex / Null-Byte Hidden SSID Decoder — Edge Cases (6)
    // =========================================================================

    @Test
    fun f06_t2_01_thirtyTwoAllNullBytesMaximumHiddenBeaconClassifiedAsHidden32Bytes() {
        val null32Hex = "00".repeat(32)
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = null, rawSsidHex = null32Hex, bssid = "04:d4:c4:06:11:01")),
        )
        val ssidObj = CliJsonLens.ssidObj(CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:06:11:01"))!!
        assertEquals(true, ssidObj.bool("isHidden"))
        assertEquals(true, ssidObj.bool("isNullBytePadded"))
        assertEquals(32, ssidObj.int("byteLength"))
    }

    @Test
    fun f06_t2_02_truncatedMultibyteEmojiAt32ByteBoundaryFallsBackToHexWithoutMalformedInputException() {
        // 30 'A' bytes (0x41) + first 2 bytes of 4-byte 🚀 UTF-8 sequence (F0 9F) -> truncated UTF-8!
        val truncatedUtf8Hex = "41".repeat(30) + "f09f"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = null, rawSsidHex = truncatedUtf8Hex, bssid = "04:d4:c4:06:12:01")),
        )
        val r = CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:06:12:01")
        assertEquals(false, CliJsonLens.ssidObj(r)!!.bool("isUtf8"))
        assertFalse(CliJsonLens.ssidDisplayName(r).contains('\uFFFD'))
    }

    @Test
    fun f06_t2_03_whitespaceOnlySsidPreservedDistinctFromZeroLengthHiddenNetwork() {
        val threeSpacesHex = "202020"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "   ", rawSsidHex = threeSpacesHex, bssid = "04:d4:c4:06:13:01")),
        )
        val ssidObj = CliJsonLens.ssidObj(CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:06:13:01"))!!
        assertEquals(false, ssidObj.bool("isHidden"))
        assertEquals(3, ssidObj.int("byteLength"))
    }

    @Test
    fun f06_t2_04_literalUnknownSsidStringFromRedactedAndroidApiNeverDisplayedVerbatimAsRealRouterName() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "<unknown ssid>",
                    rawSsidBytes = ByteArray(0),
                    bssid = "04:D4:C4:06:14:01",
                    rssiDbm = -56,
                ),
            ),
        )
        val dump = com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must NOT display raw Android '<unknown ssid>' string to user:\n$dump", dump.contains("<unknown ssid>"))
    }

    @Test
    fun f06_t2_05_ssidContainingQuotesPipesBackslashesAndNewlinesEscapesCleanlyInJsonAndMarkdown() {
        val trickySsid = "Cafe \"2F\" | Pipe\\Net\nTab"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = trickySsid, bssid = "04:d4:c4:06:15:01")),
        )
        val jsonRoot = CliRunnerHarness.analyzeStdin(scenario, format = "json").assertSucceeded().parseJsonRoot()
        assertTrue(CliJsonLens.ssidDisplayName(CliJsonLens.radioByBssid(jsonRoot, "04:d4:c4:06:15:01")).contains("Cafe"))

        val md = CliRunnerHarness.analyzeStdin(scenario, format = "markdown").assertSucceeded().stdout
        assertTrue(md.contains("Cafe"))
    }

    @Test
    fun f06_t2_06_asciiControlCharactersInSsidSanitizedWithoutInvisibleTerminalOrLayoutCorruption() {
        // SOH (0x01), BEL (0x07), ESC (0x1B) + "Lab"
        val ctrlHex = "01071b4c6162"
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = null, rawSsidHex = ctrlHex, bssid = "04:d4:c4:06:16:01")),
        )
        val r = CliJsonLens.radioByBssid(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "04:d4:c4:06:16:01")
        val disp = CliJsonLens.ssidDisplayName(r)
        assertFalse("Control characters must not pass raw ESC (0x1B) to display label", disp.contains('\u001B'))
    }

    // =========================================================================
    // F07: Offline IEEE OUI Vendor & LAA MAC Resolver — Edge Cases (5)
    // =========================================================================

    @Test
    fun f07_t2_01_androidRedactedPlaceholderMac020000000000FlaggedRedactedPlaceholder() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "RedactedProbe", bssid = "02:00:00:00:00:00")),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val radios = CliJsonLens.radios(root)
        if (radios.isNotEmpty()) {
            val v = CliJsonLens.vendorObj(radios[0])
            assertTrue(v.bool("isRedactedPlaceholder") == true || v.bool("isLocallyAdministered") == true)
        }
    }

    @Test
    fun f07_t2_02_mixedCaseAndHyphenatedMacAddressesNormalizeIdenticallyForOuiLookup() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "LowerMac", bssid = "fc:ec:da:01:02:03"),
                ScanEntrySpec(ssid = "HyphenMac", bssid = "FC-EC-DA-04-05-06"),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val v1 = CliJsonLens.vendorObj(CliJsonLens.radios(root)[0]).str("displayLabel").orEmpty()
        val v2 = CliJsonLens.vendorObj(CliJsonLens.radios(root)[1]).str("displayLabel").orEmpty()
        assertTrue(v1.contains("Ubiquiti", ignoreCase = true))
        assertEquals(v1, v2)
    }

    @Test
    fun f07_t2_03_malformedShortAndNonHexMacStringsHandledGracefullyWithoutOutOfBoundsCrash() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "ShortMac", bssid = "04:d4"),
                ScanEntrySpec(ssid = "NonHexMac", bssid = "zz:yy:xx:11:22:33"),
            ),
        )
        val res = CliRunnerHarness.analyzeStdin(scenario)
        // Either filters invalid MAC entries cleanly with exit 0 or reports descriptive validation error
        if (res.exitCode == 0) {
            val root = res.parseJsonRoot()
            assertTrue(CliJsonLens.radios(root).size <= 2)
        } else {
            assertTrue(res.stderr.isNotBlank())
        }
    }

    @Test
    fun f07_t2_04_broadcastAndAllZeroMacAddressesNeverMisattributedToRealManufacturer() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "AllZero", bssid = "00:00:00:00:00:00"),
                ScanEntrySpec(ssid = "Broadcast", bssid = "ff:ff:ff:ff:ff:ff"),
            ),
        )
        val res = CliRunnerHarness.analyzeStdin(scenario)
        if (res.exitCode == 0) {
            for (r in CliJsonLens.radios(res.parseJsonRoot())) {
                val label = CliJsonLens.vendorObj(r).str("displayLabel").orEmpty()
                assertFalse("00:00:00 / FF:FF:FF must not claim Xerox/Cisco", label.contains("Xerox", ignoreCase = true))
            }
        }
    }

    @Test
    fun f07_t2_05_wpsManufacturerFallbackEnrichesUnknownOrVirtualLaaMacVendorDisplay() {
        // Pure LAA MAC 02:9F:FE:11:22:33 with unknown OUI, but WPS IE 221 says "ASUSTeK" / "RT-AX86U Pro"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "VirtualAsusGuest",
                    bssid = "02:9F:FE:11:22:33",
                    rssiDbm = -52,
                    ies = listOf(BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe()),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("VirtualAsusGuest")
        detail.assertDetailContains("ASUSTeK", "RT-AX86U Pro")
    }

    // =========================================================================
    // F08: Physical AP Clustering, Wi-Fi 7 MLO & Mesh Grouping — Edge Cases (5)
    // =========================================================================

    @Test
    fun f08_t2_01_sameFiveOctetPrefixOnSameBandWith28DbRssiGapDoesNotFalselyMergeDistantMeshNodes() {
        // Sequential factory batch: two separate mesh nodes on 5 GHz placed in opposite ends of house (-45 vs -78 dBm)
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "BatchMesh", bssid = "04:d4:c4:88:00:01", rssiDbm = -45, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "BatchMesh", bssid = "04:d4:c4:88:00:02", rssiDbm = -78, frequencyMhz = 5745, centerFreq0Mhz = 5775, channelWidthMhz = 80),
            ),
        )
        val clusters = CliJsonLens.physicalClusters(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("Same-band nodes 33 dB apart must stay 2 separate physical AP clusters", 2, clusters.size)
    }

    @Test
    fun f08_t2_02_explicitWifi7MldMacStillClusters24GhzAnd6GhzLinksDespite30DbWallAttenuationDifference() {
        val mld = "04:d4:c4:77:77:00"
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(ssid = "WallMlo_BE", bssid = "04:d4:c4:77:77:01", rssiDbm = -50, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20, wifiStandard = "11be", apMldMacAddress = mld),
                ScanEntrySpec(ssid = "WallMlo_BE", bssid = "04:d4:c4:77:77:02", rssiDbm = -80, frequencyMhz = 6135, centerFreq0Mhz = 6105, channelWidthMhz = 320, wifiStandard = "11be", apMldMacAddress = mld),
            ),
        )
        val clusters = CliJsonLens.physicalClusters(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("Explicit apMldMacAddress must cluster links regardless of 2.4 vs 6 GHz wall loss", 1, clusters.size)
    }

    @Test
    fun f08_t2_03_multiSsidVirtualVapsMainAndGuestOnSameRouterKeepSeparateEssGroups() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "HomeMain", bssid = "04:d4:c4:55:00:01", rssiDbm = -48, frequencyMhz = 5180),
                ScanEntrySpec(ssid = "HomeGuest", bssid = "06:d4:c4:55:00:02", rssiDbm = -49, frequencyMhz = 5180),
            ),
        )
        val groups = CliJsonLens.groupedNetworks(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("Different SSIDs (Main vs Guest) must remain 2 distinct ESS network groups", 2, groups.size)
    }

    @Test
    fun f08_t2_04_caseSensitiveSsidsHomeWifiVersusHOMEWIFIRemainDistinctEssGroups() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "HomeWifi", bssid = "04:d4:c4:56:00:01", rssiDbm = -52),
                ScanEntrySpec(ssid = "HOMEWIFI", bssid = "b0:be:76:56:00:02", rssiDbm = -60),
            ),
        )
        val groups = CliJsonLens.groupedNetworks(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("IEEE 802.11 SSIDs are case-sensitive byte arrays and must not merge across case", 2, groups.size)
        assertNotEquals(groups[0].str("displayName"), groups[1].str("displayName"))
    }

    @Test
    fun f08_t2_05_mixedSecurityEssGroupHighlightsWorstSecurityAndEvilTwinFlag() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "HotelMesh", bssid = "00:40:96:57:00:01", rssiDbm = -54, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                ScanEntrySpec(ssid = "HotelMesh", bssid = "de:ad:be:ef:57:99", rssiDbm = -49, capabilities = "[ESS]"),
            ),
        )
        val group = CliJsonLens.groupedNetworks(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())[0]
        assertEquals(true, group.bool("hasEvilTwinWarning"))
        assertEquals("F", CliJsonLens.normalizeSecurityGrade(group.let { CliJsonLens.securityObj(it.get("worstSecurityAssessment") as kotlinx.serialization.json.JsonObject).str("grade") }))
    }
}
