package com.a55.wifiradar.feature.spectrum

import androidx.compose.runtime.Immutable
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.CciAciBreakdown
import com.a55.wifiradar.core.model.ChannelInterferer
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ChannelScore
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.InterferenceType
import com.a55.wifiradar.core.model.InterfererRelationship
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

val SPECTRUM_SELECTABLE_BANDS: List<WifiBand> = listOf(
    WifiBand.BAND_2_4_GHZ,
    WifiBand.BAND_5_GHZ,
    WifiBand.BAND_6_GHZ,
)

@Immutable
data class SpectrumSubBandBackdrop(
    val shortLabel: String,
    val startFreqMhz: Int,
    val endFreqMhz: Int,
    val tone: SemanticTone,
    val isDfs: Boolean = false,
    val isTdwr: Boolean = false,
)

@Immutable
data class SpectrumChannelTick(
    val channel: Int,
    val freqMhz: Int,
    val isCanonicalOrPsc: Boolean,
    val showAxisLabel: Boolean = true,
)

@Immutable
data class SpectrumBandAxisSpec(
    val band: WifiBand,
    val canonicalMinFreqMhz: Int,
    val canonicalMaxFreqMhz: Int,
    val backdrops: List<SpectrumSubBandBackdrop>,
    val ticks: List<SpectrumChannelTick>,
)

@Immutable
data class SpectrumUiState(
    val selectedBand: WifiBand = WifiBand.BAND_2_4_GHZ,
    val includeDfs: Boolean = false,
    val excludeConnectedSsid: Boolean = false,
    val selectedBssid: String? = null,
    val highlightedChannel: Int? = null,
    val readinessState: ScannerReadinessState = ScannerReadinessState.Ready(),
    val radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    val scanTelemetry: ScanTelemetry = ScanTelemetry(),
    val totalNetworkCount: Int = 0,
    val connectedSsid: String? = null,
    val connectedBssid: String? = null,
    val bandNetworkCounts: Map<WifiBand, Int> = emptyMap(),
    val minFreqMhz: Int = 2400,
    val maxFreqMhz: Int = 2495,
    val curves: List<SpectralCurve> = emptyList(),
    val aggregatedWeakCurveCount: Int = 0,
    val selectedCurve: SpectralCurve? = null,
    val recommendation: ChannelRecommendation? = null,
    val channelScores: List<ChannelScore> = emptyList(),
    val activeBreakdown: CciAciBreakdown? = null,
    val activeBreakdownTargetLabel: String = "Channel 1 (2.4 GHz)",
    val show6GhzHardwareBanner: Boolean = false,
    val hardwareExplanationHeadline: String = "6 GHz Hardware Radio Not Present on This Phone",
    val hardwareExplanationBody: String? = null,
    val colocated6GhzNeighbors: List<Colocated6GhzNeighbor> = emptyList(),
    val scanActionFeedback: String? = null,
)

internal object SpectrumDomainResolver {

    private val SCREAMING_SNAKE_REGEX = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")

    private val SPEC_2_4_GHZ = SpectrumBandAxisSpec(
        band = WifiBand.BAND_2_4_GHZ,
        canonicalMinFreqMhz = 2400,
        canonicalMaxFreqMhz = 2495,
        backdrops = listOf(
            SpectrumSubBandBackdrop(
                shortLabel = "2.4 GHz ISM",
                startFreqMhz = 2401,
                endFreqMhz = 2483,
                tone = SemanticTone.GOOD,
            ),
        ),
        ticks = (1..13).map { ch ->
            val isCanonical = ch == 1 || ch == 6 || ch == 11
            SpectrumChannelTick(
                channel = ch,
                freqMhz = 2407 + ch * 5,
                isCanonicalOrPsc = isCanonical,
                showAxisLabel = isCanonical || ch == 13,
            )
        } + SpectrumChannelTick(
            channel = 14,
            freqMhz = 2484,
            isCanonicalOrPsc = false,
            showAxisLabel = false,
        ),
    )

