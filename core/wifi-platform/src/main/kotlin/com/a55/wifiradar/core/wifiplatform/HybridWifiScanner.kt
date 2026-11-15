package com.a55.wifiradar.core.wifiplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual passive listener (`registerScanResultsCallback` + `SCAN_RESULTS_AVAILABLE_ACTION`) and
 * active token-bucket scan coordinator (`F20, F33, F34`).
 */
internal class HybridWifiScanner(
    context: Context,
    private val clock: PlatformClock,
    private val readinessStateProvider: () -> ScannerReadinessState,
    private val refreshReadinessCallback: () -> ScannerReadinessState,
    private val isScanThrottleEnabledProvider: () -> Boolean,
    private val onSecurityException: (SecurityException) -> Unit,
    private val antiFlickerCache: AntiFlickerScanCache = AntiFlickerScanCache(),
    private val scheduler: TokenBucketScanScheduler = TokenBucketScanScheduler(),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val triggerInitialScanOnStart: Boolean = true,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val dedupLock = Any()

    private val isStarted = AtomicBoolean(false)
    private val callbackRegistered = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    private var tickerJob: Job? = null
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    private var hasIngestedFreshSweep: Boolean = false
    private var lastRawSignature: Int = 0
    private var lastRawIngestElapsedMs: Long = -DUPLICATE_SWEEP_WINDOW_MS - 1L

    private val _rawScanBatches = MutableStateFlow<RawScanBatch?>(null)
    val rawScanBatches: StateFlow<RawScanBatch?> = _rawScanBatches.asStateFlow()

    private val _scanTelemetry = MutableStateFlow(
        scheduler.snapshotTelemetry(
            nowElapsedMs = clock.elapsedRealtimeMs(),
            rawThrottleEnabled = safeIpcCall(default = true) { isScanThrottleEnabledProvider() },
        ),
    )
    val scanTelemetry: StateFlow<ScanTelemetry> = _scanTelemetry.asStateFlow()

    internal val scanResultsCallback = object : WifiManager.ScanResultsCallback() {
        override fun onScanResultsAvailable() {
            onScanResultsSignal(resultsUpdated = true)
        }
    }

    internal val scanBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            // Default to true because Robolectric ShadowWifiManager.startScan() broadcasts
            // SCAN_RESULTS without EXTRA_RESULTS_UPDATED, whereas real Android OS always sets
            // the boolean extra explicitly (true on fresh sweep, false on OS throttle/failure).
            val updated = safeIpcCall(default = true) {
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            }
            onScanResultsSignal(resultsUpdated = updated)
        }
    }

    fun start(sessionScope: CoroutineScope) {
        if (!isStarted.compareAndSet(false, true)) return
        registerPassiveListeners()
        refreshTelemetry()

        if (readinessStateProvider().canScanNow) {
            harvestCachedSeedIfNeeded()
            if (triggerInitialScanOnStart) {
                triggerActiveScan(burstMode = false)
            }
        }

        tickerJob = sessionScope.launch {
            runSchedulerLoop()
        }
    }

    fun stop() {
        if (!isStarted.compareAndSet(true, false)) return
        tickerJob?.cancel()
        tickerJob = null
        unregisterPassiveListeners()
    }

    fun onReadinessBecameReady() {
        if (!isStarted.get()) return
        harvestCachedSeedIfNeeded()
        triggerActiveScan(burstMode = false)
        wakeChannel.trySend(Unit)
    }

    fun refreshTelemetry() {
        val nowMs = clock.elapsedRealtimeMs()
        val rawThrottle = safeIpcCall(default = true) { isScanThrottleEnabledProvider() }
        _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
        wakeChannel.trySend(Unit)
    }

    fun triggerActiveScan(burstMode: Boolean = false): ScanTriggerResult {
        val currentReadiness = readinessStateProvider()
        if (!currentReadiness.canScanNow) {
            return ScanTriggerResult.BlockedByReadiness(currentReadiness)
        }

        val nowMs = clock.elapsedRealtimeMs()
        val rawThrottle = safeIpcCall(default = true) { isScanThrottleEnabledProvider() }

        when (val preCheck = scheduler.checkPreTrigger(nowMs, rawThrottle)) {
            is TokenBucketScanScheduler.PreTriggerCheck.BlockedByTokenBucket -> {
                _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
                wakeChannel.trySend(Unit)
                return ScanTriggerResult.ThrottledByTokenBucket(nextTokenInMs = preCheck.nextTokenInMs)
            }
            TokenBucketScanScheduler.PreTriggerCheck.Proceed -> Unit
        }

        val wm = wifiManagerOrNull()
        if (wm == null) {
            val rejected = scheduler.onStartScanRejectedByOs(nowMs, rawThrottle)
            _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
            return rejected
        }

        var encounteredSecurityException = false
        @Suppress("DEPRECATION")
        val accepted = safeIpcCall(
            default = false,
            onSecurityException = { se ->
                encounteredSecurityException = true
                onSecurityException(se)
            },
        ) {
            wm.startScan()
        }

        if (encounteredSecurityException) {
            val refreshedReadiness = refreshReadinessCallback()
            if (!refreshedReadiness.canScanNow) {
                _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
                return ScanTriggerResult.BlockedByReadiness(refreshedReadiness)
            }
        }

        val result = if (accepted) {
            scheduler.onStartScanAccepted(
                nowElapsedMs = nowMs,
                rawThrottleEnabled = rawThrottle,
                burstMode = burstMode,
            )
        } else {
            val rejected = scheduler.onStartScanRejectedByOs(
                nowElapsedMs = nowMs,
                rawThrottleEnabled = rawThrottle,
            )
            // Harvest cached scan results passively if we haven't emitted a batch yet so cached
            // routers still render alongside the "OS Throttle Active" status pill.
            harvestCachedSeedIfNeeded()
            rejected
        }

        _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
        wakeChannel.trySend(Unit)
        return result
    }

    fun getLatestRawScanResult(bssid: String): ScanResult? =
        antiFlickerCache.getLatestRawScanResult(bssid)

    internal fun onScanResultsSignal(resultsUpdated: Boolean) {
        if (!readinessStateProvider().canScanNow) {
            refreshReadinessCallback()
            return
        }

        val nowMs = clock.elapsedRealtimeMs()
        val rawThrottle = safeIpcCall(default = true) { isScanThrottleEnabledProvider() }

        if (!resultsUpdated) {
            scheduler.onBroadcastRejectedByOs(nowMs, rawThrottle)
            harvestCachedSeedIfNeeded()
            _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
            wakeChannel.trySend(Unit)
            return
        }

        val rawList = readOsScanResultsSafely() ?: return
        val signature = ScanResultExtractor.computeRawSweepSignature(rawList, sdkInt)

        val isDuplicate = synchronized(dedupLock) {
            val dt = nowMs - lastRawIngestElapsedMs
            if (hasIngestedFreshSweep &&
                signature == lastRawSignature &&
                dt in 0L..DUPLICATE_SWEEP_WINDOW_MS
            ) {
                true
            } else {
                hasIngestedFreshSweep = true
                lastRawSignature = signature
                lastRawIngestElapsedMs = nowMs
                false
            }
        }
        if (isDuplicate) return

        scheduler.onFreshSweepIngested(nowMs, rawThrottle)
        val batch = antiFlickerCache.ingestSweep(
            rawResults = rawList,
            nowElapsedMs = nowMs,
            nowMicros = clock.elapsedRealtimeMicros(),
            epochMs = clock.currentTimeMillis(),
            isFreshHardwareSweep = true,
            sdkInt = sdkInt,
        )
        _rawScanBatches.value = batch
        _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
        wakeChannel.trySend(Unit)
    }

    private fun harvestCachedSeedIfNeeded() {
        if (_rawScanBatches.value != null) return
        val rawList = readOsScanResultsSafely() ?: return
        if (rawList.isEmpty()) return

        val nowMs = clock.elapsedRealtimeMs()
        val batch = antiFlickerCache.ingestSweep(
            rawResults = rawList,
            nowElapsedMs = nowMs,
            nowMicros = clock.elapsedRealtimeMicros(),
            epochMs = clock.currentTimeMillis(),
            isFreshHardwareSweep = false,
            sdkInt = sdkInt,
        )
        if (batch.entries.isNotEmpty()) {
            scheduler.onSeedCacheHarvested(nowMs)
            _rawScanBatches.value = batch
            val rawThrottle = safeIpcCall(default = true) { isScanThrottleEnabledProvider() }
            _scanTelemetry.value = scheduler.snapshotTelemetry(nowMs, rawThrottle)
        }
    }

    private suspend fun runSchedulerLoop() {
        while (currentCoroutineContext().isActive) {
            while (wakeChannel.tryReceive().isSuccess) {
                // Drain conflated wake notifications
            }

            val nowBeforeWait = clock.elapsedRealtimeMs()
            val delayMs = scheduler.computeNextWakeDelayMs(nowBeforeWait)
            withTimeoutOrNull(delayMs) {
                wakeChannel.receive()
            }

            if (!currentCoroutineContext().isActive || !isStarted.get()) break

            val now = clock.elapsedRealtimeMs()
            val rawThrottle = safeIpcCall(default = true) { isScanThrottleEnabledProvider() }
            if (readinessStateProvider().canScanNow && scheduler.shouldAutoTriggerAt(now, rawThrottle)) {
                triggerActiveScan(burstMode = scheduler.isBurstModeActive)
            } else {
                _scanTelemetry.value = scheduler.snapshotTelemetry(now, rawThrottle)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readOsScanResultsSafely(): List<ScanResult?>? {
        val wm = wifiManagerOrNull() ?: return null
        var securityFailed = false
        val results = safeIpcCall(
            default = emptyList<ScanResult?>(),
            onSecurityException = { se ->
                securityFailed = true
                onSecurityException(se)
            },
        ) {
            (wm.scanResults as List<ScanResult?>?) ?: emptyList()
        }
        return if (securityFailed) null else results
    }

    private fun registerPassiveListeners() {
        val wm = wifiManagerOrNull()
        if (wm != null && callbackRegistered.compareAndSet(false, true)) {
            val registered = safeIpcCall(default = false, onSecurityException = onSecurityException) {
                wm.registerScanResultsCallback(Executor(Runnable::run), scanResultsCallback)
                true
            }
            if (!registered) {
                callbackRegistered.set(false)
            }
        }

        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            appContext.registerSystemBroadcastReceiverCompat(
                receiver = scanBroadcastReceiver,
                filter = filter,
                sdkInt = sdkInt,
            )
        }
    }

    private fun unregisterPassiveListeners() {
        val wm = wifiManagerOrNull()
        if (callbackRegistered.compareAndSet(true, false) && wm != null) {
            safeIpcUnit { wm.unregisterScanResultsCallback(scanResultsCallback) }
        }
        if (receiverRegistered.compareAndSet(true, false)) {
            safeIpcUnit { appContext.unregisterReceiver(scanBroadcastReceiver) }
        }
    }

    private fun wifiManagerOrNull(): WifiManager? =
        safeIpcCall(default = null, onSecurityException = onSecurityException) {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }

    companion object {
        const val DUPLICATE_SWEEP_WINDOW_MS = 250L
    }
}
