package com.a55.wifiradar.e2e.robots

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.assertScreenContainsAny
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.clickFirstMatching
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.dumpScreenText
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.hasAnyPhrase
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.isBottomTab
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.isClickableWithAnyPhrase
import com.a55.wifiradar.e2e.robots.ComposeSemanticMatchers.isToggleWithAnyPhrase
import org.junit.Assert.assertTrue

/**
 * Opaque-box user-journey robots driving all 4 canonical bottom tabs (`Networks`, `Proximity`,
 * `Spectrum`, `Insights`), Detail Bottom Sheet, inline Legend Pills, and Readiness Checklist
 * exclusively through Compose Accessibility Semantics & touch gestures.
 */
class NavigationRobot(private val rule: ComposeTestRule) {

    fun openNetworks(): NetworksRobot = selectTab("Networks", "networks").let { NetworksRobot(rule) }
    fun openProximity(): ProximityRobot = selectTab("Proximity", "proximity").let { ProximityRobot(rule) }
    fun openSpectrum(): SpectrumRobot = selectTab("Spectrum", "spectrum").let { SpectrumRobot(rule) }
    fun openInsights(): InsightsRobot = selectTab("Insights", "insights").let { InsightsRobot(rule) }

    fun openInterferenceBreakdown() {
        val spec = openSpectrum()
        spec.scrollUntilVisible("Co-Channel", "Adjacent-Channel", "Shared-Channel", "Overlapping-Channel", "CCI", "ACI")
        if (!ComposeSemanticMatchers.screenContainsAny(
                rule,
                "Co-Channel",
                "Adjacent-Channel",
                "Shared-Channel",
                "Overlapping-Channel",
                "Interference",
            )
        ) {
            openInsights().scrollUntilVisible(
                "Co-Channel",
                "Adjacent-Channel",
                "Shared-Channel",
                "Overlapping-Channel",
                "CCI",
                "ACI",
            )
        }
    }

    fun openTimeAndRoamGraph() {
        val ins = openInsights()
        ins.scrollUntilVisible("Roam", "Timeline", "History", "15m", "5m")
        if (!ComposeSemanticMatchers.screenContainsAny(rule, "Roam", "Timeline", "15m", "5m")) {
            openSpectrum().scrollUntilVisible("Roam", "Timeline", "History", "15m", "5m")
        }
    }

    fun assertTabSelected(tabTitle: String) {
        val route = tabTitle.lowercase()
        val tagged = runCatching {
            rule.onNodeWithTag("radar_bottom_tab_$route").assertIsSelected()
            true
        }.getOrDefault(false)
        if (!tagged) {
            rule.onAllNodes(isBottomTab(tabTitle))[0].assertIsSelected()
        }
    }

    private fun selectTab(tabTitle: String, routeKey: String) {
        val clickedTag = runCatching {
            rule.onNodeWithTag("radar_bottom_tab_$routeKey").performClick()
            rule.waitForIdle()
            true
        }.getOrDefault(false)
        if (!clickedTag) {
            clickFirstMatching(rule, isBottomTab(tabTitle))
        }
    }
}

class NetworksRobot(private val rule: ComposeTestRule) {

    fun assertNetworkDisplayed(nameOrBssid: String): NetworksRobot {
        ensureVisibleOrScroll(nameOrBssid)
        assertScreenContainsAny(rule, "Networks($nameOrBssid)", nameOrBssid)
        return this
    }

    fun assertNetworkNotDisplayed(nameOrBssid: String): NetworksRobot {
        val dump = dumpScreenText(rule)
        assertTrue(
            "Expected '$nameOrBssid' NOT to be displayed on Networks, but found in:\n$dump",
            !dump.contains(nameOrBssid, ignoreCase = true),
        )
        return this
    }

    fun enterSearchQuery(query: String): NetworksRobot {
        val inputs = rule.onAllNodes(hasSetTextAction())
        assertTrue("Expected search input field on Networks screen", inputs.fetchSemanticsNodes().isNotEmpty())
        inputs[0].performTextClearance()
        if (query.isNotEmpty()) {
            inputs[0].performTextInput(query)
        }
        rule.waitForIdle()
        return this
    }

