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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.component.InlineLegendBar
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.feature.proximity.ProximityTestTags
import com.a55.wifiradar.feature.proximity.ProximityUiState
import kotlin.math.roundToInt

internal val PROXIMITY_LEGEND_TOPICS = listOf(
    LegendTopic.KALMAN_SMOOTHING,
    LegendTopic.PATH_LOSS_DISTANCE,
    LegendTopic.DBM,
    LegendTopic.SIGNAL_QUALITY,
    LegendTopic.OS_SCAN_THROTTLE,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProximityBurstAndGeigerCard(
    uiState: ProximityUiState,
    onTriggerBurstScan: () -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleAudio: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val density = LocalDensity.current
    val scanTelemetry = uiState.scanTelemetry
    val telemetry = uiState.telemetry

    val maxTokens = scanTelemetry.maxTokens.coerceAtLeast(1)
    val availableTokens = scanTelemetry.availableTokens.coerceIn(0, maxTokens)
    val unthrottled = !scanTelemetry.isScanThrottleEnabled
    val waitSec = ((scanTelemetry.nextTokenRechargeInMs + 999L) / 1000L).coerceAtLeast(1L)

    val ringStateDesc = when {
        unthrottled -> "Unthrottled · Unlimited active sweeps"
        availableTokens == 0 || scanTelemetry.isCurrentlyThrottled ->
            "0 of $maxTokens pulses · Recharging in ${waitSec}s"
        else -> "$availableTokens of $maxTokens active scan pulses ready"
    }
    val ringCenterText = when {
        unthrottled -> "Free"
        availableTokens == 0 -> "${waitSec}s"
        else -> "$availableTokens/$maxTokens"
    }
    val ringFontSize = with(density) {
        val cappedSp = 12f * fontScale.coerceAtMost(1.30f)
        (cappedSp / fontScale).sp
    }

    RadarCard(
        modifier = modifier,
        accentTone = SemanticTone.INFO,
    ) {
        Text(
            text = "Burst Homing & Geiger Feedback",
            style = RadarTheme.typography.metricMedium,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(10.dp))

        // Burst Scan Row + 4-Segment Token Ring Canvas
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.align(Alignment.CenterVertically),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Outer Box keeps BURST_TOKEN_RING leaf canvas separate from sibling text
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .radarCanvasSemantics(
                                testTag = ProximityTestTags.BURST_TOKEN_RING,
                                contentDescription = "Active scan token quota ring: $ringStateDesc",
                                stateDescription = ringStateDesc,
                            )
                            .drawWithCache {
                                val ringStroke = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round)
                                val inset = ringStroke.width * 0.7f
                                val topLeft = Offset(inset, inset)
                                val arcSize = Size(
                                    width = (size.width - inset * 2f).coerceAtLeast(1f),
                                    height = (size.height - inset * 2f).coerceAtLeast(1f),
                                )
                                val segmentCount = maxTokens.coerceIn(1, 8)
                                val gapDegrees = if (segmentCount > 1) 16f else 0f
                                val sweepPerSegment = (360f / segmentCount) - gapDegrees

                                onDrawBehind {
                                    for (seg in 0 until segmentCount) {
                                        val startAngle = -90f + seg * (sweepPerSegment + gapDegrees) + gapDegrees * 0.5f
                                        val isFilled = unthrottled || seg < availableTokens
                                        val segColor = when {
                                            unthrottled -> colors.emerald
                                            isFilled -> colors.cyan
                                            availableTokens == 0 -> colors.amber.copy(alpha = 0.45f)
                                            else -> colors.border
                                        }
                                        drawArc(
                                            color = segColor,
                                            startAngle = startAngle,
                                            sweepAngle = sweepPerSegment,
                                            useCenter = false,
                                            topLeft = topLeft,
                                            size = arcSize,
                                            style = ringStroke,
                                        )
                                    }
                                }
                            },
                    )
                    Text(
                        text = ringCenterText,
                        style = RadarTheme.typography.badgeLabel.copy(fontSize = ringFontSize),
                        color = if (availableTokens == 0 && !unthrottled) colors.amber else colors.textPrimary,
                        maxLines = 1,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Active Sweep Quota",
                        style = RadarTheme.typography.metricMedium,
                        color = colors.textPrimary,
                    )
                    val quotaSub = uiState.burstStatusFeedback
                        ?: when {
                            telemetry?.isConnectedFastPath == true ->
                                "Connected Fast-Path · 300 ms live updates (no scan quota used)"
                            unthrottled ->
                                "Developer unthrottled mode active (continuous sweeps)"
                            availableTokens == 0 ->
                                "OS quota cooling down (${waitSec}s) — passive scans still live"
                            else ->
                                "$availableTokens/$maxTokens active sweeps ready every 2 min"
                        }
                    Text(
                        text = quotaSub,
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
            }

            Button(
                onClick = onTriggerBurstScan,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .align(Alignment.CenterVertically)
                    .testTag(ProximityTestTags.BURST_SCAN_BUTTON),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cyan,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Refresh,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Burst Scan",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.onAccentDark,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Spacer(modifier = Modifier.height(10.dp))

        // Geiger Feedback Toggles (Default OFF, Role.Switch, >= 48.dp touch target)
        val hapticChecked = uiState.feedbackSettings.hapticsEnabled && uiState.hasHardwareVibrator
        val hapticCaption = when {
            !uiState.hasHardwareVibrator -> "Vibrator unavailable on device"
            hapticChecked -> "On · ${telemetry?.geigerIntervalMs ?: 1400L} ms LRA tactile tick"
            else -> "Off · Faster tactile ticks as you walk closer"
        }
        GeigerSwitchTile(
            title = "Haptic Pulse",
            caption = hapticCaption,
            checked = hapticChecked,
            enabled = uiState.hasHardwareVibrator,
            testTag = ProximityTestTags.HAPTIC_TOGGLE,
            onCheckedChange = onToggleHaptics,
        )

        Spacer(modifier = Modifier.height(8.dp))

        val audioChecked = uiState.feedbackSettings.audioEnabled
        val audioCaption = if (audioChecked) {
            "On · ${(telemetry?.geigerToneHz ?: 320.0).roundToInt()} Hz Hanning sine ping"
        } else {
            "Off · Rising pitch ping as signal strengthens"
        }
        GeigerSwitchTile(
            title = "Audio Tone",
            caption = audioCaption,
            checked = audioChecked,
            enabled = true,
            testTag = ProximityTestTags.AUDIO_TOGGLE,
            onCheckedChange = onToggleAudio,
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Spacer(modifier = Modifier.height(10.dp))

        InlineLegendBar(topics = PROXIMITY_LEGEND_TOPICS)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeigerSwitchTile(
    title: String,
    caption: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val activeSpec = colors.toneSpec(SemanticTone.EXCELLENT)

    val containerColor = when {
        !enabled -> colors.background
        checked -> activeSpec.container
        else -> colors.surfaceL2
    }
    val borderColor = when {
        !enabled -> colors.border
        checked -> activeSpec.foreground
        else -> colors.activeBorder
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.minTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(
                    text = title,
                    style = RadarTheme.typography.metricMedium,
                    color = if (enabled) colors.textPrimary else colors.textMuted,
                )
                Text(
                    text = caption,
                    style = RadarTheme.typography.explanationBody,
                    color = if (checked && enabled) activeSpec.foreground else colors.textMuted,
                )
            }

            // Visual AMOLED pill indicator
            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (checked && enabled) activeSpec.foreground else colors.border)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (checked && enabled) colors.onAccentDark else colors.textMuted),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (!enabled) "Disabled" else if (checked) "ON" else "OFF",
                    style = RadarTheme.typography.badgeLabel,
                    color = if (checked && enabled) colors.onAccentDark else colors.textSecondary,
                )
            }
        }
    }
}
