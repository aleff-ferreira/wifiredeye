package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.DiagnosticExportDocument
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiBand
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Deterministic JSON (`DiagnosticExportDocument`) and zero-jargon Markdown diagnostic
 * report exporter with optional privacy MAC redaction (`F17`).
 */
object DiagnosticExporter {

    private val exportJson = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun buildDocument(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean = false,
        proximityHistory: List<ProximityTelemetry> = emptyList(),
    ): DiagnosticExportDocument {
        val spec24 = SpectrumAnalyzer.analyze(
            networks = snapshot.radios,
            band = WifiBand.BAND_2_4_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )
        val spec5 = SpectrumAnalyzer.analyze(
            networks = snapshot.radios,
            band = WifiBand.BAND_5_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )
        val spec6 = SpectrumAnalyzer.analyze(
            networks = snapshot.radios,
            band = WifiBand.BAND_6_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            radioCapabilities = snapshot.radioCapabilities,
        )

        val generatedEpochMs = if (snapshot.timestampEpochMs != 0L) {
            snapshot.timestampEpochMs
        } else {
            snapshot.timestampElapsedMs
        }

        val rawDoc = DiagnosticExportDocument(
            schemaVersion = "1.0",
            generatedAtEpochMs = generatedEpochMs,
            redactedMacs = redactMacs,
            deviceCapabilities = snapshot.radioCapabilities,
            healthReport = health,
            spectrum24Ghz = spec24,
            spectrum5Ghz = spec5,
            spectrum6Ghz = spec6,
            connectedWifi = snapshot.connectedWifi,
            detectedRadios = snapshot.radios,
            groupedNetworks = snapshot.groupedNetworks,
            physicalClusters = snapshot.physicalClusters,
            roamEvents = snapshot.roamEvents,
            proximityTrajectory = proximityHistory,
        )

        return if (redactMacs) {
            DiagnosticPrivacyRedactor.redactDocument(rawDoc)
        } else {
            rawDoc
        }
    }

    fun exportJson(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean = false,
        proximityHistory: List<ProximityTelemetry> = emptyList(),
    ): String {
        val doc = buildDocument(
            snapshot = snapshot,
            health = health,
            redactMacs = redactMacs,
            proximityHistory = proximityHistory,
        )
        val encoded = exportJson.encodeToString(doc)
        return if (redactMacs) {
            DiagnosticPrivacyRedactor.scrubMacText(encoded)
        } else {
            encoded
        }
    }

