package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Scoped to statistics, file viewers and the browser; existing composer controls stay intact. */
@Composable
internal fun NexaCompactAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    selected: Boolean = false,
    horizontalPadding: Dp = 11.dp,
    role: Role = Role.Button,
) {
    val colors = MiuixTheme.colorScheme
    val light = colors.background.luminance() > 0.5f
    val glass = LocalEtaDesignTokens.current.skin.isGlass
    val shape = remember { smoothShape(13.dp) }
    val interaction = remember { MutableInteractionSource() }
    // A filled, continuous curve replaces the old white outline. The slight face lighting
    // is static: small actions never allocate a blur layer or an animated shader.
    val face = when {
        selected -> if (light) Color(0xFFE1EDFF) else Color(0xFF253D5B)
        light -> Color.White.copy(alpha = if (glass) 0.90f else 1f)
        else -> Color(0xFF34363F).copy(alpha = if (glass) 0.90f else 1f)
    }
    val lowerFace = when {
        selected -> if (light) Color(0xFFD8E7FC) else Color(0xFF23364D)
        light -> Color(0xFFF8F9FC).copy(alpha = if (glass) 0.86f else 1f)
        else -> Color(0xFF30323B).copy(alpha = if (glass) 0.86f else 1f)
    }
    val foreground = if (selected) {
        if (light) Color(0xFF175DB3) else Color(0xFF9BC6FF)
    } else colors.onSurface
    Row(
        modifier = modifier
            .widthIn(min = 44.dp)
            .heightIn(min = 40.dp)
            .etaPressScale(interaction, enabled, 0.97f, "compact:$text")
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(2.dp, shape, clip = false,
                ambientColor = Color.Black.copy(alpha = 0.025f),
                spotColor = Color.Black.copy(alpha = 0.035f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(face, lowerFace)))
            .semantics { this.selected = selected }
            .clickable(interactionSource = interaction, indication = etaRippleIndication(),
                enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = foreground)
        if (text.isNotEmpty()) Text(text, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NexaSegmentedTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val light = colors.background.luminance() > 0.5f
    val shape = remember { smoothShape(17.dp) }
    Row(modifier.fillMaxWidth().clip(shape)
        .background(if (light) Color(0xFFE9EDF3).copy(alpha = 0.86f) else Color(0xFF252730))
        .padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        tabs.forEachIndexed { index, title ->
            NexaCompactAction(title, onClick = { if (index != selectedTabIndex) onTabSelected(index) },
                modifier = Modifier.weight(1f), selected = index == selectedTabIndex,
                horizontalPadding = 6.dp, role = Role.Tab)
        }
    }
}
