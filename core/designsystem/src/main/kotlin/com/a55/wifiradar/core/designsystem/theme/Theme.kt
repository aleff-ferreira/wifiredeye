package com.a55.wifiradar.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val AmoledMaterialDarkColorScheme = darkColorScheme(
    primary = RadarPalette.LuminousCyan,
    onPrimary = RadarPalette.OnAccentDark,
    primaryContainer = RadarPalette.CyanBadgeBg,
    onPrimaryContainer = RadarPalette.LuminousCyan,
    secondary = RadarPalette.ElectricEmerald,
    onSecondary = RadarPalette.OnAccentDark,
    secondaryContainer = RadarPalette.EmeraldBadgeBg,
    onSecondaryContainer = RadarPalette.ElectricEmerald,
    tertiary = RadarPalette.SpectrumViolet,
    onTertiary = RadarPalette.OnAccentDark,
    tertiaryContainer = RadarPalette.VioletBadgeBg,
    onTertiaryContainer = RadarPalette.SpectrumViolet,
    error = RadarPalette.LuminousCoral,
    onError = RadarPalette.OnAccentDark,
    errorContainer = RadarPalette.CoralBadgeBg,
    onErrorContainer = RadarPalette.LuminousCoral,
    background = RadarPalette.OledBlack,
    onBackground = RadarPalette.TextPrimary,
    surface = RadarPalette.CardSurfaceL1,
    onSurface = RadarPalette.TextPrimary,
    surfaceVariant = RadarPalette.ElevatedSurfaceL2,
    onSurfaceVariant = RadarPalette.TextSecondary,
    surfaceContainerLowest = RadarPalette.OledBlack,
    surfaceContainerLow = RadarPalette.CardSurfaceL1,
    surfaceContainer = RadarPalette.CardSurfaceL1,
    surfaceContainerHigh = RadarPalette.ElevatedSurfaceL2,
    surfaceContainerHighest = RadarPalette.ElevatedSurfaceL2,
    outline = RadarPalette.HairlineBorder,
    outlineVariant = RadarPalette.ActiveHairlineBorder,
)

val LocalRadarColors = staticCompositionLocalOf { RadarColors() }
val LocalRadarTypography = staticCompositionLocalOf { DefaultRadarExtendedTypography }
val LocalRadarDimens = staticCompositionLocalOf { RadarDimens() }

object RadarTheme {
    val colors: RadarColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRadarColors.current

    val typography: RadarExtendedTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalRadarTypography.current

    val dimens: RadarDimens
        @Composable
        @ReadOnlyComposable
        get() = LocalRadarDimens.current
}

@Composable
fun WifiRadarTheme(
    content: @Composable () -> Unit,
) {
    val radarColors = RadarColors()
    val radarTypography = DefaultRadarExtendedTypography
    val radarDimens = RadarDimens()

    CompositionLocalProvider(
        LocalRadarColors provides radarColors,
        LocalRadarTypography provides radarTypography,
        LocalRadarDimens provides radarDimens,
    ) {
        MaterialTheme(
            colorScheme = AmoledMaterialDarkColorScheme,
            typography = RadarMaterialTypography,
            content = content,
        )
    }
}
