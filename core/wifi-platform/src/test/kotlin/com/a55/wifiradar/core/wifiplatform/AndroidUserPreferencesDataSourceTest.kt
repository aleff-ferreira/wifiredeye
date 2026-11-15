package com.a55.wifiradar.core.wifiplatform

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.MacAddressUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUserPreferencesDataSourceTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(
            AndroidUserPreferencesDataSource.DEFAULT_PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun persistsNormalizedNicknamesAndCalibrationsSynchronouslyAcrossInstances() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds1 = AndroidUserPreferencesDataSource(context = app, ioDispatcher = dispatcher)

        assertTrue(ds1.bssidNicknames.value.isEmpty())
        assertTrue(ds1.oneMeterCalibrationsDbm.value.isEmpty())

        ds1.setNickname(" aa-bb-cc-dd-ee-01 ", "  Living Room Router  ")
        ds1.setOneMeterCalibration("aa:bb:cc:dd:ee:01", -42.5)

        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to "Living Room Router"), ds1.bssidNicknames.value)
        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to -42.5), ds1.oneMeterCalibrationsDbm.value)

        // Immediately instantiate a second instance -> synchronous init load sees committed values!
        val ds2 = AndroidUserPreferencesDataSource(context = app, ioDispatcher = dispatcher)
        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to "Living Room Router"), ds2.bssidNicknames.value)
        assertEquals(mapOf("AA:BB:CC:DD:EE:01" to -42.5), ds2.oneMeterCalibrationsDbm.value)

        // Blank / whitespace / null removes nickname; null removes calibration
        ds2.setNickname("AA:BB:CC:DD:EE:01", "   \t\n ")
        ds2.setOneMeterCalibration("AA:BB:CC:DD:EE:01", null)

        val ds3 = AndroidUserPreferencesDataSource(context = app, ioDispatcher = dispatcher)
        assertTrue(ds3.bssidNicknames.value.isEmpty())
        assertTrue(ds3.oneMeterCalibrationsDbm.value.isEmpty())
    }

    @Test
    fun rejectsInvalidRedactedNonFiniteInputsClampsOutliersAndRecoversCorruptJson() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = app.getSharedPreferences("corrupt_test_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(AndroidUserPreferencesDataSource.KEY_NICKNAMES_JSON, "{corrupt_json!!!")
            .putString(AndroidUserPreferencesDataSource.KEY_CALIBRATIONS_JSON, "[1, 2, 3]")
            .commit()

        val ds = AndroidUserPreferencesDataSource(sharedPreferences = prefs, ioDispatcher = dispatcher)
        assertTrue(ds.bssidNicknames.value.isEmpty())
        assertTrue(ds.oneMeterCalibrationsDbm.value.isEmpty())

        // Invalid, zero, and redacted MACs are rejected without mutating state
        ds.setNickname("", "Ignored")
        ds.setNickname("not-a-mac", "Ignored")
        ds.setNickname("00:00:00:00:00:00", "Ignored")
        ds.setNickname(MacAddressUtils.ANDROID_REDACTED_MAC, "Ignored")
        ds.setOneMeterCalibration(MacAddressUtils.ANDROID_REDACTED_MAC, -45.0)
        assertTrue(ds.bssidNicknames.value.isEmpty())
        assertTrue(ds.oneMeterCalibrationsDbm.value.isEmpty())

        // Valid write heals previously corrupt JSON; overlong nickname truncates to 64 chars
        val longName = "X".repeat(100)
        ds.setNickname("11:22:33:44:55:66", longName)
        ds.setOneMeterCalibration("11:22:33:44:55:66", -48.25)
        assertEquals(64, ds.bssidNicknames.value["11:22:33:44:55:66"]!!.length)

        // NaN / Infinity are ignored without clobbering existing -48.25 calibration
        ds.setOneMeterCalibration("11:22:33:44:55:66", Double.NaN)
        ds.setOneMeterCalibration("11:22:33:44:55:66", Double.POSITIVE_INFINITY)
        ds.setOneMeterCalibration("11:22:33:44:55:66", Double.NEGATIVE_INFINITY)
        assertEquals(-48.25, ds.oneMeterCalibrationsDbm.value["11:22:33:44:55:66"]!!, 1e-6)

        // Extreme finite outliers clamp safely into [-100.0, -10.0]
        ds.setOneMeterCalibration("11:22:33:44:55:01", -250.0)
        ds.setOneMeterCalibration("11:22:33:44:55:02", 42.0)
        assertEquals(-100.0, ds.oneMeterCalibrationsDbm.value["11:22:33:44:55:01"]!!, 1e-6)
        assertEquals(-10.0, ds.oneMeterCalibrationsDbm.value["11:22:33:44:55:02"]!!, 1e-6)
    }

    @Test
    fun concurrentMutexUpdatesNeverLoseWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = AndroidUserPreferencesDataSource(context = app, ioDispatcher = dispatcher)

        coroutineScope {
            for (idx in 1..20) {
                val bssid = String.format("AA:BB:CC:DD:EE:%02X", idx)
                launch {
                    ds.setNickname(bssid, "Router #$idx")
                    ds.setOneMeterCalibration(bssid, -35.0 - idx)
                }
            }
        }

        assertEquals(20, ds.bssidNicknames.value.size)
        assertEquals(20, ds.oneMeterCalibrationsDbm.value.size)

        val reloaded = AndroidUserPreferencesDataSource(context = app, ioDispatcher = dispatcher)
        assertEquals(20, reloaded.bssidNicknames.value.size)
        assertEquals(20, reloaded.oneMeterCalibrationsDbm.value.size)
        assertEquals("Router #20", reloaded.bssidNicknames.value["AA:BB:CC:DD:EE:14"])
        assertEquals(-55.0, reloaded.oneMeterCalibrationsDbm.value["AA:BB:CC:DD:EE:14"]!!, 1e-6)
    }
}
