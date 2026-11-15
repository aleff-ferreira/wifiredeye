package com.a55.wifiradar.feature.insights

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.InsightSeverity
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.RssiSample
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
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
class InsightsStateMapperAndViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val screamingSnakeRegex = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")
    private val macAddressRegex = Regex("""^[0-9A-F]{2}(:[0-9A-F]{2}){5}$""")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun waitingForScanData_zeroApsOrErroneous100_neverEmits100OrNaN() {
        val emptySnapshot = RadarSnapshot(radios = emptyList())
        // Hostile case: upstream report claims SCORED + 100 while 0 APs exist
        val erroneousScoredReport = EnvironmentHealthReport(
            state = EnvironmentHealthState.SCORED,
            overallScore0To100 = 100,
            headlineLabel = "100 / 100 · Healthy Wireless Environment",
            securityAuditRadios = emptyList(),
        )

        val mapped = InsightsStateMapper.mapToUiState(
            snapshot = emptySnapshot,
            health = erroneousScoredReport,
            readinessState = ScannerReadinessState.Ready(),
            historyWindow = RssiHistoryWindow.FIVE_MINUTES,
            highlightedTimelineBssid = null,
            redactMacs = false,
            exportFormat = ExportFormat.MARKDOWN,
            isExportPreviewExpanded = true,
            showAllSecurityRows = false,
            showAllRoamEvents = false,
            exportPayload = null,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )

        assertTrue(mapped.isWaitingForScanData)
        assertNull(mapped.overallScore0To100)
        assertEquals("Waiting for scan data", mapped.healthHeadline)
        assertFalse(mapped.healthHeadline.contains("100"))
        assertFalse(mapped.healthHeadline.contains("NaN"))
        assertEquals(4, mapped.subScores.size)
        mapped.subScores.forEach { bar ->
            assertNull(bar.score0To100)
            assertTrue(bar.formattedValue.contains("Waiting for scan"))
            assertFalse(bar.formattedValue.contains("NaN"))
        }
    }

    @Test
    fun actionableInsights_sortedDeterministicallyBySeverityCategoryAndId() {
        val snapshot = FakeInsightsWifiRadarRepository.createPopulatedSnapshot()
        val health = FakeInsightsWifiRadarRepository.createPopulatedHealthReport(snapshot)

        val mapped = InsightsStateMapper.mapToUiState(
            snapshot = snapshot,
            health = health,
            readinessState = ScannerReadinessState.Ready(),
            historyWindow = RssiHistoryWindow.FIVE_MINUTES,
            highlightedTimelineBssid = null,
            redactMacs = false,
            exportFormat = ExportFormat.MARKDOWN,
            isExportPreviewExpanded = false,
            showAllSecurityRows = false,
            showAllRoamEvents = false,
            exportPayload = null,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )

        assertEquals(3, mapped.actionableInsights.size)
        assertEquals(InsightSeverity.CRITICAL, mapped.actionableInsights[0].severity)
        assertEquals(InsightSeverity.WARNING, mapped.actionableInsights[1].severity)
        assertEquals(InsightSeverity.SUGGESTION, mapped.actionableInsights[2].severity)
        // Default CTA label populated for OpenNetworkDetail when actionLabel was null
        assertEquals("Inspect Network", mapped.actionableInsights[1].actionLabel)
    }

    @Test
    fun rssiTimeGraph_windowFiltering_andDashedGapDetectionOnNullSampleAndMissedSweep() {
        val baseSnapshot = FakeInsightsWifiRadarRepository.createPopulatedSnapshot()
        // Inject NaN & Infinity smoothed samples alongside valid int samples to verify NaN immunity
        val hardenedTimelines = baseSnapshot.rssiTimelines.toMutableMap()
        hardenedTimelines["DD:EE:FF:33:44:05"] = listOf(
            RssiSample(timestampElapsedMs = 850_000L, rssiDbm = -66, smoothedRssiDbm = Double.NaN),
            RssiSample(timestampElapsedMs = 898_000L, rssiDbm = Int.MIN_VALUE, smoothedRssiDbm = Double.POSITIVE_INFINITY),
        )
        val snapshot = baseSnapshot.copy(rssiTimelines = hardenedTimelines)
        val health = FakeInsightsWifiRadarRepository.createPopulatedHealthReport(snapshot)

        // 5m window (600_000..900_000 ms): HomeMesh has 4 valid points (700k, null@710k, 720k, 785k [+65s gap!], 895k [+110s gap!])
        val state5m = InsightsStateMapper.mapToUiState(
            snapshot = snapshot,
            health = health,
            readinessState = ScannerReadinessState.Ready(),
            historyWindow = RssiHistoryWindow.FIVE_MINUTES,
            highlightedTimelineBssid = null,
            redactMacs = false,
            exportFormat = ExportFormat.MARKDOWN,
            isExportPreviewExpanded = false,
            showAllSecurityRows = false,
            showAllRoamEvents = false,
            exportPayload = null,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )

        val home5m = state5m.timelineSeries.first { it.bssid == "AA:BB:CC:11:22:02" }
        assertEquals(4, home5m.points.size)
        assertFalse(home5m.points[0].connectWithDashedGapFromPrev)
        assertTrue("Expected dashed gap after null sample", home5m.points[1].connectWithDashedGapFromPrev)
        assertTrue("Expected dashed gap after 65s missed connected sweep", home5m.points[2].connectWithDashedGapFromPrev)
        assertTrue("Expected dashed gap after 110s jump", home5m.points[3].connectWithDashedGapFromPrev)
        assertEquals(3, home5m.gapSegmentCount)
        assertEquals(-52, home5m.minRssiDbm)
        assertEquals(-47, home5m.maxRssiDbm)

        // 15m window (0..900_000 ms) includes the earlier 120_000 ms sample -> 5 valid points
        val state15m = InsightsStateMapper.mapToUiState(
            snapshot = snapshot,
            health = health,
            readinessState = ScannerReadinessState.Ready(),
            historyWindow = RssiHistoryWindow.FIFTEEN_MINUTES,
            highlightedTimelineBssid = null,
            redactMacs = false,
            exportFormat = ExportFormat.MARKDOWN,
            isExportPreviewExpanded = false,
            showAllSecurityRows = false,
            showAllRoamEvents = false,
            exportPayload = null,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )
        val home15m = state15m.timelineSeries.first { it.bssid == "AA:BB:CC:11:22:02" }
        assertEquals(5, home15m.points.size)
        assertEquals(-58, home15m.minRssiDbm)

        // 1m window (840_000..900_000 ms) includes only 895_000 ms for HomeMesh -> 1 point
        val state1m = InsightsStateMapper.mapToUiState(
            snapshot = snapshot,
            health = health,
            readinessState = ScannerReadinessState.Ready(),
            historyWindow = RssiHistoryWindow.ONE_MINUTE,
            highlightedTimelineBssid = null,
            redactMacs = false,
            exportFormat = ExportFormat.MARKDOWN,
            isExportPreviewExpanded = false,
            showAllSecurityRows = false,
            showAllRoamEvents = false,
            exportPayload = null,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )
        val home1m = state1m.timelineSeries.first { it.bssid == "AA:BB:CC:11:22:02" }
        assertEquals(1, home1m.points.size)

        // Verify NaN/Infinity series produced finite floats & formatted string without NaN
        val cafe1m = state1m.timelineSeries.first { it.bssid == "DD:EE:FF:33:44:05" }
        assertTrue(cafe1m.points.all { it.rssiDbm.isFinite() })
        assertFalse(cafe1m.formattedStatsLine.contains("NaN"))
    }

    @Test
    fun roamEvents_evilTwin_securityAudit_andJsonPreview_respectRedactMacsAndNeverLeakEnums() = runTest {
        val repository = FakeInsightsWifiRadarRepository()
        val vm = InsightsViewModel(
            repository = repository,
            savedStateHandle = SavedStateHandle(),
            exportDispatcher = testDispatcher,
        )

        vm.uiState.test {
            val unredacted = awaitItem()
            // Worst-score-first Security Audit ordering: F (score 5) -> C (score 65) -> A+ (score 100)
            assertEquals("66:77:88:99:AA:09", unredacted.securityAuditRows[0].rawBssid)
            assertEquals("DD:EE:FF:33:44:05", unredacted.securityAuditRows[1].rawBssid)
            assertEquals("AA:BB:CC:11:22:02", unredacted.securityAuditRows[2].rawBssid)

            // Switch to JSON Diagnostic & enable Redact MACs
            vm.selectExportFormat(ExportFormat.JSON)
            vm.setRedactMacs(true)
            val redactedJsonState = expectMostRecentItem()

            assertTrue(redactedJsonState.redactMacs)
            assertEquals("AA:BB:CC:XX:XX:02", redactedJsonState.timelineSeries.first { it.bssid == "AA:BB:CC:11:22:02" }.formattedBssid)
            assertEquals("AA:BB:CC:XX:XX:01", redactedJsonState.roamEvents.single().formattedOldBssid)
            assertEquals("AA:BB:CC:XX:XX:02", redactedJsonState.roamEvents.single().formattedNewBssid)

            val twin = redactedJsonState.evilTwinAlerts.single()
            assertEquals(listOf("66:77:88:XX:XX:09"), twin.formattedSuspectOpenBssids)
            // Raw BSSID preserved internally so Locate Rogue Radio Proximity lock still works!
            assertEquals("66:77:88:99:AA:09", twin.firstRawSuspectBssid)
            assertFalse("Inline warning MAC must also be redacted", twin.plainEnglishWarning.contains("99:AA"))

            // Preview text for JSON must show redacted MACs AND contain ZERO SCREAMING_SNAKE_CASE leaks!
            val preview = redactedJsonState.exportPreview
            assertNotNull(preview)
            assertTrue(preview!!.isStructuredJsonSummary)
            assertTrue(preview.previewText.contains("AA:BB:CC:XX:XX:02"))
            assertFalse(preview.previewText.contains("11:22:02"))
            assertFalse(SemanticFormatters.containsRawEnumLeak(preview.previewText))
            for (match in screamingSnakeRegex.findAll(preview.previewText)) {
                assertTrue(
                    "Unexpected SCREAMING_SNAKE_CASE token '${match.value}' in JSON preview",
                    macAddressRegex.matches(match.value),
                )
            }

            // Meanwhile, buildFreshExportPayload(JSON) returns the 100% verbatim serialized JSON!
            val verbatimJsonPayload = vm.buildFreshExportPayload(ExportFormat.JSON)
            assertTrue(verbatimJsonPayload.content.contains("\"BAND_5_GHZ\""))
            assertTrue(verbatimJsonPayload.content.contains("AA:BB:CC:XX:XX:02"))
            assertFalse(verbatimJsonPayload.content.contains("AA:BB:CC:11:22:02"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun savedStateHandle_persistsWindowRedactFormatPreviewAndHighlightAcrossViewModelRecreation() = runTest {
        val repository = FakeInsightsWifiRadarRepository()
        val sharedHandle = SavedStateHandle()
        val vm1 = InsightsViewModel(repository, sharedHandle, testDispatcher)

        assertEquals(RssiHistoryWindow.FIVE_MINUTES, vm1.uiState.value.selectedHistoryWindow)

        vm1.selectHistoryWindow(RssiHistoryWindow.ONE_MINUTE)
        vm1.setRedactMacs(true)
        vm1.selectExportFormat(ExportFormat.JSON)
        vm1.setExportPreviewExpanded(false)
        vm1.toggleHighlightTimelineBssid("AA:BB:CC:11:22:02")

        val vm2 = InsightsViewModel(repository, sharedHandle, testDispatcher)
        vm2.uiState.test {
            val restored = expectMostRecentItem()
            assertEquals(RssiHistoryWindow.ONE_MINUTE, restored.selectedHistoryWindow)
            assertTrue(restored.redactMacs)
            assertEquals(ExportFormat.JSON, restored.selectedExportFormat)
            assertFalse(restored.isExportPreviewExpanded)
            assertEquals("AA:BB:CC:11:22:02", restored.highlightedTimelineBssid)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
