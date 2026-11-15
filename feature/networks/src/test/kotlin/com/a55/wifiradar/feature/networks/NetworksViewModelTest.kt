package com.a55.wifiradar.feature.networks

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import com.a55.wifiradar.core.model.ScanTriggerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class NetworksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savedStateHandle_roundTripsQueryViewModeFiltersSortExpandedGroupsAndOpenDetailBssid() = runTest {
        val repo = FakeNetworksWifiRadarRepository()
        val sharedHandle = SavedStateHandle()

        val vm1 = NetworksViewModel(repository = repo, savedStateHandle = sharedHandle)
        vm1.uiState.test {
            awaitItem()
            vm1.setSearchQuery("Aurora")
            vm1.setViewMode(NetworkViewMode.ALL_RADIOS_BSSID)
            vm1.toggleFilterChip(NetworkFilterChip.BAND_5_GHZ)
            vm1.setSortOption(NetworkSortOption.CHANNEL_LOW_TO_HIGH)
            vm1.toggleGroupExpanded("ess:AuroraMesh:WPA_PERSONAL_FAMILY")
            vm1.openDetailSheet("aa:bb:cc:10:00:01")
            advanceUntilIdle()
            expectMostRecentItem()
        }

        // Simulate ViewModel recreation from persisted SavedStateHandle
        val vm2 = NetworksViewModel(repository = repo, savedStateHandle = sharedHandle)
        vm2.uiState.test {
            val restored = awaitItem()
            assertEquals("Aurora", restored.searchQuery)
            assertEquals(NetworkViewMode.ALL_RADIOS_BSSID, restored.viewMode)
            assertEquals(setOf(NetworkFilterChip.BAND_5_GHZ), restored.activeFilters)
            assertEquals(NetworkSortOption.CHANNEL_LOW_TO_HIGH, restored.sortOption)
            assertTrue("ess:AuroraMesh:WPA_PERSONAL_FAMILY" in restored.expandedGroupKeys)
            assertEquals("AA:BB:CC:10:00:01", restored.detailSheet?.requestedBssid)
            assertTrue(restored.detailSheet?.isCurrentlyInRange == true)

            vm2.resetFilters()
            vm2.dismissDetailSheet()
            advanceUntilIdle()
            val afterReset = expectMostRecentItem()
            assertEquals("", afterReset.searchQuery)
            assertEquals(setOf(NetworkFilterChip.ALL), afterReset.activeFilters)
            assertNull(afterReset.detailSheet)
        }
    }

    @Test
    fun detailSheet_keepsLastSeenRadioAndShowsDropoutAgeWhenRouterLeavesSnapshot() = runTest {
        val repo = FakeNetworksWifiRadarRepository()
        val vm = NetworksViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            awaitItem()
            vm.openDetailSheet("AA:BB:CC:20:00:01")
            advanceUntilIdle()
            val openLive = expectMostRecentItem()
            assertNotNull(openLive.detailSheet)
            assertTrue(openLive.detailSheet!!.isCurrentlyInRange)
            assertEquals("ZenWiFi XD6", openLive.detailSheet!!.radio?.beaconDetails?.wpsDevice?.modelName)

            // Emit new snapshot 15s later without AA:BB:CC:20:00:01
            val current = repo.snapshotMutable.value
            repo.snapshotMutable.value = current.copy(
                timestampElapsedMs = 25_000L,
                radios = current.radios.filterNot { it.bssid == "AA:BB:CC:20:00:01" },
                groupedNetworks = emptyList(),
            )
            advanceUntilIdle()
            val droppedOut = expectMostRecentItem()
            assertNotNull(droppedOut.detailSheet)
            assertFalse(droppedOut.detailSheet!!.isCurrentlyInRange)
            assertNotNull(droppedOut.detailSheet!!.radio)
            assertEquals(15_000L, droppedOut.detailSheet!!.dropoutAgeMs)

            // Re-introduce router in subsequent sweep -> auto-recovers in-range status
            repo.snapshotMutable.value = current.copy(timestampElapsedMs = 28_000L)
            advanceUntilIdle()
            val recovered = expectMostRecentItem()
            assertTrue(recovered.detailSheet!!.isCurrentlyInRange)
        }
    }

    @Test
    fun saveNickname_andManualScanFeedback_updateStateDeterministically() = runTest {
        val repo = FakeNetworksWifiRadarRepository()
        val vm = NetworksViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            awaitItem()
            vm.saveBssidNickname("aa:bb:cc:99:00:01", "Garage Hidden AP")
            advanceUntilIdle()
            val withNick = expectMostRecentItem()
            assertEquals("Garage Hidden AP", repo.savedNicknames["AA:BB:CC:99:00:01"])
            val updatedHidden = withNick.flatRadioItems.first { it.bssid == "AA:BB:CC:99:00:01" }
            assertEquals("Garage Hidden AP", updatedHidden.effectiveDisplayName)

            repo.nextManualScanResult = ScanTriggerResult.ThrottledByTokenBucket(nextTokenInMs = 17_200L)
            vm.requestManualScan()
            advanceUntilIdle()
            val throttledState = expectMostRecentItem()
            assertTrue(throttledState.lastScanFeedbackMessage!!.contains("18s"))
        }
    }
}