    private val SPEC_5_GHZ = SpectrumBandAxisSpec(
        band = WifiBand.BAND_5_GHZ,
        canonicalMinFreqMhz = 5150,
        canonicalMaxFreqMhz = 5895,
        backdrops = listOf(
            SpectrumSubBandBackdrop("U-NII-1", 5170, 5250, SemanticTone.GOOD),
            SpectrumSubBandBackdrop("U-NII-2A DFS", 5250, 5330, SemanticTone.FAIR, isDfs = true),
            SpectrumSubBandBackdrop("U-NII-2C DFS", 5490, 5730, SemanticTone.FAIR, isDfs = true),
            SpectrumSubBandBackdrop("TDWR", 5600, 5650, SemanticTone.WEAK, isDfs = true, isTdwr = true),
            SpectrumSubBandBackdrop("U-NII-3", 5735, 5835, SemanticTone.EXCELLENT),
            SpectrumSubBandBackdrop("U-NII-4", 5835, 5895, SemanticTone.SPECTRUM_6GHZ),
        ),
        ticks = listOf(36, 48, 52, 64, 100, 120, 140, 149, 165, 177).map { ch ->
            SpectrumChannelTick(
                channel = ch,
                freqMhz = 5000 + ch * 5,
                isCanonicalOrPsc = ch in setOf(36, 52, 100, 149),
                showAxisLabel = ch in setOf(36, 52, 100, 120, 149, 165),
            )
        },
    )

    private val SPEC_6_GHZ = SpectrumBandAxisSpec(
        band = WifiBand.BAND_6_GHZ,
        canonicalMinFreqMhz = 5925,
        canonicalMaxFreqMhz = 7125,
        backdrops = listOf(
            SpectrumSubBandBackdrop("U-NII-5", 5925, 6425, SemanticTone.SPECTRUM_6GHZ),
            SpectrumSubBandBackdrop("U-NII-6", 6425, 6525, SemanticTone.GOOD),
            SpectrumSubBandBackdrop("U-NII-7", 6525, 6875, SemanticTone.SPECTRUM_6GHZ),
            SpectrumSubBandBackdrop("U-NII-8", 6875, 7125, SemanticTone.GOOD),
        ),
        ticks = (0..14).map { idx ->
            val pscChannel = 5 + idx * 16
            SpectrumChannelTick(
                channel = pscChannel,
                freqMhz = 5950 + pscChannel * 5,
                isCanonicalOrPsc = true,
                showAxisLabel = idx % 2 == 0,
            )
        },
    )

    fun axisSpecFor(band: WifiBand): SpectrumBandAxisSpec = when (band) {
        WifiBand.BAND_2_4_GHZ -> SPEC_2_4_GHZ
        WifiBand.BAND_5_GHZ -> SPEC_5_GHZ
        WifiBand.BAND_6_GHZ -> SPEC_6_GHZ
        else -> SPEC_2_4_GHZ
    }

    fun is6GhzPscChannel(channel: Int): Boolean =
        channel in 5..229 && (channel - 5) % 16 == 0

    fun sanitizeDisplayToken(raw: String): String =
        raw.replace('_', '-').trim()

    fun isLeakFree(text: String): Boolean =
        !SemanticFormatters.containsRawEnumLeak(text) && !SCREAMING_SNAKE_REGEX.containsMatchIn(text)

    fun sanitizeUiSentence(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        if (isLeakFree(raw)) return raw
        val cleaned = SCREAMING_SNAKE_REGEX.replace(raw) { match ->
            match.value.replace('_', '-')
        }
        return if (!SemanticFormatters.containsRawEnumLeak(cleaned)) cleaned else fallback
    }

    fun safeRssiDbm(rssiDbm: Int): Int = rssiDbm.coerceIn(-120, 0)

    fun safeCanvasRssiDbm(rssiDbm: Int): Int = rssiDbm.coerceIn(-100, -20)

