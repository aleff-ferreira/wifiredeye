@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F24`–`F26` (`18` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F24ToF26NetworksDiscoveryEdgeE2eTest {

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
    // F24: Network Discovery Cards, Mesh Toggle & Connected Badge — Edge (6)
    // =========================================================================

    @Test
    fun f24_t2_01_emptyFaradayCageZeroNetworksRendersCalmEmptyStateWithZeroCrash() {
        robot.launchApp()
        robot.injectScanBatch(emptyList())
        NavigationRobot(composeRule).openNetworks()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Zero-AP Faraday cage empty state",
            "0 Networks",
            "No nearby",
            "No wireless",
            "Scanning",
            "Refresh",
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F24-ZeroAP")
    }

    @Test
    fun f24_t2_02_eightyDenseUrbanRoutersScrollSmoothlyViaLazyColumnVirtualization() {
        val eightyRouters = (1..80).map { idx ->
            ScanResultFactory.createScanResult(
                ssid = String.format("UrbanTower_%02d", idx),
                bssid = String.format("04:D4:C4:80:00:%02X", idx),
                rssiDbm = -40 - (idx / 2),
                frequencyMhz = if (idx % 2 == 0) 5180 else 2437,
                channelWidth = if (idx % 2 == 0) ScanResult.CHANNEL_WIDTH_80MHZ else ScanResult.CHANNEL_WIDTH_20MHZ,
                centerFreq0Mhz = if (idx % 2 == 0) 5210 else 2437,
            )
        }
        robot.launchApp()
        robot.injectScanBatch(eightyRouters)
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.assertNetworkDisplayed("UrbanTower_01")
        networks.enterSearchQuery("UrbanTower_80")
        networks.assertNetworkDisplayed("UrbanTower_80")
    }

    @Test
    fun f24_t2_03_threeUnrelatedHiddenRoutersRenderAsSeparateCardsNeverMergedIntoSingleHiddenMesh() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = "04:D4:C4:24:13:01", rssiDbm = -50), // ASUS
                ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = "FC:EC:DA:24:13:02", rssiDbm = -62), // Ubiquiti
                ScanResultFactory.createScanResult(ssid = "", rawSsidBytes = ByteArray(0), bssid = "B0:BE:76:24:13:03", rssiDbm = -74), // TP-Link
            ),
        )
        NavigationRobot(composeRule).openNetworks()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "ASUS hidden card", "ASUS", "24:13:01")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Ubiquiti hidden card", "Ubiquiti", "24:13:02")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "TP-Link hidden card", "TP-Link", "24:13:03")
    }

    @Test
    fun f24_t2_04_mixedSecurityMeshGroupSurfacesWorstSecurityGradeAndEvilTwinWarningOnCard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "CompromisedLobby",
                    bssid = "00:40:96:24:14:01",
                    rssiDbm = -54,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "CompromisedLobby",
                    bssid = "DE:AD:BE:EF:24:14",
                    rssiDbm = -47,
                    capabilities = "[ESS]",
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("CompromisedLobby")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Worst security / Evil-Twin warning on card", "F", "Open", "Evil Twin", "Spoof", "Risk")
    }

    @Test
    fun f24_t2_05_nonUtf8HexFallbackSsidCardRendersCleanHexBadgeWithoutReplacementDiamonds() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = null,
                    rawSsidBytes = byteArrayOf(0x80.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0x49, 0x6F, 0x54),
                    bssid = "04:D4:C4:24:15:01",
                    rssiDbm = -58,
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks()
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue("Expected hex fallback without U+FFFD replacement char:\n$dump", !dump.contains('\uFFFD'))
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Hex SSID representation", "80FEFF", "80:FE", "Hex")
    }

    @Test
    fun f24_t2_06_rapidToggleBetweenGroupedAndAllRadiosPreservesConnectedSpotlight() {
        val bssid = "04:D4:C4:24:16:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "RapidToggleMesh", bssid = bssid, rssiDbm = -49, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "RapidToggleMesh", bssid = "04:D4:C4:24:16:02", rssiDbm = -65, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "RapidToggleMesh", bssid = bssid, rssiDbm = -49),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        repeat(3) {
            networks.switchToAllRadiosMode()
            networks.switchToGroupedMode()
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Connected spotlight intact", "Connected")
    }

    // =========================================================================
    // F25: Instant Search, Filter Chips & Stable Sorting — Edge Cases (6)
    // =========================================================================

    @Test
    fun f25_t2_01_searchQueryWithZeroMatchesShowsHelpfulNoMatchingNetworksEmptyStateAndClearAction() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RealHomeWiFi", bssid = "04:D4:C4:25:11:01", rssiDbm = -50)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.enterSearchQuery("ZZZ_NONEXISTENT_999")
        networks.assertNetworkNotDisplayed("RealHomeWiFi")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "No matches message", "No matching", "No networks match", "Clear", "0")

        networks.clearSearch()
        networks.assertNetworkDisplayed("RealHomeWiFi")
    }

    @Test
    fun f25_t2_02_regexMetacharactersAndSqlQuotesInSearchQueryTreatedAsSafeLiteralSubstring() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SafeLiteral_5G", bssid = "04:D4:C4:25:12:01", rssiDbm = -52)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        // Must NEVER throw PatternSyntaxException!
        networks.enterSearchQuery(".*(([a-z+'\"\\")
        networks.clearSearch()
        networks.assertNetworkDisplayed("SafeLiteral_5G")
    }

    @Test
    fun f25_t2_03_colonFreeAndMixedCaseBssidSearchMatchesColonFormattedRouterBssid() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "MacSearchRouter", bssid = "FC:EC:DA:AB:CD:EF", rssiDbm = -54)),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.enterSearchQuery("ab:cd:ef")
        networks.assertNetworkDisplayed("MacSearchRouter")
    }

    @Test
    fun f25_t2_04_mutuallyExclusiveFilterCombinationRecoversCleanlyWhenResetToAll() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OnlyWeak24_AP", bssid = "04:D4:C4:25:14:01", rssiDbm = -84, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.toggleFilterChip("5 GHz")
        networks.toggleFilterChip("Strong", "-65 dBm")
        networks.assertNetworkNotDisplayed("OnlyWeak24_AP")

        networks.toggleFilterChip("All")
        networks.assertNetworkDisplayed("OnlyWeak24_AP")
    }

    @Test
    fun f25_t2_05_identicalRssiTieBreakerUsesDeterministicSecondaryKeyWithoutRandomShuffle() {
        robot.launchApp()
        val tied = listOf(
            ScanResultFactory.createScanResult(ssid = "TieGamma", bssid = "04:D4:C4:25:15:03", rssiDbm = -60),
            ScanResultFactory.createScanResult(ssid = "TieAlpha", bssid = "04:D4:C4:25:15:01", rssiDbm = -60),
            ScanResultFactory.createScanResult(ssid = "TieBeta", bssid = "04:D4:C4:25:15:02", rssiDbm = -60),
        )
        robot.injectScanBatch(tied)
        val order1 = ComposeSemanticMatchers.dumpScreenText(composeRule)

        robot.advanceClockMs(1_000L)
        robot.injectScanBatch(tied.reversed())
        val order2 = ComposeSemanticMatchers.dumpScreenText(composeRule)

        val names = listOf("TieAlpha", "TieBeta", "TieGamma")
        val seq1 = names.sortedBy { order1.indexOf(it) }
        val seq2 = names.sortedBy { order2.indexOf(it) }
        assertTrue("Identical -60 dBm tie-break order ($seq1 vs $seq2) must be 100% deterministic!", seq1 == seq2)
    }

    @Test
    fun f25_t2_06_largeGenuine15DbSignalChangeOverridesHysteresisAndReordersImmediately() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "WasStrongNowWeak", bssid = "04:D4:C4:25:16:01", rssiDbm = -48),
                ScanResultFactory.createScanResult(ssid = "WasWeakNowStrong", bssid = "B0:BE:76:25:16:02", rssiDbm = -75),
            ),
        )
        robot.advanceClockMs(1_500L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "WasStrongNowWeak", bssid = "04:D4:C4:25:16:01", rssiDbm = -82),
                ScanResultFactory.createScanResult(ssid = "WasWeakNowStrong", bssid = "B0:BE:76:25:16:02", rssiDbm = -45),
            ),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue(
            "37 dB signal reversal must reorder WasWeakNowStrong above WasStrongNowWeak",
            dump.indexOf("WasWeakNowStrong") < dump.indexOf("WasStrongNowWeak"),
        )
    }

    // =========================================================================
    // F26: Live Network Detail Bottom Sheet — Edge Cases (6)
    // =========================================================================

    @Test
    fun f26_t2_01_selectedRouterEvictedWhileDetailSheetOpenShowsGracefulStaleOrDismissesCleanlyWithoutCrash() {
        val transientBssid = "04:D4:C4:26:11:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "VanishingSheetAP", bssid = transientBssid, rssiDbm = -72),
                ScanResultFactory.createScanResult(ssid = "AnchorHomeAP", bssid = "B0:BE:76:26:11:02", rssiDbm = -50),
            ),
        )
        NavigationRobot(composeRule).openNetworks().openDetailSheetFor("VanishingSheetAP")

        // Advance > 95s so VanishingSheetAP is evicted from active snapshot
        robot.advanceClockMs(96_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AnchorHomeAP", bssid = "B0:BE:76:26:11:02", rssiDbm = -50)),
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F26-EvictedWhileSheetOpen")
    }

    @Test
    fun f26_t2_02_legacy80211bRouterWithZeroModernExtensionIesRendersCleanlyWithoutNullFields() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Ancient11bPrinter",
                    bssid = "00:40:96:26:12:01",
                    rssiDbm = -68,
                    frequencyMhz = 2412,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2412,
                    capabilities = "[WEP][ESS]",
                    wifiStandard = ScanResult.WIFI_STANDARD_LEGACY,
                    ies = emptyList(),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("Ancient11bPrinter")
        detail.assertDetailContains("Ancient11bPrinter", "WEP", "F")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F26-Legacy11b")
    }

    @Test
    fun f26_t2_03_hiddenNullPaddedNetworkDetailSheetShowsBytePaddingLengthAndHardwareAddress() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "",
                    rawSsidBytes = ByteArray(12) { 0 },
                    bssid = "04:D4:C4:26:13:01",
                    rssiDbm = -55,
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("Hidden Network")
        detail.assertDetailContains("04:D4:C4:26:13:01", "12")
    }

    @Test
    fun f26_t2_04_detailSheetSurvivesActivityRecreationPreservingSelectedRouterBssid() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SheetSurviveRotate", bssid = "04:D4:C4:26:14:01", rssiDbm = -51)),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("SheetSurviveRotate")
        detail.assertDetailContains("04:D4:C4:26:14:01")

        robot.recreateApp()
        detail.assertDetailContains("SheetSurviveRotate", "04:D4:C4:26:14:01")
    }

    @Test
    fun f26_t2_05_systemBackPressDismissesDetailSheetWithoutExitingMainActivity() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BackDismissRouter", bssid = "04:D4:C4:26:15:01", rssiDbm = -53)),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("BackDismissRouter")
        val networks = detail.dismiss()
        networks.assertNetworkDisplayed("BackDismissRouter")
        assertTrue("MainActivity must remain alive after dismissing Detail Sheet", !robot.currentActivity().isFinishing)
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun f26_t2_06_detailSheetScrollableToBottomActionsAt200PercentAccessibilityFontScale() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "FontScaleDetail_AX",
                    bssid = "04:D4:C4:26:16:01",
                    rssiDbm = -48,
                    ies = listOf(
                        BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT.toScanResultIe(),
                        BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe(),
                        BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("FontScaleDetail_AX").clickMeasureProximity()
        nav.assertTabSelected("Proximity")
    }
}
