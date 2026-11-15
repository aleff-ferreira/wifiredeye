package com.a55.wifiradar.e2e.harness

import android.net.MacAddress
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiSsid
import android.net.wifi.rtt.RangingResult
import android.os.SystemClock
import java.nio.charset.StandardCharsets
import org.robolectric.Shadows
import org.robolectric.shadows.RangingResultBuilder
import org.robolectric.shadows.ShadowWifiInfo
import org.robolectric.shadows.WifiScanResultBuilder

/**
 * Opaque-box Android OS telemetry factory constructing authentic SDK 34 [ScanResult],
 * [ScanResult.InformationElement], [WifiInfo], and [RangingResult] objects.
 */
object ScanResultFactory {

    data class IeSpec(
        val id: Int,
        val idExt: Int = 0,
        val bytes: ByteArray,
    ) {
        companion object {
            fun fromHex(id: Int, idExt: Int = 0, hex: String): IeSpec {
                val clean = hex.replace(" ", "").trim()
                val raw = ByteArray(clean.length / 2) { idx ->
                    clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
                }
                return IeSpec(id = id, idExt = idExt, bytes = raw)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun createScanResult(
        ssid: String? = "HomeNet_5G",
        rawSsidBytes: ByteArray? = null,
        bssid: String = "AC:84:C6:11:22:33",
        rssiDbm: Int = -54,
        frequencyMhz: Int = 5180,
        channelWidth: Int = ScanResult.CHANNEL_WIDTH_80MHZ,
        centerFreq0Mhz: Int = 5210,
        centerFreq1Mhz: Int = 0,
        capabilities: String = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
        wifiStandard: Int = ScanResult.WIFI_STANDARD_11AX,
        ageMs: Long = 250L,
        explicitTimestampMicros: Long? = null,
        is80211mcResponder: Boolean = false,
        is80211azResponder: Boolean = false,
        apMldMac: String? = null,
        apMloLinkId: Int = -1,
        ies: List<IeSpec> = emptyList(),
    ): ScanResult {
        val nowMicros = SystemClock.elapsedRealtime() * 1_000L
        val tsMicros = explicitTimestampMicros ?: (nowMicros - ageMs * 1_000L).coerceAtLeast(0L)

        val encodedBytes: ByteArray = when {
            rawSsidBytes != null -> rawSsidBytes
            ssid == null -> ByteArray(0)
            else -> ssid.toByteArray(StandardCharsets.UTF_8)
        }

        val androidIes = ies.map { spec ->
            ScanResult.InformationElement(spec.id, spec.idExt, spec.bytes)
        }

        val builder = WifiScanResultBuilder()
            .setBssid(bssid)
            .setRssi(rssiDbm)
            .setFrequency(frequencyMhz)
            .setChannelWidth(channelWidth)
            .setCenterFreq0(centerFreq0Mhz)
            .setCenterFreq1(centerFreq1Mhz)
            .setCapabilities(capabilities)
            .setWifiSsid(WifiSsid.fromBytes(encodedBytes))
            .setIs80211McRttResponder(is80211mcResponder)
            .setInformationElements(androidIes)
        // 802.11az setter needs the API-36 ScanResult.Builder (absent on sdk 34); only touch it when requested.
        if (is80211azResponder) runCatching { builder.setIs80211azNtbRttResponder(true) }
        val sr = builder.build()

        sr.timestamp = tsMicros
        sr.SSID = ssid ?: ""

        runCatching {
            val setStd = ScanResult::class.java.getDeclaredMethod("setWifiStandard", Int::class.javaPrimitiveType)
            setStd.isAccessible = true
            setStd.invoke(sr, wifiStandard)
        }.onFailure {
            runCatching {
                val stdField = ScanResult::class.java.getDeclaredField("mWifiStandard")
                stdField.isAccessible = true
                stdField.setInt(sr, wifiStandard)
            }
        }

        if (apMldMac != null) {
            val macObj = MacAddress.fromString(apMldMac)
            runCatching {
                val m = ScanResult::class.java.getDeclaredMethod("setApMldMacAddress", MacAddress::class.java)
                m.isAccessible = true
                m.invoke(sr, macObj)
            }.onFailure {
                runCatching {
                    val f = ScanResult::class.java.getDeclaredField("mApMldMacAddress")
                    f.isAccessible = true
                    f.set(sr, macObj)
                }
            }
        }

        if (apMloLinkId >= 0) {
            runCatching {
                val m = ScanResult::class.java.getDeclaredMethod("setApMloLinkId", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(sr, apMloLinkId)
            }.onFailure {
                runCatching {
                    val f = ScanResult::class.java.getDeclaredField("mApMloLinkId")
                    f.isAccessible = true
                    f.setInt(sr, apMloLinkId)
                }
            }
        }

        return sr
    }

    @Suppress("DEPRECATION")
    fun createConnectedWifiInfo(
        ssid: String = "HomeNet_5G",
        bssid: String = "AC:84:C6:11:22:33",
        rssiDbm: Int = -51,
        frequencyMhz: Int = 5180,
        linkSpeedMbps: Int = 1201,
        txLinkSpeedMbps: Int = linkSpeedMbps,
        rxLinkSpeedMbps: Int = linkSpeedMbps,
        networkId: Int = 1,
        redactedLocation: Boolean = false,
    ): WifiInfo {
        val info = ShadowWifiInfo.newInstance()
        val shadow = Shadows.shadowOf(info)
        if (redactedLocation) {
            shadow.setSSID("<unknown ssid>")
            shadow.setBSSID("02:00:00:00:00:00")
            shadow.setNetworkId(-1)
        } else {
            val quoted = if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid else "\"$ssid\""
            shadow.setSSID(quoted)
            shadow.setBSSID(bssid)
            shadow.setNetworkId(networkId)
        }
        shadow.setRssi(rssiDbm)
        shadow.setFrequency(frequencyMhz)
        shadow.setLinkSpeed(linkSpeedMbps)
        runCatching {
            val setTx = WifiInfo::class.java.getDeclaredMethod("setTxLinkSpeedMbps", Int::class.javaPrimitiveType)
            setTx.isAccessible = true
            setTx.invoke(info, txLinkSpeedMbps)
        }
        runCatching {
            val setRx = WifiInfo::class.java.getDeclaredMethod("setRxLinkSpeedMbps", Int::class.javaPrimitiveType)
            setRx.isAccessible = true
            setRx.invoke(info, rxLinkSpeedMbps)
        }
        return info
    }

    fun createRttResult(
        bssid: String,
        distanceMm: Int,
        distanceStdDevMm: Int = 350,
        rssiDbm: Int = -55,
        status: Int = RangingResult.STATUS_SUCCESS,
        timestampMs: Long = SystemClock.elapsedRealtime(),
    ): RangingResult {
        return RangingResultBuilder(
            status,
            MacAddress.fromString(bssid),
            timestampMs,
            distanceMm,
        )
            .setDistanceStandardDeviation(distanceStdDevMm)
            .setRssi(rssiDbm)
            .setNumAttemptedMeasurements(1)
            .setNumSuccessfulMeasurements(if (status == RangingResult.STATUS_SUCCESS) 1 else 0)
            .setIs80211mcMeasurement(true)
            .build()
    }
}
