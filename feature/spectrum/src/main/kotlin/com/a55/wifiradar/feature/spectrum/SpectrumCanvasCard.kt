package com.a55.wifiradar.feature.spectrum

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import com.a55.wifiradar.core.designsystem.canvas.animateRadarFloatAsState
import com.a55.wifiradar.core.designsystem.canvas.radarCanvasSemantics
import com.a55.wifiradar.core.designsystem.canvas.rememberRadarStrokeCache
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.RadarPalette
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MAX_RENDERED_CURVES = 24
private const val DEFAULT_VISIBLE_CURVE_CHIPS = 8

private val CURVE_ACCENT_PALETTE = listOf(
    RadarPalette.LuminousCyan,
    RadarPalette.SpectrumViolet,
    RadarPalette.WarmAmber,
    RadarPalette.LuminousCoral,
    RadarPalette.ElectricEmerald,
)

private class CachedBackdropBand(
    val rect: Rect,
    val fillColor: Color,
    val borderColor: Color,
    val labelLayout: TextLayoutResult?,
    val labelOffset: Offset,
    val labelColor: Color,
)

private class CachedGridLine(
    val y: Float,
    val labelLayout: TextLayoutResult?,
    val labelOffset: Offset,
)

private class CachedChannelGuide(
    val x: Float,
    val isMajor: Boolean,
    val isRecommended: Boolean,
    val isHighlighted: Boolean,
    val labelLayout: TextLayoutResult?,
    val labelOffset: Offset,
    val labelColor: Color,
)

