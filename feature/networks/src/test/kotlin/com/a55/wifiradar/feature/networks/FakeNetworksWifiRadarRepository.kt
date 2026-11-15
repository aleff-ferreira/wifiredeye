package com.a55.wifiradar.feature.networks

import com.a55.wifiradar.core.model.AkmSuite
import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssColorInfo
import com.a55.wifiradar.core.model.BssLoadInfo
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.CountryInfo
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
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
import com.a55.wifiradar.core.model.RoamingCapabilities
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
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.TimDtimInfo
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.VendorInfo
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.model.WifiRadarRepository
import com.a55.wifiradar.core.model.WpsDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeNetworksFeedbackController : ProximityFeedbackController {
    private val _feedbackSettings = MutableStateFlow(GeigerFeedbackSettings())
    override val feedbackSettings: StateFlow<GeigerFeedbackSettings> = _feedbackSettings.asStateFlow()
    override var hasHardwareVibrator: Boolean = true

    override fun setHapticsEnabled(enabled: Boolean) {
        _feedbackSettings.value = _feedbackSettings.value.copy(hapticsEnabled = enabled)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        _feedbackSettings.value = _feedbackSettings.value.copy(audioEnabled = enabled)
    }

    override fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) = Unit
    override fun stopAll() = Unit
}

class FakeNetworksWifiRadarRepository(
    initialSnapshot: RadarSnapshot = NetworksTestFixtures.sampleRichSnapshot(),
    initialReadiness: ScannerReadinessState = ScannerReadinessState.Ready(),
) : WifiRadarRepository {

    val snapshotMutable = MutableStateFlow(initialSnapshot)
    override val radarSnapshot: StateFlow<RadarSnapshot> = snapshotMutable.asStateFlow()

    val readinessMutable = MutableStateFlow(initialReadiness)
    override val readinessState: StateFlow<ScannerReadinessState> = readinessMutable.asStateFlow()

    override val environmentHealth: StateFlow<EnvironmentHealthReport> = MutableStateFlow(
        EnvironmentHealthReport(state = EnvironmentHealthState.WAITING_FOR_DATA)
    )

    override val feedbackController: ProximityFeedbackController = FakeNetworksFeedbackController()

    var nextManualScanResult: ScanTriggerResult = ScanTriggerResult.Triggered(
        remainingTokens = 3,
        unthrottledMode = false,
    )
    val manualScanBurstArgs = mutableListOf<Boolean>()
    var refreshReadinessCallCount: Int = 0
    val reportedPermissionDenials = mutableListOf<Boolean>()
    val savedNicknames = LinkedHashMap<String, String>()

    override fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
    ): Flow<ProximityTelemetry> = flowOf(
        ProximityTelemetry(
            targetKey = targetKey,
            displayTitle = "Test Target",
            displaySubtitle = "Test Subtitle",
            lockStatus = SignalLockStatus.LOCKED,
            smoothedRssiDbm = -52.0,
        )
    )

    override fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): Flow<SpectrumAnalysis> = flowOf(
        SpectrumAnalysis(
            band = band,
            minFreqMhz = 5150,
            maxFreqMhz = 5850,
            includeDfs = includeDfs,
            excludeConnectedSsid = excludeOwnSsid,
        )
    )

    override fun requestManualScan(burstMode: Boolean): ScanTriggerResult {
        manualScanBurstArgs.add(burstMode)
        return nextManualScanResult
    }

    override fun refreshReadiness() {
        refreshReadinessCallCount++
    }

    override fun reportPermissionDenial(permanentlyDenied: Boolean) {
        reportedPermissionDenials.add(permanentlyDenied)
    }

    override suspend fun setBssidNickname(bssid: String, nickname: String?) {
        val norm = MacAddressUtils.normalizeBssid(bssid)
        val cleaned = nickname?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned == null) {
            savedNicknames.remove(norm)
        } else {
            savedNicknames[norm] = cleaned
        }

        val current = snapshotMutable.value
        val updatedRadios = current.radios.map { radio ->
            if (MacAddressUtils.normalizeBssid(radio.bssid) == norm) {
                val baseName = if (radio.ssid.isHidden) {
                    DecodedSsid.HIDDEN_NETWORK_LABEL
                } else {
                    radio.ssid.displayName
                }
                radio.copy(
                    customNickname = cleaned,
                    effectiveDisplayName = cleaned ?: baseName,
                )
            } else {
                radio
            }
        }
        snapshotMutable.value = current.copy(radios = updatedRadios, groupedNetworks = emptyList())
    }

    override suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double) = Unit
    override suspend fun clearOneMeterCalibration(bssid: String) = Unit

    override fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload =
        ExportPayload(
            format = format,
            fileName = "radar.json",
            mimeType = format.mimeType,
            content = "{}",
            redactedMacs = redactMacs,
            generatedAtEpochMs = 0L,
            networkCount = 0,
            sizeBytes = 2,
        )
}

