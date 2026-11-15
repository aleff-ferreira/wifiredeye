@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier3

import android.graphics.Color as AndroidColor
import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
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
 * Tier 3 Cross-Feature Pairwise Interaction E2E Tests: Spectrum, CCI/ACI, Insights & Export (`T3_16`–`T3_24` = `9` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier3SpectrumInsightsExportInteractionsE2eTest {

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
    fun t3_16_f05_f15_f30_f31_offCenter24Ghz40MhzInterfererDrivesBestChannel11AndAciBreakdownWarning() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyHome_Ch1", bssid = "04:D4:C4:73:16:01", rssiDbm = -52, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "RudeWide_Ch3", bssid = "B0:BE:76:73:16:02", rssiDbm = -46, frequencyMhz = 2422, channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ, centerFreq0Mhz = 2432),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyHome_Ch1", bssid = "04:D4:C4:73:16:01", rssiDbm = -52, frequencyMhz = 2412),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("2.4 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Clean Ch 11 recommended", "11")

        nav.openInterferenceBreakdown()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "ACI interferer surfaced", "RudeWide_Ch3", "Adjacent", "Overlapping", "ACI")
    }

    @Test
    fun t3_17_f08_f15_f21_f30_connectedMultiNodeMeshExcludeOwnSsidEliminatesSelfContentionPenalty() {
        val n1 = "04:D4:C4:73:17:01"
        val n2 = "04:D4:C4:73:17:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "TriMeshHome", bssid = n1, rssiDbm = -46, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "TriMeshHome", bssid = n2, rssiDbm = -52, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "NeighborUNII3", bssid = "B0:BE:76:73:17:03", rssiDbm = -49, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "TriMeshHome", bssid = n1, rssiDbm = -46, frequencyMhz = 5180),
        )
        val spec = NavigationRobot(composeRule).openSpectrum().selectBand("5 GHz")
        spec.toggleExcludeOwnSsid()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Ch 36 clean once own mesh excluded", "36", "Optimal", "Wide Open")
    }

    @Test
    fun t3_18_f05_f15_f30_f35_includeDfsUnlocksUnii2RecommendationAndPersistsAcrossRotation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "Jam36", bssid = "04:D4:C4:73:18:01", rssiDbm = -43, frequencyMhz = 5180, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "Jam149", bssid = "B0:BE:76:73:18:02", rssiDbm = -44, frequencyMhz = 5745, centerFreq0Mhz = 5775),
                ScanResultFactory.createScanResult(ssid = "Jam165", bssid = "FC:EC:DA:73:18:03", rssiDbm = -46, frequencyMhz = 5825, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 5825),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("5 GHz").toggleIncludeDfs()
        robot.recreateApp()
        nav.assertTabSelected("Spectrum")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "DFS recommendation preserved after rotate", "52", "60", "100", "132", "DFS")
    }

    @Test
    fun t3_19_f10_f15_f18_f30_dualBandA55Spectrum6GhzTabCombinesHardwareExplanationWithRnrColocatedPscCards() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Asus6E_BeaconOn5G",
                    bssid = "04:D4:C4:73:19:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe()),
                ),
            ),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Hardware explanation", "dual-band", "6 GHz", "hardware")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RNR PSC 37 card", "37", "160 MHz", "PSC")
    }

    @Test
    fun t3_20_f11_f16_f24_f32_evilTwinAttackPropagatesWarningAcrossNetworksCardAndInsightsCriticalBanner() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "HQ_Secure_WiFi",
                    bssid = "00:40:96:73:20:01",
                    rssiDbm = -56,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "HQ_Secure_WiFi",
                    bssid = "DE:AD:BE:EF:73:20",
                    rssiDbm = -43,
                    capabilities = "[ESS]",
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("HQ_Secure_WiFi")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Networks card warning", "Evil Twin", "Spoof", "F", "Open")

        val insights = nav.openInsights()
        val score = insights.readOverallHealthScore()
        assertTrue("Evil-Twin attack must depress Health Score below 85, got $score", score < 85)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Insights Evil-Twin banner", "Evil Twin", "Spoof", "HQ_Secure_WiFi")
    }

    @Test
    fun t3_21_f15_f16_f30_f32_channelCongestionLowersInsightsHealthScoreAndSurfacesChannelRecommendation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "MyCongested24", bssid = "04:D4:C4:73:21:01", rssiDbm = -55, frequencyMhz = 2417, channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ, centerFreq0Mhz = 2427),
                ScanResultFactory.createScanResult(ssid = "Crowd1", bssid = "B0:BE:76:73:21:02", rssiDbm = -46, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "Crowd6", bssid = "FC:EC:DA:73:21:03", rssiDbm = -47, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyCongested24", bssid = "04:D4:C4:73:21:01", rssiDbm = -55, frequencyMhz = 2417),
        )
        val nav = NavigationRobot(composeRule)
        val insights = nav.openInsights()
        assertTrue(insights.readOverallHealthScore() < 85)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Insights recommends clean Ch 11", "11", "Channel")
    }

    @Test
    fun t3_22_f16_f17_f23_f32_savedRouterNicknameAndRedactedMacsAppearTogetherInSharedJsonAndMarkdown() {
        val rawBssid = "04:D4:C4:73:22:99"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RawISP_22", bssid = rawBssid, rssiDbm = -49)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("RawISP_22").assignNickname("Patio Mesh AP").dismiss()

        val insights = nav.openInsights()
        insights.toggleRedactMacs()
        insights.clickShareExport("JSON")

        val jsonPayload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertTrue("Shared JSON must include custom nickname 'Patio Mesh AP'", jsonPayload.resolvedBody.contains("Patio Mesh AP"))
        assertFalse("Shared JSON must mask raw BSSID $rawBssid", jsonPayload.resolvedBody.contains("73:22:99", ignoreCase = true))
        assertTrue("Shared JSON must contain XX:XX mask", jsonPayload.resolvedBody.contains("XX:XX", ignoreCase = true))
    }

    @Test
    fun t3_23_f14_f21_f31_f17_connectedMeshRoamRecordedInUiMatchesExportedJsonRoamEvents() {
        val n1 = "04:D4:C4:73:23:01"
        val n2 = "04:D4:C4:73:23:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ExportRoamMesh", bssid = n1, rssiDbm = -75, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "ExportRoamMesh", bssid = n2, rssiDbm = -48, frequencyMhz = 5180, channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ, centerFreq0Mhz = 5210),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "ExportRoamMesh", bssid = n1, rssiDbm = -75, frequencyMhz = 2412),
        )
        robot.advanceClockMs(1_500L)
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "ExportRoamMesh", bssid = n2, rssiDbm = -48, frequencyMhz = 5180),
        )
        robot.advanceClockMs(1_000L)

        val nav = NavigationRobot(composeRule)
        nav.openInsights().clickShareExport("JSON")
        val payload = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val root = CliRunnerHarness.lenientJson.parseToJsonElement(payload.resolvedBody.trim()) as kotlinx.serialization.json.JsonObject
        assertEquals("Exported JSON must contain 1 logged RoamEvent", 1, CliJsonLens.roamEvents(root).size)
    }

    @Test
    fun t3_24_f02_f03_f30_f32_amoledPixelsWcagAaaContrastAndZeroRawEnumsVerifiedOnSpectrumAndInsights() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "AmoledAudit_AX",
                    bssid = "04:D4:C4:73:24:01",
                    rssiDbm = -48,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T3_24-Spectrum")
        val (_, specBmp) = robot.captureRootBitmap("t3_24_spectrum_amoled.png")
        assertTrue(ComposeSemanticMatchers.peakContrastInRegion(specBmp) >= 7.0)

        nav.openInsights()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T3_24-Insights")
        val (_, insBmp) = robot.captureRootBitmap("t3_24_insights_amoled.png")
        assertTrue(ComposeSemanticMatchers.peakContrastInRegion(insBmp) >= 7.0)
        var blackPx = 0
        var total = 0
        for (y in 0 until insBmp.height step 10) {
            for (x in 0 until insBmp.width step 10) {
                total++
                if (insBmp.getPixel(x, y) == AndroidColor.BLACK) blackPx++
            }
        }
        assertTrue("Insights must render true #000000 AMOLED background", blackPx.toDouble() / total > 0.10)
    }
}
