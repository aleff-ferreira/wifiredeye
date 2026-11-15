package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.shadows.ShadowScanResult

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenBucketAndHybridScannerTest {

    private lateinit var app: Application
    private lateinit var wifiManager: WifiManager
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wifiManager = app.getSystemService(WifiManager::class.java)!!
        locationManager = app.getSystemService(LocationManager::class.java)!!

        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Shadows.shadowOf(locationManager).setLocationEnabled(true)
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        Shadows.shadowOf(wifiManager).setStartScanSucceeds(true)
        Shadows.shadowOf(wifiManager).setScanResults(emptyList())
        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            -1,
        )
    }

    @After
    fun tearDown() {
        Shadows.shadowOf(wifiManager).setStartScanSucceeds(true)
        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            -1,
        )
    }

    @Test
    fun throttledTokenBucketEnforcesFourPer120sBurst4sSteady30sAndExactRecharge() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 1_000L)

        val ap = ShadowScanResult.newInstance("Home_6", "aa:bb:cc:11:22:33", "[WPA2]", -52, 5180)
        Shadows.shadowOf(wifiManager).setScanResults(listOf(ap))

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            isScanThrottleEnabledOverride = { true },
            sdkInt = 34,
            radioCapabilityBackend = null,
            triggerInitialScanOnStart = false,
            rttTimeoutMs = 2_500L,
        )
        dataSource.start()
        try {
            // Trigger 1 (burstMode = true -> 4s pacing) at t = 1_000 ms
            val r1 = dataSource.triggerActiveScan(burstMode = true)
            assertEquals(ScanTriggerResult.Triggered(remainingTokens = 3, unthrottledMode = false), r1)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dataSource.scanTelemetry.value.isBurstModeActive)

            // Auto-burst #2 at +4_000 ms (t = 5_000 ms)
            advanceTimeBy(4_000L)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, dataSource.scanTelemetry.value.totalActiveScansTriggered)
            assertEquals(2, dataSource.scanTelemetry.value.availableTokens)

            // Auto-burst #3 at +4_000 ms (t = 9_000 ms)
            advanceTimeBy(4_000L)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(3, dataSource.scanTelemetry.value.totalActiveScansTriggered)
            assertEquals(1, dataSource.scanTelemetry.value.availableTokens)

            // Auto-burst #4 at +4_000 ms (t = 13_000 ms) -> exhausts 4th token!
            advanceTimeBy(4_000L)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(4, dataSource.scanTelemetry.value.totalActiveScansTriggered)
            assertEquals(0, dataSource.scanTelemetry.value.availableTokens)
            assertTrue(dataSource.scanTelemetry.value.isCurrentlyThrottled)
            assertFalse(dataSource.scanTelemetry.value.isBurstModeActive)

            // 5th manual scan at t = 13_000 ms is blocked with exact nextTokenInMs = 108_000 ms
            val r5 = dataSource.triggerActiveScan(burstMode = false)
            assertEquals(ScanTriggerResult.ThrottledByTokenBucket(nextTokenInMs = 108_000L), r5)

            val pillAtThrottle = SemanticFormatters.formatScanStatusPill(
                networkCount = 1,
                telemetry = dataSource.scanTelemetry.value,
            )
            assertEquals("OS Throttle Active · Next active scan in 108s (Passive Live)", pillAtThrottle)

            // At t = 120_999 ms (+107_999 ms): still 0 tokens
            advanceTimeBy(107_999L)
            runCurrent()
            assertEquals(0, dataSource.scanTelemetry.value.availableTokens)

            // At exact t = 121_000 ms (t0 + 120_000 ms): Token #1 recharges and auto-triggers steady scan!
            advanceTimeBy(1L)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(5, dataSource.scanTelemetry.value.totalActiveScansTriggered)

            // Steady pacing check on fresh scheduler: triggers every 30_000 ms
            val steadyScheduler = TokenBucketScanScheduler()
            steadyScheduler.onStartScanAccepted(nowElapsedMs = 0L, rawThrottleEnabled = true, burstMode = false)
            assertFalse(steadyScheduler.shouldAutoTriggerAt(29_999L, rawThrottleEnabled = true))
            assertTrue(steadyScheduler.shouldAutoTriggerAt(30_000L, rawThrottleEnabled = true))
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun unthrottledModeSweepsEvery2500msAndEnforces400msPostCallbackDebounce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 10_000L)

        val ap = ShadowScanResult.newInstance("DevMode_AP", "12:34:56:78:9a:bc", "[WPA2]", -47, 5180)
        Shadows.shadowOf(wifiManager).setScanResults(listOf(ap))

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            isScanThrottleEnabledOverride = { false },
        )
        dataSource.start()
        try {
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, dataSource.scanTelemetry.value.totalActiveScansTriggered)
            assertFalse(dataSource.scanTelemetry.value.isScanThrottleEnabled)

            val pill = SemanticFormatters.formatScanStatusPill(1, dataSource.scanTelemetry.value)
            assertTrue(pill.startsWith("Unthrottled Live · 1 Network"))

            // Before 2_500 ms: still 1 active scan
            advanceTimeBy(2_499L)
            runCurrent()
            assertEquals(1, dataSource.scanTelemetry.value.totalActiveScansTriggered)

            // At exact +2_500 ms: triggers 2nd active scan!
            advanceTimeBy(1L)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, dataSource.scanTelemetry.value.totalActiveScansTriggered)

            // Verify 400 ms post-callback debounce when passive scan arrives at +2_300 ms
            advanceTimeBy(2_300L)
            val apUpdated = ShadowScanResult.newInstance("DevMode_AP", "12:34:56:78:9a:bc", "[WPA2]", -43, 5180)
            Shadows.shadowOf(wifiManager).setScanResults(listOf(apUpdated))
            app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // +200 ms after passive callback (< 400 ms debounce): must NOT trigger yet
            advanceTimeBy(200L)
            runCurrent()
            assertEquals(2, dataSource.scanTelemetry.value.totalActiveScansTriggered)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun osRejectionAndExtraResultsUpdatedFalsePromoteThrottlePillAndDetectPassiveProfile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 5_000L)

        val cachedAp = ShadowScanResult.newInstance("Cached_AP", "aa:bb:cc:dd:ee:11", "[ESS]", -63, 2412)
        Shadows.shadowOf(wifiManager).setScanResults(listOf(cachedAp))
        Shadows.shadowOf(wifiManager).setStartScanSucceeds(false)

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            isScanThrottleEnabledOverride = { false }, // Even if raw is false, OS rejection promotes throttle!
            sdkInt = 34,
            radioCapabilityBackend = null,
            triggerInitialScanOnStart = false,
            rttTimeoutMs = 2_500L,
        )
        dataSource.start()
        try {
            // Strike 1
            val rej1 = dataSource.triggerActiveScan(burstMode = false)
            assertEquals(ScanTriggerResult.RejectedByOs(nextRetryInMs = 30_000L), rej1)
            val tel1 = dataSource.scanTelemetry.value
            assertTrue(tel1.lastTriggerRejectedByOs)
            assertTrue(tel1.isScanThrottleEnabled)
            assertTrue(tel1.isCurrentlyThrottled)
            assertNotNull(tel1.lastScanTimestampMs)
            assertNotNull(dataSource.rawScanBatches.value)
            assertEquals(1, dataSource.rawScanBatches.value!!.entries.size)

            assertEquals(
                "OS Throttle Active · Next active scan in 30s (Passive Live)",
                SemanticFormatters.formatScanStatusPill(1, tel1),
            )

            // Strike 2 & Strike 3 cold rejections -> promotes isPassiveOnlyRestrictedProfile!
            dataSource.triggerActiveScan(burstMode = false)
            dataSource.triggerActiveScan(burstMode = false)
            assertTrue(dataSource.scanTelemetry.value.isPassiveOnlyRestrictedProfile)
            assertTrue(
                SemanticFormatters.formatScanStatusPill(1, dataSource.scanTelemetry.value)
                    .startsWith("Passive Scan Mode"),
            )

            // EXTRA_RESULTS_UPDATED == false broadcast also marks OS throttle while preserving cached APs
            Shadows.shadowOf(wifiManager).setStartScanSucceeds(true)
            advanceTimeBy(31_000L)
            runCurrent()
            dataSource.triggerActiveScan(burstMode = false)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dataSource.scanTelemetry.value.lastTriggerRejectedByOs)

            app.sendBroadcast(
                Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                    .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(dataSource.scanTelemetry.value.lastTriggerRejectedByOs)
            assertTrue(dataSource.scanTelemetry.value.isCurrentlyThrottled)
            assertEquals(1, dataSource.rawScanBatches.value!!.entries.size)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun dualPassiveListenersDeduplicateWithin250msWhileAcceptingMutatedBatchAtZeroDelta() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 20_000L)

        val apInitial = ShadowScanResult.newInstance("Dual_AP", "aa:bb:cc:dd:ee:55", "[WPA2]", -68, 5180)
        Shadows.shadowOf(wifiManager).setScanResults(listOf(apInitial))

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            isScanThrottleEnabledOverride = { true },
            sdkInt = 34,
            radioCapabilityBackend = null,
            triggerInitialScanOnStart = false,
            rttTimeoutMs = 2_500L,
        )
        dataSource.start()
        try {
            // Fire ScanResultsCallback first at t = 20_000 ms
            dataSource.hybridWifiScanner.scanResultsCallback.onScanResultsAvailable()
            assertEquals(1, dataSource.scanTelemetry.value.totalPassiveScansReceived)
            assertEquals(-68, dataSource.rawScanBatches.value!!.entries.single().rssiDbm)

            // Fire BroadcastReceiver 50 ms later with IDENTICAL ScanResults -> deduplicated!
            clock.advanceManualMs(50L)
            app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, dataSource.scanTelemetry.value.totalPassiveScansReceived)

            // Mutate RSSI at dt = 0 ms and broadcast -> structural signature changed, accepted immediately!
            val apStronger = ShadowScanResult.newInstance("Dual_AP", "aa:bb:cc:dd:ee:55", "[WPA2]", -49, 5180)
            Shadows.shadowOf(wifiManager).setScanResults(listOf(apStronger))
            app.sendBroadcast(Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, dataSource.scanTelemetry.value.totalPassiveScansReceived)
            assertEquals(-49, dataSource.rawScanBatches.value!!.entries.single().rssiDbm)

            // BlockedByReadiness when Location is disabled
            Shadows.shadowOf(locationManager).setLocationEnabled(false)
            val blocked = dataSource.triggerActiveScan(burstMode = false)
            assertTrue(blocked is ScanTriggerResult.BlockedByReadiness)
            assertTrue(
                (blocked as ScanTriggerResult.BlockedByReadiness).readinessState is
                    ScannerReadinessState.LocationDisabled,
            )
        } finally {
            dataSource.close()
        }
    }
}