    fun exportMarkdown(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean = false,
        proximityHistory: List<ProximityTelemetry> = emptyList(),
    ): String {
        val doc = buildDocument(
            snapshot = snapshot,
            health = health,
            redactMacs = redactMacs,
            proximityHistory = proximityHistory,
        )
        val sb = StringBuilder(4096)

        val caps = doc.deviceCapabilities
        val supportedBands = buildList {
            if (caps.has24Ghz) add("2.4 GHz")
            if (caps.has5Ghz) add("5 GHz")
            if (caps.has6Ghz) add("6 GHz")
            if (caps.has60Ghz) add("60 GHz")
        }.joinToString(", ").ifEmpty { "None" }

        sb.appendLine("# Wi-Fi Radar Diagnostic Report")
        sb.appendLine()
        sb.appendLine("- **Device Model**: `${mdSafe(caps.deviceModel)}` (${mdSafe(caps.manufacturer)}, SDK ${caps.sdkInt})")
        sb.appendLine("- **Supported Radio Bands**: $supportedBands · ${SemanticFormatters.formatWifiGeneration(caps.maxSupportedWifiGeneration)}")
        sb.appendLine("- **Detected Radios**: ${doc.detectedRadios.size} (${doc.groupedNetworks.size} networks across ${doc.physicalClusters.size} physical routers)")
        sb.appendLine("- **MAC Privacy Redaction**: ${if (doc.redactedMacs) "Enabled (Middle Octets Masked)" else "Off (Full Hardware Addresses)"}")
        sb.appendLine("- **Snapshot Timestamp**: ${doc.generatedAtEpochMs} ms")
        sb.appendLine()

        // Section 1: Wireless Environment Health
        sb.appendLine("## Wireless Environment Health")
        sb.appendLine()
        sb.appendLine("**${mdSafe(doc.healthReport.headlineLabel)}**")
        sb.appendLine()
        sb.appendLine(mdSafe(doc.healthReport.summarySentence))
        sb.appendLine()

        val subs = doc.healthReport.subScores
        if (doc.healthReport.state == EnvironmentHealthState.SCORED && subs != null) {
            sb.appendLine("| Health Dimension | Score | Rating |")
            sb.appendLine("| --- | --- | --- |")
            sb.appendLine("| Signal Coverage | ${subs.signalCoverage0To100} / 100 | ${SemanticFormatters.congestionQualifier(subs.signalCoverage0To100)} |")
            sb.appendLine("| Channel Crowding | ${subs.channelCrowding0To100} / 100 | ${SemanticFormatters.congestionQualifier(subs.channelCrowding0To100)} |")
            sb.appendLine("| Interference Purity | ${subs.interferencePurity0To100} / 100 | ${SemanticFormatters.congestionQualifier(subs.interferencePurity0To100)} |")
            sb.appendLine("| Security Hygiene | ${subs.securityHygiene0To100} / 100 | ${SemanticFormatters.congestionQualifier(subs.securityHygiene0To100)} |")
            sb.appendLine()
        }

        // Section 2: Connected Wi-Fi Link
        val conn = doc.connectedWifi
        if (conn != null) {
            sb.appendLine("## Connected Wi-Fi Link")
            sb.appendLine()
            val connName = conn.ssid?.takeIf { it.isNotBlank() } ?: "Connected Network"
            val (connBand, connCh) = ChannelAndFrequencyResolver.freqToBandAndChannel(conn.frequencyMhz)
            val chText = if (connCh > 0) "Ch $connCh (${conn.frequencyMhz} MHz)" else "${conn.frequencyMhz} MHz"
            sb.appendLine("- **Network**: ${mdSafe(connName)} (`${conn.bssid}`)")
            sb.appendLine("- **Signal**: ${SemanticFormatters.formatRssiForDiscovery(conn.rssiDbm)}")
            sb.appendLine("- **Channel**: ${connBand.displayLabel} · $chText")
            sb.appendLine("- **Link Speed**: ${conn.txLinkSpeedMbps} Mbps Tx / ${conn.rxLinkSpeedMbps} Mbps Rx")
            sb.appendLine()
        }

        // Section 3: Best Channel Recommendations
        sb.appendLine("## Best Channel Recommendations")
        sb.appendLine()
        for (spec in listOf(doc.spectrum24Ghz, doc.spectrum5Ghz, doc.spectrum6Ghz)) {
            val rec = spec.recommendation
            if (rec != null) {
                val starStr = String.format(Locale.US, "%.1f", rec.starRating0To5)
                sb.appendLine(
                    "- **${spec.band.displayLabel}**: Channel ${rec.recommendedPrimaryChannel} (${rec.recommendedWidth.displayLabel}) — ${rec.score0To100} / 100 (${starStr}★) · ${mdSafe(rec.plainEnglishReason)}"
                )
            }
            if (!spec.isBandSupportedByHardware && spec.hardwareExplanationMessage != null) {
                sb.appendLine("  - *Hardware Note*: ${mdSafe(spec.hardwareExplanationMessage!!)}")
            }
        }
        sb.appendLine()

        // Section 4: Security & Actionable Insights
        if (doc.healthReport.evilTwinAlerts.isNotEmpty() || doc.healthReport.insights.isNotEmpty()) {
            sb.appendLine("## Security & Actionable Insights")
            sb.appendLine()
            for (alert in doc.healthReport.evilTwinAlerts) {
                sb.appendLine("- **[Action Needed · Evil-Twin Alert]** ${mdSafe(alert.plainEnglishWarning)}")
            }
            for (insight in doc.healthReport.insights) {
                val badgePart = insight.metricBadge?.let { " (`${mdSafe(it)}`)" }.orEmpty()
                sb.appendLine(
                    "- **[${insight.severity.displayLabel} · ${insight.category.displayLabel}] ${mdSafe(insight.title)}**$badgePart: ${mdSafe(insight.plainEnglishBody)}"
                )
            }
            sb.appendLine()
        }

        // Section 5: Detected Wireless Networks
        sb.appendLine("## Detected Wireless Networks")
        sb.appendLine()
        if (doc.detectedRadios.isEmpty()) {
            sb.appendLine("_No wireless networks detected in this snapshot._")
            sb.appendLine()
        } else {
            sb.appendLine("| Network | BSSID | Vendor | Band & Channel | Signal | Est. Range | Generation | Security | Load & Roaming |")
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
            for (radio in doc.detectedRadios) {
                val connectedPrefix = if (radio.isConnected) "★ " else ""
                val netLabel = "$connectedPrefix${mdCell(radio.effectiveDisplayName)}"
                val chSummary = mdCell(
                    "${radio.channelDescriptor.band.displayLabel} · ${
                        SemanticFormatters.formatChannelSummary(
                            radio.channelDescriptor,
                            radio.channelCongestionScore0To100,
                        )
                    }"
                )
                val sigText = mdCell(SemanticFormatters.formatRssiForDiscovery(radio.rssiDbm))
                val distText = String.format(Locale.US, "~%.1f m", radio.estimatedDistanceMeters.finiteOr(1.0))
                val genText = mdCell("${radio.wifiGeneration.shortBadge} · ${radio.beaconDetails.phyCapability.mimoLabel}")
                val secText = mdCell("${radio.security.grade.badgeText} · ${radio.security.protocol.displayLabel}")
                val loadPart = radio.beaconDetails.bssLoad?.let {
                    "${it.stationCount} clients (${it.channelUtilizationPct}% busy)"
                } ?: "Load N/A"
                val roamPart = SemanticFormatters.formatRoamingSummary(radio.beaconDetails.roaming)
                sb.appendLine(
                    "| $netLabel | `${radio.bssid}` | ${mdCell(radio.vendorInfo.displayLabel)} | $chSummary | $sigText | $distText | $genText | $secText | ${mdCell("$loadPart · $roamPart")} |"
                )
            }
            sb.appendLine()
        }

