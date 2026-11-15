package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ActionableInsight
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.EvilTwinAlert
import com.a55.wifiradar.core.model.HealthSubScores
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.InsightCategory
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Composite `0–100` Wireless Environment Health evaluator (`F16`) across Signal Coverage,
 * Channel Crowding, Interference Purity, and Security Hygiene, emitting prioritized
 * plain-English `ActionableInsight` cards and `EvilTwinAlert`s.
 */
object EnvironmentHealthEvaluator {

    fun evaluate(snapshot: RadarSnapshot): EnvironmentHealthReport {
        if (snapshot.radios.isEmpty()) {
            return EnvironmentHealthReport(
                state = EnvironmentHealthState.WAITING_FOR_DATA,
                overallScore0To100 = null,
                headlineLabel = SemanticFormatters.formatHealthScoreHeadline(null),
                summarySentence = "Listening for nearby wireless networks — environment scores appear as soon as scan results arrive.",
                subScores = null,
                insights = emptyList(),
                evilTwinAlerts = emptyList(),
                securityAuditRadios = emptyList(),
                best24GhzRecommendation = null,
                best5GhzRecommendation = null,
                best6GhzRecommendation = null,
                computedAtTimestampMs = snapshot.timestampElapsedMs,
            )
        }

        val (annotatedRadios, evilTwinAlerts) = SecurityAnalyzer.detectEvilTwinsAndAnnotate(snapshot.radios)

        val spec24 = SpectrumAnalyzer.analyze(
            networks = annotatedRadios,
            band = WifiBand.BAND_2_4_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )
        val spec5 = SpectrumAnalyzer.analyze(
            networks = annotatedRadios,
            band = WifiBand.BAND_5_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )
        val spec6 = SpectrumAnalyzer.analyze(
            networks = annotatedRadios,
            band = WifiBand.BAND_6_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )

        val primaryRadio = annotatedRadios.firstOrNull { it.isConnected }
            ?: annotatedRadios.maxWith(
                compareBy<BssidRadio> { it.rssiDbm }.thenBy { it.bssid }
            )

        val primarySpec = when (primaryRadio.channelDescriptor.band) {
            WifiBand.BAND_2_4_GHZ -> spec24
            WifiBand.BAND_5_GHZ -> spec5
            WifiBand.BAND_6_GHZ -> spec6
            else -> spec5
        }

        val signalCoverage = computeSignalCoverageScore(primaryRadio)
        val channelCrowding = computeChannelCrowdingScore(
            primaryRadio = primaryRadio,
            primarySpec = primarySpec,
            allRadios = annotatedRadios,
            spec24 = spec24,
            spec5 = spec5,
            spec6 = spec6,
        )
        val interferencePurity = computeInterferencePurityScore(
            primaryRadio = primaryRadio,
            primarySpec = primarySpec,
            allRadios = annotatedRadios,
        )
        val securityHygiene = computeSecurityHygieneScore(
            primaryRadio = primaryRadio,
            allRadios = annotatedRadios,
            evilTwinAlerts = evilTwinAlerts,
        )

        val subScores = HealthSubScores(
            signalCoverage0To100 = signalCoverage,
            channelCrowding0To100 = channelCrowding,
            interferencePurity0To100 = interferencePurity,
            securityHygiene0To100 = securityHygiene,
        )

        val overallScore = (
            0.28 * signalCoverage +
                0.24 * channelCrowding +
                0.24 * interferencePurity +
                0.24 * securityHygiene
            ).finiteOr(50.0).roundToInt().coerceIn(0, 100)

        val headline = SemanticFormatters.formatHealthScoreHeadline(overallScore)

        val securityAuditRadios = annotatedRadios.sortedWith(
            compareByDescending<BssidRadio> { it.security.isEvilTwinSuspect }
                .thenBy { it.security.score0To100 }
                .thenByDescending { it.isConnected }
                .thenByDescending { it.rssiDbm }
                .thenBy { it.bssid }
        )

        val insights = buildPrioritizedInsights(
            snapshot = snapshot,
            allRadios = annotatedRadios,
            primaryRadio = primaryRadio,
            overallScore = overallScore,
            evilTwinAlerts = evilTwinAlerts,
            spec24 = spec24,
            spec5 = spec5,
            spec6 = spec6,
        )

        val summarySentence = buildSummarySentence(
            overallScore = overallScore,
            primaryRadio = primaryRadio,
            totalRadios = annotatedRadios.size,
            evilTwinCount = evilTwinAlerts.size,
        )

        return EnvironmentHealthReport(
            state = EnvironmentHealthState.SCORED,
            overallScore0To100 = overallScore,
            headlineLabel = headline,
            summarySentence = summarySentence,
            subScores = subScores,
            insights = insights,
            evilTwinAlerts = evilTwinAlerts,
            securityAuditRadios = securityAuditRadios,
            best24GhzRecommendation = spec24.recommendation,
            best5GhzRecommendation = spec5.recommendation,
            best6GhzRecommendation = spec6.recommendation,
            computedAtTimestampMs = snapshot.timestampElapsedMs,
        )
    }

