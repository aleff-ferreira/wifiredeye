package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.abs

/**
 * Deterministic IEEE 802.11 multi-band frequency, channel, sub-band, regulatory flag,
 * and spectral lobe resolver (`F05`).
 */
object ChannelAndFrequencyResolver {

    private val CANONICAL_24_GHZ_CHANNELS = setOf(1, 6, 11)

    private val CANONICAL_5_GHZ_CHANNELS = setOf(
        36, 40, 44, 48,
        52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
        149, 153, 157, 161, 165,
    )

    private val TDWR_5_GHZ_CHANNELS = setOf(120, 124, 128)

    fun resolve(
        primaryFreqMhz: Int,
        centerFreq0Mhz: Int = 0,
        centerFreq1Mhz: Int = 0,
        channelWidthCode: Int = 0,
        eht320MhzOverride: Boolean = false,
    ): ChannelDescriptor {
        val (band, primaryChannel) = freqToBandAndChannel(primaryFreqMhz)
        val rawWidth = ChannelWidth.fromAndroidCode(channelWidthCode)
        val width = if (eht320MhzOverride && band == WifiBand.BAND_6_GHZ) {
            ChannelWidth.WIDTH_320_MHZ
        } else {
            rawWidth
        }

        if (band == WifiBand.UNKNOWN) {
            return ChannelDescriptor(
                band = WifiBand.UNKNOWN,
                primaryFreqMhz = primaryFreqMhz,
                primaryChannel = -1,
                centerFreq0Mhz = 0,
                centerChannel0 = -1,
                centerFreq1Mhz = 0,
                centerChannel1 = -1,
                width = width,
                occupiedStartFreqMhz = 0,
                occupiedEndFreqMhz = 0,
                secondaryOccupiedStartFreqMhz = null,
                secondaryOccupiedEndFreqMhz = null,
                uniiSubBand = UniiSubBand.UNKNOWN,
                isCanonicalPrimary = false,
                isDfs = false,
                isTdwr = false,
                isPsc = false,
            )
        }

        val effectiveCenter0 = resolveEffectiveCenter0Mhz(
            band = band,
            primaryFreqMhz = primaryFreqMhz,
            primaryChannel = primaryChannel,
            centerFreq0Mhz = centerFreq0Mhz,
            centerFreq1Mhz = centerFreq1Mhz,
            width = width,
        )
        val centerChannel0 = freqToBandAndChannel(effectiveCenter0).second

        val effectiveCenter1 = if (width == ChannelWidth.WIDTH_80_PLUS_80_MHZ && centerFreq1Mhz > 0) {
            centerFreq1Mhz
        } else {
            0
        }
        val centerChannel1 = if (effectiveCenter1 > 0) {
            freqToBandAndChannel(effectiveCenter1).second
        } else {
            -1
        }

        val (occupiedStart, occupiedEnd) = computePrimaryLobeBounds(
            band = band,
            effectiveCenter0Mhz = effectiveCenter0,
            width = width,
        )

        val secondaryStart: Int?
        val secondaryEnd: Int?
        if (width == ChannelWidth.WIDTH_80_PLUS_80_MHZ && effectiveCenter1 > 0) {
            secondaryStart = (effectiveCenter1 - 40).coerceAtLeast(0)
            secondaryEnd = (effectiveCenter1 + 40).coerceAtLeast(0)
        } else {
            secondaryStart = null
            secondaryEnd = null
        }

        val uniiSubBand = resolveUniiSubBand(band, primaryFreqMhz)
        val isPsc = isPsc6GhzChannel(band, primaryChannel)
        val isCanonical = isCanonicalPrimaryChannel(band, primaryChannel, isPsc)
        val isDfs = computeIsDfs(
            band = band,
            primaryChannel = primaryChannel,
            occupiedStart = occupiedStart,
            occupiedEnd = occupiedEnd,
            secondaryStart = secondaryStart,
            secondaryEnd = secondaryEnd,
        )
        val isTdwr = computeIsTdwr(
            band = band,
            primaryChannel = primaryChannel,
            occupiedStart = occupiedStart,
            occupiedEnd = occupiedEnd,
            secondaryStart = secondaryStart,
            secondaryEnd = secondaryEnd,
        )

        return ChannelDescriptor(
            band = band,
            primaryFreqMhz = primaryFreqMhz,
            primaryChannel = primaryChannel,
            centerFreq0Mhz = effectiveCenter0,
            centerChannel0 = centerChannel0,
            centerFreq1Mhz = effectiveCenter1,
            centerChannel1 = centerChannel1,
            width = width,
            occupiedStartFreqMhz = occupiedStart,
            occupiedEndFreqMhz = occupiedEnd,
            secondaryOccupiedStartFreqMhz = secondaryStart,
            secondaryOccupiedEndFreqMhz = secondaryEnd,
            uniiSubBand = uniiSubBand,
            isCanonicalPrimary = isCanonical,
            isDfs = isDfs,
            isTdwr = isTdwr,
            isPsc = isPsc,
        )
    }

