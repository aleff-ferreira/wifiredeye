package com.a55.wifiradar.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.LegendTopic

@Immutable
data class LegendSpec(
    val id: String,
    val pillLabel: String,
    val title: String,
    val explanation: String,
    val tone: SemanticTone = SemanticTone.INFO,
) {
    companion object {
        fun fromTopic(topic: LegendTopic): LegendSpec = LegendSpec(
            id = topic.topicId,
            pillLabel = topic.shortChipLabel,
            title = topic.title,
            explanation = topic.plainEnglishExplanation,
            tone = topic.toSemanticTone(),
        )
    }
}

@Composable
fun InlineLegendPill(
    topic: LegendTopic,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    InlineLegendPill(
        spec = LegendSpec.fromTopic(topic),
        modifier = modifier,
        initiallyExpanded = initiallyExpanded,
    )
}

@Composable
fun InlineLegendPill(
    spec: LegendSpec,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(spec.id) { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier) {
        LegendChipTrigger(
            spec = spec,
            expanded = expanded,
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            LegendExplanationCard(
                spec = spec,
                onDismiss = { expanded = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InlineLegendBar(
    topics: List<LegendTopic>,
    modifier: Modifier = Modifier,
    initialTopicId: String? = null,
) {
    val specs = topics.map(LegendSpec::fromTopic)
    var activeTopicId by rememberSaveable { mutableStateOf(initialTopicId) }
    val activeSpec = specs.firstOrNull { it.id == activeTopicId }

    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            specs.forEach { spec ->
                val isExpanded = spec.id == activeTopicId
                LegendChipTrigger(
                    spec = spec,
                    expanded = isExpanded,
                    onClick = {
                        activeTopicId = if (isExpanded) null else spec.id
                    },
                )
            }
        }
        if (activeSpec != null) {
            Spacer(modifier = Modifier.height(8.dp))
            LegendExplanationCard(
                spec = activeSpec,
                onDismiss = { activeTopicId = null },
            )
        }
    }
}

@Composable
private fun LegendChipTrigger(
    spec: LegendSpec,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val toneSpec = colors.toneSpec(spec.tone)

    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 36.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                contentDescription = "${spec.pillLabel}, tap to explain"
            }
            .testTag(RadarTestTags.legendPill(spec.id)),
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) toneSpec.container else colors.surfaceL2,
        contentColor = if (expanded) toneSpec.foreground else colors.textSecondary,
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) toneSpec.foreground else toneSpec.border,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = RadarIcons.Info,
                contentDescription = null,
                tint = toneSpec.foreground,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = "${spec.pillLabel} ?",
                style = RadarTheme.typography.badgeLabel,
                color = if (expanded) toneSpec.foreground else colors.textPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LegendExplanationCard(
    spec: LegendSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val toneSpec = colors.toneSpec(spec.tone)

    RadarElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RadarTestTags.legendCard(spec.id)),
        accentTone = spec.tone,
        contentPadding = PaddingValues(12.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = spec.title,
                style = RadarTheme.typography.metricMedium,
                color = toneSpec.foreground,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(RadarTestTags.legendDismiss(spec.id)),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, toneSpec.border),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = toneSpec.container,
                    contentColor = toneSpec.foreground,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Got it",
                    style = RadarTheme.typography.badgeLabel,
                    color = toneSpec.foreground,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = spec.explanation,
            style = RadarTheme.typography.explanationBody,
            color = colors.textPrimary,
        )
    }
}
