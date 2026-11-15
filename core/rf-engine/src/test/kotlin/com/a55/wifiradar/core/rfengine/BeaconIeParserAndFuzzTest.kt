package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.AkmSuite
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.WifiGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BeaconIeParserAndFuzzTest {

    @Test
    fun beaconIeInspector_decodesAll20PlusStandardAndExtendedIesAccurately() {
        val ssid = "TriBand_Pro"
        val ssidBytes = ssid.encodeToByteArray()

        // Supported Rates (ID 1) with 6/12/24/54 Mbps + BSS Membership Selector 123 (0xFB = SAE_H2E_ONLY)
        val ieSupportedRates = RawInformationElement(
            id = 1,
            bytes = hex("8c18306cfb"),
        )
        // TIM (ID 5): DTIM count=1, DTIM period=3, bitmap control=0x01 (multicast buffered), partial virtual bitmap=0x00
        val ieTim = RawInformationElement(id = 5, bytes = hex("01030100"))
        // Country (ID 7): "DE" + 'I' (Indoor)
        val ieCountry = RawInformationElement(id = 7, bytes = hex("444549240417"))
        // BSS Load (ID 11): 12 stations (0x000C LE), utilization 64/255 (25%), admission cap 0x0000
        val ieBssLoad = RawInformationElement(id = 11, bytes = hex("0c00400000"))
        // TPC Report (ID 35): signed -4 dBm (0xFC) or +17 dBm (0x11) + link margin 5 dB
        val ieTpc = RawInformationElement(id = 35, bytes = hex("1105"))
        // HT Capabilities (ID 45): 26 bytes, 40MHz+SGI20+SGI40 (0x0062 LE), 2x2 MIMO Rx MCS mask
        val htBytes = ByteArray(26).apply {
            this[0] = 0x62
            this[1] = 0x00
            this[3] = 0xFF.toByte()
            this[4] = 0xFF.toByte()
        }
        val ieHt = RawInformationElement(id = 45, bytes = htBytes)
        // RSN (ID 48): Version 1, Group CCMP-128, Pairwise [CCMP-128, GCMP-256], AKM [SAE, FT-SAE], RSN Cap MFPC+MFPR (0x00C0)
        val ieRsn = RawInformationElement(
            id = 48,
            bytes = hex("0100" + "000fac04" + "0200" + "000fac04" + "000fac09" + "0200" + "000fac08" + "000fac09" + "c000"),
        )
        // Mobility Domain (ID 54): MDID A1B2, Over-the-DS FT bit 0 = 1
        val ieMd = RawInformationElement(id = 54, bytes = hex("a1b201"))
        // RM Enabled (ID 70): Neighbor report enabled
        val ieRm = RawInformationElement(id = 70, bytes = hex("0200000000"))
        // Multiple BSSID (ID 71): indicator = 3 (max 8 VAPs)
        val ieMultiBssid = RawInformationElement(id = 71, bytes = hex("03"))
        // Extended Capabilities (ID 127): 11 bytes with bit 19 (802.11v), bit 70 (FTM), bit 78 (TWT), bit 84 (Beacon Prot)
        val extCapBytes = ByteArray(11).apply {
            this[2] = (1 shl 3).toByte() // bit 19
            this[8] = (1 shl 6).toByte() // bit 70
            this[9] = (1 shl 6).toByte() // bit 78
            this[10] = (1 shl 4).toByte() // bit 84
        }
        val ieExtCap = RawInformationElement(id = 127, bytes = extCapBytes)
        // RNR (ID 201): 1 neighbor entry (count-1 = 0 -> header0 = 0x00), tbttLen=13, opClass=133 (80 MHz 6 GHz), Ch 37 (PSC!)
        // Entry: offset=0x10, BSSID=04:92:26:AA:BB:6E, shortSsid(4B)=0, bssParams=0x42 (SameSSID 0x02 | Colocated 0x40), psd=0x20
        val ieRnr = RawInformationElement(
            id = 201,
            bytes = hex("000d8525" + "10" + "049226aabb6e" + "00000000" + "42" + "20"),
        )
        // Vendor Specific WPS (ID 221, 00:50:F2:04): Big-endian TLVs for Manufacturer (0x1021) + Model Name (0x1023)
        val ieWps = RawInformationElement(
            id = 221,
            bytes = hex(
                "0050f204" +
                    "1044000102" +
                    "1021000441535553" + // "ASUS"
                    "1023000752542d42453936" // "RT-BE96"
            ),
        )
        // Vendor Specific MBO (ID 221, 50:6F:9A:16)
        val ieMbo = RawInformationElement(id = 221, bytes = hex("506f9a16010100"))
        // RSNX (ID 244): bit 5 (0x20) SAE H2E
        val ieRsnx = RawInformationElement(id = 244, bytes = hex("20"))
        // HE Capabilities (ID 255, idExt 35 - idExt already stripped!): 21 bytes, 4x4 HE80 MCS map
        val heCapBytes = ByteArray(21).apply {
            this[0] = 0x04 // TWT responder
            this[6] = 0x08 // 160 MHz supported
            this[9] = 0x80.toByte() // SU beamformer
            this[10] = 0x02 // MU-MIMO
            // Rx MCS map <=80 MHz: streams 1..4 = 2 (MCS 0-11), streams 5..8 = 3 (not supported) -> 0xFFAA
            this[17] = 0xAA.toByte()
            this[18] = 0xFF.toByte()
            this[19] = 0xAA.toByte()
            this[20] = 0xFF.toByte()
        }
        // Append 4 bytes for 160 MHz MCS map since chWidthSet & 0x08 != 0
        val ieHeCap = RawInformationElement(id = 255, idExt = 35, bytes = heCapBytes + hex("aaff" + "aaff"))
        // HE Operation (ID 255, idExt 36): BSS Color 42 (0x2A)
        val ieHeOp = RawInformationElement(id = 255, idExt = 36, bytes = hex("0000002afc00"))
        // EHT Operation (ID 255, idExt 106): params=0x03 (opInfo + disabled subchannel bitmap), width=4 (320 MHz), bitmap=0x0004 (1 punctured subchannel)
        val ieEhtOp = RawInformationElement(id = 255, idExt = 106, bytes = hex("03000000000425000400"))
        // Multi-Link (ID 255, idExt 107): Basic ML (mlControl=0x0010 linkId present), commonLen=7, MLD MAC=04:92:26:00:00:99, linkId=1
        val ieMlo = RawInformationElement(id = 255, idExt = 107, bytes = hex("10000704922600009901"))
        // EHT Capabilities (ID 255, idExt 108): 14 bytes, 320 MHz bit, 4x4 EHT streams (0x44), MCS 12-13 4096-QAM
        val ehtCapBytes = ByteArray(14).apply {
            this[2] = 0x22 // 320 MHz + SU Beamformer
            this[11] = 0x44
            this[12] = 0x44
            this[13] = 0x44
        }
        val ieEhtCap = RawInformationElement(id = 255, idExt = 108, bytes = ehtCapBytes)

        val channelDesc = ChannelAndFrequencyResolver.resolve(5975, centerFreq0Mhz = 6105, channelWidthCode = 5)
        val res = BeaconIeInspector.inspect(
            informationElements = listOf(
                ieSupportedRates, ieTim, ieCountry, ieBssLoad, ieTpc, ieHt, ieRsn,
                ieMd, ieRm, ieMultiBssid, ieExtCap, ieRnr, ieWps, ieMbo, ieRsnx,
                ieHeCap, ieHeOp, ieEhtOp, ieMlo, ieEhtCap,
            ),
            sourceBssid = "04:92:26:AA:BB:01",
            sourceSsidDisplayName = ssid,
            sourceSsidBytes = ssidBytes,
            wifiStandardCode = 8,
            channelDescriptor = channelDesc,
        )

        assertEquals(0, res.beaconDetails.malformedIeCount)
        assertEquals(WifiGeneration.WIFI_7, res.wifiGeneration)
        assertTrue(res.beaconDetails.hasSaeH2eBssMembershipSelector)
        assertTrue(res.rsnxSaeH2e)
        assertTrue(res.eht320MhzActive)

        assertEquals(3, res.beaconDetails.timDtim?.dtimPeriod)
        assertTrue(res.beaconDetails.timDtim?.hasBufferedMulticast == true)
        assertEquals("DE", res.beaconDetails.country?.countryCode)
        assertEquals("Indoor", res.beaconDetails.country?.regulatoryEnvironment)

        assertEquals(12, res.beaconDetails.bssLoad?.stationCount)
        assertEquals(25, res.beaconDetails.bssLoad?.channelUtilizationPct)
        assertEquals(17, res.beaconDetails.tpcApTxPowerDbm)
        assertEquals(3, res.beaconDetails.maxBssidIndicator)

        // PHY capabilities: 4x4 MIMO on 320 MHz Wi-Fi 7 4096-QAM -> 11529 Mbps
        val phy = res.beaconDetails.phyCapability
        assertEquals(4, phy.maxSpatialStreams)
        assertEquals("4×4 MIMO", phy.mimoLabel)
        assertEquals(11529, phy.estimatedMaxPhyMbps)
        assertTrue(phy.supports160Mhz)
        assertTrue(phy.supports320Mhz)
        assertTrue(phy.supports4096Qam)
        assertTrue(phy.twtResponder)

        // BSS Color & MLO & Puncturing
        assertEquals(42, res.beaconDetails.bssColor?.colorCode)
        assertFalse(res.beaconDetails.bssColor?.isDisabled == true)
        assertEquals("04:92:26:00:00:99", res.beaconDetails.ehtMlo?.apMldMacAddress)
        assertEquals(1, res.beaconDetails.ehtMlo?.mloLinkId)
        assertTrue(res.beaconDetails.ehtMlo?.isPreamblePunctured == true)
        assertEquals(0x0004, res.beaconDetails.ehtMlo?.disabledSubchannelBitmap)
        assertEquals(1, res.beaconDetails.ehtMlo?.puncturedSubchannelCount)

        // RNR Co-located 6 GHz Neighbor
        assertEquals(1, res.beaconDetails.colocated6GhzNeighbors.size)
        val nb = res.beaconDetails.colocated6GhzNeighbors[0]
        assertEquals("04:92:26:AA:BB:6E", nb.neighborBssid)
        assertEquals(37, nb.channelNumber)
        assertEquals(80, nb.widthMhz)
        assertTrue(nb.isPscChannel)
        assertTrue(nb.isSameSsid)
        assertTrue(nb.isColocatedAp)

        // WPS Device identity
        assertEquals("ASUS", res.beaconDetails.wpsDevice?.manufacturer)
        assertEquals("RT-BE96", res.beaconDetails.wpsDevice?.modelName)

        // Roaming 802.11k/v/r + MBO + FTM + Beacon Protection
        val roam = res.beaconDetails.roaming
        assertTrue(roam.supports80211k)
        assertTrue(roam.supports80211v)
        assertTrue(roam.supports80211r)
        assertEquals("A1B2", roam.mobilityDomainIdHex)
        assertTrue(roam.overTheDsFt)
        assertTrue(roam.supportsMbo)
        assertTrue(roam.ftmResponder)
        assertTrue(roam.beaconProtection)

        // RSN parsed suites
        val rsn = res.rsnInfo
        assertNotNull(rsn)
        assertEquals(listOf(CipherSuite.CCMP_128, CipherSuite.GCMP_256), rsn!!.pairwiseCiphers)
        assertEquals(listOf(AkmSuite.SAE, AkmSuite.FT_OVER_SAE), rsn.akmSuites)
        assertTrue(rsn.mfpc)
        assertTrue(rsn.mfpr)
    }

    @Test
    fun tier5AdversarialFuzz_2500TruncatedOverflowAndRandomIePayloads_zeroExceptions() {
        val rng = Random(0xA55_2026L)
        val channelDesc = ChannelAndFrequencyResolver.resolve(5180, centerFreq0Mhz = 5210, channelWidthCode = 2)
        val targetIds = intArrayOf(0, 1, 5, 7, 11, 35, 42, 45, 48, 50, 54, 70, 71, 127, 133, 191, 201, 221, 244, 255)
        val targetIdExts = intArrayOf(0, 35, 36, 106, 107, 108, 255)

        // Explicitly include crafted integer-overflow traps (RSN pCount = 0xFFFF, RNR tbttCount = 15 & tbttLen = 255, WPS attrLen = 0xFFFF)
        val craftedTraps = listOf(
            RawInformationElement(id = 48, bytes = hex("0100000fac04ffff")),
            RawInformationElement(id = 201, bytes = hex("ffff852500")),
            RawInformationElement(id = 221, bytes = hex("0050f2041023ffff41")),
            RawInformationElement(id = 221, bytes = hex("0050f20101000050f202ffff")),
            RawInformationElement(id = 255, idExt = 107, bytes = hex("1000ff049226")),
            RawInformationElement(id = 255, idExt = 106, bytes = hex("0300000000")),
            RawInformationElement(id = 35, bytes = hex("80ff")), // negative signed int8 TPC (-128 dBm, -1 dB)
        )

        val trapRes = BeaconIeInspector.inspect(
            informationElements = craftedTraps,
            sourceBssid = "04:92:26:11:22:33",
            sourceSsidDisplayName = "FuzzNet",
            sourceSsidBytes = "FuzzNet".encodeToByteArray(),
            wifiStandardCode = 6,
            channelDescriptor = channelDesc,
        )
        assertTrue("Expected malformed IEs to be counted safely", trapRes.beaconDetails.malformedIeCount >= 5)
        assertEquals(-128, trapRes.beaconDetails.tpcApTxPowerDbm)

        repeat(2_500) { iter ->
            val ieCount = rng.nextInt(1, 8)
            val ies = List(ieCount) {
                val id = if (rng.nextInt(10) < 8) {
                    targetIds[rng.nextInt(targetIds.size)]
                } else {
                    rng.nextInt(0, 256)
                }
                val idExt = if (id == 255) {
                    targetIdExts[rng.nextInt(targetIdExts.size)]
                } else {
                    0
                }
                val len = when (iter % 5) {
                    0 -> 0
                    1 -> rng.nextInt(1, 4)
                    2 -> rng.nextInt(4, 15)
                    3 -> rng.nextInt(15, 64)
                    else -> rng.nextInt(64, 256)
                }
                RawInformationElement(id = id, idExt = idExt, bytes = rng.nextBytes(len))
            }

            val result = BeaconIeInspector.inspect(
                informationElements = ies,
                sourceBssid = "04:92:26:11:22:33",
                sourceSsidDisplayName = "Fuzz_$iter",
                sourceSsidBytes = null,
                wifiStandardCode = rng.nextInt(-1, 10),
                channelDescriptor = channelDesc,
            )
            assertEquals(ies.size, result.beaconDetails.rawIeCount)
            assertTrue(result.beaconDetails.phyCapability.maxSpatialStreams in 1..8)
            assertTrue(result.beaconDetails.phyCapability.estimatedMaxPhyMbps > 0)
        }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }
}
