package com.a55.wifiradar.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, zero-bloat bespoke vector glyphs for A55 WiFi Radar.
 * Eliminates the 30MB material-icons-extended AAR while providing crisp domain icons.
 */
object RadarIcons {
    private val WhiteBrush = SolidColor(Color.White)

    val Networks: ImageVector by lazy {
        ImageVector.Builder(
            name = "Networks",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Outer Wi-Fi arc
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(2.5f, 8.8f)
                quadTo(12f, 1.2f, 21.5f, 8.8f)
            }
            // Middle Wi-Fi arc
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5.8f, 12.6f)
                quadTo(12f, 7.6f, 18.2f, 12.6f)
            }
            // Inner Wi-Fi arc
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9.1f, 16.3f)
                quadTo(12f, 14.0f, 14.9f, 16.3f)
            }
            // Base station dot
            path(fill = WhiteBrush, pathFillType = PathFillType.NonZero) {
                moveTo(12f, 18.2f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 18.2f)
                close()
            }
        }.build()
    }

    val Proximity: ImageVector by lazy {
        ImageVector.Builder(
            name = "Proximity",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Outer sonar ring
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                close()
            }
            // Inner sonar ring
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 7.5f)
                arcTo(4.5f, 4.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 7.5f)
                close()
            }
            // Sweep needle
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 12f)
                lineTo(18.2f, 5.8f)
            }
        }.build()
    }

    val Spectrum: ImageVector by lazy {
        ImageVector.Builder(
            name = "Spectrum",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Baseline
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(2.5f, 19.5f)
                lineTo(21.5f, 19.5f)
            }
            // Primary spectral lobe
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3.5f, 19.5f)
                quadTo(9.0f, 3.5f, 14.5f, 19.5f)
            }
            // Overlapping secondary spectral lobe
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(10.0f, 19.5f)
                quadTo(15.2f, 7.5f, 20.5f, 19.5f)
            }
        }.build()
    }

    val Insights: ImageVector by lazy {
        ImageVector.Builder(
            name = "Insights",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Shield outline
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 2.8f)
                lineTo(19.5f, 6.0f)
                verticalLineTo(11.8f)
                quadTo(19.5f, 18.0f, 12f, 21.2f)
                quadTo(4.5f, 18.0f, 4.5f, 11.8f)
                verticalLineTo(6.0f)
                close()
            }
            // Health pulse line
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7.5f, 12.2f)
                lineTo(10.0f, 12.2f)
                lineTo(11.4f, 9.2f)
                lineTo(13.0f, 14.8f)
                lineTo(14.3f, 12.2f)
                lineTo(16.5f, 12.2f)
            }
        }.build()
    }

    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "CheckCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                close()
                moveTo(8.3f, 12.3f)
                lineTo(10.8f, 14.8f)
                lineTo(15.8f, 9.5f)
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(
            name = "Info",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 3f)
                close()
                moveTo(12f, 11f)
                verticalLineTo(16.5f)
                moveTo(12f, 7.6f)
                verticalLineTo(7.8f)
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(6.5f, 6.5f)
                lineTo(17.5f, 17.5f)
                moveTo(17.5f, 6.5f)
                lineTo(6.5f, 17.5f)
            }
        }.build()
    }

    val Warning: ImageVector by lazy {
        ImageVector.Builder(
            name = "Warning",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.5f)
                lineTo(21f, 19.5f)
                horizontalLineTo(3f)
                close()
                moveTo(12f, 9.5f)
                verticalLineTo(13.8f)
                moveTo(12f, 16.8f)
                verticalLineTo(17.0f)
            }
        }.build()
    }

    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Lock",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6.5f, 11f)
                horizontalLineTo(17.5f)
                verticalLineTo(20f)
                horizontalLineTo(6.5f)
                close()
                moveTo(8.5f, 11f)
                verticalLineTo(7.8f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.5f, 7.8f)
                verticalLineTo(11f)
            }
        }.build()
    }

    val ChevronUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronUp",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 15f)
                lineTo(12f, 9f)
                lineTo(18f, 15f)
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "ChevronDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 9f)
                lineTo(12f, 15f)
                lineTo(18f, 9f)
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(20f, 11f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = false, 18.2f, 17f)
                moveTo(20f, 5.5f)
                verticalLineTo(11f)
                horizontalLineTo(14.5f)
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Search",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(11f, 4f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.99f, 4f)
                close()
                moveTo(16.2f, 16.2f)
                lineTo(20.5f, 20.5f)
            }
        }.build()
    }

    val Share: ImageVector by lazy {
        ImageVector.Builder(
            name = "Share",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = WhiteBrush,
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16f, 6f)
                lineTo(12f, 2.5f)
                lineTo(8f, 6f)
                moveTo(12f, 2.5f)
                verticalLineTo(15f)
                moveTo(5f, 12.5f)
                verticalLineTo(19.5f)
                horizontalLineTo(19f)
                verticalLineTo(12.5f)
            }
        }.build()
    }
}
