package com.a55.wifiradar.e2e.robots

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue

/**
 * Pure user-visible Compose semantic matchers, WCAG 2.1 AAA contrast pixel auditors, and
 * zero-raw-enum-leak screen auditors (`F02`, `F03`, `F04`).
 *
 * Imports ZERO `com.a55.wifiradar.(core|feature|cli|MainActivity)` classes.
 */
object ComposeSemanticMatchers {

    private val FORBIDDEN_RAW_ENUM_REGEX = Regex(
        """\b(WIFI_STANDARD_[A-Z0-9_]+|SECURITY_TYPE_[A-Z0-9_]+|CHANNEL_WIDTH_[A-Z0-9_]+|""" +
            """WIFI_BAND_[A-Z0-9_]+|SCAN_RESULTS_[A-Z0-9_]+|BAND_[0-9]_[0-9]_GHZ|BAND_[0-9]+_GHZ|""" +
            """WIDTH_[0-9]+(_PLUS_[0-9]+)?_MHZ|A_PLUS|A_MINUS|DEAD_ZONE|MOVING_AWAY|HOME_OFFICE|""" +
            """HEAVY_WALLS|OPEN_OR_WEAK_SECURITY|ALL_RADIOS_BSSID|GROUPED_MESH)\b""",
    )

    private val FORBIDDEN_NUMERIC_CORRUPTION_REGEX = Regex(
        """(?<![A-Za-z])(NaN|-?Infinity)(?![A-Za-z])""",
    )

    fun extractAllStrings(node: SemanticsNode): List<String> = buildList {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
        node.config.getOrNull(SemanticsProperties.StateDescription)?.let { add(it) }
    }

    fun hasAnyPhrase(vararg phrases: String, ignoreCase: Boolean = true): SemanticsMatcher {
        val desc = "contains any of [${phrases.joinToString(", ")}]"
        return SemanticsMatcher(desc) { node ->
            val allText = extractAllStrings(node).joinToString(" | ")
            phrases.any { phrase -> allText.contains(phrase, ignoreCase = ignoreCase) }
        }
    }

    fun hasAllPhrases(vararg phrases: String, ignoreCase: Boolean = true): SemanticsMatcher {
        val desc = "contains all of [${phrases.joinToString(", ")}]"
        return SemanticsMatcher(desc) { node ->
            val allText = extractAllStrings(node).joinToString(" | ")
            phrases.all { phrase -> allText.contains(phrase, ignoreCase = ignoreCase) }
        }
    }

    fun matchesRegex(regex: Regex): SemanticsMatcher =
        SemanticsMatcher("matches /${regex.pattern}/") { node ->
            extractAllStrings(node).any { regex.containsMatchIn(it) }
        }

    fun isBottomTab(tabTitle: String): SemanticsMatcher =
        SemanticsMatcher("bottom navigation tab '$tabTitle'") { node ->
            val role = node.config.getOrNull(SemanticsProperties.Role)
            val isClickableOrSelectable = node.config.contains(SemanticsActions.OnClick) ||
                node.config.contains(SemanticsProperties.Selected)
            val texts = extractAllStrings(node)
            isClickableOrSelectable &&
                (role == Role.Tab || role == null || role == Role.Button) &&
                texts.any { it.equals(tabTitle, ignoreCase = true) }
        }

    fun isClickableWithAnyPhrase(vararg phrases: String): SemanticsMatcher =
        SemanticsMatcher("clickable containing [${phrases.joinToString()}]") { node ->
            val clickable = node.config.contains(SemanticsActions.OnClick)
            val combined = extractAllStrings(node).joinToString(" | ")
            clickable && phrases.any { combined.contains(it, ignoreCase = true) }
        }

    fun isToggleWithAnyPhrase(vararg phrases: String): SemanticsMatcher =
        SemanticsMatcher("toggle/chip containing [${phrases.joinToString()}]") { node ->
            val interactive = node.config.contains(SemanticsActions.OnClick) ||
                node.config.contains(SemanticsProperties.ToggleableState) ||
                node.config.contains(SemanticsProperties.Selected)
            val combined = extractAllStrings(node).joinToString(" | ")
            interactive && phrases.any { combined.contains(it, ignoreCase = true) }
        }

    fun hasChannelLabel(channelNumber: Int): SemanticsMatcher {
        val pattern = Regex("""\b(Ch|Channel)\s*$channelNumber\b""", RegexOption.IGNORE_CASE)
        return matchesRegex(pattern)
    }

