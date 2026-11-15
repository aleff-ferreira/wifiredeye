package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.app.Application
import android.content.Intent
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.MacAddress
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.WifiRttManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.MacAddressUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.RangingResultBuilder
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.shadows.WifiScanResultBuilder
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectedWifiAndRttTest {

    private lateinit var app: Application
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var locationManager: LocationManager
    private lateinit var rttManager: WifiRttManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wifiManager = app.getSystemService(WifiManager::class.java)!!
        connectivityManager = app.getSystemService(ConnectivityManager::class.java)!!
        locationManager = app.getSystemService(LocationManager::class.java)!!
        rttManager = app.getSystemService(WifiRttManager::class.java)!!

        ShadowConnectivityManager.reset()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Shadows.shadowOf(locationManager).setLocationEnabled(true)
        @Suppress("DEPRECATION")
        wifiManager.isWifiEnabled = true
        Shadows.shadowOf(wifiManager).setConnectionInfo(null)
    }

    @After
    fun tearDown() {
        ShadowConnectivityManager.reset()
        Shadows.shadowOf(wifiManager).setConnectionInfo(null)
    }

    @Test
    fun networkCallbackReceivesOfflineWanLessWifiExtractsLinkPropsAndClearsOnLost() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 5_000L)

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
            assertNull(dataSource.connectedWifi.value)

            val wifiInfo = WifiInfo.Builder()
                .setSsid("LocalLab_NoWAN".encodeToByteArray())
                .setBssid("aa:bb:cc:dd:ee:01")
                .setRssi(-51)
                .setCurrentSecurityType(WifiInfo.SECURITY_TYPE_SAE)
                .build()

            val network = ShadowNetwork.newInstance(101)
            val caps = ShadowNetworkCapabilities.newInstance()
            Shadows.shadowOf(caps).apply {
                addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // Deliberately NO NET_CAPABILITY_INTERNET to verify offline router resilience!
                setTransportInfo(wifiInfo)
            }
            val shadowCm = Shadows.shadowOf(connectivityManager)
            shadowCm.setNetworkCapabilities(network, caps)
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            val linkProps = LinkProperties().apply {
                // LinkAddress ctor / addLinkAddress are hidden APIs: reach them reflectively.
                val linkAddress = org.robolectric.util.ReflectionHelpers.callConstructor(
                    LinkAddress::class.java,
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(
                        InetAddress::class.java,
                        InetAddress.getByName("192.168.50.24"),
                    ),
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 24),
                )
                org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(
                    this,
                    "addLinkAddress",
                    org.robolectric.util.ReflectionHelpers.ClassParameter.from(LinkAddress::class.java, linkAddress),
                )
            }
            shadowCm.networkCallbacks.forEach { cb ->
                cb.onLinkPropertiesChanged(network, linkProps)
            }

            val obs = dataSource.connectedWifi.value
            assertNotNull(obs)
            obs!!
            assertEquals("AA:BB:CC:DD:EE:01", obs.bssid)
            assertEquals("LocalLab_NoWAN", obs.ssid)
            assertFalse(obs.isLocationRedacted)
            assertEquals(-51, obs.rssiDbm)
            assertEquals(WifiInfo.SECURITY_TYPE_SAE, obs.securityTypeCode)
            assertEquals("192.168.50.24", obs.ipAddressV4)

            // Trigger onLost -> clears connectedWifi immediately!
            shadowCm.networkCallbacks.forEach { cb ->
                cb.onLost(network)
            }
            assertNull(dataSource.connectedWifi.value)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun tracks300msHighFrequencyPollingLegacyLinkSpeedRedactedMacAndStopsOnLifecycle() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 1_000L)

        val legacyInfo = ShadowWifiInfo.newInstance()
        val shadowInfo = Shadows.shadowOf(legacyInfo)
        shadowInfo.setSSID("\"Galaxy_Mesh\"")
        shadowInfo.setBSSID("aa-bb-cc-dd-ee-77")
        shadowInfo.setRssi(-64)
        shadowInfo.setFrequency(5240)
        shadowInfo.setLinkSpeed(866)
        // 192.168.1.42 in little-endian int = 0x2A01A8C0
        shadowInfo.setInetAddress(InetAddress.getByName("192.168.1.42"))
        Shadows.shadowOf(wifiManager).setConnectionInfo(legacyInfo)

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
            val initial = dataSource.connectedWifi.value
            assertNotNull(initial)
            initial!!
            assertEquals("AA:BB:CC:DD:EE:77", initial.bssid)
            assertEquals("Galaxy_Mesh", initial.ssid)
            assertEquals(-64, initial.rssiDbm)
            assertEquals(5240, initial.frequencyMhz)
            assertEquals(866, initial.txLinkSpeedMbps)
            assertEquals(866, initial.rxLinkSpeedMbps)
            assertEquals("192.168.1.42", initial.ipAddressV4)

            // Without high-frequency polling, mutating ShadowWifiInfo silently does NOT poll at 300ms
            shadowInfo.setRssi(-58)
            advanceTimeBy(600L)
            runCurrent()
            assertEquals(-64, dataSource.connectedWifi.value!!.rssiDbm)

            // RSSI_CHANGED_ACTION updates immediately even when 300ms polling is disabled
            app.sendBroadcast(
                Intent(WifiManager.RSSI_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_NEW_RSSI, -55),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(-55, dataSource.connectedWifi.value!!.rssiDbm)

            // Enable 300 ms high-frequency polling -> samples mutated RSSI every 300 ms!
            dataSource.setHighFrequencyConnectedPolling(true)
            runCurrent()
            shadowInfo.setRssi(-46)
            advanceTimeBy(300L)
            runCurrent()
            assertEquals(-46, dataSource.connectedWifi.value!!.rssiDbm)

            shadowInfo.setRssi(-39)
            advanceTimeBy(300L)
            runCurrent()
            assertEquals(-39, dataSource.connectedWifi.value!!.rssiDbm)

            // Disable 300 ms polling -> halts immediately
            dataSource.setHighFrequencyConnectedPolling(false)
            runCurrent()
            shadowInfo.setRssi(-31)
            advanceTimeBy(900L)
            runCurrent()
            assertEquals(-39, dataSource.connectedWifi.value!!.rssiDbm)

            // Redacted BSSID 02:00:00:00:00:00 sets isLocationRedacted = true
            shadowInfo.setBSSID(MacAddressUtils.ANDROID_REDACTED_MAC)
            shadowInfo.setRssi(-50)
            app.sendBroadcast(
                Intent(WifiManager.RSSI_CHANGED_ACTION)
                    .putExtra(WifiManager.EXTRA_NEW_RSSI, -50),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val redactedObs = dataSource.connectedWifi.value!!
            assertEquals(MacAddressUtils.ANDROID_REDACTED_MAC, redactedObs.bssid)
            assertTrue(redactedObs.isLocationRedacted)

            // Lifecycle stop halts high-frequency polling as well
            dataSource.setHighFrequencyConnectedPolling(true)
            runCurrent()
            dataSource.stop()
            shadowInfo.setRssi(-29)
            advanceTimeBy(900L)
            runCurrent()
            assertEquals(-50, dataSource.connectedWifi.value!!.rssiDbm)
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun safeWifiRttAdapterHandlesPositiveNegativeCloseRangeFailedStatusAndTimeouts() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 15_000L)

        val targetBssid = "AA:BB:CC:DD:EE:01"
        val rttScanResult = WifiScanResultBuilder()
            .setSsid("RTT_80211mc_AP")
            .setBssid(targetBssid)
            .setRssi(-45)
            .setFrequency(5180)
            .setIs80211McRttResponder(true)
            .build()
        Shadows.shadowOf(wifiManager).setScanResults(listOf(rttScanResult))

        val dataSource = AndroidWifiPlatformDataSource(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            isScanThrottleEnabledOverride = { true },
            sdkInt = 34,
            radioCapabilityBackend = null,
            triggerInitialScanOnStart = true,
            rttTimeoutMs = 2_500L,
        )
        dataSource.start()
        try {
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // 1. Positive RTT distance: 3420 mm -> 3.42 m
            val positiveSuccess = RangingResultBuilder(
                RangingResult.STATUS_SUCCESS,
                MacAddress.fromString(targetBssid),
                15_000_000L,
                3420,
            )
                .setDistanceStandardDeviation(150)
                .setRssi(-44)
                .setNumAttemptedMeasurements(8)
                .setNumSuccessfulMeasurements(7)
                .setIs80211mcMeasurement(true)
                .build()
            Shadows.shadowOf(rttManager).setRangeResults(listOf(positiveSuccess))

            val m1 = dataSource.requestRttRange("aa-bb-cc-dd-ee-01")
            assertNotNull(m1)
            m1!!
            assertEquals(targetBssid, m1.bssid)
            assertEquals(3.42, m1.distanceMeters, 1e-6)
            assertEquals(3420, m1.rawDistanceMm)
            assertEquals(0.15, m1.distanceStdDevMeters, 1e-6)
            assertEquals(-44, m1.rssiDbm)
            assertEquals(8, m1.numAttemptedMeasurements)
            assertEquals(7, m1.numSuccessfulMeasurements)

            // 2. Negative close-range calibration offset: -180 mm -> clamped 0.0 m while preserving rawDistanceMm = -180
            val closeNegativeSuccess = RangingResultBuilder(
                RangingResult.STATUS_SUCCESS,
                MacAddress.fromString(targetBssid),
                16_000_000L,
                -180,
            )
                .setDistanceStandardDeviation(90)
                .setRssi(-35)
                .build()
            Shadows.shadowOf(rttManager).setRangeResults(listOf(closeNegativeSuccess))

            val m2 = dataSource.requestRttRange(targetBssid)
            assertNotNull(m2)
            assertEquals(0.0, m2!!.distanceMeters, 1e-6)
            assertEquals(-180, m2.rawDistanceMm)

            // 3. STATUS_FAIL and STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC must NOT throw IllegalStateException!
            val failStatus = RangingResultBuilder(
                RangingResult.STATUS_FAIL,
                MacAddress.fromString(targetBssid),
                17_000_000L,
                2000,
            ).build()
            Shadows.shadowOf(rttManager).setRangeResults(listOf(failStatus))
            assertNull(dataSource.requestRttRange(targetBssid))

            val nonMcStatus = RangingResultBuilder(
                RangingResult.STATUS_RESPONDER_DOES_NOT_SUPPORT_IEEE80211MC,
                MacAddress.fromString(targetBssid),
                18_000_000L,
                2000,
            ).build()
            Shadows.shadowOf(rttManager).setRangeResults(listOf(nonMcStatus))
            assertNull(dataSource.requestRttRange(targetBssid))

            // 4. Unknown BSSID, redacted BSSID, and malformed BSSID return null cleanly
            assertNull(dataSource.requestRttRange("11:22:33:44:55:99"))
            assertNull(dataSource.requestRttRange(MacAddressUtils.ANDROID_REDACTED_MAC))
            assertNull(dataSource.requestRttRange("invalid"))

            // 5. Unresponsive hardware callback times out cleanly at 2_500 ms without hanging!
            val stalledAdapter = SafeWifiRttAdapter(
                context = app,
                clock = clock,
                scanResultLookup = { rttScanResult },
                canScanNow = { false },
                onSecurityException = {},
                timeoutMs = 2_500L,
            )
            assertNull(stalledAdapter.requestRttRange(targetBssid))

            // Also verify actual timeout path when startRanging never invokes callback
            Shadows.shadowOf(rttManager).setRangeResults(emptyList())
            val emptyResultsDeferred = async { dataSource.requestRttRange(targetBssid) }
            advanceTimeBy(2_500L)
            runCurrent()
            assertNull(emptyResultsDeferred.await())
        } finally {
            dataSource.close()
        }
    }
}