    private fun computeSignalCoverageScore(primaryRadio: BssidRadio): Int {
        val rssi = primaryRadio.smoothedRssiDbm.finiteCoerceIn(-100.0, -25.0, primaryRadio.rssiDbm.toDouble())
        var score = when {
            rssi >= -55.0 -> 90.0 + (rssi + 55.0) * (10.0 / 15.0)
            rssi >= -70.0 -> 70.0 + (rssi + 70.0) * (20.0 / 15.0)
            rssi >= -85.0 -> 35.0 + (rssi + 85.0) * (35.0 / 15.0)
            else -> (rssi + 100.0) * (35.0 / 15.0)
        }
        if (primaryRadio.wifiGeneration.isWifi6OrNewer &&
            primaryRadio.channelDescriptor.band != WifiBand.BAND_2_4_GHZ
        ) {
            score += 4.0
        }
        return score.finiteOr(50.0).roundToInt().coerceIn(0, 100)
    }

    private fun computeChannelCrowdingScore(
        primaryRadio: BssidRadio,
        primarySpec: SpectrumAnalysis,
        allRadios: List<BssidRadio>,
        spec24: SpectrumAnalysis,
        spec5: SpectrumAnalysis,
        spec6: SpectrumAnalysis,
    ): Int {
        val primaryCh = primaryRadio.channelDescriptor.primaryChannel
        val primaryChScore = primarySpec.channelScores
            .firstOrNull { it.channel == primaryCh }
            ?.score0To100
            ?.toDouble()
            ?: primaryRadio.channelCongestionScore0To100.toDouble()

        val populatedSpecs = listOf(spec24, spec5, spec6).filter { spec ->
            allRadios.any { it.channelDescriptor.band == spec.band }
        }
        val canonicalScores = populatedSpecs
            .flatMap { spec -> spec.channelScores.filter { it.isCanonical } }
            .map { it.score0To100 }
        val envCanonicalMean = if (canonicalScores.isNotEmpty()) {
            canonicalScores.average()
        } else {
            100.0
        }

        val qbssPenalty = primaryRadio.beaconDetails.bssLoad?.let {
            max(0, it.channelUtilizationPct - 50) * 0.5
        } ?: 0.0

        val raw = 0.65 * primaryChScore + 0.35 * envCanonicalMean - qbssPenalty
        return raw.finiteOr(80.0).roundToInt().coerceIn(0, 100)
    }

    private fun computeInterferencePurityScore(
        primaryRadio: BssidRadio,
        primarySpec: SpectrumAnalysis,
        allRadios: List<BssidRadio>,
    ): Int {
        var score = 100.0
        val primaryBreakdown = if (primarySpec.selectedOrConnectedBreakdown?.targetBssid == primaryRadio.bssid) {
            primarySpec.selectedOrConnectedBreakdown
        } else {
            primarySpec.perChannelBreakdowns[primaryRadio.channelDescriptor.primaryChannel]
        }

        val actionableAci = primaryBreakdown?.actionableAciCount ?: 0
        score -= min(54.0, actionableAci * 18.0)
        if (primaryBreakdown?.hasSevereAciWarning == true) {
            score -= 10.0
        }

        val offCenter24Count = allRadios.count {
            it.channelDescriptor.band == WifiBand.BAND_2_4_GHZ &&
                it.channelDescriptor.primaryChannel > 0 &&
                !it.channelDescriptor.isCanonicalPrimary &&
                it.rssiDbm >= -82
        }
        score -= min(32.0, offCenter24Count * 8.0)

        val radios24Count = allRadios.count { it.channelDescriptor.band == WifiBand.BAND_2_4_GHZ }
        if (radios24Count >= 2) {
            val wide24Count = allRadios.count {
                it.channelDescriptor.band == WifiBand.BAND_2_4_GHZ &&
                    it.channelDescriptor.width.widthMhz >= 40 &&
                    it.rssiDbm >= -80
            }
            score -= min(16.0, wide24Count * 8.0)
        }

        if (allRadios.any { it.beaconDetails.bssColor?.hasCollisionOnChannel == true }) {
            score -= 10.0
        }

        val snr = primaryRadio.estimatedSnrDb.finiteOr(30.0)
        if (snr < 25.0) {
            score -= (25.0 - snr) * 1.2
        }

        return score.finiteOr(80.0).roundToInt().coerceIn(0, 100)
    }

