package com.a55.wifiradar.feature.insights.component

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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.canvas.RadarScoreRingCanvas
import com.a55.wifiradar.core.designsystem.canvas.animateRadarFloatAsState
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.canvas.rememberRadarStrokeCache
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.feature.insights.InsightsTestTags
import com.a55.wifiradar.feature.insights.InsightsUiState
import com.a55.wifiradar.feature.insights.SubScoreBarUi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HealthScoreSectionCard(
    uiState: InsightsUiState,
    onNavigateToActionTarget: (InsightActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val fontScale = LocalDensity.current.fontScale
    val ringSize = if (fontScale >= 1.5f) 184.dp else 148.dp

    RadarElevatedCard(
        modifier = modifier.testTag(InsightsTestTags.HEALTH_CARD),
        accentTone = uiState.healthTone,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsightsTonePill(
                text = uiState.deviceRadioSummary,
                tone = SemanticTone.INFO,
                modifier = Modifier.testTag(InsightsTestTags.DEVICE_RADIO_SUMMARY),
            )
            if (!uiState.colocated6GhzHint.isNullOrBlank()) {
                InsightsTonePill(
                    text = uiState.colocated6GhzHint,
                    tone = SemanticTone.SPECTRUM_6GHZ,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val score = uiState.overallScore0To100
            if (uiState.isWaitingForScanData || score == null) {
                WaitingHealthRingPlaceholder(
                    ringSize = ringSize,
                    testTag = InsightsTestTags.HEALTH_WAITING_RING,
                )
            } else {
                RadarScoreRingCanvas(
                    score0To100 = score,
                    label = "Wireless Environment Health",
                    qualifier = uiState.ringShortQualifier,
                    tone = uiState.healthTone,
                    testTag = InsightsTestTags.HEALTH_SCORE_RING,
                    ringSize = ringSize,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = uiState.healthHeadline,
                style = typography.metricLarge,
                color = if (uiState.isWaitingForScanData) {
                    colors.textPrimary
                } else {
                    colors.toneSpec(uiState.healthTone).foreground
                },
                modifier = Modifier.testTag(InsightsTestTags.HEALTH_HEADLINE),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.healthSummarySentence,
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
        }

        val recommendations = listOfNotNull(
            uiState.best24GhzRec,
            uiState.best5GhzRec,
            uiState.best6GhzRec,
        )
        if (recommendations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recommendations.forEach { rec ->
                    BestChannelJumpButton(
                        rec = rec,
                        onClick = {
                            onNavigateToActionTarget(
                                InsightActionTarget.OpenSpectrumBand(
                                    band = rec.band,
                                    highlightChannel = rec.recommendedPrimaryChannel,
                                )
                            )
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            uiState.subScores.forEach { bar ->
                SubScoreProgressRow(bar = bar)
            }
        }
    }
}

@Composable
private fun WaitingHealthRingPlaceholder(
    ringSize: Dp,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val strokes = rememberRadarStrokeCache()

    Box(
        modifier = modifier
            .size(ringSize)
            .radarCanvasSemantics(
                testTag = testTag,
                contentDescription = "Wireless Environment Health: Waiting for scan data",
                stateDescription = "Waiting for scan data",
            )
            .drawWithCache {
                val strokeWidth = strokes.gaugeTrack.width
                val inset = strokeWidth * 0.65f
                val arcTopLeft = Offset(inset, inset)
                val arcSize = Size(
                    width = (size.width - inset * 2f).coerceAtLeast(1f),
                    height = (size.height - inset * 2f).coerceAtLeast(1f),
                )
                onDrawBehind {
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
                        color = colors.activeBorder,
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = strokes.dashedDropout,
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
                text = "—",
                style = RadarTheme.typography.metricLarge,
                color = colors.textMuted,
            )
            Text(
                text = "Waiting",
                style = RadarTheme.typography.badgeLabel,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun BestChannelJumpButton(
    rec: ChannelRecommendation,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val bandTone = rec.band.toSemanticTone()
    val toneSpec = colors.toneSpec(bandTone)

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .testTag(InsightsTestTags.bestChannelJumpChip(rec.band)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, toneSpec.border),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = RadarIcons.Spectrum,
            contentDescription = null,
            tint = toneSpec.foreground,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${rec.band.displayLabel} Best: Ch ${rec.recommendedPrimaryChannel} (${rec.score0To100}/100)",
            style = RadarTheme.typography.badgeLabel,
            color = toneSpec.foreground,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubScoreProgressRow(
    bar: SubScoreBarUi,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val toneSpec = colors.toneSpec(bar.tone)
    val rawTarget = ((bar.score0To100 ?: 0).coerceIn(0, 100)) / 100f
    val safeTarget = if (rawTarget.isFinite()) rawTarget else 0f
    val animatedFill by animateRadarFloatAsState(
        targetValue = safeTarget,
        label = "subScoreFill_${bar.key}",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InsightsTestTags.subScoreRow(bar.key)),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = bar.label,
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            Text(
                text = bar.formattedValue,
                style = typography.badgeLabel,
                color = if (bar.score0To100 == null) colors.textMuted else toneSpec.foreground,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.border)
                .drawWithCache {
                    val radius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                    onDrawBehind {
                        val frac = animatedFill.coerceIn(0f, 1f)
                        if (frac > 0.005f && bar.score0To100 != null) {
                            val fillWidth = (size.width * frac).coerceIn(4.dp.toPx(), size.width)
                            drawRoundRect(
                                color = toneSpec.foreground,
                                size = Size(fillWidth, size.height),
                                cornerRadius = radius,
                            )
                        }
                    }
                },
        )
    }
}

@Composable
internal fun InsightsTonePill(
    text: String,
    tone: SemanticTone,
    modifier: Modifier = Modifier,
) {
    val spec = RadarTheme.colors.toneSpec(tone)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = spec.container,
        border = BorderStroke(1.dp, spec.border),
    ) {
        Text(
            text = text,
            style = RadarTheme.typography.badgeLabel,
            color = spec.foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
