package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ClusterMatchReason
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.SemanticFormatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusteringSecurityAndEvilTwinTest {

    @Test
    fun clustering_handlesTriBandMloVirtualGuestMeshSeparationAndHiddenNonGrouping() {
        val engine = DefaultRfAnalyticsEngine()

        val entries = listOf(
            // Physical Router 1 (ASUS Dual-Band + Virtual Guest VAP on 5 GHz sharing 5-octet prefix 04:92:26:AA:BB)
            RawScanEntry(
                bssid = "04:92:26:AA:BB:01",
                rawSsidBytes = "HomePro".encodeToByteArray(),
                rssiDbm = -48,
                frequencyMhz = 2412,
                capabilities = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            RawScanEntry(
                bssid = "04:92:26:AA:BB:02",
                rawSsidBytes = "HomePro".encodeToByteArray(),
                rssiDbm = -53,
                frequencyMhz = 5180,
                centerFreq0Mhz = 5210,
                channelWidthCode = 2,
                capabilities = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            RawScanEntry(
                bssid = "06:92:26:AA:BB:03", // LAA bit set virtual guest SSID on same 5 GHz radio!
                rawSsidBytes = "HomePro_Guest".encodeToByteArray(),
                rssiDbm = -54,
                frequencyMhz = 5180,
                centerFreq0Mhz = 5210,
                channelWidthCode = 2,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            // Physical Router 2 (Second Mesh Node upstairs on 5 GHz Ch 149 sharing SSID "HomePro"!)
            RawScanEntry(
                bssid = "04:92:26:AA:BB:09",
                rawSsidBytes = "HomePro".encodeToByteArray(),
                rssiDbm = -68,
                frequencyMhz = 5745,
                centerFreq0Mhz = 5775,
                channelWidthCode = 2,
                capabilities = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            // Two distinct Hidden Networks -> MUST NEVER be merged into one EssNetworkGroup!
            RawScanEntry(
                bssid = "50:C7:BF:10:20:01",
                rawSsidBytes = ByteArray(0),
                rssiDbm = -64,
                frequencyMhz = 2437,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            RawScanEntry(
                bssid = "20:4E:7F:30:40:01",
                rawSsidBytes = ByteArray(6) { 0 },
                rssiDbm = -71,
                frequencyMhz = 5220,
                capabilities = "[WPA2-PSK-CCMP][ESS]",
                wifiStandardCode = 6,
            ),
            // Wi-Fi 7 MLO pair across different OUI octets sharing apMldMacAddress
            RawScanEntry(
                bssid = "04:18:D6:11:11:01",
                rawSsidBytes = "WiFi7_MLO".encodeToByteArray(),
                rssiDbm = -50,
                frequencyMhz = 5180,
                apMldMacAddress = "04:18:D6:00:00:FF",
                capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                wifiStandardCode = 8,
            ),
            RawScanEntry(
                bssid = "04:18:D6:99:99:02",
                rawSsidBytes = "WiFi7_MLO".encodeToByteArray(),
                rssiDbm = -55,
                frequencyMhz = 5975,
                apMldMacAddress = "04:18:D6:00:00:FF",
                capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                wifiStandardCode = 8,
            ),
        )

        val snapshot = engine.processScanSnapshot(
            rawBatch = RawScanBatch(entries = entries, receivedElapsedMs = 1_000L),
            currentElapsedMs = 1_000L,
        )

        assertEquals(8, snapshot.radios.size)

        // Verify Wi-Fi 7 MLD pair merged into single PhysicalApCluster with MLD_MAC reason
        val mloCluster = snapshot.physicalClusters.first { it.apMldMacAddress == "04:18:D6:00:00:FF" }
        assertEquals(ClusterMatchReason.MLD_MAC, mloCluster.matchReason)
        assertEquals(2, mloCluster.radios.size)

        // Verify "HomePro" spans 2 physical clusters (Router 1 + Router 2 upstairs) -> isMeshOrMultiAp = true!
        val homeProGroup = snapshot.groupedNetworks.first { it.displayName == "HomePro" }
        assertEquals(3, homeProGroup.radios.size)
        assertEquals(2, homeProGroup.physicalClusterCount)
        assertTrue(homeProGroup.isMeshOrMultiAp)

        // Verify "HomePro_Guest" virtual VAP clustered onto Physical Router 1 alongside 04:92:26:AA:BB:01/02
        val guestRadio = snapshot.radios.first { it.ssid.displayName == "HomePro_Guest" }
        val router1Main5G = snapshot.radios.first { it.bssid == "04:92:26:AA:BB:02" }
        assertEquals(router1Main5G.physicalClusterId, guestRadio.physicalClusterId)

        // Verify two Hidden Networks remain strictly separate in groupedNetworks
        val hiddenGroups = snapshot.groupedNetworks.filter { it.ssid.isHidden }
        assertEquals(2, hiddenGroups.size)
        assertTrue(hiddenGroups.all { it.radios.size == 1 && !it.isMeshOrMultiAp })
        assertTrue(hiddenGroups.any { it.groupKey == "ess:hidden:50:C7:BF:10:20:01" })
        assertTrue(hiddenGroups.any { it.groupKey == "ess:hidden:20:4E:7F:30:40:01" })
    }

    @Test
    fun roamTrackingAndMissedBeaconAntiFlicker_emitRoamEventsAndEvictAfterTtl() {
        val engine = DefaultRfAnalyticsEngine()
        val node1 = RawScanEntry(
            bssid = "04:92:26:00:01:01",
            rawSsidBytes = "MeshHome".encodeToByteArray(),
            rssiDbm = -55,
            frequencyMhz = 5180,
            capabilities = "[WPA2-PSK-CCMP][ESS]",
            timestampMicros = 1_000_000L,
        )
        val node2 = RawScanEntry(
            bssid = "04:92:26:00:02:01",
            rawSsidBytes = "MeshHome".encodeToByteArray(),
            rssiDbm = -72,
            frequencyMhz = 2412,
            capabilities = "[WPA2-PSK-CCMP][ESS]",
            timestampMicros = 1_000_000L,
        )

        // t = 1s: connected to node1 on 5 GHz
        val snap1 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(listOf(node1, node2), receivedElapsedMs = 1_000L),
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:00:01:01",
                ssid = "\"MeshHome\"",
                rssiDbm = -55,
                frequencyMhz = 5180,
                timestampElapsedMs = 1_000L,
            ),
            currentElapsedMs = 1_000L,
        )
        assertTrue(snap1.roamEvents.isEmpty())

        // t = 21s: node2 missed in latest sweep (age 20s <= 45s -> retained with isMissedInLatestSweep = true & isStale = true!)
        val snap2 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                listOf(node1.copy(rssiDbm = -78, timestampMicros = 21_000_000L)),
                receivedElapsedMs = 21_000L,
            ),
            connectedWifi = ConnectedWifiObservation(
                bssid = "04:92:26:00:02:01", // Roamed to node2 on 2.4 GHz!
                ssid = "\"MeshHome\"",
                rssiDbm = -60,
                frequencyMhz = 2412,
                timestampElapsedMs = 21_000L,
            ),
            currentElapsedMs = 21_000L,
        )
        assertEquals(2, snap2.radios.size)
        assertEquals(1, snap2.roamEvents.size)
        val roam = snap2.roamEvents[0]
        assertEquals("04:92:26:00:01:01", roam.previousBssid)
        assertEquals("04:92:26:00:02:01", roam.newBssid)
        assertEquals(-5, roam.rssiDeltaDb) // -60 - (-55)
        assertTrue(roam.isBandSwitch)

        // t = 50s: disconnected, node2 unseen for 29s -> retained as missed & stale
        val snap3 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                listOf(node1.copy(timestampMicros = 50_000_000L)),
                receivedElapsedMs = 50_000L,
            ),
            connectedWifi = null,
            currentElapsedMs = 50_000L,
        )
        val retainedNode2 = snap3.radios.first { it.bssid == "04:92:26:00:02:01" }
        assertTrue(retainedNode2.isMissedInLatestSweep)
        assertTrue(retainedNode2.isStale)

        // t = 75s: node2 unseen for 54s (> 45s anti-flicker window) -> omitted from active snapshot!
        val snap4 = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                listOf(node1.copy(timestampMicros = 75_000_000L)),
                receivedElapsedMs = 75_000L,
            ),
            connectedWifi = null,
            currentElapsedMs = 75_000L,
        )
        assertEquals(1, snap4.radios.size)
        assertEquals("04:92:26:00:01:01", snap4.radios[0].bssid)
    }

    @Test
    fun securityAnalyzer_gradesAllProtocolsAndDetectsCrossClusterEvilTwinWithoutFalsePositives() {
        val dummyDesc = ChannelAndFrequencyResolver.resolve(5180)
        fun assessCaps(caps: String, secTypes: List<Int> = emptyList(), ies: List<RawInformationElement> = emptyList()) =
            SecurityAnalyzer.assess(
                capabilities = caps,
                securityTypes = secTypes,
                ieResult = BeaconIeInspector.inspect(ies, "04:92:26:00:00:01", "Test", null, 6, dummyDesc),
            )

        val open = assessCaps("[ESS]")
        assertEquals(SecurityGrade.F, open.grade)
        assertEquals(SecurityProtocol.OPEN, open.protocol)
        assertEquals(0, open.score0To100)

        val wep = assessCaps("[WEP][ESS]", listOf(1))
        assertEquals(SecurityGrade.F, wep.grade)
        assertEquals(SecurityProtocol.WEP, wep.protocol)

        val tkip = assessCaps("[WPA-PSK-TKIP][WPA2-PSK-TKIP][ESS]")
        assertEquals(SecurityGrade.D, tkip.grade)

        val wpa2Wps = assessCaps("[WPA2-PSK-CCMP][WPS][ESS]")
        assertEquals(SecurityGrade.C, wpa2Wps.grade)
        assertTrue(wpa2Wps.wpsEnabled)

        val wpa2Clean = assessCaps("[WPA2-PSK-CCMP][ESS]")
        assertEquals(SecurityGrade.B, wpa2Clean.grade)

        val transition = assessCaps("[WPA2-PSK-CCMP][RSN-SAE-CCMP][MFPC][ESS]")
        assertEquals(SecurityGrade.A_MINUS, transition.grade)
        assertTrue(transition.isTransitionMode)

        val owe = assessCaps("[RSN-OWE-CCMP][MFPR][ESS]", listOf(6))
        assertEquals(SecurityGrade.A, owe.grade)
        assertEquals(SecurityProtocol.OWE_ENHANCED_OPEN, owe.protocol)

        val wpa3Sae = assessCaps(
            caps = "[RSN-SAE-CCMP][MFPR][ESS]",
            secTypes = listOf(4),
            ies = listOf(RawInformationElement(id = 244, bytes = byteArrayOf(0x20))),
        )
        assertEquals(SecurityGrade.A_PLUS, wpa3Sae.grade)
        assertTrue(wpa3Sae.saeH2eSupported)
        assertTrue(wpa3Sae.score0To100 >= 95)

        val suiteB = assessCaps("[RSN-EAP_SUITE_B_192-GCMP-256][MFPR][ESS]", listOf(5))
        assertEquals(SecurityGrade.A_PLUS, suiteB.grade)
        assertEquals(SecurityProtocol.WPA3_ENTERPRISE_192, suiteB.protocol)

        // Verify zero raw enum leaks across all security labels & warnings
        for (sec in listOf(open, wep, tkip, wpa2Wps, wpa2Clean, transition, owe, wpa3Sae, suiteB)) {
            assertFalse(SemanticFormatters.containsRawEnumLeak(sec.plainEnglishLabel))
            sec.warnings.forEach { w -> assertFalse(SemanticFormatters.containsRawEnumLeak(w)) }
        }

        // Evil-Twin test: legitimate WPA3 router + rogue Open clone on external hardware cluster -> fires alert!
        // Meanwhile, an OWE Transition Open radio on the SAME physical cluster does NOT false-trigger!
        val engine = DefaultRfAnalyticsEngine()
        val snap = engine.processScanSnapshot(
            rawBatch = RawScanBatch(
                entries = listOf(
                    RawScanEntry(
                        bssid = "04:92:26:10:10:01",
                        rawSsidBytes = "CorpVault".encodeToByteArray(),
                        rssiDbm = -52,
                        frequencyMhz = 5180,
                        capabilities = "[RSN-SAE-CCMP][MFPR][ESS]",
                    ),
                    RawScanEntry(
                        bssid = "50:C7:BF:99:88:01", // Separate vendor/cluster broadcasting Open "CorpVault"!
                        rawSsidBytes = "CorpVault".encodeToByteArray(),
                        rssiDbm = -46,
                        frequencyMhz = 2437,
                        capabilities = "[ESS]",
                    ),
                    // Same-chassis guest/transition pair on "CafeSafe" -> must NOT trigger EvilTwinAlert
                    RawScanEntry(
                        bssid = "04:18:D6:55:55:01",
                        rawSsidBytes = "CafeSafe".encodeToByteArray(),
                        rssiDbm = -55,
                        frequencyMhz = 5180,
                        capabilities = "[WPA2-PSK-CCMP][ESS]",
                    ),
                    RawScanEntry(
                        bssid = "06:18:D6:55:55:02",
                        rawSsidBytes = "CafeSafe".encodeToByteArray(),
                        rssiDbm = -56,
                        frequencyMhz = 5180,
                        capabilities = "[ESS]",
                    ),
                ),
                receivedElapsedMs = 2_000L,
            ),
            currentElapsedMs = 2_000L,
        )

        val health = engine.computeEnvironmentHealth(snap)
        assertEquals(1, health.evilTwinAlerts.size)
        assertEquals("CorpVault", health.evilTwinAlerts[0].ssid)
        assertEquals(listOf("50:C7:BF:99:88:01"), health.evilTwinAlerts[0].suspectOpenBssids)

        val rogueRadio = snap.radios.first { it.bssid == "50:C7:BF:99:88:01" }
        assertTrue(rogueRadio.security.isEvilTwinSuspect)
        assertTrue(snap.groupedNetworks.first { it.displayName == "CorpVault" }.hasEvilTwinWarning)
        assertFalse(snap.groupedNetworks.first { it.displayName == "CafeSafe" }.hasEvilTwinWarning)
    }
}