    fun safeRoundToInt(value: Double, defaultValue: Int = 0): Int =
        if (value.isFinite()) value.roundToInt() else defaultValue

    fun buildUiState(
        selectedBand: WifiBand,
        includeDfs: Boolean,
        excludeConnectedSsid: Boolean,
        selectedBssid: String?,
        highlightedChannel: Int?,
        snapshot: RadarSnapshot,
        readinessState: ScannerReadinessState,
        analysis: SpectrumAnalysis?,
        scanActionFeedback: String?,
    ): SpectrumUiState {
        val axisSpec = axisSpecFor(selectedBand)
        val rawMinFreq = analysis?.minFreqMhz ?: axisSpec.canonicalMinFreqMhz
        val rawMaxFreq = analysis?.maxFreqMhz ?: axisSpec.canonicalMaxFreqMhz
        val hasValidFreqSpan = rawMaxFreq - rawMinFreq >= 10 && rawMinFreq > 0 && rawMaxFreq > 0
        val effectiveMinFreq = if (hasValidFreqSpan) rawMinFreq else axisSpec.canonicalMinFreqMhz
        val effectiveMaxFreq = if (hasValidFreqSpan) rawMaxFreq else axisSpec.canonicalMaxFreqMhz

        val curves = analysis?.curves.orEmpty()
        val normSelectedBssid = selectedBssid?.let(MacAddressUtils::normalizeBssid)?.ifEmpty { null }
        val selectedCurve = normSelectedBssid?.let { target ->
            curves.firstOrNull { MacAddressUtils.normalizeBssid(it.bssid) == target }
        }

        val connectedRadio = snapshot.radios.firstOrNull { it.isConnected }
        val connectedBssid = snapshot.connectedWifi?.bssid?.takeIf { it.isNotBlank() }
            ?: connectedRadio?.bssid
        val connectedSsid = snapshot.connectedWifi?.ssid?.takeIf {
            it.isNotBlank() && it != MacAddressUtils.UNKNOWN_SSID_SENTINEL
        } ?: connectedRadio?.effectiveDisplayName

        val bandCounts = SPECTRUM_SELECTABLE_BANDS.associateWith { band ->
            val snapshotCount = snapshot.radios.count { it.channelDescriptor.band == band }
            if (band == selectedBand && curves.isNotEmpty()) {
                max(snapshotCount, curves.size)
            } else {
                snapshotCount
            }
        }

        val mergedRnr = mergeColocated6GhzNeighbors(analysis, snapshot)
        val show6GhzBanner = selectedBand == WifiBand.BAND_6_GHZ &&
            (!snapshot.radioCapabilities.has6Ghz || (analysis != null && !analysis.isBandSupportedByHardware))

        val safeDeviceModel = sanitizeDisplayToken(snapshot.radioCapabilities.deviceModel)
            .ifBlank { "Galaxy A55 5G" }
        val defaultHardwareBody =
            "Your phone ($safeDeviceModel) uses a dual-band 2.4 & 5 GHz Wi-Fi 6 radio and cannot directly scan 6 GHz frequencies. Co-located 6 GHz Wi-Fi 6E and Wi-Fi 7 neighbor radios advertised inside 2.4 & 5 GHz router beacons (Reduced Neighbor Report) are automatically decoded below."
        val hardwareBody = if (show6GhzBanner) {
            sanitizeUiSentence(analysis?.hardwareExplanationMessage, defaultHardwareBody)
        } else {
            null
        }

        val (activeBreakdown, targetLabel) = resolveActiveBreakdown(
            selectedBand = selectedBand,
            selectedCurve = selectedCurve,
            highlightedChannel = highlightedChannel,
            curves = curves,
            analysis = analysis,
        )

        return SpectrumUiState(
            selectedBand = selectedBand,
            includeDfs = includeDfs,
            excludeConnectedSsid = excludeConnectedSsid,
            selectedBssid = selectedCurve?.let { MacAddressUtils.normalizeBssid(it.bssid) } ?: normSelectedBssid,
            highlightedChannel = highlightedChannel,
            readinessState = readinessState,
            radioCapabilities = snapshot.radioCapabilities,
            scanTelemetry = snapshot.scanTelemetry,
            totalNetworkCount = max(snapshot.radios.size, curves.size),
            connectedSsid = connectedSsid,
            connectedBssid = connectedBssid?.let(MacAddressUtils::normalizeBssid),
            bandNetworkCounts = bandCounts,
            minFreqMhz = effectiveMinFreq,
            maxFreqMhz = effectiveMaxFreq,
            curves = curves,
            aggregatedWeakCurveCount = (analysis?.aggregatedWeakCurveCount ?: 0).coerceAtLeast(0),
            selectedCurve = selectedCurve,
            recommendation = analysis?.recommendation,
            channelScores = analysis?.channelScores.orEmpty(),
            activeBreakdown = activeBreakdown,
            activeBreakdownTargetLabel = targetLabel,
            show6GhzHardwareBanner = show6GhzBanner,
            hardwareExplanationHeadline = "6 GHz Hardware Radio Not Present on This Phone",
            hardwareExplanationBody = hardwareBody,
            colocated6GhzNeighbors = mergedRnr,
            scanActionFeedback = scanActionFeedback,
        )
    }

