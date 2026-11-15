package com.a55.wifiradar.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone

@Composable
fun RadarCard(
    modifier: Modifier = Modifier,
    accentTone: SemanticTone? = null,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(RadarTheme.dimens.cardInnerPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val borderColor = when {
        highlighted && accentTone != null -> colors.toneSpec(accentTone).border
        highlighted -> colors.activeBorder
        else -> colors.border
    }
    val stripeColor: Color? = accentTone?.let { colors.toneSpec(it).foreground }
    val shape = RoundedCornerShape(dimens.cardCornerRadius)
    val border = BorderStroke(dimens.hairlineBorderWidth, borderColor)

    val innerModifier = Modifier
        .fillMaxWidth()
        .then(
            if (stripeColor != null) {
                Modifier.drawWithContent {
                    drawContent()
                    val stripeWidthPx = dimens.accentStripeWidth.toPx()
                    val verticalInset = 10f * density
                    val barHeight = (size.height - 2f * verticalInset).coerceAtLeast(8f)
                    drawRoundRect(
                        color = stripeColor,
                        topLeft = Offset(0f, verticalInset),
                        size = Size(stripeWidthPx, barHeight),
                        cornerRadius = CornerRadius(stripeWidthPx, stripeWidthPx),
                    )
                }
            } else {
                Modifier
            }
        )
        .padding(contentPadding)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = dimens.minTouchTarget),
            shape = shape,
            color = colors.surfaceL1,
            contentColor = colors.textPrimary,
            border = border,
        ) {
            Column(modifier = innerModifier, content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = colors.surfaceL1,
            contentColor = colors.textPrimary,
            border = border,
        ) {
            Column(modifier = innerModifier, content = content)
        }
    }
}

@Composable
fun RadarElevatedCard(
    modifier: Modifier = Modifier,
    accentTone: SemanticTone? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(RadarTheme.dimens.cardInnerPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens
    val borderColor = accentTone?.let { colors.toneSpec(it).border } ?: colors.activeBorder
    val shape = RoundedCornerShape(dimens.cardCornerRadius)
    val border = BorderStroke(dimens.hairlineBorderWidth, borderColor)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = dimens.minTouchTarget),
            shape = shape,
            color = colors.surfaceL2,
            contentColor = colors.textPrimary,
            border = border,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = colors.surfaceL2,
            contentColor = colors.textPrimary,
            border = border,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}