    fun clearSearch(): NetworksRobot {
        val clearedViaBtn = runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase("Clear search", "Clear query", "Clear"))
            true
        }.getOrDefault(false)
        if (!clearedViaBtn) {
            enterSearchQuery("")
        }
        return this
    }

    fun toggleFilterChip(vararg chipLabels: String): NetworksRobot {
        clickFirstMatching(rule, isToggleWithAnyPhrase(*chipLabels))
        return this
    }

    fun switchToAllRadiosMode(): NetworksRobot {
        clickFirstMatching(rule, isToggleWithAnyPhrase("All Radios", "BSSID", "Individual Radios"))
        return this
    }

    fun switchToGroupedMode(): NetworksRobot {
        clickFirstMatching(rule, isToggleWithAnyPhrase("Grouped", "Mesh"))
        return this
    }

    fun selectSortOption(vararg sortLabels: String): NetworksRobot {
        val directClicked = runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase(*sortLabels))
            true
        }.getOrDefault(false)
        if (!directClicked) {
            clickFirstMatching(
                rule,
                isClickableWithAnyPhrase("Sort", "Strongest Signal", "Name A", "Channel", "Least Congested", "Security Risk"),
            )
            clickFirstMatching(rule, isClickableWithAnyPhrase(*sortLabels))
        }
        return this
    }

    fun openDetailSheetFor(ssidOrBssid: String): NetworkDetailSheetRobot {
        ensureVisibleOrScroll(ssidOrBssid)
        clickFirstMatching(rule, isClickableWithAnyPhrase(ssidOrBssid))
        return NetworkDetailSheetRobot(rule)
    }

    fun launchProximityFor(ssidOrBssid: String): ProximityRobot {
        ensureVisibleOrScroll(ssidOrBssid)
        // Prefer direct 1-tap Proximity button scoped to or labelled with ssidOrBssid, else via card -> CTA
        val direct = runCatching {
            val matcher = SemanticsMatcher("1-tap Proximity CTA for $ssidOrBssid") { node ->
                val clickable = node.config.contains(SemanticsActions.OnClick)
                val combined = ComposeSemanticMatchers.extractAllStrings(node).joinToString(" | ")
                clickable &&
                    combined.contains("Proximity", ignoreCase = true) &&
                    combined.contains(ssidOrBssid, ignoreCase = true)
            }
            clickFirstMatching(rule, matcher)
            true
        }.getOrDefault(false)

        if (!direct) {
            openDetailSheetFor(ssidOrBssid).clickMeasureProximity()
        }
        return ProximityRobot(rule)
    }

    fun triggerManualRefresh(): NetworksRobot {
        clickFirstMatching(rule, isClickableWithAnyPhrase("Refresh", "Scan Now", "Rescan", "Sweep"))
        return this
    }

    fun clickReadinessPrimaryCta(): NetworksRobot {
        val byTag = runCatching {
            rule.onNodeWithTag("readiness_primary_cta").performScrollTo().performClick()
            rule.waitForIdle()
            true
        }.getOrDefault(false)
        if (!byTag) {
            clickFirstMatching(
                rule,
                isClickableWithAnyPhrase(
                    "Grant",
                    "Allow",
                    "Upgrade to Precise",
                    "Open Location",
                    "Turn On Wi-Fi",
                    "Open App Permission",
                    "Settings",
                ),
            )
        }
        return this
    }

    private fun ensureVisibleOrScroll(phrase: String) {
        if (ComposeSemanticMatchers.screenContainsAny(rule, phrase)) return
        repeat(4) {
            runCatching {
                val scrollable = rule.onAllNodes(
                    SemanticsMatcher("scrollable") { it.config.contains(SemanticsActions.ScrollBy) },
                )
                if (scrollable.fetchSemanticsNodes().isNotEmpty()) {
                    scrollable[0].performTouchInput { swipeUp() }
                    rule.waitForIdle()
                }
            }
            if (ComposeSemanticMatchers.screenContainsAny(rule, phrase)) return
        }
    }
}

class NetworkDetailSheetRobot(private val rule: ComposeTestRule) {

    fun assertDetailContains(vararg phrases: String): NetworkDetailSheetRobot {
        ensureDetailVisible(*phrases)
        assertScreenContainsAny(rule, "NetworkDetailSheet", *phrases)
        return this
    }