    private fun mergeColocated6GhzNeighbors(
        analysis: SpectrumAnalysis?,
        snapshot: RadarSnapshot,
    ): List<Colocated6GhzNeighbor> {
        val combined = buildList {
            if (analysis != null) {
                addAll(analysis.colocated6GhzNeighborsFromRnr)
            }
            snapshot.radios.forEach { radio ->
                addAll(radio.beaconDetails.colocated6GhzNeighbors)
            }
            snapshot.physicalClusters.forEach { cluster ->
                addAll(cluster.colocated6GhzNeighbors)
            }
        }
        return combined.distinctBy { neighbor ->
            val src = MacAddressUtils.normalizeBssid(neighbor.sourceBssid)
            val nbr = neighbor.neighborBssid?.let(MacAddressUtils::normalizeBssid).orEmpty()
            "$src|${neighbor.channelNumber}|${neighbor.operatingClass}|$nbr"
        }.sortedWith(
            compareByDescending<Colocated6GhzNeighbor> { it.isPscChannel || is6GhzPscChannel(it.channelNumber) }
                .thenBy { it.channelNumber }
                .thenBy { it.sourceSsid }
        )
    }

    private fun resolveActiveBreakdown(
        selectedBand: WifiBand,
        selectedCurve: SpectralCurve?,
        highlightedChannel: Int?,
        curves: List<SpectralCurve>,
        analysis: SpectrumAnalysis?,
    ): Pair<CciAciBreakdown?, String> {
        if (selectedCurve != null) {
            val normTarget = MacAddressUtils.normalizeBssid(selectedCurve.bssid)
            val label = "${selectedCurve.displayLabel} · Ch ${selectedCurve.primaryChannel} (${selectedBand.displayLabel})"
            val directMatch = analysis?.selectedOrConnectedBreakdown?.takeIf { bd ->
                val tb = bd.targetBssid
                tb != null && MacAddressUtils.normalizeBssid(tb) == normTarget
            }
            if (directMatch != null) {
                return stripSelfFromBreakdown(directMatch, normTarget) to label
            }
            val channelMatch = analysis?.perChannelBreakdowns?.get(selectedCurve.primaryChannel)
            if (channelMatch != null) {
                return stripSelfFromBreakdown(channelMatch.copy(targetBssid = normTarget), normTarget) to label
            }
            return deriveBreakdownFromCurves(
                selectedBand = selectedBand,
                targetChannel = selectedCurve.primaryChannel,
                targetCurve = selectedCurve,
                curves = curves,
            ) to label
        }

        if (highlightedChannel != null) {
            val label = "Channel $highlightedChannel (${selectedBand.displayLabel})"
            val channelBreakdown = analysis?.perChannelBreakdowns?.get(highlightedChannel)
                ?: analysis?.selectedOrConnectedBreakdown?.takeIf { it.targetChannel == highlightedChannel }
                ?: deriveBreakdownFromCurves(
                    selectedBand = selectedBand,
                    targetChannel = highlightedChannel,
                    targetCurve = null,
                    curves = curves,
                )
            return channelBreakdown to label
        }

        val connectedCurve = curves.firstOrNull { it.isConnected }
        val defaultBreakdown = analysis?.selectedOrConnectedBreakdown
        if (defaultBreakdown != null) {
            val cleaned = defaultBreakdown.targetBssid?.let {
                stripSelfFromBreakdown(defaultBreakdown, MacAddressUtils.normalizeBssid(it))
            } ?: defaultBreakdown
            val targetName = connectedCurve?.takeIf { it.primaryChannel == cleaned.targetChannel }?.displayLabel
            val label = if (targetName != null) {
                "$targetName · Ch ${cleaned.targetChannel} (${selectedBand.displayLabel})"
            } else {
                "Channel ${cleaned.targetChannel} (${selectedBand.displayLabel})"
            }
            return cleaned to label
        }

        val recChannel = analysis?.recommendation?.recommendedPrimaryChannel
        if (recChannel != null) {
            val recBreakdown = analysis.perChannelBreakdowns[recChannel]
                ?: deriveBreakdownFromCurves(selectedBand, recChannel, null, curves)
            return recBreakdown to "Channel $recChannel (${selectedBand.displayLabel})"
        }

        val firstEntry = analysis?.perChannelBreakdowns?.entries?.firstOrNull()
        if (firstEntry != null) {
            return firstEntry.value to "Channel ${firstEntry.key} (${selectedBand.displayLabel})"
        }

        val defaultChannel = when (selectedBand) {
            WifiBand.BAND_2_4_GHZ -> 1
            WifiBand.BAND_5_GHZ -> 36
            WifiBand.BAND_6_GHZ -> 37
            else -> 1
        }
        val derived = if (curves.isNotEmpty()) {
            deriveBreakdownFromCurves(selectedBand, defaultChannel, null, curves)
        } else {
            null
        }
        return derived to "Channel $defaultChannel (${selectedBand.displayLabel})"
    }

