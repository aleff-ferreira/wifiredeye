package com.a55.wifiradar.feature.insights.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.canvas.rememberRadarStrokeCache
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.theme.RadarPalette
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.feature.insights.InsightsStateMapper
import com.a55.wifiradar.feature.insights.InsightsTestTags
import com.a55.wifiradar.feature.insights.InsightsUiState
import com.a55.wifiradar.feature.insights.RoamEventItemUi
import com.a55.wifiradar.feature.insights.RssiTimelineSeriesUi

private val TIMELINE_SERIES_COLORS = listOf(
    RadarPalette.ElectricEmerald,
    RadarPalette.LuminousCyan,
    RadarPalette.SpectrumViolet,
    RadarPalette.WarmAmber,
    RadarPalette.LuminousCoral,
)

private class CachedTimelineYGrid(
    val y: Float,
    val labelLayout: TextLayoutResult?,
    val labelOffset: Offset,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RssiTimeGraphSectionCard(
    uiState: InsightsUiState,
    onSelectHistoryWindow: (RssiHistoryWindow) -> Unit,
    onToggleHighlightTimelineBssid: (String) -> Unit,
    onToggleShowAllRoamEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val fontScale = LocalDensity.current.fontScale

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Part A: Multi-Network RSSI Time Graph Card
        RadarCard(
            modifier = Modifier.testTag(InsightsTestTags.TIME_GRAPH_CARD),
            accentTone = SemanticTone.GOOD,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column {
                    Text(
                        text = "Multi-Network Signal History (${uiState.selectedHistoryWindow.displayLabel})",
                        style = typography.metricMedium,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = "Dashed segments mark missed scan sweeps or brief signal dropouts",
                        style = typography.explanationBody,
                        color = colors.textSecondary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 1m / 5m / 15m Window Selector Bar
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RssiHistoryWindow.entries.forEach { window ->
                    val isSelected = window == uiState.selectedHistoryWindow
                    HistoryWindowTabChip(
                        window = window,
                        isSelected = isSelected,
                        onClick = { onSelectHistoryWindow(window) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Reference Threshold Legend Pills
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InsightsTonePill(
                    text = "-67 dBm · VoIP / 4K Video",
                    tone = SemanticTone.EXCELLENT,
                )
                InsightsTonePill(
                    text = "-70 dBm · Reliable Web",
                    tone = SemanticTone.GOOD,
                )
                InsightsTonePill(
                    text = "-80 dBm · Minimum Usable",
                    tone = SemanticTone.FAIR,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val canvasHeight = if (fontScale >= 1.5f) 224.dp else 196.dp
            RssiMultiSeriesCanvas(
                uiState = uiState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(canvasHeight),
            )

            if (uiState.timelineSeries.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Signal history lines will populate automatically as wireless sweeps arrive.",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            } else {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.timelineSeries.forEach { series ->
                        TimelineSeriesLegendRow(
                            series = series,
                            seriesColor = TIMELINE_SERIES_COLORS[series.paletteIndex % TIMELINE_SERIES_COLORS.size],
                            onClick = { onToggleHighlightTimelineBssid(series.bssid) },
                        )
                    }
                }
            }
        }

        // Part B: Mesh BSSID Roam Event Log Card
        MeshRoamEventLogCard(
            roamEvents = uiState.roamEvents,
            totalCount = uiState.totalRoamEventCount,
            showAll = uiState.showAllRoamEvents,
            onToggleShowAll = onToggleShowAllRoamEvents,
        )
    }
}

@Composable
private fun HistoryWindowTabChip(
    window: RssiHistoryWindow,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val toneSpec = colors.toneSpec(SemanticTone.GOOD)

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = onClick,
            )
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .testTag(InsightsTestTags.historyWindowChip(window)),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) toneSpec.container else colors.surfaceL2,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) toneSpec.foreground else colors.border,
        ),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = window.displayLabel,
                style = RadarTheme.typography.metricMedium,
                color = if (isSelected) toneSpec.foreground else colors.textPrimary,
            )
        }
    }
}