    fun freqToBandAndChannel(freqMhz: Int): Pair<WifiBand, Int> = when {
        freqMhz <= 0 -> WifiBand.UNKNOWN to -1
        freqMhz == 2484 -> WifiBand.BAND_2_4_GHZ to 14
        freqMhz in 2400..2495 -> {
            val ch = if (freqMhz in 2412..2472 && (freqMhz - 2407) % 5 == 0) {
                (freqMhz - 2407) / 5
            } else {
                -1
            }
            WifiBand.BAND_2_4_GHZ to ch
        }
        freqMhz in 5150..5895 -> {
            val ch = if (freqMhz in 5160..5885 && (freqMhz - 5000) % 5 == 0) {
                (freqMhz - 5000) / 5
            } else {
                -1
            }
            WifiBand.BAND_5_GHZ to ch
        }
        freqMhz == 5935 -> WifiBand.BAND_6_GHZ to 2
        freqMhz in 5925..7125 -> {
            val ch = if (freqMhz in 5955..7115 && (freqMhz - 5950) % 5 == 0) {
                (freqMhz - 5950) / 5
            } else {
                -1
            }
            WifiBand.BAND_6_GHZ to ch
        }
        freqMhz in 57240..71280 -> {
            val ch = if (freqMhz in 58320..70200 && (freqMhz - 56160) % 2160 == 0) {
                (freqMhz - 56160) / 2160
            } else {
                -1
            }
            WifiBand.BAND_60_GHZ to ch
        }
        else -> WifiBand.UNKNOWN to -1
    }

    fun channelToFrequencyMhz(band: WifiBand, channel: Int): Int = when (band) {
        WifiBand.BAND_2_4_GHZ -> when {
            channel == 14 -> 2484
            channel in 1..13 -> 2407 + 5 * channel
            else -> 0
        }
        WifiBand.BAND_5_GHZ -> if (channel in 32..177) 5000 + 5 * channel else 0
        WifiBand.BAND_6_GHZ -> when {
            channel == 2 -> 5935
            channel in 1..233 -> 5950 + 5 * channel
            else -> 0
        }
        WifiBand.BAND_60_GHZ -> if (channel in 1..6) 56160 + 2160 * channel else 0
        WifiBand.UNKNOWN -> 0
    }

