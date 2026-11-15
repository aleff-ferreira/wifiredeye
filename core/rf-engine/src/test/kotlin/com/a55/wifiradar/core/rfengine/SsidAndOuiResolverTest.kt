package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsidAndOuiResolverTest {

    @Test
    fun ssidDecoder_handlesUtf8QuotesMalformedBytesNullPaddingAndConnectedReveal() {
        // Valid UTF-8 with accents and emoji
        val cafeBytes = "Café_🚀_5G".encodeToByteArray()
        val cafe = SsidDecoder.decode(cafeBytes, legacySsid = null)
        assertEquals("Café_🚀_5G", cafe.displayName)
        assertEquals("Café_🚀_5G", cafe.rawUtf8OrNull)
        assertTrue(cafe.isUtf8)
        assertFalse(cafe.isHidden)
        assertEquals(cafeBytes.size, cafe.byteLength)

        // Literal double-quotes inside raw WifiSsid bytes are preserved verbatim (no buggy quote stripping!)
        val quotedBytes = "\"LiteralQuotes\"".encodeToByteArray()
        val quoted = SsidDecoder.decode(quotedBytes, legacySsid = null)
        assertEquals("\"LiteralQuotes\"", quoted.displayName)
        assertFalse(quoted.isHidden)

        // Invalid UTF-8 byte sequence -> safe 0xHEX displayName with isUtf8 = false
        val badBytes = byteArrayOf(0x80.toByte(), 0xFE.toByte(), 0x1A.toByte())
        val nonUtf8 = SsidDecoder.decode(badBytes, legacySsid = null)
        assertFalse(nonUtf8.isUtf8)
        assertFalse(nonUtf8.isHidden)
        assertNull(nonUtf8.rawUtf8OrNull)
        assertEquals("0x80FE1A", nonUtf8.displayName)
        assertEquals("80fe1a", nonUtf8.rawHex)
        assertEquals(3, nonUtf8.byteLength)

        // Empty 0-byte SSID -> Hidden Network
        val emptyHidden = SsidDecoder.decode(ByteArray(0), legacySsid = "")
        assertTrue(emptyHidden.isHidden)
        assertFalse(emptyHidden.isNullBytePadded)
        assertEquals(DecodedSsid.HIDDEN_NETWORK_LABEL, emptyHidden.displayName)
        assertEquals(0, emptyHidden.byteLength)

        // Cisco/Broadcom 8-null-byte padded hidden SSID -> Hidden Network with byteLength = 8
        val nullPadded = SsidDecoder.decode(ByteArray(8) { 0 }, legacySsid = null)
        assertTrue(nullPadded.isHidden)
        assertTrue(nullPadded.isNullBytePadded)
        assertEquals(8, nullPadded.byteLength)
        assertEquals(DecodedSsid.HIDDEN_NETWORK_LABEL, nullPadded.displayName)

        // Whitespace-only & <unknown ssid> sentinel -> Hidden Network
        assertTrue(SsidDecoder.decode(null, "   ").isHidden)
        assertTrue(SsidDecoder.decode(null, "<unknown ssid>").isHidden)

        // Connected WifiInfo BSSID match reveals hidden SSID
        val revealed = SsidDecoder.decode(
            rawSsidBytes = ByteArray(6) { 0 },
            legacySsid = null,
            normalizedBssid = "04:92:26:11:22:33",
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:11:22:33",
                ssid = "\"SecretLab_5G\"",
                isLocationRedacted = false,
                rssiDbm = -48,
                frequencyMhz = 5180,
            ),
        )
        assertTrue(revealed.isHidden)
        assertTrue(revealed.isRevealedFromConnection)
        assertEquals("SecretLab_5G", revealed.displayName)
        assertEquals("SecretLab_5G", revealed.rawUtf8OrNull)
    }

    @Test
    fun ouiDictionary_enforcesUniversalBitInvariantAndZeroDuplicateCollisions() {
        val seenKeys = HashMap<Int, String>()
        var totalEntries = 0

        for (row in OuiVendorResolver.VENDOR_OUI_PACKS) {
            val sep = row.indexOf('|')
            assertTrue("Missing '|' separator in row: $row", sep > 0)
            val vendor = row.substring(0, sep)
            val hex = row.substring(sep + 1)
            assertEquals("Row hex must be a multiple of 6 chars for $vendor", 0, hex.length % 6)

            var idx = 0
            while (idx < hex.length) {
                val chunk = hex.substring(idx, idx + 6)
                val b0 = chunk.substring(0, 2).toInt(16)
                assertEquals(
                    "OUI $chunk for $vendor must be universally administered & unicast ((b0 & 0x03) == 0)",
                    0,
                    b0 and 0x03,
                )
                val key24 = chunk.toInt(16)
                val prevVendor = seenKeys.put(key24, vendor)
                assertNull(
                    "Duplicate OUI $chunk claimed by both '$prevVendor' and '$vendor'",
                    prevVendor,
                )
                totalEntries++
                idx += 6
            }
        }
        assertTrue("Expected rich offline OUI table (>= 600 prefixes), got $totalEntries", totalEntries >= 600)
    }

    @Test
    fun ouiVendorResolver_identifiesMajorVendorsLaaParentInferenceAndRedactedMac() {
        assertEquals("ASUS", OuiVendorResolver.resolve("04:92:26:AA:BB:CC").displayLabel)
        assertEquals("TP-Link", OuiVendorResolver.resolve("50:C7:BF:11:22:33").displayLabel)
        assertEquals("Ubiquiti", OuiVendorResolver.resolve("04:18:D6:11:22:33").displayLabel)
        assertEquals("Netgear", OuiVendorResolver.resolve("20:4E:7F:11:22:33").displayLabel)
        assertEquals("Cisco", OuiVendorResolver.resolve("00:40:96:11:22:33").displayLabel)
        assertEquals("Cisco Meraki", OuiVendorResolver.resolve("00:18:0A:11:22:33").displayLabel)
        assertEquals("Aruba (HPE)", OuiVendorResolver.resolve("00:0B:86:11:22:33").displayLabel)
        assertEquals("Ruckus Wireless", OuiVendorResolver.resolve("00:13:92:11:22:33").displayLabel)
        assertEquals("Google / Nest", OuiVendorResolver.resolve("F4:F5:D8:11:22:33").displayLabel)
        assertEquals("Fritz!Box (AVM)", OuiVendorResolver.resolve("24:65:11:11:22:33").displayLabel)
        assertEquals("Samsung", OuiVendorResolver.resolve("08:D4:2B:11:22:33").displayLabel)
        assertEquals("Apple", OuiVendorResolver.resolve("F0:18:98:11:22:33").displayLabel)
        assertEquals("Espressif", OuiVendorResolver.resolve("24:0A:C4:11:22:33").displayLabel)
        assertEquals("Raspberry Pi", OuiVendorResolver.resolve("B8:27:EB:11:22:33").displayLabel)

        // Android 02:00:00:00:00:00 placeholder
        val redacted = OuiVendorResolver.resolve("02:00:00:00:00:00")
        assertTrue(redacted.isRedactedPlaceholder)
        assertTrue(redacted.isLocallyAdministered)
        assertEquals(OuiVendorResolver.ANDROID_REDACTED_LABEL, redacted.displayLabel)

        // Locally Administered virtual VAP derived from Ubiquiti 04:18:D6 -> 06:18:D6
        val virtualUbiquiti = OuiVendorResolver.resolve("06:18:D6:99:88:77")
        assertTrue(virtualUbiquiti.isLocallyAdministered)
        assertEquals("Ubiquiti", virtualUbiquiti.inferredParentVendor)
        assertEquals("Ubiquiti (Randomized / Virtual MAC)", virtualUbiquiti.displayLabel)

        // Unknown locally administered MAC -> Randomized / Virtual MAC
        val randomHotspot = OuiVendorResolver.resolve("FE:AB:CD:12:34:56")
        assertTrue(randomHotspot.isLocallyAdministered)
        assertNull(randomHotspot.inferredParentVendor)
        assertEquals("Randomized / Virtual MAC", randomHotspot.displayLabel)

        // Malformed MAC never crashes
        val malformed = OuiVendorResolver.resolve("not-a-mac")
        assertNotNull(malformed)
        assertEquals("Unknown Vendor", malformed.displayLabel)
    }
}
