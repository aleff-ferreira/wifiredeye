package com.a55.wifiradar.core.designsystem.canvas

import android.animation.ValueAnimator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone

/**
 * Merges TalkBack accessibility descriptions, live state summary, and Compose UI testTag
 * for all custom 120Hz Canvas gauges, sparklines, and spectrum graphs.
 */
fun Modifier.radarCanvasSemantics(
    testTag: String,
    contentDescription: String,
    stateDescription: String? = null,
): Modifier = this
    .semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription
        if (stateDescription != null) {
            this.stateDescription = stateDescription
        }
    }
    .testTag(testTag)

/**
 * Checks preview inspection mode and OS animator scale so animations snap cleanly when
 * system Reduce Motion is enabled or animators are disabled.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val inspection = LocalInspectionMode.current
    val animatorsDisabled = remember {
        runCatching { !ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false)
    }
    return inspection || animatorsDisabled
}

/**
 * Finite, zero-busy-loop spring interpolator that settles deterministically for
 * `ComposeTestRule.waitForIdle()` while delivering buttery 120Hz motion on Exynos 1480.
 */
@Composable
fun animateRadarFloatAsState(
    targetValue: Float,
    label: String = "radarFloat",
): State<Float> {
    if (rememberReducedMotionEnabled()) {
        return rememberUpdatedState(targetValue)
    }
    return animateFloatAsState(
        targetValue = targetValue,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = label,
    )
}

/**
 * Pre-allocated `Stroke` cache keyed on `LocalDensity` so `onDraw` performs zero `Stroke`
 * heap allocations across 120Hz frames.
 */
@Immutable
class RadarStrokeCache(
    val gridHairline: Stroke,
    val axisLine: Stroke,
    val signalLine: Stroke,
    val gaugeTrack: Stroke,
    val gaugeArc: Stroke,
    val dashedDropout: Stroke,
)

@Composable
fun rememberRadarStrokeCache(): RadarStrokeCache {
    val density = LocalDensity.current
    return remember(density) {
        with(density) {
            RadarStrokeCache(
                gridHairline = Stroke(width = 1.dp.toPx()),
                axisLine = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                signalLine = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
                gaugeTrack = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
                gaugeArc = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round),
                dashedDropout = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        intervals = floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
                        phase = 0f,
                    ),
                ),
            )
        }
    }
}

/**
 * Reusable `Path` pool so sparkline and spectral curve renderers reset and mutate paths
 * in place without per-frame GC churn.
 */
class ReusableRadarPathSet {
    val primaryPath: Path = Path()
    val fillPath: Path = Path()
    val secondaryPath: Path = Path()

    fun resetAll() {
        primaryPath.reset()
        fillPath.reset()
        secondaryPath.reset()
    }
}

@Composable
fun rememberReusableRadarPathSet(): ReusableRadarPathSet =
    remember { ReusableRadarPathSet() }

/**
 * Zero-allocation `Modifier.drawWithCache` backdrop grid for sparklines and spectrum charts.
 */
fun Modifier.drawRadarBackdropGrid(
    horizontalDivisions: Int = 4,
    verticalDivisions: Int = 6,
    gridColor: Color = Color(0xFF1E293B),
    glowAccentColor: Color = Color(0xFF00E5FF),
): Modifier = this.drawWithCache {
    val strokePx = 1.dp.toPx()
    val hSteps = horizontalDivisions.coerceAtLeast(1)
    val vSteps = verticalDivisions.coerceAtLeast(1)
    val yCoords = FloatArray(hSteps + 1) { idx -> (size.height * idx) / hSteps }
    val xCoords = FloatArray(vSteps + 1) { idx -> (size.width * idx) / vSteps }
    val radialGlow = Brush.radialGradient(
        colors = listOf(
            glowAccentColor.copy(alpha = 0.14f),
            Color.Transparent,
        ),
        center = Offset(size.width * 0.5f, size.height * 0.5f),
        radius = (size.minDimension * 0.65f).coerceAtLeast(1f),
    )

    onDrawBehind {
        drawRect(brush = radialGlow)
        for (y in yCoords) {
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokePx,
            )
        }
        for (x in xCoords) {
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = strokePx,
            )
        }
    }
}

/**
 * Reusable 0..100 True-AMOLED arc gauge rendered via `Modifier.drawWithCache` +
 * `animateRadarFloatAsState` + TalkBack semantics (used in M5 Insights & M1 visual showcase).
 */
@Composable
fun RadarScoreRingCanvas(
    score0To100: Int,
    label: String,
    qualifier: String,
    tone: SemanticTone,
    testTag: String,
    modifier: Modifier = Modifier,
    ringSize: Dp = 148.dp,
) {
    val clamped = score0To100.coerceIn(0, 100)
    val animatedFraction = animateRadarFloatAsState(
        targetValue = clamped / 100f,
        label = "scoreRingFraction",
    )
    val colors = RadarTheme.colors
    val toneSpec = colors.toneSpec(tone)
    val strokes = rememberRadarStrokeCache()

    Box(
        modifier = modifier
            .size(ringSize)
            .radarCanvasSemantics(
                testTag = testTag,
                contentDescription = "$label: $clamped out of 100, $qualifier",
                stateDescription = "$clamped / 100 · $qualifier",
            )
            .drawWithCache {
                val strokeWidth = strokes.gaugeArc.width
                val inset = strokeWidth * 0.65f
                val arcTopLeft = Offset(inset, inset)
                val arcSize = Size(
                    width = (size.width - inset * 2f).coerceAtLeast(1f),
                    height = (size.height - inset * 2f).coerceAtLeast(1f),
                )
                val centerGlow = Brush.radialGradient(
                    colors = listOf(
                        toneSpec.foreground.copy(alpha = 0.16f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.5f),
                    radius = (size.minDimension * 0.45f).coerceAtLeast(1f),
                )

                onDrawBehind {
                    drawCircle(brush = centerGlow)
                    drawArc(
                        color = colors.border,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = strokes.gaugeTrack,
                    )
                    drawArc(
                        color = toneSpec.foreground,
                        startAngle = 135f,
                        sweepAngle = 270f * animatedFraction.value.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = strokes.gaugeArc,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$clamped",
                style = RadarTheme.typography.metricLarge,
                color = toneSpec.foreground,
            )
            Text(
                text = qualifier,
                style = RadarTheme.typography.badgeLabel,
                color = colors.textSecondary,
            )
        }
    }
}
