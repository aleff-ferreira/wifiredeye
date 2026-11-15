package com.a55.wifiradar.feature.insights.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.feature.insights.ActionableInsightCardUi
import com.a55.wifiradar.feature.insights.InsightsTestTags

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionableInsightsSection(
    insights: List<ActionableInsightCardUi>,
    isWaitingForScanData: Boolean,
    onNavigateToActionTarget: (InsightActionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(InsightsTestTags.INSIGHTS_LIST_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Prioritized Actionable Recommendations",
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            InsightsTonePill(
                text = "${insights.size} ${if (insights.size == 1) "Tip" else "Tips"}",
                tone = if (insights.any { it.severity == InsightSeverity.CRITICAL }) {
                    SemanticTone.WEAK
                } else {
                    SemanticTone.INFO
                },
            )
        }

        if (insights.isEmpty()) {
            RadarCard(
                modifier = Modifier.testTag(InsightsTestTags.INSIGHTS_EMPTY_CARD),
                accentTone = if (isWaitingForScanData) SemanticTone.NEUTRAL else SemanticTone.EXCELLENT,
            ) {
                Text(
                    text = if (isWaitingForScanData) {
                        "Recommendations Appear Automatically After Your First Scan"
                    } else {
                        "No Urgent Wireless Issues Detected"
                    },
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isWaitingForScanData) {
                        "Once nearby routers are detected, plain-English channel, signal, and security fixes will be ranked here."
                    } else {
                        "Your surrounding channels and security settings look balanced right now."
                    },
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
        } else {
            insights.forEach { item ->
                ActionableInsightItemCard(
                    item = item,
                    onActionClick = { onNavigateToActionTarget(item.actionTarget) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionableInsightItemCard(
    item: ActionableInsightCardUi,
    onActionClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val toneSpec = colors.toneSpec(item.tone)
    val isCritical = item.severity == InsightSeverity.CRITICAL

    RadarCard(
        modifier = Modifier.testTag(InsightsTestTags.insightCard(item.id)),
        accentTone = item.tone,
        highlighted = isCritical,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsightsTonePill(
                text = item.severityLabel,
                tone = item.tone,
            )
            InsightsTonePill(
                text = item.categoryLabel,
                tone = SemanticTone.NEUTRAL,
            )
            if (!item.metricBadge.isNullOrBlank()) {
                InsightsTonePill(
                    text = item.metricBadge,
                    tone = SemanticTone.INFO,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = typography.metricMedium,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.body,
            style = typography.explanationBody,
            color = colors.textSecondary,
        )

        if (item.actionTarget !is InsightActionTarget.NoAction && !item.actionLabel.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            val actionIcon = when (item.actionTarget) {
                is InsightActionTarget.LaunchProximity -> RadarIcons.Proximity
                is InsightActionTarget.OpenSpectrumBand -> RadarIcons.Spectrum
                is InsightActionTarget.OpenNetworkDetail -> RadarIcons.Networks
                InsightActionTarget.NoAction -> RadarIcons.Info
            }
            if (isCritical) {
                Button(
                    onClick = onActionClick,
                    modifier = Modifier
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(InsightsTestTags.insightActionCta(item.id)),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = toneSpec.foreground,
                        contentColor = colors.onAccentDark,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = colors.onAccentDark,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.actionLabel,
                        style = typography.badgeLabel,
                        color = colors.onAccentDark,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onActionClick,
                    modifier = Modifier
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(InsightsTestTags.insightActionCta(item.id)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, toneSpec.foreground),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = toneSpec.foreground,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.actionLabel,
                        style = typography.badgeLabel,
                        color = toneSpec.foreground,
                    )
                }
            }
        }
    }
}
