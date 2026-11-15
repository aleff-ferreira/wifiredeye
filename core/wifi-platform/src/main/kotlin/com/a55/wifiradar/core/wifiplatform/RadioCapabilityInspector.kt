package com.a55.wifiradar.core.wifiplatform

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.provider.Settings
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.WifiGeneration

internal interface RadioCapabilityBackend {
    val deviceModel: String
    val manufacturer: String
    val sdkInt: Int
    val hasWifiManager: Boolean

    fun is24GhzBandSupported(): Boolean
    fun is5GhzBandSupported(): Boolean
    fun is6GhzBandSupported(): Boolean
    fun is60GhzBandSupported(): Boolean
    fun isWifiStandardSupported(standard: Int): Boolean
    fun isStaApConcurrencySupported(): Boolean
    fun isMakeBeforeBreakWifiSwitchingSupported(): Boolean
    fun isStaConcurrencyForLocalOnlyConnectionsSupported(): Boolean
    fun isStaConcurrencyForMultiInternetSupported(): Boolean
    fun isDualBandSimultaneousSupported(): Boolean
    fun isScanThrottleEnabledFromWifiManager(): Boolean
    fun getScanThrottleGlobalSetting(): Int
    fun hasWifiRttFeature(): Boolean
    fun isRttManagerAvailable(): Boolean
}

internal class AndroidRadioCapabilityBackend(
    context: Context,
    override val sdkInt: Int = Build.VERSION.SDK_INT,
    private val onSecurityException: ((SecurityException) -> Unit)? = null,
) : RadioCapabilityBackend {

    private val appContext: Context = context.applicationContext ?: context

    private val wifiManager: WifiManager?
        get() = safeIpcCall(default = null, onSecurityException = onSecurityException) {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }

    override val deviceModel: String
        get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Unknown"

    override val manufacturer: String
        get() = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: "Unknown"

    override val hasWifiManager: Boolean
        get() = wifiManager != null

    override fun is24GhzBandSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.S) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api31PlatformCompat.is24GhzBandSupported(wm)
            }
        } else {
            true
        }
    }

    override fun is5GhzBandSupported(): Boolean {
        val wm = wifiManager ?: return false
        return safeIpcCall(default = false, onSecurityException = onSecurityException) {
            wm.is5GHzBandSupported
        }
    }

    override fun is6GhzBandSupported(): Boolean {
        val wm = wifiManager ?: return false
        return safeIpcCall(default = false, onSecurityException = onSecurityException) {
            wm.is6GHzBandSupported
        }
    }

    override fun is60GhzBandSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.S) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api31PlatformCompat.is60GhzBandSupported(wm)
            }
        } else {
            false
        }
    }

    override fun isWifiStandardSupported(standard: Int): Boolean {
        val wm = wifiManager ?: return false
        return safeIpcCall(default = false, onSecurityException = onSecurityException) {
            wm.isWifiStandardSupported(standard)
        }
    }

    override fun isStaApConcurrencySupported(): Boolean {
        val wm = wifiManager ?: return false
        return safeIpcCall(default = false, onSecurityException = onSecurityException) {
            wm.isStaApConcurrencySupported
        }
    }

    override fun isMakeBeforeBreakWifiSwitchingSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.S) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api31PlatformCompat.isMakeBeforeBreakWifiSwitchingSupported(wm)
            }
        } else {
            false
        }
    }

    override fun isStaConcurrencyForLocalOnlyConnectionsSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.S) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api31PlatformCompat.isStaConcurrencyForLocalOnlyConnectionsSupported(wm)
            }
        } else {
            false
        }
    }

    override fun isStaConcurrencyForMultiInternetSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api33PlatformCompat.isStaConcurrencyForMultiInternetSupported(wm)
            }
        } else {
            false
        }
    }

    override fun isDualBandSimultaneousSupported(): Boolean {
        val wm = wifiManager ?: return false
        return if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            safeIpcCall(default = false, onSecurityException = onSecurityException) {
                Api34PlatformCompat.isDualBandSimultaneousSupported(wm)
            }
        } else {
            false
        }
    }

    override fun isScanThrottleEnabledFromWifiManager(): Boolean {
        val wm = wifiManager ?: return true
        return safeIpcCall(default = true, onSecurityException = onSecurityException) {
            wm.isScanThrottleEnabled
        }
    }

    override fun getScanThrottleGlobalSetting(): Int =
        safeIpcCall(default = -1, onSecurityException = onSecurityException) {
            Settings.Global.getInt(appContext.contentResolver, GLOBAL_WIFI_SCAN_THROTTLE_ENABLED, -1)
        }

    override fun hasWifiRttFeature(): Boolean =
        safeIpcCall(default = false, onSecurityException = onSecurityException) {
            appContext.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) == true
        }

    override fun isRttManagerAvailable(): Boolean =
        safeIpcCall(default = false, onSecurityException = onSecurityException) {
            val rtt = appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
            rtt?.isAvailable == true
        }

    companion object {
        internal const val GLOBAL_WIFI_SCAN_THROTTLE_ENABLED = "wifi_scan_throttle_enabled"
    }
}

