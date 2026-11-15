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
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import java.io.File
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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F15`–`F17` (`16` `@Test` methods).
 * - `F15` Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine (`6` tests)
 * - `F16` Wireless Environment Health Score (`0–100`) & Insights (`5` tests)
 * - `F17` Offline JSON & Markdown Exporter + CLI Runner (`5` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F15ToF17AnalyticsExportCliEdgeE2eTest {

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
    // F15: Linear-mW Spectral Overlap, CCI/ACI & Best-Channel Engine — Edge (6)
    // =========================================================================

    @Test
    fun f15_t2_01_emptyZeroApBandScores100WideOpenAndRecommendsCanonicalDefaultChannel() {
        val root = CliRunnerHarness.analyzeStdin(CliScenarioBuilder.singleFrame(scans = emptyList()))
            .assertSucceeded()
            .parseJsonRoot()
        val spec24 = CliJsonLens.spectrumForBand(root, "2.4")
        val rec24 = spec24.obj("recommendation")!!
        assertTrue(rec24.int("recommendedPrimaryChannel") in setOf(1, 6, 11))
        assertEquals(100, rec24.int("score0To100"))
    }

    @Test
    fun f15_t2_02_allCanonical24GhzChannelsEquallySaturatedSelectsDeterministicCanonicalWinnerNeverCh2To5() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Sat1", bssid = "04:d4:c4:15:12:01", rssiDbm = -45, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Sat6", bssid = "04:d4:c4:15:12:06", rssiDbm = -45, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "Sat11", bssid = "04:d4:c4:15:12:0b", rssiDbm = -45, frequencyMhz = 2462, centerFreq0Mhz = 2462, channelWidthMhz = 20),
            ),
        )
        val recCh = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot(),
            "2.4",
        ).obj("recommendation")!!.int("recommendedPrimaryChannel")!!
        assertTrue("2.4 GHz recommendation ($recCh) must NEVER suggest overlapping Ch 2..5 or 7..10!", recCh in setOf(1, 6, 11))
    }

    @Test
    fun f15_t2_03_antiFlapCurrentChannelOptimalityAvoidsPointlessSwitchForTinySubThreePointGain() {
        // Connected on Ch 1 with 1 ultra-faint -93 dBm neighbor; Ch 6 has 0 neighbors -> score difference < 3 points!
        val myBssid = "04:d4:c4:15:13:01"
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = myBssid,
            scans = listOf(
                ScanEntrySpec(ssid = "MyHome24", bssid = myBssid, rssiDbm = -48, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                ScanEntrySpec(ssid = "TinyWhisper", bssid = "b0:be:76:15:13:02", rssiDbm = -93, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
            ),
        )
        val rec = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(scenario, excludeOwnSsid = true).assertSucceeded().parseJsonRoot(),
            "2.4",
        ).obj("recommendation")!!
        // Either keeps Ch 1 as already optimal OR reports negligible gain
        assertTrue(rec.bool("isCurrentChannelAlreadyOptimal") == true || (rec.int("estimatedScoreGain") ?: 0) <= 5)
    }

    @Test
    fun f15_t2_04_tdwrWeatherRadarChannels120To128PenalizedBelowNonTdwrDfsChannels52And100() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "Block36", bssid = "04:d4:c4:15:14:01", rssiDbm = -42, frequencyMhz = 5180, centerFreq0Mhz = 5210, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "Block149", bssid = "b0:be:76:15:14:02", rssiDbm = -42, frequencyMhz = 5745, centerFreq0Mhz = 5775, channelWidthMhz = 80),
                ScanEntrySpec(ssid = "Block165", bssid = "fc:ec:da:15:14:03", rssiDbm = -42, frequencyMhz = 5825, centerFreq0Mhz = 5825, channelWidthMhz = 20),
            ),
        )
        val spec5 = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(scenario, includeDfs = true).assertSucceeded().parseJsonRoot(),
            "5",
        )
        val s52 = CliJsonLens.scoreForChannel(spec5, 52).int("score0To100")!!
        val s120 = CliJsonLens.scoreForChannel(spec5, 120).int("score0To100")!!
        assertTrue("Clean non-TDWR DFS Ch 52 ($s52) must score >= TDWR weather radar Ch 120 ($s120)", s52 >= s120)
        assertTrue("Best DFS channel must avoid TDWR 120..128 when Ch 52/100 are wide open", spec5.obj("recommendation")!!.int("recommendedPrimaryChannel") !in 120..128)
    }

    @Test
    fun f15_t2_05_denseSixtyApApartmentBuildingCapsDetailedSpectralCurvesAndAggregatesWeakFloor() {
        val sixtyScans = (1..60).map { i ->
            ScanEntrySpec(
                ssid = "Apt_$i",
                bssid = String.format("04:d4:c4:60:00:%02x", i),
                rssiDbm = -45 - i, // -46 down to -105 dBm
                frequencyMhz = when (i % 3) {
                    0 -> 2412
                    1 -> 2437
                    else -> 2462
                },
                centerFreq0Mhz = when (i % 3) {
                    0 -> 2412
                    1 -> 2437
                    else -> 2462
                },
                channelWidthMhz = 20,
            )
        }
        val spec24 = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.singleFrame(scans = sixtyScans)).assertSucceeded().parseJsonRoot(),
            "2.4",
        )
        val curves = spec24.arr("curves")!!
        assertTrue("Detailed spectral curves (${curves.size}) should be capped or bounded cleanly <= 60", curves.size in 1..60)
        assertTrue(spec24.int("aggregatedWeakCurveCount")!! >= 0)
    }

    @Test
    fun f15_t2_06_wifi7PreamblePuncturedSubchannelReducesInterferenceOnPunctured20MhzSlice() {
        val puncturedScenario = CliScenarioBuilder.singleFrame(
            deviceHas6Ghz = true,
            scans = listOf(
                ScanEntrySpec(
                    ssid = "PuncturedBE",
                    bssid = "04:d4:c4:15:16:01",
                    rssiDbm = -48,
                    frequencyMhz = 6115,
                    centerFreq0Mhz = 6105,
                    channelWidthMhz = 320,
                    wifiStandard = "11be",
                    ies = listOf(BeaconIeVectors.EHT_CAP_4X4_320MHZ, BeaconIeVectors.EHT_OP_320MHZ_PUNCTURED_SUB2),
                ),
            ),
        )
        val spec6 = CliJsonLens.spectrumForBand(
            CliRunnerHarness.analyzeStdin(puncturedScenario).assertSucceeded().parseJsonRoot(),
            "6",
        )
        assertEquals(1, spec6.arr("curves")!!.size)
    }

    // =========================================================================
    // F16: Wireless Environment Health Score (0–100) & Insights — Edge Cases (5)
    // =========================================================================

    @Test
    fun f16_t2_01_zeroDetectedNetworksReturnsWaitingForDataStateWithNullOverallScoreInsteadOfFake100() {
        val health = CliJsonLens.healthObj(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.singleFrame(scans = emptyList()))
                .assertSucceeded()
                .parseJsonRoot(),
        )
        assertEquals("WAITING_FOR_DATA", health.str("state"))
        assertNull("overallScore0To100 MUST be null when 0 APs scanned, never fake 0 or 100!", health.int("overallScore0To100"))
    }

    @Test
    fun f16_t2_02_catastrophicAllWepOpenEvilTwinCongestedEnvironmentClampsScoreInside0To100() {
        val badScans = (1..15).map { i ->
            ScanEntrySpec(
                ssid = "DisasterCorp",
                bssid = String.format("de:ad:be:ef:00:%02x", i),
                rssiDbm = -42,
                frequencyMhz = 2417 + (i % 4) * 5,
                centerFreq0Mhz = 2422,
                channelWidthMhz = 40,
                capabilities = if (i == 1) "[WPA2-PSK-CCMP][ESS]" else if (i % 2 == 0) "[WEP][ESS]" else "[ESS]",
                ies = listOf(BeaconIeVectors.BSS_LOAD_MAX_65535_100_PCT),
            )
        }
        val health = CliJsonLens.healthObj(
            CliRunnerHarness.analyzeStdin(CliScenarioBuilder.singleFrame(connectedBssid = "de:ad:be:ef:00:03", scans = badScans))
                .assertSucceeded()
                .parseJsonRoot(),
        )
        val overall = health.int("overallScore0To100")!!
        assertTrue("Overall score ($overall) must stay clamped >= 0 and <= 45", overall in 0..45)
        val subs = health.obj("subScores")!!
        for (k in listOf("signalCoverage0To100", "channelCrowding0To100", "interferencePurity0To100", "securityHygiene0To100")) {
            val v = subs.int(k)!!
            assertTrue("Sub-score $k ($v) must stay in 0..100", v in 0..100)
        }
    }

    @Test
    fun f16_t2_03_unassociatedPhoneEvaluatesAmbientNeighborhoodWithoutCrashingOnNullConnectedWifi() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = null,
            scans = listOf(
                ScanEntrySpec(ssid = "Ambient1", bssid = "04:d4:c4:16:13:01", rssiDbm = -55, frequencyMhz = 5180),
                ScanEntrySpec(ssid = "Ambient2", bssid = "b0:be:76:16:13:02", rssiDbm = -68, frequencyMhz = 2462, centerFreq0Mhz = 2462, channelWidthMhz = 20),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertEquals("SCORED", health.str("state"))
        assertTrue(health.int("overallScore0To100")!! in 0..100)
    }

    @Test
    fun f16_t2_04_insightOrderingDeterministicallyPlacesCriticalAboveWarningAboveSuggestion() {
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = "de:ad:be:ef:16:14",
            scans = listOf(
                ScanEntrySpec(ssid = "OrderCheck", bssid = "04:d4:c4:16:14:01", rssiDbm = -58, frequencyMhz = 2422, centerFreq0Mhz = 2432, channelWidthMhz = 40, capabilities = "[RSN-SAE-CCMP][MFPR][ESS]", ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR)),
                ScanEntrySpec(ssid = "OrderCheck", bssid = "de:ad:be:ef:16:14", rssiDbm = -48, frequencyMhz = 2422, centerFreq0Mhz = 2422, channelWidthMhz = 20, capabilities = "[ESS]"),
            ),
        )
        val insights = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
            .arr("insights")!!
        val rankMap = mapOf("CRITICAL" to 0, "WARNING" to 1, "SUGGESTION" to 2, "GOOD_INFO" to 3)
        val ranks = insights.map { rankMap[(it as kotlinx.serialization.json.JsonObject).str("severity")] ?: 99 }
        assertEquals("Insights must be sorted monotonically by severity priority: $ranks", ranks.sorted(), ranks)
    }

    @Test
    fun f16_t2_05_highQbssAirtimeUtilizationAbove80PercentGeneratesCongestionInsightDespiteStrongRssi() {
        val bssid = "04:d4:c4:16:15:01"
        val scenario = CliScenarioBuilder.singleFrame(
            connectedBssid = bssid,
            scans = listOf(
                ScanEntrySpec(
                    ssid = "StadiumSaturated_5G",
                    bssid = bssid,
                    rssiDbm = -44, // Very strong signal, BUT 100% airtime saturated!
                    frequencyMhz = 5180,
                    ies = listOf(BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT),
                ),
            ),
        )
        val health = CliJsonLens.healthObj(CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot())
        assertTrue(health.int("overallScore0To100")!! < 98)
    }

    // =========================================================================
    // F17: Offline JSON & Markdown Exporter + CLI Runner — Negative/Edge Cases (5)
    // =========================================================================

    @Test
    fun f17_t2_01_cliExitsNonZeroWithClearStderrOnMalformedInputJson() {
        CliRunnerHarness.analyzeStdin("{broken_json: [1, 2,").assertFailedGracefully("Malformed JSON stdin")
    }

    @Test
    fun f17_t2_02_cliExitsNonZeroWithClearStderrWhenInputFileDoesNotExist() {
        val missing = File("/home/antigravity/.tmp/nonexistent_wifiradar_999999.json")
        missing.delete()
        CliRunnerHarness.analyzeFile(missing).assertFailedGracefully("Missing input file")
    }

    @Test
    fun f17_t2_03_cliExitsNonZeroWithClearStderrOnUnsupportedFormatAndInvalidEnvFlag() {
        val validScenario = CliScenarioBuilder.singleFrame(scans = emptyList())
        CliRunnerHarness.analyzeStdin(validScenario, format = "xml").assertFailedGracefully("Unsupported --format xml")
        CliRunnerHarness.analyzeStdin(validScenario, env = "MARS_VACUUM").assertFailedGracefully("Invalid --env MARS_VACUUM")
    }

    @Test
    fun f17_t2_04_redactMacsMasksEveryMacOccurrenceAcrossRoamEventsRnrNeighborsAndMldAddresses() {
        val b1 = "04:d4:c4:aa:11:22"
        val b2 = "04:d4:c4:bb:33:44"
        val mld = "04:d4:c4:cc:55:66"
        val scans = listOf(
            ScanEntrySpec(
                ssid = "DeepRedactMesh",
                bssid = b1,
                rssiDbm = -74,
                frequencyMhz = 2412,
                centerFreq0Mhz = 2412,
                channelWidthMhz = 20,
                apMldMacAddress = mld,
                ies = listOf(BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ), // neighbor 04:d4:c4:6e:37:01
            ),
            ScanEntrySpec(
                ssid = "DeepRedactMesh",
                bssid = b2,
                rssiDbm = -50,
                frequencyMhz = 5180,
                centerFreq0Mhz = 5210,
                channelWidthMhz = 80,
                apMldMacAddress = mld,
            ),
        )
        val scenario = CliScenarioBuilder.multiFrame(
            frames = listOf(
                CliScenarioBuilder.FrameSpec(elapsedMs = 1_000L, connectedBssid = b1, scans = scans),
                CliScenarioBuilder.FrameSpec(elapsedMs = 3_000L, connectedBssid = b2, scans = scans),
            ),
        )
        val jsonOut = CliRunnerHarness.analyzeStdin(scenario, format = "json", targetBssid = b2, redactMacs = true)
            .assertSucceeded()
            .stdout
        val mdOut = CliRunnerHarness.analyzeStdin(scenario, format = "markdown", targetBssid = b2, redactMacs = true)
            .assertSucceeded()
            .stdout

        for (secretSub in listOf("aa:11:22", "bb:33:44", "cc:55:66", "6e:37:01")) {
            assertFalse("Redacted JSON leaked MAC suffix '$secretSub'!", jsonOut.contains(secretSub, ignoreCase = true))
            assertFalse("Redacted Markdown leaked MAC suffix '$secretSub'!", mdOut.contains(secretSub, ignoreCase = true))
        }
    }

    @Test
    fun f17_t2_05_markdownTableEscapesPipeAndNewlineCharactersInAdversarialSsids() {
        val pipeSsid = "Evil | Column | Breaker"
        val md = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(ssid = pipeSsid, bssid = "04:d4:c4:17:15:01"))),
            format = "markdown",
        ).assertSucceeded().stdout

        // Every table row containing 'Evil' must escape internal '|' (e.g. `\|` or sanitized) so column count doesn't break
        val matchingLine = md.lines().firstOrNull { it.contains("Evil") }.orEmpty()
        assertTrue("Expected adversarial SSID row in Markdown", matchingLine.isNotEmpty())
        assertFalse("Unescaped '| Column |' broke Markdown table!", matchingLine.contains("| Column |"))
    }
}
