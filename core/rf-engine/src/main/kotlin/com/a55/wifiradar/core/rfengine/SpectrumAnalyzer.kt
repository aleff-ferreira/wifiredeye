package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.CciAciBreakdown
import com.a55.wifiradar.core.model.ChannelInterferer
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ChannelScore
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.InterferenceType
import com.a55.wifiradar.core.model.InterfererRelationship
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Linear-milliwatt (`10^(RSSI/10)`) spectral mask overlap, Wi-Fi 7 preamble puncturing,
 * asymmetric CCI vs ACI (`1.85x`) interference analyzer, and canonical Best-Channel
 * Recommender (`F15`).
 */
object SpectrumAnalyzer {

    const val ACI_PENALTY_MULTIPLIER: Double = 1.85
    const val DEFAULT_QBSS_UTILIZATION: Double = 0.25
    private const val MAX_RENDERED_CURVES_DEFAULT: Int = 48
    private const val MAX_RENDERED_CURVES_HARD_CAP: Int = 64

    private val CHANNELS_24_GHZ_BASE = (1..13).toList()
    private val NON_DFS_5_GHZ_CHANNELS = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    private val DFS_5_GHZ_CHANNELS = listOf(
        52, 56, 60, 64,
        100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144,
    )
    private val PSC_6_GHZ_CHANNELS = List(15) { idx -> 5 + 16 * idx }
    private val WIGIG_60_GHZ_CHANNELS = (1..6).toList()

    private data class CandidateBlock(
        val primaryChannel: Int,
        val primaryFreqMhz: Int,
        val centerFreqMhz: Int,
        val width: ChannelWidth,
        val isDfs: Boolean,
        val isTdwr: Boolean,
        val isPsc: Boolean,
    )

    private data class BlockEvaluation(
        val candidate: CandidateBlock,
        val score0To100: Int,
        val starRating0To5: Double,
        val noiseFloorDbm: Double,
        val cciCount: Int,
        val aciCount: Int,
        val merit: Double,
    )

