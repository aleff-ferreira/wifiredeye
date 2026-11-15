package com.a55.wifiradar.feature.spectrum

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.a55.wifiradar.core.model.ChannelScore
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.SavedStateKeys
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SpectralCurve
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.WifiBand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpectrumViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val screamingSnakeRegex = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun bandSwitching_dfsAndExcludeOwnSsid_recomputesSpectrumAndUnlocks6GhzRnr() = runTest {
        val repository = FakeSpectrumWifiRadarRepository(has6GhzHardware = false)
        val viewModel = SpectrumViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            val initial24 = awaitItem()
            assertEquals(WifiBand.BAND_2_4_GHZ, initial24.selectedBand)
            assertEquals(3, initial24.curves.size)
            assertEquals(11, initial24.recommendation?.recommendedPrimaryChannel)
            assertFalse(initial24.show6GhzHardwareBanner)

            // Exclude own connected SSID
            viewModel.setExcludeConnectedSsid(true)
            val excluded24 = expectMostRecentItem()
            assertTrue(excluded24.excludeConnectedSsid)
            assertEquals(2, excluded24.curves.size)
            assertTrue(excluded24.curves.none { it.isConnected })

            // Switch to 5 GHz (non-DFS recommends Ch 149)
            viewModel.setExcludeConnectedSsid(false)
            viewModel.selectBand(WifiBand.BAND_5_GHZ)
            val state5NonDfs = expectMostRecentItem()
            assertEquals(WifiBand.BAND_5_GHZ, state5NonDfs.selectedBand)
            assertEquals(149, state5NonDfs.recommendation?.recommendedPrimaryChannel)

            // Enable DFS -> unlocks Ch 100 recommendation & TDWR Ch 120
            viewModel.setIncludeDfs(true)
            val state5Dfs = expectMostRecentItem()
            assertTrue(state5Dfs.includeDfs)
            assertEquals(100, state5Dfs.recommendation?.recommendedPrimaryChannel)
            assertTrue(state5Dfs.channelScores.any { it.channel == 120 && it.isTdwr })

            // Switch to 6 GHz on Galaxy A55 5G -> hardware banner + deduplicated RNR neighbors + sanitized SM-A556B
            viewModel.selectBand(WifiBand.BAND_6_GHZ)
            val state6 = expectMostRecentItem()
            assertEquals(WifiBand.BAND_6_GHZ, state6.selectedBand)
            assertTrue(state6.show6GhzHardwareBanner)
            val body = state6.hardwareExplanationBody
            assertNotNull(body)
            assertFalse(
                "Hardware explanation must sanitize SM_A556B underscore leak: $body",
                screamingSnakeRegex.containsMatchIn(body.orEmpty()),
            )
            assertEquals(2, state6.colocated6GhzNeighbors.size)
            assertEquals(37, state6.colocated6GhzNeighbors.first().channelNumber)
            assertEquals(101, state6.recommendation?.recommendedPrimaryChannel)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selectingCurve_stripsSelfFromCciBreakdown_andSelectingChannelClearsBssid() = runTest {
        val repository = FakeSpectrumWifiRadarRepository()
        val viewModel = SpectrumViewModel(repository, SavedStateHandle())

        viewModel.uiState.test {
            awaitItem()

            // Select HomeMesh-2G (AA:BB:CC:00:00:01) on Ch 6 -> self BSSID must be stripped from CCI list
            viewModel.selectBssid("aa-bb-cc-00-00-01")
            val selectedState = expectMostRecentItem()
            assertEquals("AA:BB:CC:00:00:01", selectedState.selectedBssid)
            assertNotNull(selectedState.selectedCurve)
            val breakdown = selectedState.activeBreakdown
            assertNotNull(breakdown)
            assertTrue(
                "Selected radio must never be listed as an interferer against itself",
                breakdown!!.cciInterferers.none { it.bssid.equals("AA:BB:CC:00:00:01", ignoreCase = true) },
            )
            assertEquals(1, breakdown.cciInterferers.size)
            assertEquals("Apt2B-Guest", breakdown.cciInterferers.single().displayLabel)
            assertEquals(1, breakdown.actionableCciCount)

            // Tapping same BSSID again clears selection
            viewModel.selectBssid("AA:BB:CC:00:00:01")
            assertNull(expectMostRecentItem().selectedBssid)

            // Re-select BSSID, then select Channel 11 -> BSSID clears and Channel 11 breakdown becomes active
            viewModel.selectBssid("AA:BB:CC:00:00:01")
            assertNotNull(expectMostRecentItem().selectedBssid)
            viewModel.selectChannel(11)
            val channelSelected = expectMostRecentItem()
            assertNull(channelSelected.selectedBssid)
            assertEquals(11, channelSelected.highlightedChannel)
            assertEquals(11, channelSelected.activeBreakdown?.targetChannel)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun savedStateHandle_persistsAllControlsAndExternalDeepLinkNonceAcrossRecreation() = runTest {
        val repository = FakeSpectrumWifiRadarRepository()
        val sharedHandle = SavedStateHandle()
        val firstVm = SpectrumViewModel(repository, sharedHandle)

        firstVm.applyExternalDeepLink(
            band = WifiBand.BAND_5_GHZ,
            highlightChannel = 149,
            requestNonce = 42L,
        )
        firstVm.setIncludeDfs(true)
        firstVm.setExcludeConnectedSsid(true)
        // User subsequently switches to 6 GHz after consuming deep link nonce 42
        firstVm.selectBand(WifiBand.BAND_6_GHZ)

        assertEquals(WifiBand.BAND_6_GHZ.name, sharedHandle.get<String>(SavedStateKeys.SPECTRUM_SELECTED_BAND))
        assertEquals(true, sharedHandle.get<Boolean>(SavedStateKeys.SPECTRUM_INCLUDE_DFS))
        assertEquals(true, sharedHandle.get<Boolean>(SavedStateKeys.SPECTRUM_EXCLUDE_OWN_SSID))

        // Recreate ViewModel with same SavedStateHandle and re-fire same nonce 42 -> must NOT clobber 6 GHz!
        val recreatedVm = SpectrumViewModel(repository, sharedHandle)
        recreatedVm.applyExternalDeepLink(
            band = WifiBand.BAND_5_GHZ,
            highlightChannel = 149,
            requestNonce = 42L,
        )

        recreatedVm.uiState.test {
            val restored = expectMostRecentItem()
            assertEquals(WifiBand.BAND_6_GHZ, restored.selectedBand)
            assertTrue(restored.includeDfs)
            assertTrue(restored.excludeConnectedSsid)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun manualScanFeedback_andHostileOutlierData_neverCrashOrProduceNaN() = runTest {
        val repository = FakeSpectrumWifiRadarRepository()
        repository.manualScanResult = ScanTriggerResult.ThrottledByTokenBucket(nextTokenInMs = 18_200L)
        val vm = SpectrumViewModel(repository, SavedStateHandle())

        vm.uiState.test {
            awaitItem()
            vm.requestManualScan()
            val withFeedback = expectMostRecentItem()
            assertNotNull(withFeedback.scanActionFeedback)
            assertTrue(withFeedback.scanActionFeedback!!.contains("19s"))
            assertFalse(SemanticFormatters.containsRawEnumLeak(withFeedback.scanActionFeedback!!))

            vm.dismissScanFeedback()
            assertNull(expectMostRecentItem().scanActionFeedback)
            cancelAndIgnoreRemainingEvents()
        }

        // Verify hostile outlier SpectrumAnalysis handling directly in SpectrumDomainResolver
        val hostileAnalysis = SpectrumAnalysis(
            band = WifiBand.BAND_5_GHZ,
            minFreqMhz = 0,
            maxFreqMhz = 0,
            curves = listOf(
                SpectralCurve(
                    bssid = "AA:BB:CC:99:99:01",
                    ssid = "OutlierMin",
                    displayLabel = "OutlierMin",
                    band = WifiBand.BAND_5_GHZ,
                    primaryChannel = 36,
                    primaryFreqMhz = 5180,
                    centerFreq0Mhz = 5210,
                    width = ChannelWidth.WIDTH_320_MHZ,
                    rssiDbm = Int.MIN_VALUE,
                    linearPowerMw = Double.NaN,
                    lobeStartFreqMhz = 0,
                    lobeEndFreqMhz = 0,
                    puncturedSubchannelBitmap = -1,
                ),
                SpectralCurve(
                    bssid = "AA:BB:CC:99:99:02",
                    ssid = "OutlierZero",
                    displayLabel = "OutlierZero",
                    band = WifiBand.BAND_5_GHZ,
                    primaryChannel = 48,
                    primaryFreqMhz = 5240,
                    centerFreq0Mhz = 5210,
                    width = ChannelWidth.WIDTH_80_MHZ,
                    rssiDbm = 0,
                    linearPowerMw = Double.POSITIVE_INFINITY,
                    lobeStartFreqMhz = 5170,
                    lobeEndFreqMhz = 5250,
                ),
            ),
            channelScores = listOf(
                ChannelScore(
                    band = WifiBand.BAND_5_GHZ,
                    channel = 36,
                    centerFreqMhz = 5210,
                    evaluatedWidth = ChannelWidth.WIDTH_80_MHZ,
                    score0To100 = 150,
                    starRating0To5 = Double.NaN,
                    equivalentNoiseFloorDbm = Double.POSITIVE_INFINITY,
                    cciCount = 1,
                    aciCount = 1,
                    isCanonical = true,
                    plainEnglishRating = "Wide Open",
                )
            ),
        )

        val hostileUi = SpectrumDomainResolver.buildUiState(
            selectedBand = WifiBand.BAND_5_GHZ,
            includeDfs = true,
            excludeConnectedSsid = false,
            selectedBssid = "AA:BB:CC:99:99:01",
            highlightedChannel = null,
            snapshot = RadarSnapshot(),
            readinessState = ScannerReadinessState.Ready(),
            analysis = hostileAnalysis,
            scanActionFeedback = null,
        )
        assertEquals(5150, hostileUi.minFreqMhz)
        assertEquals(5895, hostileUi.maxFreqMhz)
        assertEquals(-100, SpectrumDomainResolver.safeCanvasRssiDbm(hostileUi.curves[0].rssiDbm))
        assertEquals(-20, SpectrumDomainResolver.safeCanvasRssiDbm(hostileUi.curves[1].rssiDbm))
        assertNotNull(hostileUi.activeBreakdown)
        assertTrue(hostileUi.activeBreakdown!!.totalAciPowerMw.isFinite())
    }
}