        // Co-Located 6 GHz Neighbors (RNR)
        val rnrList = doc.spectrum6Ghz.colocated6GhzNeighborsFromRnr
        if (rnrList.isNotEmpty()) {
            sb.appendLine("### Co-Located 6 GHz Neighbors (Beacon RNR)")
            sb.appendLine()
            sb.appendLine("| Advertised By | Source SSID | 6 GHz Neighbor BSSID | Channel | Width | PSC |")
            sb.appendLine("| --- | --- | --- | --- | --- | --- |")
            for (nb in rnrList) {
                val nbBssid = nb.neighborBssid?.let { "`$it`" } ?: "Not advertised"
                val pscText = if (nb.isPscChannel) "Yes (PSC)" else "No"
                sb.appendLine(
                    "| `${nb.sourceBssid}` | ${mdCell(nb.sourceSsid)} | $nbBssid | Ch ${nb.channelNumber} (${nb.centerFreqMhz} MHz) | ${nb.widthMhz} MHz | $pscText |"
                )
            }
            sb.appendLine()
        }

        // Section 6: Roaming Events
        if (doc.roamEvents.isNotEmpty()) {
            sb.appendLine("## Roaming Events")
            sb.appendLine()
            for (ev in doc.roamEvents) {
                val deltaStr = String.format(Locale.US, "%+d dB", ev.rssiDeltaDb)
                val bandSwitchNote = if (ev.isBandSwitch) " (Band Switch)" else ""
                sb.appendLine(
                    "- **t=${ev.timestampElapsedMs} ms** · `${mdSafe(ev.ssid)}`: `${ev.previousBssid}` (${ev.previousBand.displayLabel} Ch ${ev.previousChannel}, ${ev.previousRssiDbm} dBm) → `${ev.newBssid}` (${ev.newBand.displayLabel} Ch ${ev.newChannel}, ${ev.newRssiDbm} dBm, $deltaStr)$bandSwitchNote"
                )
            }
            sb.appendLine()
        }

        // Section 7: Proximity Homing Trajectory
        if (doc.proximityTrajectory.isNotEmpty()) {
            sb.appendLine("## Proximity Homing Trajectory")
            sb.appendLine()
            sb.appendLine("| Sample | Target | Status | Smoothed RSSI | Estimated Distance | Trend | Stability |")
            sb.appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            doc.proximityTrajectory.forEachIndexed { idx, p ->
                val targetStr = p.resolvedBssid?.let { "${mdCell(p.displayTitle)} (`$it`)" }
                    ?: mdCell(p.displayTitle)
                val rssiStr = mdCell(SemanticFormatters.formatRssiForProximity(p.smoothedRssiDbm))
                val distStr = mdCell(p.distanceEstimate?.plainEnglishFormatted ?: "Unavailable")
                val trendStr = mdCell(SemanticFormatters.formatVelocityTrend(p.velocityTrend, p.velocitySlopeDbPerSec))
                val stabStr = mdCell(SemanticFormatters.formatStability(p.stabilityPct, p.stdDevDb))
                sb.appendLine(
                    "| #${idx + 1} | $targetStr | ${mdCell(p.lockStatus.displayLabel)} | $rssiStr | $distStr | $trendStr | $stabStr |"
                )
            }
            sb.appendLine()
        }

        val rendered = sb.toString()
        return if (redactMacs) {
            DiagnosticPrivacyRedactor.scrubMacText(rendered)
        } else {
            rendered
        }
    }

    private fun mdSafe(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim()

    private fun mdCell(text: String): String =
        mdSafe(text).replace("|", "\\|")
}