private class CachedSpectralLobe(
    val fillPath: Path,
    val outlinePath: Path,
    val bridgePath: Path?,
    val fillBrush: Brush,
    val strokeColor: Color,
    val strokeStyle: Stroke,
    val peakCenter: Offset,
    val showPeakDot: Boolean,
    val peakLabelLayout: TextLayoutResult?,
    val peakLabelBgTopLeft: Offset,
    val peakLabelBgSize: Size,
    val peakLabelTextOffset: Offset,
    val peakLabelBgColor: Color,
    val peakLabelBorderColor: Color,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpectrumCanvasCard(
    uiState: SpectrumUiState,
    onSelectBssid: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenProximity: ((ProximityTargetKey) -> Unit)? = null,
    onOpenNetworkDetail: ((String) -> Unit)? = null,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val density = LocalDensity.current
    var showAllCurveChips by rememberSaveable(uiState.selectedBand.name) { mutableStateOf(false) }

    val highlightAlpha by animateRadarFloatAsState(
        targetValue = if (uiState.selectedCurve != null) 1f else 0f,
        label = "spectrumHighlightAlpha",
    )

    RadarCard(
        modifier = modifier.testTag(SpectrumTestTags.SPECTRUM_CANVAS_CARD),
        accentTone = uiState.selectedBand.toSemanticTone(),
        highlighted = uiState.selectedCurve != null,
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                Text(
                    text = "${uiState.selectedBand.displayLabel} Spectral Overlap (${uiState.minFreqMhz}–${uiState.maxFreqMhz} MHz)",
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                val subtitle = when {
                    uiState.curves.isNotEmpty() -> {
                        val noun = if (uiState.curves.size == 1) "active radio" else "active radios"
                        "${uiState.curves.size} $noun · Tap any curve or chip below to inspect overlap"
                    }
                    uiState.selectedBand == WifiBand.BAND_6_GHZ && uiState.colocated6GhzNeighbors.isNotEmpty() -> {
                        val count = uiState.colocated6GhzNeighbors.size
                        "$count co-located 6 GHz RNR beacon guide${if (count == 1) "" else "s"} plotted"
                    }
                    else -> "No wireless radios currently broadcasting on ${uiState.selectedBand.displayLabel}"
                }
                Text(
                    text = subtitle,
                    style = typography.explanationBody,
                    color = colors.textSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        val canvasHeight = if (density.fontScale >= 1.5f) 256.dp else 224.dp
        SpectrumOverlapCanvas(
            uiState = uiState,
            highlightAlpha = highlightAlpha,
            onSelectBssid = onSelectBssid,
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight),
        )

        if (uiState.aggregatedWeakCurveCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            val weakCount = uiState.aggregatedWeakCurveCount
            val toneSpec = colors.toneSpec(SemanticTone.NEUTRAL)
            Surface(
                modifier = Modifier.testTag(SpectrumTestTags.AGGREGATED_WEAK_PILL),
                shape = RoundedCornerShape(10.dp),
                color = toneSpec.container,
                border = BorderStroke(1.dp, toneSpec.border),
            ) {
                Text(
                    text = "+$weakCount faint distant radio${if (weakCount == 1) "" else "s"} (< -85 dBm) folded into noise floor",
                    style = typography.badgeLabel,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (uiState.curves.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            val sortedCurves = uiState.curves.sortedWith(
                compareByDescending<SpectralCurve> { it.isConnected }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { it.bssid }
            )
            val visibleChips = if (showAllCurveChips || sortedCurves.size <= DEFAULT_VISIBLE_CURVE_CHIPS) {
                sortedCurves
            } else {
                val topSlice = sortedCurves.take(DEFAULT_VISIBLE_CURVE_CHIPS).toMutableList()
                val sel = uiState.selectedCurve
                if (sel != null && topSlice.none { MacAddressUtils.normalizeBssid(it.bssid) == MacAddressUtils.normalizeBssid(sel.bssid) }) {
                    topSlice += sel
                }
                topSlice
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SpectrumTestTags.CURVE_CHIPS_GROUP),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visibleChips.forEachIndexed { index, curve ->
                    val normBssid = MacAddressUtils.normalizeBssid(curve.bssid)
                    val isSelected = uiState.selectedBssid == normBssid
                    val accentColor = resolveCurveColor(curve, index, isSelected)
                    CurveSelectorChip(
                        curve = curve,
                        accentColor = accentColor,
                        isSelected = isSelected,
                        onClick = { onSelectBssid(curve.bssid) },
                    )
                }

                if (sortedCurves.size > DEFAULT_VISIBLE_CURVE_CHIPS) {
                    OutlinedButton(
                        onClick = { showAllCurveChips = !showAllCurveChips },
                        modifier = Modifier
                            .heightIn(min = RadarTheme.dimens.minTouchTarget)
                            .testTag(SpectrumTestTags.CURVE_CHIPS_EXPAND_BUTTON),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.activeBorder),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (showAllCurveChips) {
                                "Show Top $DEFAULT_VISIBLE_CURVE_CHIPS Radios"
                            } else {
                                "Show All ${sortedCurves.size} Radios"
                            },
                            style = typography.badgeLabel,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        val inspected = uiState.selectedCurve
        if (inspected != null) {
            Spacer(modifier = Modifier.height(10.dp))
            SelectedCurveInspectorBanner(
                curve = inspected,
                onClearSelection = { onSelectBssid(null) },
                onOpenProximity = onOpenProximity,
                onOpenNetworkDetail = onOpenNetworkDetail,
            )
        }
    }
}

@Composable
private fun SpectrumOverlapCanvas(
    uiState: SpectrumUiState,
    highlightAlpha: Float,
    onSelectBssid: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val strokes = rememberRadarStrokeCache()
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fontScale = density.fontScale

    val contentDesc = buildCanvasContentDescription(uiState)
    val stateDesc = buildCanvasStateDescription(uiState)

    val axisLabelStyle = typography.badgeLabel.copy(color = colors.textMuted)
    val peakLabelStyle = typography.badgeLabel.copy(color = colors.textPrimary)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(RadarPalette.OledBlack)
            .radarCanvasSemantics(
                testTag = SpectrumTestTags.SPECTRUM_CANVAS,
                contentDescription = contentDesc,
                stateDescription = stateDesc,
            )
            .pointerInput(uiState.curves, uiState.minFreqMhz, uiState.maxFreqMhz) {
                if (uiState.curves.isEmpty()) return@pointerInput
                detectTapGestures { tapOffset ->
                    val widthPx = size.width.toFloat()
                    val heightPx = size.height.toFloat()
                    if (widthPx <= 20f || heightPx <= 20f) return@detectTapGestures

                    val approxLeftPad = 42.dp.toPx()
                    val approxRightPad = 10.dp.toPx()
                    val approxTopPad = 24.dp.toPx()
                    val approxBottomPad = 26.dp.toPx()
                    val plotLeft = approxLeftPad.coerceAtMost(widthPx * 0.3f)
                    val plotRight = (widthPx - approxRightPad).coerceAtLeast(plotLeft + 20f)
                    val plotTop = approxTopPad.coerceAtMost(heightPx * 0.25f)
                    val plotBottom = (heightPx - approxBottomPad).coerceAtLeast(plotTop + 20f)
                    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
                    val freqSpan = (uiState.maxFreqMhz - uiState.minFreqMhz).toFloat().coerceAtLeast(1f)
                    val hitSlopPx = 14.dp.toPx()

                    var bestCurve: SpectralCurve? = null
                    var bestDist = Float.MAX_VALUE

                    for (curve in uiState.curves) {
                        val (startFreq, endFreq) = resolvePrimaryLobeFreq(curve)
                        val xStart = plotLeft + ((startFreq - uiState.minFreqMhz) / freqSpan) * plotWidth
                        val xEnd = plotLeft + ((endFreq - uiState.minFreqMhz) / freqSpan) * plotWidth
                        val clampedDbm = SpectrumDomainResolver.safeCanvasRssiDbm(curve.rssiDbm)
                        val yPeak = plotBottom - ((clampedDbm + 100f) / 80f).coerceIn(0.06f, 1f) * plotHeight

                        val inPrimary = tapOffset.x in (xStart - hitSlopPx)..(xEnd + hitSlopPx) &&
                            tapOffset.y in (yPeak - hitSlopPx)..(plotBottom + hitSlopPx)

                        var inSecondary = false
                        var secCenterX = 0f
                        val secStart = curve.secondaryLobeStartFreqMhz
                        val secEnd = curve.secondaryLobeEndFreqMhz
                        if (secStart != null && secEnd != null && secEnd > secStart) {
                            val sx0 = plotLeft + ((secStart - uiState.minFreqMhz) / freqSpan) * plotWidth
                            val sx1 = plotLeft + ((secEnd - uiState.minFreqMhz) / freqSpan) * plotWidth
                            secCenterX = (sx0 + sx1) * 0.5f
                            inSecondary = tapOffset.x in (sx0 - hitSlopPx)..(sx1 + hitSlopPx) &&
                                tapOffset.y in (yPeak - hitSlopPx)..(plotBottom + hitSlopPx)
                        }

                        if (inPrimary || inSecondary) {
                            val centerX = if (inPrimary) (xStart + xEnd) * 0.5f else secCenterX
                            val dx = abs(tapOffset.x - centerX)
                            val dy = abs(tapOffset.y - yPeak) * 0.5f
                            val score = dx + dy
                            if (score < bestDist) {
                                bestDist = score
                                bestCurve = curve
                            }
                        }
                    }

                    if (bestCurve != null) {
                        onSelectBssid(bestCurve.bssid)
                    }
                }
            }
            .drawWithCache {
                val sampleYLayout = textMeasurer.measure("-100", style = axisLabelStyle)
                val sampleXLayout = textMeasurer.measure("Ch 11", style = axisLabelStyle)

                val leftPad = (sampleYLayout.size.width + 10.dp.toPx()).coerceAtMost(size.width * 0.28f)
                val rightPad = 10.dp.toPx()
                val topPad = (sampleXLayout.size.height + 14.dp.toPx()).coerceAtMost(size.height * 0.24f)
                val bottomPad = (sampleXLayout.size.height + 10.dp.toPx()).coerceAtMost(size.height * 0.24f)

                val plotLeft = leftPad
                val plotRight = (size.width - rightPad).coerceAtLeast(plotLeft + 16f)
                val plotTop = topPad
                val plotBottom = (size.height - bottomPad).coerceAtLeast(plotTop + 16f)
                val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

                val minFreq = uiState.minFreqMhz
                val freqSpan = (uiState.maxFreqMhz - minFreq).toFloat().coerceAtLeast(1f)
                fun freqToX(freqMhz: Float): Float =
                    (plotLeft + ((freqMhz - minFreq) / freqSpan) * plotWidth).coerceIn(plotLeft, plotRight)

                fun dbmToY(dbm: Int): Float {
                    val clamped = SpectrumDomainResolver.safeCanvasRssiDbm(dbm)
                    val norm = ((clamped + 100f) / 80f).coerceIn(0.05f, 1f)
                    return plotBottom - norm * plotHeight
                }

                // 1. Sub-band backdrop regions (U-NII / DFS / TDWR / 6 GHz)
                val axisSpec = SpectrumDomainResolver.axisSpecFor(uiState.selectedBand)
                val cachedBackdrops = mutableListOf<CachedBackdropBand>()
                var lastSubBandRight = plotLeft
                for (sub in axisSpec.backdrops) {
                    if (uiState.selectedBand == WifiBand.BAND_2_4_GHZ) continue
                    val x0 = freqToX(sub.startFreqMhz.toFloat())
                    val x1 = freqToX(sub.endFreqMhz.toFloat())
                    if (x1 - x0 < 3f) continue
                    val toneSpec = colors.toneSpec(sub.tone)
                    val fillAlpha = when {
                        sub.isTdwr -> 0.16f
                        sub.isDfs -> 0.11f
                        else -> 0.06f
                    }
                    val labelCandidate = textMeasurer.measure(
                        text = sub.shortLabel,
                        style = axisLabelStyle.copy(color = toneSpec.foreground),
                    )
                    val fitsLabel = (x1 - x0) >= (labelCandidate.size.width + 6.dp.toPx()) &&
                        x0 >= lastSubBandRight - 1f &&
                        fontScale <= 1.6f
                    if (fitsLabel) {
                        lastSubBandRight = x0 + labelCandidate.size.width + 6.dp.toPx()
                    }
                    cachedBackdrops += CachedBackdropBand(
                        rect = Rect(x0, plotTop, x1, plotBottom),
                        fillColor = toneSpec.foreground.copy(alpha = fillAlpha),
                        borderColor = toneSpec.border.copy(alpha = 0.45f),
                        labelLayout = if (fitsLabel) labelCandidate else null,
                        labelOffset = Offset(x0 + 3.dp.toPx(), plotTop + 2.dp.toPx()),
                        labelColor = toneSpec.foreground,
                    )
                }

                // 2. Recommended channel glow column
                val recChannel = uiState.recommendation?.recommendedPrimaryChannel
                val recFreq = uiState.recommendation?.recommendedCenterFreqMhz
                val recColumnRect: Rect? = if (recFreq != null && recFreq in minFreq..uiState.maxFreqMhz) {
                    val halfSpan = ((uiState.recommendation.recommendedWidth.primarySpanMhz.coerceIn(20, 80)) * 0.5f)
                    val rx0 = freqToX(recFreq - halfSpan)
                    val rx1 = freqToX(recFreq + halfSpan)
                    Rect(rx0, plotTop, max(rx0 + 4.dp.toPx(), rx1), plotBottom)
                } else {
                    null
                }

                // 3. Y-axis dBm horizontal gridlines
                val yTicksDbm = listOf(-30, -50, -70, -85, -100)
                val cachedGridLines = mutableListOf<CachedGridLine>()
                var lastYLabelBottom = -100f
                for (dbm in yTicksDbm) {
                    val y = dbmToY(dbm)
                    val layout = textMeasurer.measure("$dbm", style = axisLabelStyle)
                    val labelTop = (y - layout.size.height * 0.5f).coerceIn(2f, size.height - layout.size.height - 2f)
                    val showLabel = labelTop >= lastYLabelBottom + 2.dp.toPx()
                    if (showLabel) {
                        lastYLabelBottom = labelTop + layout.size.height
                    }
                    cachedGridLines += CachedGridLine(
                        y = y,
                        labelLayout = if (showLabel) layout else null,
                        labelOffset = Offset(
                            x = (plotLeft - layout.size.width - 5.dp.toPx()).coerceAtLeast(2f),
                            y = labelTop,
                        ),
                    )
                }

                // 4. X-axis canonical / PSC channel ticks & guides
                val cachedGuides = mutableListOf<CachedChannelGuide>()
                var lastXLabelRight = plotLeft - 10f
                for (tick in axisSpec.ticks) {
                    val x = freqToX(tick.freqMhz.toFloat())
                    val isRec = recChannel != null && tick.channel == recChannel
                    val isHighlighted = uiState.highlightedChannel != null && tick.channel == uiState.highlightedChannel
                    val shouldTryLabel = tick.showAxisLabel || isRec || isHighlighted
                    val tickText = if (uiState.selectedBand == WifiBand.BAND_2_4_GHZ) {
                        "Ch ${tick.channel}"
                    } else {
                        "${tick.channel}"
                    }
                    val labelColor = when {
                        isRec -> colors.emerald
                        isHighlighted -> colors.cyan
                        tick.isCanonicalOrPsc -> colors.textSecondary
                        else -> colors.textMuted
                    }
                    val layout = if (shouldTryLabel) {
                        textMeasurer.measure(tickText, style = axisLabelStyle.copy(color = labelColor))
                    } else {
                        null
                    }
                    val labelLeft = if (layout != null) {
                        (x - layout.size.width * 0.5f).coerceIn(plotLeft, (size.width - layout.size.width - 2f).coerceAtLeast(plotLeft))
                    } else {
                        0f
                    }
                    val fitsX = layout != null && labelLeft >= lastXLabelRight + 4.dp.toPx()
                    if (fitsX) {
                        lastXLabelRight = labelLeft + layout.size.width
                    }
                    cachedGuides += CachedChannelGuide(
                        x = x,
                        isMajor = tick.isCanonicalOrPsc || isRec || isHighlighted,
                        isRecommended = isRec,
                        isHighlighted = isHighlighted,
                        labelLayout = if (fitsX) layout else null,
                        labelOffset = Offset(labelLeft, plotBottom + 4.dp.toPx()),
                        labelColor = labelColor,
                    )
                }

                // 5. Spectral overlap lobes (or 6 GHz RNR phantom guides when direct 6 GHz curves are empty)
                val dashedBridgeEffect = PathEffect.dashPathEffect(
                    floatArrayOf(5.dp.toPx(), 4.dp.toPx()),
                    0f,
                )
                val selectedStroke = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val connectedStroke = Stroke(
                    width = 2.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val normalStroke = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val rnrPhantomStroke = Stroke(
                    width = 1.8.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashedBridgeEffect,
                )

                val cachedLobes = mutableListOf<CachedSpectralLobe>()
                val placedPeakLabelRects = mutableListOf<Rect>()
                val maxPeakLabels = if (fontScale >= 1.5f) 3 else 5

                if (uiState.curves.isNotEmpty()) {
                    // Pick up to MAX_RENDERED_CURVES strongest + connected + selected, then draw weakest -> strongest -> connected -> selected
                    val normSelected = uiState.selectedBssid
                    val strongestPool = uiState.curves
                        .sortedWith(
                            compareByDescending<SpectralCurve> {
                                normSelected != null && MacAddressUtils.normalizeBssid(it.bssid) == normSelected
                            }
                                .thenByDescending { it.isConnected }
                                .thenByDescending { it.rssiDbm }
                                .thenBy { it.bssid }
                        )
                        .take(MAX_RENDERED_CURVES)

                    // Determine top-K curves eligible for non-colliding peak labels (highest priority first)
                    val peakLabelMap = mutableMapOf<String, Triple<TextLayoutResult, Rect, Color>>()
                    for ((priorityIdx, curve) in strongestPool.withIndex()) {
                        if (placedPeakLabelRects.size >= maxPeakLabels) break
                        val normBssid = MacAddressUtils.normalizeBssid(curve.bssid)
                        val isSel = normSelected != null && normBssid == normSelected
                        val accent = resolveCurveColor(curve, priorityIdx, isSel)
                        val (startF, endF) = resolvePrimaryLobeFreq(curve)
                        val peakX = freqToX((startF + endF) * 0.5f)
                        val peakY = dbmToY(curve.rssiDbm)
                        val shortName = curve.displayLabel.take(14).trim()
                        val tagText = "$shortName · Ch ${curve.primaryChannel}"
                        val layout = textMeasurer.measure(
                            text = tagText,
                            style = peakLabelStyle.copy(color = if (isSel || curve.isConnected) accent else colors.textPrimary),
                        )
                        val padH = 5.dp.toPx()
                        val padV = 2.dp.toPx()
                        val boxW = layout.size.width + padH * 2f
                        val boxH = layout.size.height + padV * 2f
                        val boxLeft = (peakX - boxW * 0.5f).coerceIn(
                            plotLeft + 2f,
                            (plotRight - boxW - 2f).coerceAtLeast(plotLeft + 2f),
                        )
                        val boxTop = (peakY - boxH - 5.dp.toPx()).coerceIn(
                            2f,
                            (plotBottom - boxH - 8.dp.toPx()).coerceAtLeast(2f),
                        )
                        val candidateRect = Rect(
                            boxLeft - 3.dp.toPx(),
                            boxTop - 2.dp.toPx(),
                            boxLeft + boxW + 3.dp.toPx(),
                            boxTop + boxH + 2.dp.toPx(),
                        )
                        if (placedPeakLabelRects.none { it.overlaps(candidateRect) }) {
                            placedPeakLabelRects += candidateRect
                            peakLabelMap[normBssid] = Triple(
                                layout,
                                Rect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH),
                                accent,
                            )
                        }
                    }

                    // Draw order: background weakest first, connected next, selected last on top
                    val drawOrdered = strongestPool.sortedWith(
                        compareBy<SpectralCurve> {
                            normSelected != null && MacAddressUtils.normalizeBssid(it.bssid) == normSelected
                        }
                            .thenBy { it.isConnected }
                            .thenBy { it.rssiDbm }
                            .thenBy { it.bssid }
                    )

                    val hasSelection = normSelected != null
                    for ((idx, curve) in drawOrdered.withIndex()) {
                        val normBssid = MacAddressUtils.normalizeBssid(curve.bssid)
                        val isSel = hasSelection && normBssid == normSelected
                        val isDimmed = hasSelection && !isSel
                        val baseColor = resolveCurveColor(curve, idx, isSel)

                        val (startFreq, endFreq) = resolvePrimaryLobeFreq(curve)
                        val xStart = freqToX(startFreq.toFloat())
                        val xEnd = max(xStart + 4.dp.toPx(), freqToX(endFreq.toFloat()))
                        val yPeak = dbmToY(curve.rssiDbm)

                        val fillPath = Path()
                        val outlinePath = Path()
                        appendOfdmTrapezoidLobe(
                            fillPath = fillPath,
                            outlinePath = outlinePath,
                            xStart = xStart,
                            xEnd = xEnd,
                            yPeak = yPeak,
                            yBase = plotBottom,
                            width = curve.width,
                            startFreqMhz = startFreq,
                            endFreqMhz = endFreq,
                            puncturedBitmap = curve.puncturedSubchannelBitmap,
                        )

                        var bridgePath: Path? = null
                        val secStart = curve.secondaryLobeStartFreqMhz
                        val secEnd = curve.secondaryLobeEndFreqMhz
                        if (secStart != null && secEnd != null && secEnd > secStart) {
                            val sx0 = freqToX(secStart.toFloat())
                            val sx1 = max(sx0 + 4.dp.toPx(), freqToX(secEnd.toFloat()))
                            appendOfdmTrapezoidLobe(
                                fillPath = fillPath,
                                outlinePath = outlinePath,
                                xStart = sx0,
                                xEnd = sx1,
                                yPeak = yPeak,
                                yBase = plotBottom,
                                width = ChannelWidth.WIDTH_80_MHZ,
                                startFreqMhz = secStart,
                                endFreqMhz = secEnd,
                                puncturedBitmap = null,
                            )
                            val leftBridgeX = min(xEnd, sx1)
                            val rightBridgeX = max(xStart, sx0)
                            if (rightBridgeX > leftBridgeX + 2f) {
                                bridgePath = Path().apply {
                                    val midX = (leftBridgeX + rightBridgeX) * 0.5f
                                    val bridgeY = (yPeak + 8.dp.toPx()).coerceAtMost(plotBottom - 4f)
                                    moveTo(leftBridgeX, bridgeY)
                                    quadraticTo(midX, max(plotTop, yPeak - 6.dp.toPx()), rightBridgeX, bridgeY)
                                }
                            }
                        }

                        val topAlpha = when {
                            isSel -> (0.34f + 0.08f * highlightAlpha).coerceAtMost(0.45f)
                            isDimmed -> 0.05f
                            curve.isConnected -> 0.26f
                            else -> 0.18f
                        }
                        val strokeAlpha = when {
                            isSel -> 1f
                            isDimmed -> 0.28f
                            else -> 0.92f
                        }
                        val fillBrush = Brush.verticalGradient(
                            colors = listOf(
                                baseColor.copy(alpha = topAlpha),
                                baseColor.copy(alpha = topAlpha * 0.12f),
                            ),
                            startY = yPeak,
                            endY = max(yPeak + 2f, plotBottom),
                        )

                        val labelTriple = peakLabelMap[normBssid]
                        val padH = 5.dp.toPx()
                        val padV = 2.dp.toPx()
                        cachedLobes += CachedSpectralLobe(
                            fillPath = fillPath,
                            outlinePath = outlinePath,
                            bridgePath = bridgePath,
                            fillBrush = fillBrush,
                            strokeColor = baseColor.copy(alpha = strokeAlpha),
                            strokeStyle = when {
                                isSel -> selectedStroke
                                curve.isConnected -> connectedStroke
                                else -> normalStroke
                            },
                            peakCenter = Offset((xStart + xEnd) * 0.5f, yPeak),
                            showPeakDot = isSel || curve.isConnected,
                            peakLabelLayout = labelTriple?.first,
                            peakLabelBgTopLeft = labelTriple?.second?.topLeft ?: Offset.Zero,
                            peakLabelBgSize = labelTriple?.second?.size ?: Size.Zero,
                            peakLabelTextOffset = if (labelTriple != null) {
                                Offset(labelTriple.second.left + padH, labelTriple.second.top + padV)
                            } else {
                                Offset.Zero
                            },
                            peakLabelBgColor = colors.surfaceL2.copy(alpha = 0.92f),
                            peakLabelBorderColor = (labelTriple?.third ?: baseColor).copy(alpha = 0.75f),
                        )
                    }
                } else if (uiState.selectedBand == WifiBand.BAND_6_GHZ && uiState.colocated6GhzNeighbors.isNotEmpty()) {
                    // Render dashed RNR 6 GHz beacon neighbor guides when hardware cannot directly scan 6 GHz
                    for (neighbor in uiState.colocated6GhzNeighbors.take(8)) {
                        val halfWidth = (neighbor.widthMhz.coerceIn(20, 320)) * 0.5f
                        val centerFreq = if (neighbor.centerFreqMhz in 5925..7125) {
                            neighbor.centerFreqMhz.toFloat()
                        } else {
                            (5950 + neighbor.channelNumber * 5).toFloat()
                        }
                        val xStart = freqToX(centerFreq - halfWidth)
                        val xEnd = max(xStart + 6.dp.toPx(), freqToX(centerFreq + halfWidth))
                        val yPeak = dbmToY(-60)

                        val fillPath = Path()
                        val outlinePath = Path()
                        appendOfdmTrapezoidLobe(
                            fillPath = fillPath,
                            outlinePath = outlinePath,
                            xStart = xStart,
                            xEnd = xEnd,
                            yPeak = yPeak,
                            yBase = plotBottom,
                            width = ChannelWidth.fromWidthMhz(neighbor.widthMhz),
                            startFreqMhz = (centerFreq - halfWidth).toInt(),
                            endFreqMhz = (centerFreq + halfWidth).toInt(),
                            puncturedBitmap = null,
                        )

                        var rnrLabel: TextLayoutResult? = null
                        var rnrRect = Rect.Zero
                        if (placedPeakLabelRects.size < maxPeakLabels) {
                            val tag = "RNR Ch ${neighbor.channelNumber}"
                            val measured = textMeasurer.measure(
                                text = tag,
                                style = peakLabelStyle.copy(color = colors.violet),
                            )
                            val padH = 5.dp.toPx()
                            val padV = 2.dp.toPx()
                            val boxW = measured.size.width + padH * 2f
                            val boxH = measured.size.height + padV * 2f
                            val peakX = (xStart + xEnd) * 0.5f
                            val boxLeft = (peakX - boxW * 0.5f).coerceIn(
                                plotLeft + 2f,
                                (plotRight - boxW - 2f).coerceAtLeast(plotLeft + 2f),
                            )
                            val boxTop = (yPeak - boxH - 5.dp.toPx()).coerceAtLeast(2f)
                            val candidate = Rect(boxLeft - 2f, boxTop - 2f, boxLeft + boxW + 2f, boxTop + boxH + 2f)
                            if (placedPeakLabelRects.none { it.overlaps(candidate) }) {
                                placedPeakLabelRects += candidate
                                rnrLabel = measured
                                rnrRect = Rect(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
                            }
                        }

                        val padH = 5.dp.toPx()
                        val padV = 2.dp.toPx()
                        cachedLobes += CachedSpectralLobe(
                            fillPath = fillPath,
                            outlinePath = outlinePath,
                            bridgePath = null,
                            fillBrush = Brush.verticalGradient(
                                colors = listOf(
                                    colors.violet.copy(alpha = 0.20f),
                                    colors.violet.copy(alpha = 0.02f),
                                ),
                                startY = yPeak,
                                endY = max(yPeak + 2f, plotBottom),
                            ),
                            strokeColor = colors.violet.copy(alpha = 0.9f),
                            strokeStyle = rnrPhantomStroke,
                            peakCenter = Offset((xStart + xEnd) * 0.5f, yPeak),
                            showPeakDot = true,
                            peakLabelLayout = rnrLabel,
                            peakLabelBgTopLeft = rnrRect.topLeft,
                            peakLabelBgSize = rnrRect.size,
                            peakLabelTextOffset = Offset(rnrRect.left + padH, rnrRect.top + padV),
                            peakLabelBgColor = colors.surfaceL2.copy(alpha = 0.92f),
                            peakLabelBorderColor = colors.violet.copy(alpha = 0.75f),
                        )
                    }
                }

                val bridgeStroke = Stroke(
                    width = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = dashedBridgeEffect,
                )
                val recGuideStroke = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = dashedBridgeEffect,
                )
                val borderStrokePx = 1.dp.toPx()
                val labelCornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                val dotOuterRadius = 5.dp.toPx()
                val dotInnerRadius = 2.8.dp.toPx()

                onDrawBehind {
                    // Sub-band backdrops
                    for (bandRect in cachedBackdrops) {
                        drawRect(
                            color = bandRect.fillColor,
                            topLeft = bandRect.rect.topLeft,
                            size = bandRect.rect.size,
                        )
                        drawLine(
                            color = bandRect.borderColor,
                            start = Offset(bandRect.rect.left, bandRect.rect.top),
                            end = Offset(bandRect.rect.left, bandRect.rect.bottom),
                            strokeWidth = borderStrokePx,
                        )
                        if (bandRect.labelLayout != null) {
                            drawText(
                                textLayoutResult = bandRect.labelLayout,
                                topLeft = bandRect.labelOffset,
                            )
                        }
                    }

                    // Recommended channel subtle emerald column
                    if (recColumnRect != null) {
                        drawRect(
                            color = colors.emerald.copy(alpha = 0.08f),
                            topLeft = recColumnRect.topLeft,
                            size = recColumnRect.size,
                        )
                    }

                    // Horizontal dBm grid lines + Y-axis labels
                    for (grid in cachedGridLines) {
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

                    // Vertical channel guides + X-axis labels
                    for (guide in cachedGuides) {
                        if (guide.isRecommended) {
                            drawLine(
                                color = colors.emerald.copy(alpha = 0.72f),
                                start = Offset(guide.x, plotTop),
                                end = Offset(guide.x, plotBottom),
                                strokeWidth = recGuideStroke.width,
                                pathEffect = recGuideStroke.pathEffect,
                            )
                        } else if (guide.isHighlighted) {
                            drawLine(
                                color = colors.cyan.copy(alpha = 0.72f),
                                start = Offset(guide.x, plotTop),
                                end = Offset(guide.x, plotBottom),
                                strokeWidth = recGuideStroke.width,
                                pathEffect = recGuideStroke.pathEffect,
                            )
                        } else if (guide.isMajor) {
                            drawLine(
                                color = colors.border.copy(alpha = 0.75f),
                                start = Offset(guide.x, plotTop),
                                end = Offset(guide.x, plotBottom),
                                strokeWidth = strokes.gridHairline.width,
                            )
                        } else {
                            drawLine(
                                color = colors.border,
                                start = Offset(guide.x, plotBottom - 4.dp.toPx()),
                                end = Offset(guide.x, plotBottom),
                                strokeWidth = strokes.gridHairline.width,
                            )
                        }
                        if (guide.labelLayout != null) {
                            drawText(
                                textLayoutResult = guide.labelLayout,
                                topLeft = guide.labelOffset,
                            )
                        }
                    }

                    // Spectral lobe fills & strokes
                    for (lobe in cachedLobes) {
                        drawPath(path = lobe.fillPath, brush = lobe.fillBrush)
                        drawPath(
                            path = lobe.outlinePath,
                            color = lobe.strokeColor,
                            style = lobe.strokeStyle,
                        )
                        if (lobe.bridgePath != null) {
                            drawPath(
                                path = lobe.bridgePath,
                                color = lobe.strokeColor,
                                style = bridgeStroke,
                            )
                        }
                        if (lobe.showPeakDot) {
                            drawCircle(
                                color = lobe.strokeColor.copy(alpha = 0.28f),
                                radius = dotOuterRadius,
                                center = lobe.peakCenter,
                            )
                            drawCircle(
                                color = lobe.strokeColor,
                                radius = dotInnerRadius,
                                center = lobe.peakCenter,
                            )
                        }
                    }

                    // Baseline & Y-axis frame
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

                    // Non-colliding peak labels on top of curves
                    for (lobe in cachedLobes) {
                        val layout = lobe.peakLabelLayout ?: continue
                        drawRoundRect(
                            color = lobe.peakLabelBgColor,
                            topLeft = lobe.peakLabelBgTopLeft,
                            size = lobe.peakLabelBgSize,
                            cornerRadius = labelCornerRadius,
                        )
                        drawRoundRect(
                            color = lobe.peakLabelBorderColor,
                            topLeft = lobe.peakLabelBgTopLeft,
                            size = lobe.peakLabelBgSize,
                            cornerRadius = labelCornerRadius,
                            style = strokes.gridHairline,
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = lobe.peakLabelTextOffset,
                        )
                    }
                }
            }
    )
}

private fun appendOfdmTrapezoidLobe(
    fillPath: Path,
    outlinePath: Path,
    xStart: Float,
    xEnd: Float,
    yPeak: Float,
    yBase: Float,
    width: ChannelWidth,
    startFreqMhz: Int,
    endFreqMhz: Int,
    puncturedBitmap: Int?,
) {
    val spanPx = (xEnd - xStart).coerceAtLeast(4f)
    val topFlatFraction = if (width == ChannelWidth.WIDTH_20_MHZ || width == ChannelWidth.UNKNOWN) {
        0.46f
    } else {
        0.76f
    }
    val shoulderPx = (spanPx * (1f - topFlatFraction) * 0.5f).coerceAtLeast(2f)
    val xTopLeft = xStart + shoulderPx
    val xTopRight = (xEnd - shoulderPx).coerceAtLeast(xTopLeft)

    outlinePath.moveTo(xStart, yBase)
    outlinePath.cubicTo(
        xStart + shoulderPx * 0.38f,
        yBase,
        xTopLeft - shoulderPx * 0.38f,
        yPeak,
        xTopLeft,
        yPeak,
    )

    fillPath.moveTo(xStart, yBase)
    fillPath.cubicTo(
        xStart + shoulderPx * 0.38f,
        yBase,
        xTopLeft - shoulderPx * 0.38f,
        yPeak,
        xTopLeft,
        yPeak,
    )

    val bitmap = puncturedBitmap ?: 0
    val lobeMhz = (endFreqMhz - startFreqMhz).coerceAtLeast(20)
    val subCount = (lobeMhz / 20).coerceIn(1, 16)

    if (bitmap != 0 && subCount > 1 && xTopRight > xTopLeft + 4f) {
        val yNotch = (yBase - (yBase - yPeak) * 0.28f).coerceIn(yPeak, yBase)
        var cursorX = xTopLeft
        for (subIdx in 0 until subCount) {
            val isPunctured = ((bitmap ushr subIdx) and 1) != 0
            if (!isPunctured) continue
            val subStartX = (xStart + (subIdx.toFloat() / subCount) * spanPx).coerceIn(xTopLeft, xTopRight)
            val subEndX = (xStart + ((subIdx + 1).toFloat() / subCount) * spanPx).coerceIn(xTopLeft, xTopRight)
            if (subEndX <= subStartX + 1.5f) continue
            if (subStartX > cursorX) {
                outlinePath.lineTo(subStartX, yPeak)
                fillPath.lineTo(subStartX, yPeak)
            }
            val bevel = ((subEndX - subStartX) * 0.18f).coerceAtLeast(1f)
            outlinePath.lineTo(subStartX + bevel, yNotch)
            outlinePath.lineTo(subEndX - bevel, yNotch)
            outlinePath.lineTo(subEndX, yPeak)

            fillPath.lineTo(subStartX + bevel, yNotch)
            fillPath.lineTo(subEndX - bevel, yNotch)
            fillPath.lineTo(subEndX, yPeak)
            cursorX = subEndX
        }
        if (cursorX < xTopRight) {
            outlinePath.lineTo(xTopRight, yPeak)
            fillPath.lineTo(xTopRight, yPeak)
        }
    } else {
        outlinePath.lineTo(xTopRight, yPeak)
        fillPath.lineTo(xTopRight, yPeak)
    }

    outlinePath.cubicTo(
        xTopRight + shoulderPx * 0.38f,
        yPeak,
        xEnd - shoulderPx * 0.38f,
        yBase,
        xEnd,
        yBase,
    )
    fillPath.cubicTo(
        xTopRight + shoulderPx * 0.38f,
        yPeak,
        xEnd - shoulderPx * 0.38f,
        yBase,
        xEnd,
        yBase,
    )
    fillPath.close()
}

private fun resolvePrimaryLobeFreq(curve: SpectralCurve): Pair<Int, Int> {
    if (curve.lobeEndFreqMhz > curve.lobeStartFreqMhz && curve.lobeStartFreqMhz > 0) {
        return curve.lobeStartFreqMhz to curve.lobeEndFreqMhz
    }
    val center = if (curve.centerFreq0Mhz > 0) curve.centerFreq0Mhz else curve.primaryFreqMhz
    val halfSpan = (curve.width.primarySpanMhz.coerceAtLeast(20)) / 2
    return (center - halfSpan) to (center + halfSpan)
}

private fun resolveCurveColor(
    curve: SpectralCurve,
    index: Int,
    isSelected: Boolean,
): Color = when {
    curve.isConnected -> RadarPalette.ElectricEmerald
    isSelected -> RadarPalette.LuminousCyan
    curve.band == WifiBand.BAND_6_GHZ && index % 2 == 0 -> RadarPalette.SpectrumViolet
    else -> CURVE_ACCENT_PALETTE[index % CURVE_ACCENT_PALETTE.size]
}

private fun buildCanvasContentDescription(uiState: SpectrumUiState): String = buildString {
    append("${uiState.selectedBand.displayLabel} wireless spectrum overlap graph, ")
    append("${uiState.minFreqMhz} to ${uiState.maxFreqMhz} MHz, ")
    if (uiState.curves.isEmpty() && uiState.selectedBand == WifiBand.BAND_6_GHZ && uiState.colocated6GhzNeighbors.isNotEmpty()) {
        append("0 direct radios, ${uiState.colocated6GhzNeighbors.size} co-located 6 GHz RNR beacon guides.")
    } else {
        val noun = if (uiState.curves.size == 1) "active radio" else "active radios"
        append("${uiState.curves.size} $noun.")
    }
    uiState.recommendation?.let { rec ->
        append(" Recommended Channel ${rec.recommendedPrimaryChannel} (${rec.score0To100} out of 100).")
    }
    uiState.selectedCurve?.let { sel ->
        val safeDbm = SpectrumDomainResolver.safeRssiDbm(sel.rssiDbm)
        val (sFreq, eFreq) = resolvePrimaryLobeFreq(sel)
        append(" Highlighted ${sel.displayLabel} on Ch ${sel.primaryChannel} ($sFreq–$eFreq MHz) at $safeDbm dBm.")
    }
}

private fun buildCanvasStateDescription(uiState: SpectrumUiState): String = buildString {
    append(uiState.selectedBand.displayLabel)
    append(" · ")
    if (uiState.curves.isEmpty() && uiState.selectedBand == WifiBand.BAND_6_GHZ && uiState.colocated6GhzNeighbors.isNotEmpty()) {
        append("0 direct radios · ${uiState.colocated6GhzNeighbors.size} RNR beacon neighbors")
    } else {
        val noun = if (uiState.curves.size == 1) "radio" else "radios"
        append("${uiState.curves.size} $noun")
    }
    uiState.recommendation?.let { rec ->
        append(" · Best Ch ${rec.recommendedPrimaryChannel}")
    }
    uiState.selectedCurve?.let { sel ->
        append(" · Highlighted ${sel.displayLabel}")
    }
}

@Composable
private fun CurveSelectorChip(
    curve: SpectralCurve,
    accentColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = RadarTheme.colors
    val safeDbm = SpectrumDomainResolver.safeRssiDbm(curve.rssiDbm)
    val label = buildString {
        append(curve.displayLabel)
        append(" · Ch ${curve.primaryChannel} · $safeDbm dBm")
        if (curve.isConnected) append(" · Connected")
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = RadarTheme.dimens.minTouchTarget)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (isSelected) "Highlighted" else "Tap to highlight"
            }
            .testTag(SpectrumTestTags.curveChip(curve.bssid)),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) colors.surfaceL2 else colors.surfaceL1,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) accentColor else colors.activeBorder,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = RadarTheme.typography.badgeLabel,
                color = if (isSelected) accentColor else colors.textPrimary,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCurveInspectorBanner(
    curve: SpectralCurve,
    onClearSelection: () -> Unit,
    onOpenProximity: ((ProximityTargetKey) -> Unit)?,
    onOpenNetworkDetail: ((String) -> Unit)?,
) {
    val colors = RadarTheme.colors
    val typography = RadarTheme.typography
    val safeDbm = SpectrumDomainResolver.safeRssiDbm(curve.rssiDbm)
    val (sFreq, eFreq) = resolvePrimaryLobeFreq(curve)
    val secStart = curve.secondaryLobeStartFreqMhz
    val secEnd = curve.secondaryLobeEndFreqMhz
    val spanSummary = buildString {
        append("Ch ${curve.primaryChannel} · ")
        append(SemanticFormatters.formatChannelWidthWithTrait(curve.width))
        append(" ($sFreq–$eFreq MHz")
        if (secStart != null && secEnd != null && secEnd > secStart) {
            append(" + $secStart–$secEnd MHz")
        }
        append(")")
    }

    RadarElevatedCard(
        modifier = Modifier.testTag(SpectrumTestTags.SELECTED_CURVE_INSPECTOR),
        accentTone = if (curve.isConnected) SemanticTone.EXCELLENT else SemanticTone.INFO,
        contentPadding = PaddingValues(12.dp),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column {
                Text(
                    text = if (curve.isConnected) {
                        "${curve.displayLabel} · Connected Radio"
                    } else {
                        curve.displayLabel
                    },
                    style = typography.metricMedium,
                    color = colors.textPrimary,
                )
                Text(
                    text = MacAddressUtils.normalizeBssid(curve.bssid),
                    style = typography.monoAddress,
                    color = colors.textSecondary,
                )
            }
            SignalBadge(rssiDbm = safeDbm)
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = spanSummary,
            style = typography.explanationBody,
            color = colors.textPrimary,
        )

        val bssColor = curve.bssColor
        val utilPct = curve.utilizationPct
        val puncturedBitmap = curve.puncturedSubchannelBitmap
        val extraPills = buildList {
            if (bssColor != null && bssColor > 0) {
                add("BSS Color $bssColor")
            }
            if (utilPct != null) {
                add("${utilPct.coerceIn(0, 100)}% airtime busy")
            }
            if (puncturedBitmap != null && puncturedBitmap != 0) {
                val puncturedCount = Integer.bitCount(puncturedBitmap and 0xFFFF)
                add("Wi-Fi 7 Punctured ($puncturedCount × 20 MHz notched)")
            }
        }
        if (extraPills.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                extraPills.forEach { badgeText ->
                    val spec = colors.toneSpec(SemanticTone.INFO)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = spec.container,
                        border = BorderStroke(1.dp, spec.border),
                    ) {
                        Text(
                            text = badgeText,
                            style = typography.badgeLabel,
                            color = spec.foreground,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onOpenProximity != null) {
                Button(
                    onClick = {
                        onOpenProximity(
                            ProximityTargetKey.BssidLock(
                                bssid = MacAddressUtils.normalizeBssid(curve.bssid),
                                ssidHint = curve.ssid.ifBlank { curve.displayLabel },
                            )
                        )
                    },
                    modifier = Modifier
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(SpectrumTestTags.SELECTED_CURVE_PROXIMITY_CTA),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.cyan,
                        contentColor = colors.onAccentDark,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = RadarIcons.Proximity,
                        contentDescription = null,
                        tint = colors.onAccentDark,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Measure Proximity",
                        style = typography.badgeLabel,
                        color = colors.onAccentDark,
                    )
                }
            }

            if (onOpenNetworkDetail != null) {
                OutlinedButton(
                    onClick = { onOpenNetworkDetail(MacAddressUtils.normalizeBssid(curve.bssid)) },
                    modifier = Modifier
                        .heightIn(min = RadarTheme.dimens.minTouchTarget)
                        .testTag(SpectrumTestTags.SELECTED_CURVE_DETAIL_CTA),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.activeBorder),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = "Inspect Radio",
                        style = typography.badgeLabel,
                        color = colors.textPrimary,
                    )
                }
            }

            OutlinedButton(
                onClick = onClearSelection,
                modifier = Modifier
                    .heightIn(min = RadarTheme.dimens.minTouchTarget)
                    .testTag(SpectrumTestTags.CLEAR_SELECTION_CHIP),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, colors.activeBorder),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Clear Highlight",
                    style = typography.badgeLabel,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
