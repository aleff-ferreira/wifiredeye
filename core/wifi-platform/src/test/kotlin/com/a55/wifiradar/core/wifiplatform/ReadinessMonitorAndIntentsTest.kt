package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessStepState
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.WifiScanResultBuilder

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReadinessMonitorAndIntentsTest {

    private lateinit var app: Application
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wifiManager = app.getSystemService(WifiManager::class.java)!!
        locationManager = app.getSystemService(LocationManager::class.java)!!

        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Shadows.shadowOf(locationManager).setLocationEnabled(true)
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        Shadows.shadowOf(wifiManager).setIsScanAlwaysAvailable(true)
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
    }

    @After
    fun tearDown() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
    }

    @Test
    fun evaluatesAllSevenPriorityStatesAndZeroRawEnumLeaks() {
        val shadowApp = Shadows.shadowOf(app)
        val shadowLocation = Shadows.shadowOf(locationManager)
        val shadowWifi = Shadows.shadowOf(wifiManager)
        val monitor = ReadinessMonitor(context = app, sdkInt = 34)
        monitor.start()

        try {
            // 1. Ready
            val ready = monitor.readinessState.value
            assertTrue(ready is ScannerReadinessState.Ready)
            assertTrue(ready.canScanNow)
            assertNoRawLeaksInState(ready)

            // 2. WifiDisabledScanAlwaysOn (canScanNow == true!)
            shadowWifi.setIsScanAlwaysAvailable(true)
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = false
            app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val scanAlwaysOn = monitor.readinessState.value
            assertTrue(scanAlwaysOn is ScannerReadinessState.WifiDisabledScanAlwaysOn)
            assertTrue(scanAlwaysOn.canScanNow)
            assertEquals(
                ReadinessStepState.WARNING_SCAN_STILL_ACTIVE,
                scanAlwaysOn.checklist.wifiStep.state,
            )
            assertNoRawLeaksInState(scanAlwaysOn)

            // 3. WifiAndScanDisabled (isAirplaneMode == false) via ContentObserver on wifi_scan_always_enabled
            shadowWifi.setIsScanAlwaysAvailable(false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val wifiOff = monitor.readinessState.value
            assertTrue(wifiOff is ScannerReadinessState.WifiAndScanDisabled)
            assertFalse((wifiOff as ScannerReadinessState.WifiAndScanDisabled).isAirplaneMode)
            assertFalse(wifiOff.canScanNow)
            assertNoRawLeaksInState(wifiOff)

            // 4. WifiAndScanDisabled (isAirplaneMode == true even when scanAlwaysAvailable == true!)
            shadowWifi.setIsScanAlwaysAvailable(true)
            Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
            app.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", true))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val airplane = monitor.readinessState.value
            assertTrue(airplane is ScannerReadinessState.WifiAndScanDisabled)
            assertTrue((airplane as ScannerReadinessState.WifiAndScanDisabled).isAirplaneMode)
            assertFalse(airplane.canScanNow)
            assertNoRawLeaksInState(airplane)

            // Turn Wi-Fi back ON while in Airplane Mode -> Ready!
            @Suppress("DEPRECATION")
            wifiManager.isWifiEnabled = true
            app.sendBroadcast(Intent(WifiManager.WIFI_STATE_CHANGED_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(monitor.readinessState.value is ScannerReadinessState.Ready)
            Settings.Global.putInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)

            // 5. LocationDisabled -> auto-heals via ShadowLocationManager ContentObserver!
            shadowLocation.setLocationEnabled(false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val locationDisabled = monitor.readinessState.value
            assertTrue(locationDisabled is ScannerReadinessState.LocationDisabled)
            assertFalse(locationDisabled.canScanNow)
            assertNoRawLeaksInState(locationDisabled)

            // 6. MissingPermission takes priority over LocationDisabled
            shadowApp.denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            monitor.refresh()
            val missing = monitor.readinessState.value
            assertTrue(missing is ScannerReadinessState.MissingPermission)
            val missingList = (missing as ScannerReadinessState.MissingPermission).missingPermissions
            assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in missingList)
            assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in missingList)
            assertNoRawLeaksInState(missing)

            // 7. ApproximateOnlyNeedsPrecise when COARSE granted & FINE denied
            shadowApp.grantPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            monitor.refresh()
            val approx = monitor.readinessState.value
            assertTrue(approx is ScannerReadinessState.ApproximateOnlyNeedsPrecise)
            assertEquals(
                ReadinessAction.UPGRADE_TO_PRECISE_LOCATION,
                approx.primaryAction,
            )
            assertNoRawLeaksInState(approx)

            // 8. PermanentDenial takes priority over ApproximateOnlyNeedsPrecise, and auto-clears on grant!
            monitor.reportPermissionDenial(permanentlyDenied = true)
            val permDenied = monitor.readinessState.value
            assertTrue(permDenied is ScannerReadinessState.PermanentDenial)
            assertNoRawLeaksInState(permDenied)

            // Granting all permissions clears PermanentDenial WITHOUT calling reportPermissionDenial(false)!
            shadowLocation.setLocationEnabled(true)
            shadowApp.grantPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
            monitor.refresh()
            assertTrue(monitor.readinessState.value is ScannerReadinessState.Ready)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun permanentDenialFromCompleteDenialUnlatchesToApproximateUpgradeWhenCoarseEnabledInSettings() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val monitor = ReadinessMonitor(context = app, sdkInt = 34)
        monitor.reportPermissionDenial(permanentlyDenied = true)
        assertTrue(monitor.readinessState.value is ScannerReadinessState.PermanentDenial)

        // User goes to OS Settings and enables Approximate Location -> should offer 1-tap Upgrade!
        shadowApp.grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        monitor.refresh()
        assertTrue(monitor.readinessState.value is ScannerReadinessState.ApproximateOnlyNeedsPrecise)

        // If user permanently declines Precise upgrade, PermanentDenial latches over ApproximateOnly
        monitor.reportPermissionDenial(permanentlyDenied = true)
        assertTrue(monitor.readinessState.value is ScannerReadinessState.PermanentDenial)
    }

    @Test
    fun api30PermissionAndReceiverBranchSkipsNearbyWifiDevicesCleanly() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.denyPermissions(Manifest.permission.NEARBY_WIFI_DEVICES)
        shadowApp.grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            WifiPlatformIntents.requiredRuntimePermissions(sdkInt = 30).toList(),
        )

        var rttCallbackInvoked = false
        val monitor = ReadinessMonitor(
            context = app,
            sdkInt = 30,
            onCapabilitiesMayHaveChanged = { rttCallbackInvoked = true },
        )
        monitor.start()
        try {
            assertTrue(monitor.readinessState.value is ScannerReadinessState.Ready)
            app.sendBroadcast(Intent(WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(rttCallbackInvoked)
        } finally {
            monitor.stop()
        }
    }

    @Test
    fun selfHealingTransitionToCanScanTriggersImmediateSeedAndActiveScan() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this)

        Shadows.shadowOf(locationManager).setLocationEnabled(false)
        val sr = WifiScanResultBuilder()
            .setSsid("AutoRecovered_5G")
            .setBssid("aa:bb:cc:dd:ee:99")
            .setRssi(-51)
            .setFrequency(5180)
            .build()
        Shadows.shadowOf(wifiManager).setScanResults(listOf(sr))

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
        )
        dataSource.start()
        try {
            assertTrue(dataSource.readinessState.value is ScannerReadinessState.LocationDisabled)
            assertEquals(null, dataSource.rawScanBatches.value)
            assertEquals(0, dataSource.scanTelemetry.value.totalActiveScansTriggered)

            // User turns ON Location Services -> live recovery triggers seed harvest + active scan!
            Shadows.shadowOf(locationManager).setLocationEnabled(true)
            app.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            assertTrue(dataSource.readinessState.value is ScannerReadinessState.Ready)
            assertNotNull(dataSource.rawScanBatches.value)
            assertEquals(1, dataSource.rawScanBatches.value!!.entries.size)
            assertEquals("AA:BB:CC:DD:EE:99", dataSource.rawScanBatches.value!!.entries.single().bssid)
            assertEquals(1, dataSource.scanTelemetry.value.totalActiveScansTriggered)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun wifiPlatformIntentsBuildAndLaunchWithFallbackResilience() {
        val shadowApp = Shadows.shadowOf(app)

        assertTrue(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.OPEN_WIFI_SETTINGS_PANEL))
        val wifiIntent = shadowApp.nextStartedActivity
        assertNotNull(wifiIntent)
        assertEquals(Settings.Panel.ACTION_WIFI, wifiIntent.action)
        assertTrue((wifiIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)

        assertTrue(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.OPEN_LOCATION_SETTINGS))
        val locIntent = shadowApp.nextStartedActivity
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, locIntent.action)

        assertTrue(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.OPEN_APP_PERMISSION_SETTINGS))
        val appDetailsIntent = shadowApp.nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetailsIntent.action)
        assertEquals("package:${app.packageName}", appDetailsIntent.dataString)

        @Suppress("DEPRECATION")
        val expectedScanAlways = WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE
        assertTrue(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.OPEN_SCAN_ALWAYS_AVAILABLE))
        assertEquals(expectedScanAlways, shadowApp.nextStartedActivity.action)

        assertTrue(WifiPlatformIntents.launchDeveloperOptions(app))
        assertEquals(
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            shadowApp.nextStartedActivity.action,
        )

        assertFalse(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.NONE))
        assertFalse(WifiPlatformIntents.launchReadinessAction(app, ReadinessAction.REQUEST_RUNTIME_PERMISSIONS))

        // Verify fallback when primary panel intent throws ActivityNotFoundException
        val attemptedActions = mutableListOf<String?>()
        val fallbackContext: Context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                attemptedActions += intent.action
                if (intent.action == Settings.Panel.ACTION_WIFI) {
                    throw ActivityNotFoundException("No panel on stripped ROM")
                }
            }
        }
        assertTrue(
            WifiPlatformIntents.launchReadinessAction(
                fallbackContext,
                ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
            ),
        )
        assertEquals(
            listOf(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS),
            attemptedActions,
        )
    }

    private fun assertNoRawLeaksInState(state: ScannerReadinessState) {
        val texts = buildList {
            add(state.headline)
            add(state.plainEnglishExplanation)
            state.primaryActionLabel?.let(::add)
            for (step in state.checklist.steps) {
                add(step.title)
                add(step.plainEnglishDetail)
                step.actionButtonLabel?.let(::add)
            }
        }
        for (text in texts) {
            assertFalse("Raw enum leak detected in: $text", SemanticFormatters.containsRawEnumLeak(text))
            assertFalse("Raw permission constant leaked in: $text", text.contains("ACCESS_FINE_LOCATION"))
            assertFalse("Raw permission constant leaked in: $text", text.contains("ACCESS_COARSE_LOCATION"))
            assertFalse("Raw permission constant leaked in: $text", text.contains("NEARBY_WIFI_DEVICES"))
        }
    }
}