    fun analyze(
        networks: List<BssidRadio>,
        band: WifiBand,
        includeDfs: Boolean = false,
        excludeConnectedSsid: Boolean = false,
        radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    ): SpectrumAnalysis {
        val (minFreq, maxFreq, isSupported) = when (band) {
            WifiBand.BAND_2_4_GHZ -> Triple(2400, 2495, radioCapabilities.has24Ghz)
            WifiBand.BAND_5_GHZ -> Triple(5150, 5895, radioCapabilities.has5Ghz)
            WifiBand.BAND_6_GHZ -> Triple(5925, 7125, radioCapabilities.has6Ghz)
            WifiBand.BAND_60_GHZ -> Triple(57240, 71280, radioCapabilities.has60Ghz)
            WifiBand.UNKNOWN -> Triple(0, 0, false)
        }

        if (band == WifiBand.UNKNOWN) {
            return SpectrumAnalysis(
                band = WifiBand.UNKNOWN,
                includeDfs = includeDfs,
                excludeConnectedSsid = excludeConnectedSsid,
                minFreqMhz = 0,
                maxFreqMhz = 0,
                isBandSupportedByHardware = false,
                hardwareExplanationMessage = "Unclassified wireless frequency band.",
            )
        }

        val colocated6GhzFromRnr = if (band == WifiBand.BAND_6_GHZ) {
            networks.asSequence()
                .flatMap { it.beaconDetails.colocated6GhzNeighbors.asSequence() }
                .distinctBy {
                    listOf(
                        it.neighborBssid.orEmpty(),
                        it.operatingClass.toString(),
                        it.channelNumber.toString(),
                        it.sourceBssid,
                    )
                }
                .sortedWith(
                    compareBy<Colocated6GhzNeighbor> { it.channelNumber }
                        .thenBy { it.sourceBssid }
                )
                .toList()
        } else {
            emptyList()
        }

        val hardwareMessage = buildHardwareExplanationMessage(
            band = band,
            isSupported = isSupported,
            radioCapabilities = radioCapabilities,
            rnrCount = colocated6GhzFromRnr.size,
        )

        val connectedGlobal = networks.firstOrNull { it.isConnected }
        val inBandRadios = networks.filter { it.channelDescriptor.band == band }

        val activeRadios = if (excludeConnectedSsid && connectedGlobal != null) {
            inBandRadios.filterNot { r ->
                r.isConnected || isSiblingOfRadio(r, connectedGlobal)
            }
        } else {
            inBandRadios
        }

        val connectedInBand = activeRadios.firstOrNull { it.isConnected }

        val allCurves = activeRadios
            .sortedWith(
                compareByDescending<BssidRadio> { it.isConnected }
                    .thenByDescending { it.rssiDbm }
                    .thenBy { it.bssid }
            )
            .map { radio ->
                buildSpectralCurve(
                    radio = radio,
                    anchorRadio = connectedGlobal,
                )
            }

        val (retainedCurves, aggregatedWeakCount) = capDenseCurves(allCurves)

        val scoredChannelNumbers = determineScoredChannelList(
            band = band,
            includeDfs = includeDfs,
            inBandRadios = activeRadios,
        )

        val excludedSelfBssidForChannelScoring = connectedInBand?.bssid
        val channelScores = ArrayList<ChannelScore>(scoredChannelNumbers.size)
        val perChannelBreakdowns = LinkedHashMap<Int, CciAciBreakdown>(scoredChannelNumbers.size + 4)

        for (ch in scoredChannelNumbers) {
            val chFreq = ChannelAndFrequencyResolver.channelToFrequencyMhz(band, ch)
            if (chFreq <= 0) continue
            val eval = evaluateChannelMask(
                band = band,
                primaryChannel = ch,
                primaryFreqMhz = chFreq,
                centerFreqMhz = chFreq,
                width = ChannelWidth.WIDTH_20_MHZ,
                activeRadios = activeRadios,
                excludeBssid = excludedSelfBssidForChannelScoring,
                anchorForRelationship = connectedInBand ?: connectedGlobal,
            )
            val isPsc = ChannelAndFrequencyResolver.isPsc6GhzChannel(band, ch)
            val isCanonical = ChannelAndFrequencyResolver.isCanonicalPrimaryChannel(band, ch, isPsc)
            val isDfs = band == WifiBand.BAND_5_GHZ && (ch in 52..64 || ch in 100..144)
            val isTdwr = band == WifiBand.BAND_5_GHZ && ch in setOf(120, 124, 128)
            val subBand = ChannelAndFrequencyResolver.resolveUniiSubBand(band, chFreq)

            channelScores.add(
                ChannelScore(
                    band = band,
                    channel = ch,
                    centerFreqMhz = chFreq,
                    evaluatedWidth = ChannelWidth.WIDTH_20_MHZ,
                    score0To100 = eval.score0To100,
                    starRating0To5 = eval.starRating0To5,
                    equivalentNoiseFloorDbm = eval.noiseFloorDbm,
                    cciCount = eval.cciCount,
                    aciCount = eval.aciCount,
                    isCanonical = isCanonical,
                    isDfs = isDfs,
                    isTdwr = isTdwr,
                    isPsc = isPsc,
                    uniiSubBand = subBand,
                    plainEnglishRating = SemanticFormatters.formatChannelScore(
                        eval.score0To100,
                        eval.starRating0To5,
                    ),
                )
            )
            perChannelBreakdowns[ch] = eval.breakdown
        }

        // Also ensure every active radio's primary channel has an entry in perChannelBreakdowns
        for (r in activeRadios) {
            val ch = r.channelDescriptor.primaryChannel
            if (ch > 0 && ch !in perChannelBreakdowns) {
                val chFreq = r.channelDescriptor.primaryFreqMhz
                val eval = evaluateChannelMask(
                    band = band,
                    primaryChannel = ch,
                    primaryFreqMhz = chFreq,
                    centerFreqMhz = chFreq,
                    width = ChannelWidth.WIDTH_20_MHZ,
                    activeRadios = activeRadios,
                    excludeBssid = if (r.isConnected) r.bssid else excludedSelfBssidForChannelScoring,
                    anchorForRelationship = r,
                )
                perChannelBreakdowns[ch] = eval.breakdown
            }
        }

        val focusRadio = connectedInBand ?: activeRadios.maxWithOrNull(
            compareBy<BssidRadio> { it.rssiDbm }.thenBy { it.bssid }
        )
        val selectedOrConnectedBreakdown = if (focusRadio != null && focusRadio.channelDescriptor.primaryChannel > 0) {
            val focusEval = evaluateChannelMask(
                band = band,
                primaryChannel = focusRadio.channelDescriptor.primaryChannel,
                primaryFreqMhz = focusRadio.channelDescriptor.primaryFreqMhz,
                centerFreqMhz = focusRadio.channelDescriptor.centerFreq0Mhz.takeIf { it > 0 }
                    ?: focusRadio.channelDescriptor.primaryFreqMhz,
                width = focusRadio.channelDescriptor.width,
                activeRadios = activeRadios,
                excludeBssid = focusRadio.bssid,
                anchorForRelationship = focusRadio,
            )
            val focusBreakdown = focusEval.breakdown.copy(targetBssid = focusRadio.bssid)
            if (focusRadio.isConnected) {
                perChannelBreakdowns[focusRadio.channelDescriptor.primaryChannel] = focusBreakdown
            }
            focusBreakdown
        } else {
            null
        }

        val recommendation = buildChannelRecommendation(
            band = band,
            includeDfs = includeDfs,
            activeRadios = activeRadios,
            connectedInBand = connectedInBand,
            connectedGlobal = connectedGlobal,
            channelScores = channelScores,
            colocated6GhzFromRnr = colocated6GhzFromRnr,
            radioCapabilities = radioCapabilities,
        )

        return SpectrumAnalysis(
            band = band,
            includeDfs = includeDfs,
            excludeConnectedSsid = excludeConnectedSsid,
            minFreqMhz = minFreq,
            maxFreqMhz = maxFreq,
            curves = retainedCurves,
            aggregatedWeakCurveCount = aggregatedWeakCount,
            channelScores = channelScores,
            recommendation = recommendation,
            selectedOrConnectedBreakdown = selectedOrConnectedBreakdown,
            perChannelBreakdowns = perChannelBreakdowns,
            colocated6GhzNeighborsFromRnr = colocated6GhzFromRnr,
            isBandSupportedByHardware = isSupported,
            hardwareExplanationMessage = hardwareMessage,
        )
    }

