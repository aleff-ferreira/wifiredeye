package com.a55.wifiradar.core.wifiplatform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.MloLink
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingResult
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.MloLinkInfo
import com.a55.wifiradar.core.model.WifiBand

@RequiresApi(Build.VERSION_CODES.S)
internal object Api31PlatformCompat {
    @DoNotInline
    fun is24GhzBandSupported(wifiManager: WifiManager): Boolean =
        wifiManager.is24GHzBandSupported

    @DoNotInline
    fun is60GhzBandSupported(wifiManager: WifiManager): Boolean =
        wifiManager.is60GHzBandSupported

    @DoNotInline
    fun isMakeBeforeBreakWifiSwitchingSupported(wifiManager: WifiManager): Boolean =
        wifiManager.isMakeBeforeBreakWifiSwitchingSupported

    @DoNotInline
    fun isStaConcurrencyForLocalOnlyConnectionsSupported(wifiManager: WifiManager): Boolean =
        wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported

    @DoNotInline
    fun createLocationAwareNetworkCallback(
        onCapsChanged: (Network, NetworkCapabilities) -> Unit,
        onLinkPropsChanged: (Network, LinkProperties) -> Unit,
        onNetworkLost: (Network) -> Unit,
        onNetworkUnavailable: () -> Unit,
    ): ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                onCapsChanged(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                onLinkPropsChanged(network, linkProperties)
            }

            override fun onLost(network: Network) {
                onNetworkLost(network)
            }