    fun resolveUniiSubBand(band: WifiBand, primaryFreqMhz: Int): UniiSubBand = when (band) {
        WifiBand.BAND_2_4_GHZ -> if (primaryFreqMhz == 2484) {
            UniiSubBand.JAPAN_2_4_GHZ_CH14
        } else {
            UniiSubBand.ISM_2_4_GHZ
        }
        WifiBand.BAND_5_GHZ -> when (primaryFreqMhz) {
            in 5150..5250 -> UniiSubBand.UNII_1
            in 5251..5350 -> UniiSubBand.UNII_2A
            in 5470..5730 -> UniiSubBand.UNII_2C
            in 5735..5835 -> UniiSubBand.UNII_3
            in 5836..5895 -> UniiSubBand.UNII_4
            else -> UniiSubBand.UNKNOWN
        }
        WifiBand.BAND_6_GHZ -> when (primaryFreqMhz) {
            in 5925..6425 -> UniiSubBand.UNII_5
            in 6426..6525 -> UniiSubBand.UNII_6
            in 6526..6875 -> UniiSubBand.UNII_7
            in 6876..7125 -> UniiSubBand.UNII_8
            else -> UniiSubBand.UNKNOWN
        }
        WifiBand.BAND_60_GHZ -> UniiSubBand.WIGIG_60_GHZ
        WifiBand.UNKNOWN -> UniiSubBand.UNKNOWN
    }

    fun isPsc6GhzChannel(band: WifiBand, channel: Int): Boolean =
        band == WifiBand.BAND_6_GHZ && channel in 5..229 && (channel - 5) % 16 == 0

    fun isCanonicalPrimaryChannel(
        band: WifiBand,
        channel: Int,
        isPsc: Boolean = isPsc6GhzChannel(band, channel),
    ): Boolean = when (band) {
        WifiBand.BAND_2_4_GHZ -> channel in CANONICAL_24_GHZ_CHANNELS
        WifiBand.BAND_5_GHZ -> channel in CANONICAL_5_GHZ_CHANNELS
        WifiBand.BAND_6_GHZ -> isPsc
        WifiBand.BAND_60_GHZ -> channel in 1..6
        WifiBand.UNKNOWN -> false
    }

    private fun resolveEffectiveCenter0Mhz(
        band: WifiBand,
        primaryFreqMhz: Int,
        primaryChannel: Int,
        centerFreq0Mhz: Int,
        centerFreq1Mhz: Int,
        width: ChannelWidth,
    ): Int {
        if (width == ChannelWidth.WIDTH_20_MHZ || width == ChannelWidth.UNKNOWN) {
            return primaryFreqMhz
        }
        if (width == ChannelWidth.WIDTH_160_MHZ &&
            centerFreq0Mhz > 0 &&
            centerFreq1Mhz > 0 &&
            abs(centerFreq1Mhz - centerFreq0Mhz) == 40
        ) {
            return centerFreq1Mhz
        }
        if (centerFreq0Mhz > 0) {
            return centerFreq0Mhz
        }
        return inferStandardCenterFreq0Mhz(band, primaryFreqMhz, primaryChannel, width)
    }

    fun inferStandardCenterFreq0Mhz(
        band: WifiBand,
        primaryFreqMhz: Int,
        primaryChannel: Int,
        width: ChannelWidth,
    ): Int {
        if (primaryChannel <= 0) return primaryFreqMhz
        return when (band) {
            WifiBand.BAND_2_4_GHZ -> primaryFreqMhz
            WifiBand.BAND_5_GHZ -> when (width) {
                ChannelWidth.WIDTH_40_MHZ -> when (primaryChannel) {
                    36, 40 -> 5190
                    44, 48 -> 5230
                    52, 56 -> 5270
                    60, 64 -> 5310
                    100, 104 -> 5510
                    108, 112 -> 5550
                    116, 120 -> 5590
                    124, 128 -> 5630
                    132, 136 -> 5670
                    140, 144 -> 5710
                    149, 153 -> 5755
                    157, 161 -> 5795
                    165, 169 -> 5835
                    173, 177 -> 5875
                    else -> primaryFreqMhz
                }
                ChannelWidth.WIDTH_80_MHZ, ChannelWidth.WIDTH_80_PLUS_80_MHZ -> when (primaryChannel) {
                    in 36..48 -> 5210
                    in 52..64 -> 5290
                    in 100..112 -> 5530
                    in 116..128 -> 5610
                    in 132..144 -> 5690
                    in 149..161 -> 5775
                    in 165..177 -> 5855
                    else -> primaryFreqMhz
                }
                ChannelWidth.WIDTH_160_MHZ -> when (primaryChannel) {
                    in 36..64 -> 5250
                    in 100..128 -> 5570
                    in 149..177 -> 5815
                    else -> primaryFreqMhz
                }
                else -> primaryFreqMhz
            }
            WifiBand.BAND_6_GHZ -> {
                if (primaryChannel == 2) return 5935
                val idx20 = (primaryChannel - 1) / 4
                when (width) {
                    ChannelWidth.WIDTH_40_MHZ -> 5965 + (idx20 / 2) * 40
                    ChannelWidth.WIDTH_80_MHZ, ChannelWidth.WIDTH_80_PLUS_80_MHZ -> 5985 + (idx20 / 4) * 80
                    ChannelWidth.WIDTH_160_MHZ -> 6025 + (idx20 / 8) * 160
                    ChannelWidth.WIDTH_320_MHZ -> 6105 + (idx20 / 16) * 320
                    else -> primaryFreqMhz
                }
            }
            WifiBand.BAND_60_GHZ, WifiBand.UNKNOWN -> primaryFreqMhz
        }
    }