    /**
     * Enriches every `BssidRadio` in `radios` with per-channel `effectiveNoiseFloorDbm`,
     * `estimatedSnrDb`, and `channelCongestionScore0To100` (excluding the radio from
     * penalizing itself!).
     */
    fun enrichRadiosWithChannelMetrics(radios: List<BssidRadio>): List<BssidRadio> {
        if (radios.isEmpty()) return emptyList()
        val byBand = radios.groupBy { it.channelDescriptor.band }

        val updatedByBssid = HashMap<String, BssidRadio>(radios.size * 2)
        for ((band, bandRadios) in byBand) {
            if (band == WifiBand.UNKNOWN) {
                for (r in bandRadios) updatedByBssid[r.bssid] = r
                continue
            }
            for (r in bandRadios) {
                val primCh = r.channelDescriptor.primaryChannel
                val primFreq = r.channelDescriptor.primaryFreqMhz
                if (primCh <= 0 || primFreq <= 0) {
                    updatedByBssid[r.bssid] = r
                    continue
                }
                val eval = evaluateChannelMask(
                    band = band,
                    primaryChannel = primCh,
                    primaryFreqMhz = primFreq,
                    centerFreqMhz = r.channelDescriptor.centerFreq0Mhz.takeIf { it > 0 } ?: primFreq,
                    width = r.channelDescriptor.width,
                    activeRadios = bandRadios,
                    excludeBssid = r.bssid,
                    anchorForRelationship = r,
                )
                val effNoiseFloor = max(-95.0, eval.noiseFloorDbm).finiteOr(-95.0)
                val snrDb = (r.rssiDbm.toDouble() - effNoiseFloor).finiteCoerceIn(-20.0, 95.0, 25.0)
                updatedByBssid[r.bssid] = r.copy(
                    effectiveNoiseFloorDbm = effNoiseFloor,
                    estimatedSnrDb = snrDb,
                    channelCongestionScore0To100 = eval.score0To100,
                )
            }
        }
        return radios.map { updatedByBssid[it.bssid] ?: it }
    }

    private data class MaskEvalResult(
        val score0To100: Int,
        val starRating0To5: Double,
        val noiseFloorDbm: Double,
        val cciCount: Int,
        val aciCount: Int,
        val breakdown: CciAciBreakdown,
    )

    private fun evaluateChannelMask(
        band: WifiBand,
        primaryChannel: Int,
        primaryFreqMhz: Int,
        centerFreqMhz: Int,
        width: ChannelWidth,
        activeRadios: List<BssidRadio>,
        excludeBssid: String?,
        anchorForRelationship: BssidRadio?,
    ): MaskEvalResult {
        val nominalWidthMhz = when {
            band == WifiBand.BAND_60_GHZ -> 2160
            width == ChannelWidth.UNKNOWN -> 20
            else -> width.primarySpanMhz
        }
        val maskWidthMhz = if (band == WifiBand.BAND_2_4_GHZ && nominalWidthMhz == 20) {
            22.0
        } else {
            nominalWidthMhz.toDouble()
        }
        val halfMask = maskWidthMhz / 2.0
        val leftMhz = centerFreqMhz.toDouble() - halfMask
        val rightMhz = centerFreqMhz.toDouble() + halfMask

        val cciList = ArrayList<ChannelInterferer>()
        val aciList = ArrayList<ChannelInterferer>()
        var totalInterferenceMw = 0.0
        var totalCciMw = 0.0
        var totalAciMw = 0.0
        var cciCountAbove90 = 0
        var aciCountAbove90 = 0

        for (radio in activeRadios) {
            if (excludeBssid != null && radio.bssid == excludeBssid) continue

            val overlap = computeSpectralOverlapRatio(
                band = band,
                candCenterMhz = centerFreqMhz.toDouble(),
                candLeftMhz = leftMhz,
                candRightMhz = rightMhz,
                candMaskWidthMhz = maskWidthMhz,
                radio = radio,
            )
            if (overlap <= 0.0) continue

            val isCci = radio.channelDescriptor.primaryFreqMhz == primaryFreqMhz
            val type = if (isCci) {
                InterferenceType.CO_CHANNEL_CCI
            } else {
                InterferenceType.ADJACENT_CHANNEL_ACI
            }

            val penalty = if (isCci) {
                val uQbss = radio.beaconDetails.bssLoad
                    ?.let { (it.channelUtilizationRaw.coerceIn(0, 255).toDouble() / 255.0) }
                    ?: DEFAULT_QBSS_UTILIZATION
                (1.0 * (1.0 + uQbss)).finiteCoerceIn(1.0, 2.0, 1.25)
            } else {
                ACI_PENALTY_MULTIPLIER
            }

            val rssiClamped = radio.rssiDbm.coerceIn(-120, 0)
            val linearMw = 10.0.pow(rssiClamped.toDouble() / 10.0).finiteOr(0.0)
            val weightedMw = (penalty * overlap * linearMw).finiteOr(0.0)

            totalInterferenceMw += weightedMw
            if (isCci) {
                totalCciMw += weightedMw
                if (radio.rssiDbm >= -90) cciCountAbove90++
            } else {
                totalAciMw += weightedMw
                if (radio.rssiDbm >= -90) aciCountAbove90++
            }

            val relationship = if (anchorForRelationship != null && isSiblingOfRadio(radio, anchorForRelationship)) {
                InterfererRelationship.OWN_MESH_SELF_CONTENTION
            } else {
                InterfererRelationship.NEIGHBOR_NETWORK
            }
            val actionable = radio.rssiDbm >= -80 && overlap >= 0.05

            val interferer = ChannelInterferer(
                bssid = radio.bssid,
                ssid = radio.ssid.displayName,
                displayLabel = "${radio.effectiveDisplayName} (Ch ${radio.channelDescriptor.primaryChannel})",
                rssiDbm = radio.rssiDbm,
                primaryChannel = radio.channelDescriptor.primaryChannel,
                width = radio.channelDescriptor.width,
                overlapRatio0To1 = overlap.finiteCoerceIn(0.0, 1.0, 0.0),
                penaltyMultiplier = penalty,
                weightedInterferenceMw = weightedMw,
                interferenceType = type,
                relationship = relationship,
                isActionable = actionable,
            )

            if (isCci) {
                cciList.add(interferer)
            } else {
                aciList.add(interferer)
            }
        }

        val interfererComparator = compareByDescending<ChannelInterferer> { it.weightedInterferenceMw }
            .thenByDescending { it.rssiDbm }
            .thenBy { it.bssid }
        cciList.sortWith(interfererComparator)
        aciList.sortWith(interfererComparator)

        val bwRatio = (nominalWidthMhz.coerceAtLeast(20).toDouble() / 20.0)
        val bwOffsetDb = 10.0 * log10(bwRatio)
        val thermalNoiseDbm = -96.0 + bwOffsetDb
        val cleanFloorRefDbm = -95.0 + bwOffsetDb
        val thermalNoiseMw = 10.0.pow(thermalNoiseDbm / 10.0)

        val totalMw = (thermalNoiseMw + totalInterferenceMw).coerceAtLeast(thermalNoiseMw)
        val eqNoiseDbm = (10.0 * log10(totalMw)).finiteCoerceIn(-105.0, 0.0, -95.0)

        val excessDb = max(0.0, eqNoiseDbm - cleanFloorRefDbm)
        val rawScore = 100.0 - 1.8 * excessDb - 3.5 * cciCountAbove90 - 6.0 * aciCountAbove90
        val score0To100 = rawScore.finiteOr(100.0).roundToInt().coerceIn(0, 100)
        val stars = (0.5 * (score0To100.toDouble() / 10.0).roundToInt()).finiteCoerceIn(0.0, 5.0, 5.0)

        val actionableCci = cciList.count { it.isActionable }
        val actionableAci = aciList.count { it.isActionable }
        val severeAci = actionableAci >= 1 && (
            aciList.any { it.rssiDbm >= -72 && it.overlapRatio0To1 >= 0.15 } ||
                totalAciMw >= 1.0e-7
            )

        val cciSummary = if (cciList.isEmpty()) {
            "0 shared-channel routers — full polite airtime available on Channel $primaryChannel."
        } else {
            val noun = if (cciList.size == 1) "router shares" else "routers share"
            "${cciList.size} $noun Channel $primaryChannel via polite turn-taking ($actionableCci strong nearby)."
        }

        val aciSummary = if (aciList.isEmpty()) {
            "0 off-center overlapping routers — clean sidebands with no collision noise."
        } else {
            val noun = if (aciList.size == 1) "router bleeds" else "routers bleed"
            "${aciList.size} overlapping off-center $noun into Channel $primaryChannel, causing packet retries and lag."
        }

        val breakdown = CciAciBreakdown(
            targetChannel = primaryChannel,
            targetBand = band,
            targetBssid = null,
            cciInterferers = cciList,
            aciInterferers = aciList,
            actionableCciCount = actionableCci,
            actionableAciCount = actionableAci,
            totalCciPowerMw = totalCciMw.finiteOr(0.0),
            totalAciPowerMw = totalAciMw.finiteOr(0.0),
            cciPlainEnglishSummary = cciSummary,
            aciPlainEnglishSummary = aciSummary,
            hasSevereAciWarning = severeAci,
        )

        return MaskEvalResult(
            score0To100 = score0To100,
            starRating0To5 = stars,
            noiseFloorDbm = eqNoiseDbm,
            cciCount = cciCountAbove90,
            aciCount = aciCountAbove90,
            breakdown = breakdown,
        )
    }

