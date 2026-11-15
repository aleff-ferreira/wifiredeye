package com.a55.wifiradar.feature.insights.component

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarPalette
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.feature.insights.InsightsTestTags
import com.a55.wifiradar.feature.insights.InsightsUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticExportSectionCard(
    uiState: InsightsUiState,
    onSelectExportFormat: (ExportFormat) -> Unit,
    onRedactMacsChanged: (Boolean) -> Unit,
    onExportPreviewExpandedChanged: (Boolean) -> Unit,
    onCopyExportClick: () -> Unit,
    onShareMarkdownClick: () -> Unit,
    onShareJsonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val activeSpec = colors.toneSpec(SemanticTone.GOOD)

    RadarCard(
        modifier = modifier.testTag(InsightsTestTags.EXPORT_CARD),
        accentTone = SemanticTone.INFO,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RadarIcons.Share,
                contentDescription = null,
                tint = colors.cyan,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Offline Diagnostic Snapshot & Share",
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "100% local report generation — preview, copy, or share Markdown and JSON diagnostics with zero internet access.",
            style = typography.explanationBody,
            color = colors.textSecondary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Format Selector Tabs (Markdown Summary | JSON Diagnostic)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(ExportFormat.MARKDOWN, ExportFormat.JSON).forEach { format ->
                val isSelected = format == uiState.selectedExportFormat
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelectExportFormat(format) },
                        )
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(InsightsTestTags.exportFormatButton(format)),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) activeSpec.container else colors.surfaceL2,
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) activeSpec.foreground else colors.border,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = format.displayLabel,
                            style = typography.badgeLabel,
                            color = if (isSelected) activeSpec.foreground else colors.textPrimary,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Privacy Switch: Redact BSSID / MAC Addresses
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .toggleable(
                    value = uiState.redactMacs,
                    role = Role.Switch,
                    onValueChange = onRedactMacsChanged,
                )
                .heightIn(min = RadarTheme.dimens.minTouchTarget)
                .testTag(InsightsTestTags.EXPORT_REDACT_TOGGLE),
            shape = RoundedCornerShape(12.dp),
            color = if (uiState.redactMacs) activeSpec.container else colors.surfaceL2,
            border = BorderStroke(
                width = 1.dp,
                color = if (uiState.redactMacs) activeSpec.foreground else colors.border,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Redact BSSID / MAC Addresses",
                        style = typography.metricMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Masks hardware identifiers (AA:BB:CC:XX:XX:01) while keeping vendor OUI intact",
                        style = typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Switch(
                    checked = uiState.redactMacs,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccentDark,
                        checkedTrackColor = activeSpec.foreground,
                        uncheckedThumbColor = colors.textSecondary,
                        uncheckedTrackColor = colors.surfaceL1,
                        uncheckedBorderColor = colors.activeBorder,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Preview Metadata + Show/Hide Toggle
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val previewMeta = uiState.exportPreview
            val metaText = if (previewMeta != null) {
                "${previewMeta.fileName} · ${previewMeta.networkCount} radios · ${previewMeta.formattedSize}"
            } else {
                "${uiState.selectedExportFormat.displayLabel} ready to export"
            }
            Text(
                text = metaText,
                style = typography.badgeLabel,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            OutlinedButton(
                onClick = { onExportPreviewExpandedChanged(!uiState.isExportPreviewExpanded) },
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(InsightsTestTags.EXPORT_PREVIEW_TOGGLE),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = if (uiState.isExportPreviewExpanded) {
                        RadarIcons.ChevronUp
                    } else {
                        RadarIcons.ChevronDown
                    },
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (uiState.isExportPreviewExpanded) "Hide Preview" else "Show Preview",
                    style = typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }
        }

        if (uiState.isExportPreviewExpanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InsightsTestTags.EXPORT_PREVIEW_BOX),
                shape = RoundedCornerShape(12.dp),
                color = RadarPalette.OledBlack,
                border = BorderStroke(1.dp, colors.activeBorder),
            ) {
                Text(
                    text = uiState.exportPreview?.previewText
                        ?: "Preparing ${uiState.selectedExportFormat.displayLabel} preview...",
                    style = typography.monoAddress,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Bar: Share Markdown | Share JSON | Copy to Clipboard
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onShareMarkdownClick,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(InsightsTestTags.SHARE_MARKDOWN_BUTTON),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.emerald,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Share,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Share Markdown",
                    style = typography.badgeLabel,
                    color = colors.onAccentDark,
                )
            }

            Button(
                onClick = onShareJsonClick,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(InsightsTestTags.SHARE_JSON_BUTTON),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.cyan,
                    contentColor = colors.onAccentDark,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Share,
                    contentDescription = null,
                    tint = colors.onAccentDark,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Share JSON",
                    style = typography.badgeLabel,
                    color = colors.onAccentDark,
                )
            }

            OutlinedButton(
                onClick = onCopyExportClick,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(InsightsTestTags.COPY_EXPORT_BUTTON),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Copy to Clipboard",
                    style = typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }
        }

        if (!uiState.copyConfirmationMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            val emeraldSpec = colors.toneSpec(SemanticTone.EXCELLENT)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InsightsTestTags.EXPORT_COPY_CONFIRMATION),
                shape = RoundedCornerShape(10.dp),
                color = emeraldSpec.container,
                border = BorderStroke(1.dp, emeraldSpec.border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = RadarIcons.CheckCircle,
                        contentDescription = null,
                        tint = emeraldSpec.foreground,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.copyConfirmationMessage,
                        style = typography.badgeLabel,
                        color = emeraldSpec.foreground,
                    )
                }
            }
        }
    }
}
