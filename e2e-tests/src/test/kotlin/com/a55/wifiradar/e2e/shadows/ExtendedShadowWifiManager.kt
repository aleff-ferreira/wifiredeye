package com.a55.wifiradar.e2e.shadows

import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiManager

/**
 * OS-boundary Robolectric shadow extension for [WifiManager] modeling the Samsung Galaxy A55 5G
 * (`SM-A556B`, Exynos 1480 dual-band Wi-Fi 6 radio), Android 11+ `ScanResultsCallback` delivery,
 * Android 9+ scan-throttle introspection, and defensive Binder/SecurityException fault injection.
 */
@Implements(WifiManager::class)
open class ExtendedShadowWifiManager : ShadowWifiManager() {

    companion object {
        @Volatile
        var band24GhzSupported: Boolean = true

        @Volatile
        var band6GhzSupported: Boolean = false // Retail Galaxy A55 5G lacks 6 GHz hardware

        @Volatile
        var band60GhzSupported: Boolean = false

        @Volatile
        var scanThrottleEnabledFlag: Boolean = true

        @Volatile
        var throwOnGetScanResults: Throwable? = null

        @Volatile
        var throwOnStartScan: Throwable? = null

        @Volatile
        var throwOnGetConnectionInfo: Throwable? = null

        @Volatile
        var throwOnCapabilityQuery: Throwable? = null

        val supportedWifiStandards: MutableSet<Int> = CopyOnWriteArrayList(
            listOf(
                ScanResult.WIFI_STANDARD_LEGACY,
                ScanResult.WIFI_STANDARD_11N,
                ScanResult.WIFI_STANDARD_11AC,
                ScanResult.WIFI_STANDARD_11AX, // Galaxy A55 5G supports up to Wi-Fi 6 (802.11ax)
            ),
        ).toMutableSet()

        val startScanInvocationCount = AtomicInteger(0)

        private data class RegisteredCallback(
            val executor: Executor,
            val callback: WifiManager.ScanResultsCallback,
        )

        private val scanCallbacks = CopyOnWriteArrayList<RegisteredCallback>()

        fun resetExtendedState() {
            band24GhzSupported = true
            band6GhzSupported = false
            band60GhzSupported = false
            scanThrottleEnabledFlag = true
            throwOnGetScanResults = null
            throwOnStartScan = null
            throwOnGetConnectionInfo = null
            throwOnCapabilityQuery = null
            supportedWifiStandards.clear()
            supportedWifiStandards.addAll(
                listOf(
                    ScanResult.WIFI_STANDARD_LEGACY,
                    ScanResult.WIFI_STANDARD_11N,
                    ScanResult.WIFI_STANDARD_11AC,
                    ScanResult.WIFI_STANDARD_11AX,
                ),
            )
            startScanInvocationCount.set(0)
            scanCallbacks.clear()
        }

        fun notifyRegisteredScanCallbacks() {
            for (entry in scanCallbacks) {
                entry.executor.execute { entry.callback.onScanResultsAvailable() }
            }
        }

        fun registeredCallbackCount(): Int = scanCallbacks.size
    }

    @Implementation(minSdk = 30)
    protected fun is24GHzBandSupported(): Boolean {
        throwOnCapabilityQuery?.let { throw it }
        return band24GhzSupported
    }

    @Implementation
    override fun is5GHzBandSupported(): Boolean {
        throwOnCapabilityQuery?.let { throw it }
        return super.is5GHzBandSupported()
    }

    @Implementation(minSdk = 30)
    protected fun is6GHzBandSupported(): Boolean {
        throwOnCapabilityQuery?.let { throw it }
        return band6GhzSupported
    }

    @Implementation(minSdk = 30)
    protected fun is60GHzBandSupported(): Boolean {
        throwOnCapabilityQuery?.let { throw it }
        return band60GhzSupported
    }

    @Implementation(minSdk = 30)
    protected fun isWifiStandardSupported(standard: Int): Boolean {
        throwOnCapabilityQuery?.let { throw it }
        return standard in supportedWifiStandards
    }

    @Implementation(minSdk = 30)
    protected fun isScanThrottleEnabled(): Boolean = scanThrottleEnabledFlag

    @Implementation(minSdk = 30)
    protected fun registerScanResultsCallback(
        executor: Executor,
        callback: WifiManager.ScanResultsCallback,
    ) {
        scanCallbacks.removeIf { it.callback === callback }
        scanCallbacks.add(RegisteredCallback(executor, callback))
    }

    @Implementation(minSdk = 30)
    protected fun unregisterScanResultsCallback(callback: WifiManager.ScanResultsCallback) {
        scanCallbacks.removeIf { it.callback === callback }
    }

    @Implementation
    override fun startScan(): Boolean {
        startScanInvocationCount.incrementAndGet()
        throwOnStartScan?.let { throw it }
        return super.startScan()
    }

    @Implementation
    override fun getScanResults(): List<ScanResult> {
        throwOnGetScanResults?.let { throw it }
        return super.getScanResults()
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun getConnectionInfo(): WifiInfo {
        throwOnGetConnectionInfo?.let { throw it }
        return super.getConnectionInfo()
    }
}
