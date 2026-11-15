package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DiagnosticExportDocument
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.InsightCategory
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.MloLinkInfo
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiBand
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class EnvironmentHealthAndExportRedactionTest {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Test
    fun environmentHealth_handlesEmptyCleanAndCrowdedInsecureSnapshotsDeterministically() {
        val engine = DefaultRfAnalyticsEngine()

        // 1. Empty snapshot -> WAITING_FOR_DATA (never NaN or fake 100/100!)
        val emptySnap = engine.processScanSnapshot(rawBatch = null, currentElapsedMs = 500L)
        val emptyHealth = engine.computeEnvironmentHealth(emptySnap)
        assertEquals(EnvironmentHealthState.WAITING_FOR_DATA, emptyHealth.state)
        assertNull(emptyHealth.overallScore0To100)
        assertNull(emptyHealth.subScores)
        assertEquals("Waiting for scan data", emptyHealth.headlineLabel)

        // 2. Clean Wi-Fi 6 WPA3 snapshot -> >= 85/100 Healthy
        val cleanSnap = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(
                        bssid = "04:92:26:12:34:01",
                        rawSsidBytes = "CleanHome_6".encodeToByteArray(),
                        rssiDbm = -47,
                        frequencyMhz = 5180,
                        centerFreq0Mhz = 5210,
                        channelWidthCode = 2,
                        wifiStandardCode = 6,
                        capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                        securityTypes = listOf(4),
                    )
                ),
                receivedElapsedMs = 2_000L,
            ),
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:12:34:01",
                ssid = "\"CleanHome_6\"",
                rssiDbm = -47,
                frequencyMhz = 5180,
                txLinkSpeedMbps = 1201,
                rxLinkSpeedMbps = 1201,
                timestampElapsedMs = 2_000L,
            ),
            currentElapsedMs = 2_000L,
        )
        val cleanHealth = engine.computeEnvironmentHealth(cleanSnap)
        assertEquals(EnvironmentHealthState.SCORED, cleanHealth.state)
        assertNotNull(cleanHealth.overallScore0To100)
        assertTrue("Expected clean WPA3 Wi-Fi 6 score >= 85, got ${cleanHealth.overallScore0To100}", cleanHealth.overallScore0To100!! >= 85)

        // 3. Crowded + off-center ACI + WEP + Evil-Twin -> significantly lower score & prioritized CRITICAL/WARNING cards
        val crowdedSnap = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(
                        bssid = "04:92:26:44:55:01",
                        rawSsidBytes = "OfficeNet".encodeToByteArray(),
                        rssiDbm = -76,
                        frequencyMhz = 2437,
                        capabilities = "[WPA2-PSK-CCMP][WPS][ESS]",
                    ),
                    // Rogue Evil-Twin Open clone on separate vendor!
                    RawScanEntry(
                        bssid = "50:C7:BF:66:77:02",
                        rawSsidBytes = "OfficeNet".encodeToByteArray(),
                        rssiDbm = -50,
                        frequencyMhz = 2437,
                        capabilities = "[ESS]",
                    ),
                    // Off-center Ch 4 WEP router causing ACI + weak security
                    RawScanEntry(
                        bssid = "20:4E:7F:88:99:03",
                        rawSsidBytes = "LegacyPrinter".encodeToByteArray(),
                        rssiDbm = -55,
                        frequencyMhz = 2427,
                        capabilities = "[WEP][ESS]",
                    ),
                ),
                receivedElapsedMs = 4_000L,
            ),
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:44:55:01",
                ssid = "\"OfficeNet\"",
                rssiDbm = -76,
                frequencyMhz = 2437,
                timestampElapsedMs = 4_000L,
            ),
            currentElapsedMs = 4_000L,
        )
        val crowdedHealth = engine.computeEnvironmentHealth(crowdedSnap)
        assertTrue(crowdedHealth.overallScore0To100!! < cleanHealth.overallScore0To100!! - 25)
        assertEquals(1, crowdedHealth.evilTwinAlerts.size)
        assertEquals(InsightSeverity.CRITICAL, crowdedHealth.insights.first().severity)
        assertTrue(crowdedHealth.insights.any { it.category == InsightCategory.INTERFERENCE_ACI_CCI })
        assertTrue(crowdedHealth.insights.any { it.category == InsightCategory.SECURITY })
    }

    @Test
    fun diagnosticExport_enforcesZeroMacLeaksUnderRedactionZeroEnumLeaksAndLocaleGermanySafety() {
        val savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val engine = DefaultRfAnalyticsEngine()

            val macMain1 = "04:92:26:4A:5B:01"
            val macMain2 = "04:92:26:6C:7D:02"
            val macMld = "04:92:26:8E:9F:99"
            val macRnr6G = "04:92:26:AB:CD:6E"
            val macRogue = "50:C7:BF:DE:F0:03"
            val macHidden = "20:4E:7F:13:57:04"
            val macEmbeddedInNick = "FE:DC:BA:98:76:54"

            val ieHeColor = RawInformationElement(id = 255, idExt = 36, bytes = byteArrayOf(0, 0, 0, 17, 0, 0))
            val ieRnr = RawInformationElement(
                id = 201,
                bytes = byteArrayOf(
                    0x00, 0x07, 133.toByte(), 37,
                    0x10, 0x04, 0x92.toByte(), 0x26, 0xAB.toByte(), 0xCD.toByte(), 0x6E,
                ),
            )

            // Frame 1: Connected to macMain1
            val snap1 = engine.processScanSnapshot(
                rawBatch = RawScanBatch(
                    entries = listOf(
                        RawScanEntry(
                            bssid = macMain1,
                            rawSsidBytes = "VaultMesh".encodeToByteArray(),
                            rssiDbm = -55,
                            frequencyMhz = 5180,
                            centerFreq0Mhz = 5210,
                            channelWidthCode = 2,
                            wifiStandardCode = 8,
                            capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                            apMldMacAddress = macMld,
                            affiliatedMloLinks = listOf(MloLinkInfo(linkId = 0, bssid = macMain1, band = WifiBand.BAND_5_GHZ, channel = 36, frequencyMhz = 5180)),
                            informationElements = listOf(ieHeColor, ieRnr),
                            timestampMicros = 1_000_000L,
                        ),
                        RawScanEntry(
                            bssid = macMain2,
                            rawSsidBytes = "VaultMesh".encodeToByteArray(),
                            rssiDbm = -50,
                            frequencyMhz = 5180, // Same Ch 36 & BSS Color 17 -> collidingBssids populated!
                            centerFreq0Mhz = 5210,
                            channelWidthCode = 2,
                            wifiStandardCode = 8,
                            capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                            informationElements = listOf(ieHeColor),
                            timestampMicros = 1_000_000L,
                        ),
                        RawScanEntry(
                            bssid = macRogue,
                            rawSsidBytes = "VaultMesh".encodeToByteArray(),
                            rssiDbm = -62,
                            frequencyMhz = 2437,
                            capabilities = "[ESS]", // Triggers EvilTwinAlert!
                            timestampMicros = 1_000_000L,
                        ),
                        RawScanEntry(
                            bssid = macHidden,
                            rawSsidBytes = ByteArray(0), // Unrevealed Hidden Network (ess:hidden:<mac>)!
                            rssiDbm = -70,
                            frequencyMhz = 2412,
                            capabilities = "[WPA2-PSK-CCMP][ESS]",
                            timestampMicros = 1_000_000L,
                        ),
                    ),
                    receivedElapsedMs = 1_000L,
                ),
                connectedWifi = ConnectedWifiObservation(
                    bssid = macMain1,
                    ssid = "\"VaultMesh\"",
                    rssiDbm = -55,
                    frequencyMhz = 5180,
                    apMldMacAddress = macMld,
                    timestampElapsedMs = 1_000L,
                ),
                bssidNicknames = mapOf(macMain1 to "Desk Router $macEmbeddedInNick"),
                currentElapsedMs = 1_000L,
                currentEpochMs = 1_763_155_200_000L,
            )
            val prox1 = engine.updateProximityTarget(
                targetKey = ProximityTargetKey.BssidLock(macMain1, "VaultMesh"),
                snapshot = snap1,
                currentElapsedMs = 1_000L,
            )

            // Frame 2: Roam from macMain1 -> macMain2 so roamEvents contains both MACs!
            val snap2 = engine.processScanSnapshot(
                rawBatch = RawScanBatch(
                    entries = snap1.radios.map { r ->
                        RawScanEntry(
                            bssid = r.bssid,
                            rawSsidBytes = if (r.ssid.isHidden) ByteArray(0) else r.ssid.displayName.encodeToByteArray(),
                            rssiDbm = r.rssiDbm,
                            frequencyMhz = r.channelDescriptor.primaryFreqMhz,
                            centerFreq0Mhz = r.channelDescriptor.centerFreq0Mhz,
                            channelWidthCode = r.channelDescriptor.width.androidCode.coerceAtLeast(0),
                            wifiStandardCode = r.wifiStandardRawCode,
                            capabilities = r.security.rawCapabilities,
                            apMldMacAddress = r.apMldMacAddress,
                            affiliatedMloLinks = r.mloLinks,
                            informationElements = if (r.bssid == macMain1) listOf(ieHeColor, ieRnr) else if (r.bssid == macMain2) listOf(ieHeColor) else emptyList(),
                            timestampMicros = 3_000_000L,
                        )
                    },
                    receivedElapsedMs = 3_000L,
                ),
                connectedWifi = ConnectedWifiObservation(
                    bssid = macMain2,
                    ssid = "\"VaultMesh\"",
                    rssiDbm = -49,
                    frequencyMhz = 5180,
                    timestampElapsedMs = 3_000L,
                ),
                bssidNicknames = mapOf(macMain1 to "Desk Router $macEmbeddedInNick"),
                currentElapsedMs = 3_000L,
                currentEpochMs = 1_763_155_202_000L,
            )
            val prox2 = engine.updateProximityTarget(
                targetKey = ProximityTargetKey.BssidLock(macMain1, "VaultMesh"),
                snapshot = snap2,
                currentElapsedMs = 3_000L,
            )
            val health2 = engine.computeEnvironmentHealth(snap2)

            // Unredacted JSON round-trip check
            val unredactedJson = engine.exportDiagnosticJson(snap2, health2, redactMacs = false, proximityHistory = listOf(prox1, prox2))
            val parsedUnredacted = jsonParser.decodeFromString<DiagnosticExportDocument>(unredactedJson)
            assertFalse(parsedUnredacted.redactedMacs)
            assertTrue(unredactedJson.contains(macMain1))

            // Redacted JSON & Markdown check
            val redactedJson = engine.exportDiagnosticJson(snap2, health2, redactMacs = true, proximityHistory = listOf(prox1, prox2))
            val redactedMd = engine.exportDiagnosticMarkdown(snap2, health2, redactMacs = true, proximityHistory = listOf(prox1, prox2))

            val parsedRedacted = jsonParser.decodeFromString<DiagnosticExportDocument>(redactedJson)
            assertTrue(parsedRedacted.redactedMacs)
            assertEquals(1, parsedRedacted.roamEvents.size)
            assertEquals(2, parsedRedacted.proximityTrajectory.size)

            val allRawMacs = listOf(macMain1, macMain2, macMld, macRnr6G, macRogue, macHidden, macEmbeddedInNick)
            val allMiddleOctetPairs = listOf("4A:5B", "6C:7D", "8E:9F", "AB:CD", "DE:F0", "13:57", "98:76")

            for (rawMac in allRawMacs) {
                assertFalse("Leaked raw MAC $rawMac in redacted JSON!", redactedJson.contains(rawMac, ignoreCase = true))
                assertFalse("Leaked raw MAC $rawMac in redacted Markdown!", redactedMd.contains(rawMac, ignoreCase = true))
            }
            for (midPair in allMiddleOctetPairs) {
                assertFalse("Leaked middle octets $midPair in redacted JSON!", redactedJson.contains(midPair, ignoreCase = true))
                assertFalse("Leaked middle octets $midPair in redacted Markdown!", redactedMd.contains(midPair, ignoreCase = true))
            }

            // Confirm OUI + tail octets ARE preserved in redacted output
            assertTrue(redactedJson.contains("04:92:26:XX:XX:01"))
            assertTrue(redactedMd.contains("04:92:26:XX:XX:01"))

            // Confirm ZERO raw enum leaks in Markdown and Locale.GERMANY did NOT introduce decimal commas like "4,5★"
            assertFalse("Raw enum leak in Markdown:\n$redactedMd", SemanticFormatters.containsRawEnumLeak(redactedMd))
            assertFalse("Locale comma leak in Markdown!", Regex("""\d,\d★""").containsMatchIn(redactedMd))
        } finally {
            Locale.setDefault(savedLocale)
        }
    }
}