    private fun stripSelfFromBreakdown(
        breakdown: CciAciBreakdown,
        normalizedTargetBssid: String,
    ): CciAciBreakdown {
        val hadSelfInCci = breakdown.cciInterferers.any {
            MacAddressUtils.normalizeBssid(it.bssid) == normalizedTargetBssid
        }
        val hadSelfInAci = breakdown.aciInterferers.any {
            MacAddressUtils.normalizeBssid(it.bssid) == normalizedTargetBssid
        }
        if (!hadSelfInCci && !hadSelfInAci) return breakdown

        val cci = breakdown.cciInterferers.filterNot {
            MacAddressUtils.normalizeBssid(it.bssid) == normalizedTargetBssid
        }
        val aci = breakdown.aciInterferers.filterNot {
            MacAddressUtils.normalizeBssid(it.bssid) == normalizedTargetBssid
        }
        val actionableCci = cci.count { it.isActionable }
        val actionableAci = aci.count { it.isActionable }
        val totalCciMw = cci.sumOf { if (it.weightedInterferenceMw.isFinite()) it.weightedInterferenceMw else 0.0 }
        val totalAciMw = aci.sumOf { if (it.weightedInterferenceMw.isFinite()) it.weightedInterferenceMw else 0.0 }
        val severeAci = aci.any { it.isActionable && it.rssiDbm >= -72 }

        return breakdown.copy(
            cciInterferers = cci,
            aciInterferers = aci,
            actionableCciCount = actionableCci,
            actionableAciCount = actionableAci,
            totalCciPowerMw = totalCciMw,
            totalAciPowerMw = totalAciMw,
            hasSevereAciWarning = severeAci,
            cciPlainEnglishSummary = buildCciSummary(cci.size, actionableCci, breakdown.targetChannel),
            aciPlainEnglishSummary = buildAciSummary(aci.size, actionableAci, severeAci, breakdown.targetChannel),
        )
    }

