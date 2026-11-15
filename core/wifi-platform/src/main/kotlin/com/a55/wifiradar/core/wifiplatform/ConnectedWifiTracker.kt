package com.a55.wifiradar.core.wifiplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.MacAddressUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unthrottled connected-AP telemetry stream (`F21, F33, F34`).
 *
 * - Registers a `NetworkRequest` with `TRANSPORT_WIFI` and `removeCapability(NET_CAPABILITY_INTERNET)`
 *   so local/offline routers without WAN internet still report live telemetry.
 * - Uses `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` on API 31+ (`onCapabilitiesChanged`,
 *   `onLinkPropertiesChanged`, `onLost` — never reading `WifiInfo` inside `onAvailable`).
 * - Cross-checks `WifiManager.connectionInfo` (including `ShadowWifiInfo.getLinkSpeed()` fallback)
 *   and `RSSI_CHANGED_ACTION` broadcasts.
 * - Runs a `300 ms` coroutine poller strictly when [setHighFrequencyPolling] is enabled and started.
 */
internal class ConnectedWifiTracker(
    context: Context,
    private val clock: PlatformClock,
    private val onSecurityException: (SecurityException) -> Unit,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val stateLock = Any()

    private val isStarted = AtomicBoolean(false)
    private val highFrequencyEnabled = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val broadcastReceiverRegistered = AtomicBoolean(false)

    private var activeScope: CoroutineScope? = null
    private var highFreqPollJob: Job? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var trackedWifiNetwork: Network? = null
    private var latestCapsForTrackedNetwork: NetworkCapabilities? = null
    private var latestLinkProperties: LinkProperties? = null
    private var networkExplicitlyLost: Boolean = false
    private var suppressedLegacyInfoIdentity: Int = 0

    private val _connectedWifi = MutableStateFlow<ConnectedWifiObservation?>(null)
    val connectedWifi: StateFlow<ConnectedWifiObservation?> = _connectedWifi.asStateFlow()

    private val wifiStateAndRssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.RSSI_CHANGED_ACTION -> {
                    val rssiHint = safeIpcCall(default = Int.MIN_VALUE) {
                        intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE)
                    }.takeIf { it in MIN_VALID_RSSI_DBM..MAX_VALID_RSSI_DBM }
                    synchronized(stateLock) {
                        networkExplicitlyLost = false
                        suppressedLegacyInfoIdentity = 0
                    }
                    refreshConnectedWifi(rssiHint = rssiHint)
                }

                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    synchronized(stateLock) {
                        networkExplicitlyLost = false
                        suppressedLegacyInfoIdentity = 0
                    }
                    refreshConnectedWifi()
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val wifiState = safeIpcCall(default = WifiManager.WIFI_STATE_UNKNOWN) {
                        intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_UNKNOWN,
                        )
                    }
                    if (wifiState == WifiManager.WIFI_STATE_DISABLED ||
                        wifiState == WifiManager.WIFI_STATE_DISABLING
                    ) {
                        synchronized(stateLock) {
                            trackedWifiNetwork = null
                            latestCapsForTrackedNetwork = null
                            latestLinkProperties = null
                        }
                        _connectedWifi.value = null
                    } else {
                        refreshConnectedWifi()
                    }
                }
            }
        }
    }

    fun start(sessionScope: CoroutineScope) {
        if (!isStarted.compareAndSet(false, true)) return
        synchronized(stateLock) {
            activeScope = sessionScope
        }
        registerListeners()
        refreshConnectedWifi()
        syncHighFrequencyPollerLocked()
    }

    fun stop() {
        if (!isStarted.compareAndSet(true, false)) return
        synchronized(stateLock) {
            highFreqPollJob?.cancel()
            highFreqPollJob = null
            activeScope = null
        }
        unregisterListeners()
    }

    fun setHighFrequencyPolling(enabled: Boolean) {
        highFrequencyEnabled.set(enabled)
        syncHighFrequencyPollerLocked()
    }

    fun refreshConnectedWifi(
        rssiHint: Int? = null,
        preferTransportSnapshot: Boolean = false,
    ) {
        val wm = wifiManagerOrNull()
        val cm = connectivityManagerOrNull()

        val trackedNet: Network?
        val cachedCaps: NetworkCapabilities?
        val cachedLinkProps: LinkProperties?
        val wasExplicitlyLost: Boolean
        val suppressedIdentity: Int
        synchronized(stateLock) {
            trackedNet = trackedWifiNetwork
            cachedCaps = latestCapsForTrackedNetwork
            cachedLinkProps = latestLinkProperties
            wasExplicitlyLost = networkExplicitlyLost
            suppressedIdentity = suppressedLegacyInfoIdentity
        }

        val wifiEnabled = safeIpcCall(default = true, onSecurityException = onSecurityException) {
            wm?.isWifiEnabled != false
        }
        if (!wifiEnabled && trackedNet == null) {
            _connectedWifi.value = null
            return
        }

        val liveCaps = cachedCaps
            ?: trackedNet?.let { net ->
                safeIpcCall(default = null, onSecurityException = onSecurityException) {
                    cm?.getNetworkCapabilities(net)
                }
            }
            ?: if (!wasExplicitlyLost) findActiveWifiCapabilities(cm) else null

        val transportWifiInfo = safeIpcCall(default = null, onSecurityException = onSecurityException) {
            liveCaps
                ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                ?.transportInfo as? WifiInfo
        }

        @Suppress("DEPRECATION")
        val rawLegacyInfo = safeIpcCall(default = null, onSecurityException = onSecurityException) {
            wm?.connectionInfo
        }
        val legacyConnectionInfo = rawLegacyInfo?.takeIf { info ->
            if (wasExplicitlyLost && System.identityHashCode(info) == suppressedIdentity) {
                false
            } else {
                isGenuineLegacyConnection(info)
            }
        }

        if (transportWifiInfo == null && legacyConnectionInfo == null) {
            _connectedWifi.value = null
            return
        }

        val primary = transportWifiInfo ?: legacyConnectionInfo!!
        val secondary = legacyConnectionInfo?.takeIf { it !== primary }

        val primaryBssid = normalizeUnredactedMacOrNull(
            safeIpcCall(default = null) { primary.bssid },
        )
        val secondaryBssid = secondary?.let { sec ->
            normalizeUnredactedMacOrNull(safeIpcCall(default = null) { sec.bssid })
        }

        val (bssid, isLocationRedacted) = when {
            primaryBssid != null -> primaryBssid to false
            secondaryBssid != null -> secondaryBssid to false
            else -> MacAddressUtils.ANDROID_REDACTED_MAC to true
        }

        val ssid = cleanWifiInfoSsid(safeIpcCall(default = null) { primary.ssid })
            ?: secondary?.let { sec -> cleanWifiInfoSsid(safeIpcCall(default = null) { sec.ssid }) }

        val primaryRssi = safeIpcCall(default = INVALID_RSSI) { primary.rssi }
            .takeIf { it in MIN_VALID_RSSI_DBM..MAX_VALID_RSSI_DBM }
        val secondaryRssi = secondary?.let { sec ->
            safeIpcCall(default = INVALID_RSSI) { sec.rssi }
                .takeIf { it in MIN_VALID_RSSI_DBM..MAX_VALID_RSSI_DBM }
        }

        val resolvedRssi = when {
            rssiHint != null -> rssiHint
            preferTransportSnapshot && primaryRssi != null -> primaryRssi
            secondaryRssi != null && (secondaryBssid == bssid || isLocationRedacted) -> secondaryRssi
            primaryRssi != null -> primaryRssi
            secondaryRssi != null -> secondaryRssi
            else -> -100
        }.coerceIn(-127, 0)

        val frequencyMhz = (
            safeIpcCall(default = 0) { primary.frequency }.takeIf { it > 0 }
                ?: secondary?.let { sec -> safeIpcCall(default = 0) { sec.frequency }.takeIf { it > 0 } }
                ?: 0
            ).coerceAtLeast(0)

        val fallbackLinkSpeed = safeIpcCall(default = 0) { primary.linkSpeed }.takeIf { it > 0 }
            ?: secondary?.let { sec -> safeIpcCall(default = 0) { sec.linkSpeed }.takeIf { it > 0 } }
            ?: 0

        val txLinkSpeedMbps = safeIpcCall(default = 0) { primary.txLinkSpeedMbps }.takeIf { it > 0 }
            ?: secondary?.let { sec -> safeIpcCall(default = 0) { sec.txLinkSpeedMbps }.takeIf { it > 0 } }
            ?: fallbackLinkSpeed

        val rxLinkSpeedMbps = safeIpcCall(default = 0) { primary.rxLinkSpeedMbps }.takeIf { it > 0 }
            ?: secondary?.let { sec -> safeIpcCall(default = 0) { sec.rxLinkSpeedMbps }.takeIf { it > 0 } }
            ?: fallbackLinkSpeed

        val maxTxMbps = safeIpcCall(default = null) {
            primary.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 }
        } ?: secondary?.let { sec ->
            safeIpcCall(default = null) { sec.maxSupportedTxLinkSpeedMbps.takeIf { it > 0 } }
        }

        val maxRxMbps = safeIpcCall(default = null) {
            primary.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 }
        } ?: secondary?.let { sec ->
            safeIpcCall(default = null) { sec.maxSupportedRxLinkSpeedMbps.takeIf { it > 0 } }
        }

        val wifiStandardCode = safeIpcCall(default = 0) { primary.wifiStandard }.takeIf { it > 0 }
            ?: secondary?.let { sec -> safeIpcCall(default = 0) { sec.wifiStandard }.takeIf { it > 0 } }
            ?: 0

        val securityTypeCode = if (sdkInt >= Build.VERSION_CODES.S) {
            Api31PlatformCompat.getCurrentSecurityTypeOrNull(primary)
                ?: secondary?.let(Api31PlatformCompat::getCurrentSecurityTypeOrNull)
        } else {
            null
        }

        val effectiveLinkProps = cachedLinkProps ?: trackedNet?.let { net ->
            safeIpcCall(default = null, onSecurityException = onSecurityException) {
                cm?.getLinkProperties(net)
            }
        }
        val ipAddressV4 = extractIpv4FromLinkProperties(effectiveLinkProps)
            ?: extractDeprecatedIpv4(primary)
            ?: secondary?.let(::extractDeprecatedIpv4)

        val apMldMacAddress = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getApMldMacAddressOrNull(primary)
                ?: secondary?.let(Api33PlatformCompat::getApMldMacAddressOrNull)
        } else {
            null
        }

        val mloLinks = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getWifiInfoMloLinks(primary, sdkInt).ifEmpty {
                secondary?.let { sec -> Api33PlatformCompat.getWifiInfoMloLinks(sec, sdkInt) }.orEmpty()
            }
        } else {
            emptyList()
        }

        _connectedWifi.value = ConnectedWifiObservation(
            bssid = bssid,
            ssid = ssid,
            isLocationRedacted = isLocationRedacted,
            rssiDbm = resolvedRssi,
            frequencyMhz = frequencyMhz,
            txLinkSpeedMbps = txLinkSpeedMbps,
            rxLinkSpeedMbps = rxLinkSpeedMbps,
            maxSupportedTxLinkSpeedMbps = maxTxMbps,
            maxSupportedRxLinkSpeedMbps = maxRxMbps,
            wifiStandardCode = wifiStandardCode,
            securityTypeCode = securityTypeCode,
            ipAddressV4 = ipAddressV4,
            apMldMacAddress = apMldMacAddress,
            mloLinks = mloLinks,
            timestampElapsedMs = clock.elapsedRealtimeMs(),
        )
    }

    private fun handleCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        val isWifi = safeIpcCall(default = false) {
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        if (!isWifi) return
        synchronized(stateLock) {
            trackedWifiNetwork = network
            latestCapsForTrackedNetwork = networkCapabilities
            networkExplicitlyLost = false
            suppressedLegacyInfoIdentity = 0
        }
        refreshConnectedWifi(preferTransportSnapshot = true)
    }

    private fun handleLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
        val shouldRefresh = synchronized(stateLock) {
            if (trackedWifiNetwork == null || trackedWifiNetwork == network) {
                latestLinkProperties = linkProperties
                true
            } else {
                false
            }
        }
        if (shouldRefresh) {
            refreshConnectedWifi()
        }
    }

    private fun handleNetworkLost(network: Network) {
        @Suppress("DEPRECATION")
        val currentLegacy = safeIpcCall(default = null) { wifiManagerOrNull()?.connectionInfo }
        val cleared = synchronized(stateLock) {
            if (trackedWifiNetwork == null || trackedWifiNetwork == network) {
                trackedWifiNetwork = null
                latestCapsForTrackedNetwork = null
                latestLinkProperties = null
                networkExplicitlyLost = true
                suppressedLegacyInfoIdentity = currentLegacy?.let(System::identityHashCode) ?: 0
                true
            } else {
                false
            }
        }
        if (cleared) {
            _connectedWifi.value = null
        }
    }

    private fun handleNetworkUnavailable() {
        val hasTracked = synchronized(stateLock) { trackedWifiNetwork != null }
        if (!hasTracked) {
            _connectedWifi.value = null
        }
    }

    private fun syncHighFrequencyPollerLocked() {
        synchronized(stateLock) {
            val shouldRun = isStarted.get() && highFrequencyEnabled.get()
            val scope = activeScope
            if (shouldRun && scope != null) {
                if (highFreqPollJob?.isActive != true) {
                    highFreqPollJob = scope.launch {
                        while (currentCoroutineContext().isActive &&
                            isStarted.get() &&
                            highFrequencyEnabled.get()
                        ) {
                            refreshConnectedWifi()
                            delay(HIGH_FREQ_POLL_INTERVAL_MS)
                        }
                    }
                }
            } else {
                highFreqPollJob?.cancel()
                highFreqPollJob = null
            }
        }
    }

    private fun registerListeners() {
        val cm = connectivityManagerOrNull()
        if (cm != null && networkCallbackRegistered.compareAndSet(false, true)) {
            val registered = safeIpcCall(default = false, onSecurityException = onSecurityException) {
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()

                val callback = if (sdkInt >= Build.VERSION_CODES.S) {
                    Api31PlatformCompat.createLocationAwareNetworkCallback(
                        onCapsChanged = ::handleCapabilitiesChanged,
                        onLinkPropsChanged = ::handleLinkPropertiesChanged,
                        onNetworkLost = ::handleNetworkLost,
                        onNetworkUnavailable = ::handleNetworkUnavailable,
                    )
                } else {
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities,
                        ) {
                            handleCapabilitiesChanged(network, networkCapabilities)
                        }

                        override fun onLinkPropertiesChanged(
                            network: Network,
                            linkProperties: LinkProperties,
                        ) {
                            handleLinkPropertiesChanged(network, linkProperties)
                        }

                        override fun onLost(network: Network) {
                            handleNetworkLost(network)
                        }

                        override fun onUnavailable() {
                            handleNetworkUnavailable()
                        }
                    }
                }
                activeNetworkCallback = callback
                cm.registerNetworkCallback(request, callback)
                true
            }
            if (!registered) {
                activeNetworkCallback = null
                networkCallbackRegistered.set(false)
            }
        }

        if (broadcastReceiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            }
            appContext.registerSystemBroadcastReceiverCompat(
                receiver = wifiStateAndRssiReceiver,
                filter = filter,
                sdkInt = sdkInt,
            )
        }
    }

    private fun unregisterListeners() {
        val cm = connectivityManagerOrNull()
        val cb = activeNetworkCallback
        activeNetworkCallback = null
        if (networkCallbackRegistered.compareAndSet(true, false) && cm != null && cb != null) {
            safeIpcUnit { cm.unregisterNetworkCallback(cb) }
        }
        if (broadcastReceiverRegistered.compareAndSet(true, false)) {
            safeIpcUnit { appContext.unregisterReceiver(wifiStateAndRssiReceiver) }
        }
    }

    private fun findActiveWifiCapabilities(cm: ConnectivityManager?): NetworkCapabilities? {
        if (cm == null) return null
        return safeIpcCall(default = null, onSecurityException = onSecurityException) {
            val activeNet = cm.activeNetwork
            val activeCaps = activeNet?.let { cm.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                activeCaps
            } else {
                @Suppress("DEPRECATION")
                cm.allNetworks.orEmpty().firstNotNullOfOrNull { net ->
                    cm.getNetworkCapabilities(net)
                        ?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
                }
            }
        }
    }

    private fun isGenuineLegacyConnection(info: WifiInfo): Boolean {
        val supplicant = safeIpcCall(default = null) { info.supplicantState }
        if (supplicant in DISCONNECTED_SUPPLICANT_STATES) return false

        val rawBssid = safeIpcCall(default = null) { info.bssid } ?: return false
        if (!MacAddressUtils.isValidMac(rawBssid)) return false

        val normalized = MacAddressUtils.normalizeBssid(rawBssid)
        if (normalized == ZERO_MAC) return false
        if (!MacAddressUtils.isAndroidRedactedMac(normalized)) return true

        val netId = safeIpcCall(default = -1) { info.networkId }
        val rssi = safeIpcCall(default = INVALID_RSSI) { info.rssi }
        return supplicant == SupplicantState.COMPLETED ||
            netId >= 0 ||
            rssi in MIN_VALID_RSSI_DBM..-1
    }

    private fun normalizeUnredactedMacOrNull(raw: String?): String? {
        if (raw == null) return null
        val normalized = MacAddressUtils.normalizeBssid(raw)
        if (!MacAddressUtils.isValidMac(normalized) ||
            normalized == ZERO_MAC ||
            MacAddressUtils.isAndroidRedactedMac(normalized)
        ) {
            return null
        }
        return normalized
    }

    private fun cleanWifiInfoSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrEmpty() || rawSsid == MacAddressUtils.UNKNOWN_SSID_SENTINEL) return null
        val unquoted = if (rawSsid.length >= 2 && rawSsid.startsWith('"') && rawSsid.endsWith('"')) {
            rawSsid.substring(1, rawSsid.length - 1)
        } else {
            rawSsid
        }
        return unquoted.takeIf { it.isNotEmpty() && !it.all { ch -> ch == '\u0000' } }
    }

    private fun extractIpv4FromLinkProperties(linkProperties: LinkProperties?): String? {
        if (linkProperties == null) return null
        return safeIpcCall(default = null) {
            linkProperties.linkAddresses.firstNotNullOfOrNull { linkAddr ->
                (linkAddr.address as? Inet4Address)
                    ?.takeIf { !it.isLoopbackAddress }
                    ?.hostAddress
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractDeprecatedIpv4(info: WifiInfo): String? {
        val rawIp = safeIpcCall(default = 0) { info.ipAddress }
        if (rawIp == 0) return null
        return String.format(
            Locale.US,
            "%d.%d.%d.%d",
            rawIp and 0xFF,
            (rawIp shr 8) and 0xFF,
            (rawIp shr 16) and 0xFF,
            (rawIp shr 24) and 0xFF,
        )
    }

    private fun wifiManagerOrNull(): WifiManager? =
        safeIpcCall(default = null, onSecurityException = onSecurityException) {
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }

    private fun connectivityManagerOrNull(): ConnectivityManager? =
        safeIpcCall(default = null, onSecurityException = onSecurityException) {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }

    companion object {
        const val HIGH_FREQ_POLL_INTERVAL_MS = 300L
        private const val ZERO_MAC = "00:00:00:00:00:00"
        private const val INVALID_RSSI = -127
        private const val MIN_VALID_RSSI_DBM = -126
        private const val MAX_VALID_RSSI_DBM = 0

        private val DISCONNECTED_SUPPLICANT_STATES = setOf(
            SupplicantState.DISCONNECTED,
            SupplicantState.INTERFACE_DISABLED,
            SupplicantState.INACTIVE,
            SupplicantState.SCANNING,
            SupplicantState.INVALID,
        )
    }
}
