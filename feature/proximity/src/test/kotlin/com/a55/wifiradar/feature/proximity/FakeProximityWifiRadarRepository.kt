package com.a55.wifiradar.feature.proximity

import com.a55.wifiradar.core.model.AkmSuite
import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssLoadInfo
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.DistanceEstimate
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PhyCapability
import com.a55.wifiradar.core.model.PmfState
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
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.SignalQualityTier
import com.a55.wifiradar.core.model.SparklinePoint
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.VelocityTrend
import com.a55.wifiradar.core.model.VendorInfo
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

class FakeProximityFeedbackController(
    override var hasHardwareVibrator: Boolean = true,
) : ProximityFeedbackController {
    private val _feedbackSettings = MutableStateFlow(GeigerFeedbackSettings(false, false))
    override val feedbackSettings: StateFlow<GeigerFeedbackSettings> = _feedbackSettings.asStateFlow()

    val updateSignalCalls = mutableListOf<Pair<Double?, Boolean>>()
    var stopAllCallCount: Int = 0

    override fun setHapticsEnabled(enabled: Boolean) {
        if (enabled && !hasHardwareVibrator) return
        _feedbackSettings.value = _feedbackSettings.value.copy(hapticsEnabled = enabled)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        _feedbackSettings.value = _feedbackSettings.value.copy(audioEnabled = enabled)
    }

    override fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) {
        updateSignalCalls.add(smoothedRssiDbm to isSignalLost)
    }

    override fun stopAll() {
        stopAllCallCount++
    }
}

