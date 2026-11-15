package com.a55.wifiradar.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toUiModel
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ScannerReadinessState

enum class ReadinessBlockerKind {
    MISSING_PERMISSION,
    APPROXIMATE_ONLY_NEEDS_PRECISE,
    PERMANENT_PERMISSION_DENIAL,
    LOCATION_DISABLED,
    WIFI_AND_SCAN_DISABLED,
    WIFI_DISABLED_SCAN_ALWAYS_ON,
}

enum class ReadinessStepStatus {
    COMPLETED,
    ACTION_REQUIRED,
    PENDING,
}

@Immutable
data class ReadinessStepUi(
    val id: String,
    val stepNumber: Int,
    val title: String,
    val detail: String,
    val status: ReadinessStepStatus,
)

@Immutable
data class ReadinessChecklistUiModel(
    val headline: String,
    val privacyNote: String,
    val steps: List<ReadinessStepUi>,
    val primaryCtaLabel: String,
    val blockerKind: ReadinessBlockerKind,
    val isNonBlockingInfo: Boolean = false,
) {
    companion object {
        private const val PRIVACY_NOTE =
            "100% offline — Android requires Location permission to read Wi-Fi router beacons; zero location data is ever stored or transmitted."

        fun fromBlocker(kind: ReadinessBlockerKind): ReadinessChecklistUiModel = when (kind) {
            ReadinessBlockerKind.MISSING_PERMISSION -> ReadinessChecklistUiModel(
                headline = "Allow Wi-Fi & Precise Location Access",
                privacyNote = PRIVACY_NOTE,
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Allow Nearby Wi-Fi & Precise Location",
                        detail = "Required by Android to display nearby router names and channels.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Checked automatically right after permission is granted.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio",
                        detail = "Listens across 2.4 & 5 GHz bands on Galaxy A55 5G.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                ),
                primaryCtaLabel = "Grant Scan Permission",
                blockerKind = kind,
            )

            ReadinessBlockerKind.APPROXIMATE_ONLY_NEEDS_PRECISE -> ReadinessChecklistUiModel(
                headline = "Upgrade to Precise Location",
                privacyNote = PRIVACY_NOTE,
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Upgrade Location from Approximate to Precise",
                        detail = "Android 12+ hides Wi-Fi router scans when only Approximate is enabled.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Ready once Precise Location is enabled.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio",
                        detail = "Ready to scan nearby channels.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                ),
                primaryCtaLabel = "Upgrade to Precise Location",
                blockerKind = kind,
            )

            ReadinessBlockerKind.PERMANENT_PERMISSION_DENIAL -> ReadinessChecklistUiModel(
                headline = "Enable Permission in App Settings",
                privacyNote = PRIVACY_NOTE,
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Allow Precise Location in App Settings",
                        detail = "Open Permissions -> Location -> Allow Precise while using the app.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Checked automatically when you return.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio",
                        detail = "Ready to listen across wireless bands.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                ),
                primaryCtaLabel = "Open App Permission Settings",
                blockerKind = kind,
            )

            ReadinessBlockerKind.LOCATION_DISABLED -> ReadinessChecklistUiModel(
                headline = "Turn On Device Location",
                privacyNote = PRIVACY_NOTE,
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Allow Nearby Wi-Fi & Precise Location",
                        detail = "Granted — offline Wi-Fi access enabled.",
                        status = ReadinessStepStatus.COMPLETED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Android returns 0 networks while the system Location toggle is off.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio",
                        detail = "Scanning resumes automatically as soon as Location turns on.",
                        status = ReadinessStepStatus.PENDING,
                    ),
                ),
                primaryCtaLabel = "Open Location Settings",
                blockerKind = kind,
            )

            ReadinessBlockerKind.WIFI_AND_SCAN_DISABLED -> ReadinessChecklistUiModel(
                headline = "Turn On Wi-Fi to Scan",
                privacyNote = PRIVACY_NOTE,
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Allow Nearby Wi-Fi & Precise Location",
                        detail = "Granted.",
                        status = ReadinessStepStatus.COMPLETED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Active.",
                        status = ReadinessStepStatus.COMPLETED,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio",
                        detail = "Both Wi-Fi and background Wi-Fi scanning are currently off.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                ),
                primaryCtaLabel = "Turn On Wi-Fi",
                blockerKind = kind,
            )

            ReadinessBlockerKind.WIFI_DISABLED_SCAN_ALWAYS_ON -> ReadinessChecklistUiModel(
                headline = "Wi-Fi Off · Background Scanning Active",
                privacyNote = "Nearby routers are still detected via Android Wi-Fi Scanning. Turn on Wi-Fi for 300 ms live connected telemetry.",
                steps = listOf(
                    ReadinessStepUi(
                        id = "permissions",
                        stepNumber = 1,
                        title = "Allow Nearby Wi-Fi & Precise Location",
                        detail = "Granted.",
                        status = ReadinessStepStatus.COMPLETED,
                    ),
                    ReadinessStepUi(
                        id = "location",
                        stepNumber = 2,
                        title = "Turn On Phone Location",
                        detail = "Active.",
                        status = ReadinessStepStatus.COMPLETED,
                    ),
                    ReadinessStepUi(
                        id = "wifi",
                        stepNumber = 3,
                        title = "Turn On Wi-Fi Radio (Optional)",
                        detail = "Enable Wi-Fi to connect or measure unthrottled link speed.",
                        status = ReadinessStepStatus.ACTION_REQUIRED,
                    ),
                ),
                primaryCtaLabel = "Turn On Wi-Fi",
                blockerKind = kind,
                isNonBlockingInfo = true,
            )
        }
    }
}

