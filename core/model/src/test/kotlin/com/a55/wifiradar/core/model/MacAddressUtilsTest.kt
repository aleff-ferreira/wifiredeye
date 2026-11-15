package com.a55.wifiradar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAddressUtilsTest {

    @Test
    fun normalizeBssid_convertsHyphensLowercaseAndTrimsWhitespace() {
        assertEquals("AA:BB:CC:00:11:FF", MacAddressUtils.normalizeBssid(" aa-bb-cc-00-11-ff "))
        assertEquals("12:34:56:78:9A:BC", MacAddressUtils.normalizeBssid("12:34:56:78:9a:bc"))
        assertEquals("", MacAddressUtils.normalizeBssid("   "))
    }

    @Test
    fun isValidMac_validatesSixOctetHexAndRejectsMalformed() {
        assertTrue(MacAddressUtils.isValidMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(MacAddressUtils.isValidMac("aa-bb-cc-dd-ee-ff"))
        assertFalse(MacAddressUtils.isValidMac("AA:BB:CC:DD:EE"))
        assertFalse(MacAddressUtils.isValidMac("AA:BB:CC:DD:EE:GG"))
        assertFalse(MacAddressUtils.isValidMac("not-a-mac"))
        assertFalse(MacAddressUtils.isValidMac(""))
    }

    @Test
    fun isAndroidRedactedMac_detectsAndroidSentinelOnly() {
        assertTrue(MacAddressUtils.isAndroidRedactedMac("02:00:00:00:00:00"))
        assertTrue(MacAddressUtils.isAndroidRedactedMac(" 02-00-00-00-00-00 "))
        assertFalse(MacAddressUtils.isAndroidRedactedMac("02:00:00:00:00:01"))
    }

    @Test
    fun locallyAdministeredBitAndOuiHelpers_computeExactBits() {
        assertTrue(MacAddressUtils.isLocallyAdministered("02:11:22:33:44:55"))
        assertTrue(MacAddressUtils.isLocallyAdministered("da:bb:cc:11:22:33"))
        assertFalse(MacAddressUtils.isLocallyAdministered("00:11:22:33:44:55"))
        assertFalse(MacAddressUtils.isLocallyAdministered("d8:bb:cc:11:22:33"))
        assertFalse(MacAddressUtils.isLocallyAdministered("invalid"))

        assertEquals("D8:BB:CC", MacAddressUtils.clearLocallyAdministeredBitOui("da:bb:cc:11:22:33"))
        assertEquals("D8:BB:CC", MacAddressUtils.clearLocallyAdministeredBitOui("d8:bb:cc:11:22:33"))
        assertEquals("DA:BB:CC", MacAddressUtils.extractOuiPrefix("da-bb-cc-11-22-33"))
        assertNull(MacAddressUtils.extractOuiPrefix("invalid"))
        assertNull(MacAddressUtils.clearLocallyAdministeredBitOui("invalid"))
    }

    @Test
    fun extractUniversalFiveOctetPrefix_clearsUlBitAndKeepsFiveOctets() {
        assertEquals(
            "D8:BB:CC:11:22",
            MacAddressUtils.extractUniversalFiveOctetPrefix("da:bb:cc:11:22:33")
        )
        assertEquals(
            "D8:BB:CC:11:22",
            MacAddressUtils.extractUniversalFiveOctetPrefix("D8:BB:CC:11:22:99")
        )
        assertNull(MacAddressUtils.extractUniversalFiveOctetPrefix("bad-mac"))
    }

    @Test
    fun redactMac_masksSubscriberOctetsAndNeverLeaksFullMac() {
        val raw = "aa:bb:cc:44:55:01"
        val redacted = MacAddressUtils.redactMac(raw)
        assertEquals("AA:BB:CC:XX:XX:01", redacted)
        assertFalse(redacted.contains("44:55"))
        assertFalse(redacted.equals(MacAddressUtils.normalizeBssid(raw), ignoreCase = true))
        assertEquals("XX:XX:XX:XX:XX:XX", MacAddressUtils.redactMac("malformed"))
    }
}
