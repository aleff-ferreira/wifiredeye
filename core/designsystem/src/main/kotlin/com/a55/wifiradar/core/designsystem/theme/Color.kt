package com.a55.wifiradar.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object RadarPalette {
    // True AMOLED Surfaces & Structural Borders
    val OledBlack = Color(0xFF000000)
    val CardSurfaceL1 = Color(0xFF0B101B)
    val ElevatedSurfaceL2 = Color(0xFF131C2E)
    val HairlineBorder = Color(0xFF1E293B)
    val ActiveHairlineBorder = Color(0xFF334155)
    val OnAccentDark = Color(0xFF020810)

    // WCAG 2.1 AAA (>= 7.46:1 across #000000, #0B101B, #131C2E & badge containers)
    val TextPrimary = Color(0xFFF8FAFC)      // >= 16.28:1
    val TextSecondary = Color(0xFFCBD5E1)    // >= 11.47:1
    val TextMuted = Color(0xFFA8B8CE)        // >= 8.44:1

    val ElectricEmerald = Color(0xFF00E676)  // >= 10.20:1 (Excellent >= -50 dBm / Connected / Ready)
    val LuminousCyan = Color(0xFF00E5FF)     // >= 11.07:1 (Strong -65..-51 dBm / Primary Accent)
    val WarmAmber = Color(0xFFFFB300)        // >= 9.49:1  (Fair -75..-66 dBm / Throttle / DFS Caution)
    val LuminousCoral = Color(0xFFFF8A80)    // >= 7.46:1  (Weak < -75 dBm / Open Security / Blocker)
    val SpectrumViolet = Color(0xFFC49BFF)   // >= 7.69:1  (6 GHz Wi-Fi 6E/7 / RNR / MLO)

    // Non-text graphical glow/stroke accent ONLY (WCAG 1.4.11 >= 3:1 graphical; NEVER use for text)
    val CoralGlowStroke = Color(0xFFFF5252)

    // Dedicated Opaque Badge Containers (Single-pass 120Hz draw + >= 8.09:1 AAA foreground contrast)
    val EmeraldBadgeBg = Color(0xFF052014)
    val EmeraldBadgeBorder = Color(0xFF0D5C38)

    val CyanBadgeBg = Color(0xFF041E26)
    val CyanBadgeBorder = Color(0xFF0B556B)

    val AmberBadgeBg = Color(0xFF221804)
    val AmberBadgeBorder = Color(0xFF664809)

    val CoralBadgeBg = Color(0xFF240D0B)
    val CoralBadgeBorder = Color(0xFF6E2924)

    val VioletBadgeBg = Color(0xFF1B1130)
    val VioletBadgeBorder = Color(0xFF4F328A)

    val NeutralBadgeBg = Color(0xFF0D1524)
    val NeutralBadgeBorder = Color(0xFF24324B)
}

enum class SemanticTone {
    EXCELLENT,
    GOOD,
    FAIR,
    WEAK,
    SPECTRUM_6GHZ,
    INFO,
    NEUTRAL,
}

@Immutable
data class ToneColorSpec(
    val foreground: Color,
    val container: Color,
    val border: Color,
    val onFilledButton: Color = RadarPalette.OnAccentDark,
)

@Immutable
data class RadarColors(
    val background: Color = RadarPalette.OledBlack,
    val surfaceL1: Color = RadarPalette.CardSurfaceL1,
    val surfaceL2: Color = RadarPalette.ElevatedSurfaceL2,
    val border: Color = RadarPalette.HairlineBorder,
    val activeBorder: Color = RadarPalette.ActiveHairlineBorder,
    val textPrimary: Color = RadarPalette.TextPrimary,
    val textSecondary: Color = RadarPalette.TextSecondary,
    val textMuted: Color = RadarPalette.TextMuted,
    val emerald: Color = RadarPalette.ElectricEmerald,
    val cyan: Color = RadarPalette.LuminousCyan,
    val amber: Color = RadarPalette.WarmAmber,
    val coral: Color = RadarPalette.LuminousCoral,
    val violet: Color = RadarPalette.SpectrumViolet,
    val onAccentDark: Color = RadarPalette.OnAccentDark,
) {
    fun toneSpec(tone: SemanticTone): ToneColorSpec = when (tone) {
        SemanticTone.EXCELLENT -> ToneColorSpec(
            foreground = emerald,
            container = RadarPalette.EmeraldBadgeBg,
            border = RadarPalette.EmeraldBadgeBorder,
        )
        SemanticTone.GOOD, SemanticTone.INFO -> ToneColorSpec(
            foreground = cyan,
            container = RadarPalette.CyanBadgeBg,
            border = RadarPalette.CyanBadgeBorder,
        )
        SemanticTone.FAIR -> ToneColorSpec(
            foreground = amber,
            container = RadarPalette.AmberBadgeBg,
            border = RadarPalette.AmberBadgeBorder,
        )
        SemanticTone.WEAK -> ToneColorSpec(
            foreground = coral,
            container = RadarPalette.CoralBadgeBg,
            border = RadarPalette.CoralBadgeBorder,
        )
        SemanticTone.SPECTRUM_6GHZ -> ToneColorSpec(
            foreground = violet,
            container = RadarPalette.VioletBadgeBg,
            border = RadarPalette.VioletBadgeBorder,
        )
        SemanticTone.NEUTRAL -> ToneColorSpec(
            foreground = textMuted,
            container = RadarPalette.NeutralBadgeBg,
            border = RadarPalette.NeutralBadgeBorder,
        )
    }
}

object ColorContrastMath {
    fun relativeLuminance(color: Color): Double {
        fun channelLinear(c: Float): Double {
            val v = c.toDouble().coerceIn(0.0, 1.0)
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = channelLinear(color.red)
        val g = channelLinear(color.green)
        val b = channelLinear(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val bright = max(l1, l2)
        val dark = min(l1, l2)
        return (bright + 0.05) / (dark + 0.05)
    }
}
