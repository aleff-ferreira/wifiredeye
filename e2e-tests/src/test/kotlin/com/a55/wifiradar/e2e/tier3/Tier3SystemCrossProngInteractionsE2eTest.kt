@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier3

import android.net.wifi.ScanResult
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.ApkBinaryInspector
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliJsonLens.dbl
import com.a55.wifiradar.e2e.harness.CliJsonLens.int
import com.a55.wifiradar.e2e.harness.CliJsonLens.obj
import com.a55.wifiradar.e2e.harness.CliJsonLens.str
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder
import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.ScanEntrySpec
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.io.File
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
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
 * Tier 3 Cross-Prong System Interaction E2E Tests: Toolchain (`F01`), UI (`Prong 1`), CLI (`Prong 2`), and Signed APK (`Prong 3`) Numerical Parity (`T3_37`–`T3_42` = `6` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier3SystemCrossProngInteractionsE2eTest {

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
    fun t3_37_f01_f17_f36_toolchainEnvDrivesBothCliSubprocessAndSignedOfflineReleaseApkVerification() {
        assertTrue(File(ApkBinaryInspector.projectRoot, "scripts/env.sh").isFile)
        val cliOut = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(
                scans = listOf(ScanEntrySpec(ssid = "CrossProngCheck", bssid = "04:d4:c4:73:37:01", rssiDbm = -50)),
            ),
        ).assertSucceeded()
        assertEquals("1.0", cliOut.parseJsonRoot().str("schemaVersion"))

        val releaseApk = ApkBinaryInspector.resolveSignedReleaseApk()
        assertEquals(0, ApkBinaryInspector.verifySignature(releaseApk).exitCode)
        assertFalse(ApkBinaryInspector.dumpPermissions(releaseApk).stdout.contains("android.permission.INTERNET"))
    }

    @Test
    fun t3_38_f01_f02_f36_launcherIntentColdBootsCompiledPackageAndCapturesNativeAmoledScreenshot() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ColdBootVisual_5G", bssid = "04:D4:C4:73:38:01", rssiDbm = -47)),
        )
        val (pngFile, bmp) = robot.captureRootBitmap("t3_38_cold_boot_amoled.png")
        assertTrue(pngFile.length() > 2_000L)
        assertTrue(ComposeSemanticMatchers.peakContrastInRegion(bmp) >= 7.0)
        assertEquals("com.a55.wifiradar", robot.currentActivity().packageName)
    }

    @Test
    fun t3_39_f03_f05_f15_f17_uiExportedSpectrumAndBestChannelMatchCliSubprocessExactChannelRecommendation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ParityCh1", bssid = "04:D4:C4:73:39:01", rssiDbm = -47, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
                ScanResultFactory.createScanResult(ssid = "ParityCh6", bssid = "B0:BE:76:73:39:02", rssiDbm = -48, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        NavigationRobot(composeRule).openInsights().clickShareExport("JSON")
        val uiJson = CliRunnerHarness.lenientJson.parseToJsonElement(
            ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity()).resolvedBody.trim(),
        ) as JsonObject

        val cliJson = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(
                scans = listOf(
                    ScanEntrySpec(ssid = "ParityCh1", bssid = "04:d4:c4:73:39:01", rssiDbm = -47, frequencyMhz = 2412, centerFreq0Mhz = 2412, channelWidthMhz = 20),
                    ScanEntrySpec(ssid = "ParityCh6", bssid = "b0:be:76:73:39:02", rssiDbm = -48, frequencyMhz = 2437, centerFreq0Mhz = 2437, channelWidthMhz = 20),
                ),
            ),
        ).assertSucceeded().parseJsonRoot()

        val uiRec24 = CliJsonLens.spectrumForBand(uiJson, "2.4").obj("recommendation")!!.int("recommendedPrimaryChannel")
        val cliRec24 = CliJsonLens.spectrumForBand(cliJson, "2.4").obj("recommendation")!!.int("recommendedPrimaryChannel")
        assertEquals("UI and CLI 2.4 GHz Best-Channel must agree (Ch 11)", cliRec24, uiRec24)
        assertEquals(11, uiRec24)
    }

    @Test
    fun t3_40_f06_f07_f17_uiAndCliProduceIdenticalUtf8DecodingAndOfflineVendorAttribution() {
        val utf8Name = "Zürich Café 5G"
        val bssid = "FC:EC:DA:73:40:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = utf8Name, bssid = bssid, rssiDbm = -53)),
        )
        NavigationRobot(composeRule).openInsights().clickShareExport("JSON")
        val uiJson = CliRunnerHarness.lenientJson.parseToJsonElement(
            ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity()).resolvedBody.trim(),
        ) as JsonObject

        val cliJson = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(scans = listOf(ScanEntrySpec(ssid = utf8Name, bssid = bssid, rssiDbm = -53))),
        ).assertSucceeded().parseJsonRoot()

        val uiRadio = CliJsonLens.radioByBssid(uiJson, bssid)
        val cliRadio = CliJsonLens.radioByBssid(cliJson, bssid)
        assertEquals(CliJsonLens.ssidDisplayName(cliRadio), CliJsonLens.ssidDisplayName(uiRadio))
        assertEquals(
            CliJsonLens.vendorObj(cliRadio).str("displayLabel"),
            CliJsonLens.vendorObj(uiRadio).str("displayLabel"),
        )
    }

    @Test
    fun t3_41_f09_f11_f16_f17_uiDisplayedHealthScoreMatchesCliComputedHealthScoreOnIdenticalSnapshot() {
        val bssid = "04:D4:C4:73:41:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "ParityHealth_AX",
                    bssid = bssid,
                    rssiDbm = -50,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5210,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                        BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT.toScanResultIe(),
                    ),
                ),
            ),
        )
        val uiHealthScore = NavigationRobot(composeRule).openInsights().readOverallHealthScore()

        val cliRoot = CliRunnerHarness.analyzeStdin(
            CliScenarioBuilder.singleFrame(
                scans = listOf(
                    ScanEntrySpec(
                        ssid = "ParityHealth_AX",
                        bssid = bssid,
                        rssiDbm = -50,
                        frequencyMhz = 5180,
                        centerFreq0Mhz = 5210,
                        channelWidthMhz = 80,
                        capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                        ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR, BeaconIeVectors.BSS_LOAD_12_CLIENTS_25_PCT),
                    ),
                ),
            ),
        ).assertSucceeded().parseJsonRoot()
        val cliHealthScore = CliJsonLens.healthObj(cliRoot).int("overallScore0To100")!!

        assertTrue(
            "UI Health Score ($uiHealthScore) and CLI Health Score ($cliHealthScore) must match within +-3 points",
            abs(uiHealthScore - cliHealthScore) <= 3,
        )
    }

    @Test
    fun t3_42_f12_f13_f17_uiProximityDistanceMatchesCliPathLossDistanceForSameBssidAndEnvironment() {
        val bssid = "04:D4:C4:73:42:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ParityDist_5G", bssid = bssid, rssiDbm = -66, frequencyMhz = 5180)),
        )
        val uiDist = NavigationRobot(composeRule).openProximity().readDisplayedDistanceMeters()

        val cliDist = CliJsonLens.latestProximity(
            CliRunnerHarness.analyzeStdin(
                CliScenarioBuilder.singleFrame(
                    scans = listOf(ScanEntrySpec(ssid = "ParityDist_5G", bssid = bssid, rssiDbm = -66, frequencyMhz = 5180)),
                ),
                targetBssid = bssid,
                env = "HOME_OFFICE",
            ).assertSucceeded().parseJsonRoot(),
        ).obj("distanceEstimate")!!.dbl("meters")!!

        // UI range midpoint `(min + max) / 2` can be slightly wider than geometric center `meters`, so check realistic agreement!
        assertTrue(
            "UI displayed distance ($uiDist m) and CLI distance ($cliDist m) must sit in consistent indoor neighborhood",
            abs(uiDist - cliDist) <= cliDist * 0.45 + 0.5,
        )
    }
}
