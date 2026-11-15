package com.a55.wifiradar.feature.proximity

import android.app.Activity
import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.designsystem.component.ScanStatusTopPill
import com.a55.wifiradar.core.designsystem.layout.RadarScaffold
import com.a55.wifiradar.core.designsystem.theme.WifiRadarTheme
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarDestination
import com.a55.wifiradar.core.model.RadarSnapshot
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
class ProximityScreenTest {

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
        repo: FakeProximityWifiRadarRepository,
        externalTargetKey: ProximityTargetKey? = null,
    ) {
        val snapshot = repo.snapshotMutable.value
        val readiness = repo.readinessMutable.value
        WifiRadarTheme {
            RadarScaffold(
                selectedTab = RadarDestination.PROXIMITY,
                onSelectTab = {},
                topStatusSlot = {
                    ScanStatusTopPill(
                        networkCount = snapshot.radios.size,
                        telemetry = snapshot.scanTelemetry,
                        readinessState = readiness,
                    )
                },
            ) { innerPadding: PaddingValues ->
                ProximityRoute(
                    repository = repo,
                    contentPadding = innerPadding,
                    externalTargetKey = externalTargetKey,
                )
            }
        }
    }

    @Test
    fun liveSonarGaugeAndSparkline_rendersMetricsSemanticsNeverHangsWaitForIdle_andCapturesAmoledPng() {
        val repo = FakeProximityWifiRadarRepository()

        composeTestRule.setContent {
            WrappedInM6RadarScaffold(repo = repo)
        }
        composeTestRule.waitForIdle()

        // Wrapped inside RadarScaffold -> exactly 1 SCAN_STATUS_PILL (none inside ProximityScreen)
        composeTestRule.onAllNodesWithTag(RadarTestTags.SCAN_STATUS_PILL).assertCountEquals(1)

        composeTestRule.onNodeWithTag(ProximityTestTags.SONAR_GAUGE)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "-51 dBm · Strong · Close",
                )
            )
        composeTestRule.onNodeWithTag(ProximityTestTags.HERO_DBM_TEXT)
            .assertIsDisplayed()
            .assertTextContains("-51 dBm")

        val png = capturePng("proximity_live_sonar_1x.png")
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
        var blackPixels = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (bmp.getPixel(x, y) == AndroidColor.BLACK) blackPixels++
            }
        }
        assertTrue("Expected substantial #000000 AMOLED pixels, got $blackPixels", blackPixels > 150)

        composeTestRule.onNodeWithTag(ProximityTestTags.ZONE_PILL)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(ProximityTestTags.RAW_DBM_TEXT)
            .performScrollTo()
            .assertTextContains("Raw: -51 dBm")
        composeTestRule.onNodeWithTag(ProximityTestTags.PEAK_DBM_TEXT)
            .performScrollTo()
            .assertTextContains("Peak: -48 dBm")

        // Verify sparkline & burst token ring accessibility semantics
        composeTestRule.onNodeWithTag(ProximityTestTags.SPARKLINE_CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(ProximityTestTags.BURST_TOKEN_RING)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "3 of 4 active scan pulses ready",
                )
            )
    }

    @Test
    fun targetPickerSheet_lockModeToggle_rttBadge_environmentCalibration_andHiddenMeshGuard() {
        val repo = FakeProximityWifiRadarRepository()

        composeTestRule.setContent {
            WrappedInM6RadarScaffold(repo = repo)
        }
        composeTestRule.waitForIdle()

        // Open in-tree Target Picker Bottom Sheet & capture screenshot
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_SWITCHER_BUTTON).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_PICKER_SHEET).assertIsDisplayed()

        capturePng("proximity_target_picker_1x.png")

        // Select 802.11az Wi-Fi RTT router ("Studio:RTT:Lab", AA:BB:CC:77:00:07)
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_PICKER_LIST)
            .performScrollToNode(hasTestTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:77:00:07")))
        composeTestRule.onNodeWithTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:77:00:07"))
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(ProximityTestTags.TARGET_PICKER_SHEET).assertCountEquals(0)
        composeTestRule.onNodeWithTag(ProximityTestTags.DISTANCE_METHOD_BADGE)
            .performScrollTo()
            .assertTextContains("Wi-Fi RTT (802.11az)")

        // Toggle Follow Mesh (SSID) -> Single Router (BSSID)
        composeTestRule.onNodeWithTag(ProximityTestTags.LOCK_MODE_FOLLOW_MESH)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.LOCK_MODE_FOLLOW_MESH).assertIsSelected()

        composeTestRule.onNodeWithTag(ProximityTestTags.LOCK_MODE_SINGLE_BSSID)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.LOCK_MODE_SINGLE_BSSID).assertIsSelected()

        // Now switch back to non-RTT AuroraMesh router to test 1m Calibration & Environment chips
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_SWITCHER_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_PICKER_LIST)
            .performScrollToNode(hasTestTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:10:00:01")))
        composeTestRule.onNodeWithTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:10:00:01")).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ProximityTestTags.environmentChip(PropagationEnvironment.HEAVY_WALLS))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.environmentChip(PropagationEnvironment.HEAVY_WALLS))
            .assertIsSelected()

        composeTestRule.onNodeWithTag(ProximityTestTags.CALIBRATE_1M_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.DISTANCE_METHOD_BADGE)
            .assertTextContains("1m Calibrated Estimate")
        composeTestRule.onNodeWithTag(ProximityTestTags.CLEAR_CALIBRATION_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.DISTANCE_METHOD_BADGE)
            .assertTextContains("Signal Path-Loss Estimate")

        // Select Hidden Network -> Follow Mesh (SSID) MUST be disabled!
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_SWITCHER_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_PICKER_LIST)
            .performScrollToNode(hasTestTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:99:00:09")))
        composeTestRule.onNodeWithTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:99:00:09")).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.LOCK_MODE_FOLLOW_MESH)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun signalDropoutAndAutoReacquisition_preservesMinMaxStats_rendersDashedSparkline_andCapturesStandbyPng() {
        val repo = FakeProximityWifiRadarRepository()

        composeTestRule.setContent {
            WrappedInM6RadarScaffold(repo = repo)
        }
        composeTestRule.waitForIdle()

        // Remove connected target AA:BB:CC:10:00:01 from snapshot -> triggers SIGNAL_LOST while keeping Min/Max
        val initial = repo.snapshotMutable.value
        repo.snapshotMutable.value = initial.copy(
            radios = initial.radios.filterNot { it.bssid == "AA:BB:CC:10:00:01" },
            connectedWifi = null,
        )
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ProximityTestTags.SIGNAL_LOST_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithText("Signal Lost · Last seen 12s ago at -86 dBm").assertIsDisplayed()
        composeTestRule.onNodeWithTag(ProximityTestTags.HERO_DBM_TEXT).assertTextContains("— dBm")

        capturePng("proximity_signal_lost_1x.png")

        // Verify Min & Max historical stats remain intact across dropout
        composeTestRule.onNodeWithTag(ProximityTestTags.STAT_MIN)
            .performScrollTo()
            .assertTextContains("-74 dBm")
        composeTestRule.onNodeWithTag(ProximityTestTags.STAT_MAX)
            .performScrollTo()
            .assertTextContains("-51 dBm")

        // Re-inject AA:BB:CC:10:00:01 -> seamless auto-reacquisition!
        repo.snapshotMutable.value = initial
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag(ProximityTestTags.SIGNAL_LOST_BANNER).assertCountEquals(0)
        composeTestRule.onNodeWithTag(ProximityTestTags.HERO_DBM_TEXT)
            .performScrollTo()
            .assertTextContains("-51 dBm")

        // Reset Stats button
        composeTestRule.onNodeWithTag(ProximityTestTags.RESET_STATS_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, repo.resetStatsCalls.size)
    }

    @Test
    fun geigerLifecycleSafety_defaultOff_stopsOnDropout_stopsOnBackgroundStop_stopsOnDispose_andNoVibratorGuard() {
        val repo = FakeProximityWifiRadarRepository()
        var showProximity by mutableStateOf(true)

        composeTestRule.setContent {
            WifiRadarTheme {
                if (showProximity) {
                    ProximityRoute(repository = repo)
                }
            }
        }
        composeTestRule.waitForIdle()

        // Default OFF
        composeTestRule.onNodeWithTag(ProximityTestTags.HAPTIC_TOGGLE)
            .performScrollTo()
            .assertIsOff()
        composeTestRule.onNodeWithTag(ProximityTestTags.AUDIO_TOGGLE)
            .performScrollTo()
            .assertIsOff()

        // Enable Haptic Pulse -> updateSignal(-51.0, false) called
        composeTestRule.onNodeWithTag(ProximityTestTags.HAPTIC_TOGGLE).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.HAPTIC_TOGGLE).assertIsOn()
        assertTrue(repo.fakeFeedback.updateSignalCalls.contains(-51.0 to false))

        // Simulate SIGNAL_LOST -> updateSignal(null, true) and stopAll() invoked immediately
        val stopBeforeDropout = repo.fakeFeedback.stopAllCallCount
        val initial = repo.snapshotMutable.value
        repo.snapshotMutable.value = RadarSnapshot()
        composeTestRule.waitForIdle()
        assertTrue(repo.fakeFeedback.updateSignalCalls.contains(null to true))
        assertTrue(repo.fakeFeedback.stopAllCallCount > stopBeforeDropout)

        // Restore signal
        repo.snapshotMutable.value = initial
        composeTestRule.waitForIdle()

        // Move Activity to CREATED (ON_STOP background) -> synchronous stopAll()!
        val stopBeforeBackground = repo.fakeFeedback.stopAllCallCount
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(
            "Expected stopAll() on ON_STOP background transition",
            repo.fakeFeedback.stopAllCallCount > stopBeforeBackground,
        )

        // Return to RESUMED
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()

        // Remove ProximityRoute from composition (tab switch / back) -> onDispose calls stopAll()!
        val stopBeforeDispose = repo.fakeFeedback.stopAllCallCount
        showProximity = false
        composeTestRule.waitForIdle()
        assertTrue(
            "Expected stopAll() on DisposableEffect.onDispose",
            repo.fakeFeedback.stopAllCallCount > stopBeforeDispose,
        )
    }

    @Test
    fun noHardwareVibrator_disablesHapticToggleWhileKeepingAudioToggleFunctional_andReadinessRevocationHaltsGeiger() {
        val repo = FakeProximityWifiRadarRepository(hasVibrator = false)

        composeTestRule.setContent {
            WifiRadarTheme {
                ProximityRoute(repository = repo)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(ProximityTestTags.HAPTIC_TOGGLE)
            .performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()

        composeTestRule.onNodeWithTag(ProximityTestTags.AUDIO_TOGGLE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.AUDIO_TOGGLE).assertIsOn()

        // Non-blocking WifiDisabledScanAlwaysOn shows banner ABOVE live sonar gauge
        val scanAlwaysChecklist = ReadinessChecklist(
            permissionStep = ReadinessStep(1, "Permission", "Granted", ReadinessStepState.SATISFIED),
            locationStep = ReadinessStep(2, "Location", "On", ReadinessStepState.SATISFIED),
            wifiStep = ReadinessStep(
                3,
                "Wi-Fi Radio",
                "Off, scanning active",
                ReadinessStepState.WARNING_SCAN_STILL_ACTIVE,
                ReadinessAction.OPEN_WIFI_SETTINGS_PANEL,
                "Turn On Wi-Fi",
            ),
        )
        repo.readinessMutable.value = ScannerReadinessState.WifiDisabledScanAlwaysOn(scanAlwaysChecklist)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(ProximityTestTags.WIFI_OFF_SCANNING_BANNER)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(ProximityTestTags.SONAR_GAUGE)
            .performScrollTo()
            .assertIsDisplayed()

        // Blocking LocationDisabled mid-session -> halts Geiger & launches Settings intent on CTA click
        val stopBeforeBlock = repo.fakeFeedback.stopAllCallCount
        repo.readinessMutable.value = ScannerReadinessState.LocationDisabled(scanAlwaysChecklist)
        composeTestRule.waitForIdle()
        assertTrue(repo.fakeFeedback.stopAllCallCount > stopBeforeBlock)
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        val started = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, started.action)
    }

    @Test
    fun activityScenarioRecreate_preservesPickerSwitchedTargetEvenAfterInitialExternalTargetKey() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sharedRepo = FakeProximityWifiRadarRepository()
        val initialActivity = composeTestRule.activity
        val externalHandoff = ProximityTargetKey.BssidLock(
            bssid = "AA:BB:CC:10:00:01",
            ssidHint = "AuroraMesh",
        )

        composeTestRule.setContent {
            WifiRadarTheme {
                ProximityRoute(
                    repository = sharedRepo,
                    externalTargetKey = externalHandoff,
                )
            }
        }
        composeTestRule.waitForIdle()

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is ComponentActivity && activity !== initialActivity) {
                    activity.setContent {
                        WifiRadarTheme {
                            ProximityRoute(
                                repository = sharedRepo,
                                externalTargetKey = externalHandoff,
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
            // Switch target via Target Picker to Studio:RTT:Lab & select Heavy Concrete
            composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_SWITCHER_BUTTON).performClick()
            composeTestRule.onNodeWithTag(ProximityTestTags.TARGET_PICKER_LIST)
                .performScrollToNode(hasTestTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:77:00:07")))
            composeTestRule.onNodeWithTag(ProximityTestTags.pickerRadioItem("AA:BB:CC:77:00:07"))
                .performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(ProximityTestTags.environmentChip(PropagationEnvironment.HEAVY_WALLS))
                .performScrollTo()
                .performClick()
            composeTestRule.waitForIdle()

            // Recreate Activity -> must keep Studio:RTT:Lab & Heavy Concrete (not overwrite with externalHandoff!)
            composeTestRule.activityRule.scenario.recreate()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag(ProximityTestTags.environmentChip(PropagationEnvironment.HEAVY_WALLS))
                .performScrollTo()
                .assertIsSelected()
            composeTestRule.onNodeWithText("Studio:RTT:Lab")
                .performScrollTo()
                .assertIsDisplayed()
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun zeroRawEnumLeaks_inlineLegends_andFontScale200PercentLayoutIntegrityWithPngCapture() {
        val repo = FakeProximityWifiRadarRepository()

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                WrappedInM6RadarScaffold(repo = repo)
            }
        }
        composeTestRule.waitForIdle()

        capturePng("proximity_fontscale_2_0x.png")

        val rootNode = composeTestRule.onRoot().fetchSemanticsNode()
        val rootWidth = rootNode.boundsInRoot.width
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() } - 1.5f

        // Audit 100% of semantics tree for zero raw enum leaks
        assertZeroRawEnumLeaksRecursively(rootNode)

        // Verify key controls respect [0, rootWidth] and >= 48dp touch height at 2.0x font scale
        val switcherBounds = composeTestRule
            .onNodeWithTag(ProximityTestTags.TARGET_SWITCHER_BUTTON)
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(switcherBounds.left >= -0.5f && switcherBounds.right <= rootWidth + 0.5f)
        assertTrue("Target switcher height < 48dp: ${switcherBounds.height}", switcherBounds.height >= minTouchPx)

        val calibrateBounds = composeTestRule
            .onNodeWithTag(ProximityTestTags.CALIBRATE_1M_BUTTON)
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(calibrateBounds.left >= -0.5f && calibrateBounds.right <= rootWidth + 0.5f)
        assertTrue("Calibrate 1m height < 48dp: ${calibrateBounds.height}", calibrateBounds.height >= minTouchPx)

        val burstBounds = composeTestRule
            .onNodeWithTag(ProximityTestTags.BURST_SCAN_BUTTON)
            .performScrollTo()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(burstBounds.left >= -0.5f && burstBounds.right <= rootWidth + 0.5f)
        assertTrue("Burst Scan height < 48dp: ${burstBounds.height}", burstBounds.height >= minTouchPx)

        // Tap inline legend pill at 2.0x scale
        composeTestRule.onNodeWithTag(RadarTestTags.legendPill(LegendTopic.OS_SCAN_THROTTLE.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.OS_SCAN_THROTTLE.topicId))
            .performScrollTo()
            .assertIsDisplayed()
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
                "Raw enum leak detected in Proximity semantics: '$text'",
                SemanticFormatters.containsRawEnumLeak(text),
            )
        }
        for (child in node.children) {
            assertZeroRawEnumLeaksRecursively(child)
        }
    }
}
