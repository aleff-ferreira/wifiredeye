package com.a55.wifiradar.core.model

import java.util.Locale

object MacAddressUtils {
    const val ANDROID_REDACTED_MAC: String = "02:00:00:00:00:00"
    const val UNKNOWN_SSID_SENTINEL: String = "<unknown ssid>"

    private val MAC_REGEX = Regex("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")

    /**
     * Normalizes any valid 6-octet MAC/BSSID into canonical uppercase colon-separated form
     * ("AA:BB:CC:DD:EE:FF"). Trims whitespace and converts '-' separators to ':'.
     */
    fun normalizeBssid(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.replace('-', ':').uppercase(Locale.US)
    }

    fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim())

    fun isAndroidRedactedMac(mac: String): Boolean =
        normalizeBssid(mac) == ANDROID_REDACTED_MAC

    /**
     * Extracts the 3-octet uppercase OUI prefix ("AA:BB:CC") or null if malformed.
     */
    fun extractOuiPrefix(mac: String): String? {
        val norm = normalizeBssid(mac)
        if (!isValidMac(norm)) return null
        return norm.substring(0, 8)
    }

    /**
     * Extracts the first 5 octets ("AA:BB:CC:DD:EE") with the U/L bit (bit 1 of octet 0)
     * cleared to 0, enabling physical AP radio clustering across virtual/guest VAPs.
     */
    fun extractUniversalFiveOctetPrefix(mac: String): String? {
        val norm = normalizeBssid(mac)
        if (!isValidMac(norm)) return null
        val firstByte = norm.substring(0, 2).toInt(16) and 0xFD
        val firstHex = "%02X".format(Locale.US, firstByte)
        return "$firstHex:${norm.substring(3, 14)}"
    }

    /**
     * IEEE 802 locally administered address check: bit 1 (0x02) of first octet is 1.
     */
    fun isLocallyAdministered(mac: String): Boolean {
        val norm = normalizeBssid(mac)
        if (!isValidMac(norm)) return false
        val firstByte = norm.substring(0, 2).toInt(16)
        return (firstByte and 0x02) != 0
    }

    /**
     * Clears the U/L bit (0x02) on the first octet to infer parent hardware OUI ("AA:BB:CC").
     */
    fun clearLocallyAdministeredBitOui(mac: String): String? {
        val norm = normalizeBssid(mac)
        if (!isValidMac(norm)) return null
        val firstByte = norm.substring(0, 2).toInt(16) and 0xFD
        return "%02X:%s".format(Locale.US, firstByte, norm.substring(3, 8))
    }

    /**
     * Privacy-safe MAC redaction for JSON/Markdown export (`F17`):
     * Preserves 3-octet OUI vendor prefix and 6th radio-index octet while masking
     * subscriber-identifying octets 4 and 5 ("AA:BB:CC:XX:XX:01").
     * Guarantees raw MAC never appears in redacted output while keeping multi-radio BSSIDs distinct.
     */
    fun redactMac(mac: String): String {
        val norm = normalizeBssid(mac)
        if (!isValidMac(norm)) return "XX:XX:XX:XX:XX:XX"
        val oui = norm.substring(0, 8)
        val tail = norm.substring(15, 17)
        return "$oui:XX:XX:$tail"
    }
}