object NetworksTestFixtures {

    fun createRadio(
        bssid: String,
        ssidName: String,
        isHidden: Boolean = false,
        customNickname: String? = null,
        rssiDbm: Int = -54,
        band: WifiBand = WifiBand.BAND_5_GHZ,
        channel: Int = 36,
        freqMhz: Int = 5180,
        width: ChannelWidth = ChannelWidth.WIDTH_80_MHZ,
        isDfs: Boolean = false,
        wifiGen: WifiGeneration = WifiGeneration.WIFI_6,
        vendorLabel: String = "ASUSUSTek Computer",
        isLaa: Boolean = false,
        inferredParentVendor: String? = null,
        grade: SecurityGrade = SecurityGrade.A_PLUS,
        protocol: SecurityProtocol = SecurityProtocol.WPA3_SAE,
        securityScore: Int = 96,
        isEvilTwin: Boolean = false,
        congestionScore: Int = 88,
        clusterId: String = "cluster_${MacAddressUtils.normalizeBssid(bssid)}",
        isConnected: Boolean = false,
        bssLoad: BssLoadInfo? = BssLoadInfo(
            stationCount = 12,
            channelUtilizationRaw = 61,
            channelUtilizationPct = 24,
            availableAdmissionCapacity32Us = 1200,
        ),
        wpsModel: String? = "RT-AX86U Pro",
        rnrNeighbors: List<Colocated6GhzNeighbor> = emptyList(),
        lastSeenElapsedMs: Long = 10_000L,
    ): BssidRadio {
        val normMac = MacAddressUtils.normalizeBssid(bssid)
        val decodedSsid = DecodedSsid(
            displayName = if (isHidden) DecodedSsid.HIDDEN_NETWORK_LABEL else ssidName,
            rawUtf8OrNull = if (isHidden) null else ssidName,
            isHidden = isHidden,
            isNullBytePadded = isHidden,
        )
        val tier = SignalQualityTier.fromRssiDbm(rssiDbm)
        val quality = SemanticFormatters.signalQualityPct(rssiDbm)
        val subBand = when {
            band == WifiBand.BAND_2_4_GHZ -> UniiSubBand.ISM_2_4_GHZ
            band == WifiBand.BAND_6_GHZ -> UniiSubBand.UNII_5
            isDfs -> UniiSubBand.UNII_2A
            else -> UniiSubBand.UNII_1
        }
        val descriptor = ChannelDescriptor(
            band = band,
            primaryFreqMhz = freqMhz,
            primaryChannel = channel,
            centerFreq0Mhz = freqMhz + (width.widthMhz / 2 - 10).coerceAtLeast(0),
            centerChannel0 = channel,
            width = width,
            occupiedStartFreqMhz = freqMhz - width.widthMhz / 2,
            occupiedEndFreqMhz = freqMhz + width.widthMhz / 2,
            uniiSubBand = subBand,
            isCanonicalPrimary = true,
            isDfs = isDfs,
            isPsc = band == WifiBand.BAND_6_GHZ,
        )
        val securityFamily = when (protocol) {
            SecurityProtocol.OPEN -> SecurityFamily.OPEN_FAMILY
            SecurityProtocol.OWE_ENHANCED_OPEN -> SecurityFamily.OWE_FAMILY
            SecurityProtocol.WEP -> SecurityFamily.WEP_FAMILY
            SecurityProtocol.WPA2_ENTERPRISE,
            SecurityProtocol.WPA3_ENTERPRISE,
            SecurityProtocol.WPA3_ENTERPRISE_192 -> SecurityFamily.WPA_ENTERPRISE_FAMILY
            else -> SecurityFamily.WPA_PERSONAL_FAMILY
        }
        val pmf = if (protocol == SecurityProtocol.WPA3_SAE) {
            PmfState.REQUIRED
        } else if (protocol == SecurityProtocol.OPEN) {
            PmfState.NOT_APPLICABLE
        } else {
            PmfState.CAPABLE_OPTIONAL
        }

        return BssidRadio(
            bssid = normMac,
            ssid = decodedSsid,
            customNickname = customNickname,
            effectiveDisplayName = customNickname ?: decodedSsid.displayName,
            rssiDbm = rssiDbm,
            smoothedRssiDbm = rssiDbm.toDouble(),
            signalQualityPct = quality,
            signalTier = tier,
            estimatedSnrDb = (rssiDbm - (-92)).toDouble(),
            channelDescriptor = descriptor,
            wifiGeneration = wifiGen,
            vendorInfo = VendorInfo(
                ouiPrefix = MacAddressUtils.extractOuiPrefix(normMac),
                vendorName = vendorLabel,
                displayLabel = vendorLabel,
                isLocallyAdministered = isLaa,
                inferredParentVendor = inferredParentVendor,
            ),
            security = SecurityAssessment(
                grade = grade,
                score0To100 = securityScore,
                protocol = protocol,
                securityFamily = securityFamily,
                groupCipher = if (protocol == SecurityProtocol.OPEN) CipherSuite.NONE else CipherSuite.CCMP_128,
                pairwiseCiphers = if (protocol == SecurityProtocol.OPEN) emptyList() else listOf(CipherSuite.CCMP_128),
                akmSuites = when (protocol) {
                    SecurityProtocol.WPA3_SAE -> listOf(AkmSuite.SAE, AkmSuite.FT_OVER_SAE)
                    SecurityProtocol.OPEN -> listOf(AkmSuite.NONE)
                    else -> listOf(AkmSuite.PSK)
                },
                pmfState = pmf,
                saeH2eSupported = protocol == SecurityProtocol.WPA3_SAE,
                isEvilTwinSuspect = isEvilTwin,
                plainEnglishLabel = protocol.plainEnglishSummary,
                warnings = if (protocol == SecurityProtocol.OPEN) {
                    listOf("Traffic is unencrypted over the air")
                } else {
                    emptyList()
                },
            ),
            beaconDetails = BeaconIeDetails(
                bssLoad = bssLoad,
                phyCapability = PhyCapability(
                    wifiGeneration = wifiGen,
                    maxSpatialStreams = 2,
                    mimoLabel = "2×2 MIMO",
                    estimatedMaxPhyMbps = 2402,
                    htSupported = true,
                    vhtSupported = band != WifiBand.BAND_2_4_GHZ,
                    heSupported = wifiGen.isWifi6OrNewer,
                    suBeamformer = true,
                    muMimoSupported = true,
                    twtResponder = wifiGen.isWifi6OrNewer,
                ),
                bssColor = if (wifiGen.isWifi6OrNewer) {
                    BssColorInfo(colorCode = 19, hasCollisionOnChannel = false)
                } else {
                    null
                },
                colocated6GhzNeighbors = rnrNeighbors,
                wpsDevice = wpsModel?.let {
                    WpsDeviceInfo(
                        manufacturer = vendorLabel,
                        modelName = it,
                        deviceName = "Main Mesh Node",
                    )
                },
                roaming = RoamingCapabilities(
                    supports80211k = true,
                    supports80211v = true,
                    supports80211r = true,
                ),
                timDtim = TimDtimInfo(dtimCount = 0, dtimPeriod = 2),
                country = CountryInfo(countryCode = "US", regulatoryEnvironment = "Indoor/Outdoor"),
                tpcApTxPowerDbm = 21,
            ),
            physicalClusterId = clusterId,
            is80211mcResponder = true,
            isConnected = isConnected,
            estimatedDistanceMeters = 3.2,
            channelCongestionScore0To100 = congestionScore,
            lastSeenElapsedMs = lastSeenElapsedMs,
            ageMs = 1_000L,
        )
    }