@Composable
fun ReadinessChecklistCard(
    readinessState: ScannerReadinessState,
    onAction: (ReadinessAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiModel = readinessState.toUiModel() ?: return
    ReadinessChecklistCard(
        model = uiModel,
        onPrimaryActionClick = { onAction(readinessState.primaryAction) },
        modifier = modifier,
    )
}

@Composable
fun ReadinessChecklistCard(
    model: ReadinessChecklistUiModel,
    onPrimaryActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val cardTone = if (model.isNonBlockingInfo) SemanticTone.INFO else SemanticTone.FAIR
    val ctaToneSpec = colors.toneSpec(if (model.isNonBlockingInfo) SemanticTone.GOOD else SemanticTone.FAIR)

    RadarElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RadarTestTags.READINESS_CARD),
        accentTone = cardTone,
        contentPadding = PaddingValues(RadarTheme.dimens.cardInnerPadding),
    ) {
        Text(
            text = model.headline,
            style = RadarTheme.typography.metricLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = model.privacyNote,
            style = RadarTheme.typography.explanationBody,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            model.steps.forEach { step ->
                ReadinessStepRow(step = step)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onPrimaryActionClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RadarTheme.dimens.minTouchTarget)
                .testTag(RadarTestTags.READINESS_PRIMARY_CTA),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ctaToneSpec.foreground,
                contentColor = colors.onAccentDark,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = model.primaryCtaLabel,
                style = RadarTheme.typography.metricMedium,
                color = colors.onAccentDark,
            )
        }
    }
}

@Composable
private fun ReadinessStepRow(
    step: ReadinessStepUi,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val (tone, stateDesc, icon) = when (step.status) {
        ReadinessStepStatus.COMPLETED -> Triple(
            SemanticTone.EXCELLENT,
            "Completed",
            RadarIcons.CheckCircle,
        )
        ReadinessStepStatus.ACTION_REQUIRED -> Triple(
            SemanticTone.FAIR,
            "Action needed",
            RadarIcons.Warning,
        )
        ReadinessStepStatus.PENDING -> Triple(
            SemanticTone.NEUTRAL,
            "Pending",
            RadarIcons.Info,
        )
    }
    val toneSpec = colors.toneSpec(tone)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = stateDesc
            }
            .testTag(RadarTestTags.readinessStep(step.id)),
        shape = RoundedCornerShape(12.dp),
        color = toneSpec.container,
        contentColor = colors.textPrimary,
        border = BorderStroke(1.dp, toneSpec.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = toneSpec.foreground,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Step ${step.stepNumber}: ${step.title}",
                    style = RadarTheme.typography.metricMedium,
                    color = if (step.status == ReadinessStepStatus.PENDING) colors.textMuted else colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = step.detail,
                    style = RadarTheme.typography.explanationBody,
                    color = if (step.status == ReadinessStepStatus.PENDING) colors.textMuted else colors.textSecondary,
                )
            }
        }
    }
}