    private fun computeSpectralOverlapRatio(
        band: WifiBand,
        candCenterMhz: Double,
        candLeftMhz: Double,
        candRightMhz: Double,
        candMaskWidthMhz: Double,
        radio: BssidRadio,
    ): Double {
        val segments = activeUnpuncturedSegmentsMhz(radio)
        var overlapMhz = 0.0
        for ((segStart, segEnd) in segments) {
            val inter = min(candRightMhz, segEnd.toDouble()) - max(candLeftMhz, segStart.toDouble())
            if (inter > 0.0) {
                overlapMhz += inter
            }
        }
        val mainRatio = (overlapMhz / candMaskWidthMhz).finiteCoerceIn(0.0, 1.0, 0.0)

        if (band == WifiBand.BAND_2_4_GHZ && overlapMhz <= 0.0) {
            val radioCenter = (radio.channelDescriptor.centerFreq0Mhz.takeIf { it > 0 }
                ?: radio.channelDescriptor.primaryFreqMhz).toDouble()
            val df = abs(candCenterMhz - radioCenter)
            if (df < 25.0) {
                val skirt = (0.15 * (1.0 - df / 25.0)).finiteCoerceIn(0.0, 0.15, 0.0)
                return max(mainRatio, skirt)
            }
        }

        return mainRatio
    }

    private fun activeUnpuncturedSegmentsMhz(radio: BssidRadio): List<Pair<Int, Int>> {
        val desc = radio.channelDescriptor
        val pStart = desc.occupiedStartFreqMhz
        val pEnd = desc.occupiedEndFreqMhz
        if (pEnd <= pStart) return emptyList()

        val punctBitmap = radio.beaconDetails.ehtMlo
            ?.takeIf { it.isPreamblePunctured }
            ?.disabledSubchannelBitmap
            ?.takeIf { it > 0 }

        val spanMhz = pEnd - pStart
        val subchannelCount = spanMhz / 20
        val canSlicePuncturing = punctBitmap != null &&
            desc.width != ChannelWidth.WIDTH_80_PLUS_80_MHZ &&
            spanMhz in setOf(80, 160, 320) &&
            spanMhz % 20 == 0 &&
            !(0 until subchannelCount).all { s -> ((punctBitmap shr s) and 1) == 1 }

        val segments = ArrayList<Pair<Int, Int>>(4)
        if (canSlicePuncturing) {
            for (s in 0 until subchannelCount) {
                if (((punctBitmap shr s) and 1) == 0) {
                    segments.add((pStart + 20 * s) to (pStart + 20 * (s + 1)))
                }
            }
        } else {
            segments.add(pStart to pEnd)
        }

        val secStart = desc.secondaryOccupiedStartFreqMhz
        val secEnd = desc.secondaryOccupiedEndFreqMhz
        if (secStart != null && secEnd != null && secEnd > secStart) {
            segments.add(secStart to secEnd)
        }
        return segments
    }

