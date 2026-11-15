package com.a55.wifiradar.core.wifiplatform

import android.app.Application
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.WifiGeneration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RadioCapabilityInspectorTest {

    private lateinit var app: Application
    private lateinit var wifiManager: WifiManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        wifiManager = app.getSystemService(WifiManager::class.java)!!
        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            -1,
        )
    }

    @After
    fun tearDown() {
        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            -1,
        )
    }

    @Test
    fun robolectricDefaultAnd5GhzShadowReconcileNullProxyProperly() {
        val shadowWifi = Shadows.shadowOf(wifiManager)
        val shadowPm = Shadows.shadowOf(app.packageManager)

        shadowWifi.setIs5GHzBandSupported(false)
        shadowWifi.setStaApConcurrencySupported(false)
        shadowPm.setSystemFeature(PackageManager.FEATURE_WIFI_RTT, false)

        val inspector = RadioCapabilityInspector(context = app)
        val baseCaps = inspector.inspect()

        assertTrue(baseCaps.has24Ghz)
        assertFalse(baseCaps.has5Ghz)
        assertFalse(baseCaps.has6Ghz)
        assertFalse(baseCaps.has60Ghz)
        assertEquals(
            setOf(WifiGeneration.LEGACY_11B, WifiGeneration.LEGACY_11G, WifiGeneration.WIFI_4),
            baseCaps.supportedStandards,
        )
        assertEquals(WifiGeneration.WIFI_4, baseCaps.maxSupportedWifiGeneration)
        assertTrue(baseCaps.isScanThrottleEnabled)
        assertFalse(baseCaps.supportsDualBandSimultaneous)
        assertFalse(baseCaps.supportsWifiRtt)
        // Crucial: ShadowWifiRttManager.isAvailable() returns true unconditionally; gating by
        // FEATURE_WIFI_RTT keeps isRttCurrentlyAvailable == false when hardware lacks RTT!
        assertFalse(baseCaps.isRttCurrentlyAvailable)

        // Enable 5 GHz and STA+AP concurrency on ShadowWifiManager + FEATURE_WIFI_RTT
        shadowWifi.setIs5GHzBandSupported(true)
        shadowWifi.setStaApConcurrencySupported(true)
        shadowPm.setSystemFeature(PackageManager.FEATURE_WIFI_RTT, true)

        val dualBandCaps = inspector.inspect()
        assertTrue(dualBandCaps.has24Ghz)
        assertTrue(dualBandCaps.has5Ghz)
        assertFalse(dualBandCaps.has6Ghz)
        assertEquals(
            setOf(
                WifiGeneration.LEGACY_11B,
                WifiGeneration.LEGACY_11G,
                WifiGeneration.LEGACY_11A,
                WifiGeneration.WIFI_4,
                WifiGeneration.WIFI_5,
            ),
            dualBandCaps.supportedStandards,
        )
        assertEquals(WifiGeneration.WIFI_5, dualBandCaps.maxSupportedWifiGeneration)
        assertTrue(dualBandCaps.supportsDualBandSimultaneous)
        assertTrue(dualBandCaps.isScanThrottleEnabled)
        assertTrue(dualBandCaps.supportsWifiRtt)
        assertTrue(dualBandCaps.isRttCurrentlyAvailable)
    }

    @Test
    fun globalSettingAndConstructorOverrideControlScanThrottleFlag() {
        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            0,
        )
        val unthrottledCaps = RadioCapabilityInspector(context = app).inspect()
        assertFalse(unthrottledCaps.isScanThrottleEnabled)

        Settings.Global.putInt(
            app.contentResolver,
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED,
            1,
        )
        val throttledCaps = RadioCapabilityInspector(context = app).inspect()
        assertTrue(throttledCaps.isScanThrottleEnabled)

        val overrideCaps = RadioCapabilityInspector(
            context = app,
            isScanThrottleEnabledOverride = { false },
        ).inspect()
        assertFalse(overrideCaps.isScanThrottleEnabled)
    }

    @Test
    fun nativeSamsungGalaxyA55ProfileMapsExactDualBandWifi6Without6Ghz() {
        val backend = FakeRadioCapabilityBackend(
            deviceModel = "SM-A556B",
            manufacturer = "samsung",
            sdkInt = 35,
            is24Ghz = true,
            is5Ghz = true,
            is6Ghz = false,
            is60Ghz = false,
            supportedStandardCodes = setOf(
                ScanResult.WIFI_STANDARD_LEGACY,
                ScanResult.WIFI_STANDARD_11N,
                ScanResult.WIFI_STANDARD_11AC,
                ScanResult.WIFI_STANDARD_11AX,
            ),
            dbsSupported = true,
            scanThrottleFromWifiManager = true,
            hasRttFeature = false,
            rttAvailable = false,
        )

        val caps = RadioCapabilityInspector(backend = backend).inspect()
        assertEquals("SM-A556B", caps.deviceModel)
        assertEquals("samsung", caps.manufacturer)
        assertEquals(35, caps.sdkInt)
        assertTrue(caps.has24Ghz)
        assertTrue(caps.has5Ghz)
        assertFalse(caps.has6Ghz)
        assertFalse(caps.has60Ghz)
        assertEquals(WifiGeneration.WIFI_6, caps.maxSupportedWifiGeneration)
        assertFalse(WifiGeneration.WIFI_6E in caps.supportedStandards)
        assertEquals(
            setOf(
                WifiGeneration.LEGACY_11B,
                WifiGeneration.LEGACY_11G,
                WifiGeneration.LEGACY_11A,
                WifiGeneration.WIFI_4,
                WifiGeneration.WIFI_5,
                WifiGeneration.WIFI_6,
            ),
            caps.supportedStandards,
        )
        assertTrue(caps.supportsDualBandSimultaneous)
        assertTrue(caps.isScanThrottleEnabled)
        assertFalse(caps.supportsWifiRtt)
        assertFalse(caps.isRttCurrentlyAvailable)
    }

    @Test
    fun derivesWifi6eAndWifi7AndWigigAndRespectsApi30Guards() {
        // Wi-Fi 6E derived when 11AX + has6Ghz == true
        val wifi6eCaps = RadioCapabilityInspector(
            backend = FakeRadioCapabilityBackend(
                sdkInt = 34,
                is24Ghz = true,
                is5Ghz = true,
                is6Ghz = true,
                supportedStandardCodes = setOf(
                    ScanResult.WIFI_STANDARD_LEGACY,
                    ScanResult.WIFI_STANDARD_11N,
                    ScanResult.WIFI_STANDARD_11AC,
                    ScanResult.WIFI_STANDARD_11AX,
                ),
            ),
        ).inspect()
        assertTrue(WifiGeneration.WIFI_6 in wifi6eCaps.supportedStandards)
        assertTrue(WifiGeneration.WIFI_6E in wifi6eCaps.supportedStandards)
        assertEquals(WifiGeneration.WIFI_6E, wifi6eCaps.maxSupportedWifiGeneration)

        // Wi-Fi 7 + WiGig on API 34
        val wifi7Caps = RadioCapabilityInspector(
            backend = FakeRadioCapabilityBackend(
                sdkInt = 34,
                is24Ghz = true,
                is5Ghz = true,
                is6Ghz = true,
                is60Ghz = true,
                supportedStandardCodes = setOf(
                    ScanResult.WIFI_STANDARD_LEGACY,
                    ScanResult.WIFI_STANDARD_11AX,
                    ScanResult.WIFI_STANDARD_11AD,
                    ScanResult.WIFI_STANDARD_11BE,
                ),
            ),
        ).inspect()
        assertTrue(WifiGeneration.WIGIG_11AD in wifi7Caps.supportedStandards)
        assertTrue(WifiGeneration.WIFI_7 in wifi7Caps.supportedStandards)
        assertEquals(WifiGeneration.WIFI_7, wifi7Caps.maxSupportedWifiGeneration)

        // API 30 guard: never queries API 31/33/34 methods even if backend throws on them
        val api30Backend = object : FakeRadioCapabilityBackend(
            sdkInt = 30,
            is5Ghz = true,
            is6Ghz = false,
            supportedStandardCodes = setOf(
                ScanResult.WIFI_STANDARD_LEGACY,
                ScanResult.WIFI_STANDARD_11N,
                ScanResult.WIFI_STANDARD_11AC,
            ),
            staApConcurrency = true,
            scanThrottleFromWifiManager = false,
        ) {
            override fun is24GhzBandSupported(): Boolean =
                throw AssertionError("API 31 method must not be called on API 30")

            override fun is60GhzBandSupported(): Boolean =
                throw AssertionError("API 31 method must not be called on API 30")

            override fun isMakeBeforeBreakWifiSwitchingSupported(): Boolean =
                throw AssertionError("API 31 method must not be called on API 30")

            override fun isStaConcurrencyForLocalOnlyConnectionsSupported(): Boolean =
                throw AssertionError("API 31 method must not be called on API 30")

            override fun isStaConcurrencyForMultiInternetSupported(): Boolean =
                throw AssertionError("API 33 method must not be called on API 30")

            override fun isDualBandSimultaneousSupported(): Boolean =
                throw AssertionError("API 34 method must not be called on API 30")
        }

        val api30Caps = RadioCapabilityInspector(backend = api30Backend).inspect()
        assertTrue(api30Caps.has24Ghz)
        assertTrue(api30Caps.has5Ghz)
        assertFalse(api30Caps.has60Ghz)
        assertTrue(api30Caps.supportsDualBandSimultaneous)
        assertFalse(api30Caps.isScanThrottleEnabled)
        assertEquals(WifiGeneration.WIFI_5, api30Caps.maxSupportedWifiGeneration)

        // Null WifiManager hardware-absent fallback
        val absentWifiCaps = RadioCapabilityInspector(
            backend = FakeRadioCapabilityBackend(hasWifiManager = false),
        ).inspect()
        assertFalse(absentWifiCaps.has24Ghz)
        assertFalse(absentWifiCaps.has5Ghz)
        assertEquals(WifiGeneration.UNKNOWN, absentWifiCaps.maxSupportedWifiGeneration)
        assertTrue(absentWifiCaps.supportedStandards.isEmpty())
    }

    private open class FakeRadioCapabilityBackend(
        override val deviceModel: String = "SM-A556B",
        override val manufacturer: String = "samsung",
        override val sdkInt: Int = 34,
        override val hasWifiManager: Boolean = true,
        private val is24Ghz: Boolean = true,
        private val is5Ghz: Boolean = true,
        private val is6Ghz: Boolean = false,
        private val is60Ghz: Boolean = false,
        private val supportedStandardCodes: Set<Int> = emptySet(),
        private val staApConcurrency: Boolean = false,
        private val makeBeforeBreak: Boolean = false,
        private val localOnlyConcurrency: Boolean = false,
        private val multiInternetConcurrency: Boolean = false,
        private val dbsSupported: Boolean = false,
        private val scanThrottleFromWifiManager: Boolean = true,
        private val globalThrottleSetting: Int = -1,
        private val hasRttFeature: Boolean = false,
        private val rttAvailable: Boolean = false,
    ) : RadioCapabilityBackend {
        override fun is24GhzBandSupported(): Boolean = is24Ghz
        override fun is5GhzBandSupported(): Boolean = is5Ghz
        override fun is6GhzBandSupported(): Boolean = is6Ghz
        override fun is60GhzBandSupported(): Boolean = is60Ghz
        override fun isWifiStandardSupported(standard: Int): Boolean =
            standard in supportedStandardCodes

        override fun isStaApConcurrencySupported(): Boolean = staApConcurrency
        override fun isMakeBeforeBreakWifiSwitchingSupported(): Boolean = makeBeforeBreak
        override fun isStaConcurrencyForLocalOnlyConnectionsSupported(): Boolean = localOnlyConcurrency
        override fun isStaConcurrencyForMultiInternetSupported(): Boolean = multiInternetConcurrency
        override fun isDualBandSimultaneousSupported(): Boolean = dbsSupported
        override fun isScanThrottleEnabledFromWifiManager(): Boolean = scanThrottleFromWifiManager
        override fun getScanThrottleGlobalSetting(): Int = globalThrottleSetting
        override fun hasWifiRttFeature(): Boolean = hasRttFeature
        override fun isRttManagerAvailable(): Boolean = rttAvailable
    }
}
