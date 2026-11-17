package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Entry-local styling: a retained chat entry must never inherit settings colors during back. */
internal val LocalNexusSettingsVisuals = staticCompositionLocalOf { false }
private val LocalSettingsCard = staticCompositionLocalOf { false }

@Composable
internal fun NexusSettingsTheme(content: @Composable () -> Unit) {
    val base = MiuixTheme.colorScheme
    val text = MiuixTheme.textStyles
    val light = base.background.luminance() > 0.5f
    val colors = if (!light) base else base.copy(
            background = Color(0xFFF4F5F7),
            surface = Color(0xFFF4F5F7),
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFFAFAFC),
            surfaceContainerHighest = Color(0xFFF0F1F4),
            onBackground = Color(0xFF1C1C1E),
            onBackgroundVariant = Color(0xFF8E8E93),
            onSurface = Color(0xFF1C1C1E),
            onSurfaceContainer = Color(0xFF1C1C1E),
            onSurfaceVariantSummary = Color(0xFF8E8E93),
            onSurfaceVariantActions = Color(0xFF8E8E93),
            secondaryVariant = Color.White,
            outline = Color(0xFFE5E5EA),
        )
    val styles = text.copy(
            main = text.main.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            title1 = text.title1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            title2 = text.title2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            headline1 = text.headline1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            body2 = text.body2.copy(fontSize = 13.sp, lineHeight = 19.sp),
            subtitle = text.subtitle.copy(fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        )
    CompositionLocalProvider(LocalNexusSettingsVisuals provides true) {
        MiuixTheme(colors = colors, textStyles = styles, content = content)
    }
}

@Composable
internal fun settingsCardColor(): Color = if (MiuixTheme.colorScheme.background.luminance() > 0.5f) {
    Color.White
} else MiuixTheme.colorScheme.surfaceContainer

@Composable
internal fun Modifier.nexusSettingsCardSurface(): Modifier {
    val shape = smoothShape(16.dp)
    val light = MiuixTheme.colorScheme.background.luminance() > 0.5f
    return shadow(4.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = if (light) 0.03f else 0.12f),
        spotColor = Color.Black.copy(alpha = if (light) 0.03f else 0.12f))
        .clip(shape)
}

/** Row position is read only by drawing, so dividers do not add a composition per frame. */
@Composable
private fun Modifier.settingsRowDivider(hasIcon: Boolean): Modifier {
    if (!LocalNexusSettingsVisuals.current || !LocalSettingsCard.current) return this
    val hasPreviousRow = remember { mutableStateOf(false) }
    val lineColor = MiuixTheme.colorScheme.outline
    return onPlaced { hasPreviousRow.value = it.positionInParent().y > 0.5f }
        .drawBehind {
            if (hasPreviousRow.value) drawLine(
                color = lineColor,
                start = Offset((if (hasIcon) 48.dp else 16.dp).toPx(), 0f),
                end = Offset((size.width - 16.dp.toPx()).coerceAtLeast(0f), 0f),
                strokeWidth = 0.5.dp.toPx(),
            )
        }
}

@Composable
internal fun NexusSettingsCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val settings = LocalNexusSettingsVisuals.current
    val cardModifier = if (settings) modifier.nexusSettingsCardSurface() else modifier
    val cardColors = if (settings) colors.copy(color = settingsCardColor()) else colors
    CompositionLocalProvider(LocalSettingsCard provides settings) {
        Card(cardModifier, if (settings) 16.dp else cornerRadius, insideMargin, cardColors, content)
    }
}

@Composable
internal fun NexusSettingsCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val settings = LocalNexusSettingsVisuals.current
    val cardModifier = if (settings) modifier.nexusSettingsCardSurface() else modifier
    val cardColors = if (settings) colors.copy(color = settingsCardColor()) else colors
    CompositionLocalProvider(LocalSettingsCard provides settings) {
        Card(modifier = cardModifier, cornerRadius = if (settings) 16.dp else cornerRadius,
            insideMargin = insideMargin, colors = cardColors, pressFeedbackType = pressFeedbackType,
            showIndication = showIndication, holdDownState = holdDownState,
            onClick = onClick, onLongPress = onLongPress, content = content)
    }
}

@Composable
internal fun NexusSettingsSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onBackgroundVariant,
    insideMargin: PaddingValues = SmallTitleDefaults.InsideMargin,
) {
    SmallTitle(text, modifier, textColor,
        if (LocalNexusSettingsVisuals.current) PaddingValues(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp)
        else insideMargin)
}

@Composable
internal fun NexusSettingsArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    ArrowPreference(title = title, modifier = modifier.settingsRowDivider(startAction != null),
        titleColor = titleColor, summary = summary, summaryColor = summaryColor,
        startAction = startAction, endActions = endActions, bottomAction = bottomAction,
        insideMargin = insideMargin, onClick = onClick, holdDownState = holdDownState, enabled = enabled)
}

/** Shared surfaces also host callers which do not use a Card composable. */
@Composable
internal fun NexusSettingsCardContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsCard provides LocalNexusSettingsVisuals.current, content = content)
}

@Composable
internal fun NexusSettingsSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    switchColors: SwitchColors = SwitchDefaults.switchColors(),
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    SwitchPreference(checked = checked, onCheckedChange = onCheckedChange, title = title,
        modifier = modifier.settingsRowDivider(startAction != null), titleColor = titleColor,
        summary = summary, summaryColor = summaryColor, startAction = startAction,
        endActions = endActions, bottomAction = bottomAction, switchColors = switchColors,
        insideMargin = insideMargin, holdDownState = holdDownState, enabled = enabled)
}

@Composable
internal fun NexusSettingsDropdownPreference(
    items: List<String>,
    selectedIndex: Int,
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    startAction: @Composable (() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    maxHeight: Dp? = null,
    enabled: Boolean = true,
    showValue: Boolean = true,
    renderInRootScaffold: Boolean = true,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onSelectedIndexChange: ((Int) -> Unit)? = null,
) {
    OverlayDropdownPreference(items = items, selectedIndex = selectedIndex, title = title,
        modifier = modifier.settingsRowDivider(startAction != null), titleColor = titleColor,
        summary = summary, summaryColor = summaryColor, dropdownColors = dropdownColors,
        startAction = startAction, bottomAction = bottomAction, insideMargin = insideMargin,
        maxHeight = maxHeight, enabled = enabled, showValue = showValue,
        renderInRootScaffold = renderInRootScaffold, onExpandedChange = onExpandedChange,
        onSelectedIndexChange = onSelectedIndexChange)
}
