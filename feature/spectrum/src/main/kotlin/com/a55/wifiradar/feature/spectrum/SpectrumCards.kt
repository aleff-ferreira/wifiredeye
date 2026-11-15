package com.a55.wifiradar.feature.spectrum

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.CciAciBreakdown
import com.a55.wifiradar.core.model.ChannelInterferer
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ChannelScore
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.InterferenceType
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.WifiBand

private const val COLLAPSED_CHANNEL_SCORE_THRESHOLD = 16
private const val INITIAL_VISIBLE_CHANNEL_SCORES = 14

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SixGhzHardwareCapabilityBanner(
    headline: String,
    explanationBody: String,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val violetTone = colors.toneSpec(SemanticTone.SPECTRUM_6GHZ)

    RadarElevatedCard(
        modifier = modifier.testTag(SpectrumTestTags.HARDWARE_6GHZ_BANNER),
        accentTone = SemanticTone.SPECTRUM_6GHZ,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RadarIcons.Info,
                contentDescription = null,
                tint = violetTone.foreground,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = headline,
                style = typography.metricMedium,
                color = violetTone.foreground,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = explanationBody,
            style = typography.explanationBody,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToneBadgePill(
            text = "Out-of-Band RNR Discovery Active",
            tone = SemanticTone.SPECTRUM_6GHZ,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Colocated6GhzRnrCard(
    neighbors: List<Colocated6GhzNeighbor>,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    RadarCard(
        modifier = modifier.testTag(SpectrumTestTags.RNR_NEIGHBORS_CARD),
        accentTone = SemanticTone.SPECTRUM_6GHZ,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Co-Located 6 GHz Routers Discovered via Beacons (RNR)",
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            ToneBadgePill(
                text = "${neighbors.size} Discovered",
                tone = SemanticTone.SPECTRUM_6GHZ,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        if (neighbors.isEmpty()) {
            Text(
                text = "No nearby 2.4 or 5 GHz routers are currently advertising co-located 6 GHz radios in their Reduced Neighbor Report (IE 201) beacons.",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
        } else {
            Text(
                text = "Decoded from 2.4 & 5 GHz Reduced Neighbor Report (IE 201) frames so you can plan Wi-Fi 6E and Wi-Fi 7 channel usage even without a 6 GHz phone radio.",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neighbors.forEachIndexed { index, neighbor ->
                    val ssidTitle = neighbor.sourceSsid.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL }
                    val centerMhz = if (neighbor.centerFreqMhz > 0) {
                        neighbor.centerFreqMhz
                    } else {
                        5950 + neighbor.channelNumber * 5
                    }
                    val isPsc = neighbor.isPscChannel ||
                        SpectrumDomainResolver.is6GhzPscChannel(neighbor.channelNumber)

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SpectrumTestTags.rnrNeighborRow(index)),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceL2,
                        border = BorderStroke(1.dp, colors.activeBorder),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "$ssidTitle · 6 GHz Ch ${neighbor.channelNumber} ($centerMhz MHz)",
                                style = typography.metricMedium,
                                color = colors.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ToneBadgePill(
                                    text = "${neighbor.widthMhz.coerceAtLeast(20)} MHz Width",
                                    tone = SemanticTone.SPECTRUM_6GHZ,
                                )
                                if (isPsc) {
                                    ToneBadgePill(
                                        text = "PSC · Fast Discovery",
                                        tone = SemanticTone.EXCELLENT,
                                    )
                                }
                                if (neighbor.isSameSsid) {
                                    ToneBadgePill(
                                        text = "Same SSID Tri-Band",
                                        tone = SemanticTone.GOOD,
                                    )
                                }
                                ToneBadgePill(
                                    text = "Advertised via ${MacAddressUtils.normalizeBssid(neighbor.sourceBssid)}",
                                    tone = SemanticTone.NEUTRAL,
                                )
                                neighbor.neighborBssid?.takeIf { it.isNotBlank() }?.let { nbrMac ->
                                    ToneBadgePill(
                                        text = "6 GHz Radio ${MacAddressUtils.normalizeBssid(nbrMac)}",
                                        tone = SemanticTone.INFO,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BestChannelRecommendationCard(
    selectedBand: WifiBand,
    recommendation: ChannelRecommendation,
    onSelectRecommendedChannel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val clampedScore = recommendation.score0To100.coerceIn(0, 100)
    val safeStars = if (recommendation.starRating0To5.isFinite()) {
        recommendation.starRating0To5.coerceIn(0.0, 5.0)
    } else {
        (clampedScore / 20.0).coerceIn(0.0, 5.0)
    }
    val recTone = scoreToTone(clampedScore)
    val formattedScore = SemanticFormatters.formatChannelScore(clampedScore, safeStars)
    val cleanReason = SpectrumDomainResolver.sanitizeUiSentence(
        raw = recommendation.plainEnglishReason,
        fallback = "Channel ${recommendation.recommendedPrimaryChannel} offers the cleanest ${selectedBand.displayLabel} spectrum with minimal neighbor overlap.",
    )

    RadarElevatedCard(
        modifier = modifier.testTag(SpectrumTestTags.BEST_CHANNEL_CARD),
        accentTone = recTone,
        onClick = { onSelectRecommendedChannel(recommendation.recommendedPrimaryChannel) },
    ) {
        Text(
            text = "Best Channel Recommendation · ${selectedBand.displayLabel}",
            style = typography.badgeLabel,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(
                    text = "Channel ${recommendation.recommendedPrimaryChannel}",
                    style = typography.metricLarge,
                    color = colors.toneSpec(recTone).foreground,
                )
                Text(
                    text = "${recommendation.recommendedCenterFreqMhz} MHz · ${SemanticFormatters.formatChannelWidthWithTrait(recommendation.recommendedWidth)}",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            ToneBadgePill(
                text = formattedScore,
                tone = recTone,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        val curConnChannel = recommendation.currentConnectedChannel
        val scoreGain = recommendation.estimatedScoreGain
        if (recommendation.isCurrentChannelAlreadyOptimal) {
            Spacer(modifier = Modifier.height(8.dp))
            ToneBadgePill(
                text = "Your Connected Router Is Already on the Optimal Channel",
                tone = SemanticTone.EXCELLENT,
            )
        } else if (curConnChannel != null && scoreGain != null && scoreGain > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            val curScore = (recommendation.currentConnectedScore0To100 ?: 0).coerceIn(0, 100)
            ToneBadgePill(
                text = "Upgrades Ch $curConnChannel ($curScore/100) by +$scoreGain pts",
                tone = SemanticTone.GOOD,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = cleanReason,
            style = typography.explanationBody,
            color = colors.textPrimary,
            modifier = Modifier.testTag(SpectrumTestTags.BEST_CHANNEL_REASON),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelCongestionScoresCard(
    selectedBand: WifiBand,
    channelScores: List<ChannelScore>,
    recommendedChannel: Int?,
    highlightedChannel: Int?,
    onSelectChannel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    var showAllChannels by rememberSaveable(selectedBand.name) { mutableStateOf(false) }

    RadarCard(
        modifier = modifier.testTag(SpectrumTestTags.CHANNEL_SCORES_CARD),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Text(
                    text = "Per-Channel Congestion & Star Ratings",
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Tap any channel row to inspect shared vs overlapping interference",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            if (selectedBand == WifiBand.BAND_5_GHZ) {
                ToneBadgePill(text = "5 GHz U-NII", tone = SemanticTone.EXCELLENT)
            } else if (selectedBand == WifiBand.BAND_6_GHZ) {
                ToneBadgePill(text = "6 GHz PSC", tone = SemanticTone.SPECTRUM_6GHZ)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (channelScores.isEmpty()) {
            Text(
                text = "Channel congestion scores will appear once ${selectedBand.displayLabel} scan telemetry is available.",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
        } else {
            val visibleScores = if (showAllChannels || channelScores.size <= COLLAPSED_CHANNEL_SCORE_THRESHOLD) {
                channelScores
            } else {
                val prioritized = channelScores.sortedWith(
                    compareByDescending<ChannelScore> { it.channel == recommendedChannel }
                        .thenByDescending { it.channel == highlightedChannel }
                        .thenByDescending { it.isCanonical || it.isPsc }
                        .thenByDescending { it.cciCount + it.aciCount > 0 }
                        .thenByDescending { it.score0To100 }
                        .thenBy { it.channel }
                )
                prioritized.take(INITIAL_VISIBLE_CHANNEL_SCORES).sortedBy { it.channel }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleScores.forEach { score ->
                    ChannelScoreItemRow(
                        score = score,
                        isRecommended = recommendedChannel != null && score.channel == recommendedChannel,
                        isHighlighted = highlightedChannel != null && score.channel == highlightedChannel,
                        onClick = { onSelectChannel(score.channel) },
                    )
                }
            }

            if (channelScores.size > COLLAPSED_CHANNEL_SCORE_THRESHOLD) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showAllChannels = !showAllChannels },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(SpectrumTestTags.CHANNEL_SCORES_EXPAND_BUTTON),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                ) {
                    Text(
                        text = if (showAllChannels) {
                            "Collapse to Key Channels"
                        } else {
                            "Show All ${channelScores.size} Channels"
                        },
                        style = typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelScoreItemRow(
    score: ChannelScore,
    isRecommended: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val clampedScore = score.score0To100.coerceIn(0, 100)
    val safeStars = if (score.starRating0To5.isFinite()) {
        score.starRating0To5.coerceIn(0.0, 5.0)
    } else {
        (clampedScore / 20.0).coerceIn(0.0, 5.0)
    }
    val formattedScore = SemanticFormatters.formatChannelScore(clampedScore, safeStars)
    val rowTone = scoreToTone(clampedScore)
    val rowToneSpec = colors.toneSpec(rowTone)
    val safeNoiseFloor = SpectrumDomainResolver.safeRoundToInt(score.equivalentNoiseFloorDbm, -95)
        .coerceIn(-110, -20)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (isHighlighted) {
                    "Inspected · $formattedScore"
                } else {
                    formattedScore
                }
            }
            .testTag(SpectrumTestTags.channelScoreRow(score.channel)),
        shape = RoundedCornerShape(12.dp),
        color = if (isHighlighted) colors.surfaceL2 else colors.surfaceL1,
        border = BorderStroke(
            width = if (isHighlighted || isRecommended) 1.5.dp else 1.dp,
            color = when {
                isHighlighted -> colors.cyan
                isRecommended -> colors.emerald
                else -> colors.border
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Ch ${score.channel} (${score.centerFreqMhz} MHz · ${score.evaluatedWidth.displayLabel})",
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                ToneBadgePill(
                    text = formattedScore,
                    tone = rowTone,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isRecommended) {
                    ToneBadgePill(text = "Best Pick", tone = SemanticTone.EXCELLENT)
                }
                if (score.isCanonical) {
                    ToneBadgePill(text = "Canonical", tone = SemanticTone.GOOD)
                }
                if (score.isPsc) {
                    ToneBadgePill(text = "PSC · Fast Discovery", tone = SemanticTone.SPECTRUM_6GHZ)
                }
                if (score.isTdwr) {
                    ToneBadgePill(text = "Weather Radar DFS", tone = SemanticTone.WEAK)
                } else if (score.isDfs) {
                    ToneBadgePill(text = "DFS Radar Shared", tone = SemanticTone.FAIR)
                }
                if (score.uniiSubBand != UniiSubBand.UNKNOWN && score.uniiSubBand != UniiSubBand.ISM_2_4_GHZ) {
                    ToneBadgePill(text = score.uniiSubBand.displayLabel, tone = SemanticTone.NEUTRAL)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            val fraction = (clampedScore / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.border)
                    .drawWithCache {
                        val radius = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                        val barWidth = (size.width * fraction).coerceAtLeast(if (fraction > 0f) 4.dp.toPx() else 0f)
                        onDrawBehind {
                            if (barWidth > 0f) {
                                drawRoundRect(
                                    color = rowToneSpec.foreground,
                                    size = Size(barWidth.coerceAtMost(size.width), size.height),
                                    cornerRadius = radius,
                                )
                            }
                        }
                    },
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Shared (CCI): ${score.cciCount} · Overlapping (ACI): ${score.aciCount} · Noise Floor: $safeNoiseFloor dBm",
                style = typography.explanationBody,
                color = colors.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CciVsAciBreakdownSection(
    targetLabel: String,
    breakdown: CciAciBreakdown?,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(SpectrumTestTags.CCI_ACI_SECTION),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Text(
                    text = "Interference Breakdown · $targetLabel",
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Co-Channel polite turn-taking vs Adjacent-Channel sideband collisions",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            ToneBadgePill(
                text = "Shared vs Sideband",
                tone = SemanticTone.INFO,
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useTwoColumns = maxWidth >= 560.dp && fontScale <= 1.35f
            if (useTwoColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InterferenceDetailCard(
                        type = InterferenceType.CO_CHANNEL_CCI,
                        interferers = breakdown?.cciInterferers.orEmpty(),
                        actionableCount = breakdown?.actionableCciCount ?: 0,
                        summary = SpectrumDomainResolver.sanitizeUiSentence(
                            raw = breakdown?.cciPlainEnglishSummary,
                            fallback = "No shared-channel routers currently contending on this channel.",
                        ),
                        hasSevereWarning = false,
                        testTag = SpectrumTestTags.CCI_CARD,
                        modifier = Modifier.weight(1f),
                    )
                    InterferenceDetailCard(
                        type = InterferenceType.ADJACENT_CHANNEL_ACI,
                        interferers = breakdown?.aciInterferers.orEmpty(),
                        actionableCount = breakdown?.actionableAciCount ?: 0,
                        summary = SpectrumDomainResolver.sanitizeUiSentence(
                            raw = breakdown?.aciPlainEnglishSummary,
                            fallback = "Zero sideband overlapping channel collisions detected.",
                        ),
                        hasSevereWarning = breakdown?.hasSevereAciWarning == true,
                        testTag = SpectrumTestTags.ACI_CARD,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    InterferenceDetailCard(
                        type = InterferenceType.CO_CHANNEL_CCI,
                        interferers = breakdown?.cciInterferers.orEmpty(),
                        actionableCount = breakdown?.actionableCciCount ?: 0,
                        summary = SpectrumDomainResolver.sanitizeUiSentence(
                            raw = breakdown?.cciPlainEnglishSummary,
                            fallback = "No shared-channel routers currently contending on this channel.",
                        ),
                        hasSevereWarning = false,
                        testTag = SpectrumTestTags.CCI_CARD,
                    )
                    InterferenceDetailCard(
                        type = InterferenceType.ADJACENT_CHANNEL_ACI,
                        interferers = breakdown?.aciInterferers.orEmpty(),
                        actionableCount = breakdown?.actionableAciCount ?: 0,
                        summary = SpectrumDomainResolver.sanitizeUiSentence(
                            raw = breakdown?.aciPlainEnglishSummary,
                            fallback = "Zero sideband overlapping channel collisions detected.",
                        ),
                        hasSevereWarning = breakdown?.hasSevereAciWarning == true,
                        testTag = SpectrumTestTags.ACI_CARD,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterferenceDetailCard(
    type: InterferenceType,
    interferers: List<ChannelInterferer>,
    actionableCount: Int,
    summary: String,
    hasSevereWarning: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val cardTone = when {
        hasSevereWarning -> SemanticTone.WEAK
        actionableCount == 0 -> SemanticTone.EXCELLENT
        type == InterferenceType.ADJACENT_CHANNEL_ACI -> SemanticTone.WEAK
        actionableCount >= 3 -> SemanticTone.FAIR
        else -> SemanticTone.GOOD
    }

    RadarCard(
        modifier = modifier.testTag(testTag),
        accentTone = cardTone,
        highlighted = hasSevereWarning,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = type.title,
                style = typography.metricMedium,
                color = colors.textPrimary,
            )
            ToneBadgePill(
                text = if (actionableCount == 0) {
                    "0 Actionable · Clean"
                } else {
                    "$actionableCount Actionable"
                },
                tone = cardTone,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = type.plainEnglishExplanation,
            style = typography.explanationBody,
            color = colors.textSecondary,
        )

        if (hasSevereWarning) {
            Spacer(modifier = Modifier.height(8.dp))
            val weakSpec = colors.toneSpec(SemanticTone.WEAK)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SpectrumTestTags.ACI_SEVERE_WARNING),
                shape = RoundedCornerShape(10.dp),
                color = weakSpec.container,
                border = BorderStroke(1.dp, weakSpec.border),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = RadarIcons.Warning,
                        contentDescription = null,
                        tint = weakSpec.foreground,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Severe Overlapping-Channel Noise · Uncoordinated packet retries likely",
                        style = typography.badgeLabel,
                        color = weakSpec.foreground,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = summary,
            style = typography.explanationBody,
            color = colors.textPrimary,
        )

        if (interferers.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                interferers.take(8).forEach { interferer ->
                    val safeDbm = SpectrumDomainResolver.safeRssiDbm(interferer.rssiDbm)
                    val overlapPct = SpectrumDomainResolver.safeRoundToInt(
                        interferer.overlapRatio0To1 * 100.0,
                        100,
                    ).coerceIn(1, 100)

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = colors.surfaceL2,
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Column {
                                    Text(
                                        text = interferer.displayLabel,
                                        style = typography.metricMedium,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = MacAddressUtils.normalizeBssid(interferer.bssid),
                                        style = typography.monoAddress,
                                        color = colors.textMuted,
                                    )
                                }
                                SignalBadge(rssiDbm = safeDbm)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ToneBadgePill(
                                    text = "Ch ${interferer.primaryChannel} · ${interferer.width.displayLabel}",
                                    tone = SemanticTone.INFO,
                                )
                                ToneBadgePill(
                                    text = interferer.relationship.displayLabel,
                                    tone = if (interferer.isActionable) cardTone else SemanticTone.NEUTRAL,
                                )
                                if (type == InterferenceType.ADJACENT_CHANNEL_ACI) {
                                    ToneBadgePill(
                                        text = "$overlapPct% Spectral Overlap",
                                        tone = SemanticTone.WEAK,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToneBadgePill(
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

private fun scoreToTone(score0To100: Int): SemanticTone = when {
    score0To100 >= 80 -> SemanticTone.EXCELLENT
    score0To100 >= 60 -> SemanticTone.GOOD
    score0To100 >= 40 -> SemanticTone.FAIR
    else -> SemanticTone.WEAK
}
