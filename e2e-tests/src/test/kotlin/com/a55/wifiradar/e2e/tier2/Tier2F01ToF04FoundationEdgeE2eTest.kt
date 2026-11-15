@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.net.wifi.ScanResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.a55.wifiradar.e2e.harness.ApkBinaryInspector
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.LegendPillRobot
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
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
 * Tier 2 Boundary, Corner & Negative E2E Tests for Features `F01`–`F04` (`24` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F01ToF04FoundationEdgeE2eTest {

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
    // F01: Toolchain, Dual-CA Truststore & 0-Swap Build Setup — Edge Cases (6)
    // =========================================================================

    @Test
    fun f01_t2_01_envShIdempotentAcrossTripleSourceWithoutDuplicatingTruststoreFlag() {
        val envSh = File(ApkBinaryInspector.projectRoot, "scripts/env.sh")
        val proc = ProcessBuilder(
            "bash",
            "-c",
            "source '${envSh.absolutePath}' && source '${envSh.absolutePath}' && source '${envSh.absolutePath}' && printf '%s' \"\$JAVA_TOOL_OPTIONS\"",
        ).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals(0, proc.waitFor())
        val occurrences = Regex("""javax\.net\.ssl\.trustStore=""").findAll(out).count()
        assertEquals("Triple-sourcing env.sh must append trustStore flag at most once, got: $out", 1, occurrences)
    }

    @Test
    fun f01_t2_02_runE2eScriptRejectsInvalidTierAndUnknownFlagWithExitCode2AndStderr() {
        val runner = File(ApkBinaryInspector.projectRoot, "e2e-tests/run_e2e.sh")
        val badTier = ProcessBuilder("bash", runner.absolutePath, "--tier", "99").start()
        val badTierErr = badTier.errorStream.bufferedReader().readText()
        assertEquals(2, badTier.waitFor())
        assertTrue("Expected stderr explanation on --tier 99, got '$badTierErr'", badTierErr.isNotBlank())

        val badFlag = ProcessBuilder("bash", runner.absolutePath, "--nonexistent-flag").start()
        val badFlagErr = badFlag.errorStream.bufferedReader().readText()
        assertEquals(2, badFlag.waitFor())
        assertTrue("Expected stderr explanation on unknown flag, got '$badFlagErr'", badFlagErr.isNotBlank())
    }

    @Test
    fun f01_t2_03_e2eTestsBuildGradleEnforcesZeroProjectDependenciesAndExcludesNoDaemonFromRunner() {
        val buildKts = File(ApkBinaryInspector.projectRoot, "e2e-tests/build.gradle.kts").readText()
        val depsBlock = buildKts.substringAfter("dependencies {").substringBefore("\n}")
        assertFalse(
            ":e2e-tests dependencies {} MUST NOT declare any project(...) compile dependency:\n$depsBlock",
            Regex("""^\s*(testImplementation|implementation|api)\s*\(\s*project\s*\(""", RegexOption.MULTILINE)
                .containsMatchIn(depsBlock),
        )

        val runSh = File(ApkBinaryInspector.projectRoot, "e2e-tests/run_e2e.sh").readText()
        val executableLines = runSh.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertFalse("run_e2e.sh must NEVER pass --no-daemon on 0-swap VM", executableLines.contains("--no-daemon"))
    }

    @Test
    fun f01_t2_04_truststoreCertificatesAreCurrentlyValidAndNonExpired() {
        val ks = KeyStore.getInstance("PKCS12")
        File("/home/antigravity/.android-sdk/cacerts.p12").inputStream().use { ks.load(it, "changeit".toCharArray()) }
        var x509Count = 0
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val cert = ks.getCertificate(aliases.nextElement()) as? X509Certificate ?: continue
            x509Count++
            cert.checkValidity() // throws CertificateExpiredException / CertificateNotYetValidException if invalid!
        }
        assertTrue("Expected >= 2 valid X.509 certificates in cacerts.p12", x509Count >= 2)
    }

    @Test
    fun f01_t2_05_diskBackedTmpDirExistsAndIsWritableWithoutConsumingRootTmpfs() {
        val tmp = File("/home/antigravity/.tmp")
        assertTrue("/home/antigravity/.tmp must exist and be writable", tmp.isDirectory && tmp.canWrite())
        val probe = File.createTempFile("f01_t2_probe_", ".tmp", tmp)
        try {
            probe.writeText("xfs_tmp_check")
            assertEquals("xfs_tmp_check", probe.readText())
        } finally {
            probe.delete()
        }
    }

    @Test
    fun f01_t2_06_zeroSourceFilesContainForbiddenInternalImportStatementsAcrossE2eTests() {
        val srcRoot = File(ApkBinaryInspector.projectRoot, "e2e-tests/src")
        val forbiddenImport = Regex("""^\s*import\s+com\.a55\.wifiradar\.(core|feature|cli|MainActivity)""", RegexOption.MULTILINE)
        val violations = srcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { forbiddenImport.containsMatchIn(it.readText()) }
            .map { it.relativeTo(srcRoot).path }
            .toList()
        assertTrue("Forbidden internal imports found in :e2e-tests: $violations", violations.isEmpty())
    }

    // =========================================================================
    // F02: Galaxy A55 5G True-AMOLED 120Hz Design System — Edge Cases (6)
    // =========================================================================

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun f02_t2_01_accessibility200PercentFontScaleKeepsAllFourBottomTabLabelsVisibleAndNonOverlapping() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LargeFontRouter_5G", bssid = "04:D4:C4:02:01:01", rssiDbm = -52)),
        )
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        var prevRight = -1f
        for (tab in listOf("Networks", "Proximity", "Spectrum", "Insights")) {
            composeRule.onNodeWithText(tab).assertIsDisplayed()
            val b = composeRule.onNodeWithTag("radar_bottom_tab_${tab.lowercase()}").fetchSemanticsNode().boundsInRoot
            assertTrue("At fontScale=2.0f tab '$tab' must stay inside [0, $rootWidth]: $b", b.left >= -1f && b.right <= rootWidth + 1f)
            assertTrue("At fontScale=2.0f tab '$tab' must not overlap neighbor ($prevRight vs ${b.left})", b.left >= prevRight - 1.5f)
            prevRight = b.right
        }
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("LargeFontRouter_5G")
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-450dpi")
    fun f02_t2_02_landscapeViewport780x360dpRemainsScrollableAndReachableAcrossAllFourTabs() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LandscapeRouter_5G", bssid = "04:D4:C4:02:02:01", rssiDbm = -49)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("LandscapeRouter_5G")
        nav.openProximity().scrollUntilVisible("Calibrate", "Haptic")
        nav.openSpectrum().scrollUntilVisible("Channel", "5 GHz")
        nav.openInsights().scrollUntilVisible("Share", "Security")
    }

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl-w360dp-h780dp-450dpi")
    fun f02_t2_03_rightToLeftLocaleMirrorsLayoutCleanlyWhileKeepingTechnicalUnitsLegible() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RtlCheck_AX", bssid = "04:D4:C4:02:03:01", rssiDbm = -51, frequencyMhz = 5180)),
        )
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val topBar = composeRule.onNodeWithTag("radar_top_bar").fetchSemanticsNode().boundsInRoot
        assertTrue("RTL top bar must stay within root width: $topBar", topBar.left >= -1f && topBar.right <= rootWidth + 1f)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Technical dBm/GHz intact in RTL", "-51 dBm", "5 GHz")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F02-RTL")
    }

    @Test
    @Config(qualifiers = "w389dp-h842dp-420dpi")
    fun f02_t2_04_samsungOneUi389dpNativeDensityRendersCleanlyWithoutClipping() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "OneUi389_5G", bssid = "04:D4:C4:02:04:01", rssiDbm = -47)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("OneUi389_5G")
        val (_, bmp) = robot.captureRootBitmap("f02_t2_04_oneui_389dp.png")
        assertTrue("Expected crisp native width > 950px at 389dp/420dpi, got ${bmp.width}", bmp.width > 950)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-400dpi")
    fun f02_t2_05_samsungOneUiSmallScreenZoom411dpRendersCleanlyAcrossTabs() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "OneUi411_5G", bssid = "04:D4:C4:02:05:01", rssiDbm = -54)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("OneUi411_5G")
        nav.openProximity()
        nav.openSpectrum()
        nav.openInsights()
    }

    @Test
    fun f02_t2_06_maximum32ByteNoSpaceSsidWrapsOrEllipsizesWithoutPushingButtonsOffScreen() {
        val max32NoSpaces = "W".repeat(32)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = max32NoSpaces, bssid = "04:D4:C4:02:06:01", rssiDbm = -50)),
        )
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().launchProximityFor(max32NoSpaces)
        nav.assertTabSelected("Proximity")
        assertTrue(composeRule.onNodeWithTag("radar_top_bar").fetchSemanticsNode().boundsInRoot.right <= rootWidth + 1f)
    }

    // =========================================================================
    // F03: Semantic Plain-English Translations — Edge Cases (6)
    // =========================================================================

    @Test
    fun f03_t2_01_extremeHighZeroDbmAndPlusTenDbmSpoofedRssiClampSignalQualityStrictlyTo100Percent() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                ScanEntrySpec(ssid = "ZeroDbmSpoof", bssid = "04:d4:c4:03:01:01", rssiDbm = 0),
                ScanEntrySpec(ssid = "PlusTenSpoof", bssid = "04:d4:c4:03:01:02", rssiDbm = 10),
            ),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertEquals(100, CliJsonLens.radioByBssid(root, "04:d4:c4:03:01:01").int("signalQualityPct"))
        assertEquals(100, CliJsonLens.radioByBssid(root, "04:d4:c4:03:01:02").int("signalQualityPct"))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ZeroDbmSpoof", bssid = "04:D4:C4:03:01:01", rssiDbm = 0)),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Quality must NEVER exceed 100% (e.g. 200%):\n$dump", Regex("""\b[1-9]\d{2}%""").containsMatchIn(dump))
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F03-ZeroDbm")
    }

    @Test
    fun f03_t2_02_extremeLowMinus115DbmClampsSignalQualityToZeroPercentAndDeadZonePhrase() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(ScanEntrySpec(ssid = "DeepBasementAP", bssid = "04:d4:c4:03:02:01", rssiDbm = -115)),
        )
        val root = CliRunnerHarness.analyzeStdin(scenario).assertSucceeded().parseJsonRoot()
        assertEquals(0, CliJsonLens.radioByBssid(root, "04:d4:c4:03:02:01").int("signalQualityPct"))

        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "DeepBasementAP", bssid = "04:D4:C4:03:02:01", rssiDbm = -115)),
        )
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Negative percentage must NEVER render:\n$dump", Regex("""-\d+%""").containsMatchIn(dump))
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Very Weak / Edge of Reach phrase", "Very Weak", "Edge of Reach")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F03-Minus115")
    }

    @Test
    fun f03_t2_03_routerOmittingQbssLoadIe11DisplaysHonestNotAdvertisedNoteInsteadOfFakeZeroClients() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "NoQbssHomeRouter",
                    bssid = "04:D4:C4:03:03:01",
                    rssiDbm = -53,
                    ies = emptyList(), // No IE 11!
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("NoQbssHomeRouter")
        detail.assertDetailContains("not advertised", "Not advertised", "NoQbssHomeRouter")
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertFalse("Must NOT fabricate '0 clients · 0% airtime busy' when IE 11 is absent", dump.contains("0 clients · 0%"))
    }

    @Test
    fun f03_t2_04_unknownWifiStandardCodeZeroRendersGracefulPlainEnglishFallback() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "UnknownStdRouter",
                    bssid = "04:D4:C4:03:04:01",
                    rssiDbm = -60,
                    wifiStandard = ScanResult.WIFI_STANDARD_UNKNOWN,
                ),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("UnknownStdRouter")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F03-UnknownWifiStandard")
    }

    @Test
    fun f03_t2_05_emptyZeroApEnvironmentAcrossAllFourTabsContainsZeroNanOrNullStrings() {
        robot.launchApp()
        robot.injectScanBatch(emptyList())
        val nav = NavigationRobot(composeRule)

        nav.openNetworks()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Empty-Networks")

        nav.openProximity()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Empty-Proximity")

        nav.openSpectrum()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Empty-Spectrum")

        nav.openInsights()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Empty Insights waiting state", "Waiting", "Listening", "No wireless", "Scan")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Empty-Insights")
    }

    @Test
    fun f03_t2_06_singleSampleProximityTargetShowsCalibratingOrSteadyWithoutInfiniteVelocity() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "OneSampleOnly_5G", bssid = "04:D4:C4:03:06:01", rssiDbm = -57)),
        )
        NavigationRobot(composeRule).openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "1-sample trend", "Calibrating", "Steady")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F03-SingleSample")
    }

    // =========================================================================
    // F04: Tap-to-Explain Inline Legend Pills — Edge Cases (6)
    // =========================================================================

    @Test
    fun f04_t2_01_tappingSameLegendPillTwiceCollapsesExplanationCleanly() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ToggleLegendAP", bssid = "04:D4:C4:04:01:01", rssiDbm = -54)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "logarithmic")

        // Tap same pill a second time -> collapses
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        val cardStillThere = runCatching {
            composeRule.onNodeWithTag("legend_card_dbm").assertIsDisplayed()
            true
        }.getOrDefault(false)
        assertFalse("Second tap on same InlineLegendPill must collapse its explanation card", cardStillThere)
    }

    @Test
    fun f04_t2_02_switchingBetweenTwoLegendPillsInSameLegendBarReplacesActiveExplanationWithoutStackOverflow() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SwitchPillsAP", bssid = "04:D4:C4:04:02:01", rssiDbm = -52)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "3 dB")

        LegendPillRobot.tapLegendPill(composeRule, "os_scan_throttle", "OS Scan Limit")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Switched to OS Scan Limit explanation", "2 minutes", "Developer Options")
    }

    @Test
    fun f04_t2_03_expandedLegendPillSurvivesConfigurationChangeRecreateViaRememberSaveable() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RotateLegendAP", bssid = "04:D4:C4:04:03:01", rssiDbm = -50)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "3 dB")

        robot.recreateApp()
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "3 dB")
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp-450dpi", fontScale = 2.0f)
    fun f04_t2_04_legendExplanationCardAndDismissButtonRemainFullyInsideViewportAt200PercentFontScale() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BigTextLegendAP", bssid = "04:D4:C4:04:04:01", rssiDbm = -53)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val cardBounds = composeRule.onNodeWithTag("legend_card_dbm").fetchSemanticsNode().boundsInRoot
        assertTrue("Legend card must stay within [0, $rootWidth] at fontScale=2.0f: $cardBounds", cardBounds.left >= -1f && cardBounds.right <= rootWidth + 1f)
        LegendPillRobot.dismissLegendCard(composeRule, "dbm")
    }

    @Test
    fun f04_t2_05_rapidTenTapStressOnLegendPillNeverCrashesOrCorruptsSemantics() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RapidPillAP", bssid = "04:D4:C4:04:05:01", rssiDbm = -55)),
        )
        repeat(10) {
            LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        }
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F04-RapidTap")
    }

    @Test
    fun f04_t2_06_liveScanBroadcastArrivingWhileLegendPopoverOpenPreservesPopoverWithoutReset() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "InitialLegendAP", bssid = "04:D4:C4:04:06:01", rssiDbm = -60)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "3 dB")

        robot.advanceClockMs(1_500L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "InitialLegendAP", bssid = "04:D4:C4:04:06:01", rssiDbm = -48),
                ScanResultFactory.createScanResult(ssid = "NewBackgroundAP", bssid = "B0:BE:76:04:06:02", rssiDbm = -66),
            ),
        )
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "3 dB")
    }
}