    private fun computePrimaryLobeBounds(
        band: WifiBand,
        effectiveCenter0Mhz: Int,
        width: ChannelWidth,
    ): Pair<Int, Int> {
        if (band == WifiBand.BAND_60_GHZ) {
            return (effectiveCenter0Mhz - 1080).coerceAtLeast(0) to (effectiveCenter0Mhz + 1080).coerceAtLeast(0)
        }
        if (band == WifiBand.BAND_2_4_GHZ && (width == ChannelWidth.WIDTH_20_MHZ || width == ChannelWidth.UNKNOWN)) {
            return (effectiveCenter0Mhz - 11).coerceAtLeast(0) to (effectiveCenter0Mhz + 11).coerceAtLeast(0)
        }
        val halfSpan = width.primarySpanMhz / 2
        return (effectiveCenter0Mhz - halfSpan).coerceAtLeast(0) to (effectiveCenter0Mhz + halfSpan).coerceAtLeast(0)
    }

    private fun computeIsDfs(
        band: WifiBand,
        primaryChannel: Int,
        occupiedStart: Int,
        occupiedEnd: Int,
        secondaryStart: Int?,
        secondaryEnd: Int?,
    ): Boolean {
        if (band != WifiBand.BAND_5_GHZ) return false
        if (primaryChannel in 52..64 || primaryChannel in 100..144) return true
        if (overlapsOpenInterval(occupiedStart, occupiedEnd, 5250, 5330) ||
            overlapsOpenInterval(occupiedStart, occupiedEnd, 5490, 5730)
        ) {
            return true
        }
        if (secondaryStart != null && secondaryEnd != null) {
            if (overlapsOpenInterval(secondaryStart, secondaryEnd, 5250, 5330) ||
                overlapsOpenInterval(secondaryStart, secondaryEnd, 5490, 5730)
            ) {
                return true
            }
        }
        return false
    }

    private fun computeIsTdwr(
        band: WifiBand,
        primaryChannel: Int,
        occupiedStart: Int,
        occupiedEnd: Int,
        secondaryStart: Int?,
        secondaryEnd: Int?,
    ): Boolean {
        if (band != WifiBand.BAND_5_GHZ) return false
        if (primaryChannel in TDWR_5_GHZ_CHANNELS) return true
        if (overlapsOpenInterval(occupiedStart, occupiedEnd, 5590, 5650)) return true
        if (secondaryStart != null && secondaryEnd != null &&
            overlapsOpenInterval(secondaryStart, secondaryEnd, 5590, 5650)
        ) {
            return true
        }
        return false
    }

    private fun overlapsOpenInterval(startA: Int, endA: Int, startB: Int, endB: Int): Boolean =
        maxOf(startA, startB) < minOf(endA, endB)
}
