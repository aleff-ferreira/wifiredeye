package com.a55.wifiradar.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SerializationRoundTripTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Test
    fun cliScenarioInput_parsesProjectSpecFixtureCleanly() {
        val specJson = """
            {
              "deviceHas6Ghz": false,
              "frames": [
                {
                  "elapsedMs": 1000,
                  "connectedBssid": "aa:bb:cc:00:00:01",
                  "scans": [
                    {
                      "ssid": "GalaxyA55_Lab_5G",
                      "rawSsidHex": null,
                      "bssid": "aa:bb:cc:00:00:01",
                      "rssiDbm": -54,
                      "frequencyMhz": 5180,
                      "centerFreq0Mhz": 5210,
                      "centerFreq1Mhz": 0,
                      "channelWidthMhz": 80,
                      "wifiStandard": "11ax",
                      "capabilities": "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
                      "ies": [
                        { "id": 11, "idExt": 0, "hex": "0c00400000" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = json.decodeFromString<CliScenarioInput>(specJson)
        assertFalse(parsed.deviceHas6Ghz)
        assertEquals(1, parsed.frames.size)
        assertEquals("aa:bb:cc:00:00:01", parsed.frames[0].connectedBssid)
        assertEquals(1, parsed.frames[0].scans.size)
        val scan = parsed.frames[0].scans[0]
        assertEquals("GalaxyA55_Lab_5G", scan.ssid)
        assertEquals(-54, scan.rssiDbm)
        assertEquals(80, scan.channelWidthMhz)
        assertEquals(11, scan.ies[0].id)
        assertEquals("0c00400000", scan.ies[0].hex)
    }

    @Test
    fun diagnosticExportDocumentAndSealedHierarchies_roundTripWithFullFidelity() {
        val radio = sampleRadio()
        val cluster = PhysicalApCluster(
            clusterId = "cluster-1",
            primaryLabel = "Lab Router",
            vendorInfo = radio.vendorInfo,
            matchReason = ClusterMatchReason.SINGLE_RADIO,
            radios = listOf(radio),
            strongestRssiDbm = radio.rssiDbm,
            bands = setOf(WifiBand.BAND_5_GHZ),
            containsConnectedRadio = true,
        )
        val group = EssNetworkGroup(
            groupKey = "ess:Lab_5G",
            ssid = radio.ssid,
            displayName = "Lab_5G",
            isMeshOrMultiAp = false,
            physicalClusterCount = 1,
            radios = listOf(radio),
            physicalClusterIds = listOf(cluster.clusterId),
            strongestRadio = radio,
            bands = setOf(WifiBand.BAND_5_GHZ),
            maxWifiGeneration = WifiGeneration.WIFI_6,
            worstSecurityAssessment = radio.security,
            bestSecurityAssessment = radio.security,
            containsConnectedRadio = true,
        )
        val spectrum5 = SpectrumAnalysis(
            band = WifiBand.BAND_5_GHZ,
            minFreqMhz = 5170,
            maxFreqMhz = 5835,
            perChannelBreakdowns = mapOf(
                36 to CciAciBreakdown(
                    targetChannel = 36,
                    targetBand = WifiBand.BAND_5_GHZ,
                    cciPlainEnglishSummary = "0 shared-channel routers",
                    aciPlainEnglishSummary = "0 overlapping routers",
                )
            ),
        )
        val doc = DiagnosticExportDocument(
            generatedAtEpochMs = 1_763_155_200_000L,
            redactedMacs = false,
            deviceCapabilities = DeviceRadioCapabilities(),
            healthReport = EnvironmentHealthReport(
                state = EnvironmentHealthState.SCORED,
                overallScore0To100 = 91,
                headlineLabel = SemanticFormatters.formatHealthScoreHeadline(91),
                summarySentence = "Clean 5 GHz Wi-Fi 6 spectrum with WPA3 protection.",
                subScores = HealthSubScores(94, 92, 90, 88),
                insights = listOf(
                    ActionableInsight(
                        id = "insight-1",
                        severity = InsightSeverity.SUGGESTION,
                        category = InsightCategory.CHANNEL_OPTIMIZATION,
                        title = "Channel 36 is wide open",
                        plainEnglishBody = "Zero neighbor interference detected on 5 GHz.",
                        actionTarget = InsightActionTarget.OpenSpectrumBand(WifiBand.BAND_5_GHZ, 36),
                    ),
                    ActionableInsight(
                        id = "insight-2",
                        severity = InsightSeverity.GOOD_INFO,
                        category = InsightCategory.SIGNAL_COVERAGE,
                        title = "Track router proximity",
                        plainEnglishBody = "Launch proximity radar to verify mesh placement.",
                        actionTarget = InsightActionTarget.LaunchProximity(
                            ProximityTargetKey.BssidLock("AA:BB:CC:00:00:01", "Lab_5G")
                        ),
                    ),
                ),
            ),
            spectrum24Ghz = SpectrumAnalysis(band = WifiBand.BAND_2_4_GHZ, minFreqMhz = 2400, maxFreqMhz = 2495),
            spectrum5Ghz = spectrum5,
            spectrum6Ghz = SpectrumAnalysis(
                band = WifiBand.BAND_6_GHZ,
                minFreqMhz = 5925,
                maxFreqMhz = 7125,
                isBandSupportedByHardware = false,
                hardwareExplanationMessage = "Galaxy A55 5G radio supports 2.4 & 5 GHz Wi-Fi 6.",
            ),
            detectedRadios = listOf(radio),
            groupedNetworks = listOf(group),
            physicalClusters = listOf(cluster),
            proximityTrajectory = listOf(
                ProximityTelemetry(
                    targetKey = ProximityTargetKey.BssidLock("AA:BB:CC:00:00:01", "Lab_5G"),
                    resolvedBssid = "AA:BB:CC:00:00:01",
                    displayTitle = "Lab_5G",
                    displaySubtitle = "Tracking single physical router",
                    band = WifiBand.BAND_5_GHZ,
                    channel = 36,
                    frequencyMhz = 5180,
                    lockStatus = SignalLockStatus.LOCKED,
                    smoothedRssiDbm = -51.4,
                    rawRssiDbm = -52,
                    signalQualityPct = 97,
                    signalTier = SignalQualityTier.GOOD,
                )
            ),
        )

        val encoded = json.encodeToString(doc)
        val decoded = json.decodeFromString<DiagnosticExportDocument>(encoded)
        assertEquals(doc, decoded)

        val readinessStates: List<ScannerReadinessState> = listOf(
            ScannerReadinessState.Ready(),
            ScannerReadinessState.LocationDisabled(ReadinessChecklist.allReady()),
            ScannerReadinessState.MissingPermission(listOf("android.permission.ACCESS_FINE_LOCATION"), ReadinessChecklist.allReady()),
        )
        val encodedReadiness = json.encodeToString(readinessStates)
        assertEquals(readinessStates, json.decodeFromString<List<ScannerReadinessState>>(encodedReadiness))
    }

    private fun sampleRadio(): BssidRadio = BssidRadio(
        bssid = "AA:BB:CC:00:00:01",
        ssid = DecodedSsid(displayName = "Lab_5G", rawUtf8OrNull = "Lab_5G", byteLength = 6),
        effectiveDisplayName = "Lab_5G",
        rssiDbm = -52,
        smoothedRssiDbm = -51.8,
        signalQualityPct = 96,
        signalTier = SignalQualityTier.GOOD,
        estimatedSnrDb = 43.0,
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
        wifiStandardRawCode = 6,
        vendorInfo = VendorInfo(ouiPrefix = "AA:BB:CC", vendorName = "ASUS", displayLabel = "ASUS"),
        security = SecurityAssessment(
            grade = SecurityGrade.A_PLUS,
            score0To100 = 98,
            protocol = SecurityProtocol.WPA3_SAE,
            securityFamily = SecurityFamily.WPA_PERSONAL_FAMILY,
            pmfState = PmfState.REQUIRED,
            plainEnglishLabel = "WPA3-Personal · Strong Modern Password Protection",
        ),
        beaconDetails = BeaconIeDetails(
            phyCapability = PhyCapability(
                wifiGeneration = WifiGeneration.WIFI_6,
                maxSpatialStreams = 2,
                mimoLabel = "2×2 MIMO",
                estimatedMaxPhyMbps = 1201,
                heSupported = true,
            ),
        ),
        physicalClusterId = "cluster-1",
        isConnected = true,
        estimatedDistanceMeters = 2.4,
    )
}