            override fun onUnavailable() {
                onNetworkUnavailable()
            }
        }

    @DoNotInline
    fun getCurrentSecurityTypeOrNull(wifiInfo: WifiInfo): Int? =
        safeIpcCall(default = null) { wifiInfo.currentSecurityType }
            ?.takeIf { it != WifiInfo.SECURITY_TYPE_UNKNOWN && it >= 0 }

    @DoNotInline
    fun getDefaultVibratorOrNull(context: Context): Vibrator? =
        safeIpcCall(default = null) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object Api33PlatformCompat {
    @DoNotInline
    fun registerNotExportedReceiver(
        context: Context,
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ): Intent? = context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)

    @DoNotInline
    fun isStaConcurrencyForMultiInternetSupported(wifiManager: WifiManager): Boolean =
        wifiManager.isStaConcurrencyForMultiInternetSupported

    @DoNotInline
    fun getWifiSsidBytesOrNull(scanResult: ScanResult): ByteArray? =
        safeIpcCall(default = null) { scanResult.wifiSsid?.bytes?.clone() }

    @DoNotInline
    fun getSecurityTypesList(scanResult: ScanResult): List<Int> =
        safeIpcCall(default = emptyList()) { scanResult.securityTypes?.toList().orEmpty() }

    @DoNotInline
    fun getApMldMacAddressOrNull(scanResult: ScanResult): String? =
        safeIpcCall(default = null) { scanResult.apMldMacAddress?.toString() }
            ?.let(MacAddressUtils::normalizeBssid)
            ?.takeIf {
                MacAddressUtils.isValidMac(it) &&
                    it != ZERO_MAC &&
                    !MacAddressUtils.isAndroidRedactedMac(it)
            }

    @DoNotInline
    fun getApMldMacAddressOrNull(wifiInfo: WifiInfo): String? =
        safeIpcCall(default = null) { wifiInfo.apMldMacAddress?.toString() }
            ?.let(MacAddressUtils::normalizeBssid)
            ?.takeIf {
                MacAddressUtils.isValidMac(it) &&
                    it != ZERO_MAC &&
                    !MacAddressUtils.isAndroidRedactedMac(it)
            }

    @DoNotInline
    fun getApMloLinkId(scanResult: ScanResult): Int =
        safeIpcCall(default = -1) { scanResult.apMloLinkId }

    @DoNotInline
    fun getAffiliatedMloLinks(scanResult: ScanResult, sdkInt: Int): List<MloLinkInfo> =
        safeIpcCall(default = emptyList()) { scanResult.affiliatedMloLinks.orEmpty() }
            .mapNotNull { link -> mapMloLink(link, sdkInt) }

    @DoNotInline
    fun getWifiInfoMloLinks(wifiInfo: WifiInfo, sdkInt: Int): List<MloLinkInfo> {
        val rawLinks: List<MloLink> = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34PlatformCompat.getPreferredMloLinks(wifiInfo)
        } else {
            safeIpcCall(default = emptyList()) { wifiInfo.affiliatedMloLinks.orEmpty() }
        }
        return rawLinks.mapNotNull { link -> mapMloLink(link, sdkInt) }
    }

    @DoNotInline
    fun vibrateHardwareFeedback(vibrator: Vibrator, effect: VibrationEffect) {
        val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
        vibrator.vibrate(effect, attrs)
    }

    private fun mapMloLink(link: MloLink?, sdkInt: Int): MloLinkInfo? {
        if (link == null) return null
        val linkId = safeIpcCall(default = MloLink.INVALID_MLO_LINK_ID) { link.linkId }
        val rawBand = safeIpcCall(default = 0) { link.band }
        val band = when (rawBand) {
            ScanResult.WIFI_BAND_24_GHZ -> WifiBand.BAND_2_4_GHZ
            ScanResult.WIFI_BAND_5_GHZ -> WifiBand.BAND_5_GHZ
            ScanResult.WIFI_BAND_6_GHZ -> WifiBand.BAND_6_GHZ
            ScanResult.WIFI_BAND_60_GHZ -> WifiBand.BAND_60_GHZ
            else -> WifiBand.UNKNOWN
        }
        val channel = safeIpcCall(default = -1) { link.channel }.let { if (it > 0) it else -1 }
        val freqMhz = channelToFrequencyMhz(band, channel)
        val bssid = safeIpcCall(default = null) { link.apMacAddress?.toString() }
            ?.let(MacAddressUtils::normalizeBssid)
            ?.takeIf {
                MacAddressUtils.isValidMac(it) &&
                    it != ZERO_MAC &&
                    !MacAddressUtils.isAndroidRedactedMac(it)
            }
        val state = safeIpcCall(default = MloLink.MLO_LINK_STATE_ACTIVE) { link.state }
        val (rssi, txMbps, rxMbps) = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34PlatformCompat.getMloLinkTelemetry(link)
        } else {
            Triple(null, null, null)
        }
        return MloLinkInfo(
            linkId = linkId,
            bssid = bssid,
            band = band,
            channel = channel,
            frequencyMhz = freqMhz,
            rssiDbm = rssi,
            txLinkSpeedMbps = txMbps,
            rxLinkSpeedMbps = rxMbps,
            isActive = state == MloLink.MLO_LINK_STATE_ACTIVE,
        )
    }

    private fun channelToFrequencyMhz(band: WifiBand, channel: Int): Int {
        if (channel <= 0) return 0
        return when (band) {
            WifiBand.BAND_2_4_GHZ -> when {
                channel == 14 -> 2484
                channel in 1..13 -> 2407 + channel * 5
                else -> 0
            }
            WifiBand.BAND_5_GHZ -> if (channel in 32..177) 5000 + channel * 5 else 0
            WifiBand.BAND_6_GHZ -> when {
                channel == 2 -> 5935
                channel in 1..233 -> 5950 + channel * 5
                else -> 0
            }
            WifiBand.BAND_60_GHZ -> if (channel in 1..6) 56160 + channel * 2160 else 0
            WifiBand.UNKNOWN -> 0
        }
    }

    private const val ZERO_MAC = "00:00:00:00:00:00"
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object Api34PlatformCompat {
    @DoNotInline
    fun isDualBandSimultaneousSupported(wifiManager: WifiManager): Boolean =
        wifiManager.isDualBandSimultaneousSupported

    @DoNotInline
    fun getPreferredMloLinks(wifiInfo: WifiInfo): List<MloLink> {
        val associated = safeIpcCall(default = emptyList()) { wifiInfo.associatedMloLinks.orEmpty() }
        return associated.ifEmpty {
            safeIpcCall(default = emptyList()) { wifiInfo.affiliatedMloLinks.orEmpty() }
        }
    }

    @DoNotInline
    fun getMloLinkTelemetry(link: MloLink): Triple<Int?, Int?, Int?> {
        val rssi = safeIpcCall(default = null) { link.rssi }?.takeIf { it in -126..0 }
        val tx = safeIpcCall(default = null) { link.txLinkSpeedMbps }?.takeIf { it > 0 }
        val rx = safeIpcCall(default = null) { link.rxLinkSpeedMbps }?.takeIf { it > 0 }
        return Triple(rssi, tx, rx)
    }
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
internal object Api35PlatformCompat {
    @DoNotInline
    fun is80211azNtbResponder(scanResult: ScanResult): Boolean =
        safeIpcCall(default = false) { scanResult.is80211azNtbResponder }

    @DoNotInline
    fun is80211azNtbMeasurement(result: RangingResult): Boolean =
        safeIpcCall(default = false) { result.is80211azNtbMeasurement }
}