    private fun buildChannelRecommendation(
        band: WifiBand,
        includeDfs: Boolean,
        activeRadios: List<BssidRadio>,
        connectedInBand: BssidRadio?,
        connectedGlobal: BssidRadio?,
        channelScores: List<ChannelScore>,
        colocated6GhzFromRnr: List<Colocated6GhzNeighbor>,
        radioCapabilities: DeviceRadioCapabilities,
    ): ChannelRecommendation? {
        val candidates = generateRecommendationCandidates(band, includeDfs)
        if (candidates.isEmpty()) return null

        val excludedBssid = connectedInBand?.bssid
        val anchor = connectedInBand ?: connectedGlobal

        val evaluations = candidates.map { cand ->
            val eval = evaluateChannelMask(
                band = band,
                primaryChannel = cand.primaryChannel,
                primaryFreqMhz = cand.primaryFreqMhz,
                centerFreqMhz = cand.centerFreqMhz,
                width = cand.width,
                activeRadios = activeRadios,
                excludeBssid = excludedBssid,
                anchorForRelationship = anchor,
            )

            var merit = eval.score0To100.toDouble()

            if (band == WifiBand.BAND_5_GHZ) {
                if (cand.isTdwr) {
                    merit -= 15.0
                } else if (cand.isDfs) {
                    merit -= 8.0
                }
                // Prefer wider clean channels (80 MHz > 40 MHz > 20 MHz) when block is clean (>= 70)
                if (eval.score0To100 >= 70) {
                    when (cand.width) {
                        ChannelWidth.WIDTH_80_MHZ -> merit += 3.0
                        ChannelWidth.WIDTH_40_MHZ -> merit += 1.5
                        else -> Unit
                    }
                } else {
                    // When heavily congested (< 70), prefer narrower width that dodges ACI
                    when (cand.width) {
                        ChannelWidth.WIDTH_80_MHZ -> merit -= 4.0
                        ChannelWidth.WIDTH_40_MHZ -> merit -= 2.0
                        else -> Unit
                    }
                }
            } else if (band == WifiBand.BAND_6_GHZ) {
                if (cand.isPsc) merit += 10.0
                if (eval.score0To100 >= 70 && cand.width == ChannelWidth.WIDTH_80_MHZ) {
                    merit += 3.0
                }
                val halfSpan = cand.width.primarySpanMhz / 2
                val blockL = cand.centerFreqMhz - halfSpan
                val blockR = cand.centerFreqMhz + halfSpan
                val overlappingRnrCount = colocated6GhzFromRnr.count { nb ->
                    val nbHalf = nb.widthMhz.coerceAtLeast(20) / 2
                    val nbCenter = nb.centerFreqMhz.takeIf { it > 0 }
                        ?: ChannelAndFrequencyResolver.channelToFrequencyMhz(WifiBand.BAND_6_GHZ, nb.channelNumber)
                    nbCenter > 0 && max(blockL, nbCenter - nbHalf) < min(blockR, nbCenter + nbHalf)
                }
                merit -= overlappingRnrCount * 5.0
            }

            BlockEvaluation(
                candidate = cand,
                score0To100 = eval.score0To100,
                starRating0To5 = eval.starRating0To5,
                noiseFloorDbm = eval.noiseFloorDbm,
                cciCount = eval.cciCount,
                aciCount = eval.aciCount,
                merit = merit,
            )
        }

        val bestEval = evaluations.minWith(
            compareByDescending<BlockEvaluation> { it.merit }
                .thenByDescending { it.score0To100 }
                .thenBy { it.noiseFloorDbm }
                .thenBy { it.aciCount }
                .thenBy { it.cciCount }
                .thenByDescending { it.candidate.width.widthMhz }
                .thenBy { it.candidate.primaryChannel }
        )

        var chosen = bestEval
        val currentConnectedCh = connectedInBand?.channelDescriptor?.primaryChannel?.takeIf { it > 0 }
        var currentConnectedScore: Int? = null
        var currentCompCount = 0

        if (currentConnectedCh != null) {
            val curEval = evaluateChannelMask(
                band = band,
                primaryChannel = currentConnectedCh,
                primaryFreqMhz = connectedInBand.channelDescriptor.primaryFreqMhz,
                centerFreqMhz = connectedInBand.channelDescriptor.centerFreq0Mhz.takeIf { it > 0 }
                    ?: connectedInBand.channelDescriptor.primaryFreqMhz,
                width = connectedInBand.channelDescriptor.width,
                activeRadios = activeRadios,
                excludeBssid = connectedInBand.bssid,
                anchorForRelationship = connectedInBand,
            )
            currentConnectedScore = curEval.score0To100
            currentCompCount = curEval.cciCount + curEval.aciCount

            val matchingConnectedCandidate = evaluations
                .filter { it.candidate.primaryChannel == currentConnectedCh }
                .maxByOrNull { it.merit }

            if (matchingConnectedCandidate != null &&
                (includeDfs || !matchingConnectedCandidate.candidate.isDfs) &&
                matchingConnectedCandidate.score0To100 + 2 >= bestEval.score0To100 &&
                matchingConnectedCandidate.merit + 2.5 >= bestEval.merit
            ) {
                chosen = matchingConnectedCandidate
            }
        }

        val isAlreadyOptimal = currentConnectedCh != null &&
            chosen.candidate.primaryChannel == currentConnectedCh
        val scoreGain = if (currentConnectedScore != null) {
            (chosen.score0To100 - currentConnectedScore).coerceAtLeast(0)
        } else {
            null
        }

        val reason = formatOneSentenceReason(
            band = band,
            chosen = chosen,
            activeRadios = activeRadios,
            connectedInBand = connectedInBand,
            currentConnectedCh = currentConnectedCh,
            currentConnectedScore = currentConnectedScore,
            currentCompCount = currentCompCount,
            isAlreadyOptimal = isAlreadyOptimal,
            channelScores = channelScores,
            colocated6GhzFromRnr = colocated6GhzFromRnr,
            radioCapabilities = radioCapabilities,
        )

        return ChannelRecommendation(
            band = band,
            recommendedPrimaryChannel = chosen.candidate.primaryChannel,
            recommendedCenterFreqMhz = chosen.candidate.centerFreqMhz,
            recommendedWidth = chosen.candidate.width,
            score0To100 = chosen.score0To100,
            starRating0To5 = chosen.starRating0To5,
            currentConnectedChannel = currentConnectedCh,
            currentConnectedScore0To100 = currentConnectedScore,
            estimatedScoreGain = scoreGain,
            isCurrentChannelAlreadyOptimal = isAlreadyOptimal,
            plainEnglishReason = reason,
        )
    }