    private fun deriveBreakdownFromCurves(
        selectedBand: WifiBand,
        targetChannel: Int,
        targetCurve: SpectralCurve?,
        curves: List<SpectralCurve>,
    ): CciAciBreakdown {
        val normSelf = targetCurve?.bssid?.let(MacAddressUtils::normalizeBssid)
        val (targetStart, targetEnd) = if (targetCurve != null && targetCurve.lobeEndFreqMhz > targetCurve.lobeStartFreqMhz) {
            targetCurve.lobeStartFreqMhz to targetCurve.lobeEndFreqMhz
        } else {
            val centerFreq = channelToCenterFreqMhz(selectedBand, targetChannel)
            (centerFreq - 10) to (centerFreq + 10)
        }
        val targetSpan = (targetEnd - targetStart).coerceAtLeast(20).toDouble()

        val cciList = mutableListOf<ChannelInterferer>()
        val aciList = mutableListOf<ChannelInterferer>()

        for (other in curves) {
            val normOther = MacAddressUtils.normalizeBssid(other.bssid)
            if (normSelf != null && normOther == normSelf) continue

            val safeDbm = safeRssiDbm(other.rssiDbm)
            val powerMw = 10.0.pow(safeDbm / 10.0)
            val relationship = if (
                other.isOwnMeshSibling ||
                (targetCurve != null && targetCurve.ssid.isNotBlank() && other.ssid == targetCurve.ssid)
            ) {
                InterfererRelationship.OWN_MESH_SELF_CONTENTION
            } else {
                InterfererRelationship.NEIGHBOR_NETWORK
            }
            val actionable = safeDbm >= -80

            if (other.primaryChannel == targetChannel) {
                val utilBoost = 1.0 + ((other.utilizationPct ?: 0).coerceIn(0, 100) / 100.0)
                cciList += ChannelInterferer(
                    bssid = other.bssid,
                    ssid = other.ssid,
                    displayLabel = other.displayLabel,
                    rssiDbm = safeDbm,
                    primaryChannel = other.primaryChannel,
                    width = other.width,
                    overlapRatio0To1 = 1.0,
                    penaltyMultiplier = utilBoost,
                    weightedInterferenceMw = powerMw * utilBoost,
                    interferenceType = InterferenceType.CO_CHANNEL_CCI,
                    relationship = relationship,
                    isActionable = actionable,
                )
            } else {
                val overlapMhz = computeOverlapMhz(
                    targetStart = targetStart,
                    targetEnd = targetEnd,
                    targetSecStart = targetCurve?.secondaryLobeStartFreqMhz,
                    targetSecEnd = targetCurve?.secondaryLobeEndFreqMhz,
                    other = other,
                )
                if (overlapMhz > 0) {
                    val ratio = (overlapMhz / targetSpan).coerceIn(0.05, 1.0)
                    val penalty = 1.85
                    aciList += ChannelInterferer(
                        bssid = other.bssid,
                        ssid = other.ssid,
                        displayLabel = other.displayLabel,
                        rssiDbm = safeDbm,
                        primaryChannel = other.primaryChannel,
                        width = other.width,
                        overlapRatio0To1 = ratio,
                        penaltyMultiplier = penalty,
                        weightedInterferenceMw = powerMw * ratio * penalty,
                        interferenceType = InterferenceType.ADJACENT_CHANNEL_ACI,
                        relationship = relationship,
                        isActionable = actionable,
                    )
                }
            }
        }

        cciList.sortByDescending { it.rssiDbm }
        aciList.sortByDescending { it.rssiDbm }
        val actionableCci = cciList.count { it.isActionable }
        val actionableAci = aciList.count { it.isActionable }
        val severeAci = aciList.any { it.isActionable && it.rssiDbm >= -72 }

        return CciAciBreakdown(
            targetChannel = targetChannel,
            targetBand = selectedBand,
            targetBssid = normSelf,
            cciInterferers = cciList,
            aciInterferers = aciList,
            actionableCciCount = actionableCci,
            actionableAciCount = actionableAci,
            totalCciPowerMw = cciList.sumOf { it.weightedInterferenceMw },
            totalAciPowerMw = aciList.sumOf { it.weightedInterferenceMw },
            cciPlainEnglishSummary = buildCciSummary(cciList.size, actionableCci, targetChannel),
            aciPlainEnglishSummary = buildAciSummary(aciList.size, actionableAci, severeAci, targetChannel),
            hasSevereAciWarning = severeAci,
        )
    }

