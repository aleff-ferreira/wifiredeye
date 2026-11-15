package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ActionableInsight
import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssColorInfo
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.CciAciBreakdown
import com.a55.wifiradar.core.model.ChannelInterferer
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DiagnosticExportDocument
import com.a55.wifiradar.core.model.EhtMloInfo
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.EvilTwinAlert
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.MloLinkInfo
import com.a55.wifiradar.core.model.PhysicalApCluster
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RoamEvent
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WpsDeviceInfo
import java.util.Locale

/**
 * Dual-layer MAC/BSSID privacy redactor (`F17`):
 * 1. Structural deep-copy transformation across every MAC-bearing field and nested sealed key in
 *    `DiagnosticExportDocument`.
 * 2. Idempotent regex text scrubber masking any colon- or hyphen-delimited 6-octet MAC embedded
 *    inside free-form titles, subtitles, warnings, or summary strings (`AA:BB:CC:XX:XX:01`).
 */
internal object DiagnosticPrivacyRedactor {

    private val EMBEDDED_MAC_REGEX = Regex(
        """(?<![0-9A-Fa-f])([0-9A-Fa-f]{2})(?:[:-]([0-9A-Fa-f]{2})){5}(?![0-9A-Fa-f])"""
    )

    fun redactDocument(doc: DiagnosticExportDocument): DiagnosticExportDocument =
        doc.copy(
            redactedMacs = true,
            healthReport = redactHealthReport(doc.healthReport),
            spectrum24Ghz = redactSpectrumAnalysis(doc.spectrum24Ghz),
            spectrum5Ghz = redactSpectrumAnalysis(doc.spectrum5Ghz),
            spectrum6Ghz = redactSpectrumAnalysis(doc.spectrum6Ghz),
            connectedWifi = doc.connectedWifi?.let(::redactConnectedWifi),
            detectedRadios = doc.detectedRadios.map(::redactRadio),
            groupedNetworks = doc.groupedNetworks.map(::redactEssGroup),
            physicalClusters = doc.physicalClusters.map(::redactPhysicalCluster),
            roamEvents = doc.roamEvents.map(::redactRoamEvent),
            proximityTrajectory = doc.proximityTrajectory.map(::redactProximityTelemetry),
        )

    fun scrubMacText(text: String): String =
        EMBEDDED_MAC_REGEX.replace(text) { match ->
            MacAddressUtils.redactMac(match.value)
        }

    private fun redactKeyIdentifier(key: String): String = when {
        key.startsWith("ess:hidden:") ->
            "ess:hidden:${MacAddressUtils.redactMac(key.removePrefix("ess:hidden:"))}"
        key.startsWith("cluster:mld:") ->
            "cluster:mld:${MacAddressUtils.redactMac(key.removePrefix("cluster:mld:"))}"
        key.startsWith("cluster:") -> {
            val tail = key.removePrefix("cluster:")
            if (MacAddressUtils.isValidMac(tail)) {
                "cluster:${MacAddressUtils.redactMac(tail)}"
            } else {
                scrubMacText(key)
            }
        }
        else -> scrubMacText(key)
    }

    private fun redactHealthReport(report: EnvironmentHealthReport): EnvironmentHealthReport =
        report.copy(
            headlineLabel = scrubMacText(report.headlineLabel),
            summarySentence = scrubMacText(report.summarySentence),
            insights = report.insights.map(::redactInsight),
            evilTwinAlerts = report.evilTwinAlerts.map(::redactEvilTwinAlert),
            securityAuditRadios = report.securityAuditRadios.map(::redactRadio),
            best24GhzRecommendation = report.best24GhzRecommendation?.let(::redactRecommendation),
            best5GhzRecommendation = report.best5GhzRecommendation?.let(::redactRecommendation),
            best6GhzRecommendation = report.best6GhzRecommendation?.let(::redactRecommendation),
        )

    private fun redactInsight(insight: ActionableInsight): ActionableInsight =
        insight.copy(
            title = scrubMacText(insight.title),
            plainEnglishBody = scrubMacText(insight.plainEnglishBody),
            metricBadge = insight.metricBadge?.let(::scrubMacText),
            actionLabel = insight.actionLabel?.let(::scrubMacText),
            actionTarget = redactActionTarget(insight.actionTarget),
        )

    private fun redactActionTarget(target: InsightActionTarget): InsightActionTarget = when (target) {
        is InsightActionTarget.OpenNetworkDetail ->
            InsightActionTarget.OpenNetworkDetail(MacAddressUtils.redactMac(target.bssid))
        is InsightActionTarget.LaunchProximity ->
            InsightActionTarget.LaunchProximity(redactProximityTargetKey(target.targetKey))
        is InsightActionTarget.OpenSpectrumBand -> target
        InsightActionTarget.NoAction -> InsightActionTarget.NoAction
    }

