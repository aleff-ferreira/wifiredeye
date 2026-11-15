package com.a55.wifiradar.feature.proximity.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a55.wifiradar.core.designsystem.canvas.animateRadarFloatAsState
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.canvas.rememberRadarStrokeCache
import com.a55.wifiradar.core.designsystem.component.InlineLegendPill
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarFontFeatures
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.feature.proximity.ProximityTestTags
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val MAJOR_TICK_DBM_VALUES = intArrayOf(-100, -90, -80, -70, -65, -60, -50, -40, -30)

internal fun rssiToGaugeFraction(rssiDbm: Double): Float =
    ((rssiDbm - (-100.0)) / 70.0).toFloat().coerceIn(0f, 1f)

@Composable
internal fun ProximitySignalLostBanner(
    telemetry: ProximityTelemetry,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    RadarElevatedCard(
        modifier = modifier.testTag(ProximityTestTags.SIGNAL_LOST_BANNER),
        accentTone = SemanticTone.FAIR,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RadarIcons.Warning,
                contentDescription = null,
                tint = colors.amber,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = SemanticFormatters.formatDropoutBanner(
                    lastSeenAgeMs = telemetry.lastSeenAgeMs,
                    lastKnownRssiDbm = telemetry.lastKnownRssiDbm,
                ),
                style = RadarTheme.typography.metricMedium,
                color = colors.amber,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Target lock preserved — proximity meter automatically re-acquires as soon as a beacon returns without resetting Min, Max, or Avg history.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProximitySonarGaugeCard(
    telemetry: ProximityTelemetry?,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val density = LocalDensity.current
    val strokes = rememberRadarStrokeCache()

    val isSignalLost = telemetry == null ||
        telemetry.lockStatus == SignalLockStatus.SIGNAL_LOST ||
        telemetry.lockStatus == SignalLockStatus.SEARCHING ||
        telemetry.smoothedRssiDbm == null

    val smoothedDbm = if (isSignalLost) null else telemetry.smoothedRssiDbm
    val targetFraction = smoothedDbm?.let(::rssiToGaugeFraction) ?: 0f
    val peakDbm = telemetry?.peakHoldRssiDbm ?: telemetry?.maxRssiDbm?.toDouble()
    val peakFraction = peakDbm?.let(::rssiToGaugeFraction) ?: 0f

    val animatedGaugeFraction = animateRadarFloatAsState(
        targetValue = targetFraction,
        label = "proximitySonarSweep",
    )
    val animatedPeakFraction = animateRadarFloatAsState(
        targetValue = peakFraction,
        label = "proximityPeakSweep",
    )

    val activeTone = if (isSignalLost) {
        if (telemetry == null) SemanticTone.NEUTRAL else SemanticTone.FAIR
    } else {
        telemetry.signalTier.toSemanticTone()
    }
    val activeToneSpec = colors.toneSpec(activeTone)
    val activeArcColor = activeToneSpec.foreground

    val formattedProximityState = SemanticFormatters.formatRssiForProximity(smoothedDbm)
    val gaugeContentDescription = buildString {
        append("Signal proximity sonar gauge for ")
        append(telemetry?.displayTitle ?: "target router")
        append(": ")
        append(formattedProximityState)
    }
    val gaugeStateDescription = if (isSignalLost && telemetry != null) {
        SemanticFormatters.formatDropoutBanner(telemetry.lastSeenAgeMs, telemetry.lastKnownRssiDbm)
    } else {
        formattedProximityState
    }

    val dialSize = if (density.fontScale > 1.4f) 268.dp else 238.dp
    // Cap only in-ring hero font growth at 1.35x so text inside the circular dial never collides with the 270° arc.
    val inDialHeroFontSize = with(density) {
        val cappedSp = 40f * fontScale.coerceAtMost(1.35f)
        (cappedSp / fontScale).sp
    }
    val inDialHeroLineHeight = with(density) {
        val cappedSp = 44f * fontScale.coerceAtMost(1.35f)
        (cappedSp / fontScale).sp
    }
    val inDialSubFontSize = with(density) {
        val cappedSp = 12f * fontScale.coerceAtMost(1.30f)
        (cappedSp / fontScale).sp
    }
    val inDialSubLineHeight = with(density) {
        val cappedSp = 16f * fontScale.coerceAtMost(1.30f)
        (cappedSp / fontScale).sp
    }

    RadarCard(
        modifier = modifier,
        accentTone = activeTone,
        highlighted = !isSignalLost,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Outer Box keeps leaf SONAR_GAUGE canvas (mergeDescendants = true) as a sibling of HERO_DBM_TEXT!
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(dialSize)
                        .radarCanvasSemantics(
                            testTag = ProximityTestTags.SONAR_GAUGE,
                            contentDescription = gaugeContentDescription,
                            stateDescription = gaugeStateDescription,
                        )
                        .drawWithCache {
                            val center = Offset(size.width * 0.5f, size.height * 0.53f)
                            val arcRadius = (size.minDimension * 0.42f).coerceAtLeast(1f)
                            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                            val sonarRing1 = arcRadius * 0.30f
                            val sonarRing2 = arcRadius * 0.54f
                            val sonarRing3 = arcRadius * 0.78f

                            val tickInnerMajor = arcRadius - 12.dp.toPx()
                            val tickOuterMajor = arcRadius - 4.dp.toPx()
                            val tickSegments = FloatArray(MAJOR_TICK_DBM_VALUES.size * 4)
                            for (i in MAJOR_TICK_DBM_VALUES.indices) {
                                val frac = rssiToGaugeFraction(MAJOR_TICK_DBM_VALUES[i].toDouble())
                                val rad = Math.toRadians(135.0 + 270.0 * frac)
                                val c = cos(rad).toFloat()
                                val s = sin(rad).toFloat()
                                val base = i * 4
                                tickSegments[base] = center.x + tickInnerMajor * c
                                tickSegments[base + 1] = center.y + tickInnerMajor * s
                                tickSegments[base + 2] = center.x + tickOuterMajor * c
                                tickSegments[base + 3] = center.y + tickOuterMajor * s
                            }

                            val centerGlowBrush = Brush.radialGradient(
                                colors = listOf(
                                    activeArcColor.copy(alpha = 0.24f),
                                    activeArcColor.copy(alpha = 0.06f),
                                    Color.Transparent,
                                ),
                                center = center,
                                radius = (arcRadius * 0.98f).coerceAtLeast(1f),
                            )

                            val needleStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                            val peakTickStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                            val tickMarkStrokeWidth = 1.6.dp.toPx()
                            val peakInnerRadius = arcRadius - 9.dp.toPx()
                            val peakOuterRadius = arcRadius + 9.dp.toPx()
                            val needleStartRadius = arcRadius * 0.56f
                            val beaconHaloRadius = 10.dp.toPx()
                            val beaconCoreRadius = 5.5.dp.toPx()
                            val peakDotRadius = 3.8.dp.toPx()
                            val crosshairExtent = arcRadius * 0.80f

                            onDrawBehind {
                                val liveFrac = animatedGaugeFraction.value.coerceIn(0f, 1f)
                                val glowScale = 0.72f + 0.28f * liveFrac
                                drawCircle(
                                    brush = centerGlowBrush,
                                    radius = arcRadius * glowScale,
                                    center = center,
                                )

                                // 3 concentric sonar rings + subtle radar crosshairs
                                drawCircle(
                                    color = colors.border,
                                    radius = sonarRing1,
                                    center = center,
                                    style = strokes.gridHairline,
                                )
                                drawCircle(
                                    color = colors.border,
                                    radius = sonarRing2,
                                    center = center,
                                    style = strokes.gridHairline,
                                )
                                drawCircle(
                                    color = colors.activeBorder,
                                    radius = sonarRing3,
                                    center = center,
                                    style = strokes.gridHairline,
                                )
                                drawLine(
                                    color = colors.border,
                                    start = Offset(center.x - crosshairExtent, center.y),
                                    end = Offset(center.x + crosshairExtent, center.y),
                                    strokeWidth = strokes.gridHairline.width,
                                )
                                drawLine(
                                    color = colors.border,
                                    start = Offset(center.x, center.y - crosshairExtent),
                                    end = Offset(center.x, center.y + crosshairExtent * 0.65f),
                                    strokeWidth = strokes.gridHairline.width,
                                )

                                // 9 pre-computed major dBm tick marks (-100..-30 dBm)
                                var idx = 0
                                while (idx < tickSegments.size) {
                                    drawLine(
                                        color = colors.activeBorder,
                                        start = Offset(tickSegments[idx], tickSegments[idx + 1]),
                                        end = Offset(tickSegments[idx + 2], tickSegments[idx + 3]),
                                        strokeWidth = tickMarkStrokeWidth,
                                        cap = StrokeCap.Round,
                                    )
                                    idx += 4
                                }

                                // 270° track arc
                                drawArc(
                                    color = colors.border,
                                    startAngle = 135f,
                                    sweepAngle = 270f,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = strokes.gaugeTrack,
                                )

                                // Active luminous sweep arc
                                if (liveFrac > 0.002f) {
                                    drawArc(
                                        color = activeArcColor,
                                        startAngle = 135f,
                                        sweepAngle = 270f * liveFrac,
                                        useCenter = false,
                                        topLeft = arcTopLeft,
                                        size = arcSize,
                                        style = strokes.gaugeArc,
                                    )
                                }

                                // Peak-hold tick & luminous marker
                                if (peakDbm != null) {
                                    val pFrac = animatedPeakFraction.value.coerceIn(0f, 1f)
                                    val pRad = Math.toRadians(135.0 + 270.0 * pFrac)
                                    val pc = cos(pRad).toFloat()
                                    val ps = sin(pRad).toFloat()
                                    val pStart = Offset(center.x + peakInnerRadius * pc, center.y + peakInnerRadius * ps)
                                    val pEnd = Offset(center.x + peakOuterRadius * pc, center.y + peakOuterRadius * ps)
                                    drawLine(
                                        color = colors.cyan,
                                        start = pStart,
                                        end = pEnd,
                                        strokeWidth = peakTickStroke.width,
                                        cap = StrokeCap.Round,
                                    )
                                    drawCircle(
                                        color = colors.textPrimary,
                                        radius = peakDotRadius,
                                        center = pEnd,
                                    )
                                }

                                // Spring radial needle + glowing beacon tip when locked
                                if (!isSignalLost) {
                                    val nRad = Math.toRadians(135.0 + 270.0 * liveFrac)
                                    val nc = cos(nRad).toFloat()
                                    val ns = sin(nRad).toFloat()
                                    val needleStart = Offset(
                                        center.x + needleStartRadius * nc,
                                        center.y + needleStartRadius * ns,
                                    )
                                    val needleTip = Offset(
                                        center.x + arcRadius * nc,
                                        center.y + arcRadius * ns,
                                    )
                                    drawLine(
                                        color = colors.textPrimary,
                                        start = needleStart,
                                        end = needleTip,
                                        strokeWidth = needleStroke.width,
                                        cap = StrokeCap.Round,
                                    )
                                    drawCircle(
                                        color = activeArcColor.copy(alpha = 0.35f),
                                        radius = beaconHaloRadius,
                                        center = needleTip,
                                    )
                                    drawCircle(
                                        color = colors.textPrimary,
                                        radius = beaconCoreRadius,
                                        center = needleTip,
                                    )
                                }
                            }
                        },
                )

                // Sibling center readout (unmerged with SONAR_GAUGE so HERO_DBM_TEXT is directly queryable)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val heroText = smoothedDbm?.let { "${it.roundToInt()} dBm" } ?: "— dBm"
                    Text(
                        text = heroText,
                        style = RadarTheme.typography.heroDbm.copy(
                            fontSize = inDialHeroFontSize,
                            lineHeight = inDialHeroLineHeight,
                            fontWeight = FontWeight.ExtraBold,
                            fontFeatureSettings = RadarFontFeatures.TABULAR_NUMERALS,
                        ),
                        color = if (isSignalLost) colors.amber else colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.testTag(ProximityTestTags.HERO_DBM_TEXT),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val subCaption = when {
                        telemetry == null -> "Awaiting Target"
                        isSignalLost -> "Standby · Signal Lost"
                        telemetry.isConnectedFastPath -> "Kalman · 300ms Live"
                        else -> telemetry.lockStatus.displayLabel
                    }
                    Text(
                        text = subCaption,
                        style = RadarTheme.typography.badgeLabel.copy(
                            fontSize = inDialSubFontSize,
                            lineHeight = inDialSubLineHeight,
                        ),
                        color = activeArcColor,
                        maxLines = 1,
                    )
                }
            }

            // Scale reference legend row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "-100 dBm · Faint",
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textMuted,
                )
                Text(
                    text = "-65 dBm · Strong",
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textMuted,
                )
                Text(
                    text = "-30 dBm · Beside AP",
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textMuted,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Full uncapped telemetry pills + Kalman legend pill
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tier = telemetry?.signalTier ?: SignalQualityTier.DEAD_ZONE
                val zoneText = if (isSignalLost) {
                    "Signal Lost · Standby"
                } else {
                    "${tier.shortLabel} · ${tier.proximityPhrase}"
                }
                GaugeTelemetryPill(
                    text = zoneText,
                    tone = activeTone,
                    testTag = ProximityTestTags.ZONE_PILL,
                )

                val rawText = when {
                    !isSignalLost && telemetry.rawRssiDbm != null -> "Raw: ${telemetry.rawRssiDbm} dBm"
                    telemetry?.lastKnownRssiDbm != null -> "Last: ${telemetry.lastKnownRssiDbm} dBm"
                    else -> "Raw: —"
                }
                GaugeTelemetryPill(
                    text = rawText,
                    tone = SemanticTone.NEUTRAL,
                    testTag = ProximityTestTags.RAW_DBM_TEXT,
                )

                val qualityText = if (isSignalLost) {
                    "Quality: 0% · Standby"
                } else {
                    "Quality: ${SemanticFormatters.formatQualityPct(telemetry.signalQualityPct, tier)}"
                }
                GaugeTelemetryPill(
                    text = qualityText,
                    tone = activeTone,
                    testTag = ProximityTestTags.QUALITY_PCT_TEXT,
                )

                if (peakDbm != null) {
                    GaugeTelemetryPill(
                        text = "Peak: ${peakDbm.roundToInt()} dBm",
                        tone = SemanticTone.GOOD,
                        testTag = ProximityTestTags.PEAK_DBM_TEXT,
                    )
                }

                InlineLegendPill(topic = LegendTopic.KALMAN_SMOOTHING)
            }
        }
    }
}

@Composable
private fun GaugeTelemetryPill(
    text: String,
    tone: SemanticTone,
    testTag: String,
) {
    val spec = RadarTheme.colors.toneSpec(tone)
    Surface(
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .testTag(testTag),
        shape = RoundedCornerShape(10.dp),
        color = spec.container,
        border = BorderStroke(1.dp, spec.border),
    ) {
        Text(
            text = text,
            style = RadarTheme.typography.badgeLabel,
            color = spec.foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