class FakeProximityWifiRadarRepository(
    initialSnapshot: RadarSnapshot = ProximityTestFixtures.sampleSnapshot(),
    initialReadiness: ScannerReadinessState = ScannerReadinessState.Ready(),
    hasVibrator: Boolean = true,
) : WifiRadarRepository {

    val snapshotMutable = MutableStateFlow(initialSnapshot)
    override val radarSnapshot: StateFlow<RadarSnapshot> = snapshotMutable.asStateFlow()

    val readinessMutable = MutableStateFlow(initialReadiness)
    override val readinessState: StateFlow<ScannerReadinessState> = readinessMutable.asStateFlow()

    override val environmentHealth: StateFlow<EnvironmentHealthReport> = MutableStateFlow(
        EnvironmentHealthReport(state = EnvironmentHealthState.WAITING_FOR_DATA)
    )

    val fakeFeedback = FakeProximityFeedbackController(hasHardwareVibrator = hasVibrator)
    override val feedbackController: ProximityFeedbackController
        get() = fakeFeedback

    val calibrationsMutable = MutableStateFlow<Map<String, Double>>(emptyMap())
    val telemetryOverrideMutable = MutableStateFlow<ProximityTelemetry?>(null)
    private val resetEpochMutable = MutableStateFlow(0)

    var nextManualScanResult: ScanTriggerResult = ScanTriggerResult.Triggered(
        remainingTokens = 3,
        unthrottledMode = false,
    )
    val manualScanBurstCalls = mutableListOf<Boolean>()
    val resetStatsCalls = mutableListOf<ProximityTargetKey?>()
    var refreshReadinessCalls: Int = 0

    override fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
    ): Flow<ProximityTelemetry> = combine(
        snapshotMutable,
        calibrationsMutable,
        telemetryOverrideMutable,
        resetEpochMutable,
    ) { snapshot, calibrations, overrideTelemetry, resetEpoch ->
        overrideTelemetry ?: deriveTelemetry(
            targetKey = targetKey,
            environment = environment,
            snapshot = snapshot,
            calibrations = calibrations,
            resetEpoch = resetEpoch,
        )
    }

    private fun deriveTelemetry(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
        snapshot: RadarSnapshot,
        calibrations: Map<String, Double>,
        resetEpoch: Int,
    ): ProximityTelemetry {
        val matchedRadio: BssidRadio? = when (targetKey) {
            is ProximityTargetKey.BssidLock -> {
                val norm = MacAddressUtils.normalizeBssid(targetKey.bssid)
                snapshot.radios.firstOrNull { MacAddressUtils.normalizeBssid(it.bssid) == norm }
            }
            is ProximityTargetKey.FollowMeshSsid -> {
                snapshot.radios
                    .filter { !it.ssid.isHidden && it.ssid.displayName == targetKey.ssid }
                    .maxByOrNull { it.rssiDbm }
            }
        }

        if (matchedRadio == null) {
            val fallbackTitle = when (targetKey) {
                is ProximityTargetKey.BssidLock -> targetKey.ssidHint ?: DecodedSsid.HIDDEN_NETWORK_LABEL
                is ProximityTargetKey.FollowMeshSsid -> targetKey.ssid
            }
            val fallbackSub = when (targetKey) {
                is ProximityTargetKey.BssidLock -> "Radio ${MacAddressUtils.normalizeBssid(targetKey.bssid)}"
                is ProximityTargetKey.FollowMeshSsid -> "Mesh SSID · ${targetKey.ssid}"
            }
            val dropoutPoints = listOf(
                SparklinePoint(1_000L, rawRssiDbm = -56, smoothedRssiDbm = -56.0, emaRssiDbm = -56.0),
                SparklinePoint(2_000L, rawRssiDbm = -62, smoothedRssiDbm = -61.5, emaRssiDbm = -61.0),
                SparklinePoint(3_000L, rawRssiDbm = -74, smoothedRssiDbm = -72.0, emaRssiDbm = -71.5),
                SparklinePoint(4_000L, isDropoutGap = true),
                SparklinePoint(5_000L, isDropoutGap = true),
            )
            return ProximityTelemetry(
                targetKey = targetKey,
                resolvedBssid = (targetKey as? ProximityTargetKey.BssidLock)?.bssid,
                displayTitle = fallbackTitle,
                displaySubtitle = fallbackSub,
                lockStatus = SignalLockStatus.SIGNAL_LOST,
                smoothedRssiDbm = null,
                rawRssiDbm = null,
                lastKnownRssiDbm = -86,
                peakHoldRssiDbm = if (resetEpoch > 0) null else -51.0,
                minRssiDbm = if (resetEpoch > 0) null else -74,
                maxRssiDbm = if (resetEpoch > 0) null else -51,
                avgRssiDbm = if (resetEpoch > 0) null else -60.5,
                sampleCount = if (resetEpoch > 0) 0 else 24,
                stabilityPct = 88,
                stdDevDb = 1.9,
                sparkline = if (resetEpoch > 0) emptyList() else dropoutPoints,
                lastSeenAgeMs = 12_000L,
                scanTelemetry = snapshot.scanTelemetry,
            )
        }

        val normMac = MacAddressUtils.normalizeBssid(matchedRadio.bssid)
        val userRef = calibrations[normMac]
        val ref1m = userRef ?: if (matchedRadio.channelDescriptor.band == WifiBand.BAND_5_GHZ) -47.0 else -40.0
        val smoothed = matchedRadio.smoothedRssiDbm
        val meters = 10.0.pow((ref1m - smoothed) / (10.0 * environment.pathLossExponent)).coerceIn(0.4, 55.0)
        val method = when {
            matchedRadio.is80211azResponder -> DistanceMethod.RTT_80211AZ
            userRef != null -> DistanceMethod.USER_CALIBRATED_PATH_LOSS
            else -> DistanceMethod.PATH_LOSS_ESTIMATE
        }
        val minBound = (meters * 0.72).coerceAtLeast(0.3)
        val maxBound = (meters * 1.38).coerceAtMost(60.0)
        val distanceEstimate = DistanceEstimate(
            meters = meters,
            rangeMinMeters = minBound,
            rangeMaxMeters = maxBound,
            stdDevMeters = if (method.isHardwareRtt) 0.4 else null,
            method = method,
            referenceRssiAt1mDbm = ref1m,
            environment = environment,
            plainEnglishFormatted = SemanticFormatters.formatDistanceRange(
                meters = meters,
                rangeMinMeters = minBound,
                rangeMaxMeters = maxBound,
                stdDevMeters = if (method.isHardwareRtt) 0.4 else null,
                method = method,
            ),
        )

        val sparkline = List(36) { idx ->
            val wave = kotlin.math.sin(idx * 0.28) * 2.4
            val v = smoothed + wave
            SparklinePoint(
                timestampElapsedMs = (idx + 1) * 300L,
                rawRssiDbm = v.roundToInt(),
                smoothedRssiDbm = v,
                emaRssiDbm = v,
                isDropoutGap = false,
            )
        }

        return ProximityTelemetry(
            targetKey = targetKey,
            resolvedBssid = normMac,
            displayTitle = matchedRadio.effectiveDisplayName,
            displaySubtitle = "$normMac · ${matchedRadio.vendorInfo.displayLabel}",
            band = matchedRadio.channelDescriptor.band,
            channel = matchedRadio.channelDescriptor.primaryChannel,
            frequencyMhz = matchedRadio.channelDescriptor.primaryFreqMhz,
            vendorLabel = matchedRadio.vendorInfo.displayLabel,
            lockStatus = SignalLockStatus.LOCKED,
            smoothedRssiDbm = smoothed,
            emaRssiDbm = smoothed,
            rawRssiDbm = matchedRadio.rssiDbm,
            lastKnownRssiDbm = matchedRadio.rssiDbm,
            peakHoldRssiDbm = if (resetEpoch > 0) smoothed else (smoothed + 3.0).coerceAtMost(-32.0),
            signalQualityPct = matchedRadio.signalQualityPct,
            signalTier = matchedRadio.signalTier,
            distanceEstimate = distanceEstimate,
            isUserCalibrated1m = userRef != null,
            velocitySlopeDbPerSec = 1.8,
            velocityTrend = VelocityTrend.APPROACHING,
            stabilityPct = 94,
            stdDevDb = 1.1,
            minRssiDbm = if (resetEpoch > 0) matchedRadio.rssiDbm else matchedRadio.rssiDbm - 4,
            maxRssiDbm = if (resetEpoch > 0) matchedRadio.rssiDbm else matchedRadio.rssiDbm + 3,
            avgRssiDbm = if (resetEpoch > 0) smoothed else smoothed - 0.6,
            sampleCount = if (resetEpoch > 0) 1 else 36,
            sparkline = if (resetEpoch > 0) sparkline.takeLast(1) else sparkline,
            geigerIntervalMs = 260L,
            geigerToneHz = 780.0,
            normalizedProximity0To1 = ((smoothed + 100.0) / 70.0).coerceIn(0.0, 1.0),
            lastSeenElapsedMs = snapshot.timestampElapsedMs,
            lastSeenAgeMs = 300L,
            isConnectedFastPath = matchedRadio.isConnected,
            isRttActive = method.isHardwareRtt,
            scanTelemetry = snapshot.scanTelemetry,
        )
    }

    override fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): Flow<SpectrumAnalysis> = flowOf(
        SpectrumAnalysis(band = band, minFreqMhz = 5150, maxFreqMhz = 5850)
    )

    override fun requestManualScan(burstMode: Boolean): ScanTriggerResult {
        manualScanBurstCalls.add(burstMode)
        return nextManualScanResult
    }

    override fun resetProximityStats(targetKey: ProximityTargetKey?) {
        resetStatsCalls.add(targetKey)
        resetEpochMutable.value += 1
    }

    override fun refreshReadiness() {
        refreshReadinessCalls++
    }

    override suspend fun setBssidNickname(bssid: String, nickname: String?) = Unit

    override suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double) {
        val norm = MacAddressUtils.normalizeBssid(bssid)
        calibrationsMutable.value = calibrationsMutable.value + (norm to currentSmoothedDbm)
    }

    override suspend fun clearOneMeterCalibration(bssid: String) {
        val norm = MacAddressUtils.normalizeBssid(bssid)
        calibrationsMutable.value = calibrationsMutable.value - norm
    }

    override fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload =
        ExportPayload(
            format = format,
            fileName = "proximity.json",
            mimeType = format.mimeType,
            content = "{}",
            redactedMacs = redactMacs,
            generatedAtEpochMs = 0L,
            networkCount = 0,
            sizeBytes = 2,
        )
}

