package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.DeadSystemException
import android.os.RemoteException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.ScannerReadinessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefensiveIpcAndLifecycleIntegrationTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        System.setProperty("robolectric.validateReceiverExportFlags", "true")
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        val lm = app.getSystemService(LocationManager::class.java)!!
        Shadows.shadowOf(lm).setLocationEnabled(true)
        val wm = app.getSystemService(WifiManager::class.java)!!
        @Suppress("DEPRECATION")
        wm.isWifiEnabled = true
    }

    @After
    fun tearDown() {
        System.clearProperty("robolectric.validateReceiverExportFlags")
    }

    @Test
    fun safeIpcCallAlwaysRethrowsCancellationExceptionWhileIsolatingSystemExceptions() {
        // 1. CancellationException MUST be rethrown (never swallowed as IllegalStateException!)
        try {
            safeIpcCall(default = "fallback") {
                throw CancellationException("Structured concurrency cancellation")
            }
            fail("Expected CancellationException to be rethrown")
        } catch (_: CancellationException) {
            // Expected
        }

        // 2. SecurityException invokes callback and returns default
        var observedSecurity = false
        val secResult = safeIpcCall(
            default = 42,
            onSecurityException = { observedSecurity = true },
        ) {
            throw SecurityException("Revoked location permission mid-scan")
        }
        assertEquals(42, secResult)
        assertTrue(observedSecurity)

        // 3. RemoteException, DeadSystemException, IllegalStateException, IllegalArgumentException, RuntimeException
        assertEquals("safe", safeIpcCall("safe") { throw RemoteException("Binder died") })
        assertEquals("safe", safeIpcCall("safe") { throw DeadSystemException() })
        assertEquals("safe", safeIpcCall("safe") { throw IllegalStateException("Bad HAL state") })
        assertEquals("safe", safeIpcCall("safe") { throw IllegalArgumentException("Unregistered") })
        assertEquals("safe", safeIpcCall("safe") { throw RuntimeException("DeadSystemRuntimeException") })
    }

    @Test
    fun lifecycleBindRefCountsOverlappingActivityRecreationAndUnregistersZeroLeaksOnStop() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this)
        val shadowApp = Shadows.shadowOf(app)
        val baselineReceivers = shadowApp.registeredReceivers.size

        // Also test simple 1-arg constructor compilation & instantiation
        AndroidWifiPlatformDataSource(app).close()
        AndroidProximityFeedbackController(app).close()
        AndroidUserPreferencesDataSource(app)

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
        try {
            val activity1 = TestOwner()
            val activity2 = TestOwner()

            dataSource.bindToLifecycle(activity1.lifecycle)
            activity1.moveTo(Lifecycle.State.RESUMED)
            runCurrent()

            val activeReceiversCount = shadowApp.registeredReceivers.size
            assertTrue("Expected receivers registered on ON_START", activeReceiversCount >= baselineReceivers + 3)

            // Simulate overlapping Activity recreation: Activity #2 hits STARTED before Activity #1 hits CREATED (ON_STOP)
            dataSource.bindToLifecycle(activity2.lifecycle)
            activity2.moveTo(Lifecycle.State.STARTED)
            activity1.moveTo(Lifecycle.State.DESTROYED)
            runCurrent()

            // Receivers must STILL be registered because Activity #2 is STARTED!
            assertEquals(activeReceiversCount, shadowApp.registeredReceivers.size)

            // Now Activity #2 moves to background (ON_STOP) -> ref-count drops to 0, all receivers unregistered!
            activity2.moveTo(Lifecycle.State.CREATED)
            runCurrent()
            assertEquals(baselineReceivers, shadowApp.registeredReceivers.size)

            // Returning Activity #2 to foreground (ON_START -> ON_RESUME) cleanly re-registers!
            activity2.moveTo(Lifecycle.State.RESUMED)
            runCurrent()
            assertEquals(activeReceiversCount, shadowApp.registeredReceivers.size)

            // Mid-session permission revocation self-heals readinessState on refresh/security check
            shadowApp.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            dataSource.refreshReadiness()
            assertTrue(dataSource.readinessState.value is ScannerReadinessState.ApproximateOnlyNeedsPrecise)
        } finally {
            dataSource.close()
            assertEquals(baselineReceivers, shadowApp.registeredReceivers.size)
        }
    }

    private class TestOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
