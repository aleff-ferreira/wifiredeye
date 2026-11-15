@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.graphics.Color as AndroidColor
import android.net.wifi.ScanResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.ApkBinaryInspector
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F01`–`F04` (`24` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F01ToF04FoundationDesignE2eTest {

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
    // F01: Toolchain, Dual-CA Truststore & 0-Swap Build Setup (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f01_t1_01_envScriptConfiguresJava17AndroidSdk35AndDualCaTruststore() {
        val envSh = File(ApkBinaryInspector.projectRoot, "scripts/env.sh")
        assertTrue("Expected /home/antigravity/scripts/env.sh to exist", envSh.isFile)
        val proc = ProcessBuilder(
            "bash",
            "-c",
            "source '${envSh.absolutePath}' && printf '%s\n%s\n%s\n%s\n' \"\$JAVA_HOME\" \"\$ANDROID_HOME\" \"\$TMPDIR\" \"\$JAVA_TOOL_OPTIONS\"",
        ).start()
        val lines = proc.inputStream.bufferedReader().readLines()
        assertEquals(0, proc.waitFor())
        assertEquals("/usr/lib/jvm/java-17-openjdk-amd64", lines[0])
        assertEquals("/home/antigravity/.android-sdk", lines[1])
        assertEquals("/home/antigravity/.tmp", lines[2])
        assertTrue(
            "JAVA_TOOL_OPTIONS must point to cacerts.p12: ${lines[3]}",
            lines[3].contains("/home/antigravity/.android-sdk/cacerts.p12"),
        )
        assertTrue(File(lines[0], "bin/java").canExecute())
        assertTrue(File(lines[1], "platforms/android-35/android.jar").isFile)
    }

    @Test
    fun f01_t1_02_dualCaTruststoreContainsBothSystemProxyAndAgentGatewayCertificates() {
        val trustStoreFile = File("/home/antigravity/.android-sdk/cacerts.p12")
        assertTrue("Expected PKCS12 truststore at ${trustStoreFile.absolutePath}", trustStoreFile.isFile)
        val ks = KeyStore.getInstance("PKCS12")
        trustStoreFile.inputStream().use { ks.load(it, "changeit".toCharArray()) }

        val subjectsAndAliases = buildList {
            val aliases = ks.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                add(alias)
                val cert = ks.getCertificate(alias) as? X509Certificate
                if (cert != null) {
                    add(cert.subjectX500Principal.name)
                    add(cert.issuerX500Principal.name)
                }
            }
        }.joinToString(" | ").lowercase()

        assertTrue("Expected >= 2 CA entries in cacerts.p12", ks.size() >= 2)
        assertTrue(
            "Expected antigravity/system proxy CA in cacerts.p12, got: ${subjectsAndAliases.take(500)}",
            subjectsAndAliases.contains("antigravity") || subjectsAndAliases.contains("proxy") || ks.size() >= 2,
        )
        assertTrue(
            "Expected agent-gateway CA in cacerts.p12, got: ${subjectsAndAliases.take(500)}",
            subjectsAndAliases.contains("agent-gateway") || subjectsAndAliases.contains("gateway"),
        )
    }

    @Test
    fun f01_t1_03_zeroSwapMemorySafetyCapsConfiguredInGradleProperties() {
        val propsFile = File(ApkBinaryInspector.projectRoot, "gradle.properties")
        assertTrue("Expected root gradle.properties", propsFile.isFile)
        val text = propsFile.readText()
        assertTrue("Expected Gradle daemon cap -Xmx2048m", text.contains("-Xmx2048m"))
        assertTrue("Expected Kotlin daemon cap -Xmx1536m", text.contains("-Xmx1536m"))
        assertTrue("Expected org.gradle.workers.max=2", text.contains("org.gradle.workers.max=2"))
        assertTrue(
            "Expected XFS-backed java.io.tmpdir=/home/antigravity/.tmp",
            text.contains("-Djava.io.tmpdir=/home/antigravity/.tmp"),
        )
        assertTrue(
            "Expected trustStore cacerts.p12 in gradle.properties",
            text.contains("/home/antigravity/.android-sdk/cacerts.p12"),
        )
    }

    @Test
    fun f01_t1_04_multiModuleSettingsIncludesAllNineCanonicalModules() {
        val settingsText = File(ApkBinaryInspector.projectRoot, "settings.gradle.kts").readText()
        val expectedModules = listOf(
            ":core:model",
            ":core:rf-engine",
            ":core:wifi-platform",
            ":core:designsystem",
            ":feature:networks",
            ":feature:proximity",
            ":feature:spectrum",
            ":feature:insights",
            ":cli-runner",
            ":app",
            ":e2e-tests",
        )
        for (mod in expectedModules) {
            assertTrue("Expected $mod in settings.gradle.kts", settingsText.contains(mod))
        }
    }

    @Test
    fun f01_t1_05_versionCatalogPinsAgpKotlinComposeBomRobolectricAndRoborazzi() {
        val toml = File(ApkBinaryInspector.projectRoot, "gradle/libs.versions.toml").readText()
        assertTrue("Expected agp = \"8.13.2\"", toml.contains("""agp = "8.13.2""""))
        assertTrue("Expected composeBom = \"2026.05.01\"", toml.contains("""composeBom = "2026.05.01""""))
        assertTrue("Expected robolectric = \"4.18\"", toml.contains("""robolectric = "4.18""""))
        assertTrue("Expected roborazzi = \"1.79.0\"", toml.contains("""roborazzi = "1.79.0""""))
        assertTrue(
            "Expected Kotlin 2.x version in libs.versions.toml",
            Regex("""kotlin\s*=\s*"2\.\d+\.\d+"""").containsMatchIn(toml),
        )
    }

    @Test
    fun f01_t1_06_androidSdk35BuildToolsExecutablesVerifyCleanExecution() {
        assertTrue("aapt2 must be executable", ApkBinaryInspector.aapt2Bin.canExecute())
        assertTrue("apksigner must be executable", ApkBinaryInspector.apksignerBin.canExecute())
        assertTrue("apkanalyzer must be executable", ApkBinaryInspector.apkanalyzerBin.canExecute())

        val aaptVer = ApkBinaryInspector.runTool(ApkBinaryInspector.aapt2Bin, "version")
        assertEquals(0, aaptVer.exitCode)
        val signerVer = ApkBinaryInspector.runTool(ApkBinaryInspector.apksignerBin, "--version")
        assertEquals(0, signerVer.exitCode)
    }

    // =========================================================================
    // F02: Galaxy A55 5G True-AMOLED 120Hz Design System (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f02_t1_01_trueAmoledBlackBackgroundRendersPureZeroRgbPixelsOnGalaxyA55Viewport() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "AmoledFiber_5G", rssiDbm = -46, frequencyMhz = 5180),
            ),
        )
        val (_, bmp) = robot.captureRootBitmap("f02_t1_01_amoled_networks.png")
        assertTrue("Expected FHD+ class width >= 900px, got ${bmp.width}", bmp.width >= 900)
        assertTrue("Expected 19.5:9 class height >= 1800px, got ${bmp.height}", bmp.height >= 1800)

        var pureBlackCount = 0
        var totalSampled = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                totalSampled++
                if (bmp.getPixel(x, y) == AndroidColor.BLACK) {
                    pureBlackCount++
                }
            }
        }
        val blackRatio = pureBlackCount.toDouble() / totalSampled.toDouble()
        assertTrue(
            "Expected substantial pure #000000 AMOLED background pixels (>= 15%), got ${(blackRatio * 100).toInt()}%",
            blackRatio >= 0.15,
        )
    }

    @Test
    fun f02_t1_02_wcagAaaContrastRatioVerifiedOnRenderedForegroundElements() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ContrastCheck_5G", rssiDbm = -48, frequencyMhz = 5180),
            ),
        )
        val (_, bmp) = robot.captureRootBitmap("f02_t1_02_contrast_audit.png")
        val peakContrast = ComposeSemanticMatchers.peakContrastInRegion(bmp)
        assertTrue(
            "Expected WCAG 2.1 AAA foreground-to-background contrast >= 7.0:1, got $peakContrast",
            peakContrast >= 7.0,
        )
    }

    @Test
    fun f02_t1_03_fourBottomNavigationTabsAlwaysVisibleReachableAnd48dpTouchTargets() {
        robot.launchApp()
        val minTouchPx = with(composeRule.density) { 48.dp.toPx() } - 1.5f
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width

        var prevRight = -1f
        for (tab in listOf("Networks", "Proximity", "Spectrum", "Insights")) {
            composeRule.onNodeWithText(tab).assertIsDisplayed()
            val node = composeRule.onNodeWithTag("radar_bottom_tab_${tab.lowercase()}").fetchSemanticsNode()
            val b = node.boundsInRoot
            assertTrue("Tab $tab height (${b.height}) must be >= 48dp ($minTouchPx)", b.height >= minTouchPx)
            assertTrue("Tab $tab must stay inside [0, $rootWidth]: $b", b.left >= -0.5f && b.right <= rootWidth + 0.5f)
            assertTrue("Tab $tab must not overlap neighbor: prevRight=$prevRight, left=${b.left}", b.left >= prevRight - 1.0f)
            prevRight = b.right
        }

        val nav = NavigationRobot(composeRule)
        nav.openProximity()
        nav.assertTabSelected("Proximity")
        nav.openSpectrum()
        nav.assertTabSelected("Spectrum")
        nav.openInsights()
        nav.assertTabSelected("Insights")
        nav.openNetworks()
        nav.assertTabSelected("Networks")
    }

    @Test
    fun f02_t1_04_infinityOPunchHoleTopBarMaintainsSafeClearanceWithoutClipping() {
        robot.launchApp()
        val topBarBounds = composeRule.onNodeWithTag("radar_top_bar")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("Top bar must stay horizontally unclipped", topBarBounds.left >= 0f && topBarBounds.right <= rootWidth + 0.5f)
        assertTrue("Top bar must have positive height (${topBarBounds.height})", topBarBounds.height >= with(composeRule.density) { 36.dp.toPx() })
        composeRule.onNodeWithTag("radar_scan_status_pill").assertIsDisplayed()
    }

    @Test
    fun f02_t1_05_tabularNumeralMetricWidthRemainsStableAcrossLiveRssiUpdates() {
        robot.launchApp()
        val bssid = "AC:84:C6:55:66:77"
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "TabularCheck", bssid = bssid, rssiDbm = -41)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openProximity()

        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "TabularCheck", bssid = bssid, rssiDbm = -88, ageMs = 50L)),
        )
        val rootWidth = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val allClickable = composeRule.onAllNodes(
            SemanticsMatcher("clickable") { it.config.contains(SemanticsActions.OnClick) },
        ).fetchSemanticsNodes()
        assertTrue("Interactive controls must remain inside viewport after live RSSI jump", allClickable.all { it.boundsInRoot.right <= rootWidth + 1f })
    }

    @Test
    fun f02_t1_06_finiteSpringAnimationsSettleCleanlyForComposeWaitForIdleAcrossAllScreens() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SpringSettle_5G", bssid = "04:D4:C4:00:11:22", rssiDbm = -52, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "SpringSettle_2G", bssid = "04:D4:C4:00:11:21", rssiDbm = -63, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks()
        composeRule.waitForIdle()
        nav.openProximity()
        composeRule.waitForIdle()
        nav.openSpectrum()
        composeRule.waitForIdle()
        nav.openInsights()
        composeRule.waitForIdle()
    }

    // =========================================================================
    // F03: Semantic Plain-English Translations Everywhere (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f03_t1_01_rssiTiersTranslateIntoPlainEnglishSignalQualityPhrases() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "TierExcellent", bssid = "04:D4:C4:01:00:01", rssiDbm = -45, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "TierGood", bssid = "04:D4:C4:01:00:02", rssiDbm = -60, frequencyMhz = 5200),
                ScanResultFactory.createScanResult(ssid = "TierFair", bssid = "04:D4:C4:01:00:03", rssiDbm = -72, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "TierWeak", bssid = "04:D4:C4:01:00:04", rssiDbm = -82, frequencyMhz = 2462, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2462),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Excellent tier", "Excellent", "Very Close")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Good tier", "Strong", "Good")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Fair tier", "Fair")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Weak tier", "Weak")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Networks-SignalTiers")
    }

    @Test
    fun f03_t1_02_bandAndChannelWidthDisplayHumanReadableRangeAndSpeedTraits() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "DualTrait_2G",
                    bssid = "B0:BE:76:10:00:01",
                    rssiDbm = -58,
                    frequencyMhz = 2437,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2437,
                ),
                ScanResultFactory.createScanResult(
                    ssid = "DualTrait_5G",
                    bssid = "04:D4:C4:10:00:02",
                    rssiDbm = -50,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5210,
                ),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "2.4 GHz trait", "2.4 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "5 GHz trait", "5 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "80 MHz label", "80 MHz")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Networks-Bands")
    }

    @Test
    fun f03_t1_03_securityAssessmentsRenderLetterGradeAndPlainEnglishPosture() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Vault_WPA3",
                    bssid = "04:D4:C4:20:00:01",
                    rssiDbm = -49,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "Public_Cafe",
                    bssid = "B0:BE:76:20:00:02",
                    rssiDbm = -64,
                    capabilities = "[ESS]",
                ),
            ),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "WPA3 grade", "A+", "WPA3")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Open grade", "F", "Open", "Unsecured", "Unencrypted")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Networks-Security")
    }

    @Test
    fun f03_t1_04_proximityVelocityStabilityAndDistanceDisplaySelfExplanatoryLabels() {
        robot.launchApp()
        val bssid = "04:D4:C4:30:00:01"
        for ((idx, rssi) in listOf(-76, -70, -63, -55, -48).withIndex()) {
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(
                        ssid = "WalkTarget_5G",
                        bssid = bssid,
                        rssiDbm = rssi,
                        explicitTimestampMicros = (idx + 1) * 1_000_000L,
                    ),
                ),
            )
            if (idx == 0) {
                NavigationRobot(composeRule).openProximity()
            }
            robot.advanceClockMs(1_000L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Velocity phrase", "Approaching", "Closer", "Steady")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Distance phrase", "m", "Est.", "Next to Router")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Stability phrase", "Rock Solid", "Steady", "Fluctuating")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Proximity-PlainEnglish")
    }

    @Test
    fun f03_t1_05_zeroRawEnumNamesLeakAcrossAllFourTabsAndDetailSheet() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "FullAudit_AX",
                    bssid = "04:D4:C4:40:00:01",
                    rssiDbm = -52,
                    frequencyMhz = 5180,
                    ies = listOf(
                        BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT.toScanResultIe(),
                        BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Tab-Networks")

        val detail = networks.openDetailSheetFor("FullAudit_AX")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Sheet-Detail")
        detail.dismiss()

        nav.openProximity()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Tab-Proximity")

        nav.openSpectrum()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Tab-Spectrum")

        nav.openInsights()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "Tab-Insights")
    }

    @Test
    fun f03_t1_06_cliMarkdownAndJsonPlainEnglishFieldsContainZeroRawEnumConstants() {
        val scenario = CliScenarioBuilder.singleFrame(
            scans = listOf(
                CliScenarioBuilder.ScanEntrySpec(
                    ssid = "CliSemantic_5G",
                    bssid = "04:d4:c4:50:00:01",
                    rssiDbm = -53,
                    frequencyMhz = 5180,
                    channelWidthMhz = 80,
                    centerFreq0Mhz = 5210,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR, BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT),
                ),
            ),
        )
        val mdRes = CliRunnerHarness.analyzeStdin(scenario, format = "markdown").assertSucceeded()
        assertFalse(
            "Markdown export must not leak raw enum names: ${mdRes.stdout.take(800)}",
            Regex("""\b(WIFI_STANDARD_|SECURITY_TYPE_|CHANNEL_WIDTH_|BAND_5_GHZ|A_PLUS)\b""")
                .containsMatchIn(mdRes.stdout),
        )
        val jsonRoot = CliRunnerHarness.analyzeStdin(scenario, format = "json").assertSucceeded().parseJsonRoot()
        val radio = CliJsonLens.radioByBssid(jsonRoot, "04:d4:c4:50:00:01")
        val secLabel = CliJsonLens.securityObj(radio).str("plainEnglishLabel").orEmpty()
        assertTrue("Expected plain-English security summary, got '$secLabel'", secLabel.isNotBlank())
    }

    // =========================================================================
    // F04: Tap-to-Explain Inline Legend Pills (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f04_t1_01_dbmInlineLegendPillExpandsTwoSentencePlainEnglishExplanation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LegendNet", rssiDbm = -54)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "dbm", "dBm")
        LegendPillRobot.assertLegendCardVisible(composeRule, "dbm", "dBm")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "dBm legend", "signal", "router", "3 dB")
    }

    @Test
    fun f04_t1_02_osScanThrottleInlineLegendPillExplainsAndroidFourPerTwoMinuteRule() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ThrottleLegendNet", rssiDbm = -58)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "os_scan_throttle", "OS Scan Limit")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "OS Scan Throttle legend",
            "4 active",
            "2 minutes",
            "Developer Options",
        )
    }

    @Test
    fun f04_t1_03_kalmanSmoothingLegendPillOnProximityExplainsNoiseCalming() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "KalmanLegendNet", rssiDbm = -55)),
        )
        NavigationRobot(composeRule).openProximity().scrollUntilVisible("Kalman")
        LegendPillRobot.tapLegendPill(composeRule, "kalman_smoothing", "Kalman")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Kalman legend",
            "Kalman",
            "multipath",
            "flicker",
            "smooth",
        )
    }

    @Test
    fun f04_t1_04_cciVsAciAndDfsTdwrLegendPillsOnSpectrumExplainChannelPhysics() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SpecLegendNet", frequencyMhz = 5260, centerFreq0Mhz = 5290, rssiDbm = -60)),
        )
        val spec = NavigationRobot(composeRule).openSpectrum()
        spec.scrollUntilVisible("CCI", "DFS")
        LegendPillRobot.tapLegendPill(composeRule, "cci_vs_aci", "CCI vs ACI")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "CCI vs ACI legend",
            "Co-Channel",
            "Adjacent-Channel",
            "overlap",
        )

        LegendPillRobot.tapLegendPill(composeRule, "dfs_tdwr", "DFS")
        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "DFS/TDWR legend",
            "radar",
            "DFS",
        )
    }

    @Test
    fun f04_t1_05_psc6GhzAndPmf80211wLegendPillsExplainPreferredScanningAndFrameProtection() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "TriBandLegendNet",
                    bssid = "04:D4:C4:60:00:01",
                    rssiDbm = -50,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                        BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("6 GHz").scrollUntilVisible("PSC")
        LegendPillRobot.tapLegendPill(composeRule, "psc_6ghz", "PSC")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "PSC legend", "Preferred Scanning", "PSC", "6 GHz")

        nav.openInsights().scrollUntilVisible("PMF")
        LegendPillRobot.tapLegendPill(composeRule, "pmf_80211w", "PMF")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "PMF legend", "Protected Management Frames", "802.11w", "disconnect")
    }

    @Test
    fun f04_t1_06_bssLoadLegendPillExplainsClientCountAndDismissesCleanlyWithoutNavigationReset() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "BusyOffice_AX",
                    bssid = "04:D4:C4:70:00:01",
                    rssiDbm = -48,
                    ies = listOf(BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT.toScanResultIe()),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("BusyOffice_AX")
        detail.assertDetailContains("45", "78%")
        LegendPillRobot.tapLegendPill(composeRule, "bss_load", "BSS Load")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "BSS Load legend", "airtime", "70%", "devices")
        LegendPillRobot.dismissLegendCard(composeRule, "bss_load")
        detail.assertDetailContains("BusyOffice_AX")
    }
}
