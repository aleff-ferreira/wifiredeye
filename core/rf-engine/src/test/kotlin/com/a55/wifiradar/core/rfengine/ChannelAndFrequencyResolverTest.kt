package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelAndFrequencyResolverTest {

    @Test
    fun band24Ghz_mapsAllChannelsAndSpectralSpansDeterministically() {
        val ch1 = ChannelAndFrequencyResolver.resolve(2412, channelWidthCode = 0)
        assertEquals(WifiBand.BAND_2_4_GHZ, ch1.band)
        assertEquals(1, ch1.primaryChannel)
        assertEquals(2401, ch1.occupiedStartFreqMhz)
        assertEquals(2423, ch1.occupiedEndFreqMhz)
        assertEquals(UniiSubBand.ISM_2_4_GHZ, ch1.uniiSubBand)
        assertTrue(ch1.isCanonicalPrimary)
        assertFalse(ch1.isDfs)
        assertFalse(ch1.isPsc)

        val ch6 = ChannelAndFrequencyResolver.resolve(2437, channelWidthCode = 0)
        assertEquals(6, ch6.primaryChannel)
        assertTrue(ch6.isCanonicalPrimary)

        val ch11 = ChannelAndFrequencyResolver.resolve(2462, channelWidthCode = 0)
        assertEquals(11, ch11.primaryChannel)
        assertTrue(ch11.isCanonicalPrimary)

        val ch13 = ChannelAndFrequencyResolver.resolve(2472, channelWidthCode = 0)
        assertEquals(13, ch13.primaryChannel)
        assertFalse(ch13.isCanonicalPrimary)

        val ch14 = ChannelAndFrequencyResolver.resolve(2484, channelWidthCode = 0)
        assertEquals(14, ch14.primaryChannel)
        assertEquals(UniiSubBand.JAPAN_2_4_GHZ_CH14, ch14.uniiSubBand)
        assertFalse(ch14.isCanonicalPrimary)

        // Bonded 40 MHz on Ch 3 centered at 2422 MHz -> 2402..2442 MHz
        val ch3Bonded40 = ChannelAndFrequencyResolver.resolve(
            primaryFreqMhz = 2422,
            centerFreq0Mhz = 2422,
            channelWidthCode = 1,
        )
        assertEquals(3, ch3Bonded40.primaryChannel)
        assertEquals(ChannelWidth.WIDTH_40_MHZ, ch3Bonded40.width)
        assertEquals(2402, ch3Bonded40.occupiedStartFreqMhz)
        assertEquals(2442, ch3Bonded40.occupiedEndFreqMhz)
        assertFalse(ch3Bonded40.isCanonicalPrimary)
    }

    @Test
    fun band5Ghz_mapsUniiSubBandsDfsTdwrAndDualLobe80Plus80() {
        val ch36 = ChannelAndFrequencyResolver.resolve(5180, centerFreq0Mhz = 5210, channelWidthCode = 2)
        assertEquals(WifiBand.BAND_5_GHZ, ch36.band)
        assertEquals(36, ch36.primaryChannel)
        assertEquals(42, ch36.centerChannel0)
        assertEquals(ChannelWidth.WIDTH_80_MHZ, ch36.width)
        assertEquals(5170, ch36.occupiedStartFreqMhz)
        assertEquals(5250, ch36.occupiedEndFreqMhz)
        assertEquals(UniiSubBand.UNII_1, ch36.uniiSubBand)
        assertTrue(ch36.isCanonicalPrimary)
        assertFalse(ch36.isDfs)
        assertFalse(ch36.isTdwr)

        // Ch 36 @ 160 MHz (center 5250) spans into U-NII-2A -> must flag isDfs = true!
        val ch36Wide160 = ChannelAndFrequencyResolver.resolve(5180, centerFreq0Mhz = 5250, channelWidthCode = 3)
        assertEquals(ChannelWidth.WIDTH_160_MHZ, ch36Wide160.width)
        assertEquals(5170, ch36Wide160.occupiedStartFreqMhz)
        assertEquals(5330, ch36Wide160.occupiedEndFreqMhz)
        assertTrue(ch36Wide160.isDfs)
        assertFalse(ch36Wide160.isTdwr)

        // Ch 52 U-NII-2A DFS
        val ch52 = ChannelAndFrequencyResolver.resolve(5260, channelWidthCode = 0)
        assertEquals(52, ch52.primaryChannel)
        assertEquals(UniiSubBand.UNII_2A, ch52.uniiSubBand)
        assertTrue(ch52.isDfs)
        assertFalse(ch52.isTdwr)

        // Ch 100 U-NII-2C DFS non-TDWR
        val ch100 = ChannelAndFrequencyResolver.resolve(5500, channelWidthCode = 0)
        assertEquals(100, ch100.primaryChannel)
        assertEquals(UniiSubBand.UNII_2C, ch100.uniiSubBand)
        assertTrue(ch100.isDfs)
        assertFalse(ch100.isTdwr)

        // Ch 120, 124, 128 TDWR Weather Radar
        for (freq in listOf(5600, 5620, 5640)) {
            val tdwr = ChannelAndFrequencyResolver.resolve(freq, channelWidthCode = 0)
            assertTrue(tdwr.isDfs)
            assertTrue("Expected TDWR at $freq MHz", tdwr.isTdwr)
        }

        // Ch 116 @ 80 MHz (center 5610) spans across Ch 120-128 -> flags isTdwr = true
        val ch116Wide80 = ChannelAndFrequencyResolver.resolve(5580, centerFreq0Mhz = 5610, channelWidthCode = 2)
        assertTrue(ch116Wide80.isDfs)
        assertTrue(ch116Wide80.isTdwr)

        // Ch 149 U-NII-3 non-DFS & Ch 173 U-NII-4
        val ch149 = ChannelAndFrequencyResolver.resolve(5745, centerFreq0Mhz = 5775, channelWidthCode = 2)
        assertEquals(149, ch149.primaryChannel)
        assertEquals(UniiSubBand.UNII_3, ch149.uniiSubBand)
        assertFalse(ch149.isDfs)

        val ch173 = ChannelAndFrequencyResolver.resolve(5865, channelWidthCode = 0)
        assertEquals(173, ch173.primaryChannel)
        assertEquals(UniiSubBand.UNII_4, ch173.uniiSubBand)

        // 80+80 MHz non-contiguous dual lobes
        val dual80p80 = ChannelAndFrequencyResolver.resolve(
            primaryFreqMhz = 5180,
            centerFreq0Mhz = 5210,
            centerFreq1Mhz = 5775,
            channelWidthCode = 4,
        )
        assertEquals(ChannelWidth.WIDTH_80_PLUS_80_MHZ, dual80p80.width)
        assertEquals(5170, dual80p80.occupiedStartFreqMhz)
        assertEquals(5250, dual80p80.occupiedEndFreqMhz)
        assertEquals(5735, dual80p80.secondaryOccupiedStartFreqMhz)
        assertEquals(5815, dual80p80.secondaryOccupiedEndFreqMhz)
        assertFalse(dual80p80.isDfs)
    }

    @Test
    fun band6Ghz_mapsAll15PscChannelsUnii5To8And320Mhz() {
        val ch2 = ChannelAndFrequencyResolver.resolve(5935, channelWidthCode = 0)
        assertEquals(WifiBand.BAND_6_GHZ, ch2.band)
        assertEquals(2, ch2.primaryChannel)
        assertEquals(UniiSubBand.UNII_5, ch2.uniiSubBand)
        assertFalse(ch2.isPsc)

        val expectedPscs = listOf(5, 21, 37, 53, 69, 85, 101, 117, 133, 149, 165, 181, 197, 213, 229)
        val detectedPscs = (1..233 step 4).filter { ch ->
            val freq = 5950 + 5 * ch
            ChannelAndFrequencyResolver.resolve(freq, channelWidthCode = 0).isPsc
        }
        assertEquals(expectedPscs, detectedPscs)

        assertEquals(UniiSubBand.UNII_6, ChannelAndFrequencyResolver.resolve(6455).uniiSubBand)
        assertEquals(UniiSubBand.UNII_7, ChannelAndFrequencyResolver.resolve(6655).uniiSubBand)
        assertEquals(UniiSubBand.UNII_8, ChannelAndFrequencyResolver.resolve(7115).uniiSubBand)

        val eht320 = ChannelAndFrequencyResolver.resolve(
            primaryFreqMhz = 5975,
            centerFreq0Mhz = 6105,
            channelWidthCode = 0,
            eht320MhzOverride = true,
        )
        assertEquals(ChannelWidth.WIDTH_320_MHZ, eht320.width)
        assertEquals(5945, eht320.occupiedStartFreqMhz)
        assertEquals(6265, eht320.occupiedEndFreqMhz)
    }

    @Test
    fun band60GhzAndOutOfRangeFrequencies_neverThrowAndReturnSafeDescriptors() {
        val wigig = ChannelAndFrequencyResolver.resolve(60480, channelWidthCode = 0)
        assertEquals(WifiBand.BAND_60_GHZ, wigig.band)
        assertEquals(2, wigig.primaryChannel)
        assertEquals(UniiSubBand.WIGIG_60_GHZ, wigig.uniiSubBand)

        for (invalidFreq in listOf(Int.MIN_VALUE, -500, -1, 0, 900, 3650, 999_999, Int.MAX_VALUE)) {
            val desc = ChannelAndFrequencyResolver.resolve(invalidFreq, channelWidthCode = 99)
            assertEquals(WifiBand.UNKNOWN, desc.band)
            assertEquals(-1, desc.primaryChannel)
            assertEquals(0, desc.occupiedStartFreqMhz)
            assertEquals(0, desc.occupiedEndFreqMhz)
            assertNull(desc.secondaryOccupiedStartFreqMhz)
            assertFalse(desc.isDfs)
            assertFalse(desc.isPsc)
        }
    }
}
