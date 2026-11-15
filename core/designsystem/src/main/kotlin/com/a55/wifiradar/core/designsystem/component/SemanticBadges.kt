package com.a55.wifiradar.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.resolveScanPillMode
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.WifiBand

enum class ScanPillMode {
    LIVE,
    THROTTLED_PASSIVE,
    WIFI_OFF_SCANNING,
    PAUSED_BLOCKED,
}

object RadarSemanticQualifiers {
    fun signalSpec(rssiDbm: Int): Pair<String, SemanticTone> = when {
        rssiDbm >= -50 -> "Excellent · Very Close" to SemanticTone.EXCELLENT
        rssiDbm >= -65 -> "Strong · Full Speed" to SemanticTone.GOOD
        rssiDbm >= -75 -> "Fair · Moderate Distance" to SemanticTone.FAIR
        rssiDbm >= -85 -> "Weak · Far / Obstructed" to SemanticTone.WEAK
        else -> "Edge of Reach · Unreliable" to SemanticTone.WEAK
    }

    fun bandSpec(frequencyMhz: Int): Triple<String, String, SemanticTone> = when {
        frequencyMhz in 2400..2500 -> Triple("2.4 GHz", "Longer Range", SemanticTone.GOOD)
        frequencyMhz in 5150..5900 -> Triple("5 GHz", "Fast", SemanticTone.EXCELLENT)
        frequencyMhz in 5925..7125 -> Triple("6 GHz", "Ultra-Fast", SemanticTone.SPECTRUM_6GHZ)
        frequencyMhz >= 57000 -> Triple("60 GHz", "Line-of-Sight", SemanticTone.INFO)
        else -> Triple("Custom Band", "Unclassified Frequency", SemanticTone.NEUTRAL)
    }

    fun bandSpec(bandLabel: String): Pair<String, SemanticTone> = when (bandLabel.trim()) {
        "2.4 GHz" -> "2.4 GHz · Longer Range" to SemanticTone.GOOD
        "5 GHz" -> "5 GHz · Fast" to SemanticTone.EXCELLENT
        "6 GHz" -> "6 GHz · Ultra-Fast" to SemanticTone.SPECTRUM_6GHZ
        "60 GHz" -> "60 GHz · Line-of-Sight" to SemanticTone.INFO
        else -> "$bandLabel · Wireless Band" to SemanticTone.NEUTRAL
    }

    fun widthQualifier(widthMhz: Int, is80Plus80: Boolean = false): String = when {
        is80Plus80 -> "80+80 MHz · Split Dual-Lane"
        widthMhz >= 320 -> "320 MHz · Wi-Fi 7 Super-Lane"
        widthMhz >= 160 -> "160 MHz · Ultra-Wide Gigabit"
        widthMhz >= 80 -> "80 MHz · Wide Fast Lane"
        widthMhz >= 40 -> "40 MHz · Balanced Width"
        else -> "20 MHz · Clean Narrow Lane"
    }

    fun securitySpec(grade: String): Pair<String, SemanticTone> = when (grade.trim().uppercase()) {
        "A+" -> "A+ · WPA3 + PMF Protected" to SemanticTone.EXCELLENT
        "A", "A-" -> "${grade.trim().uppercase()} · Strong Modern Security" to SemanticTone.EXCELLENT
        "B" -> "B · Standard WPA2 Security" to SemanticTone.GOOD
        "C" -> "C · Mixed / WPS Exposed" to SemanticTone.FAIR
        "D" -> "D · Legacy Cipher Risk" to SemanticTone.FAIR
        else -> "F · Unsecured / Open Risk" to SemanticTone.WEAK
    }

    fun stabilitySpec(stdDevDb: Double): Pair<String, SemanticTone> = when {
        stdDevDb <= 2.0 -> "Rock Solid" to SemanticTone.EXCELLENT
        stdDevDb <= 4.5 -> "Steady" to SemanticTone.GOOD
        else -> "Fluctuating" to SemanticTone.FAIR
    }
}

object RadarSemanticGuard {
    fun containsRawEnumLeak(text: String): Boolean =
        SemanticFormatters.containsRawEnumLeak(text)
}

@Composable
fun SignalBadge(
    rssiDbm: Int,
    modifier: Modifier = Modifier,
    prefixLabel: String? = null,
) {
    val (qualifier, tone) = RadarSemanticQualifiers.signalSpec(rssiDbm)
    val label = buildString {
        if (!prefixLabel.isNullOrBlank()) {
            append(prefixLabel)
            append(": ")
        }
        append("$rssiDbm dBm · $qualifier")
    }
    OpaqueTonePill(
        text = label,
        tone = tone,
        modifier = modifier,
    )
}