    fun assignNickname(nickname: String): NetworkDetailSheetRobot {
        val hasEditTrigger = runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase("Nickname", "Alias", "Rename", "Edit Name", "Label"))
            true
        }.getOrDefault(false)
        val inputs = rule.onAllNodes(hasSetTextAction())
        val targetInput = if (inputs.fetchSemanticsNodes().size > 1) inputs[inputs.fetchSemanticsNodes().lastIndex] else inputs[0]
        targetInput.performTextClearance()
        if (nickname.isNotEmpty()) {
            targetInput.performTextInput(nickname)
        }
        rule.waitForIdle()
        runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase("Save", "Apply", "Done"))
        }
        if (!hasEditTrigger) {
            rule.waitForIdle()
        }
        return this
    }

    fun copySummary(): NetworkDetailSheetRobot {
        ensureDetailVisible("Copy")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Copy Summary", "Copy BSSID", "Copy"))
        return this
    }

    fun clickMeasureProximity(): ProximityRobot {
        ensureDetailVisible("Proximity")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Measure Proximity", "Track Proximity", "Open Proximity", "Proximity"))
        return ProximityRobot(rule)
    }

    fun dismiss(): NetworksRobot {
        val closed = runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase("Close", "Dismiss", "Done"))
            true
        }.getOrDefault(false)
        if (!closed) {
            androidx.test.espresso.Espresso.pressBack()
            rule.waitForIdle()
        }
        return NetworksRobot(rule)
    }

    private fun ensureDetailVisible(vararg phrases: String) {
        if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return
        repeat(3) {
            runCatching {
                val scrollable = rule.onAllNodes(
                    SemanticsMatcher("scrollable") { it.config.contains(SemanticsActions.ScrollBy) },
                )
                if (scrollable.fetchSemanticsNodes().isNotEmpty()) {
                    scrollable[scrollable.fetchSemanticsNodes().lastIndex].performTouchInput { swipeUp() }
                    rule.waitForIdle()
                }
            }
            if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return
        }
    }
}

class ProximityRobot(private val rule: ComposeTestRule) {

    fun selectEnvironment(vararg envPhrases: String): ProximityRobot {
        scrollUntilVisible(*envPhrases)
        clickFirstMatching(rule, isToggleWithAnyPhrase(*envPhrases))
        return this
    }

    fun switchToFollowMeshMode(): ProximityRobot {
        scrollUntilVisible("Follow Mesh", "Mesh")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Follow Mesh", "Follow SSID", "Mesh Roam"))
        return this
    }

    fun switchToSingleRouterBssidMode(): ProximityRobot {
        scrollUntilVisible("Single Router", "Lock BSSID", "BSSID Lock")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Single Router", "Lock BSSID", "BSSID Lock", "Pin BSSID"))
        return this
    }

    fun selectTargetViaPicker(targetSsidOrBssid: String): ProximityRobot {
        clickFirstMatching(rule, isClickableWithAnyPhrase("Switch Target", "Select Target", "Change Target", "Target"))
        clickFirstMatching(rule, isClickableWithAnyPhrase(targetSsidOrBssid))
        return this
    }

    fun clickCalibrateOneMeter(): ProximityRobot {
        scrollUntilVisible("Calibrate 1m", "Calibrate")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Calibrate 1m", "1m Calibrate", "Set 1m"))
        return this
    }

    fun clickResetCalibration(): ProximityRobot {
        scrollUntilVisible("Reset Calibration", "Clear Calibration", "Default Range")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Reset Calibration", "Clear Calibration", "Reset 1m"))
        return this
    }

    fun clickResetStats(): ProximityRobot {
        scrollUntilVisible("Reset Stats", "Clear Stats", "Reset History")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Reset Stats", "Clear Stats", "Reset Min/Max", "Reset History"))
        return this
    }

    fun toggleHapticGeiger(): ProximityRobot {
        scrollUntilVisible("Haptic", "Vibrate")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Haptic", "Vibrate"))
        return this
    }

    fun toggleAudioGeiger(): ProximityRobot {
        scrollUntilVisible("Audio", "Tone", "Sound")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Audio", "Tone", "Sound"))
        return this
    }

    fun toggleBurstScan(): ProximityRobot {
        scrollUntilVisible("Burst", "Pulse")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Burst", "Pulse"))
        return this
    }