    private fun redactEvilTwinAlert(alert: EvilTwinAlert): EvilTwinAlert =
        alert.copy(
            ssid = scrubMacText(alert.ssid),
            legitimateBssids = alert.legitimateBssids.map(MacAddressUtils::redactMac),
            suspectOpenBssids = alert.suspectOpenBssids.map(MacAddressUtils::redactMac),
            plainEnglishWarning = scrubMacText(alert.plainEnglishWarning),
        )

    private fun redactSpectrumAnalysis(spec: SpectrumAnalysis): SpectrumAnalysis =
        spec.copy(
            curves = spec.curves.map(::redactSpectralCurve),
            recommendation = spec.recommendation?.let(::redactRecommendation),
            selectedOrConnectedBreakdown = spec.selectedOrConnectedBreakdown?.let(::redactBreakdown),
            perChannelBreakdowns = spec.perChannelBreakdowns.mapValues { (_, bd) -> redactBreakdown(bd) },
            colocated6GhzNeighborsFromRnr = spec.colocated6GhzNeighborsFromRnr.map(::redactColocatedNeighbor),
            hardwareExplanationMessage = spec.hardwareExplanationMessage?.let(::scrubMacText),
        )

    private fun redactSpectralCurve(curve: SpectralCurve): SpectralCurve =
        curve.copy(
            bssid = MacAddressUtils.redactMac(curve.bssid),
            ssid = scrubMacText(curve.ssid),
            displayLabel = scrubMacText(curve.displayLabel),
        )

    private fun redactRecommendation(rec: ChannelRecommendation): ChannelRecommendation =
        rec.copy(
            plainEnglishReason = scrubMacText(rec.plainEnglishReason),
        )

    private fun redactBreakdown(bd: CciAciBreakdown): CciAciBreakdown =
        bd.copy(
            targetBssid = bd.targetBssid?.let(MacAddressUtils::redactMac),
            cciInterferers = bd.cciInterferers.map(::redactInterferer),
            aciInterferers = bd.aciInterferers.map(::redactInterferer),
            cciPlainEnglishSummary = scrubMacText(bd.cciPlainEnglishSummary),
            aciPlainEnglishSummary = scrubMacText(bd.aciPlainEnglishSummary),
        )

    private fun redactInterferer(interferer: ChannelInterferer): ChannelInterferer =
        interferer.copy(
            bssid = MacAddressUtils.redactMac(interferer.bssid),
            ssid = scrubMacText(interferer.ssid),
            displayLabel = scrubMacText(interferer.displayLabel),
        )

    private fun redactConnectedWifi(conn: ConnectedWifiObservation): ConnectedWifiObservation =
        conn.copy(
            bssid = MacAddressUtils.redactMac(conn.bssid),
            ssid = conn.ssid?.let(::scrubMacText),
            ipAddressV4 = conn.ipAddressV4?.let(::scrubMacText),
            apMldMacAddress = conn.apMldMacAddress?.let(MacAddressUtils::redactMac),
            mloLinks = conn.mloLinks.map(::redactMloLink),
        )

    private fun redactMloLink(link: MloLinkInfo): MloLinkInfo =
        link.copy(
            bssid = link.bssid?.let(MacAddressUtils::redactMac),
        )

    private fun redactRadio(radio: BssidRadio): BssidRadio =
        radio.copy(
            bssid = MacAddressUtils.redactMac(radio.bssid),
            ssid = redactDecodedSsid(radio.ssid),
            customNickname = radio.customNickname?.let(::scrubMacText),
            effectiveDisplayName = scrubMacText(radio.effectiveDisplayName),
            security = redactSecurity(radio.security),
            beaconDetails = redactBeaconDetails(radio.beaconDetails),
            physicalClusterId = redactKeyIdentifier(radio.physicalClusterId),
            apMldMacAddress = radio.apMldMacAddress?.let(MacAddressUtils::redactMac),
            mloLinks = radio.mloLinks.map(::redactMloLink),
            connectedLinkInfo = radio.connectedLinkInfo?.let(::redactConnectedWifi),
        )

    private fun redactDecodedSsid(ssid: DecodedSsid): DecodedSsid {
        val scrubbedDisplay = scrubMacText(ssid.displayName)
        val scrubbedUtf8 = ssid.rawUtf8OrNull?.let(::scrubMacText)
        val scrubbedHex = if (scrubbedUtf8 != null && scrubbedUtf8 != ssid.rawUtf8OrNull) {
            scrubbedUtf8.encodeToByteArray().joinToString("") { b ->
                String.format(Locale.US, "%02x", b.toInt() and 0xFF)
            }
        } else {
            ssid.rawHex
        }
        return ssid.copy(
            displayName = scrubbedDisplay,
            rawUtf8OrNull = scrubbedUtf8,
            rawHex = scrubbedHex,
        )
    }

