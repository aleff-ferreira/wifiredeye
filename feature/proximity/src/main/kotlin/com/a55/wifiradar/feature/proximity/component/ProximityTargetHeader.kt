package com.a55.wifiradar.feature.proximity.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.BandPill
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.feature.proximity.ProximityLockMode
import com.a55.wifiradar.feature.proximity.ProximityTestTags
import com.a55.wifiradar.feature.proximity.ProximityUiState
import com.a55.wifiradar.feature.proximity.isHiddenOrUnnamed

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProximityTargetHeaderCard(
    uiState: ProximityUiState,
    onOpenTargetPicker: () -> Unit,
    onSelectLockMode: (ProximityLockMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val telemetry = uiState.telemetry
    val targetKey = uiState.activeTargetKey

    val title = telemetry?.displayTitle?.takeIf { it.isNotBlank() }
        ?: when (targetKey) {
            is ProximityTargetKey.BssidLock -> targetKey.ssidHint ?: DecodedSsid.HIDDEN_NETWORK_LABEL
            is ProximityTargetKey.FollowMeshSsid -> targetKey.ssid
            null -> "No Target Selected"
        }

    val subtitle = telemetry?.displaySubtitle?.takeIf { it.isNotBlank() }
        ?: when (targetKey) {
            is ProximityTargetKey.BssidLock -> "Radio ${MacAddressUtils.normalizeBssid(targetKey.bssid)}"
            is ProximityTargetKey.FollowMeshSsid -> "Mesh SSID · ${targetKey.ssid}"
            null -> "Select any detected router to start homing"
        }

    RadarCard(
        modifier = modifier.testTag(ProximityTestTags.TARGET_HEADER_CARD),
        accentTone = if (telemetry?.isConnectedFastPath == true) SemanticTone.EXCELLENT else SemanticTone.GOOD,
        highlighted = true,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = title,
                    style = RadarTheme.typography.metricLarge,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textSecondary,
                )
            }

            OutlinedButton(
                onClick = onOpenTargetPicker,
                modifier = Modifier
                    .heightIn(min = dimens.minTouchTarget)
                    .testTag(ProximityTestTags.TARGET_SWITCHER_BUTTON),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.cyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.toneSpec(SemanticTone.GOOD).container,
                    contentColor = colors.cyan,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Networks,
                    contentDescription = null,
                    tint = colors.cyan,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Change Target",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.cyan,
                )
            }
        }

        if (telemetry != null && (telemetry.isConnectedFastPath || telemetry.band != WifiBand.UNKNOWN)) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (telemetry.isConnectedFastPath) {
                    val emeraldSpec = colors.toneSpec(SemanticTone.EXCELLENT)
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = emeraldSpec.container,
                        border = BorderStroke(1.dp, emeraldSpec.border),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = RadarIcons.CheckCircle,
                                contentDescription = null,
                                tint = emeraldSpec.foreground,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Connected · 300ms Live",
                                style = RadarTheme.typography.badgeLabel,
                                color = emeraldSpec.foreground,
                            )
                        }
                    }
                }
                if (telemetry.band != WifiBand.UNKNOWN) {
                    val chSummary = if (telemetry.channel > 0) {
                        "Ch ${telemetry.channel} (${telemetry.frequencyMhz} MHz)"
                    } else if (telemetry.frequencyMhz > 0) {
                        "${telemetry.frequencyMhz} MHz"
                    } else {
                        null
                    }
                    BandPill(
                        bandName = telemetry.band.displayLabel,
                        qualifier = telemetry.band.traitLabel,
                        tone = if (telemetry.band == WifiBand.BAND_6_GHZ) {
                            SemanticTone.SPECTRUM_6GHZ
                        } else if (telemetry.band == WifiBand.BAND_5_GHZ) {
                            SemanticTone.EXCELLENT
                        } else {
                            SemanticTone.GOOD
                        },
                        channelSummary = chSummary,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.border)
        Spacer(modifier = Modifier.height(10.dp))

        // Lock Mode Toggle: Single Router (BSSID) vs Follow Mesh (SSID)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LockModeSelectablePill(
                label = ProximityLockMode.SINGLE_ROUTER_BSSID.displayLabel,
                selected = uiState.lockMode == ProximityLockMode.SINGLE_ROUTER_BSSID,
                enabled = true,
                testTag = ProximityTestTags.LOCK_MODE_SINGLE_BSSID,
                onClick = { onSelectLockMode(ProximityLockMode.SINGLE_ROUTER_BSSID) },
            )
            LockModeSelectablePill(
                label = ProximityLockMode.FOLLOW_MESH_SSID.displayLabel,
                selected = uiState.lockMode == ProximityLockMode.FOLLOW_MESH_SSID,
                enabled = uiState.canFollowMeshSsid,
                testTag = ProximityTestTags.LOCK_MODE_FOLLOW_MESH,
                onClick = { onSelectLockMode(ProximityLockMode.FOLLOW_MESH_SSID) },
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        val lockExplanation = when {
            !uiState.canFollowMeshSsid ->
                "Hidden network has no broadcast name — locked strictly to this physical router hardware address."
            uiState.lockMode == ProximityLockMode.FOLLOW_MESH_SSID ->
                "Following strongest mesh node broadcasting \"${uiState.resolvedMeshSsid.orEmpty()}\"."
            else ->
                "Locked to one physical router hardware address so mesh roaming never jumps rooms."
        }
        Text(
            text = lockExplanation,
            style = RadarTheme.typography.explanationBody,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun LockModeSelectablePill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val spec = colors.toneSpec(SemanticTone.GOOD)

    val containerColor = when {
        !enabled -> colors.background
        selected -> spec.container
        else -> colors.surfaceL2
    }
    val borderColor = when {
        !enabled -> colors.border
        selected -> spec.foreground
        else -> colors.activeBorder
    }
    val textColor = when {
        !enabled -> colors.textMuted
        selected -> spec.foreground
        else -> colors.textSecondary
    }

    Surface(
        modifier = Modifier
            .heightIn(min = dimens.minTouchTarget)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (selected) RadarIcons.CheckCircle else RadarIcons.Lock,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = RadarTheme.typography.badgeLabel,
                color = textColor,
            )
        }
    }
}

