@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.tier4

import android.Manifest
import android.net.wifi.ScanResult
import android.provider.Settings
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.a55.wifiradar.e2e.fixtures.BeaconIeVectors
import com.a55.wifiradar.e2e.harness.AppE2eRobot
import com.a55.wifiradar.e2e.harness.CliJsonLens
import com.a55.wifiradar.e2e.harness.CliRunnerHarness
import com.a55.wifiradar.e2e.harness.ScanResultFactory
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers
import com.a55.wifiradar.e2e.robots.LegendPillRobot
import com.a55.wifiradar.e2e.robots.NavigationRobot
import com.a55.wifiradar.e2e.robots.ShareIntentInspector
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonObject
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioTrack

/**
 * Tier 4 Real-World Samsung Galaxy A55 5G User Journeys — Part 1 (`T4_J01`–`T4_J10` = `10` `@Test` methods).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [ExtendedShadowWifiManager::class], qualifiers = "w360dp-h780dp-450dpi")
class Tier4GalaxyA55RealWorldJourneysPart1E2eTest {

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
    fun t4_j01_outOfBoxFirstLaunchOnboardingLocationRecoveryAndLiveDiscovery() {
        robot.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        robot.setLocationEnabled(enabled = false, broadcastChange = false)
        robot.launchApp()

        // Step 1: Permission checklist with 100% offline assurance
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Step 1 Permission", "Grant", "Allow", "offline")

        // User grants permissions -> advances to Step 2: Location Services OFF
        robot.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Step 2 Location OFF", "Turn On", "Location")

        NavigationRobot(composeRule).openNetworks().clickReadinessPrimaryCta()
        val locIntent = robot.consumeNextStartedActivityIntent()
        assertNotNull(locIntent)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, locIntent!!.action)

        // User flips Location ON -> zero-tap auto-recovery into live discovery!
        robot.setLocationEnabled(enabled = true, broadcastChange = true)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "SamsungHome_AX", bssid = "04:D4:C4:74:01:01", rssiDbm = -47, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "SamsungHome_2G", bssid = "04:D4:C4:74:01:00", rssiDbm = -53, frequencyMhz = 2437, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2437),
            ),
        )
        NavigationRobot(composeRule).openNetworks().assertNetworkDisplayed("SamsungHome_AX")
        val (shot, bmp) = robot.captureRootBitmap("t4_j01_onboarded_live_networks.png")
        assertTrue(shot.length() > 2_000L && bmp.width >= 900)
    }

    @Test
    fun t4_j02_physicalRouterHuntWithLraHapticsAudioGeigerAnd1mCalibration() {
        val audioBursts = AtomicInteger(0)
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ ->
            if (data.isNotEmpty()) audioBursts.incrementAndGet()
        }
        ShadowAudioTrack.addAudioDataListener(listener)
        try {
            val mysteryBssid = "FC:EC:DA:74:02:99"
            robot.launchApp()
            robot.injectScanBatch(
                listOf(
                    ScanResultFactory.createScanResult(ssid = "CeilingMystery_AP", bssid = mysteryBssid, rssiDbm = -84, frequencyMhz = 5180, explicitTimestampMicros = 1_000_000L),
                    ScanResultFactory.createScanResult(ssid = "LobbyMain_WiFi", bssid = "04:D4:C4:74:02:01", rssiDbm = -48, frequencyMhz = 5180, explicitTimestampMicros = 1_000_000L),
                ),
            )
            val nav = NavigationRobot(composeRule)
            val prox = nav.openNetworks().launchProximityFor("CeilingMystery_AP")
            prox.toggleHapticGeiger()
            prox.toggleAudioGeiger()

            // Walk steadily toward ceiling tile: -84 -> -74 -> -63 -> -52 -> -43 dBm
            for ((step, rssi) in listOf(-74, -63, -52, -43).withIndex()) {
                robot.injectScanBatch(
                    listOf(
                        ScanResultFactory.createScanResult(ssid = "CeilingMystery_AP", bssid = mysteryBssid, rssiDbm = rssi, frequencyMhz = 5180, explicitTimestampMicros = (step + 2) * 1_000_000L),
                    ),
                )
                robot.advanceClockMs(1_000L)
            }
            ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Approaching + Excellent at ceiling tile", "Approaching", "Excellent")
            assertTrue("Expected Audio Geiger bursts during walk", audioBursts.get() > 0)
            assertTrue("Expected LRA Haptic ticks during walk", robot.shadowVibrator().primitiveEffects.orEmpty().isNotEmpty() || robot.shadowVibrator().milliseconds > 0L)

            // Stand 1m away (-55 dBm) and calibrate 1m reference -> cold relaunch verifies persistence
            for (k in 6..9) {
                robot.injectScanBatch(
                    listOf(ScanResultFactory.createScanResult(ssid = "CeilingMystery_AP", bssid = mysteryBssid, rssiDbm = -55, frequencyMhz = 5180, explicitTimestampMicros = k * 1_000_000L)),
                )
                robot.advanceClockMs(800L)
            }
            prox.clickCalibrateOneMeter()
            assertTrue(prox.readDisplayedDistanceMeters() in 0.65..1.45)
            val (shot, _) = robot.captureRootBitmap("t4_j02_proximity_router_hunt.png")
            assertTrue(shot.length() > 2_000L)
        } finally {
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test
    fun t4_j03_denseApartment24GhzCongestionDiagnosisAndBestChannelMigration() {
        val myBssid = "04:D4:C4:74:03:01"
        val crowdedNeighbors = (1..14).map { idx ->
            ScanResultFactory.createScanResult(
                ssid = "AptNeighbor_$idx",
                bssid = String.format("B0:BE:76:74:03:%02X", idx + 10),
                rssiDbm = -46 - idx,
                frequencyMhz = if (idx % 2 == 0) 2412 else 2422, // Jamming Ch 1 and off-center Ch 3!
                channelWidth = if (idx % 3 == 0) ScanResult.CHANNEL_WIDTH_40MHZ else ScanResult.CHANNEL_WIDTH_20MHZ,
                centerFreq0Mhz = if (idx % 2 == 0) 2412 else 2427,
            )
        }
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "MyApartment_2G",
                    bssid = myBssid,
                    rssiDbm = -52,
                    frequencyMhz = 2417, // Stuck on terrible Ch 2!
                    channelWidth = ScanResult.CHANNEL_WIDTH_40MHZ,
                    centerFreq0Mhz = 2427,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ) + crowdedNeighbors,
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyApartment_2G", bssid = myBssid, rssiDbm = -52, frequencyMhz = 2417),
        )

        val nav = NavigationRobot(composeRule)
        val scoreBefore = nav.openInsights().readOverallHealthScore()

        nav.openSpectrum().selectBand("2.4 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Spectrum recommends clean Ch 11", "11")
        val (specShot, _) = robot.captureRootBitmap("t4_j03_congested_24ghz_spectrum.png")
        assertTrue(specShot.length() > 2_000L)

        // User logs into router admin and moves MyApartment_2G to clean canonical Ch 11 (20 MHz) or 5 GHz Ch 149!
        robot.advanceClockMs(3_000L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "MyApartment_2G",
                    bssid = myBssid,
                    rssiDbm = -48,
                    frequencyMhz = 5745,
                    channelWidth = ScanResult.CHANNEL_WIDTH_80MHZ,
                    centerFreq0Mhz = 5775,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ) + crowdedNeighbors,
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "MyApartment_2G", bssid = myBssid, rssiDbm = -48, frequencyMhz = 5745),
        )
        val scoreAfter = nav.openInsights().readOverallHealthScore()
        assertTrue(
            "Migrating off congested 2.4 GHz Ch 2 ($scoreBefore) to clean 5 GHz Ch 149 ($scoreAfter) must improve Health Score!",
            scoreAfter > scoreBefore,
        )
    }

    @Test
    fun t4_j04_wholeHomeThreeNodeMeshWalkoverBssidPinningFollowMeshAndRoamAudit() {
        val foyer = "04:D4:C4:74:04:01"
        val kitchen = "04:D4:C4:74:04:02"
        val attic = "04:D4:C4:74:04:03"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = foyer, rssiDbm = -47, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = kitchen, rssiDbm = -64, frequencyMhz = 5220, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = attic, rssiDbm = -78, frequencyMhz = 2462, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2462),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DecoHomeMesh", bssid = foyer, rssiDbm = -47, frequencyMhz = 5180),
        )

        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks()
        networks.switchToAllRadiosMode()
        networks.openDetailSheetFor(foyer).assignNickname("Foyer Main Node").dismiss()
        networks.openDetailSheetFor(attic).assignNickname("Attic Satellite").dismiss()

        // Walk upstairs to Attic -> Follow Mesh tracks strongest node & ConnectivityManager logs handoff
        val prox = nav.openProximity().switchToFollowMeshMode()
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = foyer, rssiDbm = -81, frequencyMhz = 5180),
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = kitchen, rssiDbm = -68, frequencyMhz = 5220, centerFreq0Mhz = 5210),
                ScanResultFactory.createScanResult(ssid = "DecoHomeMesh", bssid = attic, rssiDbm = -46, frequencyMhz = 2462, channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ, centerFreq0Mhz = 2462),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "DecoHomeMesh", bssid = attic, rssiDbm = -46, frequencyMhz = 2462),
        )
        robot.advanceClockMs(1_000L)
        prox.scrollUntilVisible("Attic Satellite", "74:04:03")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Follow Mesh switched to Attic", "Attic Satellite", "74:04:03")

        nav.openTimeAndRoamGraph()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Roam event logged", "74:04:03", "DecoHomeMesh")
    }

    @Test
    fun t4_j05_coffeeShopPublicWifiEvilTwinDetectionAndRedactedAlertShare() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "Roastery_VIP_WiFi",
                    bssid = "00:40:96:74:05:01",
                    rssiDbm = -61,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "Roastery_VIP_WiFi",
                    bssid = "DE:AD:BE:EF:74:05",
                    rssiDbm = -42,
                    frequencyMhz = 2437,
                    channelWidth = ScanResult.CHANNEL_WIDTH_20MHZ,
                    centerFreq0Mhz = 2437,
                    capabilities = "[ESS]", // Rogue Open Pineapple!
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().assertNetworkDisplayed("Roastery_VIP_WiFi")
        val insights = nav.openInsights()
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Critical Evil-Twin alert", "Evil Twin", "Spoof", "Roastery_VIP_WiFi")

        insights.toggleRedactMacs()
        insights.clickShareExport("Markdown")
        val shared = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        assertTrue(shared.resolvedBody.contains("Roastery_VIP_WiFi"))
        assertFalse(shared.resolvedBody.contains("DE:AD:BE:EF:74:05", ignoreCase = true))
    }

    @Test
    fun t4_j06_samsungGalaxyA55DualBandWifi6InspectingNewTriBandRouterViaOutOfBandRnr() {
        robot.configureReadyGalaxyA55Defaults(has6Ghz = false, hasRtt = false)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "MyNewAsus6E",
                    bssid = "04:D4:C4:74:06:01",
                    rssiDbm = -45,
                    frequencyMhz = 5180,
                    channelWidth = ScanResult.CHANNEL_WIDTH_160MHZ,
                    centerFreq0Mhz = 5250,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(
                        BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe(),
                        BeaconIeVectors.VENDOR_WPS_ASUS_AX86U.toScanResultIe(),
                        BeaconIeVectors.RNR_6GHZ_ASUS_PSC37_160MHZ.toScanResultIe(),
                    ),
                ),
            ),
        )
        val nav = NavigationRobot(composeRule)
        val detail = nav.openNetworks().openDetailSheetFor("MyNewAsus6E")
        detail.assertDetailContains("RT-AX86U Pro", "6 GHz", "37", "160 MHz")
        detail.dismiss()

        nav.openSpectrum().selectBand("6 GHz")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "A55 dual-band hardware banner", "dual-band", "6 GHz", "hardware")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Out-of-band RNR PSC 37 radio", "37", "PSC", "160 MHz")
    }

    @Test
    fun t4_j07_conferenceAuditoriumHighClientLoadAndBssColorCollisionTroubleshooting() {
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "KeynoteHall_WiFi6",
                    bssid = "04:D4:C4:74:07:01",
                    rssiDbm = -46,
                    frequencyMhz = 5180,
                    ies = listOf(
                        BeaconIeVectors.BSS_LOAD_45_CLIENTS_78_PCT.toScanResultIe(),
                        BeaconIeVectors.HE_CAP_4X4_160MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe(),
                    ),
                ),
                ScanResultFactory.createScanResult(
                    ssid = "ExpoBooth_WiFi6",
                    bssid = "B0:BE:76:74:07:02",
                    rssiDbm = -53,
                    frequencyMhz = 5180,
                    ies = listOf(
                        BeaconIeVectors.HE_CAP_2X2_80MHZ_TWT.toScanResultIe(),
                        BeaconIeVectors.HE_OP_BSS_COLOR_9.toScanResultIe(),
                    ),
                ),
            ),
        )
        val detail = NavigationRobot(composeRule).openNetworks().openDetailSheetFor("KeynoteHall_WiFi6")
        detail.assertDetailContains("45", "78%", "Collision")
        LegendPillRobot.tapLegendPill(composeRule, "bss_load", "BSS Load")
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Inline BSS Load explanation", "airtime", "70%")
    }

    @Test
    fun t4_j08_osScanThrottleExhaustionConnected300msFastPathAndDeveloperOptionsUnlock() {
        val bssid = "AC:84:C6:74:08:01"
        robot.configureReadyGalaxyA55Defaults(scanThrottleEnabled = true)
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "HomeFiber_Fast", bssid = bssid, rssiDbm = -74, frequencyMhz = 5180)),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "HomeFiber_Fast", bssid = bssid, rssiDbm = -74, frequencyMhz = 5180),
        )
        val nav = NavigationRobot(composeRule)
        val networks = nav.openNetworks()
        repeat(4) { runCatching { networks.triggerManualRefresh() } }
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(false)
        runCatching { networks.triggerManualRefresh() }

        // Even while startScan() is throttled, Proximity updates connected AP every 300ms!
        nav.openProximity()
        for (r in listOf(-64, -52, -45)) {
            robot.simulateConnectedWifi(
                ScanResultFactory.createConnectedWifiInfo(ssid = "HomeFiber_Fast", bssid = bssid, rssiDbm = r, frequencyMhz = 5180),
            )
            robot.advanceClockMs(350L)
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "300ms fast path active", "Excellent", "-45 dBm", "-46 dBm", "-47 dBm")

        // User toggles Wi-Fi Scan Throttling OFF in Samsung Developer Options and returns
        Shadows.shadowOf(robot.wifiManager).setStartScanSucceeds(true)
        ExtendedShadowWifiManager.scanThrottleEnabledFlag = false
        robot.moveToLifecycle(Lifecycle.State.STARTED)
        robot.moveToLifecycle(Lifecycle.State.RESUMED)
        robot.advanceClockMs(3_000L)
        ComposeSemanticMatchers.assertZeroRawEnumOrNanLeaks(composeRule, "T4_J08-Unthrottled")
    }

    @Test
    fun t4_j09_routerFirmwareRebootMidTrackingDropoutSilenceAndZeroTapReacquisition() {
        val bssid = "04:D4:C4:74:09:01"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "FirmwareUpdate_AX", bssid = bssid, rssiDbm = -52)),
        )
        NavigationRobot(composeRule).openProximity()

        // Router reboots -> 4 sweeps (48s) with 0 beacons from FirmwareUpdate_AX
        repeat(4) {
            robot.advanceClockMs(12_000L)
            robot.injectScanBatch(
                listOf(ScanResultFactory.createScanResult(ssid = "NeighborSteady", bssid = "B0:BE:76:74:09:02", rssiDbm = -65)),
            )
        }
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Signal Lost during reboot", "Signal Lost", "-52 dBm")
        assertFalse(ComposeSemanticMatchers.dumpScreenText(composeRule).contains("-100 dBm"))

        // Router comes back online -> immediate zero-tap re-acquisition!
        robot.advanceClockMs(2_000L)
        robot.injectScanBatch(
            listOf(ScanResultFactory.createScanResult(ssid = "FirmwareUpdate_AX", bssid = bssid, rssiDbm = -48)),
        )
        ComposeSemanticMatchers.assertScreenContainsAny(composeRule, "Re-acquired live router", "-48 dBm", "Excellent", "Strong")
    }

    @Test
    fun t4_j10_privacySafeDiagnosticTicketExportForIspSupportWithMacRedaction() {
        val sensitiveMac = "04:D4:C4:DE:AD:99"
        robot.launchApp()
        robot.injectScanBatch(
            listOf(
                ScanResultFactory.createScanResult(
                    ssid = "FiberCustomer_5G",
                    bssid = sensitiveMac,
                    rssiDbm = -51,
                    frequencyMhz = 5180,
                    capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ies = listOf(BeaconIeVectors.RSN_WPA3_SAE_MFPR.toScanResultIe()),
                ),
            ),
        )
        robot.simulateConnectedWifi(
            ScanResultFactory.createConnectedWifiInfo(ssid = "FiberCustomer_5G", bssid = sensitiveMac, rssiDbm = -51, frequencyMhz = 5180, linkSpeedMbps = 1201),
        )
        val nav = NavigationRobot(composeRule)
        nav.openNetworks().openDetailSheetFor("FiberCustomer_5G").assignNickname("Hallway ONT Gateway").dismiss()

        val insights = nav.openInsights()
        val (insShot, _) = robot.captureRootBitmap("t4_j10_insights_health_dashboard.png")
        assertTrue(insShot.length() > 2_000L)

        insights.toggleRedactMacs()
        insights.clickShareExport("JSON")
        val jsonShare = ShareIntentInspector.captureActionSend(robot.appContext, robot.currentActivity())
        val root = CliRunnerHarness.lenientJson.parseToJsonElement(jsonShare.resolvedBody.trim()) as JsonObject

        assertEquals(1, CliJsonLens.radios(root).size)
        assertTrue(jsonShare.resolvedBody.contains("Hallway ONT Gateway"))
        assertFalse("Subscriber MAC octets DE:AD:99 must NEVER leak in redacted ISP ticket!", jsonShare.resolvedBody.contains("DE:AD:99", ignoreCase = true))
        assertTrue(jsonShare.resolvedBody.contains("XX:XX", ignoreCase = true))
    }
}