    fun collectAllScreenText(rule: ComposeTestRule): List<String> {
        val out = mutableListOf<String>()
        fun visit(n: SemanticsNode) {
            out.addAll(extractAllStrings(n))
            n.children.forEach(::visit)
        }
        for (treeFlag in listOf(false, true)) {
            runCatching { visit(rule.onRoot(useUnmergedTree = treeFlag).fetchSemanticsNode()) }
        }
        return out.distinct()
    }

    fun dumpScreenText(rule: ComposeTestRule): String =
        collectAllScreenText(rule).joinToString("\n")

    fun screenContainsAny(rule: ComposeTestRule, vararg phrases: String): Boolean {
        val dump = dumpScreenText(rule)
        return phrases.any { dump.contains(it, ignoreCase = true) }
    }

    fun assertScreenContainsAny(
        rule: ComposeTestRule,
        contextLabel: String,
        vararg phrases: String,
    ) {
        val dump = dumpScreenText(rule)
        assertTrue(
            "[$contextLabel] Expected screen to contain at least one of ${phrases.toList()}, " +
                "but visible semantic text was:\n${dump.take(2500)}",
            phrases.any { dump.contains(it, ignoreCase = true) },
        )
    }

    /**
     * Global Plain-English & Numerical Sanity Gate (`F03`, `F33`, `F34`):
     * Asserts ZERO node on screen leaks raw Android/Kotlin enum constants or `NaN` / `Infinity`.
     */
    fun assertZeroRawEnumOrNanLeaks(rule: ComposeTestRule, screenName: String) {
        val allStrings = collectAllScreenText(rule)
        val enumLeaks = allStrings.filter { FORBIDDEN_RAW_ENUM_REGEX.containsMatchIn(it) }
        val nanLeaks = allStrings.filter { FORBIDDEN_NUMERIC_CORRUPTION_REGEX.containsMatchIn(it) }
        assertTrue(
            "[$screenName] Leaked raw internal enum names to user UI: $enumLeaks",
            enumLeaks.isEmpty(),
        )
        assertTrue(
            "[$screenName] Leaked NaN/Infinity numerical corruption to user UI: $nanLeaks",
            nanLeaks.isEmpty(),
        )
    }

    fun clickFirstMatching(rule: ComposeTestRule, matcher: SemanticsMatcher): SemanticsNodeInteraction {
        for (unmerged in listOf(false, true)) {
            val nodes = rule.onAllNodes(matcher, useUnmergedTree = unmerged)
            val count = runCatching { nodes.fetchSemanticsNodes().size }.getOrDefault(0)
            if (count > 0) {
                val target = nodes[0]
                runCatching { target.performScrollTo() }
                target.performClick()
                rule.waitForIdle()
                return target
            }
        }
        throw AssertionError(
            "Could not find clickable node matching [${matcher.description}]. Visible screen text:\n" +
                dumpScreenText(rule).take(2000),
        )
    }

    // --- WCAG 2.1 Relative Luminance & Contrast Math (`F02`) ---
    fun relativeLuminance(argb: Int): Double {
        fun channelLinear(c8: Int): Double {
            val s = (c8 and 0xFF) / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channelLinear(AndroidColor.red(argb))
        val g = channelLinear(AndroidColor.green(argb))
        val b = channelLinear(AndroidColor.blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(argbA: Int, argbB: Int): Double {
        val l1 = relativeLuminance(argbA)
        val l2 = relativeLuminance(argbB)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    /**
     * Measures the peak foreground-vs-background WCAG contrast ratio inside a bounding region
     * of a rendered Skia bitmap.
     */
    fun peakContrastInRegion(
        bitmap: Bitmap,
        leftPx: Int = 0,
        topPx: Int = 0,
        rightPx: Int = bitmap.width,
        bottomPx: Int = bitmap.height,
        stepPx: Int = 4,
    ): Double {
        var minLum = 1.0
        var maxLum = 0.0
        val x0 = leftPx.coerceIn(0, bitmap.width - 1)
        val y0 = topPx.coerceIn(0, bitmap.height - 1)
        val x1 = rightPx.coerceIn(x0 + 1, bitmap.width)
        val y1 = bottomPx.coerceIn(y0 + 1, bitmap.height)
        for (y in y0 until y1 step stepPx) {
            for (x in x0 until x1 step stepPx) {
                val lum = relativeLuminance(bitmap.getPixel(x, y))
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }
        return (maxLum + 0.05) / (minLum + 0.05)
    }
}