    private fun computeOverlapMhz(
        targetStart: Int,
        targetEnd: Int,
        targetSecStart: Int?,
        targetSecEnd: Int?,
        other: SpectralCurve,
    ): Int {
        val oStart = if (other.lobeEndFreqMhz > other.lobeStartFreqMhz) other.lobeStartFreqMhz else other.primaryFreqMhz - 10
        val oEnd = if (other.lobeEndFreqMhz > other.lobeStartFreqMhz) other.lobeEndFreqMhz else other.primaryFreqMhz + 10
        val otherSecStart = other.secondaryLobeStartFreqMhz
        val otherSecEnd = other.secondaryLobeEndFreqMhz
        var overlap = intervalOverlap(targetStart, targetEnd, oStart, oEnd)
        if (otherSecStart != null && otherSecEnd != null) {
            overlap += intervalOverlap(targetStart, targetEnd, otherSecStart, otherSecEnd)
        }
        if (targetSecStart != null && targetSecEnd != null && targetSecEnd > targetSecStart) {
            overlap += intervalOverlap(targetSecStart, targetSecEnd, oStart, oEnd)
            if (otherSecStart != null && otherSecEnd != null) {
                overlap += intervalOverlap(targetSecStart, targetSecEnd, otherSecStart, otherSecEnd)
            }
        }
        return overlap
    }

    private fun intervalOverlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        (min(aEnd, bEnd) - max(aStart, bStart)).coerceAtLeast(0)

    private fun channelToCenterFreqMhz(band: WifiBand, channel: Int): Int = when (band) {
        WifiBand.BAND_2_4_GHZ -> if (channel == 14) 2484 else 2407 + channel * 5
        WifiBand.BAND_5_GHZ -> 5000 + channel * 5
        WifiBand.BAND_6_GHZ -> 5950 + channel * 5
        else -> 2412
    }

    private fun buildCciSummary(total: Int, actionable: Int, channel: Int): String = when {
        total == 0 -> "No other routers share primary Channel $channel — zero turn-taking airtime delay."
        actionable == 0 -> "$total distant radio${if (total == 1) "" else "s"} on Channel $channel (below -80 dBm, negligible impact)."
        else -> "$actionable active radio${if (actionable == 1) "" else "s"} politely sharing Channel $channel airtime."
    }

    private fun buildAciSummary(total: Int, actionable: Int, severe: Boolean, channel: Int): String = when {
        total == 0 -> "Zero sideband channel collisions detected across Channel $channel."
        severe -> "Severe sideband collision from $actionable strong off-center router${if (actionable == 1) "" else "s"} overlapping Channel $channel."
        actionable == 0 -> "$total faint overlapping radio${if (total == 1) "" else "s"} below -80 dBm."
        else -> "$actionable partially overlapping radio${if (actionable == 1) "" else "s"} bleeding noise into Channel $channel."
    }
}
