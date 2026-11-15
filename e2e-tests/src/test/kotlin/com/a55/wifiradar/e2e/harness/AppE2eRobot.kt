@file:Suppress("DEPRECATION")

package com.a55.wifiradar.e2e.harness

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.WifiRttManager
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.e2e.shadows.ExtendedShadowWifiManager
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Duration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowVibrator

/**
 * Pure OS-boundary E2E harness driving `com.a55.wifiradar` exclusively via
 * `PackageManager.getLaunchIntentForPackage`, Robolectric system service shadows,
 * Compose semantic interactions, and Skia native pixel captures.
 */
class AppE2eRobot(
    val composeRule: ComposeTestRule,
) : AutoCloseable {

    companion object {
        const val APP_PACKAGE = "com.a55.wifiradar"

        val ALL_RADAR_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.VIBRATE,
        )
    }

    val appContext: Application = ApplicationProvider.getApplicationContext()
    val wifiManager: WifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val locationManager: LocationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val connectivityManager: ConnectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    val vibrator: Vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    var scenario: ActivityScenario<Activity>? = null
        private set

    private var activeWifiNetwork: Network? = null

    fun configureReadyGalaxyA55Defaults(
        has6Ghz: Boolean = false,
        hasRtt: Boolean = false,
        scanThrottleEnabled: Boolean = true,
    ) {
        ExtendedShadowWifiManager.resetExtendedState()
        ExtendedShadowWifiManager.band24GhzSupported = true
        ExtendedShadowWifiManager.band6GhzSupported = has6Ghz
        if (has6Ghz) {
            ExtendedShadowWifiManager.supportedWifiStandards.add(ScanResult.WIFI_STANDARD_11AX)
        }
        ExtendedShadowWifiManager.scanThrottleEnabledFlag = scanThrottleEnabled

        val shadowPm = Shadows.shadowOf(appContext.packageManager)
        shadowPm.setSystemFeature(PackageManager.FEATURE_WIFI, true)
        shadowPm.setSystemFeature(PackageManager.FEATURE_WIFI_RTT, hasRtt)

        val shadowApp = Shadows.shadowOf(appContext)
        shadowApp.grantPermissions(*ALL_RADAR_PERMISSIONS)

        Shadows.shadowOf(locationManager).setLocationEnabled(true)

        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setIs5GHzBandSupported(true)
        @Suppress("DEPRECATION")
        wifiManager.setWifiEnabled(true)
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)
        shadowWifi.setIsScanAlwaysAvailable(true)
        shadowWifi.setStartScanSucceeds(true)
        shadowWifi.setScanResults(emptyList())

        ShadowVibrator.reset()
        val shadowVib = Shadows.shadowOf(vibrator)
        shadowVib.setHasVibrator(true)
        shadowVib.setHasAmplitudeControl(true)
        runCatching {
            shadowVib.setSupportedPrimitives(
                listOf(
                    VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                ),
            )
        }
        ShadowAudioTrack.resetTest()
    }

    fun grantPermissions(vararg permissions: String) {
        Shadows.shadowOf(appContext).grantPermissions(*permissions)
    }

    fun denyPermissions(vararg permissions: String) {
        Shadows.shadowOf(appContext).denyPermissions(*permissions)
    }

    fun setLocationEnabled(enabled: Boolean, broadcastChange: Boolean = true) {
        Shadows.shadowOf(locationManager).setLocationEnabled(enabled)
        if (broadcastChange) {
            appContext.sendBroadcast(Intent(LocationManager.MODE_CHANGED_ACTION))
            idleMainLooper()
        }
    }

    fun setWifiEnabledState(
        enabled: Boolean,
        scanAlwaysAvailable: Boolean = false,
        broadcastChange: Boolean = true,
    ) {
        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setIsScanAlwaysAvailable(scanAlwaysAvailable)
        @Suppress("DEPRECATION")
        wifiManager.setWifiEnabled(enabled)
        shadowWifi.setWifiState(
            if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED,
        )
        if (broadcastChange) {
            appContext.sendBroadcast(
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
                    putExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        if (enabled) WifiManager.WIFI_STATE_ENABLED else WifiManager.WIFI_STATE_DISABLED,
                    )
                },
            )
            idleMainLooper()
        }
    }

    fun injectScanBatch(
        results: List<ScanResult>,
        resultsUpdatedExtra: Boolean = true,
        fireCallback: Boolean = true,
        fireBroadcast: Boolean = true,
    ) {
        Shadows.shadowOf(wifiManager).setScanResults(ArrayList(results))
        if (fireCallback) {
            ExtendedShadowWifiManager.notifyRegisteredScanCallbacks()
        }
        if (fireBroadcast) {
            appContext.sendBroadcast(
                Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
                    putExtra(WifiManager.EXTRA_RESULTS_UPDATED, resultsUpdatedExtra)
                },
            )
        }
        idleMainLooper()
        if (scenario != null) {
            composeRule.waitForIdle()
        }
    }

    @Suppress("DEPRECATION")
    fun simulateConnectedWifi(
        wifiInfo: WifiInfo,
        hasInternetCapability: Boolean = false,
        netId: Int = 100,
    ) {
        Shadows.shadowOf(wifiManager).setConnectionInfo(wifiInfo)
        val network = activeWifiNetwork ?: ShadowNetwork.newInstance(netId).also {
            activeWifiNetwork = it
        }
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadowCaps = Shadows.shadowOf(caps)
        shadowCaps.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        if (hasInternetCapability) {
            shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            shadowCaps.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowCaps.setTransportInfo(wifiInfo)

        val shadowCm = Shadows.shadowOf(connectivityManager)
        val netInfo = ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            NetworkInfo.State.CONNECTED,
        )
        shadowCm.addNetwork(network, netInfo)
        shadowCm.setNetworkCapabilities(network, caps)
        for (cb in shadowCm.networkCallbacks) {
            cb.onAvailable(network)
            cb.onCapabilitiesChanged(network, caps)
        }
        appContext.sendBroadcast(
            Intent(WifiManager.RSSI_CHANGED_ACTION).apply {
                putExtra(WifiManager.EXTRA_NEW_RSSI, wifiInfo.rssi)
            },
        )
        idleMainLooper()
        if (scenario != null) {
            composeRule.waitForIdle()
        }
    }

    fun simulateWifiDisconnected() {
        Shadows.shadowOf(wifiManager).setConnectionInfo(
            ScanResultFactory.createConnectedWifiInfo(redactedLocation = true, rssiDbm = -127),
        )
        val shadowCm = Shadows.shadowOf(connectivityManager)
        activeWifiNetwork?.let { net ->
            for (cb in shadowCm.networkCallbacks) {
                cb.onLost(net)
            }
            shadowCm.removeNetwork(net)
        }
        activeWifiNetwork = null
        idleMainLooper()
        if (scenario != null) {
            composeRule.waitForIdle()
        }
    }

    fun injectRttResults(results: List<RangingResult>) {
        val rtt = appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        if (rtt != null) {
            Shadows.shadowOf(rtt).setRangeResults(results)
        }
    }

    fun advanceClockMs(deltaMs: Long) {
        if (deltaMs > 0L) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(deltaMs))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(deltaMs))
            runCatching { composeRule.mainClock.advanceTimeBy(deltaMs) }
        } else {
            idleMainLooper()
        }
        if (scenario != null) {
            composeRule.waitForIdle()
        }
    }

    fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    fun launchApp(): ActivityScenario<Activity> {
        val packageManager = appContext.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(APP_PACKAGE) ?: run {
            val component = android.content.ComponentName(APP_PACKAGE, "$APP_PACKAGE.MainActivity")
            Shadows.shadowOf(packageManager).addActivityIfNotPresent(component)
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        }
        val launched = ActivityScenario.launch<Activity>(launchIntent)
        scenario = launched
        composeRule.waitForIdle()
        return launched
    }

    fun recreateApp() {
        val active = checkNotNull(scenario) { "ActivityScenario not launched" }
        active.recreate()
        idleMainLooper()
        composeRule.waitForIdle()
    }

    fun closeAndColdRelaunch(): ActivityScenario<Activity> {
        runCatching { scenario?.close() }
        scenario = null
        idleMainLooper()
        return launchApp()
    }

    fun moveToLifecycle(state: Lifecycle.State) {
        val active = checkNotNull(scenario) { "ActivityScenario not launched" }
        active.moveToState(state)
        idleMainLooper()
    }

    fun currentActivity(): Activity {
        var ref: Activity? = null
        checkNotNull(scenario) { "ActivityScenario not launched" }.onActivity { ref = it }
        return checkNotNull(ref) { "Activity reference unavailable" }
    }

    fun peekNextStartedActivityIntent(): Intent? {
        var captured: Intent? = null
        scenario?.onActivity { activity ->
            captured = Shadows.shadowOf(activity).peekNextStartedActivity()
        }
        return captured ?: Shadows.shadowOf(appContext).peekNextStartedActivity()
    }

    fun consumeNextStartedActivityIntent(): Intent? {
        var captured: Intent? = null
        scenario?.onActivity { activity ->
            captured = Shadows.shadowOf(activity).nextStartedActivity
        }
        return captured ?: Shadows.shadowOf(appContext).nextStartedActivity
    }

    @OptIn(ExperimentalRoborazziApi::class)
    fun captureRootBitmap(filename: String): Pair<File, Bitmap> {
        val outDir = File("build/outputs/roborazzi").apply { mkdirs() }
        val outFile = File(outDir, filename)
        composeRule.onRoot().captureRoboImage(
            outFile,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        assertTrue("Expected Roborazzi PNG at ${outFile.absolutePath}", outFile.exists() && outFile.length() > 1024L)
        val bmp = BitmapFactory.decodeFile(outFile.absolutePath)
        assertNotNull("Decoded PNG bitmap must not be null for ${outFile.name}", bmp)
        return outFile to bmp
    }

    fun shadowVibrator(): ShadowVibrator = Shadows.shadowOf(vibrator)

    override fun close() {
        runCatching { scenario?.close() }
        scenario = null
        ExtendedShadowWifiManager.resetExtendedState()
        ShadowVibrator.reset()
        ShadowAudioTrack.resetTest()
    }
}