object ProximityTestFixtures {

    fun makeRadio(
        bssid: String,
        ssid: String,
        isHidden: Boolean = false,
        rssiDbm: Int = -51,
        band: WifiBand = WifiBand.BAND_5_GHZ,
        channel: Int = 36,
        freqMhz: Int = 5180,
        vendor: String = "ASUSUSTek Computer",
        isConnected: Boolean = false,
        is80211az: Boolean = false,
    ): BssidRadio {
        val norm = MacAddressUtils.normalizeBssid(bssid)
        val decodedSsid = DecodedSsid(
            displayName = if (isHidden) DecodedSsid.HIDDEN_NETWORK_LABEL else ssid,
            rawUtf8OrNull = if (isHidden) null else ssid,
            isHidden = isHidden,
        )
        return BssidRadio(
            bssid = norm,
            ssid = decodedSsid,
            effectiveDisplayName = decodedSsid.displayName,
            rssiDbm = rssiDbm,
            smoothedRssiDbm = rssiDbm.toDouble(),
            signalQualityPct = SemanticFormatters.signalQualityPct(rssiDbm),
            signalTier = SignalQualityTier.fromRssiDbm(rssiDbm),
            estimatedSnrDb = (rssiDbm + 92).toDouble(),
            channelDescriptor = ChannelDescriptor(
                band = band,
                primaryFreqMhz = freqMhz,
                primaryChannel = channel,
                centerFreq0Mhz = freqMhz,
                centerChannel0 = channel,
                width = ChannelWidth.WIDTH_80_MHZ,
                occupiedStartFreqMhz = freqMhz - 40,
                occupiedEndFreqMhz = freqMhz + 40,
                uniiSubBand = if (band == WifiBand.BAND_5_GHZ) UniiSubBand.UNII_1 else UniiSubBand.ISM_2_4_GHZ,
            ),
            wifiGeneration = WifiGeneration.WIFI_6,
            vendorInfo = VendorInfo(
                ouiPrefix = MacAddressUtils.extractOuiPrefix(norm),
                vendorName = vendor,
                displayLabel = vendor,
            ),
            security = SecurityAssessment(
                grade = SecurityGrade.A_PLUS,
                score0To100 = 96,
                protocol = SecurityProtocol.WPA3_SAE,
                securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
                groupCipher = CipherSuite.CCMP_128,
                pairwiseCiphers = listOf(CipherSuite.CCMP_128),
                akmSuites = listOf(AkmSuite.SAE),
                pmfState = PmfState.REQUIRED,
                plainEnglishLabel = "Strong Modern Password Protection",
            ),
            beaconDetails = BeaconIeDetails(
                bssLoad = BssLoadInfo(8, 50, 20, 1000),
                phyCapability = PhyCapability(
                    wifiGeneration = WifiGeneration.WIFI_6,
                    maxSpatialStreams = 2,
                    mimoLabel = "2×2 MIMO",
                    estimatedMaxPhyMbps = 2402,
                ),
            ),
            physicalClusterId = "cluster_$norm",
            is80211azResponder = is80211az,
            isConnected = isConnected,
            estimatedDistanceMeters = 2.4,
            lastSeenElapsedMs = 10_000L,
        )
    }

