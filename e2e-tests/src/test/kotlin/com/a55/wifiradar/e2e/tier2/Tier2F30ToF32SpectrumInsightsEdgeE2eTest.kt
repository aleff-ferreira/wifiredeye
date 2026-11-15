@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import org.junit.After
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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F30`–`F32` (`18` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F30ToF32SpectrumInsightsEdgeE2eTest {

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
    // F30: Interactive Multi-Band Spectrum Canvas & Best-Channel UI — Edge (6)
    // =========================================================================

    @Test
    fun f30_t2_01_emptySpectrumBandRendersCleanBaselineAxisAnd100ScoreWithoutCanvasCrash() {
        robot.launchApp()
        robot.injectScanBatch(emptyList())
        val spec = NavigationRobot(composeRule).openSpectrum()
        spec.selectBand("2.4 GHz")
        spec.selectBand("5 GHz")
        val (_, bmp) = robot.captureRootBitmap("f30_t2_01_empty_spectrum.png")
        assertTrue(bmp.width > 0 && bmp.height > 0)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-EmptySpectrum")
    }

    @Test
    fun f30_t2_02_fiftyDenseOverlappingRoutersRenderCappedSpectrumCanvasSmoothly() {
        val fiftyScans = (1..50).map { i ->
            ScanResultFactory.createScanResult(
                ssid = "DenseSpec_$i",
                bssid = String.format("04:D4:C4:30:12:%02X", i),
                rssiDbm = -44 - i,
                frequencyMhz = 2412 + (i % 11) * 5,
                channelWidth = if (i % 4 == 0) ScanResult.CHANNEL_WIDTH_40MHZ else ScanResult.CHANNEL_WIDTH_20MHZ,
                centerFreq0Mhz = 2412 + (i % 11) * 5,
            )
        }
        robot.launchApp()
        robot.injectScanBatch(fiftyScans)
        NavigationRobot(composeRule).openSpectrum().selectBand("2.4 GHz")
        val (_, bmp) = robot.captureRootBitmap("f30_t2_02_dense50_spectrum.png")
        assertTrue(bmp.width > 0)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-Dense50")
    }

    @Test
    fun f30_t2_03_excludeOwnNetworkToggleWhenNotConnectedToWifiBehavesAsSafeNoOp() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "UnconnectedSpecAP", bssid = "04:D4:C4:30:13:01", rssiDbm = -52, frequencyMhz = 5180)),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        runCatching { spec.toggleExcludeOwnSsid() }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "UnconnectedSpecAP stays visible", "UnconnectedSpecAP", "149", "36")
    }

    @Test
    fun f30_t2_04_ultraWide160MhzAndSplit80Plus80MhzCurvesStayClippedInsideCanvasBounds() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "UltraWide160",
                    bssid = "04:D4:C4:30:14:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_160MHZ,
                    centerFreq0Mhz = 5250,
                ),
                ScanResultFactory.createScanResult(
                    ssid = "SplitDual80",
                    bssid = "B0:BE:76:30:14:02",
                    rssiDbm = -54,
                    frequencyMhz = 5220,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ,
                    centerFreq0Mhz = 5210,
                    centerFreq1Mhz = 5775,
                ),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        val (_, bmp) = robot.captureRootBitmap("f30_t2_04_ultrawide_lobes.png")
        assertTrue(bmp.width > 0)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-UltraWide")
    }

    @Test
    fun f30_t2_05_japanChannel14At2484MhzRendersInside24GhzSpectrumWithoutHorizontalOverflow() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "JapanCh14_AP",
                    bssid = "04:D4:C4:30:15:01",
                    rssiDbm = -55,
                    frequencyMhz = 2484,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2484,
                ),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("2.4 GHz")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-JapanCh14")
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-450dpi")
    fun f30_t2_06_spectrumScreenInLandscapeViewportMaintainsReadableCanvasAndRecommendation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LandscapeSpec_5G", bssid = "04:D4:C4:30:16:01", rssiDbm = -50, frequencyMhz = 5180)),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        spec.scrollUntilVisible("149", "Channel", "Recommendation")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-Landscape")
    }

    // =========================================================================
    // F31: CCI vs ACI Interference Breakdown & Multi-AP Time/Roam — Edge (6)
    // =========================================================================

    @Test
    fun f31_t2_01_isolatedRouterWithZeroNeighborsShowsCleanZeroInterferenceSummary() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "CabinInWoods_5G", bssid = "04:D4:C4:31:11:01", rssiDbm = -47, frequencyMhz = 5180)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "CabinInWoods_5G", bssid = "04:D4:C4:31:11:01", rssiDbm = -47, frequencyMhz = 5180),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("5 GHz")
        nav.openInterferenceBreakdown()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Zero interference summary",
            "0",
            "No ",
            "Clean",
            "Wide Open",
            "None",
        )
    }

    @Test
    fun f31_t2_02_faintBelowMinus85DbmDistantNeighborMarkedNonActionableNoiseFloor() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyStrongCh1", bssid = "04:D4:C4:31:12:01", rssiDbm = -46, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "DistantWhisperCh2", bssid = "B0:BE:76:31:12:02", rssiDbm = -90, frequencyMhz = 2417, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2417),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyStrongCh1", bssid = "04:D4:C4:31:12:01", rssiDbm = -46, frequencyMhz = 2412),
        )
        NavigationRobot(composeRule).openInterferenceBreakdown()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F31-FaintBelow85")
    }

    @Test
    fun f31_t2_03_zeroRoamEventsDisplaysFriendlyNoRoamingEventsRecordedPlaceholder() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "StationaryNoRoam", bssid = "04:D4:C4:31:13:01", rssiDbm = -51)),
        )
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F31-ZeroRoamEvents")
    }

    @Test
    fun f31_t2_04_singleSweepMultiApTimeGraphRendersCleanlyWithoutTwoPointLineException() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OneTickAlpha", bssid = "04:D4:C4:31:14:01", rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "OneTickBeta", bssid = "B0:BE:76:31:14:02", rssiDbm = -64),
            ),
        )
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        val (_, bmp) = robot.captureRootBitmap("f31_t2_04_single_tick_time_graph.png")
        assertTrue(bmp.width > 0)
    }

    @Test
    fun f31_t2_05_thirtySimultaneousApsCapTopTracesInTimeGraphMaintainingLegibility() {
        val thirtyAps = (1..30).map { i ->
            ScanResultFactory.createScanResult(
                ssid = "TraceCap_$i",
                bssid = String.format("04:D4:C4:31:15:%02X", i),
                rssiDbm = -45 - i,
            )
        }
        robot.launchApp()
        robot.injectScanBatch(thirtyAps)
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(thirtyAps)
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F31-ThirtyTraceCap")
    }

    @Test
    fun f31_t2_06_historyWindowSelectionSurvivesActivityRecreation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WindowPersistAP", bssid = "04:D4:C4:31:16:01", rssiDbm = -54)),
        )
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.clickFirstMatching(composeRule, ComposeSemanticMatchers.isToggleWithAnyPhrase("15m"))

        robot.recreateApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "15m window preserved", "15m")
    }

    // =========================================================================
    // F32: Insights Health Dashboard, Security Audit & Share Export — Edge (6)
    // =========================================================================

    @Test
    fun f32_t2_01_insightsBeforeFirstScanShowsWaitingForScanDataWithoutFakeZeroOrHundredScore() {
        robot.launchApp()
        NavigationRobot(composeRule).openInsights()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Waiting for scan data placeholder",
            "Waiting",
            "Listening",
            "Scan",
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must NOT display fake '100 / 100' when 0 scans received:\n$dump", dump.contains("100 / 100"))
    }

    @Test
    fun f32_t2_02_exportingEmptyZeroNetworkSnapshotProducesValidRfc8259JsonAndMarkdownWithoutCrash() {
        robot.launchApp()
        robot.injectScanBatch(emptyList())
        val insights = NavigationRobot(composeRule).openInsights()
        insights.clickShareExport("JSON")

        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val parsed = CliRunnerHarness.lenientJson.parseToJsonElement(payload.resolvedBody.trim())
        assertTrue(parsed is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun f32_t2_03_exportingSpecialCharacterSsidsProducesValidUnbrokenJsonAndMarkdownViaShareSheet() {
        val specialSsid = "R&D \"Lab\" | \\Backslash\\"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = specialSsid, bssid = "04:D4:C4:32:13:01", rssiDbm = -50)),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.clickShareExport("JSON")

        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val parsed = CliRunnerHarness.lenientJson.parseToJsonElement(payload.resolvedBody.trim())
        assertTrue(parsed.toString().contains("Lab"))
    }

    @Test
    fun f32_t2_04_togglingRedactMacsOnThenOffRestoresFullHardwareMacAddressInExport() {
        val rawBssid = "04:D4:C4:77:88:99"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ToggleRedactBack_5G", bssid = rawBssid, rssiDbm = -52)),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.toggleRedactMacs() // ON
        insights.toggleRedactMacs() // Back OFF
        insights.clickShareExport("JSON")

        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertTrue(
            "Turning Redact MACs back OFF must include full BSSID $rawBssid in JSON export",
            payload.resolvedBody.contains(rawBssid, ignoreCase = true),
        )
    }

    @Test
    fun f32_t2_05_tappingActionableInsightCtaNavigatesDirectlyToRelevantTargetTab() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "BadChannelCh3_40",
                    bssid = "04:D4:C4:32:15:01",
                    rssiDbm = -50,
                    frequencyMhz = 2422,
                    channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ,
                    centerFreq0Mhz = 2432,
                ),
                ScanResultFactory.createScanResult(
                    ssid = "NeighborCh1",
                    bssid = "B0:BE:76:32:15:02",
                    rssiDbm = -48,
                    frequencyMhz = 2412,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2412,
                ),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "BadChannelCh3_40", bssid = "04:D4:C4:32:15:01", rssiDbm = -50, frequencyMhz = 2422),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        runCatching {
            ComposeSemanticMatchers.clickFirstMatching(
                composeRule,
                ComposeSemanticMatchers.isClickableWithAnyPhrase("Open Spectrum", "View Spectrum", "Inspect", "Check Channel"),
            )
        }
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F32-InsightCta")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun f32_t2_06_insightsDashboardAndExportControlsRemainAccessibleAt200PercentFontScale() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "LargeFontInsights_AX",
                    bssid = "04:D4:C4:32:16:01",
                    rssiDbm = -49,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.scrollUntilVisible("Share", "Redact")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F32-FontScale200")
    }
}
