package com.a55.wifiradar.feature.proximity.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.InlineLegendPill
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.VelocityTrend
import com.a55.wifiradar.feature.proximity.ProximityTestTags
import com.a55.wifiradar.feature.proximity.ProximityUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProximityPhysicsAndVelocityCard(
    uiState: ProximityUiState,
    onSelectEnvironment: (PropagationEnvironment) -> Unit,
    onCalibrateOneMeter: () -> Unit,
    onClearCalibration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val telemetry = uiState.telemetry

    val trend = telemetry?.velocityTrend ?: VelocityTrend.CALIBRATING
    val slope = telemetry?.velocitySlopeDbPerSec ?: 0.0
    val velocityTone = trend.toSemanticTone()
    val velocitySpec = colors.toneSpec(velocityTone)
    val velocityIcon = when (trend) {
        VelocityTrend.APPROACHING -> RadarIcons.ChevronUp
        VelocityTrend.MOVING_AWAY -> RadarIcons.ChevronDown
        VelocityTrend.STEADY, VelocityTrend.CALIBRATING -> RadarIcons.Proximity
    }

    val distanceEstimate = telemetry?.distanceEstimate
    val distanceText = distanceEstimate?.plainEnglishFormatted?.takeIf { it.isNotBlank() }
        ?: distanceEstimate?.let { est ->
            SemanticFormatters.formatDistanceRange(
                meters = est.meters,
                rangeMinMeters = est.rangeMinMeters,
                rangeMaxMeters = est.rangeMaxMeters,
                stdDevMeters = est.stdDevMeters,
                method = est.method,
            )
        }
        ?: "—"

    val distanceMethod = distanceEstimate?.method
        ?: if (uiState.isCalibratedOneMeter) {
            DistanceMethod.USER_CALIBRATED_PATH_LOSS
        } else {
            DistanceMethod.PATH_LOSS_ESTIMATE
        }
    val methodTone = when {
        distanceMethod.isHardwareRtt -> SemanticTone.SPECTRUM_6GHZ
        uiState.isCalibratedOneMeter || distanceMethod == DistanceMethod.USER_CALIBRATED_PATH_LOSS ->
            SemanticTone.EXCELLENT
        else -> SemanticTone.GOOD
    }
    val methodSpec = colors.toneSpec(methodTone)

    RadarCard(
        modifier = modifier,
        accentTone = velocityTone,
    ) {
        // Velocity Trend Banner
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .testTag(ProximityTestTags.VELOCITY_BANNER),
            shape = RoundedCornerShape(12.dp),
            color = velocitySpec.container,
            border = BorderStroke(1.dp, velocitySpec.border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = velocityIcon,
                    contentDescription = null,
                    tint = velocitySpec.foreground,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = SemanticFormatters.formatVelocityTrend(trend, slope),
                    style = RadarTheme.typography.metricMedium,
                    color = velocitySpec.foreground,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Distance Range + Method Badge + Inline Legend
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Estimated Distance",
                style = RadarTheme.typography.metricMedium,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            InlineLegendPill(
                topic = LegendTopic.PATH_LOSS_DISTANCE,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = distanceText,
                style = RadarTheme.typography.metricLarge,
                color = colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .testTag(ProximityTestTags.DISTANCE_VALUE_TEXT),
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .semantics(mergeDescendants = true) {}
                    .testTag(ProximityTestTags.DISTANCE_METHOD_BADGE),
                shape = RoundedCornerShape(10.dp),
                color = methodSpec.container,
                border = BorderStroke(1.dp, methodSpec.border),
            ) {
                Text(
                    text = distanceMethod.badgeLabel,
                    style = RadarTheme.typography.badgeLabel,
                    color = methodSpec.foreground,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Spacer(modifier = Modifier.height(10.dp))

        // Propagation Environment Selector (Open Space / Home / Office / Heavy Concrete)
        Text(
            text = "Indoor Wall Environment",
            style = RadarTheme.typography.badgeLabel,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PropagationEnvironment.entries.forEach { env ->
                val selected = uiState.environment == env
                val spec = colors.toneSpec(SemanticTone.GOOD)
                Surface(
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = selected,
                            onClick = { onSelectEnvironment(env) },
                            role = Role.RadioButton,
                        )
                        .testTag(ProximityTestTags.environmentChip(env)),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) spec.container else colors.surfaceL2,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (selected) spec.foreground else colors.border,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = env.displayLabel,
                            style = RadarTheme.typography.badgeLabel,
                            color = if (selected) spec.foreground else colors.textSecondary,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiState.environment.shortDescription,
            style = RadarTheme.typography.explanationBody,
            color = colors.textMuted,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 1-Tap 1m Calibration & Clear Calibration
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCalibrateOneMeter,
                enabled = uiState.canCalibrateOneMeter,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(ProximityTestTags.CALIBRATE_1M_BUTTON),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cyan,
                    contentColor = colors.onAccentDark,
                    disabledContainerColor = colors.surfaceL2,
                    disabledContentColor = colors.textMuted,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Calibrate 1m",
                    style = RadarTheme.typography.metricMedium,
                    color = if (uiState.canCalibrateOneMeter) colors.onAccentDark else colors.textMuted,
                )
            }

            if (uiState.isCalibratedOneMeter) {
                OutlinedButton(
                    onClick = onClearCalibration,
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(ProximityTestTags.CLEAR_CALIBRATION_BUTTON),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surfaceL2,
                        contentColor = colors.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Clear Calibration",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Stand 1 meter from the router with a clear sightline and tap Calibrate 1m to tune range physics to that transmitter.",
            style = RadarTheme.typography.explanationBody,
            color = colors.textMuted,
        )
    }
}