@Composable
internal fun BoxScope.ProximityTargetPickerSheetOverlay(
    availableMeshGroups: List<EssNetworkGroup>,
    availableRadios: List<BssidRadio>,
    activeTargetKey: ProximityTargetKey?,
    resolvedBssid: String?,
    onSelectTarget: (ProximityTargetKey) -> Unit,
    onDismiss: () -> Unit,
    topClearancePadding: Dp = 0.dp,
    bottomClearancePadding: Dp = 0.dp,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    BackHandler(enabled = true) {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .semantics { contentDescription = "Dismiss sheet" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag(ProximityTestTags.TARGET_PICKER_SCRIM),
    )

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.84f)
            .padding(top = topClearancePadding, bottom = bottomClearancePadding)
            .imePadding()
            .testTag(ProximityTestTags.TARGET_PICKER_SHEET),
        color = colors.surfaceL2,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, colors.activeBorder),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drag Handle + Pinned Header with explicit 48.dp Close button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.cardInnerPadding, vertical = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(40.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(colors.activeBorder),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select Proximity Target",
                            style = RadarTheme.typography.metricLarge,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = "${availableRadios.size} detected radio${if (availableRadios.size == 1) "" else "s"} in reach",
                            style = RadarTheme.typography.explanationBody,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .heightIn(min = dimens.minTouchTarget)
                            .semantics { contentDescription = "Close" }
                            .testTag(ProximityTestTags.TARGET_PICKER_CLOSE_BUTTON),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.activeBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.surfaceL1,
                            contentColor = colors.textPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = RadarIcons.Close,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Close",
                            style = RadarTheme.typography.badgeLabel,
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = colors.border)

            val activeLockedBssid = when (activeTargetKey) {
                is ProximityTargetKey.BssidLock -> MacAddressUtils.normalizeBssid(activeTargetKey.bssid)
                is ProximityTargetKey.FollowMeshSsid -> resolvedBssid?.let(MacAddressUtils::normalizeBssid)
                null -> null
            }
            val activeMeshSsid = (activeTargetKey as? ProximityTargetKey.FollowMeshSsid)?.ssid

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(ProximityTestTags.TARGET_PICKER_LIST),
                contentPadding = PaddingValues(horizontal = dimens.cardInnerPadding, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (availableMeshGroups.isNotEmpty()) {
                    item(key = "mesh_section_header") {
                        Text(
                            text = "Follow Mesh Network (Strongest Node)",
                            style = RadarTheme.typography.badgeLabel,
                            color = colors.cyan,
                        )
                    }
                    items(
                        items = availableMeshGroups,
                        key = { group -> "picker_mesh:${group.groupKey}" },
                    ) { group ->
                        val isSelectedMesh = activeMeshSsid == group.ssid.displayName
                        PickerMeshGroupRow(
                            group = group,
                            isSelected = isSelectedMesh,
                            onClick = {
                                onSelectTarget(
                                    ProximityTargetKey.FollowMeshSsid(
                                        ssid = group.ssid.displayName,
                                        preferredBssid = MacAddressUtils.normalizeBssid(group.strongestRadio.bssid),
                                    )
                                )
                            },
                        )
                    }
                    item(key = "mesh_section_spacer") {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                item(key = "radio_section_header") {
                    Text(
                        text = "Single Physical Router Lock (BSSID)",
                        style = RadarTheme.typography.badgeLabel,
                        color = colors.emerald,
                    )
                }

                if (availableRadios.isEmpty()) {
                    item(key = "empty_picker_note") {
                        Text(
                            text = "No nearby routers detected yet. Trigger a scan sweep to populate targets.",
                            style = RadarTheme.typography.explanationBody,
                            color = colors.textSecondary,
                        )
                    }
                } else {
                    items(
                        items = availableRadios,
                        key = { radio -> "picker_radio:${MacAddressUtils.normalizeBssid(radio.bssid)}" },
                    ) { radio ->
                        val normMac = MacAddressUtils.normalizeBssid(radio.bssid)
                        val isSelectedRadio = activeTargetKey is ProximityTargetKey.BssidLock &&
                            activeLockedBssid == normMac
                        PickerRadioRow(
                            radio = radio,
                            isSelected = isSelectedRadio,
                            onClick = {
                                onSelectTarget(
                                    ProximityTargetKey.BssidLock(
                                        bssid = normMac,
                                        ssidHint = radio.ssid.takeUnless { it.isHiddenOrUnnamed() }?.displayName,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerMeshGroupRow(
    group: EssNetworkGroup,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val spec = colors.toneSpec(SemanticTone.GOOD)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.minTouchTarget)
            .testTag(ProximityTestTags.pickerMeshItem(group.displayName)),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) spec.container else colors.surfaceL1,
        border = BorderStroke(1.dp, if (isSelected) spec.foreground else colors.border),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = RadarIcons.CheckCircle,
                            contentDescription = "Selected target",
                            tint = spec.foreground,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = "${group.displayName} · Follow Mesh (${group.radios.size} radios)",
                        style = RadarTheme.typography.metricMedium,
                        color = colors.textPrimary,
                    )
                }
                val bandsText = group.bands.sortedBy { it.ordinal }.joinToString(" / ") { it.displayLabel }
                Text(
                    text = "$bandsText · Best ${group.strongestRadio.rssiDbm} dBm",
                    style = RadarTheme.typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            SignalBadge(radio = group.strongestRadio)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerRadioRow(
    radio: BssidRadio,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val normMac = MacAddressUtils.normalizeBssid(radio.bssid)
    val spec = colors.toneSpec(SemanticTone.EXCELLENT)
    val displayTitle = radio.customNickname?.takeIf { it.isNotBlank() }
        ?: if (radio.ssid.isHiddenOrUnnamed()) {
            DecodedSsid.HIDDEN_NETWORK_LABEL
        } else {
            radio.effectiveDisplayName.ifBlank { radio.ssid.displayName }
        }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = dimens.minTouchTarget)
            .testTag(ProximityTestTags.pickerRadioItem(normMac)),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) spec.container else colors.surfaceL1,
        border = BorderStroke(1.dp, if (isSelected) spec.foreground else colors.border),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSelected) {
                        Icon(
                            imageVector = RadarIcons.CheckCircle,
                            contentDescription = "Selected target",
                            tint = spec.foreground,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = if (radio.isConnected) "$displayTitle (Connected)" else displayTitle,
                        style = RadarTheme.typography.metricMedium,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "$normMac · ${radio.vendorInfo.displayLabel} · ${radio.channelDescriptor.band.displayLabel} Ch ${radio.channelDescriptor.primaryChannel}",
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textMuted,
                )
            }
            SignalBadge(radio = radio)
        }
    }
}