    private fun computeSecurityHygieneScore(
        primaryRadio: BssidRadio,
        allRadios: List<BssidRadio>,
        evilTwinAlerts: List<EvilTwinAlert>,
    ): Int {
        val primarySec = primaryRadio.security.score0To100.toDouble()
        val envAvgSec = allRadios.map { it.security.score0To100 }.average().finiteOr(primarySec)
        val weakCount = allRadios.count { it.security.grade.isWeakOrInsecure }
        val evilTwinPenalty = if (evilTwinAlerts.isNotEmpty()) 30.0 else 0.0
        val weakPenalty = if (primaryRadio.security.grade.isWeakOrInsecure) {
            min(28.0, weakCount * 8.0)
        } else {
            min(18.0, weakCount * 5.0)
        }

        val raw = 0.65 * primarySec + 0.35 * envAvgSec - weakPenalty - evilTwinPenalty
        return raw.finiteOr(50.0).roundToInt().coerceIn(0, 100)
    }

    private fun buildPrioritizedInsights(
        snapshot: RadarSnapshot,
        allRadios: List<BssidRadio>,
        primaryRadio: BssidRadio,
        overallScore: Int,
        evilTwinAlerts: List<EvilTwinAlert>,
        spec24: SpectrumAnalysis,
        spec5: SpectrumAnalysis,
        spec6: SpectrumAnalysis,
    ): List<ActionableInsight> {
        val rawInsights = ArrayList<ActionableInsight>()

        // 1. Evil-Twin Open Downgrade Alerts (CRITICAL)
        evilTwinAlerts.forEachIndexed { index, alert ->
            val suspectMac = alert.suspectOpenBssids.firstOrNull()
            val actionTarget = if (suspectMac != null) {
                InsightActionTarget.LaunchProximity(
                    ProximityTargetKey.BssidLock(bssid = suspectMac, ssidHint = alert.ssid)
                )
            } else {
                InsightActionTarget.NoAction
            }
            rawInsights.add(
                ActionableInsight(
                    id = "evil_twin_${index + 1}",
                    severity = InsightSeverity.CRITICAL,
                    category = InsightCategory.SECURITY,
                    title = "Potential Evil-Twin Clone on \"${alert.ssid}\"",
                    plainEnglishBody = alert.plainEnglishWarning,
                    metricBadge = "${alert.suspectRssiDbm} dBm Open Clone",
                    actionLabel = if (suspectMac != null) "Locate Rogue Radio" else null,
                    actionTarget = actionTarget,
                )
            )
        }

        // 2. Connected / Primary Weak Security (CRITICAL)
        if (primaryRadio.isConnected && primaryRadio.security.grade.isWeakOrInsecure) {
            rawInsights.add(
                ActionableInsight(
                    id = "connected_insecure",
                    severity = InsightSeverity.CRITICAL,
                    category = InsightCategory.SECURITY,
                    title = "Connected Wi-Fi Uses Weak or Open Security",
                    plainEnglishBody = "\"${primaryRadio.effectiveDisplayName}\" is graded ${primaryRadio.security.grade.badgeText} (${primaryRadio.security.protocol.displayLabel}). Upgrade router encryption to WPA2/WPA3 AES-CCMP to protect your wireless traffic.",
                    metricBadge = "Grade ${primaryRadio.security.grade.badgeText}",
                    actionLabel = "Inspect Security",
                    actionTarget = InsightActionTarget.OpenNetworkDetail(primaryRadio.bssid),
                )
            )
        }

        // 3. Adjacent-Channel Interference (ACI) Alert (WARNING)
        val offCenter24 = allRadios.filter {
            it.channelDescriptor.band == WifiBand.BAND_2_4_GHZ &&
                it.channelDescriptor.primaryChannel > 0 &&
                !it.channelDescriptor.isCanonicalPrimary &&
                it.rssiDbm >= -82
        }
        val severe5Aci = spec5.selectedOrConnectedBreakdown?.hasSevereAciWarning == true
        val severe24Aci = spec24.selectedOrConnectedBreakdown?.hasSevereAciWarning == true
        if (offCenter24.isNotEmpty() || severe24Aci) {
            val exampleCh = offCenter24.firstOrNull()?.channelDescriptor?.primaryChannel
                ?: spec24.selectedOrConnectedBreakdown?.aciInterferers?.firstOrNull()?.primaryChannel
                ?: 3
            val rec24Ch = spec24.recommendation?.recommendedPrimaryChannel ?: 11
            rawInsights.add(
                ActionableInsight(
                    id = "aci_collision_24ghz",
                    severity = InsightSeverity.WARNING,
                    category = InsightCategory.INTERFERENCE_ACI_CCI,
                    title = "Overlapping-Channel Noise on 2.4 GHz (Ch $exampleCh)",
                    plainEnglishBody = "Off-center 2.4 GHz routers on Channel $exampleCh bleed across neighboring channels and cause mid-packet collisions. Use Channel $rec24Ch (20 MHz) or move streaming devices to 5 GHz.",
                    metricBadge = "ACI Noise",
                    actionLabel = "View 2.4 GHz Spectrum",
                    actionTarget = InsightActionTarget.OpenSpectrumBand(
                        band = WifiBand.BAND_2_4_GHZ,
                        highlightChannel = rec24Ch,
                    ),
                )
            )
        } else if (severe5Aci) {
            val rec5Ch = spec5.recommendation?.recommendedPrimaryChannel ?: 36
            rawInsights.add(
                ActionableInsight(
                    id = "aci_collision_5ghz",
                    severity = InsightSeverity.WARNING,
                    category = InsightCategory.INTERFERENCE_ACI_CCI,
                    title = "Overlapping Bonded-Channel Contention on 5 GHz",
                    plainEnglishBody = "Wide bonded 5 GHz neighbor channels overlap your primary spectrum and cause uncoordinated packet retries. Switch to Channel $rec5Ch for cleaner airtime.",
                    metricBadge = "5 GHz Overlap",
                    actionLabel = "View 5 GHz Spectrum",
                    actionTarget = InsightActionTarget.OpenSpectrumBand(
                        band = WifiBand.BAND_5_GHZ,
                        highlightChannel = rec5Ch,
                    ),
                )
            )
        }

        // 4. Weak Neighbor Security Hygiene (WARNING)
        val weakNeighbors = allRadios.filter {
            (!it.isConnected) && it.security.grade.isWeakOrInsecure && !it.security.isEvilTwinSuspect
        }
        if (weakNeighbors.isNotEmpty()) {
            val strongestWeak = weakNeighbors.maxWith(
                compareBy<BssidRadio> { it.rssiDbm }.thenBy { it.bssid }
            )
            val count = weakNeighbors.size
            val noun = if (count == 1) "router nearby is" else "routers nearby are"
            rawInsights.add(
                ActionableInsight(
                    id = "weak_neighbor_security",
                    severity = InsightSeverity.WARNING,
                    category = InsightCategory.SECURITY,
                    title = "$count ${if (count == 1) "Network Uses" else "Networks Use"} Legacy or Open Security",
                    plainEnglishBody = "$count $noun broadcasting with ${strongestWeak.security.protocol.displayLabel} (including \"${strongestWeak.effectiveDisplayName}\"). Avoid connecting personal devices to unencrypted or WEP/TKIP signals.",
                    metricBadge = "Grade ${strongestWeak.security.grade.badgeText}",
                    actionLabel = "Inspect Network",
                    actionTarget = InsightActionTarget.OpenNetworkDetail(strongestWeak.bssid),
                )
            )
        }

        // 5. WPS PIN Exposure (WARNING)
        val wpsTarget = allRadios
            .filter { it.security.wpsEnabled && (it.isConnected || it.rssiDbm >= -70) }
            .maxWithOrNull(compareBy<BssidRadio> { it.isConnected }.thenBy { it.rssiDbm })
        if (wpsTarget != null) {
            rawInsights.add(
                ActionableInsight(
                    id = "wps_pin_exposed",
                    severity = InsightSeverity.WARNING,
                    category = InsightCategory.SECURITY,
                    title = "WPS Push-Button / PIN Setup Active on \"${wpsTarget.effectiveDisplayName}\"",
                    plainEnglishBody = "WPS PIN mode increases exposure to offline PIN brute-force attacks. Turn off WPS in your router's administration page to raise security.",
                    metricBadge = "WPS Active",
                    actionLabel = "Inspect Router",
                    actionTarget = InsightActionTarget.OpenNetworkDetail(wpsTarget.bssid),
                )
            )
        }

        // 6. Weak Primary Signal Coverage (WARNING)
        if (primaryRadio.rssiDbm <= -73) {
            val tier = SemanticFormatters.formatQualityPct(primaryRadio.signalQualityPct, primaryRadio.signalTier)
            rawInsights.add(
                ActionableInsight(
                    id = "weak_signal_coverage",
                    severity = InsightSeverity.WARNING,
                    category = InsightCategory.SIGNAL_COVERAGE,
                    title = "Weak Signal from \"${primaryRadio.effectiveDisplayName}\" (${primaryRadio.rssiDbm} dBm)",
                    plainEnglishBody = "Current signal quality is $tier, which can cause video buffering and dropped calls. Open the Proximity Radar to find a clearer router placement or dead-spot boundary.",
                    metricBadge = "${primaryRadio.rssiDbm} dBm",
                    actionLabel = "Open Proximity Radar",
                    actionTarget = InsightActionTarget.LaunchProximity(
                        ProximityTargetKey.BssidLock(
                            bssid = primaryRadio.bssid,
                            ssidHint = primaryRadio.effectiveDisplayName,
                        )
                    ),
                )
            )
        }

        // 7. Channel Optimization Recommendations (SUGGESTION)
        for ((spec, idKey) in listOf(spec24 to "channel_switch_24ghz", spec5 to "channel_switch_5ghz")) {
            val rec = spec.recommendation ?: continue
            val hasRadiosInBand = allRadios.any { it.channelDescriptor.band == spec.band }
            if (!hasRadiosInBand) continue

            val gain = rec.estimatedScoreGain
            val worstInBand = spec.channelScores.minOfOrNull { it.score0To100 } ?: 100
            val shouldSuggest = if (gain != null) {
                !rec.isCurrentChannelAlreadyOptimal && gain >= 15
            } else {
                rec.score0To100 - worstInBand >= 20
            }

            if (shouldSuggest) {
                rawInsights.add(
                    ActionableInsight(
                        id = idKey,
                        severity = InsightSeverity.SUGGESTION,
                        category = InsightCategory.CHANNEL_OPTIMIZATION,
                        title = "Cleaner ${spec.band.displayLabel} Airtime on Channel ${rec.recommendedPrimaryChannel}",
                        plainEnglishBody = rec.plainEnglishReason,
                        metricBadge = "${rec.score0To100} / 100",
                        actionLabel = "Inspect ${spec.band.displayLabel}",
                        actionTarget = InsightActionTarget.OpenSpectrumBand(
                            band = spec.band,
                            highlightChannel = rec.recommendedPrimaryChannel,
                        ),
                    )
                )
            }
        }

        // 8. Mesh Self-Contention or Fast-Roaming Tuning (SUGGESTION)
        val meshGroups = snapshot.groupedNetworks.filter { it.isMeshOrMultiAp }
        val selfContendingMesh = meshGroups.firstOrNull { group ->
            val byBandCh = group.radios
                .filter { it.channelDescriptor.primaryChannel > 0 }
                .groupBy { it.channelDescriptor.band to it.channelDescriptor.primaryChannel }
            byBandCh.values.any { sameChRadios ->
                sameChRadios.map { it.physicalClusterId }.distinct().size >= 2
            }
        }
        val nonKvrMesh = meshGroups.firstOrNull { group ->
            group.radios.none { it.beaconDetails.roaming.hasAnyFastRoaming }
        }
        if (selfContendingMesh != null) {
            rawInsights.add(
                ActionableInsight(
                    id = "mesh_self_contention",
                    severity = InsightSeverity.SUGGESTION,
                    category = InsightCategory.ROAMING_MESH,
                    title = "Mesh Nodes Sharing Same Channel on \"${selfContendingMesh.displayName}\"",
                    plainEnglishBody = "Multiple mesh nodes of \"${selfContendingMesh.displayName}\" transmit on the exact same channel and compete with each other for airtime. Stagger mesh node channels or use wired backhaul for higher speed.",
                    metricBadge = "${selfContendingMesh.physicalClusterCount} Mesh Nodes",
                    actionLabel = "Walk Mesh Coverage",
                    actionTarget = InsightActionTarget.LaunchProximity(
                        ProximityTargetKey.FollowMeshSsid(
                            ssid = selfContendingMesh.displayName,
                            preferredBssid = selfContendingMesh.strongestRadio.bssid,
                        )
                    ),
                )
            )
        } else if (nonKvrMesh != null) {
            rawInsights.add(
                ActionableInsight(
                    id = "mesh_fast_roaming",
                    severity = InsightSeverity.SUGGESTION,
                    category = InsightCategory.ROAMING_MESH,
                    title = "Enable Fast Roaming (802.11k/v/r) on \"${nonKvrMesh.displayName}\"",
                    plainEnglishBody = "\"${nonKvrMesh.displayName}\" spans ${nonKvrMesh.physicalClusterCount} access points without advertising 802.11k/v/r roaming assistance. Enabling Fast Roaming in router settings keeps calls smooth as you walk between rooms.",
                    metricBadge = "Standard Roaming",
                    actionLabel = "Test Mesh Walk",
                    actionTarget = InsightActionTarget.LaunchProximity(
                        ProximityTargetKey.FollowMeshSsid(
                            ssid = nonKvrMesh.displayName,
                            preferredBssid = nonKvrMesh.strongestRadio.bssid,
                        )
                    ),
                )
            )
        }

        // 9. BSS Color Collision or High QBSS Airtime Load (SUGGESTION)
        val colorCollidingRadio = allRadios.firstOrNull {
            it.beaconDetails.bssColor?.hasCollisionOnChannel == true
        }
        val busyBssRadio = allRadios
            .filter { (it.beaconDetails.bssLoad?.channelUtilizationPct ?: 0) >= 70 }
            .maxByOrNull { it.beaconDetails.bssLoad?.channelUtilizationPct ?: 0 }

        if (colorCollidingRadio != null) {
            val cCode = colorCollidingRadio.beaconDetails.bssColor?.colorCode ?: 1
            rawInsights.add(
                ActionableInsight(
                    id = "bss_color_collision",
                    severity = InsightSeverity.SUGGESTION,
                    category = InsightCategory.INTERFERENCE_ACI_CCI,
                    title = "Wi-Fi 6 BSS Color $cCode Collision on Channel ${colorCollidingRadio.channelDescriptor.primaryChannel}",
                    plainEnglishBody = "\"${colorCollidingRadio.effectiveDisplayName}\" shares Spatial Reuse Color $cCode with another router on the same channel. Rebooting or re-channeling the router picks a fresh color for better Wi-Fi 6 efficiency.",
                    metricBadge = "BSS Color $cCode",
                    actionLabel = "View Details",
                    actionTarget = InsightActionTarget.OpenNetworkDetail(colorCollidingRadio.bssid),
                )
            )
        } else if (busyBssRadio != null) {
            val utilPct = busyBssRadio.beaconDetails.bssLoad?.channelUtilizationPct ?: 70
            rawInsights.add(
                ActionableInsight(
                    id = "qbss_high_airtime",
                    severity = InsightSeverity.SUGGESTION,
                    category = InsightCategory.INTERFERENCE_ACI_CCI,
                    title = "High Channel Airtime Load ($utilPct% Busy)",
                    plainEnglishBody = "\"${busyBssRadio.effectiveDisplayName}\" reports $utilPct% of channel airtime busy via 802.11k BSS Load. Switching to a wide-open 5 GHz channel will reduce latency spikes.",
                    metricBadge = "$utilPct% Busy",
                    actionLabel = "View Details",
                    actionTarget = InsightActionTarget.OpenNetworkDetail(busyBssRadio.bssid),
                )
            )
        }

        // 10. Out-of-Band 6 GHz RNR Discovery on Dual-Band Hardware (GOOD_INFO)
        if (!snapshot.radioCapabilities.has6Ghz && spec6.colocated6GhzNeighborsFromRnr.isNotEmpty()) {
            val firstNb = spec6.colocated6GhzNeighborsFromRnr.first()
            val count = spec6.colocated6GhzNeighborsFromRnr.size
            val noun = if (count == 1) "radio" else "radios"
            rawInsights.add(
                ActionableInsight(
                    id = "rnr_6ghz_discovery",
                    severity = InsightSeverity.GOOD_INFO,
                    category = InsightCategory.HARDWARE_CAPABILITY,
                    title = "Discovered $count Co-Located 6 GHz $noun via Beacon Reports",
                    plainEnglishBody = "Although ${snapshot.radioCapabilities.deviceModel} operates on 2.4 and 5 GHz Wi-Fi 6, nearby tri-band beacons advertise 6 GHz service (such as Channel ${firstNb.channelNumber}, ${firstNb.widthMhz} MHz).",
                    metricBadge = "6 GHz RNR",
                    actionLabel = "Inspect 6 GHz Info",
                    actionTarget = InsightActionTarget.OpenSpectrumBand(
                        band = WifiBand.BAND_6_GHZ,
                        highlightChannel = firstNb.channelNumber,
                    ),
                )
            )
        }

        // 11. Healthy Environment Confirmation (GOOD_INFO)
        val hasCriticalOrWarning = rawInsights.any {
            it.severity == InsightSeverity.CRITICAL || it.severity == InsightSeverity.WARNING
        }
        if (!hasCriticalOrWarning || overallScore >= 85) {
            rawInsights.add(
                ActionableInsight(
                    id = "healthy_rf_summary",
                    severity = InsightSeverity.GOOD_INFO,
                    category = InsightCategory.SIGNAL_COVERAGE,
                    title = "Strong & Clean Wireless Link on \"${primaryRadio.effectiveDisplayName}\"",
                    plainEnglishBody = "\"${primaryRadio.effectiveDisplayName}\" delivers ${primaryRadio.rssiDbm} dBm (${primaryRadio.signalTier.shortLabel}) on ${primaryRadio.channelDescriptor.band.displayLabel} Channel ${primaryRadio.channelDescriptor.primaryChannel} with ${primaryRadio.security.protocol.displayLabel}.",
                    metricBadge = "$overallScore / 100",
                    actionLabel = "Open Proximity Radar",
                    actionTarget = InsightActionTarget.LaunchProximity(
                        ProximityTargetKey.BssidLock(
                            bssid = primaryRadio.bssid,
                            ssidHint = primaryRadio.effectiveDisplayName,
                        )
                    ),
                )
            )
        }

        return rawInsights
            .distinctBy { it.id }
            .sortedWith(
                compareBy<ActionableInsight> { it.severity.ordinal }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.id }
            )
    }

    private fun buildSummarySentence(
        overallScore: Int,
        primaryRadio: BssidRadio,
        totalRadios: Int,
        evilTwinCount: Int,
    ): String {
        val radioNoun = if (totalRadios == 1) "radio" else "radios"
        if (evilTwinCount > 0) {
            return "Security attention needed: detected an unencrypted Open clone alongside $totalRadios nearby wireless $radioNoun."
        }
        return when {
            overallScore >= 80 ->
                "Clean wireless spectrum across $totalRadios detected $radioNoun, led by \"${primaryRadio.effectiveDisplayName}\" at ${primaryRadio.rssiDbm} dBm (${primaryRadio.security.protocol.displayLabel})."
            overallScore >= 60 ->
                "Good wireless environment across $totalRadios detected $radioNoun with minor channel or security tuning available."
            overallScore >= 40 ->
                "Moderate wireless crowding or legacy security detected across $totalRadios nearby $radioNoun — review channel recommendations below."
            else ->
                "High channel interference or security risk across $totalRadios detected $radioNoun — follow the top action cards below to improve stability."
        }
    }
}