    fun sampleSnapshot(): RadarSnapshot {
        val connected5g = makeRadio(
            bssid = "AA:BB:CC:10:00:01",
            ssid = "AuroraMesh",
            rssiDbm = -51,
            band = WifiBand.BAND_5_GHZ,
            channel = 36,
            freqMhz = 5180,
            isConnected = true,
        )
        val meshHallway = makeRadio(
            bssid = "AA:BB:CC:20:00:02",
            ssid = "AuroraMesh",
            rssiDbm = -44,
            band = WifiBand.BAND_5_GHZ,
            channel = 149,
            freqMhz = 5745,
            isConnected = false,
        )
        val hiddenVault = makeRadio(
            bssid = "AA:BB:CC:99:00:09",
            ssid = "",
            isHidden = true,
            rssiDbm = -63,
            band = WifiBand.BAND_2_4_GHZ,
            channel = 6,
            freqMhz = 2437,
            vendor = "Ubiquiti Inc",
        )
        val rttRouter = makeRadio(
            bssid = "AA:BB:CC:77:00:07",
            ssid = "Studio:RTT:Lab",
            rssiDbm = -56,
            band = WifiBand.BAND_5_GHZ,
            channel = 48,
            freqMhz = 5240,
            vendor = "Google Nest WiFi",
            is80211az = true,
        )

        val meshGroup = EssNetworkGroup(
            groupKey = "ess:AuroraMesh",
            ssid = connected5g.ssid,
            displayName = "AuroraMesh",
            isMeshOrMultiAp = true,
            physicalClusterCount = 2,
            radios = listOf(meshHallway, connected5g),
            physicalClusterIds = listOf("c1", "c2"),
            strongestRadio = meshHallway,
            bands = setOf(WifiBand.BAND_5_GHZ),
            maxWifiGeneration = WifiGeneration.WIFI_6,
            worstSecurityAssessment = connected5g.security,
            bestSecurityAssessment = connected5g.security,
            containsConnectedRadio = true,
        )

        return RadarSnapshot(
            timestampElapsedMs = 10_000L,
            radios = listOf(connected5g, meshHallway, hiddenVault, rttRouter),
            groupedNetworks = listOf(meshGroup),
            scanTelemetry = ScanTelemetry(
                lastScanTimestampMs = 9_500L,
                lastScanAgeMs = 500L,
                availableTokens = 3,
                maxTokens = 4,
            ),
            connectedWifi = ConnectedWifiObservation(
                bssid = "AA:BB:CC:10:00:01",
                ssid = "AuroraMesh",
                rssiDbm = -51,
                frequencyMhz = 5180,
                txLinkSpeedMbps = 1201,
                rxLinkSpeedMbps = 1201,
            ),
            radioCapabilities = DeviceRadioCapabilities(has6Ghz = false),
        )
    }
}
