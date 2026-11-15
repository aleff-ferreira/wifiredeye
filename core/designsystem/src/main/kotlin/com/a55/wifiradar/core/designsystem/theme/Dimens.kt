package com.a55.wifiradar.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class RadarDimens(
    val screenHorizontalPadding: Dp = 14.dp,
    val cardInnerPadding: Dp = 14.dp,
    val cardVerticalSpacing: Dp = 10.dp,
    val cardCornerRadius: Dp = 16.dp,
    val pillCornerRadius: Dp = 999.dp,
    val hairlineBorderWidth: Dp = 1.dp,
    val accentStripeWidth: Dp = 3.dp,
    val minTouchTarget: Dp = 48.dp,
    val inlineGapSmall: Dp = 6.dp,
    val inlineGapMedium: Dp = 10.dp,
    val topBarExtraCutoutClearance: Dp = 6.dp,
)
