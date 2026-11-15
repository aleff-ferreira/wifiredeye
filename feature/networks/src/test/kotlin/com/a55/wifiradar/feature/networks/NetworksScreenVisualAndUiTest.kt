package com.a55.wifiradar.feature.networks

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.designsystem.component.ScanStatusTopPill
import com.a55.wifiradar.core.designsystem.layout.RadarScaffold
import com.a55.wifiradar.core.designsystem.theme.WifiRadarTheme
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.NetworkFilterChip
import com.a55.wifiradar.core.model.NetworkSortOption
import com.a55.wifiradar.core.model.NetworkViewMode
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarDestination
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessChecklist
import com.a55.wifiradar.core.model.ReadinessStep
import com.a55.wifiradar.core.model.ReadinessStepState
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-450dpi")
class NetworksScreenVisualAndUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun capturePng(filename: String): File {
        val outDir = File("build/outputs/roborazzi").apply { mkdirs() }
        val file = File(outDir, filename)
        composeTestRule.onRoot().captureRoboImage(
            file,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        assertTrue("Expected PNG ${file.absolutePath} to exist", file.exists())
        assertTrue("Expected non-empty PNG (${file.length()} bytes)", file.length() > 2_048L)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("Decoded PNG bitmap must not be null", bitmap)
        assertTrue(
            "Expected Galaxy A55 5G FHD+ class dimensions, got ${bitmap.width}x${bitmap.height}",
            bitmap.width >= 900 && bitmap.height >= 1800,
        )
        return file
    }

    @Composable
    private fun WrappedInM6RadarScaffold(
        repo: FakeNetworksWifiRadarRepository,
        onOpenProximity: (ProximityTargetKey) -> Unit = {},
    ) {
        val snapshot = repo.snapshotMutable.value
        val readiness = repo.readinessMutable.value
        WifiRadarTheme {
            RadarScaffold(
                selectedTab = RadarDestination.NETWORKS,
                onSelectTab = {},
                topStatusSlot = {
                    ScanStatusTopPill(
                        networkCount = snapshot.radios.size,
                        telemetry = snapshot.scanTelemetry,
                        readinessState = readiness,
                    )
                },
            ) { innerPadding: PaddingValues ->
                NetworksRoute(
                    repository = repo,
                    onOpenProximity = onOpenProximity,
                    contentPadding = innerPadding,
                )
            }
        }
    }

