package com.a55.wifiradar.core.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticFormattersZeroLeakTest {

    private val screamingSnakeRegex = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")

    @Test
    fun allModelEnumsAndFormatters_neverLeakRawScreamingSnakeEnumNames() {
        val outputs = mutableListOf<String>()

        WifiBand.entries.forEach { outputs += SemanticFormatters.formatBandWithTrait(it) }
        ChannelWidth.entries.forEach { outputs += SemanticFormatters.formatChannelWidthWithTrait(it) }
        WifiGeneration.entries.forEach { outputs += SemanticFormatters.formatWifiGeneration(it) }
        SignalQualityTier.entries.forEach { outputs += SemanticFormatters.formatQualityPct(80, it) }
        SecurityGrade.entries.forEach { grade ->
            SecurityProtocol.entries.forEach { proto ->
                outputs += SemanticFormatters.formatSecuritySummary(
                    SecurityAssessment(
                        grade = grade,
                        score0To100 = 80,
                        protocol = proto,
                        securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
                        plainEnglishLabel = proto.plainEnglishSummary,
                    )
                )
            }
        }
        PmfState.entries.forEach {
            outputs += it.displayLabel
            outputs += it.plainEnglishNote
        }
        VelocityTrend.entries.forEach { outputs += SemanticFormatters.formatVelocityTrend(it, 1.8) }
        PropagationEnvironment.entries.forEach {
            outputs += it.displayLabel
            outputs += it.shortDescription
        }
        DistanceMethod.entries.forEach {
            outputs += SemanticFormatters.formatDistanceRange(3.2, 2.3, 4.4, 0.4, it)
        }
        RadarDestination.entries.forEach { outputs += it.tabTitle }
        NetworkViewMode.entries.forEach { outputs += it.displayLabel }
        NetworkFilterChip.entries.forEach { outputs += it.displayLabel }
        NetworkSortOption.entries.forEach { outputs += it.displayLabel }

        for (text in outputs) {
            assertFalse("Raw enum leak detected in '$text'", SemanticFormatters.containsRawEnumLeak(text))
            assertFalse("SCREAMING_SNAKE_CASE token detected in '$text'", screamingSnakeRegex.containsMatchIn(text))
        }
    }

    @Test
    fun legendTopics_coverAll14CanonicalTopicsWithTwoSentencePlainEnglishExplanations() {
        assertEquals(14, LegendTopic.entries.size)
        for (topic in LegendTopic.entries) {
            assertTrue("topicId blank on $topic", topic.topicId.isNotBlank())
            assertTrue("shortChipLabel blank on $topic", topic.shortChipLabel.isNotBlank())
            assertTrue("title blank on $topic", topic.title.isNotBlank())
            assertTrue(
                "Expected explanation for $topic to end with '.'",
                topic.plainEnglishExplanation.endsWith(".")
            )
            val sentences = topic.plainEnglishExplanation.split(". ")
            assertEquals(
                "Expected exact 2-sentence plain-English explanation for $topic, got: ${topic.plainEnglishExplanation}",
                2,
                sentences.size,
            )
            assertFalse(
                "Raw enum leak in LegendTopic $topic",
                SemanticFormatters.containsRawEnumLeak(topic.plainEnglishExplanation)
            )
        }
    }

    @Test
    fun boundaryThresholdsAndFormatting_matchSpecification() {
        assertEquals(0, SemanticFormatters.signalQualityPct(-100))
        assertEquals(50, SemanticFormatters.signalQualityPct(-75))
        assertEquals(100, SemanticFormatters.signalQualityPct(-50))
        assertEquals(100, SemanticFormatters.signalQualityPct(-20))

        assertTrue(SemanticFormatters.formatRssiForDiscovery(-45).contains("Excellent"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-50).contains("Excellent"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-51).contains("Strong"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-65).contains("Strong"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-66).contains("Fair"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-75).contains("Fair"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-76).contains("Weak"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-85).contains("Weak"))
        assertTrue(SemanticFormatters.formatRssiForDiscovery(-92).contains("Very Weak"))
        assertEquals("— dBm · Signal Lost", SemanticFormatters.formatRssiForProximity(null))

        assertEquals("Rock Solid", SemanticFormatters.stabilityQualifier(1.1))
        assertEquals("Steady", SemanticFormatters.stabilityQualifier(3.0))
        assertEquals("Fluctuating", SemanticFormatters.stabilityQualifier(5.8))

        assertEquals("< 0.5 m · Right Next to Router", SemanticFormatters.formatDistanceRange(0.2, 0.1, 0.4))
        assertEquals("> 50 m · Far Edge of Reach", SemanticFormatters.formatDistanceRange(65.0, 52.0, 80.0))
        assertTrue(
            SemanticFormatters.formatDistanceRange(
                meters = 3.4,
                rangeMinMeters = 3.0,
                rangeMaxMeters = 3.8,
                stdDevMeters = 0.4,
                method = DistanceMethod.RTT_80211MC,
            ).contains("3.4 m ± 0.4 m")
        )

        assertEquals(
            "Signal Lost · Last seen 12s ago at -86 dBm",
            SemanticFormatters.formatDropoutBanner(12_400L, -86)
        )
        assertEquals("Waiting for scan data", SemanticFormatters.formatHealthScoreHeadline(null))
        assertFalse(SemanticFormatters.formatHealthScoreHeadline(null).contains("NaN"))
        assertTrue(SemanticFormatters.formatHealthScoreHeadline(84).contains("84 / 100"))
    }

    @Test
    fun decimalFormatting_remainsDotSeparatedUnderCommaDecimalLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val stability = SemanticFormatters.formatStability(94, 1.1)
            val distance = SemanticFormatters.formatDistanceRange(3.2, 2.4, 4.3)
            val velocity = SemanticFormatters.formatVelocityTrend(VelocityTrend.APPROACHING, 1.8)
            val score = SemanticFormatters.formatChannelScore(90, 4.5)

            assertEquals("94% · Rock Solid (±1.1 dB)", stability)
            assertEquals("Est. ~2.4 – 4.3 m", distance)
            assertEquals("Approaching ↑ (+1.8 dB/s)", velocity)
            assertEquals("90 / 100 · Wide Open (4.5★)", score)
        } finally {
            Locale.setDefault(original)
        }
    }
}
