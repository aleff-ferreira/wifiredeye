package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.MacAddressUtils
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Strict UTF-8 / Hex / Null-byte hidden SSID decoder with connected `WifiInfo` reveal (`F06`).
 */
object SsidDecoder {

    fun decode(
        rawSsidBytes: ByteArray?,
        legacySsid: String?,
        normalizedBssid: String = "",
        connectedWifi: ConnectedWifiObservation? = null,
    ): DecodedSsid {
        val base = if (rawSsidBytes != null) {
            decodeFromRawBytes(rawSsidBytes)
        } else {
            decodeFromLegacySsid(legacySsid)
        }

        if (base.isHidden &&
            connectedWifi != null &&
            !connectedWifi.isLocationRedacted &&
            normalizedBssid.isNotEmpty() &&
            MacAddressUtils.normalizeBssid(connectedWifi.bssid) == normalizedBssid
        ) {
            val revealed = extractConnectedSsid(connectedWifi.ssid)
            if (revealed != null) {
                return base.copy(
                    displayName = revealed,
                    rawUtf8OrNull = revealed,
                    isRevealedFromConnection = true,
                )
            }
        }

        return base
    }

    private fun decodeFromRawBytes(bytes: ByteArray): DecodedSsid {
        if (bytes.isEmpty()) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = "",
                isHidden = true,
                isNullBytePadded = false,
                isUtf8 = true,
                byteLength = 0,
            )
        }

        val hexLower = bytes.toHexStringLower()
        if (bytes.all { it == 0.toByte() }) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = hexLower,
                isHidden = true,
                isNullBytePadded = true,
                isUtf8 = true,
                byteLength = bytes.size,
            )
        }

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (decoded.all { it == '\u0000' || it.isWhitespace() }) {
                DecodedSsid(
                    displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                    rawUtf8OrNull = decoded,
                    rawHex = hexLower,
                    isHidden = true,
                    isNullBytePadded = false,
                    isUtf8 = true,
                    byteLength = bytes.size,
                )
            } else {
                val cleaned = decoded.replace("\u0000", "")
                DecodedSsid(
                    displayName = cleaned,
                    rawUtf8OrNull = cleaned,
                    rawHex = hexLower,
                    isHidden = false,
                    isNullBytePadded = false,
                    isUtf8 = true,
                    byteLength = bytes.size,
                )
            }
        } catch (_: CharacterCodingException) {
            val hexUpper = bytes.toHexStringUpper()
            DecodedSsid(
                displayName = "0x$hexUpper",
                rawUtf8OrNull = null,
                rawHex = hexLower,
                isHidden = false,
                isNullBytePadded = false,
                isUtf8 = false,
                byteLength = bytes.size,
            )
        }
    }

    private fun decodeFromLegacySsid(legacySsid: String?): DecodedSsid {
        if (legacySsid == null) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = "",
                isHidden = true,
                isNullBytePadded = false,
                isUtf8 = true,
                byteLength = 0,
            )
        }

        val trimmedSentinel = legacySsid.trim()
        if (trimmedSentinel.equals(MacAddressUtils.UNKNOWN_SSID_SENTINEL, ignoreCase = true)) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = "",
                isHidden = true,
                isNullBytePadded = false,
                isUtf8 = true,
                byteLength = 0,
            )
        }

        val unquoted = unquoteSinglePair(legacySsid)
        if (unquoted.isEmpty()) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = "",
                isHidden = true,
                isNullBytePadded = false,
                isUtf8 = true,
                byteLength = 0,
            )
        }

        val utf8Bytes = unquoted.encodeToByteArray()
        val hexLower = utf8Bytes.toHexStringLower()

        if (unquoted.all { it == '\u0000' }) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = null,
                rawHex = hexLower,
                isHidden = true,
                isNullBytePadded = true,
                isUtf8 = true,
                byteLength = unquoted.length,
            )
        }

        if (unquoted.all { it == '\u0000' || it.isWhitespace() }) {
            return DecodedSsid(
                displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
                rawUtf8OrNull = unquoted,
                rawHex = hexLower,
                isHidden = true,
                isNullBytePadded = false,
                isUtf8 = true,
                byteLength = utf8Bytes.size,
            )
        }

        val cleaned = unquoted.replace("\u0000", "")
        return DecodedSsid(
            displayName = cleaned,
            rawUtf8OrNull = cleaned,
            rawHex = hexLower,
            isHidden = false,
            isNullBytePadded = false,
            isUtf8 = true,
            byteLength = utf8Bytes.size,
        )
    }

    internal fun extractConnectedSsid(rawConnectedSsid: String?): String? {
        if (rawConnectedSsid == null) return null
        val unquoted = unquoteSinglePair(rawConnectedSsid)
        if (unquoted.isBlank()) return null
        if (unquoted.trim().equals(MacAddressUtils.UNKNOWN_SSID_SENTINEL, ignoreCase = true)) return null
        if (unquoted.all { it == '\u0000' || it.isWhitespace() }) return null
        return unquoted.replace("\u0000", "")
    }

    private fun unquoteSinglePair(s: String): String =
        if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) {
            s.substring(1, s.length - 1)
        } else {
            s
        }

    private fun ByteArray.toHexStringLower(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun ByteArray.toHexStringUpper(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
