package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Settings category navigation stays above the page's scrolling content. */
@Composable
internal fun NexusSettingsTabs(
    tabs: List<String>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold)
    val requiredItemWidth = remember(tabs, labelStyle, density, textMeasurer) {
        val widestLabel = tabs.maxOf { textMeasurer.measure(it, labelStyle, maxLines = 1).size.width }
        with(density) { widestLabel.toDp() } + 32.dp
    }
    // Restored tabs must start at their saved position. Animating from item zero on first
    // composition made a remembered page visibly settle sideways every time it reopened.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedTab.coerceAtLeast(0))
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            modifier = Modifier.widthIn(max = 920.dp).fillMaxWidth()
                .padding(horizontal = if (LocalNexusSettingsVisuals.current) 16.dp else EtaUiMetrics.pageHorizontal, vertical = 8.dp)
                .etaGlassSurface(cornerRadius = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainer),
        ) {
            val equalWidth = (maxWidth - 8.dp - 4.dp * (tabs.size - 1)) / tabs.size
            if (tabs.size <= 4 && requiredItemWidth <= equalWidth) {
                Row(
                    Modifier.fillMaxWidth().padding(4.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tabs.forEachIndexed { index, label ->
                        SettingsTabItem(label, index == selectedTab, { onTabSelected(index) }, Modifier.weight(1f))
                    }
                }
            } else {
                LaunchedEffect(selectedTab, tabs.size) {
                    if (selectedTab in tabs.indices) state.animateScrollToItem(selectedTab)
                }
                LazyRow(
                    state = state,
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(tabs, key = { index, _ -> index }) { index, label ->
                        SettingsTabItem(label, index == selectedTab, { onTabSelected(index) },
                            Modifier.width(requiredItemWidth.coerceIn(88.dp, 240.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTabItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(durationMillis = 140),
        label = "settings-tab-container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurfaceVariantSummary,
        animationSpec = tween(durationMillis = 140),
        label = "settings-tab-content",
    )
    Box(
        modifier.heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = content,
        )
    }
}

@Composable
internal fun NexusSettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val settings = LocalNexusSettingsVisuals.current
    NexusSettingsSurface(modifier = modifier.fillMaxWidth().padding(horizontal = if (settings) 16.dp else EtaUiMetrics.pageHorizontal)
        .padding(bottom = if (settings) 8.dp else EtaUiMetrics.groupGap), content = content)
}

@Composable
internal fun NexusSettingsSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalNexusSettingsVisuals.current) {
        Column(modifier.fillMaxWidth().nexusSettingsCardSurface().background(settingsCardColor())) {
            NexusSettingsCardContent(content)
        }
        return
    }
    val glass = LocalEtaDesignTokens.current.skin.isGlass
    val radius = if (glass) LocalEtaDesignTokens.current.cornerMedium else 18.dp
    val shape = RoundedCornerShape(radius)
    val colors = MiuixTheme.colorScheme
    val surfaceFill = if (glass) {
        // Keep the glass edge and depth, but give settings controls a clean white body
        // so the page atmosphere cannot make every action look like the background.
        colors.secondaryVariant.copy(alpha = 0.82f)
    } else {
        colors.surfaceContainer
    }
    Column(
        modifier = modifier.fillMaxWidth().etaGlassSurface(cornerRadius = radius)
            .border(0.5.dp, colors.outline.copy(alpha = if (glass) 0.24f else 0.18f), shape)
            .clip(shape)
            .background(surfaceFill),
    ) {
        content()
    }
}

@Composable
internal fun NexusSettingsSectionTitle(title: String) {
    if (LocalNexusSettingsVisuals.current) NexusSettingsSmallTitle(title)
    else SmallTitle(title, modifier = Modifier.padding(horizontal = 4.dp))
}