@Composable
private fun RssiMultiSeriesCanvas(
    uiState: InsightsUiState,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val strokes = rememberRadarStrokeCache()
    val textMeasurer = rememberTextMeasurer()

    // Pre-allocated Path pool reused across drawWithCache rebuilds (zero per-frame heap allocations)
    val solidPaths = remember { Array(InsightsStateMapper.MAX_TIMELINE_SERIES) { Path() } }
    val dashedPaths = remember { Array(InsightsStateMapper.MAX_TIMELINE_SERIES) { Path() } }

    val seriesCount = uiState.timelineSeries.size
    val roamCount = uiState.chartRoamMarkers.size
    val gapTotal = uiState.timelineSeries.sumOf { it.gapSegmentCount }
    val contentDesc = buildString {
        append("Multi-Network Signal History (${uiState.selectedHistoryWindow.displayLabel} window): ")
        append("$seriesCount tracked ${if (seriesCount == 1) "network" else "networks"}, ")
        append("$roamCount roam ${if (roamCount == 1) "handoff" else "handoffs"}")
        if (gapTotal > 0) {
            append(", $gapTotal dashed dropout ${if (gapTotal == 1) "gap" else "gaps"}")
        }
    }
    val stateDesc = "${uiState.selectedHistoryWindow.displayLabel} window · $seriesCount networks · $roamCount roams"

    val axisStyle = typography.badgeLabel.copy(color = colors.textMuted)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RadarPalette.OledBlack)
            .radarCanvasSemantics(
                testTag = InsightsTestTags.RSSI_TIME_GRAPH_CANVAS,
                contentDescription = contentDesc,
                stateDescription = stateDesc,
            )
            .drawWithCache {
                val sampleY = textMeasurer.measure("-95", style = axisStyle)
                val sampleX = textMeasurer.measure("Now", style = axisStyle)

                val leftPad = (sampleY.size.width + 10.dp.toPx()).coerceAtMost(size.width * 0.28f)
                val rightPad = 12.dp.toPx()
                val topPad = 10.dp.toPx()
                val bottomPad = (sampleX.size.height + 8.dp.toPx()).coerceAtMost(size.height * 0.24f)

                val plotLeft = leftPad
                val plotRight = (size.width - rightPad).coerceAtLeast(plotLeft + 16f)
                val plotTop = topPad
                val plotBottom = (size.height - bottomPad).coerceAtLeast(plotTop + 16f)
                val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                val minDbm = -95f
                val maxDbm = -30f
                val dbmSpan = maxDbm - minDbm // 65f

                fun dbmToY(dbm: Float): Float {
                    val safe = if (dbm.isFinite()) dbm.coerceIn(minDbm, maxDbm) else minDbm
                    val norm = (safe - minDbm) / dbmSpan
                    return plotBottom - norm * plotHeight
                }

                fun fracToX(xFrac: Float): Float {
                    val safe = if (xFrac.isFinite()) xFrac.coerceIn(0f, 1f) else 1f
                    return plotLeft + safe * plotWidth
                }

                // Y-axis gridlines & labels
                val yDbms = listOf(-35f, -50f, -67f, -80f, -95f)
                val yGrids = mutableListOf<CachedTimelineYGrid>()
                var lastYBottom = -100f
                for (dbm in yDbms) {
                    val y = dbmToY(dbm)
                    val layout = textMeasurer.measure("${dbm.toInt()}", style = axisStyle)
                    val topY = (y - layout.size.height * 0.5f).coerceIn(2f, size.height - layout.size.height - 2f)
                    val fits = topY >= lastYBottom + 2.dp.toPx()
                    if (fits) {
                        lastYBottom = topY + layout.size.height
                    }
                    yGrids += CachedTimelineYGrid(
                        y = y,
                        labelLayout = if (fits) layout else null,
                        labelOffset = Offset((plotLeft - layout.size.width - 5.dp.toPx()).coerceAtLeast(2f), topY),
                    )
                }

                // Threshold guide Y positions
                val y67 = dbmToY(-67f)
                val y70 = dbmToY(-70f)
                val y80 = dbmToY(-80f)

                val thresholdDash = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                    0f,
                )
                val roamDash = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                    0f,
                )
                val highlightedStroke = Stroke(
                    width = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )

                // X-axis labels (-window, Mid, Now)
                val startText = textMeasurer.measure("-${uiState.selectedHistoryWindow.displayLabel}", style = axisStyle)
                val midText = textMeasurer.measure("Mid", style = axisStyle)
                val nowText = textMeasurer.measure("Now", style = axisStyle)
                val xAxisTop = plotBottom + 3.dp.toPx()
                val startOffset = Offset(plotLeft, xAxisTop)
                val nowLeft = (plotRight - nowText.size.width).coerceAtLeast(plotLeft)
                val nowOffset = Offset(nowLeft, xAxisTop)
                val midLeft = plotLeft + (plotWidth - midText.size.width) * 0.5f
                val showMid = midLeft > (plotLeft + startText.size.width + 6.dp.toPx()) &&
                    (midLeft + midText.size.width + 6.dp.toPx()) < nowLeft
                val midOffset = Offset(midLeft, xAxisTop)

                // Roam marker X coordinates
                val roamXs = FloatArray(uiState.chartRoamMarkers.size) { idx ->
                    fracToX(uiState.chartRoamMarkers[idx].xFraction0To1)
                }

                // Build reusable solid & dashed series paths
                val activeSeriesCount = uiState.timelineSeries.size.coerceAtMost(solidPaths.size)
                val seriesColors = Array(activeSeriesCount) { Color.Transparent }
                val seriesHighlighted = BooleanArray(activeSeriesCount)
                val seriesSingleOrEndpoint = Array(activeSeriesCount) { Offset.Zero }
                val seriesNeedsOuterHalo = BooleanArray(activeSeriesCount)

                for (i in 0 until activeSeriesCount) {
                    val s = uiState.timelineSeries[i]
                    val solidPath = solidPaths[i].apply { reset() }
                    val dashedPath = dashedPaths[i].apply { reset() }

                    val rawColor = TIMELINE_SERIES_COLORS[s.paletteIndex % TIMELINE_SERIES_COLORS.size]
                    val alpha = when {
                        s.isHighlighted -> 1f
                        s.isDimmed -> 0.24f
                        else -> 0.92f
                    }
                    seriesColors[i] = rawColor.copy(alpha = alpha)
                    seriesHighlighted[i] = s.isHighlighted

                    var prevX = 0f
                    var prevY = 0f
                    for ((ptIdx, pt) in s.points.withIndex()) {
                        val px = fracToX(pt.xFraction0To1)
                        val py = dbmToY(pt.rssiDbm)
                        if (ptIdx == 0) {
                            solidPath.moveTo(px, py)
                        } else if (pt.connectWithDashedGapFromPrev) {
                            dashedPath.moveTo(prevX, prevY)
                            dashedPath.lineTo(px, py)
                            solidPath.moveTo(px, py)
                        } else {
                            solidPath.lineTo(px, py)
                        }
                        prevX = px
                        prevY = py
                    }

                    seriesSingleOrEndpoint[i] = Offset(prevX, prevY)
                    seriesNeedsOuterHalo[i] = s.points.size == 1 || s.isConnected || s.isHighlighted
                }

                val endpointRadius = 3.5.dp.toPx()
                val haloRadius = 7.dp.toPx()
                val roamDotRadius = 3.dp.toPx()
                val thresholdWidthPx = 1.1.dp.toPx()
                val roamLineWidthPx = 1.5.dp.toPx()

                onDrawBehind {
                    // Subtle gridlines + Y-axis labels
                    for (grid in yGrids) {
                        drawLine(
                            color = colors.border,
                            start = Offset(plotLeft, grid.y),
                            end = Offset(plotRight, grid.y),
                            strokeWidth = strokes.gridHairline.width,
                        )
                        if (grid.labelLayout != null) {
                            drawText(
                                textLayoutResult = grid.labelLayout,
                                topLeft = grid.labelOffset,
                            )
                        }
                    }

                    // Reference thresholds (-67, -70, -80 dBm)
                    drawLine(
                        color = colors.emerald.copy(alpha = 0.48f),
                        start = Offset(plotLeft, y67),
                        end = Offset(plotRight, y67),
                        strokeWidth = thresholdWidthPx,
                        pathEffect = thresholdDash,
                    )
                    drawLine(
                        color = colors.cyan.copy(alpha = 0.42f),
                        start = Offset(plotLeft, y70),
                        end = Offset(plotRight, y70),
                        strokeWidth = thresholdWidthPx,
                        pathEffect = thresholdDash,
                    )
                    drawLine(
                        color = colors.amber.copy(alpha = 0.48f),
                        start = Offset(plotLeft, y80),
                        end = Offset(plotRight, y80),
                        strokeWidth = thresholdWidthPx,
                        pathEffect = thresholdDash,
                    )

                    // Vertical roam markers
                    for (rx in roamXs) {
                        drawLine(
                            color = colors.violet.copy(alpha = 0.80f),
                            start = Offset(rx, plotTop),
                            end = Offset(rx, plotBottom),
                            strokeWidth = roamLineWidthPx,
                            pathEffect = roamDash,
                        )
                        drawCircle(
                            color = colors.violet,
                            radius = roamDotRadius,
                            center = Offset(rx, plotTop + roamDotRadius + 1f),
                        )
                    }

                    // Multi-series solid + dashed dropout trajectories & endpoint markers
                    for (i in 0 until activeSeriesCount) {
                        val sColor = seriesColors[i]
                        drawPath(
                            path = dashedPaths[i],
                            color = sColor,
                            style = strokes.dashedDropout,
                        )
                        drawPath(
                            path = solidPaths[i],
                            color = sColor,
                            style = if (seriesHighlighted[i]) highlightedStroke else strokes.signalLine,
                        )
                        val endPt = seriesSingleOrEndpoint[i]
                        if (seriesNeedsOuterHalo[i]) {
                            drawCircle(
                                color = sColor.copy(alpha = 0.26f),
                                radius = haloRadius,
                                center = endPt,
                            )
                        }
                        drawCircle(
                            color = sColor,
                            radius = endpointRadius,
                            center = endPt,
                        )
                    }

                    // Axes frame + X-axis labels
                    drawLine(
                        color = colors.activeBorder,
                        start = Offset(plotLeft, plotBottom),
                        end = Offset(plotRight, plotBottom),
                        strokeWidth = strokes.axisLine.width,
                    )
                    drawLine(
                        color = colors.activeBorder,
                        start = Offset(plotLeft, plotTop),
                        end = Offset(plotLeft, plotBottom),
                        strokeWidth = strokes.axisLine.width,
                    )
                    drawText(textLayoutResult = startText, topLeft = startOffset)
                    if (showMid) {
                        drawText(textLayoutResult = midText, topLeft = midOffset)
                    }
                    if (nowLeft >= plotLeft + startText.size.width + 4.dp.toPx()) {
                        drawText(textLayoutResult = nowText, topLeft = nowOffset)
                    }
                }
            },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineSeriesLegendRow(
    series: RssiTimelineSeriesUi,
    seriesColor: Color,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (series.isHighlighted) "Focused" else "Tap to focus"
            }
            .testTag(InsightsTestTags.timelineSeriesRow(series.bssid)),
        shape = RoundedCornerShape(12.dp),
        color = if (series.isHighlighted) colors.surfaceL2 else colors.surfaceL1,
        border = BorderStroke(
            width = if (series.isHighlighted) 1.5.dp else 1.dp,
            color = if (series.isHighlighted) seriesColor else colors.border,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(seriesColor),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = series.displayName,
                        style = typography.metricMedium,
                        color = if (series.isDimmed) colors.textMuted else colors.textPrimary,
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (series.isConnected) {
                        InsightsTonePill(text = "Connected", tone = SemanticTone.EXCELLENT)
                    }
                    InsightsTonePill(
                        text = "${series.bandLabel} · Ch ${series.channel}",
                        tone = SemanticTone.INFO,
                    )
                    if (series.gapSegmentCount > 0) {
                        InsightsTonePill(
                            text = "${series.gapSegmentCount} scan ${if (series.gapSegmentCount == 1) "gap" else "gaps"}",
                            tone = SemanticTone.FAIR,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = series.formattedBssid,
                style = typography.monoAddress,
                color = colors.textSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = series.formattedStatsLine,
                style = typography.badgeLabel,
                color = if (series.isDimmed) colors.textMuted else colors.textPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshRoamEventLogCard(
    roamEvents: List<RoamEventItemUi>,
    totalCount: Int,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography

    RadarCard(
        modifier = Modifier.testTag(InsightsTestTags.ROAM_LOG_CARD),
        accentTone = SemanticTone.SPECTRUM_6GHZ,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Text(
                    text = "Mesh BSSID Roam Handoff Log",
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Tracks automatic router-to-router transitions on your connected Wi-Fi",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
            InsightsTonePill(
                text = "$totalCount ${if (totalCount == 1) "Handoff" else "Handoffs"}",
                tone = SemanticTone.SPECTRUM_6GHZ,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (roamEvents.isEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InsightsTestTags.ROAM_LOG_EMPTY),
                shape = RoundedCornerShape(10.dp),
                color = colors.surfaceL2,
                border = BorderStroke(1.dp, colors.border),
            ) {
                Text(
                    text = "No mesh BSSID handoffs recorded in this session yet. Walk between rooms while connected to a multi-AP network to observe live roam transitions.",
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                roamEvents.forEachIndexed { index, item ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(InsightsTestTags.roamEventRow(index)),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceL2,
                        border = BorderStroke(1.dp, colors.activeBorder),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "${item.ssid} · ${item.relativeAgeLabel}",
                                    style = typography.metricMedium,
                                    color = colors.textPrimary,
                                )
                                InsightsTonePill(
                                    text = item.deltaFormatted,
                                    tone = item.deltaTone,
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${item.formattedOldBssid} → ${item.formattedNewBssid}",
                                style = typography.monoAddress,
                                color = colors.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                InsightsTonePill(
                                    text = item.channelTransitionLabel,
                                    tone = SemanticTone.INFO,
                                )
                                if (item.isBandSwitch) {
                                    InsightsTonePill(
                                        text = "Band Switch",
                                        tone = SemanticTone.SPECTRUM_6GHZ,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (totalCount > InsightsStateMapper.DEFAULT_VISIBLE_ROAM_ROWS) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onToggleShowAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(InsightsTestTags.ROAM_SHOW_ALL_TOGGLE),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                ) {
                    Text(
                        text = if (showAll) {
                            "Show Recent ${InsightsStateMapper.DEFAULT_VISIBLE_ROAM_ROWS} Handoffs"
                        } else {
                            "Show All $totalCount Handoffs"
                        },
                        style = typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}
