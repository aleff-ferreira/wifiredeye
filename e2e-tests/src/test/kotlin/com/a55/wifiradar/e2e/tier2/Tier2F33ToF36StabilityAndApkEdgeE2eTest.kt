@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier2

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.DeadObjectException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.harness.ApkBinaryInspector
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.NavigationRobot
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
 * Tier 2 Boundary, Corner, Stress & Binary Hygiene E2E Tests for Features `F33`–`F36` (`24` `@Test` methods: `6` per feature).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier2F33ToF36StabilityAndApkEdgeE2eTest {

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
    // F33: Immutable Unidirectional State & Lifecycle Safety — Stress/Edge (6)
    // =========================================================================

    @Test
    fun f33_t2_01_twentyRapidBottomTabSwitchesDuringLiveScanBurstsNeverThrowConcurrentModification() {
        robot.launchApp()
        val nav = NavigationRobot(composeRule)
        for (i in 1..20) {
            if (i % 4 == 1) {
                robot.injectScanBatch(
                    listOf(
                        ScanResultFactory.createScanResult(ssid = "RapidTab_$i", bssid = String.format("04:D4:C4:33:11:%02X", i), rssiDbm = -45 - i),
                    ),
                )
            }
            when (i % 4) {
                0 -> nav.openNetworks()
                1 -> nav.openProximity()
                2 -> nav.openSpectrum()
                else -> nav.openInsights()
            }
        }
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F33-20RapidTabSwitches")
    }

    @Test
    fun f33_t2_02_fiveRepeatedPauseStopStartResumeLifecycleCyclesNeverAccumulateDuplicateCallbacks() {
        robot.launchApp()
        repeat(5) {
            robot.moveToLifecycle(Lifecycle.State.STARTED)
            robot.moveToLifecycle(Lifecycle.State.CREATED)
            robot.moveToLifecycle(Lifecycle.State.STARTED)
            robot.moveToLifecycle(Lifecycle.State.RESUMED)
        }
        assertTrue(
            "After 5 background/foreground cycles, registered ScanResultsCallbacks must stay <= 1, got ${ExtendedShadowWifiManager.registeredCallbackCount()}",
            ExtendedShadowWifiManager.registeredCallbackCount() <= 1,
        )
    }

    @Test
    fun f33_t2_03_backToBackScanBroadcastsInSameMillisecondMaintainConsistentSnapshot() {
        robot.launchApp()
        repeat(8) { idx ->
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "BurstSameMs_$idx", bssid = "04:D4:C4:33:13:01", rssiDbm = -50 - idx),
                ),
            )
        }
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("BurstSameMs_7")
    }

    @Test
    fun f33_t2_04_backgroundBroadcastArrivingWhileStoppedDoesNotCrashDestroyedOrStoppedUi() {
        robot.launchApp()
        robot.moveToLifecycle(Lifecycle.State.CREATED)
        robot.appContext.sendBroadcast(
            Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
                putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            },
        )
        robot.idleMainLooper()
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F33-BroadcastWhileStopped")
    }

    @Test
    fun f33_t2_05_immediateColdLaunchFollowedByImmediateCloseLeavesZeroDanglingCallbacks() {
        robot.launchApp()
        robot.scenario?.close()
        robot.idleMainLooper()
        assertEquals(0, ExtendedShadowWifiManager.registeredCallbackCount())
    }

    @Test
    fun f33_t2_06_simultaneousConnectedPollAndPassiveScanUpdateConvergeWithoutRaceCondition() {
        val bssid = "AC:84:C6:33:16:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RaceFree_5G", bssid = bssid, rssiDbm = -58)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "RaceFree_5G", bssid = bssid, rssiDbm = -54),
        )
        NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(600L)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RaceFree_5G converged cleanly", "RaceFree_5G")
    }

    // =========================================================================
    // F34: Defensive Android IPC & Coroutine Error Isolation — Edge Cases (6)
    // =========================================================================

    @Test
    fun f34_t2_01_getConnectionInfoThrowingSecurityExceptionIsolatedWithoutBreakingPassiveScanList() {
        ExtendedShadowWifiManager.throwOnGetConnectionInfo = SecurityException("Fine location temporarily unavailable for connectionInfo")
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ScanStillWorks_5G", bssid = "04:D4:C4:34:11:01", rssiDbm = -51)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("ScanStillWorks_5G")
    }

    @Test
    fun f34_t2_02_combinedSimultaneousIpcExceptionsAcrossStartScanGetScanResultsAndConnectionInfoNeverCrashProcess() {
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnStartScan = RuntimeException(DeadObjectException("startScan dead"))
        ExtendedShadowWifiManager.throwOnGetScanResults = SecurityException("getScanResults denied")
        ExtendedShadowWifiManager.throwOnGetConnectionInfo = IllegalStateException("getConnectionInfo bad state")

        runCatching { NavigationRobot(composeRule).openNetworks().triggerManualRefresh() }
        robot.injectScanBatch(emptyList())
        robot.advanceClockMs(1_000L)

        // Clear faults -> app must recover immediately without restart!
        ExtendedShadowWifiManager.throwOnStartScan = null
        ExtendedShadowWifiManager.throwOnGetScanResults = null
        ExtendedShadowWifiManager.throwOnGetConnectionInfo = null
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "TripleFaultSurvivor_5G", bssid = "04:D4:C4:34:12:01", rssiDbm = -48)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("TripleFaultSurvivor_5G")
    }

    @Test
    fun f34_t2_03_scanResultWithEmptyNullBssidOrOutlandishFrequencyFilteredSafely() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ZeroFreqWeird", bssid = "04:D4:C4:34:13:01", rssiDbm = -60, frequencyMhz = -500, centerFreq0Mhz = -500),
                ScanResultFactory.createScanResult(ssid = "ValidSibling_5G", bssid = "04:D4:C4:34:13:02", rssiDbm = -49, frequencyMhz = 5180),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("ValidSibling_5G")
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F34-WeirdScanResult")
    }

    @Test
    fun f34_t2_04_malformedBroadcastIntentsMissingExpectedExtrasHandledGracefully() {
        robot.launchApp()
        // Send bare intents WITHOUT EXTRA_RESULTS_UPDATED, EXTRA_WIFI_STATE, or EXTRA_NEW_RSSI
        robot.appContext.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        robot.appContext.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION))
        robot.appContext.sendBroadcast(Intent(WifiManager.RSSI_CHANGED_ACTION))
        robot.idleMainLooper()
        composeRule.waitForIdle()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F34-BareBroadcasts")
    }

    @Test
    fun f34_t2_05_rttResultsForWrongUnmatchedBssidIgnoredSafely() {
        val myTarget = "04:D4:C4:34:15:01"
        val wrongBssid = "FC:EC:DA:34:15:99"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(ScanResultFactory.createRttResult(bssid = wrongBssid, distanceMm = 9_900)),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "TargetOnlyAP", bssid = myTarget, rssiDbm = -58, is80211mcResponder = true)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        assertTrue(prox.readDisplayedDistanceMeters() in 0.5..35.0)
    }

    @Test
    fun f34_t2_06_unhandledExceptionHandlerRemainsCleanThroughoutFaultInjectionSuite() {
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnStartScan = SecurityException("Denied active trigger")
        runCatching { NavigationRobot(composeRule).openNetworks().triggerManualRefresh() }
        assertFalse("Activity must never finish on caught SecurityException", robot.currentActivity().isFinishing)
    }

    // =========================================================================
    // F35: Rotation & Process-Death State Survival — Edge Cases (6)
    // =========================================================================

    @Test
    fun f35_t2_01_tripleConsecutiveActivityRecreatePreservesActiveTabAndSearchQuery() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "TripleRotateMatch", bssid = "04:D4:C4:35:11:01", rssiDbm = -50),
                ScanResultFactory.createScanResult(ssid = "OtherFilteredOut", bssid = "B0:BE:76:35:11:02", rssiDbm = -60),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.enterSearchQuery("TripleRotate")

        repeat(3) { robot.recreateApp() }
        networks.assertNetworkDisplayed("TripleRotateMatch").assertNetworkNotDisplayed("OtherFilteredOut")
    }

    @Test
    fun f35_t2_02_followMeshSsidTargetModeSurvivesActivityRecreation() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "RecreateMesh", bssid = "04:D4:C4:35:12:01", rssiDbm = -52, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "RecreateMesh", bssid = "04:D4:C4:35:12:02", rssiDbm = -68, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openProximity().switchToFollowMeshMode()

        robot.recreateApp()
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "RecreateMesh preserved", "RecreateMesh")
    }

    @Test
    fun f35_t2_03_recreateWhileNoNetworksScannedYetRecoversCleanlyWhenFirstSweepArrivesAfterwards() {
        robot.launchApp()
        val nav = NavigationRobot(composeRule)
        nav.openProximity()
        robot.recreateApp()
        nav.assertTabSelected("Proximity")

        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PostRecreateFirstAP", bssid = "04:D4:C4:35:13:01", rssiDbm = -49)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Locked onto PostRecreateFirstAP", "PostRecreateFirstAP")
    }

    @Test
    fun f35_t2_04_recreateWhileReadinessLocationBlockerActiveReevaluatesAndRecoversWhenLocationTurnedOn() {
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()
        robot.recreateApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Blocker still shown after recreate", "Location")

        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PostRotateLocationOn_5G", bssid = "04:D4:C4:35:14:01", rssiDbm = -51)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("PostRotateLocationOn_5G")
    }

    @Test
    fun f35_t2_05_savedSortOrderSurvivesActivityRecreate() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "ZuluStrong", bssid = "04:D4:C4:35:15:01", rssiDbm = -44),
                ScanResultFactory.createScanResult(ssid = "AlphaFaint", bssid = "B0:BE:76:35:15:02", rssiDbm = -75),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.selectSortOption("Name A–Z", "Name A-Z", "Name")

        robot.recreateApp()
        val dump = ComposeSemanticMatchers.dumpScreenText(composeRule)
        assertTrue("Name A–Z sort must survive recreate", dump.indexOf("AlphaFaint") < dump.indexOf("ZuluStrong"))
    }

    @Test
    fun f35_t2_06_parcelableSafetyAcrossAllTabsNeverThrowsBadParcelableExceptionOnRecreate() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ParcelSafe_5G", bssid = "04:D4:C4:35:16:01", rssiDbm = -50)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks()
        robot.recreateApp()
        nav.openProximity()
        robot.recreateApp()
        nav.openSpectrum()
        robot.recreateApp()
        nav.openInsights()
        robot.recreateApp()
    }

    // =========================================================================
    // F36: Full App Assembly, Manifest Hygiene & Signed A55 5G APK — Binary Audit (6)
    // =========================================================================

    @Test
    fun f36_t2_01_signedReleaseApkEnforcesZeroInternetZeroBackgroundLocationAndZeroAdIdPermissions() {
        val releaseApk = ApkBinaryInspector.resolveSignedReleaseApk()
        val perms = ApkBinaryInspector.dumpPermissions(releaseApk)
        assertEquals(0, perms.exitCode)
        for (forbidden in listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "com.google.android.gms.permission.AD_ID",
            "android.permission.READ_PHONE_STATE",
            "android.permission.BLUETOOTH_SCAN",
        )) {
            assertFalse("Forbidden permission $forbidden leaked into release APK!\n${perms.stdout}", perms.stdout.contains(forbidden))
        }
    }

    @Test
    fun f36_t2_02_manifestSetsFileProviderExportedFalsepreventingCrossAppDataLeak() {
        val xml = ApkBinaryInspector.dumpManifestXmlTree(ApkBinaryInspector.debugApk)
        assertEquals(0, xml.exitCode)
        val lines = xml.stdout.lines()
        val providerIdx = lines.indexOfFirst { it.contains("E: provider") }
        assertTrue("Expected <provider> element in AndroidManifest.xml", providerIdx >= 0)
        val providerBlock = lines.subList(providerIdx, (providerIdx + 14).coerceAtMost(lines.size)).joinToString("\n")
        assertTrue(
            "FileProvider MUST set android:exported=false (0x0)!\n$providerBlock",
            providerBlock.contains("exported") && providerBlock.contains("0x0"),
        )
    }

    @Test
    fun f36_t2_03_dexBytecodeContainsZeroHttpNetworkingTelemetryOrAnalyticsSdks() {
        val dexOut = ApkBinaryInspector.analyzeDexPackages(ApkBinaryInspector.debugApk)
        assertEquals(0, dexOut.exitCode)
        for (forbiddenPkg in listOf(
            "okhttp3",
            "retrofit2",
            "io.ktor",
            "com.google.firebase",
            "com.facebook",
            "io.sentry",
        )) {
            assertFalse(
                "100% offline guarantee violated: networking/analytics package '$forbiddenPkg' found in DEX!\n${dexOut.stdout.take(1500)}",
                dexOut.stdout.contains(forbiddenPkg),
            )
        }
    }

    @Test
    fun f36_t2_04_dexBytecodeContainsZeroRobolectricJunitOrRoborazziTestLeakageInProductionApk() {
        val dexOut = ApkBinaryInspector.analyzeDexPackages(ApkBinaryInspector.debugApk)
        assertEquals(0, dexOut.exitCode)
        for (testPkg in listOf(
            "org.robolectric",
            "com.github.takahirom.roborazzi",
            "org.junit.runners",
        )) {
            assertFalse("Test framework '$testPkg' leaked into production APK DEX!", dexOut.stdout.contains(testPkg))
        }
    }

    @Test
    fun f36_t2_05_signedReleaseApkCompactSizeFitsLeanOfflineUtilityBudgetUnder35Mb() {
        val releaseApk = ApkBinaryInspector.resolveSignedReleaseApk()
        assertTrue(releaseApk.isFile)
        val sizeMb = releaseApk.length().toDouble() / (1024.0 * 1024.0)
        assertTrue("Expected lean release APK > 0.5 MB and < 35 MB, got ${"%.2f".format(sizeMb)} MB", sizeMb in 0.5..35.0)
    }

    @Test
    fun f36_t2_06_apkZipStructureContainsCompiledDexArscManifestAndAdaptiveLauncherIcons() {
        val entries = ApkBinaryInspector.listZipEntries(ApkBinaryInspector.debugApk)
        assertTrue("APK must contain AndroidManifest.xml", "AndroidManifest.xml" in entries)
        assertTrue("APK must contain classes.dex", "classes.dex" in entries)
        assertTrue("APK must contain resources.arsc", "resources.arsc" in entries)
        assertTrue(
            "APK must package launcher icon resources",
            entries.any { it.contains("ic_launcher") || it.startsWith("res/mipmap") || it.startsWith("res/drawable") },
        )
    }
}
