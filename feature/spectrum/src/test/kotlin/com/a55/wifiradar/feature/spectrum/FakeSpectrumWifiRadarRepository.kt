package com.a55.wifiradar.feature.spectrum

import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.CciAciBreakdown
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelInterferer
import com.a55.wifiradar.core.model.ChannelRecommendation
import com.a55.wifiradar.core.model.ChannelScore
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.InterferenceType
import com.a55.wifiradar.core.model.InterfererRelationship
import com.a55.wifiradar.core.model.PhyCapability
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityFeedbackController
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.SecurityFamily
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.VendorInfo
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

class FakeSpectrumWifiRadarRepository(
    initialReadiness: ScannerReadinessState = ScannerReadinessState.Ready(),
    has6GhzHardware: Boolean = false,
) : WifiRadarRepository {

    data class RecordedSpectrumCall(
        val band: WifiBand,
        val includeDfs: Boolean,
        val excludeOwnSsid: Boolean,
    )

    val recordedCalls = mutableListOf<RecordedSpectrumCall>()
    var manualScanResult: ScanTriggerResult = ScanTriggerResult.Triggered(
        remainingTokens = 3,
        unthrottledMode = false,
    )
    var manualScanCount = 0
    var refreshReadinessCount = 0

    private val _radarSnapshot = MutableStateFlow(createPopulatedSnapshot(has6GhzHardware))
    override val radarSnapshot: StateFlow<RadarSnapshot> = _radarSnapshot

    private val _readinessState = MutableStateFlow(initialReadiness)
    override val readinessState: StateFlow<ScannerReadinessState> = _readinessState

    override val environmentHealth: StateFlow<EnvironmentHealthReport> =
        MutableStateFlow(EnvironmentHealthReport())

    override val feedbackController: ProximityFeedbackController = object : ProximityFeedbackController {
        override val feedbackSettings = MutableStateFlow(GeigerFeedbackSettings())
        override fun setHapticsEnabled(enabled: Boolean) {}
        override fun setAudioEnabled(enabled: Boolean) {}
        override fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) {}
        override fun stopAll() {}
    }

    fun setReadiness(state: ScannerReadinessState) {
        _readinessState.value = state
    }

    override fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
    ): Flow<ProximityTelemetry> = MutableStateFlow(
        ProximityTelemetry(
            targetKey = targetKey,
            displayTitle = "Target",
            displaySubtitle = "Subtitle",
            lockStatus = com.a55.wifiradar.core.model.SignalLockStatus.LOCKED,
        )
    )

    override fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): Flow<SpectrumAnalysis> {
        recordedCalls += RecordedSpectrumCall(band, includeDfs, excludeOwnSsid)
        return _radarSnapshot.map { snapshot ->
            when (band) {
                WifiBand.BAND_2_4_GHZ -> build24GhzAnalysis(excludeOwnSsid)
                WifiBand.BAND_5_GHZ -> build5GhzAnalysis(includeDfs, excludeOwnSsid)
                WifiBand.BAND_6_GHZ -> build6GhzAnalysis(snapshot.radioCapabilities.has6Ghz)
                else -> build24GhzAnalysis(excludeOwnSsid)
            }
        }
    }

    override fun requestManualScan(burstMode: Boolean): ScanTriggerResult {
        manualScanCount++
        return manualScanResult
    }

    override fun refreshReadiness() {
        refreshReadinessCount++
    }

    override suspend fun setBssidNickname(bssid: String, nickname: String?) {}
    override suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double) {}
    override suspend fun clearOneMeterCalibration(bssid: String) {}

    override fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload =
        ExportPayload(
            format = format,
            fileName = "wifi-radar.${format.fileExtension}",
            mimeType = format.mimeType,
            content = "{}",
            redactedMacs = redactMacs,
            generatedAtEpochMs = 1_700_000_000_000L,
            networkCount = 4,
            sizeBytes = 2,
        )

    companion object {
        val RNR_NEIGHBORS = listOf(
            Colocated6GhzNeighbor(
                sourceBssid = "AA:BB:CC:00:00:11",
                sourceSsid = "HomeMesh-5G",
                operatingClass = 134,
                channelNumber = 37,
                centerFreqMhz = 6135,
                widthMhz = 160,
                neighborBssid = "AA:BB:CC:00:06:11",
                isSameSsid = true,
                isColocatedAp = true,
                isPscChannel = true,
            ),
            Colocated6GhzNeighbor(
                sourceBssid = "AA:BB:CC:00:00:13",
                sourceSsid = "WiFi7-Lab",
                operatingClass = 137,
                channelNumber = 69,
                centerFreqMhz = 6295,
                widthMhz = 320,
                neighborBssid = "AA:BB:CC:00:06:13",
                isSameSsid = true,
                isColocatedAp = true,
                isPscChannel = true,
            ),
        )

        fun createPopulatedSnapshot(has6Ghz: Boolean = false): RadarSnapshot {
            val secA = SecurityAssessment(
                grade = SecurityGrade.A_PLUS,
                score0To100 = 98,
                protocol = SecurityProtocol.WPA3_SAE,
                securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
                plainEnglishLabel = "Strong Modern Password Protection",
            )
            val beaconWithRnr = BeaconIeDetails(
                phyCapability = PhyCapability(wifiGeneration = WifiGeneration.WIFI_6),
                colocated6GhzNeighbors = listOf(RNR_NEIGHBORS.first()),
            )
            val radio24 = BssidRadio(
                bssid = "AA:BB:CC:00:00:01",
                ssid = DecodedSsid("HomeMesh-2G"),
                effectiveDisplayName = "HomeMesh-2G",
                rssiDbm = -48,
                smoothedRssiDbm = -48.0,
                signalQualityPct = 100,
                signalTier = SignalQualityTier.EXCELLENT,
                estimatedSnrDb = 44.0,
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
                wifiGeneration = WifiGeneration.WIFI_6,
                vendorInfo = VendorInfo(displayLabel = "AsusTek"),
                security = secA,
                beaconDetails = BeaconIeDetails(phyCapability = PhyCapability(WifiGeneration.WIFI_6)),
                physicalClusterId = "cluster-home",
                isConnected = true,
                estimatedDistanceMeters = 1.8,
            )
            val radio5 = radio24.copy(
                bssid = "AA:BB:CC:00:00:11",
                ssid = DecodedSsid("HomeMesh-5G"),
                effectiveDisplayName = "HomeMesh-5G",
                rssiDbm = -51,
                smoothedRssiDbm = -51.0,
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
                beaconDetails = beaconWithRnr,
                isConnected = false,
            )

            return RadarSnapshot(
                timestampElapsedMs = 20_000L,
                timestampEpochMs = 1_700_000_000_000L,
                radios = listOf(radio24, radio5),
                scanTelemetry = ScanTelemetry(
                    lastScanTimestampMs = 19_000L,
                    lastScanAgeMs = 1_000L,
                    availableTokens = 3,
                    maxTokens = 4,
                ),
                connectedWifi = ConnectedWifiObservation(
                    bssid = "AA:BB:CC:00:00:01",
                    ssid = "HomeMesh-2G",
                    rssiDbm = -48,
                    frequencyMhz = 2437,
                ),
                radioCapabilities = DeviceRadioCapabilities(
                    deviceModel = "SM_A556B",
                    has24Ghz = true,
                    has5Ghz = true,
                    has6Ghz = has6Ghz,
                    maxSupportedWifiGeneration = WifiGeneration.WIFI_6,
                ),
            )
        }

        fun build24GhzAnalysis(excludeOwnSsid: Boolean): SpectrumAnalysis {
            val ownCurve = SpectralCurve(
                bssid = "AA:BB:CC:00:00:01",
                ssid = "HomeMesh-2G",
                displayLabel = "HomeMesh-2G",
                band = WifiBand.BAND_2_4_GHZ,
                primaryChannel = 6,
                primaryFreqMhz = 2437,
                centerFreq0Mhz = 2437,
                width = ChannelWidth.WIDTH_20_MHZ,
                rssiDbm = -48,
                linearPowerMw = 1.58e-5,
                lobeStartFreqMhz = 2426,
                lobeEndFreqMhz = 2448,
                isConnected = true,
                bssColor = 19,
                utilizationPct = 24,
            )
            val cciNeighbor = SpectralCurve(
                bssid = "AA:BB:CC:00:00:02",
                ssid = "Apt2B-Guest",
                displayLabel = "Apt2B-Guest",
                band = WifiBand.BAND_2_4_GHZ,
                primaryChannel = 6,
                primaryFreqMhz = 2437,
                centerFreq0Mhz = 2437,
                width = ChannelWidth.WIDTH_20_MHZ,
                rssiDbm = -67,
                linearPowerMw = 2.0e-7,
                lobeStartFreqMhz = 2426,
                lobeEndFreqMhz = 2448,
            )
            val aciRogue = SpectralCurve(
                bssid = "AA:BB:CC:00:00:03",
                ssid = "RogueCh4",
                displayLabel = "RogueCh4",
                band = WifiBand.BAND_2_4_GHZ,
                primaryChannel = 4,
                primaryFreqMhz = 2427,
                centerFreq0Mhz = 2427,
                width = ChannelWidth.WIDTH_20_MHZ,
                rssiDbm = -54,
                linearPowerMw = 3.98e-6,
                lobeStartFreqMhz = 2416,
                lobeEndFreqMhz = 2438,
            )

            val curves = if (excludeOwnSsid) {
                listOf(cciNeighbor, aciRogue)
            } else {
                listOf(ownCurve, cciNeighbor, aciRogue)
            }

            val ch6Breakdown = CciAciBreakdown(
                targetChannel = 6,
                targetBand = WifiBand.BAND_2_4_GHZ,
                targetBssid = if (excludeOwnSsid) null else "AA:BB:CC:00:00:01",
                cciInterferers = listOf(
                    // Intentionally include own radio in perChannelBreakdowns to verify self-stripping!
                    ChannelInterferer(
                        bssid = "AA:BB:CC:00:00:01",
                        ssid = "HomeMesh-2G",
                        displayLabel = "HomeMesh-2G",
                        rssiDbm = -48,
                        primaryChannel = 6,
                        width = ChannelWidth.WIDTH_20_MHZ,
                        overlapRatio0To1 = 1.0,
                        penaltyMultiplier = 1.0,
                        weightedInterferenceMw = 1.58e-5,
                        interferenceType = InterferenceType.CO_CHANNEL_CCI,
                        relationship = InterfererRelationship.OWN_MESH_SELF_CONTENTION,
                        isActionable = true,
                    ),
                    ChannelInterferer(
                        bssid = "AA:BB:CC:00:00:02",
                        ssid = "Apt2B-Guest",
                        displayLabel = "Apt2B-Guest",
                        rssiDbm = -67,
                        primaryChannel = 6,
                        width = ChannelWidth.WIDTH_20_MHZ,
                        overlapRatio0To1 = 1.0,
                        penaltyMultiplier = 1.0,
                        weightedInterferenceMw = 2.0e-7,
                        interferenceType = InterferenceType.CO_CHANNEL_CCI,
                        relationship = InterfererRelationship.NEIGHBOR_NETWORK,
                        isActionable = true,
                    ),
                ),
                aciInterferers = listOf(
                    ChannelInterferer(
                        bssid = "AA:BB:CC:00:00:03",
                        ssid = "RogueCh4",
                        displayLabel = "RogueCh4",
                        rssiDbm = -54,
                        primaryChannel = 4,
                        width = ChannelWidth.WIDTH_20_MHZ,
                        overlapRatio0To1 = 0.55,
                        penaltyMultiplier = 1.85,
                        weightedInterferenceMw = 4.05e-6,
                        interferenceType = InterferenceType.ADJACENT_CHANNEL_ACI,
                        relationship = InterfererRelationship.NEIGHBOR_NETWORK,
                        isActionable = true,
                    ),
                ),
                actionableCciCount = 2,
                actionableAciCount = 1,
                totalCciPowerMw = 1.60e-5,
                totalAciPowerMw = 4.05e-6,
                cciPlainEnglishSummary = "1 neighbor router politely sharing Channel 6 airtime.",
                aciPlainEnglishSummary = "RogueCh4 on off-center Ch 4 bleeds raw sideband noise into Channel 6.",
                hasSevereAciWarning = true,
            )

            val ch11Breakdown = CciAciBreakdown(
                targetChannel = 11,
                targetBand = WifiBand.BAND_2_4_GHZ,
                cciPlainEnglishSummary = "Zero shared-channel routers on Channel 11.",
                aciPlainEnglishSummary = "Zero sideband collisions on Channel 11.",
                hasSevereAciWarning = false,
            )

            return SpectrumAnalysis(
                band = WifiBand.BAND_2_4_GHZ,
                includeDfs = false,
                excludeConnectedSsid = excludeOwnSsid,
                minFreqMhz = 2400,
                maxFreqMhz = 2495,
                curves = curves,
                aggregatedWeakCurveCount = 3,
                channelScores = listOf(
                    ChannelScore(
                        band = WifiBand.BAND_2_4_GHZ,
                        channel = 1,
                        centerFreqMhz = 2412,
                        evaluatedWidth = ChannelWidth.WIDTH_20_MHZ,
                        score0To100 = 76,
                        starRating0To5 = 3.8,
                        equivalentNoiseFloorDbm = -88.0,
                        cciCount = 0,
                        aciCount = 1,
                        isCanonical = true,
                        uniiSubBand = UniiSubBand.ISM_2_4_GHZ,
                        plainEnglishRating = "Light Traffic",
                    ),
                    ChannelScore(
                        band = WifiBand.BAND_2_4_GHZ,
                        channel = 6,
                        centerFreqMhz = 2437,
                        evaluatedWidth = ChannelWidth.WIDTH_20_MHZ,
                        score0To100 = 42,
                        starRating0To5 = 2.1,
                        equivalentNoiseFloorDbm = -68.0,
                        cciCount = 1,
                        aciCount = 1,
                        isCanonical = true,
                        uniiSubBand = UniiSubBand.ISM_2_4_GHZ,
                        plainEnglishRating = "Moderate Load",
                    ),
                    ChannelScore(
                        band = WifiBand.BAND_2_4_GHZ,
                        channel = 11,
                        centerFreqMhz = 2462,
                        evaluatedWidth = ChannelWidth.WIDTH_20_MHZ,
                        score0To100 = 93,
                        starRating0To5 = 4.6,
                        equivalentNoiseFloorDbm = -95.0,
                        cciCount = 0,
                        aciCount = 0,
                        isCanonical = true,
                        uniiSubBand = UniiSubBand.ISM_2_4_GHZ,
                        plainEnglishRating = "Wide Open",
                    ),
                ),
                recommendation = ChannelRecommendation(
                    band = WifiBand.BAND_2_4_GHZ,
                    recommendedPrimaryChannel = 11,
                    recommendedCenterFreqMhz = 2462,
                    recommendedWidth = ChannelWidth.WIDTH_20_MHZ,
                    score0To100 = 93,
                    starRating0To5 = 4.6,
                    currentConnectedChannel = 6,
                    currentConnectedScore0To100 = 42,
                    estimatedScoreGain = 51,
                    isCurrentChannelAlreadyOptimal = false,
                    plainEnglishReason = "Channel 11 avoids RogueCh4 sideband overlap on Ch 4 and delivers +51 pts cleaner spectrum.",
                ),
                selectedOrConnectedBreakdown = ch6Breakdown,
                perChannelBreakdowns = mapOf(
                    6 to ch6Breakdown,
                    11 to ch11Breakdown,
                ),
                isBandSupportedByHardware = true,
            )
        }

        fun build5GhzAnalysis(includeDfs: Boolean, excludeOwnSsid: Boolean): SpectrumAnalysis {
            val home5 = SpectralCurve(
                bssid = "AA:BB:CC:00:00:11",
                ssid = "HomeMesh-5G",
                displayLabel = "HomeMesh-5G",
                band = WifiBand.BAND_5_GHZ,
                primaryChannel = 36,
                primaryFreqMhz = 5180,
                centerFreq0Mhz = 5210,
                width = ChannelWidth.WIDTH_80_MHZ,
                rssiDbm = -51,
                linearPowerMw = 7.94e-6,
                lobeStartFreqMhz = 5170,
                lobeEndFreqMhz = 5250,
                isConnected = true,
            )
            val dual80Plus80 = SpectralCurve(
                bssid = "AA:BB:CC:00:00:12",
                ssid = "StudioLink",
                displayLabel = "StudioLink",
                band = WifiBand.BAND_5_GHZ,
                primaryChannel = 44,
                primaryFreqMhz = 5220,
                centerFreq0Mhz = 5210,
                centerFreq1Mhz = 5775,
                width = ChannelWidth.WIDTH_80_PLUS_80_MHZ,
                rssiDbm = -58,
                linearPowerMw = 1.58e-6,
                lobeStartFreqMhz = 5170,
                lobeEndFreqMhz = 5250,
                secondaryLobeStartFreqMhz = 5735,
                secondaryLobeEndFreqMhz = 5815,
            )
            val wifi7Punctured = SpectralCurve(
                bssid = "AA:BB:CC:00:00:13",
                ssid = "WiFi7-Lab",
                displayLabel = "WiFi7-Lab",
                band = WifiBand.BAND_5_GHZ,
                primaryChannel = 52,
                primaryFreqMhz = 5260,
                centerFreq0Mhz = 5250,
                width = ChannelWidth.WIDTH_160_MHZ,
                rssiDbm = -61,
                linearPowerMw = 7.94e-7,
                lobeStartFreqMhz = 5170,
                lobeEndFreqMhz = 5330,
                puncturedSubchannelBitmap = 0b0000_0100,
                bssColor = 42,
            )

            val curves = if (excludeOwnSsid) {
                listOf(dual80Plus80, wifi7Punctured)
            } else {
                listOf(home5, dual80Plus80, wifi7Punctured)
            }

            val baseScores = mutableListOf(
                ChannelScore(
                    band = WifiBand.BAND_5_GHZ,
                    channel = 36,
                    centerFreqMhz = 5210,
                    evaluatedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 58,
                    starRating0To5 = 2.9,
                    equivalentNoiseFloorDbm = -74.0,
                    cciCount = 1,
                    aciCount = 2,
                    isCanonical = true,
                    uniiSubBand = UniiSubBand.UNII_1,
                    plainEnglishRating = "Moderate Load",
                ),
                ChannelScore(
                    band = WifiBand.BAND_5_GHZ,
                    channel = 149,
                    centerFreqMhz = 5775,
                    evaluatedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 91,
                    starRating0To5 = 4.5,
                    equivalentNoiseFloorDbm = -92.0,
                    cciCount = 0,
                    aciCount = 1,
                    isCanonical = true,
                    uniiSubBand = UniiSubBand.UNII_3,
                    plainEnglishRating = "Wide Open",
                ),
            )
            if (includeDfs) {
                baseScores += ChannelScore(
                    band = WifiBand.BAND_5_GHZ,
                    channel = 100,
                    centerFreqMhz = 5530,
                    evaluatedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 97,
                    starRating0To5 = 4.9,
                    equivalentNoiseFloorDbm = -95.0,
                    cciCount = 0,
                    aciCount = 0,
                    isCanonical = true,
                    isDfs = true,
                    uniiSubBand = UniiSubBand.UNII_2C,
                    plainEnglishRating = "Wide Open",
                )
                baseScores += ChannelScore(
                    band = WifiBand.BAND_5_GHZ,
                    channel = 120,
                    centerFreqMhz = 5610,
                    evaluatedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 84,
                    starRating0To5 = 4.2,
                    equivalentNoiseFloorDbm = -94.0,
                    cciCount = 0,
                    aciCount = 0,
                    isCanonical = false,
                    isDfs = true,
                    isTdwr = true,
                    uniiSubBand = UniiSubBand.UNII_2C,
                    plainEnglishRating = "Wide Open",
                )
            }

            val rec = if (includeDfs) {
                ChannelRecommendation(
                    band = WifiBand.BAND_5_GHZ,
                    recommendedPrimaryChannel = 100,
                    recommendedCenterFreqMhz = 5530,
                    recommendedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 97,
                    starRating0To5 = 4.9,
                    currentConnectedChannel = 36,
                    currentConnectedScore0To100 = 58,
                    estimatedScoreGain = 39,
                    plainEnglishReason = "DFS Channel 100 (U-NII-2C) is completely empty and avoids U-NII-1 80 MHz contention.",
                )
            } else {
                ChannelRecommendation(
                    band = WifiBand.BAND_5_GHZ,
                    recommendedPrimaryChannel = 149,
                    recommendedCenterFreqMhz = 5775,
                    recommendedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 91,
                    starRating0To5 = 4.5,
                    currentConnectedChannel = 36,
                    currentConnectedScore0To100 = 58,
                    estimatedScoreGain = 33,
                    plainEnglishReason = "Non-DFS Channel 149 (U-NII-3) avoids radar wait times while escaping Ch 36 congestion.",
                )
            }

            return SpectrumAnalysis(
                band = WifiBand.BAND_5_GHZ,
                includeDfs = includeDfs,
                excludeConnectedSsid = excludeOwnSsid,
                minFreqMhz = 5150,
                maxFreqMhz = 5895,
                curves = curves,
                channelScores = baseScores.sortedBy { it.channel },
                recommendation = rec,
                isBandSupportedByHardware = true,
            )
        }

        fun build6GhzAnalysis(has6GhzHardware: Boolean): SpectrumAnalysis = SpectrumAnalysis(
            band = WifiBand.BAND_6_GHZ,
            includeDfs = false,
            excludeConnectedSsid = false,
            minFreqMhz = 5925,
            maxFreqMhz = 7125,
            curves = emptyList(),
            channelScores = listOf(
                ChannelScore(
                    band = WifiBand.BAND_6_GHZ,
                    channel = 37,
                    centerFreqMhz = 6135,
                    evaluatedWidth = ChannelWidth.WIDTH_160_MHZ,
                    score0To100 = 88,
                    starRating0To5 = 4.4,
                    equivalentNoiseFloorDbm = -93.0,
                    cciCount = 1,
                    aciCount = 0,
                    isCanonical = true,
                    isPsc = true,
                    uniiSubBand = UniiSubBand.UNII_5,
                    plainEnglishRating = "Wide Open",
                ),
                ChannelScore(
                    band = WifiBand.BAND_6_GHZ,
                    channel = 101,
                    centerFreqMhz = 6455,
                    evaluatedWidth = ChannelWidth.WIDTH_160_MHZ,
                    score0To100 = 99,
                    starRating0To5 = 5.0,
                    equivalentNoiseFloorDbm = -95.0,
                    cciCount = 0,
                    aciCount = 0,
                    isCanonical = true,
                    isPsc = true,
                    uniiSubBand = UniiSubBand.UNII_6,
                    plainEnglishRating = "Wide Open",
                ),
            ),
            recommendation = ChannelRecommendation(
                band = WifiBand.BAND_6_GHZ,
                recommendedPrimaryChannel = 101,
                recommendedCenterFreqMhz = 6455,
                recommendedWidth = ChannelWidth.WIDTH_160_MHZ,
                score0To100 = 99,
                starRating0To5 = 5.0,
                plainEnglishReason = "Preferred Scanning Channel 101 offers zero co-located 6 GHz RNR overlap.",
            ),
            colocated6GhzNeighborsFromRnr = RNR_NEIGHBORS,
            isBandSupportedByHardware = has6GhzHardware,
            hardwareExplanationMessage = if (has6GhzHardware) {
                null
            } else {
                "Galaxy A55 5G (SM_A556B) uses a dual-band 2.4 & 5 GHz Wi-Fi 6 radio; 6 GHz co-located routers below are decoded from RNR beacons."
            },
        )
    }
}
