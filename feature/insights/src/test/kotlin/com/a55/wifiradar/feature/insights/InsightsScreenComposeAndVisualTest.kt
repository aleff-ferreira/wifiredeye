package com.a55.wifiradar.feature.insights

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
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
import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessChecklist
import com.a55.wifiradar.core.model.RssiHistoryWindow
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-450dpi")
class InsightsScreenComposeAndVisualTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val screamingSnakeRegex = Regex("""\b[A-Z0-9]+_[A-Z0-9_]+\b""")
    private val macOrRedactedRegex = Regex("""^[0-9A-FX]{2}(:[0-9A-FX]{2}){5}$""")

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
                    "Raw enum leak detected in Insights UI text: '$text'",
                    SemanticFormatters.containsRawEnumLeak(text),
                )
                for (match in screamingSnakeRegex.findAll(text)) {
                    val token = match.value
                    assertTrue(
                        "SCREAMING_SNAKE_CASE leak '$token' in Insights UI text: '$text'",
                        macOrRedactedRegex.matches(token),
                    )
                }
            }
        }
    }

    @Test
    fun emptyScanWaitingState_rendersWaitingPlaceholder_readinessCta_neverBlocksIdle_andCapturesPng() {
        val locationDisabled = ScannerReadinessState.LocationDisabled(
            checklist = ReadinessChecklist.allReady(),
        )
        val repository = FakeInsightsWifiRadarRepository(
            initialSnapshot = RadarSnapshot(),
            initialHealth = EnvironmentHealthReport(),
            initialReadiness = locationDisabled,
        )
        var capturedReadinessAction: ReadinessAction? = null

        composeTestRule.setContent {
            WifiRadarTheme {
                InsightsRoute(
                    repository = repository,
                    onReadinessAction = { capturedReadinessAction = it },
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Wireless Health & Security Insights").assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_CARD).assertIsDisplayed()
        composeTestRule.onNodeWithTag(InsightsTestTags.HEALTH_WAITING_RING)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Waiting for scan data",
                )
            )
        composeTestRule.onNodeWithTag(InsightsTestTags.HEALTH_SCORE_RING).assertDoesNotExist()
        composeTestRule.onNodeWithText("Waiting for scan data")
            .performScrollTo()
            .assertIsDisplayed()

        capturePng("insights_waiting_state_1x.png")

        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA)
            .performScrollTo()
            .performClick()
        assertEquals(ReadinessAction.OPEN_LOCATION_SETTINGS, capturedReadinessAction)

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }

    @Test
    fun populatedDashboard_rendersHealthSubScoresInsightsTimeGraphRoamEvilTwinAndSecurityAudit_andCapturesPng() {
        val repository = FakeInsightsWifiRadarRepository()
        val dispatchedTargets = mutableListOf<InsightActionTarget>()

        composeTestRule.setContent {
            WifiRadarTheme {
                InsightsRoute(
                    repository = repository,
                    onNavigateToActionTarget = { dispatchedTargets += it },
                )
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(InsightsTestTags.HEALTH_SCORE_RING)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "84 / 100 · Healthy RF",
                )
            )

        // Capture top populated AMOLED screenshot & verify true #000000 black pixels
        val png = capturePng("insights_populated_amoled_1x.png")
        val bmp = BitmapFactory.decodeFile(png.absolutePath)
        var blackPixels = 0
        for (y in 0 until bmp.height step 8) {
            for (x in 0 until bmp.width step 8) {
                if (bmp.getPixel(x, y) == AndroidColor.BLACK) {
                    blackPixels++
                }
            }
        }
        assertTrue("Expected true #000000 AMOLED pixels, got $blackPixels", blackPixels > 250)

        // Verify all 4 sub-score bars
        composeTestRule.onNodeWithTag(InsightsTestTags.subScoreRow("signal_coverage"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(InsightsTestTags.subScoreRow("channel_crowding"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(InsightsTestTags.subScoreRow("interference_purity"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(InsightsTestTags.subScoreRow("security_hygiene"))
            .performScrollTo()
            .assertIsDisplayed()

        // Actionable Insight CTA -> OpenSpectrumBand(5 GHz, 149)
        composeTestRule.onNodeWithTag(InsightsTestTags.insightActionCta("tip_channel_5g"))
            .performScrollTo()
            .performClick()
        assertEquals(
            InsightActionTarget.OpenSpectrumBand(WifiBand.BAND_5_GHZ, 149),
            dispatchedTargets.last(),
        )

        // Time graph window switching (5m default -> 15m -> 1m -> 5m) & series focus toggle
        composeTestRule.onNodeWithTag(InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIVE_MINUTES))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithTag(InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIFTEEN_MINUTES))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(InsightsTestTags.RSSI_TIME_GRAPH_CANVAS)
            .performScrollTo()
            .assertIsDisplayed()
        capturePng("insights_timegraph_and_roam_1x.png")
        composeTestRule.onNodeWithTag(InsightsTestTags.timelineSeriesRow("AA:BB:CC:11:22:02"))
            .performScrollTo()
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Focused"))

        // Mesh Roam Log row
        composeTestRule.onNodeWithTag(InsightsTestTags.roamEventRow(0))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("AA:BB:CC:11:22:01 → AA:BB:CC:11:22:02")
            .performScrollTo()
            .assertIsDisplayed()

        // Evil-Twin Downgrade Alert + Locate Rogue Radio CTA
        composeTestRule.onNodeWithTag(InsightsTestTags.evilTwinAlertCard("HomeMesh"))
            .performScrollTo()
            .assertIsDisplayed()
        capturePng("insights_eviltwin_and_security_1x.png")
        composeTestRule.onNodeWithTag(InsightsTestTags.evilTwinLocateCta("HomeMesh"))
            .performScrollTo()
            .performClick()
        assertEquals(
            InsightActionTarget.LaunchProximity(
                ProximityTargetKey.BssidLock("66:77:88:99:AA:09", "HomeMesh")
            ),
            dispatchedTargets.last(),
        )

        // Security Audit rows (PMF Required, WPS PIN Brute-Force Risk, Inspect Network CTA)
        composeTestRule.onNodeWithTag(InsightsTestTags.securityAuditRow("DD:EE:FF:33:44:05"))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("WPS Enabled · PIN Brute-Force Risk")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(InsightsTestTags.securityInspectCta("AA:BB:CC:11:22:02"))
            .performScrollTo()
            .performClick()
        assertEquals(
            InsightActionTarget.OpenNetworkDetail("AA:BB:CC:11:22:02"),
            dispatchedTargets.last(),
        )

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }

    @Test
    fun diagnosticExport_markdownAndJsonTabs_redactMacs_clipboardCopy_andShareMarkdownAndJsonIntents() {
        val repository = FakeInsightsWifiRadarRepository()

        composeTestRule.setContent {
            WifiRadarTheme {
                InsightsRoute(repository = repository)
            }
        }
        composeTestRule.waitForIdle()

        // Toggle Redact BSSID / MAC Addresses ON and switch preview to JSON Diagnostic
        composeTestRule.onNodeWithTag(InsightsTestTags.EXPORT_REDACT_TOGGLE)
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(InsightsTestTags.exportFormatButton(ExportFormat.JSON))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(InsightsTestTags.EXPORT_PREVIEW_BOX)
            .performScrollTo()
            .assertIsDisplayed()

        // Verify JSON preview displays redacted MACs with ZERO raw SCREAMING_SNAKE_CASE enum leaks!
        assertZeroRawEnumLeaksAcrossSemanticsTree()

        // Copy to Clipboard -> must copy 100% verbatim redacted JSON and display confirmation
        composeTestRule.onNodeWithTag(InsightsTestTags.COPY_EXPORT_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(InsightsTestTags.EXPORT_COPY_CONFIRMATION)
            .performScrollTo()
            .assertIsDisplayed()

        val clipboard = composeTestRule.activity.getSystemService(ClipboardManager::class.java)
        assertNotNull(clipboard?.primaryClip)
        val copiedText = clipboard!!.primaryClip!!.getItemAt(0).text.toString()
        assertTrue("Clipboard must contain full verbatim JSON", copiedText.contains("\"BAND_5_GHZ\""))
        assertTrue("Clipboard must mask MAC octets", copiedText.contains("AA:BB:CC:XX:XX:02"))
        assertFalse("Clipboard must never leak unredacted MAC", copiedText.contains("AA:BB:CC:11:22:02"))

        // Click Share Markdown -> verify ACTION_CHOOSER wrapping ACTION_SEND text/markdown
        composeTestRule.onNodeWithTag(InsightsTestTags.SHARE_MARKDOWN_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        val mdChooser = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("Expected Share Markdown chooser intent", mdChooser)
        assertEquals(Intent.ACTION_CHOOSER, mdChooser.action)
        @Suppress("DEPRECATION")
        val mdSend = mdChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(mdSend)
        assertEquals(Intent.ACTION_SEND, mdSend!!.action)
        assertEquals("text/markdown", mdSend.type)
        val mdExtraText = mdSend.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(mdExtraText.contains("# A55 WiFi Radar Diagnostic Report"))
        assertTrue(mdExtraText.contains("AA:BB:CC:XX:XX:02"))
        assertFalse(mdExtraText.contains("AA:BB:CC:11:22:02"))

        // Click Share JSON -> verify ACTION_CHOOSER wrapping ACTION_SEND application/json with verbatim JSON
        composeTestRule.onNodeWithTag(InsightsTestTags.SHARE_JSON_BUTTON)
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        val jsonChooser = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertNotNull("Expected Share JSON chooser intent", jsonChooser)
        assertEquals(Intent.ACTION_CHOOSER, jsonChooser.action)
        @Suppress("DEPRECATION")
        val jsonSend = jsonChooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull(jsonSend)
        assertEquals(Intent.ACTION_SEND, jsonSend!!.action)
        assertEquals("application/json", jsonSend.type)
        val jsonExtraText = jsonSend.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(jsonExtraText.contains("\"BAND_5_GHZ\""))
        assertTrue(jsonExtraText.contains("AA:BB:CC:XX:XX:02"))
    }

    @Test
    fun activityRecreation_andFontScale200Percent_zeroOverflow_48dpTargets_andZeroRawEnumLeakAudit() {
        val repository = FakeInsightsWifiRadarRepository()

        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        WifiRadarTheme {
                            InsightsRoute(repository = repository)
                        }
                    }
                }
            )
        }
        composeTestRule.waitForIdle()

        // Mutate window -> 15m, format -> JSON, Redact MACs -> ON
        composeTestRule.onNodeWithTag(InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIFTEEN_MINUTES))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(InsightsTestTags.EXPORT_REDACT_TOGGLE)
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(InsightsTestTags.exportFormatButton(ExportFormat.JSON))
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        // Recreate Activity and re-mount InsightsRoute -> SavedStateHandle must restore everything!
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setContentView(
                ComposeView(activity).apply {
                    setContent {
                        val baseDensity = LocalDensity.current
                        CompositionLocalProvider(
                            LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
                        ) {
                            WifiRadarTheme {
                                InsightsRoute(repository = repository)
                            }
                        }
                    }
                }
            )
        }
        composeTestRule.waitForIdle()

        // Capture 200% font scale screenshot of Insights
        capturePng("insights_populated_fontscale_2_0x.png")

        composeTestRule.onNodeWithTag(InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIFTEEN_MINUTES))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        composeTestRule.onNodeWithTag(InsightsTestTags.exportFormatButton(ExportFormat.JSON))
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

        // Verify >= 48dp touch targets & zero horizontal overflow at fontScale = 2.0f on 360dp width
        val rootWidth = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot.width
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() } - 1.5f

        val tagsToVerifyBounds = listOf(
            InsightsTestTags.HEADER_SCAN_NOW_BUTTON,
            InsightsTestTags.historyWindowChip(RssiHistoryWindow.ONE_MINUTE),
            InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIVE_MINUTES),
            InsightsTestTags.historyWindowChip(RssiHistoryWindow.FIFTEEN_MINUTES),
            InsightsTestTags.evilTwinLocateCta("HomeMesh"),
            InsightsTestTags.EXPORT_REDACT_TOGGLE,
            InsightsTestTags.SHARE_MARKDOWN_BUTTON,
            InsightsTestTags.SHARE_JSON_BUTTON,
            InsightsTestTags.COPY_EXPORT_BUTTON,
        )

        for (tag in tagsToVerifyBounds) {
            val bounds = composeTestRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue("$tag left < 0: ${bounds.left}", bounds.left >= -0.5f)
            assertTrue("$tag right > rootWidth: ${bounds.right} > $rootWidth", bounds.right <= rootWidth + 0.5f)
            assertTrue("$tag height < 48dp at 2.0x fontScale: ${bounds.height}", bounds.height >= minTouchPx)
        }

        // Verify InlineLegendBar tap-to-explain & dismiss at 2.0x font scale
        composeTestRule.onNodeWithTag(RadarTestTags.legendPill(LegendTopic.PMF_80211W.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.PMF_80211W.topicId))
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(RadarTestTags.legendDismiss(LegendTopic.PMF_80211W.topicId))
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard(LegendTopic.PMF_80211W.topicId))
            .assertDoesNotExist()

        assertZeroRawEnumLeaksAcrossSemanticsTree()
    }
}