    private fun redactSecurity(sec: SecurityAssessment): SecurityAssessment =
        sec.copy(
            plainEnglishLabel = scrubMacText(sec.plainEnglishLabel),
            warnings = sec.warnings.map(::scrubMacText),
        )

    private fun redactBeaconDetails(details: BeaconIeDetails): BeaconIeDetails =
        details.copy(
            bssColor = details.bssColor?.let(::redactBssColor),
            ehtMlo = details.ehtMlo?.let(::redactEhtMlo),
            colocated6GhzNeighbors = details.colocated6GhzNeighbors.map(::redactColocatedNeighbor),
            wpsDevice = details.wpsDevice?.let(::redactWpsDevice),
        )

    private fun redactBssColor(color: BssColorInfo): BssColorInfo =
        color.copy(
            collidingBssids = color.collidingBssids.map(MacAddressUtils::redactMac),
        )

    private fun redactEhtMlo(mlo: EhtMloInfo): EhtMloInfo =
        mlo.copy(
            apMldMacAddress = mlo.apMldMacAddress?.let(MacAddressUtils::redactMac),
        )

    private fun redactColocatedNeighbor(nb: Colocated6GhzNeighbor): Colocated6GhzNeighbor =
        nb.copy(
            sourceBssid = MacAddressUtils.redactMac(nb.sourceBssid),
            sourceSsid = scrubMacText(nb.sourceSsid),
            neighborBssid = nb.neighborBssid?.let(MacAddressUtils::redactMac),
        )

    private fun redactWpsDevice(wps: WpsDeviceInfo): WpsDeviceInfo =
        wps.copy(
            manufacturer = wps.manufacturer?.let(::scrubMacText),
            modelName = wps.modelName?.let(::scrubMacText),
            modelNumber = wps.modelNumber?.let(::scrubMacText),
            deviceName = wps.deviceName?.let(::scrubMacText),
            advertisedApHostname = wps.advertisedApHostname?.let(::scrubMacText),
        )

    private fun redactEssGroup(group: EssNetworkGroup): EssNetworkGroup =
        group.copy(
            groupKey = redactKeyIdentifier(group.groupKey),
            ssid = redactDecodedSsid(group.ssid),
            displayName = scrubMacText(group.displayName),
            radios = group.radios.map(::redactRadio),
            physicalClusterIds = group.physicalClusterIds.map(::redactKeyIdentifier),
            strongestRadio = redactRadio(group.strongestRadio),
            worstSecurityAssessment = redactSecurity(group.worstSecurityAssessment),
            bestSecurityAssessment = redactSecurity(group.bestSecurityAssessment),
        )

    private fun redactPhysicalCluster(cluster: PhysicalApCluster): PhysicalApCluster =
        cluster.copy(
            clusterId = redactKeyIdentifier(cluster.clusterId),
            primaryLabel = scrubMacText(cluster.primaryLabel),
            wpsDeviceInfo = cluster.wpsDeviceInfo?.let(::redactWpsDevice),
            apMldMacAddress = cluster.apMldMacAddress?.let(MacAddressUtils::redactMac),
            radios = cluster.radios.map(::redactRadio),
            colocated6GhzNeighbors = cluster.colocated6GhzNeighbors.map(::redactColocatedNeighbor),
        )

    private fun redactRoamEvent(event: RoamEvent): RoamEvent =
        event.copy(
            ssid = scrubMacText(event.ssid),
            previousBssid = MacAddressUtils.redactMac(event.previousBssid),
            newBssid = MacAddressUtils.redactMac(event.newBssid),
        )

    private fun redactProximityTargetKey(key: ProximityTargetKey): ProximityTargetKey = when (key) {
        is ProximityTargetKey.BssidLock -> ProximityTargetKey.BssidLock(
            bssid = MacAddressUtils.redactMac(key.bssid),
            ssidHint = key.ssidHint?.let(::scrubMacText),
        )
        is ProximityTargetKey.FollowMeshSsid -> ProximityTargetKey.FollowMeshSsid(
            ssid = scrubMacText(key.ssid),
            preferredBssid = key.preferredBssid?.let(MacAddressUtils::redactMac),
        )
    }

    private fun redactProximityTelemetry(telemetry: ProximityTelemetry): ProximityTelemetry =
        telemetry.copy(
            targetKey = redactProximityTargetKey(telemetry.targetKey),
            resolvedBssid = telemetry.resolvedBssid?.let(MacAddressUtils::redactMac),
            displayTitle = scrubMacText(telemetry.displayTitle),
            displaySubtitle = scrubMacText(telemetry.displaySubtitle),
            vendorLabel = telemetry.vendorLabel?.let(::scrubMacText),
        )
}