    private fun formatOneSentenceReason(
        band: WifiBand,
        chosen: BlockEvaluation,
        activeRadios: List<BssidRadio>,
        connectedInBand: BssidRadio?,
        currentConnectedCh: Int?,
        currentConnectedScore: Int?,
        currentCompCount: Int,
        isAlreadyOptimal: Boolean,
        channelScores: List<ChannelScore>,
        colocated6GhzFromRnr: List<Colocated6GhzNeighbor>,
        radioCapabilities: DeviceRadioCapabilities,
    ): String {
        val recCh = chosen.candidate.primaryChannel
        val recWidth = chosen.candidate.width.displayLabel
        val recScore = chosen.score0To100
        val recComp = chosen.cciCount + chosen.aciCount
        val dfsPrefix = if (chosen.candidate.isDfs) "DFS " else ""

        if (band == WifiBand.BAND_6_GHZ && !radioCapabilities.has6Ghz && activeRadios.isEmpty()) {
            return if (colocated6GhzFromRnr.isNotEmpty()) {
                val n = colocated6GhzFromRnr.size
                val rNoun = if (n == 1) "radio" else "radios"
                "Preferred Scanning Channel $recCh ($recWidth) avoids $n beacon-advertised 6 GHz neighbor $rNoun ($recScore/100), though this phone connects on 2.4 and 5 GHz."
            } else {
                "Preferred Scanning Channel $recCh ($recWidth) has 0 detected 6 GHz networks ($recScore/100), though this phone's hardware radio operates on 2.4 and 5 GHz."
            }
        }

        val nonSelfRadiosCount = if (connectedInBand != null) {
            activeRadios.count { it.bssid != connectedInBand.bssid }
        } else {
            activeRadios.size
        }

        if (nonSelfRadiosCount == 0 && connectedInBand == null) {
            return "All ${band.displayLabel} channels are wide open with 0 competing networks — ${dfsPrefix}Channel $recCh ($recWidth) delivers maximum interference-free speed."
        }

        if (isAlreadyOptimal && currentConnectedCh != null) {
            return "Keep Channel $recCh ($recWidth) — your current channel already leads ${band.displayLabel} at $recScore/100 with $recComp competing ${routerNoun(recComp)}."
        }

        if (connectedInBand != null && currentConnectedCh != null && currentConnectedScore != null) {
            return "Switch your router to ${dfsPrefix}Channel $recCh ($recWidth) — it has $recComp competing ${routerNoun(recComp)} ($recScore/100), whereas your current Channel $currentConnectedCh shares spectrum with $currentCompCount nearby ${routerNoun(currentCompCount)} ($currentConnectedScore/100)."
        }

        val busiest = channelScores
            .filter { it.channel != recCh }
            .minWithOrNull(
                compareBy<ChannelScore> { it.score0To100 }
                    .thenByDescending { it.aciCount + it.cciCount }
                    .thenBy { it.channel }
            )

        return if (busiest != null && (busiest.cciCount + busiest.aciCount) > recComp) {
            val bComp = busiest.cciCount + busiest.aciCount
            "Select ${dfsPrefix}Channel $recCh ($recWidth) — it has $recComp competing ${routerNoun(recComp)} ($recScore/100), compared with $bComp nearby ${routerNoun(bComp)} on Channel ${busiest.channel} (${busiest.score0To100}/100)."
        } else {
            "Select ${dfsPrefix}Channel $recCh ($recWidth) — it offers the cleanest ${band.displayLabel} airtime at $recScore/100 with $recComp competing ${routerNoun(recComp)}."
        }
    }