    fun sampleRichSnapshot(): RadarSnapshot {
        val rnrNeighbor = Colocated6GhzNeighbor(
            sourceBssid = "AA:BB:CC:10:00:01",
            sourceSsid = "AuroraMesh",
            operatingClass = 134,
            channelNumber = 37,
            centerFreqMhz = 6185,
            widthMhz = 160,
            neighborBssid = "AA:BB:CC:10:6E:01",
            isSameSsid = true,
            isColocatedAp = true,
            isPscChannel = true,
        )

        val mesh5gPrimary = createRadio(
            bssid = "AA:BB:CC:10:00:01",
            ssidName = "AuroraMesh",
            rssiDbm = -46,
            band = WifiBand.BAND_5_GHZ,
            channel = 36,
            freqMhz = 5180,
            width = ChannelWidth.WIDTH_80_MHZ,
            clusterId = "cluster_node_1",
            isConnected = true,
            rnrNeighbors = listOf(rnrNeighbor),
        )
        val mesh24gPrimary = createRadio(
            bssid = "AA:BB:CC:10:00:02",
            ssidName = "AuroraMesh",
            rssiDbm = -53,
            band = WifiBand.BAND_2_4_GHZ,
            channel = 6,
            freqMhz = 2437,
            width = ChannelWidth.WIDTH_20_MHZ,
            clusterId = "cluster_node_1",
            wpsModel = "RT-AX86U Pro",
        )
        val mesh5gHallway = createRadio(
            bssid = "AA:BB:CC:20:00:01",
            ssidName = "AuroraMesh",
            rssiDbm = -64,
            band = WifiBand.BAND_5_GHZ,
            channel = 149,
            freqMhz = 5745,
            width = ChannelWidth.WIDTH_80_MHZ,
            clusterId = "cluster_node_2",
            wpsModel = "ZenWiFi XD6",
        )

        val hiddenOne = createRadio(
            bssid = "AA:BB:CC:99:00:01",
            ssidName = "",
            isHidden = true,
            rssiDbm = -61,
            band = WifiBand.BAND_5_GHZ,
            channel = 44,
            freqMhz = 5220,
            vendorLabel = "Ubiquiti Inc",
            wpsModel = null,
        )
        val hiddenTwo = createRadio(
            bssid = "AA:BB:CC:99:00:02",
            ssidName = "",
            isHidden = true,
            rssiDbm = -73,
            band = WifiBand.BAND_2_4_GHZ,
            channel = 11,
            freqMhz = 2462,
            vendorLabel = "Netgear",
            wpsModel = null,
        )

        val openCafe = createRadio(
            bssid = "06:22:33:44:55:66",
            ssidName = "CornerEspresso",
            rssiDbm = -78,
            band = WifiBand.BAND_2_4_GHZ,
            channel = 1,
            freqMhz = 2412,
            width = ChannelWidth.WIDTH_20_MHZ,
            wifiGen = WifiGeneration.WIFI_4,
            vendorLabel = "TP-Link Systems",
            isLaa = true,
            inferredParentVendor = "TP-Link Systems",
            grade = SecurityGrade.F,
            protocol = SecurityProtocol.OPEN,
            securityScore = 15,
            congestionScore = 32,
            bssLoad = null,
            wpsModel = null,
        )

        val meshGroup = EssNetworkGroup(
            groupKey = "ess:AuroraMesh:WPA_PERSONAL_FAMILY",
            ssid = mesh5gPrimary.ssid,
            displayName = "AuroraMesh",
            isMeshOrMultiAp = true,
            physicalClusterCount = 2,
            radios = listOf(mesh5gPrimary, mesh24gPrimary, mesh5gHallway),
            physicalClusterIds = listOf("cluster_node_1", "cluster_node_2"),
            strongestRadio = mesh5gPrimary,
            bands = setOf(WifiBand.BAND_2_4_GHZ, WifiBand.BAND_5_GHZ),
            maxWifiGeneration = WifiGeneration.WIFI_6,
            worstSecurityAssessment = mesh5gPrimary.security,
            bestSecurityAssessment = mesh5gPrimary.security,
            hasEvilTwinWarning = false,
            containsConnectedRadio = true,
        )

        return RadarSnapshot(
            timestampElapsedMs = 10_000L,
            timestampEpochMs = 1_760_000_000_000L,
            radios = listOf(
                mesh5gPrimary,
                mesh24gPrimary,
                mesh5gHallway,
                hiddenOne,
                hiddenTwo,
                openCafe,
            ),
            groupedNetworks = listOf(meshGroup),
            scanTelemetry = ScanTelemetry(
                lastScanTimestampMs = 9_000L,
                lastScanAgeMs = 1_000L,
                availableTokens = 3,
                maxTokens = 4,
            ),
            connectedWifi = ConnectedWifiObservation(
                bssid = "AA:BB:CC:10:00:01",
                ssid = "AuroraMesh",
                rssiDbm = -46,
                frequencyMhz = 5180,
                txLinkSpeedMbps = 1201,
                rxLinkSpeedMbps = 1201,
                ipAddressV4 = "192.168.1.42",
                timestampElapsedMs = 10_000L,
            ),
            radioCapabilities = DeviceRadioCapabilities(
                deviceModel = "SM-A556B",
                has24Ghz = true,
                has5Ghz = true,
                has6Ghz = false,
            ),
        )
    }
}
