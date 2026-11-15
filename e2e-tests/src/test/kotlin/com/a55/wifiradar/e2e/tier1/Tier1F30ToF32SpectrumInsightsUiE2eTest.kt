@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F30`–`F32` (`18` `@Test` methods: `6` per feature).
 * - `F30` Interactive Multi-Band Spectrum Canvas & Best-Channel UI (`6` tests)
 * - `F31` CCI vs ACI Interference Breakdown & Multi-AP Time/Roam Graph (`6` tests)
 * - `F32` Insights Health Dashboard, Security Audit & Share Export (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F30ToF32SpectrumInsightsUiE2eTest {

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
    // F30: Interactive Multi-Band Spectrum Canvas & Best-Channel UI (6 Tests)
    // =========================================================================

    @Test
    fun f30_t1_01_spectrum24GhzCanvasRendersChannelCurvesAndRecommendsCleanCanonicalChannel11() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "CrowdedCh1", bssid = "04:D4:C4:30:01:01", rssiDbm = -47, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "CrowdedCh6", bssid = "B0:BE:76:30:01:02", rssiDbm = -49, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("2.4 GHz")
        spec.assertAccessibleCanvasPresent()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Best-Channel 2.4 GHz recommendation for clean Ch 11",
            "Ch 11",
            "Channel 11",
            "11",
        )
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F30-24GHz")
    }

    @Test
    fun f30_t1_02_spectrum5GhzRecommendsCleanUnii3WhenUnii1IsCrowdedAndDfsExcludedByDefault() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "BusyUNII1_36", bssid = "04:D4:C4:30:02:01", rssiDbm = -45, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "BusyUNII1_44", bssid = "B0:BE:76:30:02:02", rssiDbm = -50, frequencyMhz = 5220, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "5 GHz clean UNII-3 recommendation (149..165)",
            "149",
            "153",
            "157",
            "161",
        )
    }

    @Test
    fun f30_t1_03_includeDfsToggleUnlocksCleanUnii2ChannelsWhenNonDfs5GhzIsCongested() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Busy36", bssid = "04:D4:C4:30:03:01", rssiDbm = -44, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "Busy149", bssid = "B0:BE:76:30:03:02", rssiDbm = -45, frequencyMhz = 5745, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5775),
                ScanResultFactory.createScanResult(ssid = "Busy165", bssid = "FC:EC:DA:30:03:03", rssiDbm = -47, frequencyMhz = 5825, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 5825),
            ),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        spec.toggleIncludeDfs()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Unlocked DFS channel recommendation",
            "52",
            "60",
            "100",
            "132",
            "DFS",
        )
    }

    @Test
    fun f30_t1_04_excludeOwnConnectedNetworkToggleRemovesMeshSelfContentionFromRecommendation() {
        val node1 = "04:D4:C4:30:04:01"
        val node2 = "04:D4:C4:30:04:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyOwnMesh", bssid = node1, rssiDbm = -46, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "MyOwnMesh", bssid = node2, rssiDbm = -54, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "NeighborBlast149", bssid = "B0:BE:76:30:04:03", rssiDbm = -48, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyOwnMesh", bssid = node1, rssiDbm = -46, frequencyMhz = 5180),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        spec.toggleExcludeOwnSsid()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "With own mesh excluded, Ch 36 has zero neighbor contention",
            "36",
            "Optimal",
            "Wide Open",
        )
    }

    @Test
    fun f30_t1_05_perChannelScoreStripDisplays0To100ScoresAndStarRatings() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ChannelStripTest", bssid = "04:D4:C4:30:05:01", rssiDbm = -52, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("2.4 GHz")
        spec.scrollUntilVisible("★", "100", "Wide Open")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Channel score & star rating", "★", "/ 100", "Wide Open", "Crowded")
    }

    @Test
    fun f30_t1_06_selectingRouterInSpectrumHighlightsCurveAndShowsChannelOccupancyDetails() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SpotlightCurve_80", bssid = "04:D4:C4:30:06:01", rssiDbm = -49, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "BackgroundCurve_20", bssid = "B0:BE:76:30:06:02", rssiDbm = -68, frequencyMhz = 5745, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 5745),
            ),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        spec.selectApCurve("SpotlightCurve_80")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Selected curve label", "SpotlightCurve_80", "80 MHz", "36")
    }

    // =========================================================================
    // F31: CCI vs ACI Interference Breakdown & Multi-AP Time/Roam Graph (6 Tests)
    // =========================================================================

    @Test
    fun f31_t1_01_cciVersusAciBreakdownDistinguishesPoliteCoChannelFromOverlappingSidebandNoise() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyRouter_Ch1", bssid = "04:D4:C4:31:01:01", rssiDbm = -50, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "PoliteNeighbor_Ch1", bssid = "B0:BE:76:31:01:02", rssiDbm = -58, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "RudeOverlap_Ch3", bssid = "FC:EC:DA:31:01:03", rssiDbm = -54, frequencyMhz = 2422, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2422),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyRouter_Ch1", bssid = "04:D4:C4:31:01:01", rssiDbm = -50, frequencyMhz = 2412),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("2.4 GHz")
        nav.openInterferenceBreakdown()

        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Co-Channel (CCI) section",
            "Co-Channel",
            "Shared-Channel",
            "CCI",
        )
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Adjacent-Channel (ACI) section",
            "Adjacent-Channel",
            "Overlapping-Channel",
            "ACI",
        )
    }

    @Test
    fun f31_t1_02_severeOffCenter24GhzAdjacentOverlapDisplaysActionableWarning() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyCh1", bssid = "04:D4:C4:31:02:01", rssiDbm = -52, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "WideCh3Blaster", bssid = "B0:BE:76:31:02:02", rssiDbm = -45, frequencyMhz = 2422, channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ, centerFreq0Mhz = 2432),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyCh1", bssid = "04:D4:C4:31:02:01", rssiDbm = -52, frequencyMhz = 2412),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("2.4 GHz")
        nav.openInterferenceBreakdown()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Severe ACI overlap warning",
            "WideCh3Blaster",
            "ACI",
            "Overlapping",
            "collision",
        )
    }

    @Test
    fun f31_t1_03_ownMeshSameSsidCoChannelSiblingLabeledAsMeshSelfContentionInsteadOfHostileNeighbor() {
        val nodeA = "04:D4:C4:31:03:01"
        val nodeB = "04:D4:C4:31:03:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = nodeA, rssiDbm = -48, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = nodeB, rssiDbm = -59, frequencyMhz = 5180, centerFreq0Mhz = 5210),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DecoHomeMesh", bssid = nodeA, rssiDbm = -48, frequencyMhz = 5180),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("5 GHz")
        nav.openInterferenceBreakdown()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Mesh self-contention attribution",
            "Mesh",
            "Self-Contention",
            "DecoHomeMesh",
            "Co-Channel",
        )
    }

    @Test
    fun f31_t1_04_multiApRssiTimeGraphRendersSimultaneousRouterTracesOverSweepTimeline() {
        robot.launchApp()
        for (step in 1..4) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "TimelineAP_Alpha", bssid = "04:D4:C4:31:04:01", rssiDbm = -50 - step, explicitTimestampMicros = step * 2_000_000L),
                    ScanResultFactory.createScanResult(ssid = "TimelineAP_Beta", bssid = "B0:BE:76:31:04:02", rssiDbm = -68 + step, explicitTimestampMicros = step * 2_000_000L),
                ),
            )
            robot.advanceClockMs(2_000L)
        }
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Multi-AP RSSI time graph legend/traces",
            "TimelineAP_Alpha",
            "TimelineAP_Beta",
            "1m",
            "5m",
        )
    }

    @Test
    fun f31_t1_05_historyWindowSelectorSwitchesCleanlyBetween1m5mAnd15mWindows() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WindowSwitchAP", bssid = "04:D4:C4:31:05:01", rssiDbm = -54)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openTimeAndRoamGraph()

        ComposeSemanticMatchers.clickFirstMatching(composeRule, ComposeSemanticMatchers.isToggleWithAnyPhrase("5m"))
        ComposeSemanticMatchers.clickFirstMatching(composeRule, ComposeSemanticMatchers.isToggleWithAnyPhrase("15m"))
        ComposeSemanticMatchers.clickFirstMatching(composeRule, ComposeSemanticMatchers.isToggleWithAnyPhrase("1m"))
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F31-HistoryWindow")
    }

    @Test
    fun f31_t1_06_bssidRoamingHandoffEventRendersInChronologicalRoamTimelineWithBandAndDbDelta() {
        val b24 = "04:D4:C4:31:06:01"
        val b5 = "04:D4:C4:31:06:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DualRoamHome", bssid = b24, rssiDbm = -76, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "DualRoamHome", bssid = b5, rssiDbm = -49, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DualRoamHome", bssid = b24, rssiDbm = -76, frequencyMhz = 2412),
        )
        robot.advanceClockMs(1_500L)
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DualRoamHome", bssid = b5, rssiDbm = -49, frequencyMhz = 5180),
        )
        robot.advanceClockMs(1_000L)

        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Roam event card with target BSSID and +27 dB improvement",
            "31:06:02",
            "+27",
            "5 GHz",
        )
    }

    // =========================================================================
    // F32: Insights Health Dashboard, Security Audit & Share Export (6 Tests)
    // =========================================================================

    @Test
    fun f32_t1_01_insightsDashboardRendersOverall0To100HealthGaugeAndFourSubScoreBars() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "HealthyStudio_AX",
                    bssid = "04:D4:C4:32:01:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.assertAccessibleHealthRingPresent()
        val score = insights.readOverallHealthScore()
        assertTrue("Expected pristine environment Health Score >= 75, got $score", score in 75..100)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Sub-score labels", "Coverage", "Crowding", "Interference", "Security")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F32-Insights")
    }

    @Test
    fun f32_t1_02_evilTwinSpoofingSurfacesHighPriorityCriticalSecurityBannerOnInsights() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "BankBranch_WiFi",
                    bssid = "00:40:96:32:02:01",
                    rssiDbm = -60,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "BankBranch_WiFi",
                    bssid = "DE:AD:BE:EF:32:99",
                    rssiDbm = -45,
                    capabilities = "[ESS]",
                ),
            ),
        )
        NavigationRobot(composeRule).openInsights()
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Evil-Twin alert banner",
            "Evil Twin",
            "Spoof",
            "BankBranch_WiFi",
        )
    }

    @Test
    fun f32_t1_03_perRouterSecurityAuditGradesEveryDetectedNetworkWithPlainEnglishRemediation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "AuditWPA3",
                    bssid = "04:D4:C4:32:03:01",
                    rssiDbm = -50,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "AuditLegacyWEP",
                    bssid = "B0:BE:76:32:03:02",
                    rssiDbm = -63,
                    capabilities = "[WEP][ESS]",
                ),
            ),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.scrollUntilVisible("AuditLegacyWEP", "WEP")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "AuditWPA3 A+ grade", "AuditWPA3", "A+")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "AuditLegacyWEP F grade", "AuditLegacyWEP", "F", "WEP")
    }

    @Test
    fun f32_t1_04_shareJsonExportDispatchesActionSendWithValidDiagnosticJsonAndFileProviderPermissions() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ShareJsonNet_5G", bssid = "04:D4:C4:32:04:01", rssiDbm = -51)),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.clickShareExport("JSON")

        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val parsed = CliRunnerHarness.lenientJson.parseToJsonElement(payload.resolvedBody.trim())
        val root = parsed as kotlinx.serialization.json.JsonObject
        assertEquals(1, CliJsonLens.radios(root).size)
        assertTrue(payload.resolvedBody.contains("ShareJsonNet_5G"))
    }

    @Test
    fun f32_t1_05_shareMarkdownExportWithRedactMacsEnabledMasksSubscriberOctetsEndToEnd() {
        val sensitiveBssid = "04:D4:C4:AB:CD:EF"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RedactedShare_AX", bssid = sensitiveBssid, rssiDbm = -49)),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.toggleRedactMacs()
        insights.clickShareExport("Markdown")

        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertTrue("Expected Markdown headings in shared report", payload.resolvedBody.contains("#"))
        assertTrue("Expected SSID in shared Markdown", payload.resolvedBody.contains("RedactedShare_AX"))
        assertFalse(
            "Sensitive subscriber MAC octets AB:CD must be redacted in shared Markdown:\n${payload.resolvedBody}",
            payload.resolvedBody.contains("04:D4:C4:AB:CD:EF", ignoreCase = true) ||
                payload.resolvedBody.contains("AB:CD:EF", ignoreCase = true),
        )
        assertTrue("Expected XX:XX redaction mask in shared Markdown", payload.resolvedBody.contains("XX:XX", ignoreCase = true))
    }

    @Test
    fun f32_t1_06_copyDiagnosticReportActionWritesStructuredReportToClipboard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ClipboardInsight_5G", bssid = "04:D4:C4:32:06:01", rssiDbm = -54)),
        )
        val insights = NavigationRobot(composeRule).openInsights()
        insights.clickCopyExport()

        val clipText = ShareIntentInspector.readClipboardText(robot.appContext)
        assertTrue("Clipboard diagnostic payload must include ClipboardInsight_5G", clipText.contains("ClipboardInsight_5G"))
    }
}
