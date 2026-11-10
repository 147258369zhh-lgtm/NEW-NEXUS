package io.github.mangi.eta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 可选中的胶囊标签：用于筛选与少量互斥/多选项。
 * 未选中只有细描边、没有底色，选中才填主题色浅底，选中项在视觉上永远比未选中项更重。
 */
@Composable
internal fun EtaChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.97f else 1f,
        animationSpec = EtaMotion.pressSpring(),
        label = "choice_chip_press",
    )
    val container by animateColorAsState(
        targetValue = if (selected) {
            MiuixTheme.colorScheme.tertiaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = EtaMotion.state),
        label = "choice_chip_container",
    )
    val content by animateColorAsState(
        targetValue = when {
            !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
            selected -> MiuixTheme.colorScheme.onTertiaryContainer
            else -> MiuixTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = EtaMotion.state),
        label = "choice_chip_content",
    )
    val border by animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.74f),
        animationSpec = tween(durationMillis = EtaMotion.state),
        label = "choice_chip_border",
    )
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
        fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
        color = content,
        maxLines = 1,
        modifier = modifier
            .shadow(
                elevation = if (selected && enabled) 3.dp else 0.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ambientColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.14f),
                spotColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.14f),
            )
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { this.selected = selected }
            .squircleBorder(width = 1.dp, color = border, cornerRadius = 16.dp)
            .squircleSurface(color = container, cornerRadius = 16.dp)
            .then(
                if (selected) Modifier.background(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)),
                    androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ) else Modifier,
            )
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = etaRippleIndication(),
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
