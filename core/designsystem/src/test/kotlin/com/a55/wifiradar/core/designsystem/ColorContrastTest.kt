package com.a55.wifiradar.core.designsystem

import androidx.compose.ui.graphics.Color
import com.a55.wifiradar.core.designsystem.component.RadarSemanticGuard
import com.a55.wifiradar.core.designsystem.component.RadarSemanticQualifiers
import com.a55.wifiradar.core.designsystem.theme.ColorContrastMath
import com.a55.wifiradar.core.designsystem.theme.RadarColors
import com.a55.wifiradar.core.designsystem.theme.RadarFontFeatures
import com.a55.wifiradar.core.designsystem.theme.RadarMaterialTypography
import com.a55.wifiradar.core.designsystem.theme.RadarPalette
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.LegendTopic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {

    @Test
    fun amoledSurfaceTokens_matchExactGalaxyA55Spec() {
        assertEquals(Color(0xFF000000), RadarPalette.OledBlack)
        assertEquals(0.0, ColorContrastMath.relativeLuminance(RadarPalette.OledBlack), 1e-9)
        assertEquals(Color(0xFF0B101B), RadarPalette.CardSurfaceL1)
        assertEquals(Color(0xFF131C2E), RadarPalette.ElevatedSurfaceL2)
        assertEquals(Color(0xFF1E293B), RadarPalette.HairlineBorder)
    }

    @Test
    fun allForegroundTokens_exceedWcagAaa7To1AcrossAllAmoledSurfacesAndBadgeContainers() {
        val surfaces = listOf(
            "OledBlack (#000000)" to RadarPalette.OledBlack,
            "CardSurfaceL1 (#0B101B)" to RadarPalette.CardSurfaceL1,
            "ElevatedSurfaceL2 (#131C2E)" to RadarPalette.ElevatedSurfaceL2,
        )
        val foregrounds = listOf(
            "TextPrimary" to RadarPalette.TextPrimary,
            "TextSecondary" to RadarPalette.TextSecondary,
            "TextMuted" to RadarPalette.TextMuted,
            "ElectricEmerald" to RadarPalette.ElectricEmerald,
            "LuminousCyan" to RadarPalette.LuminousCyan,
            "WarmAmber" to RadarPalette.WarmAmber,
            "LuminousCoral" to RadarPalette.LuminousCoral,
            "SpectrumViolet" to RadarPalette.SpectrumViolet,
        )

        for ((fgName, fgColor) in foregrounds) {
            for ((bgName, bgColor) in surfaces) {
                val ratio = ColorContrastMath.contrastRatio(fgColor, bgColor)
                assertTrue(
                    "Expected WCAG AAA >= 7.0:1 for $fgName on $bgName, got $ratio",
                    ratio >= 7.0
                )
            }
        }

        val colors = RadarColors()
        for (tone in SemanticTone.entries) {
            val spec = colors.toneSpec(tone)
            val ratio = ColorContrastMath.contrastRatio(spec.foreground, spec.container)
            assertTrue(
                "Expected WCAG AAA >= 7.0:1 for tone $tone foreground on paired container, got $ratio",
                ratio >= 7.0
            )
        }
    }

    @Test
    fun filledCtaButtons_exceedWcagAaa7To1WithOnAccentDarkInk() {
        val buttonFills = listOf(
            "ElectricEmerald" to RadarPalette.ElectricEmerald,
            "LuminousCyan" to RadarPalette.LuminousCyan,
            "WarmAmber" to RadarPalette.WarmAmber,
            "LuminousCoral" to RadarPalette.LuminousCoral,
            "SpectrumViolet" to RadarPalette.SpectrumViolet,
        )
        for ((name, fill) in buttonFills) {
            val ratio = ColorContrastMath.contrastRatio(RadarPalette.OnAccentDark, fill)
            assertTrue(
                "Expected WCAG AAA >= 7.0:1 for OnAccentDark on $name CTA fill, got $ratio",
                ratio >= 7.0
            )
        }
    }

    @Test
    fun tabularNumeralsAndSemanticQualifiers_haveZeroRawEnumLeaks() {
        assertEquals(RadarFontFeatures.TABULAR_NUMERALS, RadarMaterialTypography.displayLarge.fontFeatureSettings)
        assertEquals(RadarFontFeatures.TABULAR_NUMERALS, RadarMaterialTypography.titleMedium.fontFeatureSettings)
        assertEquals(RadarFontFeatures.TABULAR_NUMERALS, RadarMaterialTypography.labelLarge.fontFeatureSettings)

        val rssiValues = listOf(-35, -50, -51, -65, -66, -75, -76, -85, -95)
        for (rssi in rssiValues) {
            val (qualifier, _) = RadarSemanticQualifiers.signalSpec(rssi)
            assertFalse("Enum leak in '$qualifier'", RadarSemanticGuard.containsRawEnumLeak(qualifier))
        }

        for (freq in listOf(2412, 5180, 5975, 60480)) {
            val (band, trait, _) = RadarSemanticQualifiers.bandSpec(freq)
            assertFalse(RadarSemanticGuard.containsRawEnumLeak("$band · $trait"))
        }

        for (width in listOf(20, 40, 80, 160, 320)) {
            assertFalse(RadarSemanticGuard.containsRawEnumLeak(RadarSemanticQualifiers.widthQualifier(width)))
        }
        assertFalse(RadarSemanticGuard.containsRawEnumLeak(RadarSemanticQualifiers.widthQualifier(80, is80Plus80 = true)))

        for (grade in listOf("A+", "A", "A-", "B", "C", "D", "F")) {
            val (summary, _) = RadarSemanticQualifiers.securitySpec(grade)
            assertFalse(RadarSemanticGuard.containsRawEnumLeak(summary))
        }

        for (stdDev in listOf(0.9, 3.2, 6.4)) {
            val (stability, _) = RadarSemanticQualifiers.stabilitySpec(stdDev)
            assertFalse(RadarSemanticGuard.containsRawEnumLeak(stability))
        }

        assertTrue(RadarSemanticGuard.containsRawEnumLeak("Mode: WIFI_STANDARD_11AX"))
        assertTrue(RadarSemanticGuard.containsRawEnumLeak("Auth: SECURITY_TYPE_SAE"))

        assertEquals(14, LegendTopic.entries.size)
        for (topic in LegendTopic.entries) {
            assertEquals(
                "Expected 2 plain-English sentences for $topic",
                2,
                topic.plainEnglishExplanation.split(". ").size
            )
        }
    }
}