    @Test
    fun groupedDiscovery_connectedSpotlight_hiddenIsolation_proximityHandoff_andAmoledScreenshot() {
        val repo = FakeNetworksWifiRadarRepository()
        val launchedTargets = mutableListOf<ProximityTargetKey>()

        // First verify bare NetworksRoute emits ZERO duplicate RadarTestTags.SCAN_STATUS_PILL
        composeTestRule.setContent {
            WrappedInM6RadarScaffold(
                repo = repo,
                onOpenProximity = { launchedTargets.add(it) },
            )
        }
        composeTestRule.waitForIdle()

        // Wrapped inside RadarScaffold -> exactly 1 SCAN_STATUS_PILL in the entire composition!
        composeTestRule.onAllNodesWithTag(RadarTestTags.SCAN_STATUS_PILL).assertCountEquals(1)
        composeTestRule.onNodeWithTag(NetworksTestTags.CONNECTED_SPOTLIGHT_CARD).assertIsDisplayed()

        // Capture full AMOLED grouped discovery screenshot
        val png = capturePng("networks_discovery_grouped_1x.png")
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
        var blackPixels = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (bmp.getPixel(x, y) == AndroidColor.BLACK) blackPixels++
            }
        }
        assertTrue("Expected true #000000 AMOLED background pixels, got $blackPixels", blackPixels > 150)

        // Expand AuroraMesh group -> verify all 3 sub-radios appear and launch BssidLock on 1 tap
        val meshKey = "ess:AuroraMesh:WPA_PERSONAL_FAMILY"
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.groupExpandButton(meshKey)))
        composeTestRule.onNodeWithTag(NetworksTestTags.groupExpandButton(meshKey)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.proximityButton("AA:BB:CC:20:00:01")))
        composeTestRule.onNodeWithTag(NetworksTestTags.proximityButton("AA:BB:CC:20:00:01")).performClick()
        assertEquals(
            ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:20:00:01", ssidHint = "AuroraMesh"),
            launchedTargets.last(),
        )

        // Verify both Hidden SSIDs remain separate singletons and launch BssidLock with ssidHint = null
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.radioCard("AA:BB:CC:99:00:01")))
        composeTestRule.onNodeWithTag(NetworksTestTags.proximityButton("AA:BB:CC:99:00:01")).performClick()
        assertEquals(
            ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:99:00:01", ssidHint = null),
            launchedTargets.last(),
        )

        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.radioCard("AA:BB:CC:99:00:02")))
        composeTestRule.onNodeWithTag(NetworksTestTags.radioCard("AA:BB:CC:99:00:02")).assertIsDisplayed()
    }

    @Test
    fun progressiveReadinessStates_settingsIntents_nonBlockingWifiOffScanAlwaysOn_andAutoRecovery() {
        val checklistLocationOff = ReadinessChecklist(
            permissionStep = ReadinessStep(1, "Permission", "Granted", ReadinessStepState.SATISFIED),
            locationStep = ReadinessStep(
                2,
                "Device Location",
                "Off",
                ReadinessStepState.ACTION_REQUIRED,
                ReadinessAction.OPEN_LOCATION_SETTINGS,
                "Open Location Settings",
            ),
            wifiStep = ReadinessStep(3, "Wi-Fi", "Ready", ReadinessStepState.SATISFIED),
        )
        val repo = FakeNetworksWifiRadarRepository(
            initialReadiness = ScannerReadinessState.LocationDisabled(checklistLocationOff)
        )

        composeTestRule.setContent {
            WifiRadarTheme {
                NetworksRoute(
                    repository = repo,
                    onOpenProximity = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        // Blocking LocationDisabled -> ReadinessChecklistCard displayed, Settings intent launched on CTA click
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        val locIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("Expected Location Settings intent", locIntent)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, locIntent.action)

        // Transition to PermanentDenial -> App Permission Settings intent
        repo.readinessMutable.value = ScannerReadinessState.PermanentDenial(
            deniedPermissions = listOf("android.permission.ACCESS_FINE_LOCATION"),
            checklist = checklistLocationOff,
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        val appDetailsIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(appDetailsIntent)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetailsIntent.action)

        // Transition to WifiDisabledScanAlwaysOn (canScanNow = true) -> BOTH banner AND live networks visible!
        val scanAlwaysChecklist = ReadinessChecklist(
            permissionStep = ReadinessStep(1, "Permission", "Granted", ReadinessStepState.SATISFIED),
            locationStep = ReadinessStep(2, "Location", "On", ReadinessStepState.SATISFIED),
            wifiStep = ReadinessStep(
                3,
                "Wi-Fi Radio",
                "Off, background scanning active",
                ReadinessStepState.WARNING_SCAN_STILL_ACTIVE,
                ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
                "Turn On Wi-Fi",
            ),
        )
        repo.readinessMutable.value = ScannerReadinessState.WifiDisabledScanAlwaysOn(scanAlwaysChecklist)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.WIFI_OFF_SCANNING_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.groupCard("ess:AuroraMesh:WPA_PERSONAL_FAMILY")))
        composeTestRule.onNodeWithTag(NetworksTestTags.groupCard("ess:AuroraMesh:WPA_PERSONAL_FAMILY"))
            .assertIsDisplayed()

        // Transition to Ready() -> banner disappears automatically
        repo.readinessMutable.value = ScannerReadinessState.Ready()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(NetworksTestTags.WIFI_OFF_SCANNING_BANNER).assertCountEquals(0)
    }

    @Test
    fun searchFilterSortReset_andSixGhzDualBandRnrBannerCapture() {
        val repo = FakeNetworksWifiRadarRepository()

        composeTestRule.setContent {
            WrappedInM6RadarScaffold(repo = repo)
        }
        composeTestRule.waitForIdle()

        // Search non-existent SSID -> Empty filter card -> 1-tap Reset Filters restores list
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.SEARCH_INPUT))
        composeTestRule.onNodeWithTag(NetworksTestTags.SEARCH_INPUT).performTextInput("NoSuchRouterXYZ")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.EMPTY_FILTER_CARD))
        composeTestRule.onNodeWithTag(NetworksTestTags.EMPTY_FILTER_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NetworksTestTags.RESET_FILTERS_BUTTON).performClick()
        composeTestRule.waitForIdle()

        // Tap 6 GHz filter chip on Galaxy A55 5G (has6Ghz == false) -> SixGhzHardwareAndRnrBanner + RNR Ch 37
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.filterChip(NetworkFilterChip.BAND_6_GHZ)))
        composeTestRule.onNodeWithTag(NetworksTestTags.filterChip(NetworkFilterChip.BAND_6_GHZ)).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.SIX_GHZ_HARDWARE_BANNER))
        composeTestRule.onNodeWithTag(NetworksTestTags.SIX_GHZ_HARDWARE_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithText("6 GHz · Ch 37 (6185 MHz · 160 MHz)").assertIsDisplayed()

        capturePng("networks_6ghz_rnr_banner_1x.png")
    }

    @Test
    fun liveNetworkDetailBottomSheet_rendersIeFields_copiesSummary_savesNickname_survivesDropout_andCapturesPng() {
        val repo = FakeNetworksWifiRadarRepository()
        val launchedTargets = mutableListOf<ProximityTargetKey>()

        composeTestRule.setContent {
            WrappedInM6RadarScaffold(
                repo = repo,
                onOpenProximity = { launchedTargets.add(it) },
            )
        }
        composeTestRule.waitForIdle()

        // Open detail sheet from Connected Spotlight
        composeTestRule.onNodeWithTag(NetworksTestTags.CONNECTED_DETAILS_BUTTON).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_SHEET).assertIsDisplayed()
        composeTestRule.onNode(
            hasText("AuroraMesh") and hasAnyAncestor(hasTestTag(NetworksTestTags.DETAIL_SHEET))
        ).assertIsDisplayed()

        capturePng("networks_detail_sheet_1x.png")

        // Copy Summary -> verify Android ClipboardManager contains plain-English diagnostic summary
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_COPY_SUMMARY_CTA)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        val clipboard = composeTestRule.activity.getSystemService(ClipboardManager::class.java)
        val copiedText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("Expected AuroraMesh in copied summary: $copiedText", copiedText.contains("AuroraMesh"))
        assertTrue("Expected AA:BB:CC:10:00:01 in copied summary", copiedText.contains("AA:BB:CC:10:00:01"))
        assertFalse("Copied summary must have zero raw enum leaks", SemanticFormatters.containsRawEnumLeak(copiedText))

        // Save custom nickname
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_NICKNAME_INPUT)
            .performScrollTo()
            .performTextClearance()
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_NICKNAME_INPUT)
            .performTextInput("Master Bedroom AX")
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_NICKNAME_SAVE_CTA)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals("Master Bedroom AX", repo.savedNicknames["AA:BB:CC:10:00:01"])

        // Simulate AP dropout while detail sheet is open -> stays open & displays Out of range banner!
        val current = repo.snapshotMutable.value
        repo.snapshotMutable.value = current.copy(
            timestampElapsedMs = 24_000L,
            radios = current.radios.filterNot { it.bssid == "AA:BB:CC:10:00:01" },
            groupedNetworks = emptyList(),
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_SHEET).assertIsDisplayed()
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_DROPOUT_BANNER)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Out of range · Last seen 14s ago").assertIsDisplayed()

        // Click Measure Proximity CTA inside detail sheet -> closes sheet & hands off BssidLock
        composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_MEASURE_PROXIMITY_CTA)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(NetworksTestTags.DETAIL_SHEET).assertCountEquals(0)
        assertEquals("AA:BB:CC:10:00:01", (launchedTargets.last() as ProximityTargetKey.BssidLock).bssid)
    }

    @Test
    fun activityScenarioRecreate_preservesSearchViewModeFilterSortAndOpenDetailSheet() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sharedRepo = FakeNetworksWifiRadarRepository()
        val initialActivity = composeTestRule.activity

        composeTestRule.setContent {
            WifiRadarTheme {
                NetworksRoute(
                    repository = sharedRepo,
                    onOpenProximity = {},
                )
            }
        }
        composeTestRule.waitForIdle()

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is ComponentActivity && activity !== initialActivity) {
                    activity.setContent {
                        WifiRadarTheme {
                            NetworksRoute(
                                repository = sharedRepo,
                                onOpenProximity = {},
                            )
                        }
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            // Mutate search, view mode, sort, and open detail sheet
            composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
                .performScrollToNode(hasTestTag(NetworksTestTags.SEARCH_INPUT))
            composeTestRule.onNodeWithTag(NetworksTestTags.SEARCH_INPUT).performTextInput("Aurora")
            composeTestRule.onNodeWithTag(NetworksTestTags.viewModeTab(NetworkViewMode.ALL_RADIOS_BSSID))
                .performClick()
            composeTestRule.onNodeWithTag(NetworksTestTags.SORT_SELECTOR_BUTTON).performClick()
            composeTestRule.onNodeWithTag(NetworksTestTags.sortOptionChip(NetworkSortOption.NAME_A_TO_Z))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
                .performScrollToNode(hasTestTag(NetworksTestTags.radioDetailsButton("AA:BB:CC:10:00:01")))
            composeTestRule.onNodeWithTag(NetworksTestTags.radioDetailsButton("AA:BB:CC:10:00:01"))
                .performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_SHEET).assertIsDisplayed()

            // Recreate Activity (configuration change / SavedStateRegistry round-trip)
            composeTestRule.activityRule.scenario.recreate()
            composeTestRule.waitForIdle()

            // Detail sheet, search text, sort label, and view mode survive!
            composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_SHEET).assertIsDisplayed()
            composeTestRule.onNodeWithTag(NetworksTestTags.DETAIL_CLOSE_BUTTON).performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
                .performScrollToNode(hasTestTag(NetworksTestTags.SEARCH_INPUT))
            composeTestRule.onNodeWithTag(NetworksTestTags.SEARCH_INPUT).assertTextContains("Aurora")
            composeTestRule.onNodeWithTag(NetworksTestTags.SORT_SELECTOR_BUTTON)
                .performScrollTo()
                .assertTextContains("Sort: Name A–Z")
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun zeroRawEnumLeaks_inlineLegends_andFontScale200PercentLayoutIntegrityWithPngCapture() {
        val repo = FakeNetworksWifiRadarRepository()

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                WrappedInM6RadarScaffold(repo = repo)
            }
        }
        composeTestRule.waitForIdle()

        // Tap inline legend pill and verify plain-English explanation card opens cleanly at 2.0x font scale
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(RadarTestTags.legendPill(LegendTopic.DBM.topicId)))
        composeTestRule.onNodeWithTag(RadarTestTags.legendPill(LegendTopic.DBM.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.DBM.topicId))
            .performScrollTo()
            .assertIsDisplayed()

        // Scroll back to top and capture 2.0x fontScale screenshot
        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.DISCOVERY_STATUS_SUMMARY))
        composeTestRule.waitForIdle()
        capturePng("networks_discovery_fontscale_2_0x.png")

        val rootNode = composeTestRule.onRoot().fetchSemanticsNode()
        val rootWidth = rootNode.boundsInRoot.width
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() } - 1.5f

        // Audit 100% of semantics tree for zero raw enum leaks
        assertZeroRawEnumLeaksRecursively(rootNode)

        // Verify primary interactive controls respect [0, rootWidth] and >= 48dp touch height at 2.0x scale
        val scanBtnBounds = composeTestRule
            .onNodeWithTag(NetworksTestTags.MANUAL_SCAN_BUTTON)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(scanBtnBounds.left >= -0.5f && scanBtnBounds.right <= rootWidth + 0.5f)
        assertTrue("Manual Scan button height < 48dp: ${scanBtnBounds.height}", scanBtnBounds.height >= minTouchPx)

        composeTestRule.onNodeWithTag(NetworksTestTags.LIST)
            .performScrollToNode(hasTestTag(NetworksTestTags.filterChip(NetworkFilterChip.OPEN_OR_WEAK_SECURITY)))
        val chipBounds = composeTestRule
            .onNodeWithTag(NetworksTestTags.filterChip(NetworkFilterChip.OPEN_OR_WEAK_SECURITY))
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(chipBounds.left >= -0.5f && chipBounds.right <= rootWidth + 0.5f)
        assertTrue("Filter chip height < 48dp: ${chipBounds.height}", chipBounds.height >= minTouchPx)
    }

    private fun assertZeroRawEnumLeaksRecursively(node: SemanticsNode) {
        val texts = buildList {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
            node.config.getOrNull(SemanticsProperties.StateDescription)?.let { add(it) }
        }
        for (text in texts) {
            assertFalse(
                "Raw enum leak detected in Networks semantics: '$text'",
                SemanticFormatters.containsRawEnumLeak(text),
            )
        }
        for (child in node.children) {
            assertZeroRawEnumLeaksRecursively(child)
        }
    }
}
