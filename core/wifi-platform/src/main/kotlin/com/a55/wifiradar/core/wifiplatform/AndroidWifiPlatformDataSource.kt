package com.a55.wifiradar.core.wifiplatform

import android.content.Context
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.WifiPlatformDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production Android implementation of [WifiPlatformDataSource] (`F13, F18–F21, F33, F34`).
 *
 * - Provides zero-boilerplate `constructor(context: Context)` defaults, [DefaultLifecycleObserver]
 *   (`onStart` / `onResume` / `onStop`), [Closeable], and overlap-safe ref-counted
 *   [bindToLifecycle] for Activity recreation (`ActivityScenario.recreate()`).
 * - Isolates every Android IPC call via [safeIpcCall] (preserving `CancellationException`) and
 *   runs foreground timers inside a child [SupervisorJob] cancelled immediately on [stop].
 */
class AndroidWifiPlatformDataSource internal constructor(
    context: Context,
    private val clock: PlatformClock,
    coroutineDispatcher: CoroutineDispatcher,
    isScanThrottleEnabledOverride: (() -> Boolean)?,
    sdkInt: Int,
    radioCapabilityBackend: RadioCapabilityBackend?,
    triggerInitialScanOnStart: Boolean,
    rttTimeoutMs: Long,
) : WifiPlatformDataSource, DefaultLifecycleObserver, Closeable {

    @JvmOverloads
    constructor(
        context: Context,
        clock: PlatformClock = SystemPlatformClock,
        coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
        isScanThrottleEnabledOverride: (() -> Boolean)? = null,
    ) : this(
        context = context,
        clock = clock,
        coroutineDispatcher = coroutineDispatcher,
        isScanThrottleEnabledOverride = isScanThrottleEnabledOverride,
        sdkInt = Build.VERSION.SDK_INT,
        radioCapabilityBackend = null,
        triggerInitialScanOnStart = true,
        rttTimeoutMs = SafeWifiRttAdapter.DEFAULT_RTT_TIMEOUT_MS,
    )

    private val appContext: Context = context.applicationContext ?: context
    private val lifecycleLock = Any()
    private val isStarted = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    private val boundLifecycleRefCount = AtomicInteger(0)

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        // Swallowed defensively per F34 so background IPC anomalies never crash the process
    }
    private val rootJob = SupervisorJob()
    private val rootScope = CoroutineScope(rootJob + coroutineDispatcher + exceptionHandler)
    private var sessionJob: Job? = null

    private val capabilityInspector = RadioCapabilityInspector(
        backend = radioCapabilityBackend ?: AndroidRadioCapabilityBackend(
            context = appContext,
            sdkInt = sdkInt,
            onSecurityException = ::handleSecurityException,
        ),
        isScanThrottleEnabledOverride = isScanThrottleEnabledOverride,
    )

    private val _radioCapabilities = MutableStateFlow(capabilityInspector.inspect())
    override val radioCapabilities: StateFlow<DeviceRadioCapabilities> =
        _radioCapabilities.asStateFlow()

    private val readinessMonitor = ReadinessMonitor(
        context = appContext,
        sdkInt = sdkInt,
        onCapabilitiesMayHaveChanged = ::refreshCapabilitiesAndTelemetry,
        onCanScanTransitionToReady = ::handleBecameReadyToScan,
    )

    override val readinessState: StateFlow<ScannerReadinessState> = readinessMonitor.readinessState

    internal val hybridWifiScanner = HybridWifiScanner(
        context = appContext,
        clock = clock,
        readinessStateProvider = { readinessState.value },
        refreshReadinessCallback = { readinessMonitor.refresh() },
        isScanThrottleEnabledProvider = {
            _radioCapabilities.value = capabilityInspector.inspect()
            _radioCapabilities.value.isScanThrottleEnabled
        },
        onSecurityException = ::handleSecurityException,
        sdkInt = sdkInt,
        triggerInitialScanOnStart = triggerInitialScanOnStart,
    )

    override val rawScanBatches: StateFlow<RawScanBatch?> = hybridWifiScanner.rawScanBatches
    override val scanTelemetry: StateFlow<ScanTelemetry> = hybridWifiScanner.scanTelemetry

    internal val connectedWifiTracker = ConnectedWifiTracker(
        context = appContext,
        clock = clock,
        onSecurityException = ::handleSecurityException,
        sdkInt = sdkInt,
    )

    override val connectedWifi: StateFlow<ConnectedWifiObservation?> =
        connectedWifiTracker.connectedWifi

    private val rttAdapter = SafeWifiRttAdapter(
        context = appContext,
        clock = clock,
        scanResultLookup = hybridWifiScanner::getLatestRawScanResult,
        canScanNow = { readinessState.value.canScanNow },
        onSecurityException = ::handleSecurityException,
        timeoutMs = rttTimeoutMs,
        sdkInt = sdkInt,
    )

    /**
     * Starts live OS broadcast/callback monitoring, active scan pacing, and connected-AP tracking.
     * Idempotent across repeated calls and safe to restart after [stop].
     */
    fun start() {
        if (isClosed.get()) return
        val childScope: CoroutineScope
        synchronized(lifecycleLock) {
            if (!isStarted.compareAndSet(false, true)) return
            val childJob = SupervisorJob(rootJob)
            sessionJob = childJob
            childScope = CoroutineScope(rootScope.coroutineContext + childJob)
        }
        _radioCapabilities.value = capabilityInspector.inspect()
        readinessMonitor.start()
        connectedWifiTracker.start(childScope)
        hybridWifiScanner.start(childScope)
    }

    /**
     * Halts all active scan timers and `300 ms` `WifiInfo` polling and unregisters all OS
     * broadcast receivers, callbacks, and content observers immediately (`F33`).
     */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!isStarted.compareAndSet(true, false)) return
            sessionJob?.cancel()
            sessionJob = null
        }
        hybridWifiScanner.stop()
        connectedWifiTracker.stop()
        readinessMonitor.stop()
    }

    /**
     * Attaches a ref-counted [DefaultLifecycleObserver] so overlapping Activity recreations
     * (`ActivityScenario.recreate()`) keep scanning seamlessly without prematurely stopping.
     */
    fun bindToLifecycle(lifecycle: Lifecycle) {
        if (isClosed.get()) return
        val observer = object : DefaultLifecycleObserver {
            private var countedAsStarted = false

            override fun onStart(owner: LifecycleOwner) {
                if (!countedAsStarted) {
                    countedAsStarted = true
                    if (boundLifecycleRefCount.incrementAndGet() == 1) {
                        start()
                    }
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                refreshReadiness()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (countedAsStarted) {
                    countedAsStarted = false
                    if (boundLifecycleRefCount.decrementAndGet().coerceAtLeast(0) == 0) {
                        stop()
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (countedAsStarted) {
                    countedAsStarted = false
                    if (boundLifecycleRefCount.decrementAndGet().coerceAtLeast(0) == 0) {
                        stop()
                    }
                }
                owner.lifecycle.removeObserver(this)
            }
        }
        lifecycle.addObserver(observer)
    }

    override fun onStart(owner: LifecycleOwner) {
        start()
    }

    override fun onResume(owner: LifecycleOwner) {
        refreshReadiness()
    }

    override fun onStop(owner: LifecycleOwner) {
        stop()
    }

    override fun triggerActiveScan(burstMode: Boolean): ScanTriggerResult {
        _radioCapabilities.value = capabilityInspector.inspect()
        readinessMonitor.refresh(notifyReadyTransition = false)
        return hybridWifiScanner.triggerActiveScan(burstMode = burstMode)
    }

    override fun setHighFrequencyConnectedPolling(enabled: Boolean) {
        connectedWifiTracker.setHighFrequencyPolling(enabled)
    }

    override fun refreshReadiness() {
        _radioCapabilities.value = capabilityInspector.inspect()
        readinessMonitor.refresh(notifyReadyTransition = true)
        hybridWifiScanner.refreshTelemetry()
    }

    override fun reportPermissionDenial(permanentlyDenied: Boolean) {
        readinessMonitor.reportPermissionDenial(permanentlyDenied)
    }

    override suspend fun requestRttRange(bssid: String): RttMeasurement? =
        rttAdapter.requestRttRange(bssid)

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        stop()
        rootScope.cancel()
    }

    private fun handleSecurityException(se: SecurityException) {
        readinessMonitor.onSecurityExceptionObserved()
    }

    private fun refreshCapabilitiesAndTelemetry() {
        _radioCapabilities.value = capabilityInspector.inspect()
        hybridWifiScanner.refreshTelemetry()
    }

    private fun handleBecameReadyToScan() {
        _radioCapabilities.value = capabilityInspector.inspect()
        connectedWifiTracker.refreshConnectedWifi()
        hybridWifiScanner.onReadinessBecameReady()
    }
}