    /**
     * Parses the displayed distance estimate (in meters) from visible Proximity screen text.
     * For range strings such as `"Est. ~2.4 – 4.6 m"`, returns the midpoint `(2.4 + 4.6) / 2.0`.
     * For RTT strings such as `"3.4 m ± 0.4 m"`, returns `3.4`.
     */
    fun readDisplayedDistanceMeters(): Double {
        val text = dumpScreenText(rule)
        Regex("""<\s*(0\.\d+)\s*m""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDouble() * 0.8
        }
        Regex(""">\s*(\d+(?:\.\d+)?)\s*m""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDouble() + 5.0
        }
        Regex("""~?\s*(\d+(?:\.\d+)?)\s*[–\-]\s*(\d+(?:\.\d+)?)\s*m\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            val lo = it.groupValues[1].toDouble()
            val hi = it.groupValues[2].toDouble()
            return (lo + hi) / 2.0
        }
        Regex("""(\d+(?:\.\d+)?)\s*m\s*±""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDouble()
        }
        Regex("""~?\s*(\d+(?:\.\d+)?)\s*m\b""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toDouble()
        }
        throw AssertionError("Could not parse meter distance from Proximity UI:\n$text")
    }

    fun assertAccessibleCanvasPresent(): ProximityRobot {
        val nodes = rule.onAllNodes(
            SemanticsMatcher("Canvas with telemetry semantics") { node ->
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                val sd = node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                (cd.contains("dBm", ignoreCase = true) || cd.contains("Signal", ignoreCase = true) ||
                    sd.contains("dBm", ignoreCase = true) || sd.contains("Signal Lost", ignoreCase = true))
            },
            useUnmergedTree = true,
        )
        assertTrue(
            "Expected Proximity Sonar Gauge / Sparkline Canvas to expose accessible semantics",
            nodes.fetchSemanticsNodes().isNotEmpty(),
        )
        return this
    }

    fun scrollUntilVisible(vararg phrases: String): ProximityRobot {
        if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        repeat(4) {
            runCatching {
                val scrollable = rule.onAllNodes(
                    SemanticsMatcher("scrollable") { it.config.contains(SemanticsActions.ScrollBy) },
                )
                if (scrollable.fetchSemanticsNodes().isNotEmpty()) {
                    scrollable[0].performTouchInput { swipeUp() }
                    rule.waitForIdle()
                }
            }
            if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        }
        return this
    }
}

class SpectrumRobot(private val rule: ComposeTestRule) {

    fun selectBand(bandLabel: String): SpectrumRobot {
        clickFirstMatching(rule, isToggleWithAnyPhrase(bandLabel))
        return this
    }

    fun toggleIncludeDfs(): SpectrumRobot {
        scrollUntilVisible("Include DFS", "DFS")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Include DFS", "Allow DFS", "DFS Channels"))
        return this
    }

    fun toggleExcludeOwnSsid(): SpectrumRobot {
        scrollUntilVisible("Exclude", "Own Network", "Own SSID")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Exclude Own", "Exclude Connected", "Ignore My"))
        return this
    }

    fun selectHistoryWindow(windowLabel: String): SpectrumRobot {
        scrollUntilVisible(windowLabel)
        clickFirstMatching(rule, isToggleWithAnyPhrase(windowLabel))
        return this
    }

    fun selectApCurve(ssidOrBssid: String): SpectrumRobot {
        scrollUntilVisible(ssidOrBssid)
        runCatching {
            clickFirstMatching(rule, isClickableWithAnyPhrase(ssidOrBssid))
        }
        return this
    }

    fun assertAccessibleCanvasPresent(): SpectrumRobot {
        val nodes = rule.onAllNodes(
            SemanticsMatcher("Spectrum Canvas with telemetry semantics") { node ->
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                val sd = node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                cd.contains("Spectrum", ignoreCase = true) ||
                    cd.contains("Channel", ignoreCase = true) ||
                    cd.contains("GHz", ignoreCase = true) ||
                    sd.contains("Channel", ignoreCase = true) ||
                    sd.contains("GHz", ignoreCase = true)
            },
            useUnmergedTree = true,
        )
        assertTrue(
            "Expected Spectrum Canvas to expose accessible semantics",
            nodes.fetchSemanticsNodes().isNotEmpty(),
        )
        return this
    }

    fun scrollUntilVisible(vararg phrases: String): SpectrumRobot {
        if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        repeat(4) {
            runCatching {
                val scrollable = rule.onAllNodes(
                    SemanticsMatcher("scrollable") { it.config.contains(SemanticsActions.ScrollBy) },
                )
                if (scrollable.fetchSemanticsNodes().isNotEmpty()) {
                    scrollable[0].performTouchInput { swipeUp() }
                    rule.waitForIdle()
                }
            }
            if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        }
        return this
    }
}

class InsightsRobot(private val rule: ComposeTestRule) {

    fun readOverallHealthScore(): Int {
        val text = dumpScreenText(rule)
        Regex("""\b(\d{1,3})\s*/\s*100\b""").find(text)?.let {
            return it.groupValues[1].toInt()
        }
        Regex("""Environment Health:\s*(\d{1,3})\s*out of\s*100""", RegexOption.IGNORE_CASE).find(text)?.let {
            return it.groupValues[1].toInt()
        }
        throw AssertionError("Could not parse 0..100 Health Score from Insights UI:\n$text")
    }

    fun toggleRedactMacs(): InsightsRobot {
        scrollUntilVisible("Redact", "Mask MAC")
        clickFirstMatching(rule, isToggleWithAnyPhrase("Redact MAC", "Mask MAC", "Redact BSSID", "Redact"))
        return this
    }

    fun selectExportFormat(formatKeyword: String): InsightsRobot {
        scrollUntilVisible(formatKeyword)
        runCatching {
            clickFirstMatching(rule, isToggleWithAnyPhrase(formatKeyword))
        }
        return this
    }

    fun clickShareExport(formatKeyword: String? = null): InsightsRobot {
        if (formatKeyword != null) {
            scrollUntilVisible(formatKeyword, "Share", "Export")
            val combinedClicked = runCatching {
                clickFirstMatching(rule, isClickableWithAnyPhrase("Share $formatKeyword", "Export $formatKeyword"))
                true
            }.getOrDefault(false)
            if (combinedClicked) return this
            selectExportFormat(formatKeyword)
        }
        scrollUntilVisible("Share", "Export")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Share", "Export Report", "Export"))
        return this
    }

    fun clickCopyExport(formatKeyword: String? = null): InsightsRobot {
        if (formatKeyword != null) {
            selectExportFormat(formatKeyword)
        }
        scrollUntilVisible("Copy")
        clickFirstMatching(rule, isClickableWithAnyPhrase("Copy Report", "Copy JSON", "Copy Markdown", "Copy"))
        return this
    }

    fun selectHistoryWindow(windowLabel: String): InsightsRobot {
        scrollUntilVisible(windowLabel)
        clickFirstMatching(rule, isToggleWithAnyPhrase(windowLabel))
        return this
    }

    fun assertAccessibleHealthRingPresent(): InsightsRobot {
        val nodes = rule.onAllNodes(
            SemanticsMatcher("Health Score Ring Canvas with telemetry semantics") { node ->
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                val sd = node.config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                cd.contains("Health", ignoreCase = true) ||
                    cd.contains("100", ignoreCase = true) ||
                    sd.contains("100", ignoreCase = true) ||
                    sd.contains("Waiting", ignoreCase = true)
            },
            useUnmergedTree = true,
        )
        assertTrue(
            "Expected Insights Health Score Ring Canvas to expose accessible semantics",
            nodes.fetchSemanticsNodes().isNotEmpty(),
        )
        return this
    }

    fun scrollUntilVisible(vararg phrases: String): InsightsRobot {
        if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        repeat(5) {
            runCatching {
                val scrollable = rule.onAllNodes(
                    SemanticsMatcher("scrollable") { it.config.contains(SemanticsActions.ScrollBy) },
                )
                if (scrollable.fetchSemanticsNodes().isNotEmpty()) {
                    scrollable[0].performTouchInput { swipeUp() }
                    rule.waitForIdle()
                }
            }
            if (ComposeSemanticMatchers.screenContainsAny(rule, *phrases)) return this
        }
        return this
    }
}

object LegendPillRobot {
    fun tapLegendPill(rule: ComposeTestRule, topicId: String, pillLabelFallback: String) {
        val byTag = runCatching {
            rule.onNodeWithTag("legend_pill_$topicId").performScrollTo().performClick()
            rule.waitForIdle()
            true
        }.getOrDefault(false)
        if (!byTag) {
            clickFirstMatching(rule, isClickableWithAnyPhrase("$pillLabelFallback ?", pillLabelFallback))
        }
    }

    fun assertLegendCardVisible(rule: ComposeTestRule, topicId: String, expectedPhrase: String) {
        val byTag = runCatching {
            rule.onNodeWithTag("legend_card_$topicId").assertIsDisplayed()
            true
        }.getOrDefault(false)
        if (!byTag) {
            assertScreenContainsAny(rule, "Legend($topicId)", expectedPhrase)
        } else {
            assertScreenContainsAny(rule, "Legend($topicId)", expectedPhrase)
        }
    }

    fun dismissLegendCard(rule: ComposeTestRule, topicId: String) {
        val byTag = runCatching {
            rule.onNodeWithTag("legend_dismiss_$topicId").performScrollTo().performClick()
            rule.waitForIdle()
            true
        }.getOrDefault(false)
        if (!byTag) {
            clickFirstMatching(rule, isClickableWithAnyPhrase("Got it", "Dismiss"))
        }
    }
}
