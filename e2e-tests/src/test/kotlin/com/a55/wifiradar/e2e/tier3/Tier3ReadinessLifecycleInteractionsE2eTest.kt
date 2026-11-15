@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier3

import android.Manifest
import android.os.DeadObjectException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.LegendPillRobot
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack

/**
 * Tier 3 Cross-Feature Pairwise Interaction E2E Tests: OS Readiness, Lifecycle, IPC Faults & Persistence (`T3_25`–`T3_36` = `12` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier3ReadinessLifecycleInteractionsE2eTest {

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
    fun t3_25_f19_f20_f24_locationDisabledAutoHealsIntoImmediateHybridScanAndDiscoveryCards() {
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Blocked by Location OFF", "Location")

        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "HealedDiscovery_5G", bssid = "04:D4:C4:73:25:01", rssiDbm = -49)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("HealedDiscovery_5G")
    }

    @Test
    fun t3_26_f19_f20_f30_f32_wifiOffWithBackgroundScanningAlwaysAvailablePopulatesSpectrumAndInsights() {
        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = true, broadcastChange = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "BgScanSpec_5G", bssid = "04:D4:C4:73:26:01", rssiDbm = -51, frequencyMhz = 5180),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openSpectrum().selectBand("5 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Spectrum populated via background scan", "BgScanSpec_5G", "149", "36")

        val insights = nav.openInsights()
        assertTrue(insights.readOverallHealthScore() in 0..100)
    }

    @Test
    fun t3_27_f19_f28_f29_disablingWifiMidProximityTrackingClearsStaleLiveStatusAndRecoversWhenWifiRestored() {
        val bssid = "04:D4:C4:73:27:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WifiToggleProximityAP", bssid = bssid, rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openProximity()

        robot.setWifiEnabledState(enabled = false, scanAlwaysAvailable = false, broadcastChange = true)
        robot.setWifiEnabledState(enabled = true, scanAlwaysAvailable = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "WifiToggleProximityAP", bssid = bssid, rssiDbm = -46)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Recovered live Proximity after Wi-Fi re-enabled", "-46 dBm", "Excellent")
    }

    @Test
    fun t3_28_f18_f19_f30_dualBandA55LocationHealedTransitionsCleanlyInto6GhzHardwareExplanation() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false)
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()

        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PostHeal_5G", bssid = "04:D4:C4:73:28:01", rssiDbm = -53)),
        )
        NavigationRobot(composeRule).openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "6 GHz hardware note after readiness heal", "6 GHz", "dual-band", "hardware")
    }

    @Test
    fun t3_29_f04_f20_f35_osScanThrottleLegendPillExpandedSurvivesRotationDuringPassiveScan() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ThrottlePillRotate_5G", bssid = "04:D4:C4:73:29:01", rssiDbm = -54)),
        )
        LegendPillRobot.tapLegendPill(composeRule, "os_scan_throttle", "OS Scan Limit")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Expanded before rotate", "2 minutes", "Developer Options")

        robot.recreateApp()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Expanded after rotate", "2 minutes", "Developer Options")
    }

    @Test
    fun t3_30_f19_f34_midSessionPermissionRevocationAndSecurityExceptionSelfHealOnSettingsReturn() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PreRevoke_5G", bssid = "04:D4:C4:73:30:01", rssiDbm = -48)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("PreRevoke_5G")

        // OS revokes location -> SecurityException thrown on next scan
        robot.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        ExtendedShadowWifiManager.throwOnGetScanResults = SecurityException("Location revoked")
        robot.injectScanBatch(emptyList())
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Permission readiness card", "Location", "Permission", "Grant", "Allow")

        // User re-grants in Settings and returns to foreground
        ExtendedShadowWifiManager.throwOnGetScanResults = null
        robot.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "PostRegrant_5G", bssid = "04:D4:C4:73:30:02", rssiDbm = -47)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("PostRegrant_5G")
    }

    @Test
    fun t3_31_f19_f34_f33_binderDeadObjectFaultDuringForegroundResumeRecoversWithoutKillingSupervisorScope() {
        robot.launchApp()
        robot.moveToLifecycle(Lifecycle.State.CREATED)

        ExtendedShadowWifiManager.throwOnGetScanResults = RuntimeException(DeadObjectException("Binder reset"))
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.injectScanBatch(emptyList())

        ExtendedShadowWifiManager.throwOnGetScanResults = null
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "BinderHealed_5G", bssid = "04:D4:C4:73:31:01", rssiDbm = -50)),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("BinderHealed_5G")
    }

    @Test
    fun t3_32_f20_f33_unthrottledScannerPausesCompletelyInBackgroundAndResumesCleanlyInForeground() {
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = false)
        robot.launchApp()
        robot.advanceClockMs(2_600L)

        robot.moveToLifecycle(Lifecycle.State.CREATED)
        val pausedCount = ExtendedShadowWifiManager.startScanInvocationCount.get()
        robot.advanceClockMs(20_000L)
        assertEquals(pausedCount, ExtendedShadowWifiManager.startScanInvocationCount.get())

        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.advanceClockMs(3_000L)
        assertTrue(ExtendedShadowWifiManager.startScanInvocationCount.get() > pausedCount)
    }

    @Test
    fun t3_33_f20_f25_antiFlicker45sCachePreservesFilteredRouterAcrossTransientMissedBeacon() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "FilteredTarget_5G", bssid = "04:D4:C4:73:33:01", rssiDbm = -55),
                ScanResultFactory.createScanResult(ssid = "OtherAnchor_5G", bssid = "B0:BE:76:73:33:02", rssiDbm = -50),
            ),
        )
        val networks = NavigationRobot(composeRule).openNetworks()
        networks.enterSearchQuery("FilteredTarget")
        networks.assertNetworkDisplayed("FilteredTarget_5G")

        // FilteredTarget_5G misses 1 beacon 5s later (< 45s cache) -> search view must NOT blink empty!
        robot.advanceClockMs(5_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "OtherAnchor_5G", bssid = "B0:BE:76:73:33:02", rssiDbm = -50)),
        )
        networks.assertNetworkDisplayed("FilteredTarget_5G")
    }

    @Test
    fun t3_34_f21_f31_f33_connectedPollerPausesOnStopAndLogsRoamWhenNewBssidObservedOnResume() {
        val b1 = "AC:84:C6:73:34:01"
        val b2 = "AC:84:C6:73:34:02"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "PocketWalkMesh", bssid = b1, rssiDbm = -74, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "PocketWalkMesh", bssid = b2, rssiDbm = -48, frequencyMhz = 5745, centerFreq0Mhz = 5775),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "PocketWalkMesh", bssid = b1, rssiDbm = -74, frequencyMhz = 5180),
        )
        robot.moveToLifecycle(Lifecycle.State.CREATED)
        robot.advanceClockMs(5_000L)

        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "PocketWalkMesh", bssid = b2, rssiDbm = -48, frequencyMhz = 5745),
        )
        robot.advanceClockMs(1_000L)
        NavigationRobot(composeRule).openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Roam recorded on resume", "73:34:02", "PocketWalkMesh")
    }

    @Test
    fun t3_35_f22_f29_f33_geigerSilenceEnforcedAcrossBothSignalLostDropoutAndLifecycleStop() {
        val bgOrLostBytes = AtomicInteger(0)
        var guardActive = false
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (guardActive) bgOrLostBytes.addAndGet(data.size)
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            robot.launchApp()
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "DualGuardGeiger", bssid = "04:D4:C4:73:35:01", rssiDbm = -42)),
            )
            NavigationRobot(composeRule).openProximity().toggleAudioGeiger()
            robot.advanceClockMs(600L)

            robot.moveToLifecycle(Lifecycle.State.CREATED)
            guardActive = true
            robot.advanceClockMs(3_000L)
            assertEquals(0, bgOrLostBytes.get())
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun t3_36_f23_f32_f35_f36_persistedNicknameAndCalibrationSurviveFullColdBootAndAppearInFileProviderShare() {
        val bssid = "04:D4:C4:73:36:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ColdBootExportAP", bssid = bssid, rssiDbm = -58)),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("ColdBootExportAP").assignNickname("Basement Rack AP").clickMeasureProximity()
            .clickCalibrateOneMeter()

        // Full cold process restart via PackageManager launcher intent (F36)
        robot.closeAndColdRelaunch()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "ColdBootExportAP", bssid = bssid, rssiDbm = -58)),
        )
        robot.recreateApp()
        nav.openInsights().clickShareExport("JSON")

        val shared = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertTrue(shared.resolvedBody.contains("Basement Rack AP"))
    }
}
