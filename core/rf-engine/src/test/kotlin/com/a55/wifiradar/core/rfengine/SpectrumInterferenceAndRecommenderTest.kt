package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.InterferenceType
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumInterferenceAndRecommenderTest {

    @Test
    fun band24Ghz_classifiesCciVsAciRecommendsCanonicalChannel11AndNeverNonCanonical() {
        val engine = DefaultRfAnalyticsEngine()
        val entries = listOf(
            // Connected AP on Ch 6 (2437 MHz)
            RawScanEntry(
                bssid = "04:92:26:00:00:06",
                rawSsidBytes = "MyHome_24G".encodeToByteArray(),
                rssiDbm = -52,
                frequencyMhz = 2437,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
            ),
            // Strong Neighbor on Ch 1 (2412 MHz)
            RawScanEntry(
                bssid = "50:C7:BF:00:00:01",
                rawSsidBytes = "Neighbor_Ch1".encodeToByteArray(),
                rssiDbm = -48,
                frequencyMhz = 2412,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
            ),
            // Co-channel Neighbor A on Ch 6 (2437 MHz) -> CCI!
            RawScanEntry(
                bssid = "50:C7:BF:00:00:06",
                rawSsidBytes = "Neighbor_Ch6_CCI".encodeToByteArray(),
                rssiDbm = -58,
                frequencyMhz = 2437,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
            ),
            // Adjacent-channel Neighbor B on Ch 4 (2427 MHz) -> ACI into Ch 6 AND Ch 1, ZERO overlap into Ch 11!
            RawScanEntry(
                bssid = "20:4E:7F:00:00:04",
                rawSsidBytes = "Neighbor_Ch4_ACI".encodeToByteArray(),
                rssiDbm = -59,
                frequencyMhz = 2427,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
            ),
        )

        val snapshot = engine.processScanSnapshot(
            rawBatch = RawScanBatch(entries = entries, receivedElapsedMs = 1_000L),
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:00:00:06",
                ssid = "\"MyHome_24G\"",
                rssiDbm = -52,
                frequencyMhz = 2437,
                timestampElapsedMs = 1_000L,
            ),
            currentElapsedMs = 1_000L,
        )

        val spec24 = engine.computeSpectrumAnalysis(snapshot.radios, WifiBand.BAND_2_4_GHZ)
        val ch6Breakdown = spec24.selectedOrConnectedBreakdown
        assertNotNull(ch6Breakdown)
        assertEquals(6, ch6Breakdown!!.targetChannel)
        assertEquals(1, ch6Breakdown.cciInterferers.size)
        assertEquals("50:C7:BF:00:00:06", ch6Breakdown.cciInterferers[0].bssid)
        assertEquals(InterferenceType.CO_CHANNEL_CCI, ch6Breakdown.cciInterferers[0].interferenceType)

        assertEquals(1, ch6Breakdown.aciInterferers.size)
        assertEquals("20:4E:7F:00:00:04", ch6Breakdown.aciInterferers[0].bssid)
        assertEquals(InterferenceType.ADJACENT_CHANNEL_ACI, ch6Breakdown.aciInterferers[0].interferenceType)
        assertEquals(1.85, ch6Breakdown.aciInterferers[0].penaltyMultiplier, 1e-6)
        assertTrue(ch6Breakdown.hasSevereAciWarning)

        // Verify Ch 11 has exact 0 CCI and 0 ACI interferers and scores 100 / 5.0★!
        val ch11Score = spec24.channelScores.first { it.channel == 11 }
        assertEquals(100, ch11Score.score0To100)
        assertEquals(5.0, ch11Score.starRating0To5, 1e-6)
        assertEquals(0, ch11Score.cciCount)
        assertEquals(0, ch11Score.aciCount)

        // Recommender MUST pick canonical Channel 11 @ 20 MHz with a 1-sentence plain-English reason
        val rec = spec24.recommendation
        assertNotNull(rec)
        assertEquals(11, rec!!.recommendedPrimaryChannel)
        assertEquals(ChannelWidth.WIDTH_20_MHZ, rec.recommendedWidth)
        assertEquals(6, rec.currentConnectedChannel)
        assertFalse(rec.isCurrentChannelAlreadyOptimal)
        assertTrue((rec.estimatedScoreGain ?: 0) > 25)
        assertFalse(SemanticFormatters.containsRawEnumLeak(rec.plainEnglishReason))
        assertSingleSentence(rec.plainEnglishReason)
    }

    @Test
    fun wifi7PreamblePuncturingAnd80Plus80DualLobe_respectExactSubchannelPhysics() {
        val engine = DefaultRfAnalyticsEngine()
        // Wi-Fi 7 80 MHz AP on Ch 36 (center 5210: subchannels 36, 40, 44, 48 -> bits 0, 1, 2, 3)
        // Puncture subchannel bit 2 (Ch 44: 5210..5230 MHz) via EHT Operation IE (255/106): bitmap = 0x0004!
        val ehtOpPuncturedCh44 = RawInformationElement(
            id = 255,
            idExt = 106,
            bytes = byteArrayOf(0x03, 0x00, 0x00, 0x00, 0x00, 0x02, 42, 0, 0x04, 0x00),
        )
        // 80+80 MHz AP with primary Ch 52 (center0 = 5290) + secondary lobe at center1 = 5775 (Ch 149..161)
        val snapshot = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(
                        bssid = "04:18:D6:77:77:01",
                        rawSsidBytes = "Punctured_WiFi7".encodeToByteArray(),
                        rssiDbm = -45,
                        frequencyMhz = 5180,
                        centerFreq0Mhz = 5210,
                        channelWidthCode = 2,
                        wifiStandardCode = 8,
                        informationElements = listOf(ehtOpPuncturedCh44),
                    ),
                    RawScanEntry(
                        bssid = "20:4E:7F:88:88:01",
                        rawSsidBytes = "Split_80p80".encodeToByteArray(),
                        rssiDbm = -50,
                        frequencyMhz = 5260,
                        centerFreq0Mhz = 5290,
                        centerFreq1Mhz = 5775,
                        channelWidthCode = 4,
                        wifiStandardCode = 5,
                    ),
                ),
                receivedElapsedMs = 1_000L,
            ),
            currentElapsedMs = 1_000L,
        )

        val spec5 = engine.computeSpectrumAnalysis(snapshot.radios, WifiBand.BAND_5_GHZ, includeDfs = true)
        val ch36Score = spec5.channelScores.first { it.channel == 36 }
        val ch40Score = spec5.channelScores.first { it.channel == 40 }
        val ch44Score = spec5.channelScores.first { it.channel == 44 } // Punctured hole!
        val ch149Score = spec5.channelScores.first { it.channel == 149 } // Hit by 80+80 secondary lobe!

        assertEquals("Punctured 20 MHz hole on Ch 44 must have 0 interferers and score 100!", 100, ch44Score.score0To100)
        assertTrue(ch36Score.score0To100 < 70)
        assertTrue(ch40Score.score0To100 < 70)
        assertTrue("Secondary lobe of 80+80 MHz must penalize Ch 149", ch149Score.score0To100 < 75)
        assertEquals(1, ch149Score.aciCount)
    }

    @Test
    fun dfsToggleAnd6GhzRnrHarvestingOnGalaxyA55_behaveDeterministically() {
        val engine = DefaultRfAnalyticsEngine()
        // Crowd non-DFS 5 GHz (Ch 36..48 & Ch 149..165) while leaving DFS Ch 52..64 empty,
        // AND include a 5 GHz RNR IE advertising a 6 GHz AP on PSC Ch 5 (80 MHz, 5955..6035 MHz)!
        val rnrCh5 = RawInformationElement(
            id = 201,
            bytes = byteArrayOf(
                0x00, 0x07, 133.toByte(), 5,
                0x10, 0x04, 0x92.toByte(), 0x26, 0xAA.toByte(), 0xBB.toByte(), 0x6E,
            ),
        )
        val entries = listOf(
            RawScanEntry(
                bssid = "04:92:26:00:00:36",
                rawSsidBytes = "Crowd_UNII1".encodeToByteArray(),
                rssiDbm = -42,
                frequencyMhz = 5180,
                centerFreq0Mhz = 5210,
                channelWidthCode = 2,
                informationElements = listOf(rnrCh5),
            ),
            RawScanEntry(
                bssid = "50:C7:BF:00:01:49",
                rawSsidBytes = "Crowd_UNII3".encodeToByteArray(),
                rssiDbm = -43,
                frequencyMhz = 5745,
                centerFreq0Mhz = 5775,
                channelWidthCode = 2,
            ),
            RawScanEntry(
                bssid = "20:4E:7F:00:01:65",
                rawSsidBytes = "Crowd_Ch165".encodeToByteArray(),
                rssiDbm = -44,
                frequencyMhz = 5825,
                channelWidthCode = 0,
            ),
        )

        val a55Caps = DeviceRadioCapabilities(deviceModel = "SM-A556B", has6Ghz = false)
        val snap = engine.processScanSnapshot(
            rawBatch = RawScanBatch(entries, 1_000L),
            radioCapabilities = a55Caps,
            currentElapsedMs = 1_000L,
        )

        // When includeDfs = false, recommender MUST NOT suggest DFS channels 52..144
        val specNoDfs = engine.computeSpectrumAnalysis(snap.radios, WifiBand.BAND_5_GHZ, includeDfs = false, radioCapabilities = a55Caps)
        val recNoDfs = specNoDfs.recommendation!!
        assertFalse(recNoDfs.recommendedPrimaryChannel in 52..144)

        // When includeDfs = true, recommender unlocks empty DFS spectrum (e.g. Ch 52 @ 80 MHz, avoiding TDWR 120-128!)
        val specWithDfs = engine.computeSpectrumAnalysis(snap.radios, WifiBand.BAND_5_GHZ, includeDfs = true, radioCapabilities = a55Caps)
        val recWithDfs = specWithDfs.recommendation!!
        assertTrue(recWithDfs.recommendedPrimaryChannel in 52..144)
        assertFalse(recWithDfs.recommendedPrimaryChannel in setOf(120, 124, 128))
        assertEquals(100, recWithDfs.score0To100)

        // 6 GHz analysis on Galaxy A55 (has6Ghz = false) harvests RNR neighbor from 5 GHz radio,
        // explains hardware limit in plain English, AND recommends PSC Ch 21 instead of occupied PSC Ch 5!
        val spec6 = engine.computeSpectrumAnalysis(snap.radios, WifiBand.BAND_6_GHZ, radioCapabilities = a55Caps)
        assertFalse(spec6.isBandSupportedByHardware)
        assertNotNull(spec6.hardwareExplanationMessage)
        assertFalse(SemanticFormatters.containsRawEnumLeak(spec6.hardwareExplanationMessage!!))
        assertEquals(1, spec6.colocated6GhzNeighborsFromRnr.size)
        assertEquals(5, spec6.colocated6GhzNeighborsFromRnr[0].channelNumber)
        assertEquals(21, spec6.recommendation?.recommendedPrimaryChannel)
        assertSingleSentence(spec6.recommendation!!.plainEnglishReason)
    }

    private fun assertSingleSentence(reason: String) {
        val trimmed = reason.trim()
        assertTrue("Recommendation reason must end with '.': $trimmed", trimmed.endsWith("."))
        // Replace numeric decimals like "2.4" or "5.0" and verify no internal sentence break ". "
        val withoutDecimals = trimmed.replace(Regex("""(\d)\.(\d)"""), "$1_$2")
        val bodyWithoutFinalDot = withoutDecimals.dropLast(1)
        assertFalse(
            "Recommendation reason must be a single sentence, got: $trimmed",
            bodyWithoutFinalDot.contains(". ") || bodyWithoutFinalDot.contains("! ") || bodyWithoutFinalDot.contains("? "),
        )
    }
}
