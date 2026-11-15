package com.a55.wifiradar.core.wifiplatform

import android.net.wifi.ScanResult
import android.os.Build
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RawScanEntry

/**
 * Defensive API 30–35 [ScanResult] -> [RawScanEntry] extractor (`F20`).
 *
 * - Copies [ScanResult.InformationElement.getBytes] via `asReadOnlyBuffer()` without `.array()`.
 * - Guards against `new ScanResult()` null backing arrays (`NullPointerException` in SDK 34).
 * - Populates both [RawScanEntry.rawSsidBytes] (API 33+) and [RawScanEntry.legacySsid].
 * - Clamps extreme RSSI values to `[-127, 0]` and rejects malformed/zero/redacted BSSIDs.
 */
internal object ScanResultExtractor {

    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val MIN_CLAMPED_RSSI_DBM = -127
    private const val MAX_CLAMPED_RSSI_DBM = 0

    fun extract(
        scanResult: ScanResult?,
        fallbackTimestampMicros: Long,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): RawScanEntry? {
        if (scanResult == null) return null

        val rawBssid = safeIpcCall(default = null) { scanResult.BSSID } ?: return null
        val bssid = MacAddressUtils.normalizeBssid(rawBssid)
        if (!MacAddressUtils.isValidMac(bssid) ||
            bssid == ZERO_MAC ||
            MacAddressUtils.isAndroidRedactedMac(bssid)
        ) {
            return null
        }

        val rssiDbm = safeIpcCall(default = -100) { scanResult.level }
            .coerceIn(MIN_CLAMPED_RSSI_DBM, MAX_CLAMPED_RSSI_DBM)

        val rawSsidBytes = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getWifiSsidBytesOrNull(scanResult)
        } else {
            null
        }

        @Suppress("DEPRECATION")
        val legacySsid = safeIpcCall(default = null) { scanResult.SSID }
            ?.takeIf { it != MacAddressUtils.UNKNOWN_SSID_SENTINEL }

        val frequencyMhz = safeIpcCall(default = 0) { scanResult.frequency }.coerceAtLeast(0)
        val centerFreq0Mhz = safeIpcCall(default = 0) { scanResult.centerFreq0 }.coerceAtLeast(0)
        val centerFreq1Mhz = safeIpcCall(default = 0) { scanResult.centerFreq1 }.coerceAtLeast(0)
        val channelWidthCode = safeIpcCall(default = 0) { scanResult.channelWidth }
        val wifiStandardCode = safeIpcCall(default = 0) { scanResult.wifiStandard }
        val capabilities = safeIpcCall(default = "") { scanResult.capabilities.orEmpty() }

        val securityTypes = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getSecurityTypesList(scanResult)
        } else {
            emptyList()
        }

        val rawTimestamp = safeIpcCall(default = 0L) { scanResult.timestamp }
        val timestampMicros = if (rawTimestamp > 0L) {
            rawTimestamp
        } else {
            fallbackTimestampMicros.coerceAtLeast(0L)
        }

        val is80211mcResponder = safeIpcCall(default = false) { scanResult.is80211mcResponder }
        val is80211azResponder = if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Api35PlatformCompat.is80211azNtbResponder(scanResult)
        } else {
            false
        }

        val apMldMacAddress = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getApMldMacAddressOrNull(scanResult)
        } else {
            null
        }

        val apMloLinkId = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getApMloLinkId(scanResult)
        } else {
            -1
        }

        val affiliatedMloLinks = if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Api33PlatformCompat.getAffiliatedMloLinks(scanResult, sdkInt)
        } else {
            emptyList()
        }

        @Suppress("DEPRECATION")
        val venueName = safeIpcCall(default = null) {
            scanResult.venueName?.toString()?.takeIf { it.isNotBlank() }
        }

        @Suppress("DEPRECATION")
        val operatorFriendlyName = safeIpcCall(default = null) {
            scanResult.operatorFriendlyName?.toString()?.takeIf { it.isNotBlank() }
        }

        val isPasspointNetwork = safeIpcCall(default = false) { scanResult.isPasspointNetwork }

        val informationElements = safeIpcCall(default = emptyList<ScanResult.InformationElement?>()) {
            scanResult.informationElements
        }.mapNotNull(::extractInformationElement)

        return RawScanEntry(
            bssid = bssid,
            rawSsidBytes = rawSsidBytes,
            legacySsid = legacySsid,
            rssiDbm = rssiDbm,
            frequencyMhz = frequencyMhz,
            centerFreq0Mhz = centerFreq0Mhz,
            centerFreq1Mhz = centerFreq1Mhz,
            channelWidthCode = channelWidthCode,
            wifiStandardCode = wifiStandardCode,
            capabilities = capabilities,
            securityTypes = securityTypes,
            timestampMicros = timestampMicros,
            is80211mcResponder = is80211mcResponder,
            is80211azResponder = is80211azResponder,
            apMldMacAddress = apMldMacAddress,
            informationElements = informationElements,
            apMloLinkId = apMloLinkId,
            affiliatedMloLinks = affiliatedMloLinks,
            venueName = venueName,
            operatorFriendlyName = operatorFriendlyName,
            isPasspointNetwork = isPasspointNetwork,
        )
    }

    internal fun extractInformationElement(
        ie: ScanResult.InformationElement?,
    ): RawInformationElement? {
        if (ie == null) return null
        return safeIpcCall(default = null) {
            val readOnlyBuffer = ie.bytes?.asReadOnlyBuffer() ?: return@safeIpcCall null
            val payload = ByteArray(readOnlyBuffer.remaining())
            readOnlyBuffer.get(payload)
            RawInformationElement(
                id = ie.id,
                idExt = ie.idExt,
                bytes = payload,
            )
        }
    }

    /**
     * Order-independent structural signature over a raw [ScanResult] sweep so simultaneous
     * `ScanResultsCallback` + `SCAN_RESULTS_AVAILABLE_ACTION` deliveries for the identical sweep
     * are deduplicated while any RSSI/SSID/channel/AP mutation produces a new signature.
     */
    fun computeRawSweepSignature(
        results: List<ScanResult?>,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Int {
        val perEntryHashes = ArrayList<Pair<String, Int>>(results.size)
        for (sr in results) {
            if (sr == null) continue
            val rawBssid = safeIpcCall(default = null) { sr.BSSID } ?: continue
            val bssid = MacAddressUtils.normalizeBssid(rawBssid)
            if (bssid.isEmpty()) continue
            var h = bssid.hashCode()
            h = 31 * h + safeIpcCall(default = 0) { sr.level }
            h = 31 * h + safeIpcCall(default = 0) { sr.frequency }
            h = 31 * h + safeIpcCall(default = 0) { sr.channelWidth }
            h = 31 * h + safeIpcCall(default = 0) { sr.centerFreq0 }
            h = 31 * h + safeIpcCall(default = 0) { sr.centerFreq1 }
            h = 31 * h + safeIpcCall(default = 0L) { sr.timestamp }.hashCode()
            @Suppress("DEPRECATION")
            h = 31 * h + (safeIpcCall(default = null) { sr.SSID }?.hashCode() ?: 0)
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                h = 31 * h + (Api33PlatformCompat.getWifiSsidBytesOrNull(sr)?.contentHashCode() ?: 0)
            }
            h = 31 * h + safeIpcCall(default = "") { sr.capabilities.orEmpty() }.hashCode()
            perEntryHashes.add(bssid to h)
        }
        perEntryHashes.sortBy { it.first }
        var total = perEntryHashes.size
        for ((_, entryHash) in perEntryHashes) {
            total = 31 * total + entryHash
        }
        return total
    }
}
