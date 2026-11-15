package com.a55.wifiradar.feature.insights

import com.a55.wifiradar.core.designsystem.model.toSemanticTone
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.model.ActionableInsight
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.EvilTwinAlert
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.HealthSubScores
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PmfState
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RoamEvent
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.RssiSample
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.WifiBand
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal object InsightsStateMapper {

    const val MAX_TIMELINE_SERIES = 5
    const val DEFAULT_VISIBLE_SECURITY_ROWS = 12
    const val DEFAULT_VISIBLE_ROAM_ROWS = 10
    const val MAX_PREVIEW_CHARS = 4_096

    const val SUBSCORE_SIGNAL_COVERAGE = "signal_coverage"
    const val SUBSCORE_CHANNEL_CROWDING = "channel_crowding"
    const val SUBSCORE_INTERFERENCE_PURITY = "interference_purity"
    const val SUBSCORE_SECURITY_HYGIENE = "security_hygiene"

    private val SCREAMING_SNAKE_REGEX = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")
    private val MAC_PAIR_REGEX = Regex("""^[0-9A-F]{2}(:[0-9A-F]{2}){5}$""")

    fun formatDisplayMac(rawBssid: String, redactMacs: Boolean): String =
        if (redactMacs) {
            MacAddressUtils.redactMac(rawBssid)
        } else {
            MacAddressUtils.normalizeBssid(rawBssid).ifEmpty { "00:00:00:00:00:00" }
        }

    fun sanitizeText(raw: String?, fallback: String = ""): String {
        if (raw.isNullOrBlank()) return fallback
        if (!SemanticFormatters.containsRawEnumLeak(raw) && !SCREAMING_SNAKE_REGEX.containsMatchIn(raw)) {
            return raw
        }
        val cleaned = SCREAMING_SNAKE_REGEX.replace(raw) { match ->
            val token = match.value
            if (MAC_PAIR_REGEX.matches(token)) token else token.replace('_', '-')
        }
        return if (!SemanticFormatters.containsRawEnumLeak(cleaned)) cleaned else fallback
    }

    fun safeRssiDbm(rssiDbm: Int): Int = rssiDbm.coerceIn(-120, 0)

    fun mapToUiState(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        readinessState: ScannerReadinessState,
        historyWindow: RssiHistoryWindow,
        highlightedTimelineBssid: String?,
        redactMacs: Boolean,
        exportFormat: ExportFormat,
        isExportPreviewExpanded: Boolean,
        showAllSecurityRows: Boolean,
        showAllRoamEvents: Boolean,
        exportPayload: ExportPayload?,
        copyConfirmationMessage: String?,
        statusBannerMessage: String?,
    ): InsightsUiState {
        val allKnownRadios = if (snapshot.radios.isNotEmpty()) snapshot.radios else health.securityAuditRadios
        val hasAnyRadioData = snapshot.radios.isNotEmpty() || health.securityAuditRadios.isNotEmpty()
        val rawScore = health.overallScore0To100
        val deviceSummary = buildDeviceRadioSummary(snapshot.radioCapabilities)
        val rnrCount = countColocated6GhzNeighbors(snapshot)
        val colocatedHint = if (rnrCount > 0 && !snapshot.radioCapabilities.has6Ghz) {
            "$rnrCount co-located 6 GHz router${if (rnrCount == 1) "" else "s"} discovered via 2.4 & 5 GHz RNR beacons"
        } else {
            null
        }

        // 1. Health Score & 4 Sub-Scores
        val isScored: Boolean
        val clampedOverallScore: Int?
        val ringQualifier: String
        val healthHeadline: String
        val healthSummary: String
        val healthTone: SemanticTone
        val subScoreBars: List<SubScoreBarUi>

        if (health.state != EnvironmentHealthState.SCORED || rawScore == null || !hasAnyRadioData) {
            isScored = false
            clampedOverallScore = null
            ringQualifier = "Waiting"
            healthHeadline = "Waiting for scan data"
            healthSummary = if (!readinessState.canScanNow) {
                sanitizeText(
                    readinessState.plainEnglishExplanation,
                    "Grant scan permission and enable Location to begin wireless health auditing.",
                )
            } else {
                "Listening for nearby wireless networks..."
            }
            healthTone = SemanticTone.NEUTRAL
            subScoreBars = buildWaitingSubScores()
        } else {
            isScored = true
            val score = rawScore.coerceIn(0, 100)
            clampedOverallScore = score
            ringQualifier = when {
                score >= 80 -> "Healthy RF"
                score >= 60 -> "Good RF"
                score >= 40 -> "Moderate"
                else -> "Needs Care"
            }
            healthTone = scoreToTone(score)
            val candidateHeadline = health.headlineLabel
            healthHeadline = if (
                candidateHeadline.contains("/ 100") &&
                !SemanticFormatters.containsRawEnumLeak(candidateHeadline) &&
                !SCREAMING_SNAKE_REGEX.containsMatchIn(candidateHeadline)
            ) {
                candidateHeadline
            } else {
                SemanticFormatters.formatHealthScoreHeadline(score)
            }
            healthSummary = sanitizeText(
                health.summarySentence,
                "Composite health score across signal reach, channel crowding, interference purity, and Wi-Fi security.",
            )
            subScoreBars = buildScoredSubScores(health.subScores, score)
        }

        // 2. Actionable Insights (severity-first deterministic sort)
        val mappedInsights = health.insights
            .sortedWith(
                compareBy<ActionableInsight> { it.severity.ordinal }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.id }
            )
            .map { item -> mapActionableInsight(item, redactMacs) }

        // 3. Multi-Network RSSI Time Graph & Roam Log
        val referenceLatestElapsedMs = computeLatestElapsedMs(snapshot)
        val windowEndMs = referenceLatestElapsedMs
        val windowStartMs = referenceLatestElapsedMs - historyWindow.durationMs
        val spanMs = historyWindow.durationMs.toDouble().coerceAtLeast(1_000.0)

        val normHighlighted = highlightedTimelineBssid
            ?.let(MacAddressUtils::normalizeBssid)
            ?.ifEmpty { null }

        val timelineSeries = buildTimelineSeries(
            snapshot = snapshot,
            allKnownRadios = allKnownRadios,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            spanMs = spanMs,
            normHighlightedBssid = normHighlighted,
            redactMacs = redactMacs,
        )

        val activeHighlighted = normHighlighted?.takeIf { target ->
            timelineSeries.any { it.bssid == target }
        }

        val sortedRoamsDesc = snapshot.roamEvents.sortedByDescending { it.timestampElapsedMs }
        val chartRoamMarkers = snapshot.roamEvents
            .filter { it.timestampElapsedMs in windowStartMs..windowEndMs }
            .sortedBy { it.timestampElapsedMs }
            .map { roam ->
                val xFrac = ((roam.timestampElapsedMs - windowStartMs) / spanMs)
                    .toFloat()
                    .coerceIn(0f, 1f)
                RoamMarkerUi(
                    xFraction0To1 = xFrac,
                    timestampElapsedMs = roam.timestampElapsedMs,
                    label = "Roam → Ch ${roam.newChannel}",
                )
            }

        val allMappedRoams = sortedRoamsDesc.map { roam ->
            mapRoamEvent(
                event = roam,
                referenceElapsedMs = referenceLatestElapsedMs,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                redactMacs = redactMacs,
            )
        }
        val visibleRoams = if (showAllRoamEvents || allMappedRoams.size <= DEFAULT_VISIBLE_ROAM_ROWS) {
            allMappedRoams
        } else {
            allMappedRoams.take(DEFAULT_VISIBLE_ROAM_ROWS)
        }

        // 4. Evil-Twin Alerts & Security Posture Audit
        val mappedEvilTwins = health.evilTwinAlerts.map { alert ->
            mapEvilTwinAlert(alert, redactMacs)
        }

        val sourceSecurityRadios = health.securityAuditRadios.ifEmpty { snapshot.radios }
        val sortedSecurityRadios = sourceSecurityRadios.sortedWith(
            compareBy<BssidRadio> { it.security.score0To100 }
                .thenByDescending { it.security.isEvilTwinSuspect }
                .thenByDescending { it.isConnected }
                .thenByDescending { it.rssiDbm }
                .thenBy { it.bssid }
        )

        val weakCount = sortedSecurityRadios.count {
            it.security.grade.isWeakOrInsecure || it.security.isEvilTwinSuspect
        }
        val wpsCount = sortedSecurityRadios.count { it.security.wpsEnabled }
        val modernCount = sortedSecurityRadios.count {
            it.security.grade == SecurityGrade.A_PLUS ||
                it.security.grade == SecurityGrade.A ||
                it.security.pmfState == PmfState.REQUIRED
        }

        val slicedSecurityRadios = if (
            showAllSecurityRows || sortedSecurityRadios.size <= DEFAULT_VISIBLE_SECURITY_ROWS
        ) {
            sortedSecurityRadios
        } else {
            sortedSecurityRadios.take(DEFAULT_VISIBLE_SECURITY_ROWS)
        }
        val mappedSecurityRows = slicedSecurityRadios.map { radio ->
            mapSecurityAuditRow(radio, redactMacs)
        }

        // 5. Leak-Free Export Preview
        val previewUi = exportPayload?.let { payload ->
            buildExportPreviewUi(
                payload = payload,
                radios = sortedSecurityRadios.ifEmpty { allKnownRadios },
                healthHeadline = healthHeadline,
                redactMacs = redactMacs,
            )
        }

        return InsightsUiState(
            readinessState = readinessState,
            scanTelemetry = snapshot.scanTelemetry,
            detectedNetworkCount = allKnownRadios.size,
            deviceRadioSummary = deviceSummary,
            colocated6GhzHint = colocatedHint,
            isWaitingForScanData = !isScored,
            overallScore0To100 = clampedOverallScore,
            ringShortQualifier = ringQualifier,
            healthHeadline = healthHeadline,
            healthSummarySentence = healthSummary,
            healthTone = healthTone,
            subScores = subScoreBars,
            best24GhzRec = health.best24GhzRecommendation,
            best5GhzRec = health.best5GhzRecommendation,
            best6GhzRec = health.best6GhzRecommendation,
            actionableInsights = mappedInsights,
            selectedHistoryWindow = historyWindow,
            highlightedTimelineBssid = activeHighlighted,
            timelineSeries = timelineSeries,
            chartRoamMarkers = chartRoamMarkers,
            roamEvents = visibleRoams,
            totalRoamEventCount = allMappedRoams.size,
            showAllRoamEvents = showAllRoamEvents,
            evilTwinAlerts = mappedEvilTwins,
            securityAuditRows = mappedSecurityRows,
            totalSecurityRadioCount = sortedSecurityRadios.size,
            weakSecurityCount = weakCount,
            wpsExposedCount = wpsCount,
            modernProtectedCount = modernCount,
            showAllSecurityRows = showAllSecurityRows,
            selectedExportFormat = exportFormat,
            redactMacs = redactMacs,
            isExportPreviewExpanded = isExportPreviewExpanded,
            exportPreview = previewUi,
            copyConfirmationMessage = copyConfirmationMessage,
            statusBannerMessage = statusBannerMessage,
        )
    }

    private fun buildDeviceRadioSummary(caps: DeviceRadioCapabilities): String {
        val safeModel = caps.deviceModel.replace('_', '-').trim().ifBlank { "Galaxy A55 5G" }
        val bandPhrase = when {
            caps.has24Ghz && caps.has5Ghz && caps.has6Ghz -> "2.4, 5 & 6 GHz Tri-Band Radio"
            caps.has24Ghz && caps.has5Ghz -> "2.4 & 5 GHz Dual-Band Radio"
            caps.has24Ghz -> "2.4 GHz Radio"
            else -> "Wireless Radio"
        }
        val genBadge = caps.maxSupportedWifiGeneration.shortBadge
        return "$safeModel · $bandPhrase · Up to $genBadge"
    }

    private fun countColocated6GhzNeighbors(snapshot: RadarSnapshot): Int {
        val keys = mutableSetOf<String>()
        snapshot.radios.forEach { radio ->
            radio.beaconDetails.colocated6GhzNeighbors.forEach { n ->
                keys += "${MacAddressUtils.normalizeBssid(n.sourceBssid)}|${n.channelNumber}"
            }
        }
        snapshot.physicalClusters.forEach { cluster ->
            cluster.colocated6GhzNeighbors.forEach { n ->
                keys += "${MacAddressUtils.normalizeBssid(n.sourceBssid)}|${n.channelNumber}"
            }
        }
        return keys.size
    }

    private fun buildWaitingSubScores(): List<SubScoreBarUi> = listOf(
        SubScoreBarUi(
            key = SUBSCORE_SIGNAL_COVERAGE,
            label = "Signal Coverage",
            score0To100 = null,
            formattedValue = "— / 100 · Waiting for scan",
            tone = SemanticTone.NEUTRAL,
        ),
        SubScoreBarUi(
            key = SUBSCORE_CHANNEL_CROWDING,
            label = "Channel Crowding",
            score0To100 = null,
            formattedValue = "— / 100 · Waiting for scan",
            tone = SemanticTone.NEUTRAL,
        ),
        SubScoreBarUi(
            key = SUBSCORE_INTERFERENCE_PURITY,
            label = "Interference Purity",
            score0To100 = null,
            formattedValue = "— / 100 · Waiting for scan",
            tone = SemanticTone.NEUTRAL,
        ),
        SubScoreBarUi(
            key = SUBSCORE_SECURITY_HYGIENE,
            label = "Security Hygiene",
            score0To100 = null,
            formattedValue = "— / 100 · Waiting for scan",
            tone = SemanticTone.NEUTRAL,
        ),
    )

    private fun buildScoredSubScores(
        subScores: HealthSubScores?,
        fallbackScore: Int,
    ): List<SubScoreBarUi> {
        val coverage = (subScores?.signalCoverage0To100 ?: fallbackScore).coerceIn(0, 100)
        val crowding = (subScores?.channelCrowding0To100 ?: fallbackScore).coerceIn(0, 100)
        val purity = (subScores?.interferencePurity0To100 ?: fallbackScore).coerceIn(0, 100)
        val security = (subScores?.securityHygiene0To100 ?: fallbackScore).coerceIn(0, 100)

        return listOf(
            SubScoreBarUi(
                key = SUBSCORE_SIGNAL_COVERAGE,
                label = "Signal Coverage",
                score0To100 = coverage,
                formattedValue = "$coverage / 100 · ${coverageQualifier(coverage)}",
                tone = scoreToTone(coverage),
            ),
            SubScoreBarUi(
                key = SUBSCORE_CHANNEL_CROWDING,
                label = "Channel Crowding",
                score0To100 = crowding,
                formattedValue = "$crowding / 100 · ${SemanticFormatters.congestionQualifier(crowding)}",
                tone = scoreToTone(crowding),
            ),
            SubScoreBarUi(
                key = SUBSCORE_INTERFERENCE_PURITY,
                label = "Interference Purity",
                score0To100 = purity,
                formattedValue = "$purity / 100 · ${purityQualifier(purity)}",
                tone = scoreToTone(purity),
            ),
            SubScoreBarUi(
                key = SUBSCORE_SECURITY_HYGIENE,
                label = "Security Hygiene",
                score0To100 = security,
                formattedValue = "$security / 100 · ${securityQualifier(security)}",
                tone = scoreToTone(security),
            ),
        )
    }

    private fun coverageQualifier(score: Int): String = when {
        score >= 80 -> "Strong Reach"
        score >= 60 -> "Solid Coverage"
        score >= 40 -> "Patchy Reach"
        else -> "Weak Signal Zone"
    }

    private fun purityQualifier(score: Int): String = when {
        score >= 80 -> "Clean Spectrum"
        score >= 60 -> "Low Overlap"
        score >= 40 -> "Sideband Noise"
        else -> "Heavy Collisions"
    }

    private fun securityQualifier(score: Int): String = when {
        score >= 80 -> "Hardened"
        score >= 60 -> "Standard WPA2"
        score >= 40 -> "Upgrade Advised"
        else -> "Exposed Risk"
    }

    private fun mapActionableInsight(
        insight: ActionableInsight,
        redactMacs: Boolean,
    ): ActionableInsightCardUi {
        val defaultCta = when (val target = insight.actionTarget) {
            is InsightActionTarget.OpenNetworkDetail -> "Inspect Network"
            is InsightActionTarget.LaunchProximity -> "Open Proximity Radar"
            is InsightActionTarget.OpenSpectrumBand -> "View ${target.band.displayLabel} Spectrum"
            InsightActionTarget.NoAction -> null
        }
        val cleanActionLabel = if (insight.actionTarget is InsightActionTarget.NoAction) {
            null
        } else {
            insight.actionLabel?.takeIf { it.isNotBlank() }?.let { sanitizeText(it, defaultCta.orEmpty()) }
                ?: defaultCta
        }
        val bodyText = maybeRedactInlineMacs(
            text = sanitizeText(insight.plainEnglishBody, insight.title),
            redactMacs = redactMacs,
        )
        return ActionableInsightCardUi(
            id = insight.id,
            severity = insight.severity,
            severityLabel = insight.severity.displayLabel,
            category = insight.category,
            categoryLabel = insight.category.displayLabel,
            tone = insight.severity.toSemanticTone(),
            title = sanitizeText(insight.title, "Wireless Recommendation"),
            body = bodyText,
            metricBadge = insight.metricBadge?.let { sanitizeText(it, "") }?.ifBlank { null },
            actionLabel = cleanActionLabel,
            actionTarget = insight.actionTarget,
        )
    }

    private fun computeLatestElapsedMs(snapshot: RadarSnapshot): Long {
        var latest = snapshot.timestampElapsedMs.coerceAtLeast(0L)
        snapshot.rssiTimelines.values.forEach { samples ->
            samples.forEach { s ->
                if (s.timestampElapsedMs > latest) latest = s.timestampElapsedMs
            }
        }
        snapshot.roamEvents.forEach { roam ->
            if (roam.timestampElapsedMs > latest) latest = roam.timestampElapsedMs
        }
        snapshot.radios.forEach { radio ->
            if (radio.lastSeenElapsedMs > latest) latest = radio.lastSeenElapsedMs
        }
        return latest
    }

    private fun buildTimelineSeries(
        snapshot: RadarSnapshot,
        allKnownRadios: List<BssidRadio>,
        windowStartMs: Long,
        windowEndMs: Long,
        spanMs: Double,
        normHighlightedBssid: String?,
        redactMacs: Boolean,
    ): List<RssiTimelineSeriesUi> {
        val normalizedTimelines = mutableMapOf<String, List<RssiSample>>()
        for ((rawKey, samples) in snapshot.rssiTimelines) {
            val normKey = MacAddressUtils.normalizeBssid(rawKey)
            if (normKey.isNotEmpty()) {
                val existing = normalizedTimelines[normKey]
                normalizedTimelines[normKey] = if (existing == null) samples else (existing + samples)
            }
        }

        val radioByBssid = allKnownRadios.associateBy { MacAddressUtils.normalizeBssid(it.bssid) }
        val connectedNormBssid = snapshot.connectedWifi?.bssid?.let(MacAddressUtils::normalizeBssid)?.ifEmpty { null }
            ?: allKnownRadios.firstOrNull { it.isConnected }?.let { MacAddressUtils.normalizeBssid(it.bssid) }

        val candidateOrder = LinkedHashSet<String>()
        if (connectedNormBssid != null) {
            candidateOrder += connectedNormBssid
        }
        snapshot.roamEvents.sortedByDescending { it.timestampElapsedMs }.forEach { roam ->
            val newNorm = MacAddressUtils.normalizeBssid(roam.newBssid)
            val prevNorm = MacAddressUtils.normalizeBssid(roam.previousBssid)
            if (newNorm.isNotEmpty() && (newNorm in radioByBssid || newNorm in normalizedTimelines)) {
                candidateOrder += newNorm
            }
            if (prevNorm.isNotEmpty() && (prevNorm in radioByBssid || prevNorm in normalizedTimelines)) {
                candidateOrder += prevNorm
            }
        }
        allKnownRadios
            .sortedWith(compareByDescending<BssidRadio> { it.rssiDbm }.thenBy { it.bssid })
            .forEach { radio ->
                val norm = MacAddressUtils.normalizeBssid(radio.bssid)
                if (norm.isNotEmpty()) candidateOrder += norm
            }
        normalizedTimelines.keys.sorted().forEach { norm ->
            candidateOrder += norm
        }

        val built = mutableListOf<RssiTimelineSeriesUi>()
        for (normBssid in candidateOrder) {
            if (built.size >= MAX_TIMELINE_SERIES) break
            val radio = radioByBssid[normBssid]
            val rawSamples = normalizedTimelines[normBssid].orEmpty()
            val sortedSamples = if (rawSamples.isNotEmpty()) {
                rawSamples.sortedBy { it.timestampElapsedMs }
            } else if (radio != null && !radio.isMissedInLatestSweep) {
                val sampleTs = radio.lastSeenElapsedMs.takeIf { it in windowStartMs..windowEndMs } ?: windowEndMs
                listOf(
                    RssiSample(
                        timestampElapsedMs = sampleTs,
                        rssiDbm = safeRssiDbm(radio.rssiDbm),
                        smoothedRssiDbm = radio.smoothedRssiDbm.takeIf { it.isFinite() },
                        isConnectedSample = radio.isConnected,
                    )
                )
            } else {
                emptyList()
            }

            if (sortedSamples.isEmpty()) continue

            val points = mutableListOf<TimelinePointUi>()
            val rawIntValues = mutableListOf<Int>()
            var prevPlottedSample: RssiSample? = null
            var sawNullDropoutSincePrevPlotted = false
            var gapCount = 0

            for (sample in sortedSamples) {
                val finiteSmoothed = sample.smoothedRssiDbm?.takeIf { it.isFinite() }
                val validInt = sample.rssiDbm?.let(::safeRssiDbm)

                if (finiteSmoothed == null && validInt == null) {
                    if (prevPlottedSample != null) {
                        sawNullDropoutSincePrevPlotted = true
                    }
                    continue
                }

                if (sample.timestampElapsedMs !in windowStartMs..windowEndMs) {
                    continue
                }

                val plottedDbmFloat = (finiteSmoothed?.toFloat() ?: validInt!!.toFloat())
                    .coerceIn(-100f, -20f)
                val statInt = validInt ?: finiteSmoothed!!.roundToInt().coerceIn(-120, 0)

                val prev = prevPlottedSample
                val isGap = if (prev == null) {
                    false
                } else {
                    val gapThresholdMs = when {
                        prev.isConnectedSample && sample.isConnectedSample -> 6_000L
                        !snapshot.scanTelemetry.isScanThrottleEnabled -> 12_000L
                        else -> 40_000L
                    }
                    sawNullDropoutSincePrevPlotted ||
                        (sample.timestampElapsedMs - prev.timestampElapsedMs) > gapThresholdMs
                }
                if (isGap) gapCount++

                val xFraction = ((sample.timestampElapsedMs - windowStartMs).toDouble() / spanMs)
                    .toFloat()
                    .coerceIn(0f, 1f)

                points += TimelinePointUi(
                    xFraction0To1 = xFraction,
                    rssiDbm = plottedDbmFloat,
                    timestampElapsedMs = sample.timestampElapsedMs,
                    connectWithDashedGapFromPrev = isGap,
                )
                rawIntValues += statInt
                prevPlottedSample = sample
                sawNullDropoutSincePrevPlotted = false
            }

            if (points.isEmpty()) continue

            val latestDbm = rawIntValues.last()
            val minDbm = rawIntValues.minOrNull() ?: latestDbm
            val maxDbm = rawIntValues.maxOrNull() ?: latestDbm
            val avgDbm = rawIntValues.average().let { if (it.isFinite()) it else latestDbm.toDouble() }
            val tier = SignalQualityTier.fromRssiDbm(latestDbm)
            val statsLine = String.format(
                Locale.US,
                "Now %d dBm (%s) · Min %d · Max %d · Avg %.1f dBm",
                latestDbm,
                tier.shortLabel,
                minDbm,
                maxDbm,
                avgDbm,
            )

            val isConn = (connectedNormBssid != null && normBssid == connectedNormBssid) || radio?.isConnected == true
            val paletteIdx = built.size % 5
            val displayName = radio?.effectiveDisplayName?.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL }
                ?: snapshot.roamEvents.firstOrNull {
                    MacAddressUtils.normalizeBssid(it.newBssid) == normBssid ||
                        MacAddressUtils.normalizeBssid(it.previousBssid) == normBssid
                }?.ssid?.ifBlank { null }
                ?: formatDisplayMac(normBssid, redactMacs)
            val bandLabel = radio?.channelDescriptor?.band?.displayLabel ?: WifiBand.BAND_5_GHZ.displayLabel
            val chNum = radio?.channelDescriptor?.primaryChannel?.coerceAtLeast(1) ?: 1

            built += RssiTimelineSeriesUi(
                bssid = normBssid,
                formattedBssid = formatDisplayMac(normBssid, redactMacs),
                displayName = displayName,
                bandLabel = bandLabel,
                channel = chNum,
                paletteIndex = paletteIdx,
                isConnected = isConn,
                isHighlighted = false,
                isDimmed = false,
                points = points,
                gapSegmentCount = gapCount,
                latestRssiDbm = latestDbm,
                minRssiDbm = minDbm,
                maxRssiDbm = maxDbm,
                avgRssiDbm = avgDbm,
                formattedStatsLine = statsLine,
            )
        }

        val highlightActive = normHighlightedBssid != null && built.any { it.bssid == normHighlightedBssid }
        return if (!highlightActive) {
            built
        } else {
            built.map { series ->
                val isTarget = series.bssid == normHighlightedBssid
                series.copy(
                    isHighlighted = isTarget,
                    isDimmed = !isTarget,
                )
            }
        }
    }

    private fun mapRoamEvent(
        event: RoamEvent,
        referenceElapsedMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        redactMacs: Boolean,
    ): RoamEventItemUi {
        val oldMac = formatDisplayMac(event.previousBssid, redactMacs)
        val newMac = formatDisplayMac(event.newBssid, redactMacs)
        val prevDbm = safeRssiDbm(event.previousRssiDbm)
        val nextDbm = safeRssiDbm(event.newRssiDbm)
        val delta = event.rssiDeltaDb.coerceIn(-90, 90)
        val deltaStr = String.format(
            Locale.US,
            "%+d dB (%d → %d dBm)",
            delta,
            prevDbm,
            nextDbm,
        )
        val deltaTone = when {
            delta >= 3 -> SemanticTone.EXCELLENT
            delta >= -2 -> SemanticTone.GOOD
            else -> SemanticTone.FAIR
        }
        val ageMs = (referenceElapsedMs - event.timestampElapsedMs).coerceAtLeast(0L)
        return RoamEventItemUi(
            ssid = event.ssid.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL },
            formattedOldBssid = oldMac,
            formattedNewBssid = newMac,
            channelTransitionLabel = "${event.previousBand.displayLabel} Ch ${event.previousChannel} → ${event.newBand.displayLabel} Ch ${event.newChannel}",
            deltaFormatted = deltaStr,
            deltaTone = deltaTone,
            relativeAgeLabel = SemanticFormatters.formatAge(ageMs),
            isBandSwitch = event.isBandSwitch,
            isWithinSelectedWindow = event.timestampElapsedMs in windowStartMs..windowEndMs,
        )
    }

    private fun mapEvilTwinAlert(
        alert: EvilTwinAlert,
        redactMacs: Boolean,
    ): EvilTwinAlertCardUi {
        val legitMacs = alert.legitimateBssids.map { formatDisplayMac(it, redactMacs) }
        val suspectMacs = alert.suspectOpenBssids.map { formatDisplayMac(it, redactMacs) }
        val firstRawSuspect = alert.suspectOpenBssids.firstOrNull()
            ?.let(MacAddressUtils::normalizeBssid)
            ?.ifEmpty { null }
        val safeDbm = safeRssiDbm(alert.suspectRssiDbm)
        val warningBody = maybeRedactInlineMacs(
            text = sanitizeText(
                alert.plainEnglishWarning,
                "An unencrypted Open radio is broadcasting the same network name as your protected Wi-Fi.",
            ),
            redactMacs = redactMacs,
        )
        return EvilTwinAlertCardUi(
            ssid = alert.ssid.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL },
            plainEnglishWarning = warningBody,
            legitimateProtocolLabel = "${alert.legitimateProtocol.displayLabel} · ${alert.legitimateProtocol.plainEnglishSummary}",
            formattedLegitimateBssids = legitMacs,
            formattedSuspectOpenBssids = suspectMacs,
            firstRawSuspectBssid = firstRawSuspect,
            suspectSignalSummary = SemanticFormatters.formatRssiForDiscovery(safeDbm),
        )
    }

    private fun mapSecurityAuditRow(
        radio: BssidRadio,
        redactMacs: Boolean,
    ): SecurityAuditRowUi {
        val sec = radio.security
        val pmfTone = when (sec.pmfState) {
            PmfState.REQUIRED -> SemanticTone.EXCELLENT
            PmfState.CAPABLE_OPTIONAL -> SemanticTone.GOOD
            PmfState.DISABLED -> SemanticTone.FAIR
            PmfState.NOT_APPLICABLE -> SemanticTone.NEUTRAL
        }
        val cipherLabels = sec.pairwiseCiphers
            .filter { it != CipherSuite.NONE && it != CipherSuite.UNKNOWN }
            .map { it.displayLabel }
            .distinct()
        val cipherSummary = if (cipherLabels.isNotEmpty()) {
            "Cipher: ${cipherLabels.joinToString(", ")}"
        } else {
            null
        }
        val hasWeakCipher = sec.pairwiseCiphers.any {
            it == CipherSuite.TKIP || it == CipherSuite.WEP_40 || it == CipherSuite.WEP_104
        }
        val wpsWarning = if (sec.wpsEnabled) {
            "WPS Enabled · PIN Brute-Force Risk"
        } else {
            null
        }
        val featureBadges = buildList {
            if (sec.saeH2eRequired) {
                add("SAE H2E Required")
            } else if (sec.saeH2eSupported) {
                add("SAE H2E Supported")
            }
            if (sec.beaconProtection || radio.beaconDetails.roaming.beaconProtection) {
                add("Beacon Protection Active")
            }
            if (sec.isTransitionMode) {
                add("WPA2 / WPA3 Transition")
            }
            if (radio.beaconDetails.roaming.hasAnyFastRoaming) {
                add(SemanticFormatters.formatRoamingSummary(radio.beaconDetails.roaming))
            }
        }
        val cleanWarnings = sec.warnings
            .map { w -> maybeRedactInlineMacs(sanitizeText(w, ""), redactMacs) }
            .filter { it.isNotBlank() }

        return SecurityAuditRowUi(
            rawBssid = MacAddressUtils.normalizeBssid(radio.bssid),
            formattedBssid = formatDisplayMac(radio.bssid, redactMacs),
            displayName = radio.effectiveDisplayName.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL },
            vendorLabel = sanitizeText(radio.vendorInfo.displayLabel, "Unknown Vendor"),
            channelSummary = SemanticFormatters.formatChannelSummary(
                descriptor = radio.channelDescriptor,
                congestionScore0To100 = radio.channelCongestionScore0To100,
            ),
            band = radio.channelDescriptor.band,
            rssiDbm = safeRssiDbm(radio.rssiDbm),
            isConnected = radio.isConnected,
            security = sec,
            pmfLabel = sec.pmfState.displayLabel,
            pmfNote = sec.pmfState.plainEnglishNote,
            pmfTone = pmfTone,
            cipherSummary = cipherSummary,
            hasWeakCipher = hasWeakCipher,
            wpsWarningLabel = wpsWarning,
            featureBadges = featureBadges,
            warnings = cleanWarnings,
        )
    }

    private fun buildExportPreviewUi(
        payload: ExportPayload,
        radios: List<BssidRadio>,
        healthHeadline: String,
        redactMacs: Boolean,
    ): ExportPreviewUi {
        val safeFileName = sanitizeText(payload.fileName, "wifi-radar-report.${payload.format.fileExtension}")
        val bytes = max(payload.sizeBytes, payload.content.encodeToByteArray().size)
        val formattedSize = if (bytes < 1024) {
            "$bytes B"
        } else {
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        }

        if (payload.format == ExportFormat.JSON) {
            // Render a crisp, leak-free structured summary of the JSON payload so kotlinx.serialization
            // SCREAMING_SNAKE_CASE enum constants never leak into Compose UI SemanticsNodes!
            val privacyLine = if (payload.redactedMacs || redactMacs) {
                "MAC Privacy: Redacted (OUI + radio tail preserved)"
            } else {
                "MAC Privacy: Full hardware BSSIDs included"
            }
            val sampleLines = radios.take(4).joinToString("\n") { r ->
                val mac = formatDisplayMac(r.bssid, payload.redactedMacs || redactMacs)
                val safeDbm = safeRssiDbm(r.rssiDbm)
                "  • ${r.effectiveDisplayName.ifBlank { DecodedSsid.HIDDEN_NETWORK_LABEL }} ($mac) · ${r.channelDescriptor.band.displayLabel} Ch ${r.channelDescriptor.primaryChannel} · $safeDbm dBm · Grade ${r.security.grade.badgeText}"
            }
            val structuredSummary = buildString {
                appendLine("JSON Diagnostic Schema v1.0 · $safeFileName ($formattedSize)")
                appendLine("Health Summary: $healthHeadline")
                appendLine("Audited Radios: ${payload.networkCount} · $privacyLine")
                if (sampleLines.isNotBlank()) {
                    appendLine("Included Radio Entries:")
                    appendLine(sampleLines)
                }
                append("Full machine-readable JSON (${bytes} bytes) is exported verbatim via Copy to Clipboard or Share JSON.")
            }
            return ExportPreviewUi(
                format = payload.format,
                fileName = safeFileName,
                networkCount = payload.networkCount,
                formattedSize = formattedSize,
                previewText = structuredSummary,
                isPreviewTruncated = false,
                isStructuredJsonSummary = true,
            )
        }

        val isTruncated = payload.content.length > MAX_PREVIEW_CHARS
        val sliced = if (isTruncated) {
            payload.content.take(MAX_PREVIEW_CHARS) + "\n… (preview truncated — full report exported on Copy or Share)"
        } else {
            payload.content
        }
        val leakSafePreview = sanitizeText(sliced, sliced)
        return ExportPreviewUi(
            format = payload.format,
            fileName = safeFileName,
            networkCount = payload.networkCount,
            formattedSize = formattedSize,
            previewText = leakSafePreview,
            isPreviewTruncated = isTruncated,
            isStructuredJsonSummary = false,
        )
    }

    private val INLINE_MAC_FINDER = Regex("""\b[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}\b""")

    private fun maybeRedactInlineMacs(text: String, redactMacs: Boolean): String {
        if (!redactMacs) return text
        return INLINE_MAC_FINDER.replace(text) { match ->
            MacAddressUtils.redactMac(match.value)
        }
    }

    private fun scoreToTone(score0To100: Int): SemanticTone = when {
        score0To100 >= 80 -> SemanticTone.EXCELLENT
        score0To100 >= 60 -> SemanticTone.GOOD
        score0To100 >= 40 -> SemanticTone.FAIR
        else -> SemanticTone.WEAK
    }
}
