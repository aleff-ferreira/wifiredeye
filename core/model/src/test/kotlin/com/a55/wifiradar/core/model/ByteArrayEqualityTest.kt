package com.a55.wifiradar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ByteArrayEqualityTest {

    @Test
    fun rawInformationElement_usesStructuralContentEqualityAndHashCode() {
        val ieA = RawInformationElement(id = 11, idExt = 0, bytes = byteArrayOf(0x0C, 0x00, 0x40, 0x00, 0x00))
        val ieB = RawInformationElement(id = 11, idExt = 0, bytes = byteArrayOf(0x0C, 0x00, 0x40, 0x00, 0x00))
        val ieDiffPayload = RawInformationElement(id = 11, idExt = 0, bytes = byteArrayOf(0x0D, 0x00, 0x40, 0x00, 0x00))
        val ieDiffExt = RawInformationElement(id = 255, idExt = 35, bytes = byteArrayOf(0x0C, 0x00, 0x40, 0x00, 0x00))

        assertEquals(ieA, ieB)
        assertEquals(ieA.hashCode(), ieB.hashCode())
        assertNotEquals(ieA, ieDiffPayload)
        assertNotEquals(ieA, ieDiffExt)
    }

    @Test
    fun rawScanEntry_usesStructuralContentEqualityAcrossClonedArrays() {
        val ssidBytesA = "Home_5G".encodeToByteArray()
        val ssidBytesB = ssidBytesA.clone()
        val entryA = RawScanEntry(
            bssid = "AA:BB:CC:00:00:01",
            rawSsidBytes = ssidBytesA,
            legacySsid = "Home_5G",
            rssiDbm = -52,
            frequencyMhz = 5180,
            centerFreq0Mhz = 5210,
            channelWidthCode = 2,
            wifiStandardCode = 6,
            capabilities = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
            securityTypes = listOf(2, 4),
            timestampMicros = 1_250_000L,
            informationElements = listOf(
                RawInformationElement(id = 11, idExt = 0, bytes = byteArrayOf(4, 0, 64, 0, 0))
            ),
        )
        val entryB = entryA.copy(
            rawSsidBytes = ssidBytesB,
            informationElements = listOf(
                RawInformationElement(id = 11, idExt = 0, bytes = byteArrayOf(4, 0, 64, 0, 0))
            ),
        )
        val entryNullSsid = entryA.copy(rawSsidBytes = null)
        val entryEmptySsid = entryA.copy(rawSsidBytes = byteArrayOf())

        assertEquals(entryA, entryB)
        assertEquals(entryA.hashCode(), entryB.hashCode())
        assertNotEquals(entryNullSsid, entryEmptySsid)
    }
}
