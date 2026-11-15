package com.a55.wifiradar.core.wifiplatform

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RttMeasurement
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Safe suspending `802.11mc` / `802.11az` `WifiRttManager` adapter (`F13, F34`).
 *
 * - Looks up the latest raw [ScanResult] for the normalized BSSID.
 * - Executes ranging inside `withTimeoutOrNull(2_500L)` using an inline [Executor] so
 *   `RangingResultCallback` never deadlocks on an un-idled `MainLooper`.
 * - Strictly verifies `match.status == RangingResult.STATUS_SUCCESS` BEFORE reading ANY
 *   measurement getter (`getDistanceMm` et al. throw `IllegalStateException` on failed status).
 * - Clamps close-range negative millimeter distances via `max(0, rawDistanceMm) / 1000.0`.
 */
internal class SafeWifiRttAdapter(
    context: Context,
    private val clock: PlatformClock,
    private val scanResultLookup: (String) -> ScanResult?,
    private val canScanNow: () -> Boolean,
    private val onSecurityException: (SecurityException) -> Unit,
    private val timeoutMs: Long = DEFAULT_RTT_TIMEOUT_MS,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val appContext: Context = context.applicationContext ?: context

    suspend fun requestRttRange(bssid: String): RttMeasurement? {
        val normalizedBssid = MacAddressUtils.normalizeBssid(bssid)
        if (!MacAddressUtils.isValidMac(normalizedBssid) ||
            normalizedBssid == ZERO_MAC ||
            MacAddressUtils.isAndroidRedactedMac(normalizedBssid)
        ) {
            return null
        }

        if (!canScanNow()) return null

        val rttManager = safeIpcCall(default = null, onSecurityException = onSecurityException) {
            appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        } ?: return null

        val isAvailable = safeIpcCall(default = false, onSecurityException = onSecurityException) {
            rttManager.isAvailable
        }
        if (!isAvailable) return null

        val targetScanResult = scanResultLookup(normalizedBssid) ?: return null

        val request = safeIpcCall(default = null, onSecurityException = onSecurityException) {
            RangingRequest.Builder()
                .addAccessPoint(targetScanResult)
                .build()
        } ?: return null

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : RangingResultCallback() {
                    override fun onRangingFailure(code: Int) {
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }

                    override fun onRangingResults(results: List<RangingResult>) {
                        if (!cont.isActive) return
                        val measurement = parseMatchingResult(results, normalizedBssid)
                        cont.resume(measurement)
                    }
                }

                val started = safeIpcCall(
                    default = false,
                    onSecurityException = onSecurityException,
                ) {
                    rttManager.startRanging(request, Executor { it.run() }, callback)
                    true
                }

                if (!started && cont.isActive) {
                    cont.resume(null)
                }
            }
        }
    }

    private fun parseMatchingResult(
        results: List<RangingResult>,
        targetBssid: String,
    ): RttMeasurement? = safeIpcCall(default = null) {
        val match = results.firstOrNull { candidate ->
            val mac = safeIpcCall(default = null) { candidate.macAddress?.toString() }
            mac != null && MacAddressUtils.normalizeBssid(mac) == targetBssid
        } ?: return@safeIpcCall null

        // Mandatory guard: RangingResult getters throw IllegalStateException unless STATUS_SUCCESS
        if (match.status != RangingResult.STATUS_SUCCESS) {
            return@safeIpcCall null
        }

        val rawDistanceMm = match.distanceMm
        val clampedDistanceMeters = max(0, rawDistanceMm) / 1000.0
        val stdDevMeters = max(0, match.distanceStdDevMm) / 1000.0
        val rssiDbm = match.rssi.coerceIn(-127, 0)
        val attempted = match.numAttemptedMeasurements.coerceAtLeast(1)
        val successful = match.numSuccessfulMeasurements.coerceAtLeast(1)
        val is80211az = if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Api35PlatformCompat.is80211azNtbMeasurement(match)
        } else {
            false
        }

        RttMeasurement(
            bssid = targetBssid,
            distanceMeters = clampedDistanceMeters,
            rawDistanceMm = rawDistanceMm,
            distanceStdDevMeters = stdDevMeters,
            rssiDbm = rssiDbm,
            numAttemptedMeasurements = attempted,
            numSuccessfulMeasurements = successful,
            is80211azNtb = is80211az,
            timestampElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    companion object {
        const val DEFAULT_RTT_TIMEOUT_MS = 2_500L
        private const val ZERO_MAC = "00:00:00:00:00:00"
    }
}
