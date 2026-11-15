package com.a55.wifiradar

import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.model.RadarDestination
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
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
class MainActivitySmokeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun coldLaunch_rendersFourTabsSurvivesRecreationAndCapturesAmoledScreenshot() {
        composeTestRule.onNodeWithText("A55 WiFi Radar").assertIsDisplayed()

        for (destination in RadarDestination.entries) {
            composeTestRule.onNodeWithText(destination.tabTitle).assertIsDisplayed()
        }
        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("networks")).assertIsSelected()

        val outDir = File("build/outputs/roborazzi").apply { mkdirs() }
        val pngFile = File(outDir, "app_m1_shell_main.png")
        composeTestRule.onRoot().captureRoboImage(
            pngFile,
            RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        assertTrue("Expected PNG ${pngFile.absolutePath} to exist", pngFile.exists())
        assertTrue("Expected non-empty PNG (${pngFile.length()} bytes)", pngFile.length() > 2_048L)
        assertNotNull(BitmapFactory.decodeFile(pngFile.absolutePath))

        // Navigate across tabs and verify state survives Activity recreation
        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("spectrum")).performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("spectrum")).assertIsSelected()
        composeTestRule.onNodeWithText("Channel Overlap & Spectrum Analyzer").assertIsDisplayed()

        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("insights")).performClick()
        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("insights")).assertIsSelected()
        composeTestRule.onNodeWithText("Wireless Health & Security Insights").assertIsDisplayed()

        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(RadarTestTags.bottomNavTab("insights")).assertIsSelected()
        composeTestRule.onNodeWithText("Wireless Health & Security Insights").assertIsDisplayed()
    }
}
