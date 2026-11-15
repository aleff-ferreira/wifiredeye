package com.a55.wifiradar.core.designsystem.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a55.wifiradar.core.designsystem.component.RadarTestTags
import com.a55.wifiradar.core.designsystem.icon.RadarIcons
import com.a55.wifiradar.core.designsystem.theme.RadarFontFeatures
import com.a55.wifiradar.core.designsystem.theme.RadarTheme
import com.a55.wifiradar.core.model.RadarDestination

typealias RadarTab = RadarDestination

fun RadarDestination.icon(): ImageVector = when (this) {
    RadarDestination.NETWORKS -> RadarIcons.Networks
    RadarDestination.PROXIMITY -> RadarIcons.Proximity
    RadarDestination.SPECTRUM -> RadarIcons.Spectrum
    RadarDestination.INSIGHTS -> RadarIcons.Insights
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadarScaffold(
    selectedTab: RadarDestination,
    onSelectTab: (RadarDestination) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "WIFIREDEYE",
    topStatusSlot: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = RadarTheme.colors
    val dimens = RadarTheme.dimens

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.background,
        contentColor = colors.textPrimary,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        )
                    )
                    .padding(
                        start = dimens.screenHorizontalPadding,
                        end = dimens.screenHorizontalPadding,
                        top = dimens.topBarExtraCutoutClearance,
                        bottom = 8.dp,
                    )
                    .testTag(RadarTestTags.TOP_BAR),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = RadarTheme.typography.metricLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = colors.textPrimary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    if (topStatusSlot != null) {
                        Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                            topStatusSlot()
                        }
                    }
                }
            }
        },
        bottomBar = {
            RadarBottomNavigationBar(
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
            )
        },
        content = content,
    )
}

@Composable
fun RadarBottomNavigationBar(
    selectedTab: RadarDestination,
    onSelectTab: (RadarDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RadarTheme.colors
    val density = LocalDensity.current
    // Cap bottom-bar rail label growth at 1.25x so all 4 canonical labels ("Networks", "Proximity",
    // "Spectrum", "Insights") stay 100% un-ellipsized and non-overlapping on 360dp even at fontScale = 2.0f.
    val tabLabelFontSize = with(density) {
        val effectiveSp = 11.5f * fontScale.coerceAtMost(1.25f)
        (effectiveSp / fontScale).sp
    }
    val tabLabelLineHeight = with(density) {
        val effectiveSp = 15.0f * fontScale.coerceAtMost(1.25f)
        (effectiveSp / fontScale).sp
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RadarTestTags.BOTTOM_NAV_BAR),
        color = colors.surfaceL1,
        contentColor = colors.textPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                    )
                ),
        ) {
            HorizontalDivider(thickness = 1.dp, color = colors.border)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadarDestination.entries.forEach { destination ->
                    val selected = destination == selectedTab
                    val fgColor = if (selected) colors.cyan else colors.textMuted
                    val indicatorBg = if (selected) colors.toneSpec(
                        com.a55.wifiradar.core.designsystem.theme.SemanticTone.GOOD
                    ).container else Color.Transparent

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(
                                selected = selected,
                                onClick = { onSelectTab(destination) },
                                role = Role.Tab,
                            )
                            .padding(horizontal = 2.dp, vertical = 4.dp)
                            .testTag(RadarTestTags.bottomNavTab(destination.route)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(indicatorBg)
                                .padding(horizontal = 14.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = destination.icon(),
                                contentDescription = null,
                                tint = fgColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = destination.tabTitle,
                            color = if (selected) colors.textPrimary else colors.textMuted,
                            fontSize = tabLabelFontSize,
                            lineHeight = tabLabelLineHeight,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            style = RadarTheme.typography.badgeLabel.copy(
                                fontFeatureSettings = RadarFontFeatures.TABULAR_NUMERALS
                            ),
                        )
                    }
                }
            }
        }
    }
}