    private fun generateRecommendationCandidates(
        band: WifiBand,
        includeDfs: Boolean,
    ): List<CandidateBlock> {
        val out = ArrayList<CandidateBlock>()
        when (band) {
            WifiBand.BAND_2_4_GHZ -> {
                for (ch in listOf(1, 6, 11)) {
                    val freq = ChannelAndFrequencyResolver.channelToFrequencyMhz(WifiBand.BAND_2_4_GHZ, ch)
                    out.add(
                        CandidateBlock(
                            primaryChannel = ch,
                            primaryFreqMhz = freq,
                            centerFreqMhz = freq,
                            width = ChannelWidth.WIDTH_20_MHZ,
                            isDfs = false,
                            isTdwr = false,
                            isPsc = false,
                        )
                    )
                }
            }

            WifiBand.BAND_5_GHZ -> {
                val primaryPool = if (includeDfs) {
                    NON_DFS_5_GHZ_CHANNELS + DFS_5_GHZ_CHANNELS
                } else {
                    NON_DFS_5_GHZ_CHANNELS
                }
                for (ch in primaryPool) {
                    val primFreq = ChannelAndFrequencyResolver.channelToFrequencyMhz(WifiBand.BAND_5_GHZ, ch)
                    val isDfs = ch in 52..64 || ch in 100..144
                    val isTdwr = ch in setOf(120, 124, 128)

                    // Always include 20 MHz candidate
                    out.add(
                        CandidateBlock(
                            primaryChannel = ch,
                            primaryFreqMhz = primFreq,
                            centerFreqMhz = primFreq,
                            width = ChannelWidth.WIDTH_20_MHZ,
                            isDfs = isDfs,
                            isTdwr = isTdwr,
                            isPsc = false,
                        )
                    )

                    // Include 40 MHz & 80 MHz candidates for bonded blocks (Ch 165 is standalone 20 MHz in non-DFS)
                    if (ch != 165) {
                        val center40 = ChannelAndFrequencyResolver.inferStandardCenterFreq0Mhz(
                            WifiBand.BAND_5_GHZ,
                            primFreq,
                            ch,
                            ChannelWidth.WIDTH_40_MHZ,
                        )
                        val tdwr40 = isTdwr || ch == 116
                        out.add(
                            CandidateBlock(
                                primaryChannel = ch,
                                primaryFreqMhz = primFreq,
                                centerFreqMhz = center40,
                                width = ChannelWidth.WIDTH_40_MHZ,
                                isDfs = isDfs,
                                isTdwr = tdwr40,
                                isPsc = false,
                            )
                        )

                        val center80 = ChannelAndFrequencyResolver.inferStandardCenterFreq0Mhz(
                            WifiBand.BAND_5_GHZ,
                            primFreq,
                            ch,
                            ChannelWidth.WIDTH_80_MHZ,
                        )
                        val tdwr80 = isTdwr || ch == 116
                        out.add(
                            CandidateBlock(
                                primaryChannel = ch,
                                primaryFreqMhz = primFreq,
                                centerFreqMhz = center80,
                                width = ChannelWidth.WIDTH_80_MHZ,
                                isDfs = isDfs,
                                isTdwr = tdwr80,
                                isPsc = false,
                            )
                        )
                    }
                }
            }

            WifiBand.BAND_6_GHZ -> {
                for (ch in PSC_6_GHZ_CHANNELS) {
                    val primFreq = ChannelAndFrequencyResolver.channelToFrequencyMhz(WifiBand.BAND_6_GHZ, ch)
                    out.add(
                        CandidateBlock(
                            primaryChannel = ch,
                            primaryFreqMhz = primFreq,
                            centerFreqMhz = primFreq,
                            width = ChannelWidth.WIDTH_20_MHZ,
                            isDfs = false,
                            isTdwr = false,
                            isPsc = true,
                        )
                    )
                    if (ch < 229) {
                        val center80 = ChannelAndFrequencyResolver.inferStandardCenterFreq0Mhz(
                            WifiBand.BAND_6_GHZ,
                            primFreq,
                            ch,
                            ChannelWidth.WIDTH_80_MHZ,
                        )
                        out.add(
                            CandidateBlock(
                                primaryChannel = ch,
                                primaryFreqMhz = primFreq,
                                centerFreqMhz = center80,
                                width = ChannelWidth.WIDTH_80_MHZ,
                                isDfs = false,
                                isTdwr = false,
                                isPsc = true,
                            )
                        )
                    } else {
                        out.add(
                            CandidateBlock(
                                primaryChannel = ch,
                                primaryFreqMhz = primFreq,
                                centerFreqMhz = 7085,
                                width = ChannelWidth.WIDTH_40_MHZ,
                                isDfs = false,
                                isTdwr = false,
                                isPsc = true,
                            )
                        )
                    }
                }
            }

            WifiBand.BAND_60_GHZ -> {
                for (ch in WIGIG_60_GHZ_CHANNELS) {
                    val freq = ChannelAndFrequencyResolver.channelToFrequencyMhz(WifiBand.BAND_60_GHZ, ch)
                    out.add(
                        CandidateBlock(
                            primaryChannel = ch,
                            primaryFreqMhz = freq,
                            centerFreqMhz = freq,
                            width = ChannelWidth.WIDTH_20_MHZ,
                            isDfs = false,
                            isTdwr = false,
                            isPsc = false,
                        )
                    )
                }
            }

            WifiBand.UNKNOWN -> Unit
        }
        return out
    }

    private fun determineScoredChannelList(
        band: WifiBand,
        includeDfs: Boolean,
        inBandRadios: List<BssidRadio>,
    ): List<Int> = when (band) {
        WifiBand.BAND_2_4_GHZ -> {
            if (inBandRadios.any { it.channelDescriptor.primaryChannel == 14 }) {
                CHANNELS_24_GHZ_BASE + 14
            } else {
                CHANNELS_24_GHZ_BASE
            }
        }
        WifiBand.BAND_5_GHZ -> {
            val base = if (includeDfs) {
                (NON_DFS_5_GHZ_CHANNELS + DFS_5_GHZ_CHANNELS).sorted()
            } else {
                NON_DFS_5_GHZ_CHANNELS
            }
            val detectedExtra = inBandRadios
                .map { it.channelDescriptor.primaryChannel }
                .filter { it > 0 && (includeDfs || (it !in 52..64 && it !in 100..144)) }
            (base + detectedExtra).distinct().sorted()
        }
        WifiBand.BAND_6_GHZ -> {
            val detected = inBandRadios.map { it.channelDescriptor.primaryChannel }.filter { it > 0 }
            (PSC_6_GHZ_CHANNELS + detected).distinct().sorted()
        }
        WifiBand.BAND_60_GHZ -> WIGIG_60_GHZ_CHANNELS
        WifiBand.UNKNOWN -> emptyList()
    }