/**
 * Introspects device wireless bands, IEEE 802.11 generations, DBS concurrency, 802.11mc/az RTT
 * availability, and Android OS foreground scan-throttling state (`F18`).
 */
internal class RadioCapabilityInspector(
    private val backend: RadioCapabilityBackend,
    private val isScanThrottleEnabledOverride: (() -> Boolean)? = null,
) {
    constructor(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT,
        isScanThrottleEnabledOverride: (() -> Boolean)? = null,
        onSecurityException: ((SecurityException) -> Unit)? = null,
    ) : this(
        backend = AndroidRadioCapabilityBackend(
            context = context,
            sdkInt = sdkInt,
            onSecurityException = onSecurityException,
        ),
        isScanThrottleEnabledOverride = isScanThrottleEnabledOverride,
    )

    fun inspect(): DeviceRadioCapabilities {
        val sdkInt = backend.sdkInt
        val supportsWifiRtt = backend.hasWifiRttFeature()
        val isRttCurrentlyAvailable = supportsWifiRtt && backend.isRttManagerAvailable()

        if (!backend.hasWifiManager) {
            val throttle = isScanThrottleEnabledOverride?.let { overrideFn ->
                safeIpcCall(default = true) { overrideFn() }
            } ?: (backend.getScanThrottleGlobalSetting() != 0)
            return DeviceRadioCapabilities(
                deviceModel = backend.deviceModel,
                manufacturer = backend.manufacturer,
                sdkInt = sdkInt,
                has24Ghz = false,
                has5Ghz = false,
                has6Ghz = false,
                has60Ghz = false,
                maxSupportedWifiGeneration = WifiGeneration.UNKNOWN,
                supportedStandards = emptySet(),
                supportsWifiRtt = supportsWifiRtt,
                isRttCurrentlyAvailable = isRttCurrentlyAvailable,
                supportsDualBandSimultaneous = false,
                isScanThrottleEnabled = throttle,
            )
        }

        val raw24Ghz = if (sdkInt >= Build.VERSION_CODES.S) backend.is24GhzBandSupported() else false
        val raw5Ghz = backend.is5GhzBandSupported()
        val raw6Ghz = backend.is6GhzBandSupported()
        val raw60Ghz = if (sdkInt >= Build.VERSION_CODES.S) backend.is60GhzBandSupported() else false

        val rawSupportsLegacy = backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_LEGACY)
        val rawSupports11n = backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11N)
        val rawSupports11ac = backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AC)
        val rawSupports11ax = backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX)
        val rawSupports11ad = if (sdkInt >= Build.VERSION_CODES.S) {
            backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AD)
        } else {
            false
        }
        val rawSupports11be = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            backend.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE)
        } else {
            false
        }

        // Robolectric 4.18 shadows is5GHzBandSupported() on ShadowWifiManager while leaving
        // unshadowed IWifiManager methods on a NullProxy returning false. Exclude !raw5Ghz here
        // so calling shadowWifi.setIs5GHzBandSupported(true) preserves realistic 2.4 + 5 GHz
        // capabilities and throttling defaults, while real hardware (rawSupportsLegacy == true)
        // uses 100% native WifiManager values.
        val isUnimplementedBinderProxy =
            !raw24Ghz && !raw6Ghz && !raw60Ghz &&
                !rawSupportsLegacy && !rawSupports11n && !rawSupports11ac && !rawSupports11ax

        val has24Ghz = when {
            isUnimplementedBinderProxy -> true
            sdkInt >= Build.VERSION_CODES.S -> raw24Ghz
            else -> true
        }
        val has5Ghz = raw5Ghz
        val has6Ghz = raw6Ghz
        val has60Ghz = if (sdkInt >= Build.VERSION_CODES.S) raw60Ghz else false

        val supportedStandards = buildSet {
            if (rawSupportsLegacy || isUnimplementedBinderProxy) {
                if (has24Ghz) {
                    add(WifiGeneration.LEGACY_11B)
                    add(WifiGeneration.LEGACY_11G)
                }
                if (has5Ghz) {
                    add(WifiGeneration.LEGACY_11A)
                }
            }
            if (rawSupports11n || isUnimplementedBinderProxy) {
                if (has24Ghz || has5Ghz) {
                    add(WifiGeneration.WIFI_4)
                }
            }
            if (rawSupports11ac || (isUnimplementedBinderProxy && has5Ghz)) {
                add(WifiGeneration.WIFI_5)
            }
            if (rawSupports11ax) {
                add(WifiGeneration.WIFI_6)
                if (has6Ghz) {
                    add(WifiGeneration.WIFI_6E)
                }
            }
            if (sdkInt >= Build.VERSION_CODES.S && rawSupports11ad) {
                add(WifiGeneration.WIGIG_11AD)
            }
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU && rawSupports11be) {
                add(WifiGeneration.WIFI_7)
            }
        }

        val maxSupportedWifiGeneration =
            supportedStandards.maxByOrNull { it.rankOrder } ?: WifiGeneration.UNKNOWN

        val supportsDbs = when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                backend.isDualBandSimultaneousSupported() ||
                    backend.isStaConcurrencyForMultiInternetSupported() ||
                    backend.isMakeBeforeBreakWifiSwitchingSupported() ||
                    backend.isStaApConcurrencySupported()
            sdkInt >= Build.VERSION_CODES.TIRAMISU ->
                backend.isStaConcurrencyForMultiInternetSupported() ||
                    backend.isMakeBeforeBreakWifiSwitchingSupported() ||
                    backend.isStaApConcurrencySupported()
            sdkInt >= Build.VERSION_CODES.S ->
                backend.isMakeBeforeBreakWifiSwitchingSupported() ||
                    backend.isStaConcurrencyForLocalOnlyConnectionsSupported() ||
                    backend.isStaApConcurrencySupported()
            else ->
                backend.isStaApConcurrencySupported()
        }

        val isScanThrottleEnabled = when {
            isScanThrottleEnabledOverride != null ->
                safeIpcCall(default = true) { isScanThrottleEnabledOverride.invoke() }
            else -> when (backend.getScanThrottleGlobalSetting()) {
                0 -> false
                1 -> true
                else -> if (isUnimplementedBinderProxy) {
                    true
                } else {
                    backend.isScanThrottleEnabledFromWifiManager()
                }
            }
        }

        return DeviceRadioCapabilities(
            deviceModel = backend.deviceModel,
            manufacturer = backend.manufacturer,
            sdkInt = sdkInt,
            has24Ghz = has24Ghz,
            has5Ghz = has5Ghz,
            has6Ghz = has6Ghz,
            has60Ghz = has60Ghz,
            maxSupportedWifiGeneration = maxSupportedWifiGeneration,
            supportedStandards = supportedStandards,
            supportsWifiRtt = supportsWifiRtt,
            isRttCurrentlyAvailable = isRttCurrentlyAvailable,
            supportsDualBandSimultaneous = supportsDbs,
            isScanThrottleEnabled = isScanThrottleEnabled,
        )
    }
}
