package com.a55.wifiradar.feature.insights

import com.a55.wifiradar.core.model.ActionableInsight
import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.EvilTwinAlert
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.HealthSubScores
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.InsightCategory
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PhyCapability
import com.a55.wifiradar.core.model.PmfState
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityFeedbackController
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RoamEvent
import com.a55.wifiradar.core.model.RoamingCapabilities
import com.a55.wifiradar.core.model.RssiSample
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.SecurityFamily
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.VendorInfo
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeInsightsWifiRadarRepository(
    initialSnapshot: RadarSnapshot = createPopulatedSnapshot(),
    initialHealth: EnvironmentHealthReport = createPopulatedHealthReport(initialSnapshot),
    initialReadiness: ScannerReadinessState = ScannerReadinessState.Ready(),
) : WifiRadarRepository {

    private val _radarSnapshot = MutableStateFlow(initialSnapshot)
    override val radarSnapshot: StateFlow<RadarSnapshot> = _radarSnapshot

    private val _readinessState = MutableStateFlow(initialReadiness)
    override val readinessState: StateFlow<ScannerReadinessState> = _readinessState

    private val _environmentHealth = MutableStateFlow(initialHealth)
    override val environmentHealth: StateFlow<EnvironmentHealthReport> = _environmentHealth

    override val feedbackController: ProximityFeedbackController = object : ProximityFeedbackController {
        override val feedbackSettings = MutableStateFlow(GeigerFeedbackSettings())
        override fun setHapticsEnabled(enabled: Boolean) {}
        override fun setAudioEnabled(enabled: Boolean) {}
        override fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) {}
        override fun stopAll() {}
    }

    var manualScanCount = 0
    var refreshReadinessCount = 0

    fun setState(
        snapshot: RadarSnapshot = _radarSnapshot.value,
        health: EnvironmentHealthReport = _environmentHealth.value,
        readiness: ScannerReadinessState = _readinessState.value,
    ) {
        _radarSnapshot.value = snapshot
        _environmentHealth.value = health
        _readinessState.value = readiness
    }

    override fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
    ): Flow<ProximityTelemetry> = MutableStateFlow(
        ProximityTelemetry(
            targetKey = targetKey,
            displayTitle = "Target",
            displaySubtitle = "Sub",
            lockStatus = SignalLockStatus.LOCKED,
        )
    )

    override fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): Flow<SpectrumAnalysis> = MutableStateFlow(
        SpectrumAnalysis(band = band, minFreqMhz = 2400, maxFreqMhz = 2495)
    )

    override fun requestManualScan(burstMode: Boolean): ScanTriggerResult {
        manualScanCount++
        return ScanTriggerResult.Triggered(remainingTokens = 3, unthrottledMode = false)
    }

    override fun refreshReadiness() {
        refreshReadinessCount++
    }

    override suspend fun setBssidNickname(bssid: String, nickname: String?) {}
    override suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double) {}
    override suspend fun clearOneMeterCalibration(bssid: String) {}

    override fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload {
        val snapshot = _radarSnapshot.value
        val health = _environmentHealth.value
        val fmtMac: (String) -> String = { raw ->
            if (redactMacs) MacAddressUtils.redactMac(raw) else MacAddressUtils.normalizeBssid(raw)
        }
        val content = when (format) {
            ExportFormat.MARKDOWN -> buildString {
                appendLine("# WIFIREDEYE Diagnostic Report")
                appendLine("- Health Score: ${health.overallScore0To100 ?: "Waiting"}")
                appendLine("- MAC Redacted: $redactMacs")
                snapshot.radios.forEach { r ->
                    appendLine("- ${r.effectiveDisplayName} (${fmtMac(r.bssid)}): ${r.rssiDbm} dBm · ${r.security.grade.badgeText}")
                }
            }
            ExportFormat.JSON -> {
                // Realistic kotlinx.serialization JSON containing SCREAMING_SNAKE_CASE enum tokens
                val radiosJson = snapshot.radios.joinToString(",") { r ->
                    """{"ssid":"${r.effectiveDisplayName}","bssid":"${fmtMac(r.bssid)}","band":"${r.channelDescriptor.band.name}","grade":"${r.security.grade.name}","protocol":"${r.security.protocol.name}"}"""
                }
                """{"schemaVersion":"1.0","redactedMacs":$redactMacs,"state":"${health.state.name}","overallScore":${health.overallScore0To100 ?: "null"},"radios":[$radiosJson]}"""
            }
        }
        val size = content.encodeToByteArray().size
        return ExportPayload(
            format = format,
            fileName = "wifi-radar-report.${format.fileExtension}",
            mimeType = format.mimeType,
            content = content,
            redactedMacs = redactMacs,
            generatedAtEpochMs = 1_700_000_000_000L,
            networkCount = snapshot.radios.size,
            sizeBytes = size,
        )
    }

    companion object {
        fun createPopulatedSnapshot(): RadarSnapshot {
            val basePhy = PhyCapability(wifiGeneration = WifiGeneration.WIFI_6)
            val home5g = BssidRadio(
                bssid = "AA:BB:CC:11:22:02",
                ssid = DecodedSsid("HomeMesh"),
                effectiveDisplayName = "HomeMesh",
                rssiDbm = -47,
                smoothedRssiDbm = -47.2,
                signalQualityPct = 100,
                signalTier = SignalQualityTier.EXCELLENT,
                estimatedSnrDb = 45.0,
                channelDescriptor = ChannelDescriptor(
                    band = WifiBand.BAND_5_GHZ,
                    primaryFreqMhz = 5180,
                    primaryChannel = 36,
                    centerFreq0Mhz = 5210,
                    centerChannel0 = 42,
                    width = ChannelWidth.WIDTH_80_MHZ,
                    occupiedStartFreqMhz = 5170,
                    occupiedEndFreqMhz = 5250,
                    uniiSubBand = UniiSubBand.UNII_1,
                    isCanonicalPrimary = true,
                ),
                wifiGeneration = WifiGeneration.WIFI_6,
                vendorInfo = VendorInfo(displayLabel = "AsusTek"),
                security = SecurityAssessment(
                    grade = SecurityGrade.A_PLUS,
                    score0To100 = 100,
                    protocol = SecurityProtocol.WPA3_SAE,
                    securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
                    pairwiseCiphers = listOf(CipherSuite.CCMP_128),
                    pmfState = PmfState.REQUIRED,
                    saeH2eSupported = true,
                    saeH2eRequired = true,
                    beaconProtection = true,
                    plainEnglishLabel = "Strong Modern Password Protection",
                ),
                beaconDetails = BeaconIeDetails(
                    phyCapability = basePhy,
                    roaming = RoamingCapabilities(
                        supports80211k = true,
                        supports80211v = true,
                        supports80211r = true,
                        beaconProtection = true,
                    ),
                    colocated6GhzNeighbors = listOf(
                        Colocated6GhzNeighbor(
                            sourceBssid = "AA:BB:CC:11:22:02",
                            sourceSsid = "HomeMesh",
                            operatingClass = 134,
                            channelNumber = 37,
                            centerFreqMhz = 6135,
                            widthMhz = 160,
                            isPscChannel = true,
                        )
                    ),
                ),
                physicalClusterId = "cluster-1",
                isConnected = true,
                estimatedDistanceMeters = 1.6,
                channelCongestionScore0To100 = 86,
                lastSeenElapsedMs = 900_000L,
            )

            val cafeWps = home5g.copy(
                bssid = "DD:EE:FF:33:44:05",
                ssid = DecodedSsid("CornerCafe"),
                effectiveDisplayName = "CornerCafe",
                rssiDbm = -64,
                smoothedRssiDbm = -64.0,
                signalQualityPct = 72,
                signalTier = SignalQualityTier.GOOD,
                isConnected = false,
                vendorInfo = VendorInfo(displayLabel = "TP-Link"),
                channelDescriptor = ChannelDescriptor(
                    band = WifiBand.BAND_2_4_GHZ,
                    primaryFreqMhz = 2437,
                    primaryChannel = 6,
                    centerFreq0Mhz = 2437,
                    centerChannel0 = 6,
                    width = ChannelWidth.WIDTH_20_MHZ,
                    occupiedStartFreqMhz = 2426,
                    occupiedEndFreqMhz = 2448,
                    uniiSubBand = UniiSubBand.ISM_2_4_GHZ,
                    isCanonicalPrimary = true,
                ),
                security = SecurityAssessment(
                    grade = SecurityGrade.C,
                    score0To100 = 65,
                    protocol = SecurityProtocol.WPA2_PSK,
                    securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
                    pairwiseCiphers = listOf(CipherSuite.CCMP_128),
                    pmfState = PmfState.DISABLED,
                    wpsEnabled = true,
                    plainEnglishLabel = "Standard Home Wi-Fi Security",
                    warnings = listOf("WPS PIN setup is active — disable WPS in router settings."),
                ),
                beaconDetails = BeaconIeDetails(phyCapability = basePhy),
            )

            val rogueClone = home5g.copy(
                bssid = "66:77:88:99:AA:09",
                ssid = DecodedSsid("HomeMesh"),
                effectiveDisplayName = "HomeMesh",
                rssiDbm = -53,
                smoothedRssiDbm = -53.0,
                signalQualityPct = 94,
                signalTier = SignalQualityTier.GOOD,
                isConnected = false,
                vendorInfo = VendorInfo(displayLabel = "Unknown Vendor"),
                security = SecurityAssessment(
                    grade = SecurityGrade.F,
                    score0To100 = 5,
                    protocol = SecurityProtocol.OPEN,
                    securityFamily = SecurityFamily.OPEN_FAMILY,
                    pmfState = PmfState.NOT_APPLICABLE,
                    isEvilTwinSuspect = true,
                    plainEnglishLabel = "Unencrypted — Traffic Visible Nearby",
                    warnings = listOf("Unencrypted Open radio cloning protected network HomeMesh (66:77:88:99:AA:09)."),
                ),
                beaconDetails = BeaconIeDetails(phyCapability = basePhy),
            )

            // 15-minute timeline (latest = 900_000 ms) with both a pre-5m sample, a null dropout, and a 65s missed-sweep gap
            val homeTimeline = listOf(
                RssiSample(timestampElapsedMs = 120_000L, rssiDbm = -58, smoothedRssiDbm = -58.0, isConnectedSample = true),
                RssiSample(timestampElapsedMs = 700_000L, rssiDbm = -52, smoothedRssiDbm = -52.0, isConnectedSample = true),
                RssiSample(timestampElapsedMs = 710_000L, rssiDbm = null, smoothedRssiDbm = null, isConnectedSample = true),
                RssiSample(timestampElapsedMs = 720_000L, rssiDbm = -50, smoothedRssiDbm = -50.0, isConnectedSample = true),
                RssiSample(timestampElapsedMs = 785_000L, rssiDbm = -49, smoothedRssiDbm = -49.0, isConnectedSample = true),
                RssiSample(timestampElapsedMs = 895_000L, rssiDbm = -47, smoothedRssiDbm = -47.0, isConnectedSample = true),
            )
            val cafeTimeline = listOf(
                RssiSample(timestampElapsedMs = 850_000L, rssiDbm = -66, smoothedRssiDbm = -66.0),
                RssiSample(timestampElapsedMs = 898_000L, rssiDbm = -64, smoothedRssiDbm = -64.0),
            )

            val roamEvents = listOf(
                RoamEvent(
                    timestampElapsedMs = 885_000L,
                    epochTimeMs = 1_700_000_885_000L,
                    ssid = "HomeMesh",
                    previousBssid = "AA:BB:CC:11:22:01",
                    newBssid = "AA:BB:CC:11:22:02",
                    previousBand = WifiBand.BAND_2_4_GHZ,
                    newBand = WifiBand.BAND_5_GHZ,
                    previousChannel = 6,
                    newChannel = 36,
                    previousRssiDbm = -59,
                    newRssiDbm = -47,
                    rssiDeltaDb = 12,
                    isBandSwitch = true,
                )
            )

            return RadarSnapshot(
                timestampElapsedMs = 900_000L,
                timestampEpochMs = 1_700_000_900_000L,
                radios = listOf(home5g, cafeWps, rogueClone),
                scanTelemetry = ScanTelemetry(
                    lastScanTimestampMs = 899_000L,
                    lastScanAgeMs = 1_000L,
                    availableTokens = 4,
                    maxTokens = 4,
                ),
                connectedWifi = ConnectedWifiObservation(
                    bssid = "AA:BB:CC:11:22:02",
                    ssid = "HomeMesh",
                    rssiDbm = -47,
                    frequencyMhz = 5180,
                ),
                radioCapabilities = DeviceRadioCapabilities(
                    deviceModel = "SM_A556B",
                    has24Ghz = true,
                    has5Ghz = true,
                    has6Ghz = false,
                ),
                roamEvents = roamEvents,
                rssiTimelines = mapOf(
                    "AA:BB:CC:11:22:02" to homeTimeline,
                    "DD:EE:FF:33:44:05" to cafeTimeline,
                ),
            )
        }

        fun createPopulatedHealthReport(snapshot: RadarSnapshot): EnvironmentHealthReport =
            EnvironmentHealthReport(
                state = EnvironmentHealthState.SCORED,
                overallScore0To100 = 84,
                headlineLabel = "84 / 100 · Healthy Wireless Environment",
                summarySentence = "Strong 5 GHz WPA3 connection, though an unencrypted Evil-Twin suspect requires attention.",
                subScores = HealthSubScores(
                    signalCoverage0To100 = 94,
                    channelCrowding0To100 = 82,
                    interferencePurity0To100 = 88,
                    securityHygiene0To100 = 72,
                ),
                insights = listOf(
                    ActionableInsight(
                        id = "tip_channel_5g",
                        severity = InsightSeverity.SUGGESTION,
                        category = InsightCategory.CHANNEL_OPTIMIZATION,
                        title = "Channel 149 Offers Cleaner 5 GHz Headroom",
                        plainEnglishBody = "Switching your 5 GHz primary channel to Ch 149 avoids neighbor traffic.",
                        metricBadge = "+9 pts",
                        actionLabel = "Open 5 GHz Spectrum",
                        actionTarget = InsightActionTarget.OpenSpectrumBand(
                            band = WifiBand.BAND_5_GHZ,
                            highlightChannel = 149,
                        ),
                    ),
                    ActionableInsight(
                        id = "crit_evil_twin",
                        severity = InsightSeverity.CRITICAL,
                        category = InsightCategory.SECURITY,
                        title = "Unencrypted Clone Detected on HomeMesh",
                        plainEnglishBody = "Radio 66:77:88:99:AA:09 is broadcasting HomeMesh without WPA3 encryption.",
                        metricBadge = "Security Risk",
                        actionLabel = "Track Suspect Radio",
                        actionTarget = InsightActionTarget.LaunchProximity(
                            ProximityTargetKey.BssidLock("66:77:88:99:AA:09", "HomeMesh")
                        ),
                    ),
                    ActionableInsight(
                        id = "warn_wps_exposed",
                        severity = InsightSeverity.WARNING,
                        category = InsightCategory.SECURITY,
                        title = "WPS PIN Enabled on CornerCafe",
                        plainEnglishBody = "WPS PIN mode can be brute-forced offline within hours.",
                        actionTarget = InsightActionTarget.OpenNetworkDetail("DD:EE:FF:33:44:05"),
                    ),
                ),
                evilTwinAlerts = listOf(
                    EvilTwinAlert(
                        ssid = "HomeMesh",
                        legitimateBssids = listOf("AA:BB:CC:11:22:02"),
                        suspectOpenBssids = listOf("66:77:88:99:AA:09"),
                        legitimateProtocol = SecurityProtocol.WPA3_SAE,
                        suspectRssiDbm = -53,
                        plainEnglishWarning = "Suspect radio 66:77:88:99:AA:09 advertises HomeMesh with Open security while your real router uses WPA3-Personal.",
                    )
                ),
                securityAuditRadios = snapshot.radios,
                best24GhzRecommendation = ChannelRecommendation(
                    band = WifiBand.BAND_2_4_GHZ,
                    recommendedPrimaryChannel = 11,
                    recommendedCenterFreqMhz = 2462,
                    recommendedWidth = ChannelWidth.WIDTH_20_MHZ,
                    score0To100 = 93,
                    starRating0To5 = 4.6,
                    plainEnglishReason = "Wide open 2.4 GHz channel.",
                ),
                best5GhzRecommendation = ChannelRecommendation(
                    band = WifiBand.BAND_5_GHZ,
                    recommendedPrimaryChannel = 149,
                    recommendedCenterFreqMhz = 5775,
                    recommendedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 95,
                    starRating0To5 = 4.8,
                    plainEnglishReason = "Clean U-NII-3 80 MHz channel.",
                ),
                computedAtTimestampMs = 900_000L,
            )
    }
}