@Composable
fun SignalBadge(
    rssiDbm: Int,
    tier: SignalQualityTier,
    modifier: Modifier = Modifier,
    prefixLabel: String? = null,
) {
    val tone = tier.toSemanticTone()
    val summary = SemanticFormatters.formatRssiForDiscovery(rssiDbm)
    val label = if (!prefixLabel.isNullOrBlank()) "$prefixLabel: $summary" else summary
    OpaqueTonePill(
        text = label,
        tone = tone,
        modifier = modifier,
    )
}

@Composable
fun SignalBadge(
    radio: BssidRadio,
    modifier: Modifier = Modifier,
    prefixLabel: String? = null,
) {
    SignalBadge(
        rssiDbm = radio.rssiDbm,
        tier = radio.signalTier,
        modifier = modifier,
        prefixLabel = prefixLabel,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SemanticMetricRow(
    label: String,
    numericValue: String,
    plainEnglishQualifier: String,
    tone: SemanticTone,
    modifier: Modifier = Modifier,
    legendTopic: LegendTopic? = null,
) {
    val colors = RadarTheme.colors

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            modifier = Modifier.align(Alignment.CenterVertically),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = RadarTheme.typography.metricMedium,
                color = colors.textSecondary,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            if (legendTopic != null) {
                InlineLegendPill(
                    topic = legendTopic,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        OpaqueTonePill(
            text = "$numericValue · $plainEnglishQualifier",
            tone = tone,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Composable
fun BandPill(
    bandName: String,
    qualifier: String,
    tone: SemanticTone,
    modifier: Modifier = Modifier,
    channelSummary: String? = null,
) {
    val text = buildString {
        append("$bandName · $qualifier")
        if (!channelSummary.isNullOrBlank()) {
            append(" · ")
            append(channelSummary)
        }
    }
    OpaqueTonePill(
        text = text,
        tone = tone,
        modifier = modifier,
    )
}

@Composable
fun BandPill(
    frequencyMhz: Int,
    channel: Int,
    widthMhz: Int,
    modifier: Modifier = Modifier,
) {
    val (bandName, qualifier, tone) = RadarSemanticQualifiers.bandSpec(frequencyMhz)
    val chText = if (channel > 0) "Ch $channel (${widthMhz} MHz)" else "${frequencyMhz} MHz (${widthMhz} MHz)"
    BandPill(
        bandName = bandName,
        qualifier = qualifier,
        tone = tone,
        channelSummary = chText,
        modifier = modifier,
    )
}

@Composable
fun BandPill(
    band: WifiBand,
    descriptor: ChannelDescriptor? = null,
    congestionScore0To100: Int? = null,
    modifier: Modifier = Modifier,
) {
    val chSummary = descriptor?.let {
        SemanticFormatters.formatChannelSummary(it, congestionScore0To100)
    }
    BandPill(
        bandName = band.displayLabel,
        qualifier = band.traitLabel,
        tone = band.toSemanticTone(),
        channelSummary = chSummary,
        modifier = modifier,
    )
}

@Composable
fun BandPill(
    descriptor: ChannelDescriptor,
    congestionScore0To100: Int? = null,
    modifier: Modifier = Modifier,
) {
    BandPill(
        band = descriptor.band,
        descriptor = descriptor,
        congestionScore0To100 = congestionScore0To100,
        modifier = modifier,
    )
}

@Composable
fun SecurityGradeBadge(
    grade: String,
    modifier: Modifier = Modifier,
    protocolSummary: String? = null,
) {
    val (defaultSummary, tone) = RadarSemanticQualifiers.securitySpec(grade)
    val label = if (!protocolSummary.isNullOrBlank()) {
        "${grade.trim()} · $protocolSummary"
    } else {
        defaultSummary
    }
    OpaqueTonePill(
        text = label,
        tone = tone,
        leadingLockIcon = true,
        modifier = modifier,
    )
}

@Composable
fun SecurityGradeBadge(
    grade: SecurityGrade,
    protocol: SecurityProtocol? = null,
    modifier: Modifier = Modifier,
) {
    val label = if (protocol != null) {
        "${grade.badgeText} · ${protocol.displayLabel} · ${protocol.plainEnglishSummary}"
    } else {
        "${grade.badgeText} · ${grade.plainEnglishTier}"
    }
    OpaqueTonePill(
        text = label,
        tone = grade.toSemanticTone(),
        leadingLockIcon = true,
        modifier = modifier,
    )
}

@Composable
fun SecurityGradeBadge(
    assessment: SecurityAssessment,
    modifier: Modifier = Modifier,
) {
    OpaqueTonePill(
        text = SemanticFormatters.formatSecuritySummary(assessment),
        tone = assessment.grade.toSemanticTone(),
        leadingLockIcon = true,
        modifier = modifier,
    )
}

@Composable
fun ScanStatusTopPill(
    mode: ScanPillMode,
    networkCount: Int,
    updatedSecondsAgo: Int,
    modifier: Modifier = Modifier,
    throttleCountdownSec: Int? = null,
    onClickExplain: (() -> Unit)? = null,
) {
    val netNoun = if (networkCount == 1) "Network" else "Networks"
    val ageText = if (updatedSecondsAgo <= 2) "Just now" else "${updatedSecondsAgo}s ago"
    val (label, tone) = when (mode) {
        ScanPillMode.LIVE ->
            "Live · $networkCount $netNoun · $ageText" to SemanticTone.EXCELLENT
        ScanPillMode.THROTTLED_PASSIVE -> {
            val wait = (throttleCountdownSec ?: 15).coerceAtLeast(1)
            "OS Throttle · Next sweep ${wait}s (Passive Live)" to SemanticTone.FAIR
        }
        ScanPillMode.WIFI_OFF_SCANNING ->
            "Wi-Fi Off · Scanning Active ($networkCount $netNoun)" to SemanticTone.INFO
        ScanPillMode.PAUSED_BLOCKED ->
            "Setup Needed · Tap Checklist Below" to SemanticTone.WEAK
    }
    ScanStatusPillSurface(
        label = label,
        tone = tone,
        onClickExplain = onClickExplain,
        modifier = modifier,
    )
}

@Composable
fun ScanStatusTopPill(
    networkCount: Int,
    telemetry: ScanTelemetry,
    modifier: Modifier = Modifier,
    readinessState: ScannerReadinessState = ScannerReadinessState.Ready(),
    onClickExplain: (() -> Unit)? = null,
) {
    val mode = resolveScanPillMode(telemetry = telemetry, readinessState = readinessState)
    val (label, tone) = when (mode) {
        ScanPillMode.PAUSED_BLOCKED ->
            "Setup Needed · Tap Checklist Below" to SemanticTone.WEAK
        ScanPillMode.WIFI_OFF_SCANNING ->
            "Wi-Fi Off · Scanning Active ($networkCount ${if (networkCount == 1) "Network" else "Networks"})" to SemanticTone.INFO
        ScanPillMode.THROTTLED_PASSIVE ->
            SemanticFormatters.formatScanStatusPill(networkCount, telemetry) to SemanticTone.FAIR
        ScanPillMode.LIVE ->
            SemanticFormatters.formatScanStatusPill(networkCount, telemetry) to SemanticTone.EXCELLENT
    }
    ScanStatusPillSurface(
        label = label,
        tone = tone,
        onClickExplain = onClickExplain,
        modifier = modifier,
    )
}

@Composable
private fun ScanStatusPillSurface(
    label: String,
    tone: SemanticTone,
    onClickExplain: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val toneSpec = RadarTheme.colors.toneSpec(tone)
    val pillModifier = modifier
        .heightIn(min = 34.dp)
        .semantics(mergeDescendants = true) {
            if (onClickExplain != null) role = Role.Button
            contentDescription = label
        }
        .testTag(RadarTestTags.SCAN_STATUS_PILL)

    if (onClickExplain != null) {
        Surface(
            onClick = onClickExplain,
            modifier = pillModifier,
            shape = RoundedCornerShape(12.dp),
            color = toneSpec.container,
            contentColor = toneSpec.foreground,
            border = BorderStroke(1.dp, toneSpec.border),
        ) {
            ScanStatusPillInner(label = label, dotColor = toneSpec.foreground, fgColor = toneSpec.foreground)
        }
    } else {
        Surface(
            modifier = pillModifier,
            shape = RoundedCornerShape(12.dp),
            color = toneSpec.container,
            contentColor = toneSpec.foreground,
            border = BorderStroke(1.dp, toneSpec.border),
        ) {
            ScanStatusPillInner(label = label, dotColor = toneSpec.foreground, fgColor = toneSpec.foreground)
        }
    }
}

@Composable
private fun ScanStatusPillInner(
    label: String,
    dotColor: androidx.compose.ui.graphics.Color,
    fgColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            style = RadarTheme.typography.badgeLabel,
            color = fgColor,
        )
    }
}

@Composable
private fun OpaqueTonePill(
    text: String,
    tone: SemanticTone,
    modifier: Modifier = Modifier,
    leadingLockIcon: Boolean = false,
) {
    val toneSpec = RadarTheme.colors.toneSpec(tone)

    Surface(
        modifier = modifier
            .heightIn(min = 28.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = text
            },
        shape = RoundedCornerShape(12.dp),
        color = toneSpec.container,
        contentColor = toneSpec.foreground,
        border = BorderStroke(1.dp, toneSpec.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingLockIcon) {
                Icon(
                    imageVector = RadarIcons.Lock,
                    contentDescription = null,
                    tint = toneSpec.foreground,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
            }
            Text(
                text = text,
                style = RadarTheme.typography.badgeLabel,
                color = toneSpec.foreground,
            )
        }
    }
}
