package com.a55.wifiradar.feature.proximity.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.canvas.drawRadarBackdropGrid
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.canvas.rememberRadarStrokeCache
import com.a55.wifiradar.core.designsystem.canvas.rememberReusableRadarPathSet
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.feature.proximity.ProximityTestTags
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProximitySparklineCard(
    telemetry: ProximityTelemetry?,
    onResetStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val density = LocalDensity.current
    val strokes = rememberRadarStrokeCache()
    val paths = rememberReusableRadarPathSet()

    val points = remember(telemetry?.sparkline) {
        telemetry?.sparkline?.takeLast(60).orEmpty()
    }
    val isSignalLost = telemetry?.lockStatus == SignalLockStatus.SIGNAL_LOST
    val tone = when {
        isSignalLost -> SemanticTone.FAIR
        telemetry != null -> telemetry.signalTier.toSemanticTone()
        else -> SemanticTone.GOOD
    }
    val toneColor = colors.toneSpec(tone).foreground

    val minText = telemetry?.minRssiDbm?.let { "$it dBm" } ?: "—"
    val maxText = telemetry?.maxRssiDbm?.let { "$it dBm" } ?: "—"
    val avgText = telemetry?.avgRssiDbm?.let { String.format(Locale.US, "%.1f dBm", it) } ?: "—"
    val stabilityText = if (telemetry != null && telemetry.sampleCount > 0) {
        SemanticFormatters.formatStability(telemetry.stabilityPct, telemetry.stdDevDb)
    } else {
        "—"
    }

    val sparklineContentDesc = buildString {
        append("60-sample signal history sparkline: ")
        if (points.isEmpty()) {
            append("Collecting live signal samples")
        } else {
            append("${points.size} samples, minimum $minText, maximum $maxText, stability $stabilityText")
        }
    }
    val sparklineStateDesc = if (isSignalLost) {
        "Signal dropout gap recorded · Min $minText · Max $maxText"
    } else if (telemetry != null && telemetry.sampleCount > 0) {
        "Live · Min $minText · Max $maxText · $stabilityText"
    } else {
        "Waiting for samples"
    }

    RadarCard(
        modifier = modifier,
        accentTone = tone,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Text(
                    text = "Signal History & Stability (60 Samples)",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.textPrimary,
                )
                val gapCount = points.count { it.isDropoutGap }
                Text(
                    text = if (gapCount > 0 || isSignalLost) {
                        "Solid curve = live signal · Dashed amber = signal dropout gap"
                    } else {
                        "Smoothed dBm trace (-100 to -30 dBm)"
                    },
                    style = RadarTheme.typography.badgeLabel,
                    color = if (gapCount > 0 || isSignalLost) colors.amber else colors.textMuted,
                )
            }

            OutlinedButton(
                onClick = onResetStats,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(ProximityTestTags.RESET_STATS_BUTTON),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surfaceL2,
                    contentColor = colors.textPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Refresh,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Reset Stats",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 120Hz Zero-Allocation Sparkline Canvas (leaf node for radarCanvasSemantics)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(136.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.background)
                .drawRadarBackdropGrid(
                    horizontalDivisions = 4,
                    verticalDivisions = 6,
                    gridColor = colors.border,
                    glowAccentColor = toneColor,
                )
                .radarCanvasSemantics(
                    testTag = ProximityTestTags.SPARKLINE_CANVAS,
                    contentDescription = sparklineContentDesc,
                    stateDescription = sparklineStateDesc,
                )
                .drawWithCache {
                    val padH = 10.dp.toPx()
                    val padV = 12.dp.toPx()
                    val leftX = padH
                    val rightX = (size.width - padH).coerceAtLeast(leftX + 1f)
                    val topY = padV
                    val bottomY = (size.height - padV).coerceAtLeast(topY + 1f)
                    val plotW = (rightX - leftX).coerceAtLeast(1f)
                    val plotH = (bottomY - topY).coerceAtLeast(1f)
                    val dotRadius = 3.6.dp.toPx()
                    val dotHaloRadius = 7.dp.toPx()
                    val fillBrush = Brush.verticalGradient(
                        colors = listOf(
                            toneColor.copy(alpha = 0.28f),
                            toneColor.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                        startY = topY,
                        endY = bottomY,
                    )

                    onDrawBehind {
                        paths.resetAll()
                        val count = points.size
                        if (count == 0) return@onDrawBehind

                        fun dbmToY(dbm: Double): Float {
                            val norm = ((dbm - (-100.0)) / 70.0).toFloat().coerceIn(0f, 1f)
                            return bottomY - norm * plotH
                        }

                        var inSolidRun = false
                        var runStartX = leftX
                        var lastRunX = leftX
                        var hasLastValid = false
                        var lastValidX = leftX
                        var lastValidY = bottomY
                        var sawGapSinceLastValid = false

                        for (idx in 0 until count) {
                            val pt = points[idx]
                            val x = if (count == 1) {
                                rightX
                            } else {
                                leftX + (plotW * idx.toFloat()) / (count - 1).toFloat()
                            }
                            val dbm = pt.emaRssiDbm ?: pt.smoothedRssiDbm ?: pt.rawRssiDbm?.toDouble()
                            val isGap = pt.isDropoutGap || dbm == null

                            if (isGap) {
                                if (inSolidRun) {
                                    paths.fillPath.lineTo(lastRunX, bottomY)
                                    paths.fillPath.lineTo(runStartX, bottomY)
                                    paths.fillPath.close()
                                    inSolidRun = false
                                }
                                if (hasLastValid) {
                                    sawGapSinceLastValid = true
                                }
                            } else {
                                val y = dbmToY(dbm)
                                if (count == 1) {
                                    paths.primaryPath.moveTo(leftX, y)
                                    paths.primaryPath.lineTo(rightX, y)
                                    paths.fillPath.moveTo(leftX, bottomY)
                                    paths.fillPath.lineTo(leftX, y)
                                    paths.fillPath.lineTo(rightX, y)
                                    paths.fillPath.lineTo(rightX, bottomY)
                                    paths.fillPath.close()
                                } else if (!inSolidRun) {
                                    if (hasLastValid && sawGapSinceLastValid) {
                                        paths.secondaryPath.moveTo(lastValidX, lastValidY)
                                        paths.secondaryPath.lineTo(x, y)
                                    }
                                    paths.primaryPath.moveTo(x, y)
                                    paths.fillPath.moveTo(x, y)
                                    runStartX = x
                                    lastRunX = x
                                    inSolidRun = true
                                } else {
                                    paths.primaryPath.lineTo(x, y)
                                    paths.fillPath.lineTo(x, y)
                                    lastRunX = x
                                }
                                hasLastValid = true
                                lastValidX = x
                                lastValidY = y
                                sawGapSinceLastValid = false
                            }
                        }

                        if (inSolidRun && count > 1) {
                            paths.fillPath.lineTo(lastRunX, bottomY)
                            paths.fillPath.lineTo(runStartX, bottomY)
                            paths.fillPath.close()
                        }

                        // Trailing horizontal dashed dropout bridge when ending in dropout/Signal Lost
                        if (hasLastValid && (sawGapSinceLastValid || isSignalLost) && lastValidX < rightX - 1f) {
                            paths.secondaryPath.moveTo(lastValidX, lastValidY)
                            paths.secondaryPath.lineTo(rightX, lastValidY)
                        }

                        if (!paths.fillPath.isEmpty) {
                            drawPath(path = paths.fillPath, brush = fillBrush)
                        }
                        if (!paths.primaryPath.isEmpty) {
                            drawPath(
                                path = paths.primaryPath,
                                color = toneColor,
                                style = strokes.signalLine,
                            )
                        }
                        if (!paths.secondaryPath.isEmpty) {
                            drawPath(
                                path = paths.secondaryPath,
                                color = colors.amber,
                                style = strokes.dashedDropout,
                            )
                        }
                        if (hasLastValid) {
                            val endDotColor = if (isSignalLost || sawGapSinceLastValid) {
                                colors.amber
                            } else {
                                toneColor
                            }
                            val dotX = if (isSignalLost || sawGapSinceLastValid) rightX else lastValidX
                            drawCircle(
                                color = endDotColor.copy(alpha = 0.30f),
                                radius = dotHaloRadius,
                                center = Offset(dotX, lastValidY),
                            )
                            drawCircle(
                                color = colors.textPrimary,
                                radius = dotRadius,
                                center = Offset(dotX, lastValidY),
                            )
                        }
                    }
                },
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Adaptive 2x2 (1x4 stacked when fontScale >= 1.45f) Stat Grid
        if (density.fontScale >= 1.45f) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SparklineStatTile(
                    label = "Min",
                    value = minText,
                    testTag = ProximityTestTags.STAT_MIN,
                    modifier = Modifier.fillMaxWidth(),
                )
                SparklineStatTile(
                    label = "Max",
                    value = maxText,
                    testTag = ProximityTestTags.STAT_MAX,
                    modifier = Modifier.fillMaxWidth(),
                )
                SparklineStatTile(
                    label = "Avg",
                    value = avgText,
                    testTag = ProximityTestTags.STAT_AVG,
                    modifier = Modifier.fillMaxWidth(),
                )
                SparklineStatTile(
                    label = "Stability",
                    value = stabilityText,
                    testTag = ProximityTestTags.STAT_STABILITY,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SparklineStatTile(
                        label = "Min",
                        value = minText,
                        testTag = ProximityTestTags.STAT_MIN,
                        modifier = Modifier.weight(1f),
                    )
                    SparklineStatTile(
                        label = "Max",
                        value = maxText,
                        testTag = ProximityTestTags.STAT_MAX,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SparklineStatTile(
                        label = "Avg",
                        value = avgText,
                        testTag = ProximityTestTags.STAT_AVG,
                        modifier = Modifier.weight(1f),
                    )
                    SparklineStatTile(
                        label = "Stability",
                        value = stabilityText,
                        testTag = ProximityTestTags.STAT_STABILITY,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SparklineStatTile(
    label: String,
    value: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    Surface(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceL2,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = RadarTheme.typography.badgeLabel,
                color = colors.textMuted,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = RadarTheme.typography.metricMedium,
                color = colors.textPrimary,
            )
        }
    }
}