    private fun buildSpectralCurve(
        radio: BssidRadio,
        anchorRadio: BssidRadio?,
    ): SpectralCurve {
        val desc = radio.channelDescriptor
        val rssiClamped = radio.rssiDbm.coerceIn(-120, 0)
        val linearMw = 10.0.pow(rssiClamped.toDouble() / 10.0).finiteOr(0.0)
        val punctBitmap = radio.beaconDetails.ehtMlo
            ?.takeIf { it.isPreamblePunctured }
            ?.disabledSubchannelBitmap
            ?.takeIf { it > 0 }
        val colorCode = radio.beaconDetails.bssColor
            ?.takeIf { !it.isDisabled && it.colorCode in 1..63 }
            ?.colorCode

        return SpectralCurve(
            bssid = radio.bssid,
            ssid = radio.ssid.displayName,
            displayLabel = radio.effectiveDisplayName,
            band = desc.band,
            primaryChannel = desc.primaryChannel,
            primaryFreqMhz = desc.primaryFreqMhz,
            centerFreq0Mhz = desc.centerFreq0Mhz,
            centerFreq1Mhz = desc.centerFreq1Mhz,
            width = desc.width,
            rssiDbm = radio.rssiDbm,
            linearPowerMw = linearMw,
            lobeStartFreqMhz = desc.occupiedStartFreqMhz,
            lobeEndFreqMhz = desc.occupiedEndFreqMhz,
            secondaryLobeStartFreqMhz = desc.secondaryOccupiedStartFreqMhz,
            secondaryLobeEndFreqMhz = desc.secondaryOccupiedEndFreqMhz,
            puncturedSubchannelBitmap = punctBitmap,
            isConnected = radio.isConnected,
            isOwnMeshSibling = anchorRadio != null && isSiblingOfRadio(radio, anchorRadio),
            utilizationPct = radio.beaconDetails.bssLoad?.channelUtilizationPct,
            bssColor = colorCode,
        )
    }

    private fun capDenseCurves(allCurves: List<SpectralCurve>): Pair<List<SpectralCurve>, Int> {
        if (allCurves.size <= MAX_RENDERED_CURVES_DEFAULT) {
            return allCurves to 0
        }
        val strongCount = allCurves.count { it.rssiDbm >= -85 || it.isConnected || it.isOwnMeshSibling }
        val targetCap = max(MAX_RENDERED_CURVES_DEFAULT, strongCount).coerceAtMost(MAX_RENDERED_CURVES_HARD_CAP)
        if (allCurves.size <= targetCap) {
            return allCurves to 0
        }
        val retained = ArrayList<SpectralCurve>(targetCap)
        for (c in allCurves) {
            if (c.isConnected || c.isOwnMeshSibling) {
                retained.add(c)
            }
        }
        for (c in allCurves) {
            if (retained.size >= targetCap) break
            if (!c.isConnected && !c.isOwnMeshSibling) {
                retained.add(c)
            }
        }
        return retained to (allCurves.size - retained.size).coerceAtLeast(0)
    }

    private fun buildHardwareExplanationMessage(
        band: WifiBand,
        isSupported: Boolean,
        radioCapabilities: DeviceRadioCapabilities,
        rnrCount: Int,
    ): String? {
        if (isSupported) return null
        if (band == WifiBand.BAND_6_GHZ) {
            return if (rnrCount > 0) {
                val noun = if (rnrCount == 1) "radio was" else "radios were"
                "Phone radio (${radioCapabilities.deviceModel}) supports 2.4 & 5 GHz Wi-Fi 6; direct 6 GHz scanning is unavailable on this hardware, but $rnrCount co-located 6 GHz neighbor $noun discovered via 2.4/5 GHz beacon reports (RNR)."
            } else {
                "Phone radio (${radioCapabilities.deviceModel}) supports 2.4 & 5 GHz Wi-Fi 6; 6 GHz Wi-Fi 6E/7 channels cannot be tuned directly by this hardware, though 6 GHz neighbor links advertised in 2.4/5 GHz beacons are still inspected."
            }
        }
        return "Phone radio (${radioCapabilities.deviceModel}) does not include a hardware transceiver for ${band.displayLabel}."
    }

    internal fun isSiblingOfRadio(candidate: BssidRadio, anchor: BssidRadio): Boolean {
        if (candidate.bssid == anchor.bssid) return false
        if (candidate.physicalClusterId.isNotEmpty() &&
            candidate.physicalClusterId == anchor.physicalClusterId
        ) {
            return true
        }
        if (!anchor.apMldMacAddress.isNullOrBlank() &&
            anchor.apMldMacAddress == candidate.apMldMacAddress
        ) {
            return true
        }
        val anchorVisible = !anchor.ssid.isHidden || anchor.ssid.isRevealedFromConnection
        val candVisible = !candidate.ssid.isHidden || candidate.ssid.isRevealedFromConnection
        return anchorVisible &&
            candVisible &&
            candidate.ssid.displayName == anchor.ssid.displayName &&
            candidate.security.securityFamily == anchor.security.securityFamily
    }

    private fun routerNoun(count: Int): String = if (count == 1) "router" else "routers"
}
