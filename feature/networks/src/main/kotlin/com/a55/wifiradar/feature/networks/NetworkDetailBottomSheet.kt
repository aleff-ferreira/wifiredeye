package com.a55.wifiradar.feature.networks

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.BandPill
import com.a55.wifiradar.core.designsystem.component.InlineLegendPill
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.SecurityGradeBadge
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.SemanticFormatters
import java.util.Locale

@Composable
internal fun BoxScope.NetworkDetailBottomSheetOverlay(
    detailSheet: NetworkDetailSheetState,
    onDismiss: () -> Unit,
    onSaveNickname: (bssid: String, nickname: String?) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
    topClearancePadding: androidx.compose.ui.unit.Dp = 0.dp,
    bottomClearancePadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val colors = RadarTheme.colors

    BackHandler(enabled = true) {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .semantics {
                role = Role.Button
                contentDescription = "Dismiss sheet"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag(NetworksTestTags.DETAIL_SCRIM),
    )

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(0.88f)
            .padding(top = topClearancePadding, bottom = bottomClearancePadding)
            .testTag(NetworksTestTags.DETAIL_SHEET),
        color = colors.surfaceL2,
        contentColor = colors.textPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        border = BorderStroke(1.dp, colors.activeBorder),
    ) {
        NetworkDetailSheetContent(
            detailSheet = detailSheet,
            onDismiss = onDismiss,
            onSaveNickname = onSaveNickname,
            onOpenProximity = onOpenProximity,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkDetailSheetContent(
    detailSheet: NetworkDetailSheetState,
    onDismiss: () -> Unit,
    onSaveNickname: (bssid: String, nickname: String?) -> Unit,
    onOpenProximity: (ProximityTargetKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val context = LocalContext.current
    val radio = detailSheet.radio
    val normalizedBssid = MacAddressUtils.normalizeBssid(
        radio?.bssid ?: detailSheet.requestedBssid
    )

    var draftNickname by rememberSaveable(normalizedBssid, radio?.customNickname) {
        mutableStateOf(radio?.customNickname.orEmpty())
    }
    var copiedConfirmation by rememberSaveable(normalizedBssid) {
        mutableStateOf(false)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Drag handle + Pinned Header
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
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val title = if (radio != null) {
                        NetworksProjector.radioDisplayTitle(radio)
                    } else {
                        "Router $normalizedBssid"
                    }
                    Text(
                        text = title,
                        style = RadarTheme.typography.metricLarge,
                        color = colors.textPrimary,
                    )
                    if (radio != null && !radio.customNickname.isNullOrBlank()) {
                        val rawSsidLabel = if (radio.ssid.isEffectivelyHidden()) {
                            DecodedSsid.HIDDEN_NETWORK_LABEL
                        } else {
                            radio.ssid.displayName
                        }
                        Text(
                            text = "Broadcast SSID: $rawSsidLabel",
                            style = RadarTheme.typography.explanationBody,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (radio != null) {
                            "$normalizedBssid · ${radio.vendorInfo.displayLabel}"
                        } else {
                            normalizedBssid
                        },
                        style = RadarTheme.typography.monoAddress,
                        color = colors.textMuted,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .semantics { contentDescription = "Close" }
                        .testTag(NetworksTestTags.DETAIL_CLOSE_BUTTON),
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = dimens.cardInnerPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!detailSheet.isCurrentlyInRange) {
                RadarElevatedCard(
                    modifier = Modifier.testTag(NetworksTestTags.DETAIL_DROPOUT_BANNER),
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
                            text = "Out of range · Last seen ${SemanticFormatters.formatAge(detailSheet.dropoutAgeMs)}",
                            style = RadarTheme.typography.metricMedium,
                            color = colors.amber,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This router dropped out of the latest wireless sweep. Last captured diagnostics remain visible until it returns or you close this sheet.",
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
            }

            if (radio == null) {
                RadarCard(accentTone = SemanticTone.NEUTRAL) {
                    Text(
                        text = "Waiting for beacon telemetry from $normalizedBssid...",
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
                return@Column
            }

            // Primary CTAs: Measure Proximity & Copy Summary
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val target = ProximityTargetKey.BssidLock(
                            bssid = normalizedBssid,
                            ssidHint = radio.ssid.nonHiddenSsidOrNull(),
                        )
                        onDismiss()
                        onOpenProximity(target)
                    },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(NetworksTestTags.DETAIL_MEASURE_PROXIMITY_CTA),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.cyan,
                        contentColor = colors.onAccentDark,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = RadarIcons.Proximity,
                        contentDescription = null,
                        tint = colors.onAccentDark,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Measure Proximity",
                        style = RadarTheme.typography.metricMedium,
                        color = colors.onAccentDark,
                    )
                }

                OutlinedButton(
                    onClick = {
                        val summary = buildPlainEnglishNetworkSummary(radio, detailSheet)
                        copySummaryToClipboard(context, summary)
                        copiedConfirmation = true
                    },
                    modifier = Modifier
                        .heightIn(min = dimens.minTouchTarget)
                        .testTag(NetworksTestTags.DETAIL_COPY_SUMMARY_CTA),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surfaceL1,
                        contentColor = colors.textPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = RadarIcons.Share,
                        contentDescription = null,
                        tint = colors.cyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Copy Summary",
                        style = RadarTheme.typography.metricMedium,
                        color = colors.textPrimary,
                    )
                }
            }

            if (copiedConfirmation) {
                Text(
                    text = "Plain-English summary copied to clipboard",
                    style = RadarTheme.typography.badgeLabel,
                    color = colors.emerald,
                )
            }

            // Custom BSSID Nickname Card
            RadarCard(accentTone = SemanticTone.INFO) {
                Text(
                    text = "Custom Router Nickname",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Assign a friendly room label to this hardware address (stored 100% offline on your phone).",
                    style = RadarTheme.typography.explanationBody,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = draftNickname,
                    onValueChange = { draftNickname = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NetworksTestTags.DETAIL_NICKNAME_INPUT),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "e.g. Living Room Router",
                            style = RadarTheme.typography.explanationBody,
                            color = colors.textMuted,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedContainerColor = colors.background,
                        unfocusedContainerColor = colors.background,
                        focusedBorderColor = colors.cyan,
                        unfocusedBorderColor = colors.activeBorder,
                        cursorColor = colors.cyan,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onSaveNickname(normalizedBssid, draftNickname.trim().ifEmpty { null })
                        },
                        modifier = Modifier
                            .heightIn(min = dimens.minTouchTarget)
                            .testTag(NetworksTestTags.DETAIL_NICKNAME_SAVE_CTA),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.emerald,
                            contentColor = colors.onAccentDark,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Save Nickname",
                            style = RadarTheme.typography.badgeLabel,
                            color = colors.onAccentDark,
                        )
                    }

                    if (!radio.customNickname.isNullOrBlank() || draftNickname.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                draftNickname = ""
                                onSaveNickname(normalizedBssid, null)
                            },
                            modifier = Modifier
                                .heightIn(min = dimens.minTouchTarget)
                                .testTag(NetworksTestTags.DETAIL_NICKNAME_CLEAR_CTA),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, colors.activeBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = colors.surfaceL1,
                                contentColor = colors.textSecondary,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "Clear Nickname",
                                style = RadarTheme.typography.badgeLabel,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }

            // Card 1: Signal, SNR, Distance & Channel
            RadarCard(accentTone = radio.signalTier.toSemanticTone(), highlighted = true) {
                Text(
                    text = "Signal, Range & Spectrum Channel",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SignalBadge(radio = radio)
                    BandPill(
                        descriptor = radio.channelDescriptor,
                        congestionScore0To100 = radio.channelCongestionScore0To100,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                DetailKeyValueRow(
                    label = "Smoothed Signal",
                    value = String.format(
                        Locale.US,
                        "%.1f dBm · %s",
                        radio.smoothedRssiDbm,
                        SemanticFormatters.formatQualityPct(radio.signalQualityPct, radio.signalTier),
                    ),
                )
                DetailKeyValueRow(
                    label = "Signal-to-Noise",
                    value = SemanticFormatters.formatSnr(radio.estimatedSnrDb),
                )
                DetailKeyValueRow(
                    label = "Estimated Distance",
                    value = formatEstimatedRadioRange(
                        meters = radio.estimatedDistanceMeters,
                        isCalibrated = radio.oneMeterCalibrationDbm != null,
                    ),
                )
                DetailKeyValueRow(
                    label = "Band & Channel",
                    value = "${SemanticFormatters.formatBandWithTrait(radio.channelDescriptor.band)} · ${
                        SemanticFormatters.formatChannelSummary(
                            radio.channelDescriptor,
                            radio.channelCongestionScore0To100,
                        )
                    }",
                )
                DetailKeyValueRow(
                    label = "Channel Width",
                    value = "${SemanticFormatters.formatChannelWidthWithTrait(radio.channelDescriptor.width)} (${radio.channelDescriptor.occupiedStartFreqMhz}–${radio.channelDescriptor.occupiedEndFreqMhz} MHz)",
                )
                DetailKeyValueRow(
                    label = "Sub-Band",
                    value = radio.channelDescriptor.uniiSubBand.displayLabel,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InlineLegendPill(topic = LegendTopic.DBM)
                    InlineLegendPill(topic = LegendTopic.SIGNAL_QUALITY)
                    if (radio.channelDescriptor.isDfs || radio.channelDescriptor.isTdwr) {
                        InlineLegendPill(topic = LegendTopic.DFS_TDWR)
                    }
                }
            }

            // Card 2: Security, PMF & Roaming
            RadarCard(accentTone = radio.security.grade.toSemanticTone()) {
                Text(
                    text = "Security Protection & Mesh Roaming",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                SecurityGradeBadge(assessment = radio.security)
                Spacer(modifier = Modifier.height(10.dp))

                if (radio.security.isEvilTwinSuspect) {
                    DetailAlertNote(
                        text = "Evil-Twin Warning · Unencrypted open clone shares the name of a password-protected network.",
                        tone = SemanticTone.WEAK,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                DetailKeyValueRow(
                    label = "Management Frames",
                    value = "${radio.security.pmfState.displayLabel} · ${radio.security.pmfState.plainEnglishNote}",
                )
                val ciphersText = radio.security.pairwiseCiphers
                    .map { it.displayLabel }
                    .ifEmpty { listOf(radio.security.groupCipher.displayLabel) }
                    .distinct()
                    .joinToString(", ")
                DetailKeyValueRow(
                    label = "Encryption Ciphers",
                    value = ciphersText,
                )
                if (radio.security.akmSuites.isNotEmpty()) {
                    DetailKeyValueRow(
                        label = "Authentication",
                        value = radio.security.akmSuites.map { it.displayLabel }.distinct().joinToString(", "),
                    )
                }
                if (radio.security.saeH2eSupported) {
                    DetailKeyValueRow(
                        label = "WPA3 H2E",
                        value = if (radio.security.saeH2eRequired) {
                            "Required · Modern Hash-to-Element protection"
                        } else {
                            "Supported · Modern Hash-to-Element protection"
                        },
                    )
                }
                if (radio.security.wpsEnabled) {
                    DetailKeyValueRow(
                        label = "WPS Setup",
                        value = "Enabled — disable WPS PIN in router admin for stronger security",
                    )
                }
                for (warning in radio.security.warnings) {
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailAlertNote(text = warning, tone = SemanticTone.FAIR)
                }

                Spacer(modifier = Modifier.height(8.dp))
                val roaming = radio.beaconDetails.roaming
                DetailKeyValueRow(
                    label = "Fast Roaming",
                    value = SemanticFormatters.formatRoamingSummary(roaming),
                )
                val capabilityLabels = buildList {
                    if (roaming.supports80211k) add("802.11k Neighbor Map")
                    if (roaming.supports80211v) add("802.11v Smart Steering")
                    if (roaming.supports80211r) add("802.11r Fast Handoff")
                    if (roaming.supportsMbo) add("Agile Multiband (MBO)")
                    if (radio.is80211mcResponder || roaming.ftmResponder) add("Wi-Fi RTT (802.11mc)")
                    if (radio.is80211azResponder) add("Next-Gen RTT (802.11az)")
                }
                if (capabilityLabels.isNotEmpty()) {
                    DetailKeyValueRow(
                        label = "Assisted Features",
                        value = capabilityLabels.joinToString(" · "),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InlineLegendPill(topic = LegendTopic.PMF_80211W)
                    InlineLegendPill(topic = LegendTopic.FAST_ROAMING_KVR)
                }
            }

            // Card 3: 802.11 Beacon PHY, BSS Load, BSS Color, MLO & WPS Hardware
            val details = radio.beaconDetails
            val phy = details.phyCapability
            RadarCard(accentTone = SemanticTone.GOOD) {
                Text(
                    text = "802.11 Beacon Radio & Hardware Capabilities",
                    style = RadarTheme.typography.metricMedium,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                DetailKeyValueRow(
                    label = "Wi-Fi Standard",
                    value = SemanticFormatters.formatWifiGeneration(radio.wifiGeneration),
                )
                DetailKeyValueRow(
                    label = "Antenna & Peak Rate",
                    value = "${phy.mimoLabel} · Up to ${phy.estimatedMaxPhyMbps} Mbps theoretical PHY",
                )
                val phyExtras = buildList {
                    if (phy.suBeamformer) add("Beamforming")
                    if (phy.muMimoSupported) add("MU-MIMO")
                    if (phy.twtResponder) add("Target Wake Time (Battery Saver)")
                    if (phy.supports4096Qam) add("4K-QAM")
                }
                if (phyExtras.isNotEmpty()) {
                    DetailKeyValueRow(
                        label = "Radio Enhancements",
                        value = phyExtras.joinToString(" · "),
                    )
                }
                DetailKeyValueRow(
                    label = "Channel Airtime Load",
                    value = SemanticFormatters.formatBssLoad(details.bssLoad),
                )

                details.bssColor?.let { colorInfo ->
                    val colorSummary = when {
                        colorInfo.isDisabled -> "Color ${colorInfo.colorCode} (Spatial reuse disabled)"
                        colorInfo.hasCollisionOnChannel ->
                            "Color ${colorInfo.colorCode} · Collision warning (${colorInfo.collidingBssids.size} same-channel neighbor)"
                        else -> "Color ${colorInfo.colorCode} · Clean spatial reuse tag"
                    }
                    DetailKeyValueRow(
                        label = "BSS Color",
                        value = colorSummary,
                    )
                }

                val mldMac = details.ehtMlo?.apMldMacAddress ?: radio.apMldMacAddress
                if (mldMac != null || details.ehtMlo != null || radio.mloLinks.isNotEmpty()) {
                    val mloSummary = buildList {
                        if (mldMac != null) add("MLD ${MacAddressUtils.normalizeBssid(mldMac)}")
                        details.ehtMlo?.mloLinkId?.let { add("Link #$it") }
                        if (details.ehtMlo?.isPreamblePunctured == true) {
                            add("Preamble Puncturing (${details.ehtMlo?.puncturedSubchannelCount ?: 1} subchannel)")
                        }
                        if (radio.mloLinks.isNotEmpty()) add("${radio.mloLinks.size} affiliated links")
                    }.joinToString(" · ")
                    if (mloSummary.isNotBlank()) {
                        DetailKeyValueRow(
                            label = "Wi-Fi 7 Multi-Link",
                            value = mloSummary,
                        )
                    }
                }

                val vendorSummary = buildString {
                    append(radio.vendorInfo.displayLabel)
                    if (radio.vendorInfo.isLocallyAdministered) {
                        append(" · Virtual / Locally Administered MAC")
                        radio.vendorInfo.inferredParentVendor?.let { parent ->
                            append(" (Hardware: $parent)")
                        }
                    }
                }
                DetailKeyValueRow(
                    label = "Hardware Vendor",
                    value = vendorSummary,
                )

                details.wpsDevice?.let { wps ->
                    val modelParts = listOfNotNull(
                        wps.manufacturer?.takeIf { it.isNotBlank() },
                        wps.modelName?.takeIf { it.isNotBlank() },
                        wps.modelNumber?.takeIf { it.isNotBlank() },
                        wps.deviceName?.takeIf { it.isNotBlank() },
                        wps.advertisedApHostname?.takeIf { it.isNotBlank() }?.let { "Host: $it" },
                    ).distinct()
                    if (modelParts.isNotEmpty()) {
                        DetailKeyValueRow(
                            label = "Router Model",
                            value = modelParts.joinToString(" · "),
                        )
                    }
                }

                details.timDtim?.let { dtim ->
                    DetailKeyValueRow(
                        label = "Beacon DTIM Interval",
                        value = "Every ${dtim.dtimPeriod} beacon${if (dtim.dtimPeriod == 1) "" else "s"}",
                    )
                }
                details.country?.let { country ->
                    DetailKeyValueRow(
                        label = "Regulatory Domain",
                        value = "${country.countryCode} (${country.regulatoryEnvironment})",
                    )
                }
                details.tpcApTxPowerDbm?.let { txDbm ->
                    DetailKeyValueRow(
                        label = "Router Transmit Power",
                        value = "$txDbm dBm advertised (802.11h TPC)",
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InlineLegendPill(topic = LegendTopic.BSS_LOAD)
                    if (details.bssColor != null) {
                        InlineLegendPill(topic = LegendTopic.BSS_COLOR)
                    }
                    if (mldMac != null || details.ehtMlo != null) {
                        InlineLegendPill(topic = LegendTopic.MLO_WIFI7)
                    }
                    if (radio.vendorInfo.isLocallyAdministered) {
                        InlineLegendPill(topic = LegendTopic.LAA_RANDOM_MAC)
                    }
                }
            }

            // Card 4: Co-located 6 GHz Neighbors (RNR IE 201)
            if (details.colocated6GhzNeighbors.isNotEmpty()) {
                RadarCard(accentTone = SemanticTone.SPECTRUM_6GHZ) {
                    Text(
                        text = "Co-Located 6 GHz Radios (RNR Beacon Report)",
                        style = RadarTheme.typography.metricMedium,
                        color = colors.violet,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Advertised out-of-band by this router via 802.11ax/be Reduced Neighbor Report (IE 201).",
                        style = RadarTheme.typography.explanationBody,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    details.colocated6GhzNeighbors.forEach { neighbor ->
                        RnrNeighborDetailRow(neighbor = neighbor)
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    InlineLegendPill(topic = LegendTopic.PSC_6GHZ)
                }
            }
        }
    }
}

@Composable
private fun RnrNeighborDetailRow(neighbor: Colocated6GhzNeighbor) {
    val colors = RadarTheme.colors
    val violetSpec = colors.toneSpec(SemanticTone.SPECTRUM_6GHZ)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = violetSpec.container,
        border = BorderStroke(1.dp, violetSpec.border),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val pscTag = if (neighbor.isPscChannel) " · PSC Preferred Channel" else ""
            Text(
                text = "6 GHz · Ch ${neighbor.channelNumber} (${neighbor.centerFreqMhz} MHz · ${neighbor.widthMhz} MHz)$pscTag",
                style = RadarTheme.typography.badgeLabel,
                color = violetSpec.foreground,
            )
            neighbor.neighborBssid?.takeIf { it.isNotBlank() }?.let { bssid ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "6 GHz Radio BSSID: ${MacAddressUtils.normalizeBssid(bssid)}",
                    style = RadarTheme.typography.monoAddress,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailKeyValueRow(
    label: String,
    value: String,
) {
    val colors = RadarTheme.colors
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = RadarTheme.typography.badgeLabel,
            color = colors.textMuted,
        )
        Text(
            text = value,
            style = RadarTheme.typography.explanationBody,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun DetailAlertNote(
    text: String,
    tone: SemanticTone,
) {
    val spec = RadarTheme.colors.toneSpec(tone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = spec.container,
        border = BorderStroke(1.dp, spec.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = RadarIcons.Warning,
                contentDescription = null,
                tint = spec.foreground,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = RadarTheme.typography.explanationBody,
                color = spec.foreground,
            )
        }
    }
}

internal fun formatEstimatedRadioRange(
    meters: Double,
    isCalibrated: Boolean,
): String {
    val method = if (isCalibrated) {
        DistanceMethod.USER_CALIBRATED_PATH_LOSS
    } else {
        DistanceMethod.PATH_LOSS_ESTIMATE
    }
    val rangeStr = SemanticFormatters.formatDistanceRange(
        meters = meters,
        rangeMinMeters = (meters * 0.72).coerceAtLeast(0.3),
        rangeMaxMeters = (meters * 1.38).coerceAtMost(60.0),
        method = method,
    )
    return if (isCalibrated) "$rangeStr (1m Calibrated)" else rangeStr
}

internal fun buildPlainEnglishNetworkSummary(
    radio: BssidRadio,
    detailSheet: NetworkDetailSheetState,
): String {
    val title = NetworksProjector.radioDisplayTitle(radio)
    val normBssid = MacAddressUtils.normalizeBssid(radio.bssid)
    return buildString {
        appendLine("WIFIREDEYE — Network Diagnostic Summary")
        appendLine("Network: $title")
        if (!radio.customNickname.isNullOrBlank()) {
            val rawSsid = if (radio.ssid.isEffectivelyHidden()) {
                DecodedSsid.HIDDEN_NETWORK_LABEL
            } else {
                radio.ssid.displayName
            }
            appendLine("Broadcast SSID: $rawSsid")
        }
        appendLine("Radio Address (BSSID): $normBssid")
        appendLine("Hardware Vendor: ${radio.vendorInfo.displayLabel}")
        if (!detailSheet.isCurrentlyInRange) {
            appendLine("Status: Out of range · Last seen ${SemanticFormatters.formatAge(detailSheet.dropoutAgeMs)}")
        }
        appendLine("Signal: ${SemanticFormatters.formatRssiForDiscovery(radio.rssiDbm)} (${SemanticFormatters.formatQualityPct(radio.signalQualityPct, radio.signalTier)})")
        appendLine("SNR: ${SemanticFormatters.formatSnr(radio.estimatedSnrDb)}")
        appendLine("Channel: ${SemanticFormatters.formatBandWithTrait(radio.channelDescriptor.band)} · ${SemanticFormatters.formatChannelSummary(radio.channelDescriptor, radio.channelCongestionScore0To100)}")
        appendLine("Standard: ${SemanticFormatters.formatWifiGeneration(radio.wifiGeneration)} (${radio.beaconDetails.phyCapability.mimoLabel}, up to ${radio.beaconDetails.phyCapability.estimatedMaxPhyMbps} Mbps)")
        appendLine("Security: ${SemanticFormatters.formatSecuritySummary(radio.security)} · ${radio.security.pmfState.displayLabel}")
        appendLine("Airtime Load: ${SemanticFormatters.formatBssLoad(radio.beaconDetails.bssLoad)}")
        appendLine("Roaming: ${SemanticFormatters.formatRoamingSummary(radio.beaconDetails.roaming)}")
    }.trimEnd()
}

private fun copySummaryToClipboard(context: Context, summary: String) {
    runCatching {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Wi-Fi Network Summary", summary))
    }
}
