package com.a55.wifiradar.feature.spectrum

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.designsystem.theme.WifiRadarTheme
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessChecklist
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiBand
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-450dpi")
class SpectrumScreenComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val screamingSnakeRegex = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")
    private val macAddressRegex = Regex("""^[0-9A-F]{2}(:[0-9A-F]{2}){5}$""")

    private fun capturePng(filename: String): File {
        val outDir = File("build/outputs/roborazzi").apply { mkdirs() }
        val file = File(outDir, filename)
        composeTestRule.onRoot().captureRoboImage(
            file,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        assertTrue("Expected PNG ${file.absolutePath} to exist", file.exists())
        assertTrue("Expected non-empty PNG (${file.length()} bytes)", file.length() > 4_096L)
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        assertNotNull("Decoded PNG bitmap must not be null", bitmap)
        assertTrue(
            "Expected Galaxy A55 5G FHD+ class dimensions, got ${bitmap.width}x${bitmap.height}",
            bitmap.width >= 900 && bitmap.height >= 1800,
        )
        return file
    }

    private fun assertZeroRawEnumLeaksAcrossSemanticsTree() {
        val nodes = composeTestRule.onAllNodes(isRoot().not(), useUnmergedTree = true).fetchSemanticsNodes()
        for (node in nodes) {
            val texts = mutableListOf<String>()
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                ?.forEach { texts += it.text }
            node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                ?.forEach { texts += it }
            node.config.getOrElseNullable(SemanticsProperties.StateDescription) { null }
                ?.let { texts += it }

            for (text in texts) {
                assertFalse(
                    "Raw enum leak detected in Spectrum UI text: '$text'",
                    SemanticFormatters.containsRawEnumLeak(text),
                )
                for (match in screamingSnakeRegex.findAll(text)) {
                    val token = match.value
                    assertTrue(
                        "SCREAMING_SNAKE_CASE leak '$token' in Spectrum UI text: '$text'",
                        macAddressRegex.matches(token),
                    )
                }
            }
        }
    }

    @Test
    fun spectrum24Ghz_rendersOverlapCanvasBestChannelCciAciAndCapturesAmoledPng() {
        val repository = FakeSpectrumWifiRadarRepository()
        var launchedProximityTarget: ProximityTargetKey? = null

        composeTestRule.setContent {
            WifiRadarTheme {
                SpectrumRoute(
                    repository = repository,
                    onOpenProximity = { launchedProximityTarget = it },
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Channel Overlap & Spectrum Analyzer").assertIsDisplayed()
        composeTestRule.onNodeWithTag(SpectrumTestTags.SPECTRUM_CANVAS)
            .performScrollTo()
            .assertIsDisplayed()

        // Capture default 2.4 GHz AMOLED visual screenshot & verify true #000000 black pixels
        val png24 = capturePng("spectrum_24ghz_overlap_amoled_1x.png")
        val bmp = BitmapFactory.decodeFile(png24.absolutePath)
        var trueBlackCount = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (bmp.getPixel(x, y) == AndroidColor.BLACK) {
                    trueBlackCount++
                }
            }
        }
        assertTrue("Expected true #000000 AMOLED pixels, got $trueBlackCount", trueBlackCount > 250)

        // Tap curve chip to highlight HomeMesh-2G and trigger Measure Proximity CTA
        composeTestRule.onNodeWithTag(SpectrumTestTags.curveChip("AA:BB:CC:00:00:01"))
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithTag(SpectrumTestTags.SELECTED_CURVE_INSPECTOR)
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag(SpectrumTestTags.SELECTED_CURVE_PROXIMITY_CTA)
            .performScrollTo()
            .performClick()

        assertEquals(
            ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:00:00:01", ssidHint = "HomeMesh-2G"),
            launchedProximityTarget,
        )

        // Verify Best Channel 11 card and CCI / severe ACI cards
        composeTestRule.onNodeWithTag(SpectrumTestTags.BEST_CHANNEL_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Channel 11").assertIsDisplayed()

        composeTestRule.onNodeWithTag(SpectrumTestTags.CCI_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SpectrumTestTags.ACI_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SpectrumTestTags.ACI_SEVERE_WARNING)
            .performScrollTo()
            .assertIsDisplayed()

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }

    @Test
    fun spectrum5Ghz_dfsToggle_80Plus80AndPuncturedCurves_and6GhzA55RnrBanner_capturesPngs() {
        val repository = FakeSpectrumWifiRadarRepository(has6GhzHardware = false)

        composeTestRule.setContent {
            WifiRadarTheme {
                SpectrumRoute(repository = repository)
            }
        }
        composeTestRule.waitForIdle()

        // Switch to 5 GHz and toggle Include DFS Channels ON
        composeTestRule.onNodeWithTag(SpectrumTestTags.bandTab(WifiBand.BAND_5_GHZ))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(SpectrumTestTags.TOGGLE_INCLUDE_DFS)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // Highlight WiFi7-Lab punctured 160 MHz curve
        composeTestRule.onNodeWithTag(SpectrumTestTags.curveChip("AA:BB:CC:00:00:13"))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SpectrumTestTags.SPECTRUM_CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        capturePng("spectrum_5ghz_dfs_80plus80_1x.png")

        composeTestRule.onNodeWithTag(SpectrumTestTags.BEST_CHANNEL_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Channel 100").assertIsDisplayed()

        composeTestRule.onNodeWithTag(SpectrumTestTags.channelScoreRow(120))
            .performScrollTo()
            .assertIsDisplayed()

        assertZeroRawEnumLeaksAcrossSemanticsTree()

        // Switch to 6 GHz on Galaxy A55 5G (has6Ghz = false) -> verify hardware banner + RNR card + Best Channel
        composeTestRule.onNodeWithTag(SpectrumTestTags.bandTab(WifiBand.BAND_6_GHZ))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SpectrumTestTags.HARDWARE_6GHZ_BANNER)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SpectrumTestTags.RNR_NEIGHBORS_CARD)
            .performScrollTo()
            .assertIsDisplayed()

        capturePng("spectrum_6ghz_a55_rnr_banner_1x.png")

        composeTestRule.onNodeWithTag(SpectrumTestTags.BEST_CHANNEL_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Channel 101").assertIsDisplayed()

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }

    @Test
    fun spectrumRoute_activityRecreation_persistsState_andReadinessChecklistFiresAction() {
        val locationOffState = ScannerReadinessState.LocationDisabled(
            checklist = ReadinessChecklist.allReady(),
        )
        val repository = FakeSpectrumWifiRadarRepository(initialReadiness = locationOffState)
        var capturedAction: ReadinessAction? = null

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContentView(
                androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        WifiRadarTheme {
                            SpectrumRoute(
                                repository = repository,
                                onReadinessAction = { capturedAction = it },
                            )
                        }
                    }
                }
            )
        }
        composeTestRule.waitForIdle()

        // Verify ReadinessChecklistCard renders and dispatches OPEN_LOCATION_SETTINGS
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        assertEquals(ReadinessAction.OPEN_LOCATION_SETTINGS, capturedAction)

        // Heal readiness, switch to 5 GHz, enable DFS, and select HomeMesh-5G
        repository.setReadiness(ScannerReadinessState.Ready())
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_CARD).assertDoesNotExist()

        composeTestRule.onNodeWithTag(SpectrumTestTags.bandTab(WifiBand.BAND_5_GHZ))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(SpectrumTestTags.TOGGLE_INCLUDE_DFS)
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(SpectrumTestTags.curveChip("AA:BB:CC:00:00:11"))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // Recreate Activity -> SavedStateHandle must preserve 5 GHz, Include DFS, and selected BSSID!
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContentView(
                androidx.compose.ui.platform.ComposeView(activity).apply {
                    setContent {
                        WifiRadarTheme {
                            SpectrumRoute(repository = repository)
                        }
                    }
                }
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(SpectrumTestTags.bandTab(WifiBand.BAND_5_GHZ))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithTag(SpectrumTestTags.SELECTED_CURVE_INSPECTOR)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(SpectrumTestTags.BEST_CHANNEL_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Channel 100").assertIsDisplayed()
    }

    @Test
    fun spectrumScreen_fontScale200Percent_zeroOverflow_48dpTouchTargets_andInlineLegend() {
        val repository = FakeSpectrumWifiRadarRepository()

        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                WifiRadarTheme {
                    SpectrumRoute(repository = repository)
                }
            }
        }
        composeTestRule.waitForIdle()

        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() } - 1.5f

        for (band in SPECTRUM_SELECTABLE_BANDS) {
            val bounds = composeTestRule
                .onNodeWithTag(SpectrumTestTags.bandTab(band))
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("Band tab $band left < 0: ${bounds.left}", bounds.left >= -0.5f)
            assertTrue("Band tab $band right > rootWidth: ${bounds.right} > $rootWidth", bounds.right <= rootWidth + 0.5f)
            assertTrue("Band tab $band height < 48dp: ${bounds.height}", bounds.height >= minTouchPx)
        }

        val dfsBounds = composeTestRule
            .onNodeWithTag(SpectrumTestTags.TOGGLE_INCLUDE_DFS)
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(dfsBounds.left >= -0.5f && dfsBounds.right <= rootWidth + 0.5f)
        assertTrue("DFS toggle height < 48dp", dfsBounds.height >= minTouchPx)

        val scanBtnBounds = composeTestRule
            .onNodeWithTag(SpectrumTestTags.BUTTON_SCAN_NOW)
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue("Scan Now height < 48dp", scanBtnBounds.height >= minTouchPx)

        composeTestRule.onNodeWithTag(SpectrumTestTags.SPECTRUM_CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        capturePng("spectrum_24ghz_fontscale_2_0x.png")

        // Verify InlineLegendBar tap-to-explain & dismiss at 2.0x font scale
        composeTestRule.onNodeWithTag(RadarTestTags.legendPill(LegendTopic.CCI_VS_ACI.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.CCI_VS_ACI.topicId))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.legendDismiss(LegendTopic.CCI_VS_ACI.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.CCI_VS_ACI.topicId))
            .assertDoesNotExist()

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }
}
