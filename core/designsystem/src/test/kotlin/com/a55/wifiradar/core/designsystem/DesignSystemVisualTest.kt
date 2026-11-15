package com.a55.wifiradar.core.designsystem

import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.canvas.RadarScoreRingCanvas
import com.a55.wifiradar.core.designsystem.canvas.drawRadarBackdropGrid
import com.a55.wifiradar.core.designsystem.component.BandPill
import com.a55.wifiradar.core.designsystem.component.InlineLegendBar
import com.a55.wifiradar.core.designsystem.component.InlineLegendPill
import com.a55.wifiradar.core.designsystem.component.LegendSpec
import com.a55.wifiradar.core.designsystem.component.RadarCard
import com.a55.wifiradar.core.designsystem.component.RadarElevatedCard
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.designsystem.component.ReadinessBlockerKind
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistCard
import com.a55.wifiradar.core.designsystem.component.ReadinessChecklistUiModel
import com.a55.wifiradar.core.designsystem.component.ScanPillMode
import com.a55.wifiradar.core.designsystem.component.ScanStatusTopPill
import com.a55.wifiradar.core.designsystem.component.SecurityGradeBadge
import com.a55.wifiradar.core.designsystem.component.SemanticMetricRow
import com.a55.wifiradar.core.designsystem.component.SignalBadge
import com.a55.wifiradar.core.designsystem.layout.RadarScaffold
import com.a55.wifiradar.core.designsystem.layout.RadarTab
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.designsystem.theme.SemanticTone
import com.a55.wifiradar.core.designsystem.theme.WifiRadarTheme
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.LegendTopic
import com.a55.wifiradar.core.model.RadarDestination
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ReadinessChecklist
import com.a55.wifiradar.core.model.ReadinessStep
import com.a55.wifiradar.core.model.ReadinessStepState
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import com.a55.wifiradar.core.model.UniiSubBand
import com.a55.wifiradar.core.model.WifiBand
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertEquals
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
class DesignSystemVisualTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

    @Test
    fun amoledShowcase_rendersBadgesCardsCanvasAndNeverBlocksWaitForIdle() {
        composeTestRule.setContent {
            WifiRadarTheme {
                DesignSystemShowcaseContent()
            }
        }

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("showcase_health_ring")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Environment Health: 88 out of 100, Healthy RF"),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "88 / 100 · Healthy RF",
                )
            )

        val pngFile = capturePng("designsystem_amoled_showcase_1x.png")
        val bitmap = BitmapFactory.decodeFile(pngFile.absolutePath)
        var trueBlackPixelCount = 0
        val step = 8
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                if (bitmap.getPixel(x, y) == AndroidColor.BLACK) {
                    trueBlackPixelCount++
                }
            }
        }
        assertTrue(
            "Expected substantial true #000000 AMOLED pixels in screenshot, got $trueBlackPixelCount",
            trueBlackPixelCount > 200,
        )
    }

    @Test
    fun inlineLegendPill_tapToExplainAndDismiss_allCanonicalTopics() {
        val primaryEightTopics = listOf(
            LegendTopic.DBM,
            LegendTopic.CCI_VS_ACI,
            LegendTopic.DFS_TDWR,
            LegendTopic.PMF_80211W,
            LegendTopic.OS_SCAN_THROTTLE,
            LegendTopic.KALMAN_SMOOTHING,
            LegendTopic.PSC_6GHZ,
            LegendTopic.BSS_LOAD,
        )

        composeTestRule.setContent {
            WifiRadarTheme {
                RadarScaffold(
                    selectedTab = RadarTab.NETWORKS,
                    onSelectTab = {},
                    topStatusSlot = {
                        ScanStatusTopPill(
                            mode = ScanPillMode.LIVE,
                            networkCount = 14,
                            updatedSecondsAgo = 1,
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadarCard(accentTone = SemanticTone.GOOD, highlighted = true) {
                            Text(
                                text = "Tap Any Term for Instant Plain-English Meaning",
                                style = RadarTheme.typography.metricMedium,
                                color = RadarTheme.colors.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            InlineLegendBar(topics = primaryEightTopics)
                        }
                        RadarCard {
                            InlineLegendPill(
                                spec = LegendSpec(
                                    id = "custom_snr",
                                    pillLabel = "SNR",
                                    title = "Signal-to-Noise Margin",
                                    explanation = "Measures how far your router signal rises above background channel noise. Margins above 25 dB deliver rock-solid throughput.",
                                    tone = SemanticTone.EXCELLENT,
                                )
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(RadarTestTags.legendPill("dbm"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()

        composeTestRule.onNodeWithTag(RadarTestTags.legendPill("dbm"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard("dbm"))
            .assertIsDisplayed()

        capturePng("designsystem_legend_dbm_expanded.png")

        composeTestRule.onNodeWithTag(RadarTestTags.legendDismiss("dbm")).performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.legendCard("dbm")).assertDoesNotExist()
        composeTestRule.onNodeWithTag(RadarTestTags.legendPill("dbm"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))

        for (topic in primaryEightTopics.drop(1)) {
            composeTestRule.onNodeWithTag(RadarTestTags.legendPill(topic.topicId)).performClick()
            composeTestRule.onNodeWithTag(RadarTestTags.legendCard(topic.topicId)).assertIsDisplayed()
        }
    }

    @Test
    fun readinessChecklistCard_progressiveStepsAndPrimaryCtaAndDomainOverload() {
        var ctaClicks = 0
        var lastDomainAction: ReadinessAction? = null

        val domainLocationDisabled = ScannerReadinessState.LocationDisabled(
            checklist = ReadinessChecklist(
                permissionStep = ReadinessStep(
                    stepNumber = 1,
                    title = "Nearby Wi-Fi & Precise Location Permission",
                    plainEnglishDetail = "Granted — 100% offline local Wi-Fi scanning enabled.",
                    state = ReadinessStepState.SATISFIED,
                ),
                locationStep = ReadinessStep(
                    stepNumber = 2,
                    title = "Device Location Services",
                    plainEnglishDetail = "Turned off — Android hides nearby Wi-Fi networks while Location is off.",
                    state = ReadinessStepState.ACTION_REQUIRED,
                    action = ReadinessAction.OPEN_LOCATION_SETTINGS,
                    actionButtonLabel = "Open Location Settings",
                ),
                wifiStep = ReadinessStep(
                    stepNumber = 3,
                    title = "Wi-Fi Radio",
                    plainEnglishDetail = "Turned on and ready.",
                    state = ReadinessStepState.SATISFIED,
                ),
            )
        )

        composeTestRule.setContent {
            var useDomainOverload by remember { mutableStateOf(false) }
            WifiRadarTheme {
                RadarScaffold(
                    selectedTab = RadarTab.NETWORKS,
                    onSelectTab = {},
                    topStatusSlot = {
                        ScanStatusTopPill(
                            mode = ScanPillMode.PAUSED_BLOCKED,
                            networkCount = 0,
                            updatedSecondsAgo = 0,
                        )
                    },
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (!useDomainOverload) {
                            ReadinessChecklistCard(
                                model = ReadinessChecklistUiModel.fromBlocker(
                                    ReadinessBlockerKind.LOCATION_DISABLED
                                ),
                                onPrimaryActionClick = {
                                    ctaClicks++
                                    useDomainOverload = true
                                },
                            )
                        } else {
                            ReadinessChecklistCard(
                                readinessState = domainLocationDisabled,
                                onAction = { lastDomainAction = it },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag(RadarTestTags.readinessStep("permissions"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Completed"))
        composeTestRule.onNodeWithTag(RadarTestTags.readinessStep("location"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Action needed"))
        composeTestRule.onNodeWithTag(RadarTestTags.readinessStep("wifi"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Pending"))

        capturePng("designsystem_readiness_checklist.png")

        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        assertEquals(1, ctaClicks)

        // Now domain overload is displayed
        composeTestRule.onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA).performClick()
        assertEquals(ReadinessAction.OPEN_LOCATION_SETTINGS, lastDomainAction)
    }

    @Test
    fun fontScale200Percent_on360dpWidth_zeroClippingAndFullReachability() {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = baseDensity.density, fontScale = 2.0f)
            ) {
                WifiRadarTheme {
                    RadarScaffold(
                        selectedTab = RadarDestination.SPECTRUM,
                        onSelectTab = {},
                        topStatusSlot = {
                            ScanStatusTopPill(
                                mode = ScanPillMode.THROTTLED_PASSIVE,
                                networkCount = 18,
                                updatedSecondsAgo = 9,
                                throttleCountdownSec = 19,
                            )
                        },
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadarCard(accentTone = SemanticTone.EXCELLENT, highlighted = true) {
                                SignalBadge(rssiDbm = -48, prefixLabel = "Connected")
                                Spacer(modifier = Modifier.height(8.dp))
                                SemanticMetricRow(
                                    label = "Smoothed Signal",
                                    numericValue = "-48 dBm",
                                    plainEnglishQualifier = "Excellent · Very Close",
                                    tone = SemanticTone.EXCELLENT,
                                    legendTopic = LegendTopic.DBM,
                                )
                            }
                            InlineLegendBar(
                                topics = listOf(LegendTopic.DBM, LegendTopic.CCI_VS_ACI, LegendTopic.DFS_TDWR),
                                initialTopicId = LegendTopic.CCI_VS_ACI.topicId,
                            )
                            ReadinessChecklistCard(
                                model = ReadinessChecklistUiModel.fromBlocker(
                                    ReadinessBlockerKind.MISSING_PERMISSION
                                ),
                                onPrimaryActionClick = {},
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        val rootBounds = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val rootWidth = rootBounds.width
        val minTouchPx = with(composeTestRule.density) { 48.dp.toPx() } - 1.5f

        var previousRight = -1f
        for (destination in RadarDestination.entries) {
            val tabNode = composeTestRule
                .onNodeWithTag(RadarTestTags.bottomNavTab(destination.route))
                .assertIsDisplayed()
                .fetchSemanticsNode()
            val bounds = tabNode.boundsInRoot
            assertTrue("Tab ${destination.tabTitle} left < 0: ${bounds.left}", bounds.left >= -0.5f)
            assertTrue("Tab ${destination.tabTitle} right > rootWidth: ${bounds.right} > $rootWidth", bounds.right <= rootWidth + 0.5f)
            assertTrue(
                "Tab ${destination.tabTitle} overlaps previous tab: prevRight=$previousRight, left=${bounds.left}",
                bounds.left >= previousRight - 1.0f,
            )
            assertTrue("Tab ${destination.tabTitle} height < 48dp", bounds.height >= minTouchPx)
            previousRight = bounds.right
        }

        // Verify all 4 user-visible canonical tab labels exist un-ellipsized
        composeTestRule.onNodeWithText("Networks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Proximity").assertIsDisplayed()
        composeTestRule.onNodeWithText("Spectrum").assertIsDisplayed()
        composeTestRule.onNodeWithText("Insights").assertIsDisplayed()

        capturePng("designsystem_amoled_showcase_fontscale_2_0x.png")

        val legendCardBounds = composeTestRule
            .onNodeWithTag(RadarTestTags.legendCard(LegendTopic.CCI_VS_ACI.topicId))
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(legendCardBounds.left >= 0f && legendCardBounds.right <= rootWidth + 0.5f)

        val dismissBounds = composeTestRule
            .onNodeWithTag(RadarTestTags.legendDismiss(LegendTopic.CCI_VS_ACI.topicId))
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue("Dismiss button height < 48dp at 2.0x font scale", dismissBounds.height >= minTouchPx)

        val ctaBounds = composeTestRule
            .onNodeWithTag(RadarTestTags.READINESS_PRIMARY_CTA)
            .performScrollTo()
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(ctaBounds.left >= 0f && ctaBounds.right <= rootWidth + 0.5f)
        assertTrue("Readiness CTA height < 48dp at 2.0x font scale", ctaBounds.height >= minTouchPx)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignSystemShowcaseContent() {
    var selectedTab by remember { mutableStateOf(RadarDestination.NETWORKS) }

    RadarScaffold(
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        topStatusSlot = {
            ScanStatusTopPill(
                mode = ScanPillMode.LIVE,
                networkCount = 18,
                updatedSecondsAgo = 2,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadarElevatedCard(accentTone = SemanticTone.EXCELLENT) {
                Text(
                    text = "Galaxy A55 5G · True AMOLED 120Hz Telemetry",
                    style = RadarTheme.typography.metricMedium,
                    color = RadarTheme.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SignalBadge(rssiDbm = -46, prefixLabel = "Connected")
                    SignalBadge(rssiDbm = -59)
                    SignalBadge(rssiDbm = -71)
                    SignalBadge(rssiDbm = -83)
                }
            }

            RadarCard(accentTone = SemanticTone.GOOD, highlighted = true) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BandPill(frequencyMhz = 2437, channel = 6, widthMhz = 20)
                    BandPill(
                        descriptor = ChannelDescriptor(
                            band = WifiBand.BAND_5_GHZ,
                            primaryFreqMhz = 5180,
                            primaryChannel = 36,
                            centerFreq0Mhz = 5210,
                            centerChannel0 = 42,
                            width = ChannelWidth.WIDTH_80_MHZ,
                            occupiedStartFreqMhz = 5170,
                            occupiedEndFreqMhz = 5250,
                            uniiSubBand = UniiSubBand.UNII_1,
                            isCanonicalPrimary = true,
                        ),
                        congestionScore0To100 = 92,
                    )
                    BandPill(band = WifiBand.BAND_6_GHZ)
                    SecurityGradeBadge(grade = SecurityGrade.A_PLUS, protocol = SecurityProtocol.WPA3_SAE)
                    SecurityGradeBadge(grade = "F")
                }
                Spacer(modifier = Modifier.height(10.dp))
                SemanticMetricRow(
                    label = "Channel Width",
                    numericValue = "80 MHz",
                    plainEnglishQualifier = "Wide Fast Lane",
                    tone = SemanticTone.GOOD,
                    legendTopic = LegendTopic.DBM,
                )
            }

            RadarCard(
                modifier = Modifier.drawRadarBackdropGrid(),
                accentTone = SemanticTone.EXCELLENT,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RadarScoreRingCanvas(
                        score0To100 = 88,
                        label = "Environment Health",
                        qualifier = "Healthy RF",
                        tone = SemanticTone.EXCELLENT,
                        testTag = "showcase_health_ring",
                    )
                }
            }
        }
    }
}
