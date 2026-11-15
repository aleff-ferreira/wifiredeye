package com.a55.wifiradar.feature.proximity

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.SignalLockStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProximityViewModelTest {

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
    fun smartInitialFallback_prefersConnectedBssidOverStrongerUnconnectedMeshNode() = runTest {
        // In sampleSnapshot(), AA:BB:CC:10:00:01 is Connected (-51 dBm) while AA:BB:CC:20:00:02 is stronger (-44 dBm)
        val repo = FakeProximityWifiRadarRepository()
        val vm = ProximityViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            advanceUntilIdle()
            val state = expectMostRecentItem()
            assertEquals(
                ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:10:00:01", ssidHint = "AuroraMesh"),
                state.activeTargetKey,
            )
            assertTrue(state.telemetry?.isConnectedFastPath == true)
        }
    }

    @Test
    fun smartInitialFallback_whenDisconnected_locksStrongestWithLexicalTieBreak_andNeverJumpsDuringDropout() = runTest {
        val tieA = ProximityTestFixtures.makeRadio(bssid = "AA:BB:CC:00:00:09", ssid = "TieA", rssiDbm = -55)
        val tieB = ProximityTestFixtures.makeRadio(bssid = "AA:BB:CC:00:00:02", ssid = "TieB", rssiDbm = -55)
        val repo = FakeProximityWifiRadarRepository(
            initialSnapshot = RadarSnapshot(radios = listOf(tieA, tieB), connectedWifi = null)
        )
        val vm = ProximityViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            // Lexical BSSID tie-break selects 00:00:02 over 00:00:09
            assertEquals(
                ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:00:00:02", ssidHint = "TieB"),
                initial.activeTargetKey,
            )

            // Now remove 00:00:02 from scan while 00:00:09 becomes super strong (-38 dBm)
            repo.snapshotMutable.value = RadarSnapshot(
                radios = listOf(tieA.copy(rssiDbm = -38, smoothedRssiDbm = -38.0)),
                connectedWifi = null,
            )
            advanceUntilIdle()
            val afterDropout = expectMostRecentItem()

            // Target MUST stay locked on 00:00:02 in SIGNAL_LOST instead of hijacking to 00:00:09!
            assertEquals(
                ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:00:00:02", ssidHint = "TieB"),
                afterDropout.activeTargetKey,
            )
            assertEquals(SignalLockStatus.SIGNAL_LOST, afterDropout.telemetry?.lockStatus)
        }
    }

    @Test
    fun smartInitialFallback_staysNullWhenEmpty_thenAutoLocksOnFirstScanArrival() = runTest {
        val repo = FakeProximityWifiRadarRepository(initialSnapshot = RadarSnapshot())
        val vm = ProximityViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            advanceUntilIdle()
            val emptyState = expectMostRecentItem()
            assertNull(emptyState.activeTargetKey)

            // First wireless sweep arrives
            repo.snapshotMutable.value = ProximityTestFixtures.sampleSnapshot()
            advanceUntilIdle()
            val lockedState = expectMostRecentItem()
            assertEquals(
                ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:10:00:01", ssidHint = "AuroraMesh"),
                lockedState.activeTargetKey,
            )
        }
    }

    @Test
    fun savedStateHandle_roundTripsColonSsid_lockModeToggle_andBlocksHiddenNetworkFromMeshMode() = runTest {
        val repo = FakeProximityWifiRadarRepository()
        val handle = SavedStateHandle()

        val vm1 = ProximityViewModel(repository = repo, savedStateHandle = handle)
        vm1.uiState.test {
            awaitItem()
            // Select SSID containing colons ("Studio:RTT:Lab")
            vm1.selectTarget(
                ProximityTargetKey.BssidLock(
                    bssid = "aa:bb:cc:77:00:07",
                    ssidHint = "Studio:RTT:Lab",
                )
            )
            vm1.setLockMode(ProximityLockMode.FOLLOW_MESH_SSID)
            vm1.setEnvironment(PropagationEnvironment.HEAVY_WALLS)
            advanceUntilIdle()
            val updated = expectMostRecentItem()
            assertEquals(
                ProximityTargetKey.FollowMeshSsid(
                    ssid = "Studio:RTT:Lab",
                    preferredBssid = "AA:BB:CC:77:00:07",
                ),
                updated.activeTargetKey,
            )
        }

        // Recreate ViewModel with same SavedStateHandle -> colon SSID & Heavy Concrete survive intact!
        val vm2 = ProximityViewModel(repository = repo, savedStateHandle = handle)
        vm2.uiState.test {
            advanceUntilIdle()
            val restored = expectMostRecentItem()
            assertEquals(
                ProximityTargetKey.FollowMeshSsid(
                    ssid = "Studio:RTT:Lab",
                    preferredBssid = "AA:BB:CC:77:00:07",
                ),
                restored.activeTargetKey,
            )
            assertEquals(PropagationEnvironment.HEAVY_WALLS, restored.environment)

            // Now select Hidden Network -> canFollowMeshSsid becomes false and setLockMode(FOLLOW_MESH_SSID) is blocked
            vm2.selectTarget(ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:99:00:09", ssidHint = null))
            advanceUntilIdle()
            val hiddenState = expectMostRecentItem()
            assertFalse(hiddenState.canFollowMeshSsid)

            vm2.setLockMode(ProximityLockMode.FOLLOW_MESH_SSID)
            advanceUntilIdle()
            assertEquals(
                ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:99:00:09", ssidHint = null),
                vm2.uiState.value.activeTargetKey,
            )
        }
    }

    @Test
    fun calibrateOneMeter_clearCalibration_burstScan_resetStats_andNoVibratorGuard() = runTest {
        val repo = FakeProximityWifiRadarRepository(hasVibrator = false)
        val vm = ProximityViewModel(repository = repo, savedStateHandle = SavedStateHandle())

        vm.uiState.test {
            advanceUntilIdle()
            val initial = expectMostRecentItem()
            assertFalse(initial.hasHardwareVibrator)
            assertFalse(initial.feedbackSettings.hapticsEnabled)
            assertFalse(initial.feedbackSettings.audioEnabled)

            // Enabling haptics when hasHardwareVibrator == false is ignored; audio works independently
            vm.setHapticsEnabled(true)
            vm.setAudioEnabled(true)
            advanceUntilIdle()
            val afterToggles = expectMostRecentItem()
            assertFalse(afterToggles.feedbackSettings.hapticsEnabled)
            assertTrue(afterToggles.feedbackSettings.audioEnabled)

            // Calibrate 1m on connected target AA:BB:CC:10:00:01 (-51.0 dBm)
            vm.calibrateOneMeter()
            advanceUntilIdle()
            val calibrated = expectMostRecentItem()
            assertTrue(calibrated.isCalibratedOneMeter)
            assertEquals(-51.0, repo.calibrationsMutable.value["AA:BB:CC:10:00:01"]!!, 0.01)

            vm.clearOneMeterCalibration()
            advanceUntilIdle()
            val cleared = expectMostRecentItem()
            assertFalse(cleared.isCalibratedOneMeter)

            // Burst scan & Reset stats
            repo.nextManualScanResult = ScanTriggerResult.ThrottledByTokenBucket(14_100L)
            vm.triggerBurstScan()
            vm.resetStats()
            advanceUntilIdle()
            val afterBurst = expectMostRecentItem()
            assertEquals(listOf(true), repo.manualScanBurstCalls)
            assertEquals(1, repo.resetStatsCalls.size)
            assertTrue(afterBurst.burstStatusFeedback!!.contains("15s"))
        }
    }
}
