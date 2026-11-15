package com.a55.wifiradar.feature.insights.component

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.SecurityGradeBadge
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.feature.insights.EvilTwinAlertCardUi
import com.a55.wifiradar.feature.insights.InsightsStateMapper
import com.a55.wifiradar.feature.insights.InsightsTestTags
import com.a55.wifiradar.feature.insights.InsightsUiState
import com.a55.wifiradar.feature.insights.SecurityAuditRowUi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecurityAuditSectionCard(
    uiState: InsightsUiState,
    onNavigateToActionTarget: (InsightActionTarget) -> Unit,
    onToggleShowAllSecurityRows: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Prominent Evil-Twin Downgrade Alert Cards
        uiState.evilTwinAlerts.forEach { alert ->
            EvilTwinWarningCard(
                alert = alert,
                onLocateRogueClick = {
                    val rawBssid = alert.firstRawSuspectBssid
                    val target = if (rawBssid != null) {
                        ProximityTargetKey.BssidLock(
                            bssid = rawBssid,
                            ssidHint = alert.ssid,
                        )
                    } else {
                        ProximityTargetKey.FollowMeshSsid(ssid = alert.ssid)
                    }
                    onNavigateToActionTarget(InsightActionTarget.LaunchProximity(target))
                },
            )
        }

        // Per-Network Security Posture Audit Card
        RadarCard(
            modifier = Modifier.testTag(InsightsTestTags.SECURITY_SECTION_CARD),
            accentTone = if (uiState.weakSecurityCount > 0) SemanticTone.WEAK else SemanticTone.EXCELLENT,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column {
                    Text(
                        text = "Security Posture & Encryption Audit",
                        style = typography.metricMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Audits WPA3/WPA2 ciphers, PMF management protection, WPS PIN risk, and rogue clones",
                        style = typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InsightsTonePill(
                    text = "Weak / Open: ${uiState.weakSecurityCount}",
                    tone = if (uiState.weakSecurityCount > 0) SemanticTone.WEAK else SemanticTone.EXCELLENT,
                )
                InsightsTonePill(
                    text = "WPS Exposed: ${uiState.wpsExposedCount}",
                    tone = if (uiState.wpsExposedCount > 0) SemanticTone.FAIR else SemanticTone.GOOD,
                )
                InsightsTonePill(
                    text = "Modern WPA3 / PMF: ${uiState.modernProtectedCount}",
                    tone = SemanticTone.EXCELLENT,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (uiState.securityAuditRows.isEmpty()) {
                Text(
                    text = "Detected routers will be graded A+ through F here as soon as scan results arrive.",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    uiState.securityAuditRows.forEach { row ->
                        SecurityAuditItemRow(
                            row = row,
                            onInspectClick = {
                                onNavigateToActionTarget(
                                    InsightActionTarget.OpenNetworkDetail(bssid = row.rawBssid)
                                )
                            },
                        )
                    }
                }

                if (uiState.totalSecurityRadioCount > InsightsStateMapper.DEFAULT_VISIBLE_SECURITY_ROWS) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onToggleShowAllSecurityRows,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = RadarTheme.dimens.minTouchTarget)
                            .testTag(InsightsTestTags.SECURITY_SHOW_ALL_TOGGLE),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.activeBorder),
                    ) {
                        Text(
                            text = if (uiState.showAllSecurityRows) {
                                "Show Top ${InsightsStateMapper.DEFAULT_VISIBLE_SECURITY_ROWS} Priority Radios"
                            } else {
                                "Show All ${uiState.totalSecurityRadioCount} Audited Radios"
                            },
                            style = typography.badgeLabel,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvilTwinWarningCard(
    alert: EvilTwinAlertCardUi,
    onLocateRogueClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val weakSpec = colors.toneSpec(SemanticTone.WEAK)

    RadarElevatedCard(
        modifier = Modifier.testTag(InsightsTestTags.evilTwinAlertCard(alert.ssid)),
        accentTone = SemanticTone.WEAK,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = RadarIcons.Warning,
                contentDescription = null,
                tint = weakSpec.foreground,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Evil-Twin Downgrade Alert: \"${alert.ssid}\"",
                style = typography.metricLarge,
                color = weakSpec.foreground,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = alert.plainEnglishWarning,
            style = typography.explanationBody,
            color = colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            InsightsTonePill(
                text = "Legitimate: ${alert.legitimateProtocolLabel}",
                tone = SemanticTone.EXCELLENT,
            )
            InsightsTonePill(
                text = "Suspect Signal: ${alert.suspectSignalSummary}",
                tone = SemanticTone.WEAK,
            )
        }

        if (alert.formattedLegitimateBssids.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Protected BSSIDs: ${alert.formattedLegitimateBssids.joinToString(", ")}",
                style = typography.monoAddress,
                color = colors.textSecondary,
            )
        }

        if (alert.formattedSuspectOpenBssids.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Suspect Open BSSIDs: ${alert.formattedSuspectOpenBssids.joinToString(", ")}",
                style = typography.monoAddress,
                color = weakSpec.foreground,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onLocateRogueClick,
            modifier = Modifier
                .heightIn(min = RadarTheme.dimens.minTouchTarget)
                .testTag(InsightsTestTags.evilTwinLocateCta(alert.ssid)),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = weakSpec.foreground,
                contentColor = colors.onAccentDark,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = RadarIcons.Proximity,
                contentDescription = null,
                tint = colors.onAccentDark,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Locate Rogue Radio",
                style = typography.badgeLabel,
                color = colors.onAccentDark,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecurityAuditItemRow(
    row: SecurityAuditRowUi,
    onInspectClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InsightsTestTags.securityAuditRow(row.rawBssid)),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceL2,
        border = BorderStroke(
            width = if (row.security.isEvilTwinSuspect || row.security.grade.isWeakOrInsecure) 1.5.dp else 1.dp,
            color = when {
                row.security.isEvilTwinSuspect || row.security.grade.isWeakOrInsecure -> colors.coral
                row.isConnected -> colors.emerald
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
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.displayName,
                            style = typography.metricMedium,
                            color = colors.textPrimary,
                        )
                        if (row.isConnected) {
                            Spacer(modifier = Modifier.width(6.dp))
                            InsightsTonePill(text = "Connected", tone = SemanticTone.EXCELLENT)
                        }
                    }
                    Text(
                        text = "${row.formattedBssid} · ${row.vendorLabel}",
                        style = typography.monoAddress,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = row.channelSummary,
                        style = typography.explanationBody,
                        color = colors.textMuted,
                    )
                }
                SignalBadge(rssiDbm = row.rssiDbm)
            }

            Spacer(modifier = Modifier.height(8.dp))
            SecurityGradeBadge(assessment = row.security)

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InsightsTonePill(
                    text = "Score ${row.security.score0To100.coerceIn(0, 100)} / 100",
                    tone = if (row.security.grade.isWeakOrInsecure) SemanticTone.WEAK else SemanticTone.GOOD,
                )
                InsightsTonePill(
                    text = row.pmfLabel,
                    tone = row.pmfTone,
                )
                if (!row.cipherSummary.isNullOrBlank()) {
                    InsightsTonePill(
                        text = row.cipherSummary,
                        tone = if (row.hasWeakCipher) SemanticTone.WEAK else SemanticTone.INFO,
                    )
                }
                if (!row.wpsWarningLabel.isNullOrBlank()) {
                    InsightsTonePill(
                        text = row.wpsWarningLabel,
                        tone = SemanticTone.FAIR,
                    )
                }
                row.featureBadges.forEach { feat ->
                    InsightsTonePill(
                        text = feat,
                        tone = SemanticTone.EXCELLENT,
                    )
                }
            }

            if (row.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.warnings.forEach { warningText ->
                        Text(
                            text = "• $warningText",
                            style = typography.explanationBody,
                            color = colors.coral,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onInspectClick,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(InsightsTestTags.securityInspectCta(row.rawBssid)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = RadarIcons.Networks,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Inspect Network",
                    style = typography.badgeLabel,
                    color = colors.textPrimary,
                )
            }
        }
    }
}
