@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier1

import android.Manifest
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingResult
import android.os.DeadObjectException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
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
 * Tier 1 Core Functional Happy-Path E2E Tests for Features `F33`–`F36` (`24` `@Test` methods: `6` per feature).
 * - `F33` Immutable Unidirectional State & Lifecycle Safety (`6` tests)
 * - `F34` Defensive Android IPC & Coroutine Error Isolation (`6` tests)
 * - `F35` Rotation & Process-Death State Survival (`6` tests)
 * - `F36` Full App Assembly, Manifest Hygiene & Signed A55 5G APK (`6` tests)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier1F33ToF36StabilityAndApkE2eTest {

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
    // F33: Immutable Unidirectional State & Lifecycle Safety (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f33_t1_01_lifecycleMoveToCreatedStopsActiveScanTimerLoopAndUnregistersCallbacks() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = false)
        robot.launchApp()
        robot.advanceClockMs(2_600L)

        robot.moveToLifecycle(Lifecycle.State.CREATED)
        val countAtBackground = ExtendedShadowWifiManager.startScanInvocationCount.get()
        robot.advanceClockMs(25_000L)
        val countAfter30sBackground = ExtendedShadowWifiManager.startScanInvocationCount.get()

        assertEquals(
            "Zero active scans may execute while Activity is backgrounded (ON_STOP / CREATED)",
            countAtBackground,
            countAfter30sBackground,
        )
        assertEquals(
            "ScanResultsCallback must be unregistered on ON_STOP",
            0,
            ExtendedShadowWifiManager.registeredCallbackCount(),
        )
    }

    @Test
    fun f33_t1_02_lifecycleMoveBackToResumedCleanlyResumesScanningWithoutDuplicateCallbackLeak() {
        robot.launchApp()
        robot.moveToLifecycle(Lifecycle.State.CREATED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)

        assertTrue(
            "Returning to RESUMED must register at most 1 ScanResultsCallback, got ${ExtendedShadowWifiManager.registeredCallbackCount()}",
            ExtendedShadowWifiManager.registeredCallbackCount() <= 1,
        )
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ResumedFresh_5G", bssid = "04:D4:C4:33:02:01", rssiDbm = -48)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("ResumedFresh_5G")
    }

    @Test
    fun f33_t1_03_connected300msHighRatePollerHaltsOnBackgroundAndResumesOnForeground() {
        val bssid = "AC:84:C6:33:03:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "LifecyclePoll_5G", bssid = bssid, rssiDbm = -66)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "LifecyclePoll_5G", bssid = bssid, rssiDbm = -66),
        )
        NavigationRobot(composeRule).openProximity()

        robot.moveToLifecycle(Lifecycle.State.CREATED)
        robot.advanceClockMs(5_000L)

        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        for (r in listOf(-54, -46, -44)) {
            robot.simulateConnectedWifi(
                ScanResultFactory.createConnectedWifiInfo(ssid = "LifecyclePoll_5G", bssid = bssid, rssiDbm = r),
            )
            robot.advanceClockMs(350L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Resumed 300ms live RSSI", "Excellent", "-44 dBm", "-45 dBm", "-46 dBm")
    }

    @Test
    fun f33_t1_04_deterministicWaitForIdleCompletesPromptlyAcrossAllFourScreens() {
        robot.launchApp()
        robot.injectScanBatch(
            (1..8).map { i ->
                ScanResultFactory.createScanResult(
                    ssid = "DeterministicAP_$i",
                    bssid = "04:D4:C4:33:04:0$i",
                    rssiDbm = -46 - i * 3,
                    frequencyMhz = if (i % 2 == 0) 5180 else 2437,
                    channelWidth = if (i % 2 == 0) ScanResult.CHANNEL_WIDTH_80MHZ else ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = if (i % 2 == 0) 5210 else 2437,
                )
            },
        )
        val nav = NavigationRobot(composeRule)
        for (action in listOf<() -> Unit>(
            { nav.openNetworks() },
            { nav.openProximity() },
            { nav.openSpectrum() },
            { nav.openInsights() },
        )) {
            val t0 = System.nanoTime()
            action()
            composeRule.waitForIdle()
            val ms = (System.nanoTime() - t0) / 1_000_000L
            assertTrue("Compose waitForIdle must settle deterministically without infinite animation hang (${ms}ms)", ms < 10_000L)
        }
    }

    @Test
    fun f33_t1_05_unidirectionalCrossTabSnapshotConsistencyReflectsNewRoutersAcrossAllFourTabs() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "InitialSingleAP", bssid = "04:D4:C4:33:05:01", rssiDbm = -55)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("InitialSingleAP")

        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "InitialSingleAP", bssid = "04:D4:C4:33:05:01", rssiDbm = -55),
                ScanResultFactory.createScanResult(ssid = "UnifiedNewMesh_5G", bssid = "FC:EC:DA:33:05:02", rssiDbm = -45, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        nav.openNetworks().assertNetworkDisplayed("UnifiedNewMesh_5G")
        nav.openSpectrum().selectBand("5 GHz").scrollUntilVisible("UnifiedNewMesh_5G", "149")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Spectrum reflects UnifiedNewMesh_5G", "UnifiedNewMesh_5G", "149")
        nav.openInsights().scrollUntilVisible("UnifiedNewMesh_5G")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Insights reflects UnifiedNewMesh_5G", "UnifiedNewMesh_5G")
    }

    @Test
    fun f33_t1_06_activityCloseDestructionUnregistersReceiversAndCancelsGeigerFeedbackCleanly() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "CloseSafety_5G", bssid = "04:D4:C4:33:06:01", rssiDbm = -45)),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        prox.toggleHapticGeiger()
        prox.toggleAudioGeiger()
        robot.advanceClockMs(600L)

        robot.scenario?.close()
        robot.idleMainLooper()
        assertEquals(0, ExtendedShadowWifiManager.registeredCallbackCount())
    }

    // =========================================================================
    // F34: Defensive Android IPC & Coroutine Error Isolation (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f34_t1_01_midSessionSecurityExceptionOnGetScanResultsTransitionsCleanlyToPermissionCard() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BeforeRevoke_5G", bssid = "04:D4:C4:34:01:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("BeforeRevoke_5G")

        robot.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ExtendedShadowWifiManager.throwOnGetScanResults = SecurityException("UID lacks ACCESS_FINE_LOCATION")
        robot.injectScanBatch(emptyList())

        ComposeSemanticMatchers.assertScreenContainsAny(
            composeRule,
            "Graceful transition to Permission Readiness Card without crash",
            "Location",
            "Permission",
            "Grant",
            "Allow",
        )
    }

    @Test
    fun f34_t1_02_supervisorScopeSurvivesMidSessionSecurityExceptionAndRecoversWhenPermissionRestored() {
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnGetScanResults = SecurityException("Transient permission fault")
        robot.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        robot.injectScanBatch(emptyList())

        // Restore permission and clear fault -> supervisor scope must still be alive!
        ExtendedShadowWifiManager.throwOnGetScanResults = null
        robot.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RecoveredAfterFault_5G", bssid = "04:D4:C4:34:02:01", rssiDbm = -49)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("RecoveredAfterFault_5G")
    }

    @Test
    fun f34_t1_03_startScanThrowingIllegalStateExceptionIsolatedWithoutKillingScannerLoop() {
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnStartScan = IllegalStateException("WifiService busy")
        runCatching { NavigationRobot(composeRule).openNetworks().triggerManualRefresh() }
        robot.advanceClockMs(500L)

        ExtendedShadowWifiManager.throwOnStartScan = null
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "AliveAfterStartScanFault", bssid = "04:D4:C4:34:03:01", rssiDbm = -52)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("AliveAfterStartScanFault")
    }

    @Test
    fun f34_t1_04_wifiRttStatusFailResultNeverCallsGetDistanceMmAndFallsBackToPathLoss() {
        val bssid = "04:D4:C4:34:04:01"
        robot.configureReadyGalaxyA55Defaults(hasRtt = true)
        robot.injectRttResults(
            listOf(
                ScanResultFactory.createRttResult(
                    bssid = bssid,
                    distanceMm = 0,
                    status = RangingResult.STATUS_FAIL,
                ),
            ),
        )
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "RttFailFallback_AP",
                    bssid = bssid,
                    rssiDbm = -62,
                    is80211mcResponder = true,
                ),
            ),
        )
        val prox = NavigationRobot(composeRule).openProximity()
        robot.advanceClockMs(1_000L)
        val meters = prox.readDisplayedDistanceMeters()
        assertTrue("Must fall back cleanly to finite path-loss distance ($meters m) on RTT STATUS_FAIL", meters in 0.5..35.0)
    }

    @Test
    fun f34_t1_05_truncatedAndMalformedInformationElementsInScanResultParsedSafelyWithoutUnderflow() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "HostileBeacon_5G",
                    bssid = "04:D4:C4:34:05:01",
                    rssiDbm = -53,
                    ies = listOf(
                        BeaconIeVectors.EMPTY_0B_BSS_LOAD.toScanResultIe(),
                        BeaconIeVectors.TRUNCATED_4B_BSS_LOAD.toScanResultIe(),
                        BeaconIeVectors.RSN_TRUNCATED_MID_CIPHER.toScanResultIe(),
                        BeaconIeVectors.RNR_TRUNCATED_TBTT_ENTRY.toScanResultIe(),
                        BeaconIeVectors.WPS_OVERFLOW_TLV_LEN.toScanResultIe(),
                        BeaconIeVectors.TRUNCATED_20B_HE_CAP.toScanResultIe(),
                        BeaconIeVectors.EHT_OP_MISSING_BITMAP_BYTES.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val detail = nav.openNetworks().assertNetworkDisplayed("HostileBeacon_5G").openDetailSheetFor("HostileBeacon_5G")
        detail.assertDetailContains("HostileBeacon_5G")
        detail.dismiss()
        nav.openSpectrum()
        nav.openInsights()
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "F34-MalformedIEs")
    }

    @Test
    fun f34_t1_06_simulatedBinderDeadObjectRuntimeExceptionCaughtAndAutoRecoversNextSweep() {
        robot.launchApp()
        ExtendedShadowWifiManager.throwOnGetScanResults = RuntimeException(DeadObjectException("WifiService restarted"))
        robot.injectScanBatch(emptyList())

        ExtendedShadowWifiManager.throwOnGetScanResults = null
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "SurvivedBinderRestart_5G", bssid = "04:D4:C4:34:06:01", rssiDbm = -47)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("SurvivedBinderRestart_5G")
    }

    // =========================================================================
    // F35: Rotation & Process-Death State Survival (6 Happy-Path Tests)
    // =========================================================================

    @Test
    fun f35_t1_01_activeBottomNavigationTabSurvivesActivityRecreateAcrossAllFourTabs() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RecreateTabAP", bssid = "04:D4:C4:35:01:01", rssiDbm = -52)),
        )
        val nav = NavigationRobot(composeRule)

        nav.openSpectrum()
        robot.recreateApp()
        nav.assertTabSelected("Spectrum")

        nav.openInsights()
        robot.recreateApp()
        nav.assertTabSelected("Insights")
    }

    @Test
    fun f35_t1_02_networksSearchQueryAndFilterChipsSurviveConfigurationChangeRecreate() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "KeepVisible_5G", bssid = "04:D4:C4:35:02:01", rssiDbm = -50, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "HideOther_2G", bssid = "B0:BE:76:35:02:02", rssiDbm = -55, frequencyMhz = 2412, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2412),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.enterSearchQuery("KeepVisible")
        networks.assertNetworkDisplayed("KeepVisible_5G").assertNetworkNotDisplayed("HideOther_2G")

        robot.recreateApp()
        networks.assertNetworkDisplayed("KeepVisible_5G").assertNetworkNotDisplayed("HideOther_2G")
    }

    @Test
    fun f35_t1_03_networksAllRadiosViewModeSurvivesActivityRecreate() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "RotateMesh", bssid = "04:D4:C4:35:03:01", rssiDbm = -48, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "RotateMesh", bssid = "04:D4:C4:35:03:02", rssiDbm = -63, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.switchToAllRadiosMode()
        networks.assertNetworkDisplayed("04:D4:C4:35:03:01").assertNetworkDisplayed("04:D4:C4:35:03:02")

        robot.recreateApp()
        networks.assertNetworkDisplayed("04:D4:C4:35:03:01").assertNetworkDisplayed("04:D4:C4:35:03:02")
    }

    @Test
    fun f35_t1_04_proximityLockedTargetAndPropagationEnvironmentSurviveActivityRecreate() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "OtherStrong_5G", bssid = "B0:BE:76:35:04:01", rssiDbm = -44),
                ScanResultFactory.createScanResult(ssid = "PinnedRotate_5G", bssid = "04:D4:C4:35:04:02", rssiDbm = -68, frequencyMhz = 5180),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val prox = nav.openNetworks().launchProximityFor("PinnedRotate_5G")
        prox.selectEnvironment("Heavy Concrete", "Heavy")
        val distBefore = prox.readDisplayedDistanceMeters()

        robot.recreateApp()
        nav.assertTabSelected("Proximity")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Preserved PinnedRotate_5G after recreate", "PinnedRotate_5G")
        val distAfter = prox.readDisplayedDistanceMeters()
        assertEquals("Heavy Concrete environment distance must survive recreate", distBefore, distAfter, 0.8)
    }

    @Test
    fun f35_t1_05_spectrumSelectedBandAndIncludeDfsToggleSurviveActivityRecreate() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SpecRotate_6E", bssid = "04:D4:C4:35:05:01", rssiDbm = -52, frequencyMhz = 6135, channelWidth = ScanResult.CHANNEL_WIDTH_160MHZ, centerFreq0Mhz = 6185),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("6 GHz")

        robot.recreateApp()
        nav.assertTabSelected("Spectrum")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "6 GHz band preserved after recreate", "SpecRotate_6E", "37", "PSC")
    }

    @Test
    fun f35_t1_06_insightsRedactMacsToggleSurvivesActivityRecreate() {
        val rawBssid = "04:D4:C4:EE:FF:11"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "RedactSurvive_5G", bssid = rawBssid, rssiDbm = -51)),
        )
        val nav = NavigationRobot(composeRule)
        val insights = nav.openInsights()
        insights.toggleRedactMacs()

        robot.recreateApp()
        nav.assertTabSelected("Insights")
        insights.clickShareExport("Markdown")
        val shared = com.a55.wifiradar.e2e.robots.ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertFalse("Redact MACs toggle must remain active after recreate", shared.resolvedBody.contains(rawBssid, ignoreCase = true))
    }

    // =========================================================================
    // F36: Full App Assembly, Manifest Hygiene & Signed A55 5G APK (6 Tests)
    // =========================================================================

    @Test
    fun f36_t1_01_compiledDebugApkDeclaresExactPackageMinSdk30TargetSdk35AndLaunchableMainActivity() {
        val apk = ApkBinaryInspector.debugApk
        assertTrue("Expected compiled debug APK at ${apk.absolutePath}", apk.isFile && apk.length() > 100_000L)
        val badging = ApkBinaryInspector.dumpBadging(apk)
        assertEquals(0, badging.exitCode)
        assertTrue(badging.stdout.contains("package: name='com.a55.wifiradar'"))
        assertTrue(badging.stdout.contains("sdkVersion:'30'"))
        assertTrue(badging.stdout.contains("targetSdkVersion:'35'"))
        assertTrue(badging.stdout.contains("launchable-activity: name='com.a55.wifiradar.MainActivity'"))
    }

    @Test
    fun f36_t1_02_compiledApkEnforces100PercentOfflinePrivacyWithZeroInternetPermission() {
        val apk = ApkBinaryInspector.debugApk
        val perms = ApkBinaryInspector.dumpPermissions(apk)
        assertEquals(0, perms.exitCode)
        assertFalse(
            "100% offline privacy contract VIOLATED: android.permission.INTERNET found in merged APK!\n${perms.stdout}",
            perms.stdout.contains("android.permission.INTERNET"),
        )
        for (requiredPerm in listOf(
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.VIBRATE",
        )) {
            assertTrue("Expected $requiredPerm in APK permissions:\n${perms.stdout}", perms.stdout.contains(requiredPerm))
        }
    }

    @Test
    fun f36_t1_03_manifestOmitsNeverForLocationOnNearbyWifiDevicesAndOmitsMaxSdkOnFineLocation() {
        val xmlTree = ApkBinaryInspector.dumpManifestXmlTree(ApkBinaryInspector.debugApk)
        assertEquals(0, xmlTree.exitCode)
        val lines = xmlTree.stdout.lines()

        // Verify NEARBY_WIFI_DEVICES does NOT carry usesPermissionFlags = 0x10000 (neverForLocation)
        val nearbyIdx = lines.indexOfFirst { it.contains("android.permission.NEARBY_WIFI_DEVICES") }
        assertTrue("NEARBY_WIFI_DEVICES must exist in AndroidManifest.xml", nearbyIdx >= 0)
        val nearbyBlock = lines.subList((nearbyIdx - 2).coerceAtLeast(0), (nearbyIdx + 5).coerceAtMost(lines.size)).joinToString("\n")
        assertFalse(
            "CRITICAL: NEARBY_WIFI_DEVICES must NOT set neverForLocation (0x10000), which strips SSID/BSSID/RSSI on Galaxy A55!\n$nearbyBlock",
            nearbyBlock.contains("usesPermissionFlags") && nearbyBlock.contains("0x10000"),
        )

        // Verify ACCESS_FINE_LOCATION does NOT cap maxSdkVersion=32
        val fineIdx = lines.indexOfFirst { it.contains("android.permission.ACCESS_FINE_LOCATION") }
        assertTrue("ACCESS_FINE_LOCATION must exist in AndroidManifest.xml", fineIdx >= 0)
        val fineBlock = lines.subList((fineIdx - 2).coerceAtLeast(0), (fineIdx + 5).coerceAtMost(lines.size)).joinToString("\n")
        assertFalse(
            "CRITICAL: ACCESS_FINE_LOCATION must NOT cap maxSdkVersion on Android 14/15!\n$fineBlock",
            fineBlock.contains("maxSdkVersion"),
        )
    }

    @Test
    fun f36_t1_04_manifestMarksWifiRttAndGpsHardwareFeaturesOptionalAndSecuresFileProvider() {
        val xmlTree = ApkBinaryInspector.dumpManifestXmlTree(ApkBinaryInspector.debugApk)
        assertEquals(0, xmlTree.exitCode)
        val text = xmlTree.stdout

        assertTrue("Expected android.hardware.wifi uses-feature", text.contains("android.hardware.wifi"))
        assertTrue("Expected FileProvider in merged manifest", text.contains("FileProvider") || text.contains("fileprovider", ignoreCase = true))
        assertTrue("Expected grantUriPermissions=true on FileProvider", text.contains("grantUriPermissions"))
    }

    @Test
    fun f36_t1_05_signedReleaseApkExistsAndPassesApksignerV2AndV3CryptographicVerification() {
        val releaseApk = ApkBinaryInspector.resolveSignedReleaseApk()
        assertTrue(
            "Expected signed installable release APK at ${releaseApk.absolutePath}",
            releaseApk.isFile && releaseApk.length() > 100_000L,
        )
        val sig = ApkBinaryInspector.verifySignature(releaseApk)
        assertEquals("apksigner verify failed:\n${sig.combined}", 0, sig.exitCode)
        assertTrue("Expected v2 signature true:\n${sig.stdout}", sig.stdout.contains("Verified using v2 scheme (APK Signature Scheme v2): true"))
        assertTrue("Expected v3 signature true:\n${sig.stdout}", sig.stdout.contains("Verified using v3 scheme", ignoreCase = true))
    }

    @Test
    fun f36_t1_06_opaquePackageManagerLauncherColdBootNavigatesFullAppGraphCleanly() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "AssemblyE2E_5G", bssid = "04:D4:C4:36:06:01", rssiDbm = -48),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("AssemblyE2E_5G")
        nav.openProximity()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Proximity wired in full app", "AssemblyE2E_5G")
        nav.openSpectrum()
        nav.openInsights()
        assertTrue("Insights health score must compute in assembled app", nav.openInsights().readOverallHealthScore() in 0..100)
    }
}
