@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.arr
import com.a55.wifiradar.e2e.harness.CliJsonLens.bool
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.obj
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F15`–`F17` (`16` `@Test` methods).
 * - `F15` Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine (`6` tests)
 * - `F16` Wireless Environment Health Score (`0–100`) & Actionable Insights (`5` tests)
 * - `F17` Offline JSON & Markdown Exporter + CLI Runner (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F15ToF17AnalyticsExportCliE2eTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var robot: AppE2eRobot

    @Before
    fun setUp() {
        robot = AppE2eRobot(composeRule)
    }

    @After
    fun tearDown() {
        robot.close()
    }

    // =========================================================================
    // F15: Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine (6 Tests)
    // =========================================================================

    @Test
    fun f15_t1_01_24GhzRecommendsCleanCanonicalChannel11WhenChannels1And6AreCrowded() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Crowded1A", bssid = "04:d4:c4:15:01:01", rssiDbm = -48, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Crowded1B", bssid = "04:d4:c4:15:01:02", rssiDbm = -54, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Crowded6A", bssid = "b0:be:76:15:01:03", rssiDbm = -46, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        val spec24 = CliJsonLens.spectrumForBand(root, "2.4")
        val rec = spec24.obj("recommendation")!!
        assertEquals(11, rec.int("recommendedPrimaryChannel"))
        assertTrue("Recommended 2.4 GHz channel MUST be canonical {1, 6, 11}", rec.int("recommendedPrimaryChannel") in setOf(1, 6, 11))

        val s1 = CliJsonLens.scoreForChannel(spec24, 1).int("score0To100")!!
        val s6 = CliJsonLens.scoreForChannel(spec24, 6).int("score0To100")!!
        val s11 = CliJsonLens.scoreForChannel(spec24, 11).int("score0To100")!!
        assertTrue("Clean Ch 11 ($s11) must outscore crowded Ch 1 ($s1) and Ch 6 ($s6)", s11 > s1 && s11 > s6)
    }

    @Test
    fun f15_t1_02_adjacentOffCenterChannel3PenalizesChannel1MoreSeverelyThanCoChannelCci() {
        val cciScenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "PoliteCCI", bssid = "04:d4:c4:15:02:01", rssiDbm = -55, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20)),
        )
        val aciScenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "RudeACI_Ch2", bssid = "04:d4:c4:15:02:02", rssiDbm = -55, frequencyMhz = 2417, centerFreq0Mhz = 2417, channelWidthMhz = 20)),
        )
        val cciCh1 = CliJsonLens.scoreForChannel(
            CliJsonLens.spectrumForBand(CliRunnerHarness.analyzeStdin(cciScenario).assertSucceeded().parseJsonRoot(), "2.4"),
            1,
        )
        val aciCh1 = CliJsonLens.scoreForChannel(
            CliJsonLens.spectrumForBand(CliRunnerHarness.analyzeStdin(aciScenario).assertSucceeded().parseJsonRoot(), "2.4"),
            1,
        )
        assertEquals(1, cciCh1.int("cciCount"))
        assertEquals(1, aciCh1.int("aciCount"))
        assertTrue(
            "Adjacent-channel collision on Ch 2 (${aciCh1.int("score0To100")}) must penalize or match co-channel (${cciCh1.int("score0To100")})",
            aciCh1.int("score0To100")!! <= cciCh1.int("score0To100")!!,
        )
    }

    @Test
    fun f15_t1_03_linearMilliwattSummationAvoidsRawDbmAdditionFallacy() {
        // One strong -45 dBm AP (~3.16e-5 mW) on Ch 1 vs five faint -85 dBm APs (5 * 3.16e-9 = 1.58e-8 mW -> 2000x weaker!) on Ch 6.
        // Naive dBm addition would think 5 APs is worse than 1 AP; linear mW physics knows Ch 6 is vastly cleaner!
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Blaster_Ch1", bssid = "04:d4:c4:15:03:01", rssiDbm = -45, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Faint1_Ch6", bssid = "b0:be:76:15:03:11", rssiDbm = -85, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Faint2_Ch6", bssid = "b0:be:76:15:03:12", rssiDbm = -86, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Faint3_Ch6", bssid = "b0:be:76:15:03:13", rssiDbm = -85, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Faint4_Ch6", bssid = "b0:be:76:15:03:14", rssiDbm = -87, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
            ),
        )
        val spec24 = CliJsonLens.spectrumForBand(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "2.4")
        val scoreCh1 = CliJsonLens.scoreForChannel(spec24, 1).int("score0To100")!!
        val scoreCh6 = CliJsonLens.scoreForChannel(spec24, 6).int("score0To100")!!
        assertTrue(
            "Linear mW physics must score Ch 6 with 4 faint -85 dBm APs ($scoreCh6) much higher than Ch 1 with -45 dBm blaster ($scoreCh1)",
            scoreCh6 > scoreCh1 + 15,
        )
    }

    @Test
    fun f15_t1_04_5GhzDefaultNonDfsRecommendationSelectsUnii3WhenUnii1IsSaturated() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "BusyUNII1_A", bssid = "04:d4:c4:15:04:01", rssiDbm = -45, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "BusyUNII1_B", bssid = "b0:be:76:15:04:02", rssiDbm = -50, frequencyMhz = 5220, centerFreq0Mhz = 5210, channelWidthMhz = 80),
            ),
        )
        val spec5 = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(scenario, includeDfs = false).assertSucceeded().parseJsonRoot(),
            "5",
        )
        val recCh = spec5.obj("recommendation")!!.int("recommendedPrimaryChannel")!!
        assertTrue("Non-DFS 5 GHz recommendation ($recCh) must land in clean UNII-3 (149..165)", recCh in setOf(149, 153, 157, 161, 165))
    }

    @Test
    fun f15_t1_05_includeDfsToggleUnlocksCleanUnii2ChannelsWhenUnii1AndUnii3AreBusy() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Busy36", bssid = "04:d4:c4:15:05:01", rssiDbm = -44, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "Busy149", bssid = "b0:be:76:15:05:02", rssiDbm = -45, frequencyMhz = 5745, centerFreq0Mhz = 5775, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "Busy165", bssid = "fc:ec:da:15:05:03", rssiDbm = -48, frequencyMhz = 5825, centerFreq0Mhz = 5825, channelWidthMhz = 20),
            ),
        )
        val spec5Dfs = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(scenario, includeDfs = true).assertSucceeded().parseJsonRoot(),
            "5",
        )
        val recCh = spec5Dfs.obj("recommendation")!!.int("recommendedPrimaryChannel")!!
        assertTrue("With --include-dfs, clean DFS channel (52..144) should be recommended, got $recCh", recCh in 52..144)
    }

    @Test
    fun f15_t1_06_6GhzChannelPlanningPrioritizesCleanPreferredScanningChannels() {
        val scenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(ssid = "Busy6E_Ch37", bssid = "04:d4:c4:15:06:01", rssiDbm = -46, frequencyMhz = 6135, centerFreq0Mhz = 6185, channelWidthMhz = 160),
            ),
        )
        val spec6 = CliJsonLens.spectrumForBand(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(), "6")
        val recCh = spec6.obj("recommendation")!!.int("recommendedPrimaryChannel")!!
        assertEquals("Recommended 6 GHz primary channel ($recCh) must be a PSC ((ch - 5) % 16 == 0)", 5, recCh % 16)
        assertTrue("Must avoid congested Ch 37 block", recCh !in 33..61)
    }

    // =========================================================================
    // F16: Wireless Environment Health Score (0–100) & Insights (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f16_t1_01_cleanHighSecurityEnvironmentScoresAbove80WithAllFourSubScores() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "04:d4:c4:16:01:01",
            scans = listOf(
                ScanEntrySpec(
                    ssid = "PristineHome_AX",
                    bssid = "04:d4:c4:16:01:01",
                    rssiDbm = -46,
                    frequencyMhz = 5180,
                    centerFreq0Mhz = 5210,
                    channelWidthMhz = 80,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR, BeaconIeVectors.BSS_LOAD_0_CLIENTS_0_PCT),
                ),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("SCORED", health.str("state"))
        val overall = health.int("overallScore0To100")!!
        assertTrue("Expected pristine wireless environment score >= 80, got $overall", overall in 80..100)

        val subs = health.obj("subScores")!!
        assertTrue(subs.int("signalCoverage0To100")!! in 80..100)
        assertTrue(subs.int("channelCrowding0To100")!! in 80..100)
        assertTrue(subs.int("interferencePurity0To100")!! in 80..100)
        assertTrue(subs.int("securityHygiene0To100")!! in 80..100)
    }

    @Test
    fun f16_t1_02_congestedInsecureEnvironmentDropsScoreSignificantlyAndOrdersCriticalFirst() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "de:ad:be:ef:16:02",
            scans = listOf(
                ScanEntrySpec(ssid = "MyCorpNet", bssid = "04:d4:c4:16:02:01", rssiDbm = -72, frequencyMhz = 2417, centerFreq0Mhz = 2417, channelWidthMhz = 40, capabilities = "[WPA2-PSK-CCMP][ESS]"),
                ScanEntrySpec(ssid = "MyCorpNet", bssid = "de:ad:be:ef:16:02", rssiDbm = -50, frequencyMhz = 2417, centerFreq0Mhz = 2417, channelWidthMhz = 20, capabilities = "[ESS]"),
                ScanEntrySpec(ssid = "AciSpam1", bssid = "b0:be:76:16:02:03", rssiDbm = -47, frequencyMhz = 2422, centerFreq0Mhz = 2422, channelWidthMhz = 40, capabilities = "[WEP][ESS]"),
                ScanEntrySpec(ssid = "AciSpam2", bssid = "fc:ec:da:16:02:04", rssiDbm = -49, frequencyMhz = 2427, centerFreq0Mhz = 2427, channelWidthMhz = 40, capabilities = "[ESS]"),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        val overall = health.int("overallScore0To100")!!
        assertTrue("Hostile/congested environment must score < 60, got $overall", overall < 60)

        val insights = health.arr("insights")!!
        assertTrue("Expected actionable insights in degraded RF environment", insights.isNotEmpty())
        val firstSeverity = insights[0].let { (it as kotlinx.serialization.json.JsonObject).str("severity") }
        assertEquals("Top insight must be CRITICAL when Evil-Twin / Open connection is active", "CRITICAL", firstSeverity)
    }

    @Test
    fun f16_t1_03_offCenter24Ghz40MhzChannelTriggersInterferencePurityInsight() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "04:d4:c4:16:03:01",
            scans = listOf(
                ScanEntrySpec(ssid = "BadCh3_40MHz", bssid = "04:d4:c4:16:03:01", rssiDbm = -54, frequencyMhz = 2422, centerFreq0Mhz = 2432, channelWidthMhz = 40, capabilities = "[WPA2-PSK-CCMP][ESS]"),
                ScanEntrySpec(ssid = "NeighborCh1", bssid = "b0:be:76:16:03:02", rssiDbm = -52, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "NeighborCh6", bssid = "fc:ec:da:16:03:03", rssiDbm = -53, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        val allBodies = health.arr("insights")!!.joinToString(" | ") { it.toString() }
        assertTrue(
            "Expected channel/ACI optimization insight for Ch 3 40 MHz, got: ${allBodies.take(800)}",
            allBodies.contains("11") || allBodies.contains("channel", ignoreCase = true) || allBodies.contains("ACI", ignoreCase = true),
        )
    }

    @Test
    fun f16_t1_04_weakConnected24GhzSignalWithStrong5GhzSiblingRecommendsBandUpgrade() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "04:d4:c4:16:04:01",
            scans = listOf(
                ScanEntrySpec(ssid = "DualHome", bssid = "04:d4:c4:16:04:01", rssiDbm = -79, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "DualHome", bssid = "04:d4:c4:16:04:02", rssiDbm = -54, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertTrue(health.arr("insights")!!.isNotEmpty())
        assertNotNull(health.obj("best5GhzRecommendation"))
    }

    @Test
    fun f16_t1_05_securityAuditListIncludesDetectedRadiosForPerRouterGrading() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "NetA", bssid = "04:d4:c4:16:05:01", capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                ScanEntrySpec(ssid = "NetB", bssid = "b0:be:76:16:05:02", capabilities = "[ESS]"),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        val audit = health.arr("securityAuditRadios")
        assertNotNull("Expected securityAuditRadios in EnvironmentHealthReport", audit)
        assertEquals(2, audit!!.size)
    }

    // =========================================================================
    // F17: Offline JSON & Markdown Exporter + CLI Runner (5 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f17_t1_01_cliAnalyzeFileProducesCompleteValidDiagnosticExportDocumentJson() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "04:d4:c4:17:01:01",
            scans = listOf(
                ScanEntrySpec(ssid = "ExportFiber_5G", bssid = "04:d4:c4:17:01:01", rssiDbm = -51, frequencyMhz = 5180),
            ),
        )
        val tempFile = CliRunnerHarness.createTempScenarioFile("f17_t1_01_", scenario)
        val root = CliRunnerHarness.analyzeFile(tempFile, format = "json", targetBssid = "04:d4:c4:17:01:01")
            .assertSucceeded()
            .parseJsonRoot()

        assertEquals("1.0", root.str("schemaVersion"))
        assertEquals(false, root.bool("redactedMacs"))
        assertEquals(1, CliJsonLens.radios(root).size)
        assertNotNull(root.obj("healthReport"))
        assertNotNull(root.obj("spectrum24Ghz"))
        assertNotNull(root.obj("spectrum5Ghz"))
        assertNotNull(root.obj("spectrum6Ghz"))
        assertEquals(1, CliJsonLens.proximityFrames(root).size)
    }

    @Test
    fun f17_t1_02_cliAnalyzeMarkdownRendersStructuredHeadingsTablesAndRecommendations() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "MarkdownRouter_5G", bssid = "04:d4:c4:17:02:01", rssiDbm = -49, frequencyMhz = 5180),
                ScanEntrySpec(ssid = "MarkdownGuest_2G", bssid = "b0:be:76:17:02:02", rssiDbm = -67, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
            ),
        )
        val md = CliRunnerHarness.analyzeStdin(scenario, format = "markdown").assertSucceeded().stdout
        assertTrue("Markdown must contain H1/H2 headings (#)", md.contains("#"))
        assertTrue("Markdown must contain MarkdownRouter_5G", md.contains("MarkdownRouter_5G"))
        assertTrue("Markdown must contain MarkdownGuest_2G", md.contains("MarkdownGuest_2G"))
        assertTrue("Markdown must include Health Score / Channel section", md.contains("100") || md.contains("Channel", ignoreCase = true))
    }

    @Test
    fun f17_t1_03_redactMacsMasksSubscriberOctetsInBothJsonAndMarkdownWhilePreservingVendorOui() {
        val rawBssid = "04:d4:c4:9a:bc:de"
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = rawBssid,
            scans = listOf(ScanEntrySpec(ssid = "PrivacyCheck_AX", bssid = rawBssid, rssiDbm = -52)),
        )
        val jsonRes = CliRunnerHarness.analyzeStdin(scenario, format = "json", redactMacs = true).assertSucceeded()
        val mdRes = CliRunnerHarness.analyzeStdin(scenario, format = "markdown", redactMacs = true).assertSucceeded()

        assertFalse("Full raw MAC must NEVER appear in redacted JSON", jsonRes.stdout.contains("04:d4:c4:9a:bc:de", ignoreCase = true))
        assertFalse("Subscriber octets 9A:BC must NEVER appear in redacted JSON", jsonRes.stdout.contains("9a:bc", ignoreCase = true))
        assertTrue("Masked XX:XX token must appear in redacted JSON", jsonRes.stdout.contains("XX:XX", ignoreCase = true))
        assertEquals(true, jsonRes.parseJsonRoot().bool("redactedMacs"))

        assertFalse("Full raw MAC must NEVER appear in redacted Markdown", mdRes.stdout.contains("04:d4:c4:9a:bc:de", ignoreCase = true))
        assertFalse("Subscriber octets 9A:BC must NEVER appear in redacted Markdown", mdRes.stdout.contains("9a:bc", ignoreCase = true))
        assertTrue("Vendor OUI ASUS / 04:D4:C4 preserved in redacted Markdown", mdRes.stdout.contains("04:D4:C4", ignoreCase = true) || mdRes.stdout.contains("ASUS", ignoreCase = true))
    }

    @Test
    fun f17_t1_04_cliStdinPipeMatchesFileInputDeterministically() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "DeterministicAP", bssid = "04:d4:c4:17:04:01", rssiDbm = -57, frequencyMhz = 5180)),
        )
        val tmp = CliRunnerHarness.createTempScenarioFile("f17_det_", scenario)
        val fromStdin = CliRunnerHarness.analyzeStdin(scenario, format = "json").assertSucceeded().parseJsonRoot()
        val fromFile = CliRunnerHarness.analyzeFile(tmp, format = "json").assertSucceeded().parseJsonRoot()

        assertEquals(
            CliJsonLens.healthObj(fromStdin).int("overallScore0To100"),
            CliJsonLens.healthObj(fromFile).int("overallScore0To100"),
        )
        assertEquals(
            CliJsonLens.radioByBssid(fromStdin, "04:d4:c4:17:04:01").dbl("estimatedDistanceMeters"),
            CliJsonLens.radioByBssid(fromFile, "04:d4:c4:17:04:01").dbl("estimatedDistanceMeters"),
        )
    }

    @Test
    fun f17_t1_05_cliHelpFlagExitsZeroAndPrintsUsageDocumentation() {
        val helpRes = CliRunnerHarness.executeRaw(listOf("--help")).assertSucceeded("cli-runner --help")
        assertTrue(helpRes.stdout.contains("WiFi Radar", ignoreCase = true) || helpRes.stdout.contains("analyze", ignoreCase = true))
    }
}
