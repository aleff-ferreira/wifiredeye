package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessChecklist
import com.a55.wifiradar.core.model.ReadinessStep
import com.a55.wifiradar.core.model.ReadinessStepState
import com.a55.wifiradar.core.model.ScannerReadinessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Progressive, self-healing OS Readiness State Machine (`F19`).
 *
 * Evaluates runtime permissions, Approximate-vs-Precise location upgrade, permanent denial,
 * Location Services, Wi-Fi Radio, Background Wi-Fi Scanning (`isScanAlwaysAvailable`), and
 * Airplane Mode across API 30–35, recovering live via both OS [BroadcastReceiver]s and
 * [Settings] [ContentObserver]s.
 */
internal class ReadinessMonitor(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val onCapabilitiesMayHaveChanged: () -> Unit = {},
    private val onCanScanTransitionToReady: () -> Unit = {},
) {
    private val appContext: Context = context.applicationContext ?: context
    private val stateLock = Any()

    private val isStarted = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)

    private var permanentlyDeniedLatch: Boolean = false
    private var coarseGrantedAtDenialTime: Boolean = false

    private val _readinessState: MutableStateFlow<ScannerReadinessState>

    val readinessState: StateFlow<ScannerReadinessState>

    private val readinessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action !in MONITORED_BROADCAST_ACTIONS) return
            if (action == WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED) {
                onCapabilitiesMayHaveChanged()
            }
            refresh()
        }
    }

    private val settingsObserver: ContentObserver by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val uriStr = uri?.toString().orEmpty()
                if (uriStr.endsWith(GLOBAL_WIFI_SCAN_THROTTLE_ENABLED)) {
                    onCapabilitiesMayHaveChanged()
                }
                refresh()
            }
        }
    }

    init {
        val initial = synchronized(stateLock) { computeStateLocked() }
        _readinessState = MutableStateFlow(initial)
        readinessState = _readinessState.asStateFlow()
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return
        registerReceiversAndObservers()
        refresh(notifyReadyTransition = false)
    }

    fun stop() {
        if (!isStarted.compareAndSet(true, false)) return
        unregisterReceiversAndObservers()
    }

    fun refresh(notifyReadyTransition: Boolean = true): ScannerReadinessState {
        val previousCanScan: Boolean
        val updated: ScannerReadinessState
        synchronized(stateLock) {
            previousCanScan = _readinessState.value.canScanNow
            updated = computeStateLocked()
            _readinessState.value = updated
        }
        if (notifyReadyTransition && isStarted.get() && !previousCanScan && updated.canScanNow) {
            onCanScanTransitionToReady()
        }
        return updated
    }

    fun reportPermissionDenial(permanentlyDenied: Boolean) {
        synchronized(stateLock) {
            if (permanentlyDenied) {
                permanentlyDeniedLatch = true
                coarseGrantedAtDenialTime = isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                permanentlyDeniedLatch = false
                coarseGrantedAtDenialTime = false
            }
        }
        refresh()
    }

    fun onSecurityExceptionObserved() {
        refresh()
    }

    private fun computeStateLocked(): ScannerReadinessState {
        val fineGranted = isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseGranted = isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val nearbyGranted = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            true
        }
        val allPermissionsGranted = fineGranted && nearbyGranted

        // Self-healing latch auto-clear:
        // 1. Granting all required permissions (e.g., via OS App Settings) clears permanent denial.
        // 2. Enabling Approximate location in App Settings after complete denial clears latch so
        //    the user sees the 1-tap Upgrade-to-Precise prompt.
        if (allPermissionsGranted) {
            permanentlyDeniedLatch = false
            coarseGrantedAtDenialTime = false
        } else if (permanentlyDeniedLatch && !coarseGrantedAtDenialTime && coarseGranted && !fineGranted) {
            permanentlyDeniedLatch = false
        }

        val missingPermissions = buildList {
            if (!fineGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!coarseGranted && !fineGranted) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU && !nearbyGranted) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        val isLocationEnabled = queryIsLocationEnabled()
        val isWifiEnabled = queryIsWifiEnabled()
        val isScanAlwaysAvailable = queryIsScanAlwaysAvailable()
        val isAirplaneMode = queryIsAirplaneModeOn()

        val checklist = buildChecklist(
            allPermissionsGranted = allPermissionsGranted,
            permanentlyDenied = !allPermissionsGranted && permanentlyDeniedLatch,
            approximateOnly = !fineGranted && coarseGranted,
            isLocationEnabled = isLocationEnabled,
            isWifiEnabled = isWifiEnabled,
            isScanAlwaysAvailable = isScanAlwaysAvailable,
            isAirplaneMode = isAirplaneMode,
        )

        return when {
            !allPermissionsGranted && permanentlyDeniedLatch ->
                ScannerReadinessState.PermanentDenial(
                    deniedPermissions = missingPermissions,
                    checklist = checklist,
                )

            !fineGranted && coarseGranted ->
                ScannerReadinessState.ApproximateOnlyNeedsPrecise(
                    checklist = checklist,
                )

            !allPermissionsGranted ->
                ScannerReadinessState.MissingPermission(
                    missingPermissions = missingPermissions,
                    checklist = checklist,
                )

            !isLocationEnabled ->
                ScannerReadinessState.LocationDisabled(
                    checklist = checklist,
                )

            !isWifiEnabled && (!isScanAlwaysAvailable || isAirplaneMode) ->
                ScannerReadinessState.WifiAndScanDisabled(
                    isAirplaneMode = isAirplaneMode,
                    checklist = checklist,
                )

            !isWifiEnabled && isScanAlwaysAvailable && !isAirplaneMode ->
                ScannerReadinessState.WifiDisabledScanAlwaysOn(
                    checklist = checklist,
                )

            else ->
                ScannerReadinessState.Ready(
                    checklist = checklist,
                )
        }
    }

    private fun buildChecklist(
        allPermissionsGranted: Boolean,
        permanentlyDenied: Boolean,
        approximateOnly: Boolean,
        isLocationEnabled: Boolean,
        isWifiEnabled: Boolean,
        isScanAlwaysAvailable: Boolean,
        isAirplaneMode: Boolean,
    ): ReadinessChecklist {
        val permissionStep = when {
            allPermissionsGranted -> ReadinessStep(
                stepNumber = 1,
                title = "Nearby Wi-Fi & Precise Location Permission",
                plainEnglishDetail = "Granted — 100% offline local Wi-Fi scanning enabled.",
                state = ReadinessStepState.SATISFIED,
                action = ReadinessAction.NONE,
                actionButtonLabel = null,
            )

            permanentlyDenied -> ReadinessStep(
                stepNumber = 1,
                title = "Allow Precise Location in App Settings",
                plainEnglishDetail = "Permission was permanently declined. Open App Settings and allow Precise Location while using the app.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.OPEN_APP_PERMISSION_SETTINGS,
                actionButtonLabel = "Open App Permission Settings",
            )

            approximateOnly -> ReadinessStep(
                stepNumber = 1,
                title = "Upgrade Location from Approximate to Precise",
                plainEnglishDetail = "Android 12+ hides nearby Wi-Fi routers when only Approximate Location is enabled.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.UPGRADE_TO_PRECISE_LOCATION,
                actionButtonLabel = "Upgrade to Precise Location",
            )

            else -> ReadinessStep(
                stepNumber = 1,
                title = "Nearby Wi-Fi & Precise Location Permission",
                plainEnglishDetail = "Required by Android to read nearby router names and channels — zero location data ever leaves your phone.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
                actionButtonLabel = "Grant Scan Permission",
            )
        }

        val locationStep = if (isLocationEnabled) {
            ReadinessStep(
                stepNumber = 2,
                title = "Device Location Services",
                plainEnglishDetail = "Turned on — Android allows Wi-Fi scan results.",
                state = ReadinessStepState.SATISFIED,
                action = ReadinessAction.NONE,
                actionButtonLabel = null,
            )
        } else {
            ReadinessStep(
                stepNumber = 2,
                title = "Turn On Device Location",
                plainEnglishDetail = "Phone Location is turned off. Android returns zero Wi-Fi networks while Location is disabled.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.OPEN_LOCATION_SETTINGS,
                actionButtonLabel = "Open Location Settings",
            )
        }

        val wifiStep = when {
            isWifiEnabled -> ReadinessStep(
                stepNumber = 3,
                title = "Wi-Fi Radio",
                plainEnglishDetail = "Turned on and actively listening across bands.",
                state = ReadinessStepState.SATISFIED,
                action = ReadinessAction.NONE,
                actionButtonLabel = null,
            )

            isScanAlwaysAvailable && !isAirplaneMode -> ReadinessStep(
                stepNumber = 3,
                title = "Turn On Wi-Fi Radio (Optional)",
                plainEnglishDetail = "Background Wi-Fi Scanning is active. Turn on Wi-Fi to connect or measure 300 ms live link telemetry.",
                state = ReadinessStepState.WARNING_SCAN_STILL_ACTIVE,
                action = ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
                actionButtonLabel = "Turn On Wi-Fi",
            )

            isAirplaneMode -> ReadinessStep(
                stepNumber = 3,
                title = "Turn On Wi-Fi (Airplane Mode Active)",
                plainEnglishDetail = "Airplane Mode turned off wireless radios. Turn on Wi-Fi to resume scanning.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
                actionButtonLabel = "Turn On Wi-Fi",
            )

            else -> ReadinessStep(
                stepNumber = 3,
                title = "Turn On Wi-Fi Radio",
                plainEnglishDetail = "Both Wi-Fi and background Wi-Fi Scanning are off, so the radio cannot listen for routers.",
                state = ReadinessStepState.ACTION_REQUIRED,
                action = ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
                actionButtonLabel = "Turn On Wi-Fi",
            )
        }

        return ReadinessChecklist(
            permissionStep = permissionStep,
            locationStep = locationStep,
            wifiStep = wifiStep,
        )
    }

    private fun isPermissionGranted(permission: String): Boolean =
        safeIpcCall(default = false) {
            ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun queryIsLocationEnabled(): Boolean =
        safeIpcCall(default = false) {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            lm?.isLocationEnabled == true
        }

    private fun queryIsWifiEnabled(): Boolean =
        safeIpcCall(default = false) {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled == true
        }

    @Suppress("DEPRECATION")
    private fun queryIsScanAlwaysAvailable(): Boolean =
        safeIpcCall(default = false) {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isScanAlwaysAvailable == true
        }

    private fun queryIsAirplaneModeOn(): Boolean =
        safeIpcCall(default = false) {
            Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) != 0
        }

    private fun registerReceiversAndObservers() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                MONITORED_BROADCAST_ACTIONS.forEach(::addAction)
            }
            appContext.registerSystemBroadcastReceiverCompat(
                receiver = readinessReceiver,
                filter = filter,
                sdkInt = sdkInt,
            )
        }

        if (observerRegistered.compareAndSet(false, true)) {
            safeIpcUnit {
                val resolver = appContext.contentResolver
                @Suppress("DEPRECATION")
                val uris = listOfNotNull(
                    Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                    Settings.Global.getUriFor(GLOBAL_WIFI_SCAN_ALWAYS_ENABLED),
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                    Settings.Global.getUriFor(GLOBAL_WIFI_SCAN_THROTTLE_ENABLED),
                )
                for (uri in uris) {
                    resolver.registerContentObserver(uri, false, settingsObserver)
                }
            }
        }
    }

    private fun unregisterReceiversAndObservers() {
        if (receiverRegistered.compareAndSet(true, false)) {
            safeIpcUnit { appContext.unregisterReceiver(readinessReceiver) }
        }
        if (observerRegistered.compareAndSet(true, false)) {
            safeIpcUnit { appContext.contentResolver.unregisterContentObserver(settingsObserver) }
        }
    }

    companion object {
        internal const val GLOBAL_WIFI_SCAN_ALWAYS_ENABLED = "wifi_scan_always_enabled"
        internal const val GLOBAL_WIFI_SCAN_THROTTLE_ENABLED =
            AndroidRadioCapabilityBackend.GLOBAL_WIFI_SCAN_THROTTLE_ENABLED

        private val MONITORED_BROADCAST_ACTIONS = setOf(
            WifiManager.WIFI_STATE_CHANGED_ACTION,
            WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED,
            LocationManager.MODE_CHANGED_ACTION,
            LocationManager.PROVIDERS_CHANGED_ACTION,
            Intent.ACTION_AIRPLANE_MODE_CHANGED,
            WifiRttManager.ACTION_WIFI_RTT_STATE_CHANGED,
        )
    }
}
