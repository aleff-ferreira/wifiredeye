package com.a55.wifiradar.feature.networks

import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.DecodedSsid
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.EssNetworkGroup
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworksProjectorTest {

    @Test
    fun hiddenSsids_neverMergeInGroupedMode_evenWhenUpstreamGroupAttemptsToCombineThem() {
        val hiddenA = NetworksTestFixtures.createRadio(
            bssid = "aa:bb:cc:00:00:02",
            ssidName = "",
            isHidden = true,
            rssiDbm = -58,
        )
        val hiddenB = NetworksTestFixtures.createRadio(
            bssid = "aa:bb:cc:00:00:01",
            ssidName = "",
            isHidden = true,
            rssiDbm = -58,
        )
        val badUpstreamMergedHiddenGroup = EssNetworkGroup(
            groupKey = "bad_hidden_merge",
            ssid = DecodedSsid(displayName = DecodedSsid.HIDDEN_NETWORK_LABEL, isHidden = true),
            displayName = DecodedSsid.HIDDEN_NETWORK_LABEL,
            isMeshOrMultiAp = true,
            physicalClusterCount = 2,
            radios = listOf(hiddenA, hiddenB),
            physicalClusterIds = listOf("c1", "c2"),
            strongestRadio = hiddenA,
            bands = setOf(WifiBand.BAND_5_GHZ),
            maxWifiGeneration = WifiGeneration.WIFI_6,
            worstSecurityAssessment = hiddenA.security,
            bestSecurityAssessment = hiddenA.security,
        )

        val snapshot = RadarSnapshot(
            radios = listOf(hiddenA, hiddenB),
            groupedNetworks = listOf(badUpstreamMergedHiddenGroup),
        )

        val result = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "",
            activeFilters = setOf(NetworkFilterChip.ALL),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )

        assertEquals(2, result.groupedItems.size)
        assertTrue(result.groupedItems.all { it.radios.size == 1 && !it.isMeshOrMultiAp })
        // Lexical tie-break at identical -58 dBm puts 00:00:01 before 00:00:02
        assertEquals("AA:BB:CC:00:00:01", result.groupedItems[0].strongestRadio.bssid)
        assertEquals("AA:BB:CC:00:00:02", result.groupedItems[1].strongestRadio.bssid)
    }

    @Test
    fun multiTokenSearch_matchesSsidColonAgnosticBssidVendorNicknameWpsModelChannelAndSecurity() {
        val base = NetworksTestFixtures.sampleRichSnapshot()
        val nicknamedRadio = base.radios.first().copy(
            customNickname = "Upstairs Studio",
            effectiveDisplayName = "Upstairs Studio",
        )
        val snapshot = base.copy(
            radios = listOf(nicknamedRadio) + base.radios.drop(1),
            groupedNetworks = emptyList(),
        )

        // Nickname + channel token
        val byNickname = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "studio ch36",
            activeFilters = setOf(NetworkFilterChip.ALL),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(1, byNickname.visibleRadioCount)
        assertEquals("AA:BB:CC:10:00:01", byNickname.flatRadioItems.single().bssid)

        // Colon-stripped BSSID
        val byBareMac = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "aabbcc990002",
            activeFilters = setOf(NetworkFilterChip.ALL),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(1, byBareMac.visibleRadioCount)
        assertEquals("AA:BB:CC:99:00:02", byBareMac.flatRadioItems.single().bssid)

        // WPS model + vendor
        val byWpsModel = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "zenwifi xd6",
            activeFilters = setOf(NetworkFilterChip.ALL),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(1, byWpsModel.visibleRadioCount)
        assertEquals("AA:BB:CC:20:00:01", byWpsModel.flatRadioItems.single().bssid)
    }

    @Test
    fun filterChips_unionSelectedBands_intersectAttributes_andRestoreAllWhenEmpty() {
        val snapshot = NetworksTestFixtures.sampleRichSnapshot()

        // Toggle logic: selecting 2.4 GHz + 5 GHz drops ALL; deselecting both restores ALL
        val step1 = NetworksProjector.toggleFilterSelection(
            setOf(NetworkFilterChip.ALL),
            NetworkFilterChip.BAND_2_4_GHZ,
        )
        assertEquals(setOf(NetworkFilterChip.BAND_2_4_GHZ), step1)

        val step2 = NetworksProjector.toggleFilterSelection(step1, NetworkFilterChip.BAND_5_GHZ)
        assertEquals(setOf(NetworkFilterChip.BAND_2_4_GHZ, NetworkFilterChip.BAND_5_GHZ), step2)

        val unionResult = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "",
            activeFilters = step2,
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(6, unionResult.visibleRadioCount)

        // Intersect 5 GHz AND Strong >= -65 dBm AND Wi-Fi 6+
        val intersected = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "",
            activeFilters = setOf(
                NetworkFilterChip.BAND_5_GHZ,
                NetworkFilterChip.STRONG_SIGNAL,
                NetworkFilterChip.WIFI_6_PLUS,
            ),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertTrue(intersected.flatRadioItems.all {
            it.channelDescriptor.band == WifiBand.BAND_5_GHZ &&
                it.rssiDbm >= -65 &&
                it.wifiGeneration.isWifi6OrNewer
        })
        assertEquals(3, intersected.visibleRadioCount)

        // Open / Weak Security filter isolates CornerEspresso (Grade F Open)
        val weakOnly = NetworksProjector.project(
            snapshot = snapshot,
            searchQuery = "",
            activeFilters = setOf(NetworkFilterChip.OPEN_OR_WEAK_SECURITY),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(1, weakOnly.visibleRadioCount)
        assertEquals("CornerEspresso", weakOnly.flatRadioItems.single().ssid.displayName)

        // Deselecting single chip restores ALL
        val backToAll = NetworksProjector.toggleFilterSelection(step1, NetworkFilterChip.BAND_2_4_GHZ)
        assertEquals(setOf(NetworkFilterChip.ALL), backToAll)
    }

    @Test
    fun allFiveSortOptions_andDeterministicLexicalBssidTieBreak() {
        val ap1 = NetworksTestFixtures.createRadio(
            bssid = "AA:BB:CC:00:00:09",
            ssidName = "ZetaNet",
            rssiDbm = -60,
            band = WifiBand.BAND_5_GHZ,
            channel = 149,
            freqMhz = 5745,
            congestionScore = 50,
            grade = SecurityGrade.A_PLUS,
            protocol = SecurityProtocol.WPA3_SAE,
            securityScore = 95,
        )
        val ap2 = NetworksTestFixtures.createRadio(
            bssid = "AA:BB:CC:00:00:02",
            ssidName = "AlphaNet",
            rssiDbm = -60,
            band = WifiBand.BAND_2_4_GHZ,
            channel = 1,
            freqMhz = 2412,
            congestionScore = 92,
            grade = SecurityGrade.F,
            protocol = SecurityProtocol.OPEN,
            securityScore = 10,
        )
        val snapshot = RadarSnapshot(radios = listOf(ap1, ap2))

        // Identical -60 dBm -> lexical BSSID tie-break puts 00:00:02 first
        val strongest = NetworksProjector.project(
            snapshot,
            "",
            setOf(NetworkFilterChip.ALL),
            NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(listOf("AA:BB:CC:00:00:02", "AA:BB:CC:00:00:09"), strongest.flatRadioItems.map { it.bssid })

        // Name A-Z -> AlphaNet then ZetaNet
        val nameAz = NetworksProjector.project(
            snapshot,
            "",
            setOf(NetworkFilterChip.ALL),
            NetworkSortOption.NAME_A_TO_Z,
        )
        assertEquals(listOf("AlphaNet", "ZetaNet"), nameAz.flatRadioItems.map { it.ssid.displayName })

        // Channel Low-High -> 2412 MHz Ch 1 before 5745 MHz Ch 149
        val channelAsc = NetworksProjector.project(
            snapshot,
            "",
            setOf(NetworkFilterChip.ALL),
            NetworkSortOption.CHANNEL_LOW_TO_HIGH,
        )
        assertEquals(listOf(1, 149), channelAsc.flatRadioItems.map { it.channelDescriptor.primaryChannel })

        // Least Congested -> score 92 (Wide Open) before 50
        val leastCongested = NetworksProjector.project(
            snapshot,
            "",
            setOf(NetworkFilterChip.ALL),
            NetworkSortOption.LEAST_CONGESTED_CHANNEL,
        )
        assertEquals(listOf(92, 50), leastCongested.flatRadioItems.map { it.channelCongestionScore0To100 })

        // Security Risk -> lowest securityScore (10, Grade F) first
        val securityRisk = NetworksProjector.project(
            snapshot,
            "",
            setOf(NetworkFilterChip.ALL),
            NetworkSortOption.SECURITY_RISK,
        )
        assertEquals(listOf("AA:BB:CC:00:00:02", "AA:BB:CC:00:00:09"), securityRisk.flatRadioItems.map { it.bssid })
    }

    @Test
    fun sixGhzFilterOnDualBandHardware_enablesBannerAndHarvestsRnrNeighbors() {
        val base = NetworksTestFixtures.sampleRichSnapshot()
        val projectedDualBand = NetworksProjector.project(
            snapshot = base,
            searchQuery = "",
            activeFilters = setOf(NetworkFilterChip.BAND_6_GHZ),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertTrue(projectedDualBand.showSixGhzHardwareBanner)
        assertEquals(1, projectedDualBand.rnr6GhzNeighbors.size)
        assertEquals(37, projectedDualBand.rnr6GhzNeighbors.single().neighbor.channelNumber)

        val triBandSnapshot = base.copy(
            radioCapabilities = DeviceRadioCapabilities(has6Ghz = true)
        )
        val projectedTriBand = NetworksProjector.project(
            snapshot = triBandSnapshot,
            searchQuery = "",
            activeFilters = setOf(NetworkFilterChip.BAND_6_GHZ),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertFalse(projectedTriBand.showSixGhzHardwareBanner)
    }

    @Test
    fun duplicateBssidsInAdversarialSnapshot_areDeduplicatedCleanly() {
        val ap = NetworksTestFixtures.createRadio(bssid = "aa:bb:cc:11:22:33", ssidName = "DupTest")
        val apLowerDup = ap.copy(bssid = "AA-BB-CC-11-22-33", rssiDbm = -70)
        val result = NetworksProjector.project(
            snapshot = RadarSnapshot(radios = listOf(ap, apLowerDup)),
            searchQuery = "",
            activeFilters = setOf(NetworkFilterChip.ALL),
            sortOption = NetworkSortOption.STRONGEST_SIGNAL,
        )
        assertEquals(1, result.totalUnfilteredRadioCount)
        assertEquals(1, result.visibleRadioCount)
        assertEquals(1, result.groupedItems.size)
    }
}
