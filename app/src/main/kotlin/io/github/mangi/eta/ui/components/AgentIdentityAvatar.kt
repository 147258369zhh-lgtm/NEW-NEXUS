package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Deterministic visual identity shared by meeting participants and Subagent settings. */
@Composable
internal fun AgentIdentityAvatar(
    displayName: String,
    seed: String = displayName,
    size: Dp = 40.dp,
    icon: ImageVector? = Icons.Rounded.AutoAwesome,
    statusColor: Color? = null,
    statusDescription: String? = null,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val palette = listOf(
        Color(0xFF5B8DEF), Color(0xFF8B6CF6), Color(0xFF19A974),
        Color(0xFFE58B2A), Color(0xFFE45C86), Color(0xFF00A6A6),
    )
    val start = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    val end = palette[((seed.hashCode().toLong() * 31L and Long.MAX_VALUE) % palette.size).toInt()]
    Box(
        modifier = modifier.size(size + 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size + if (selected) 4.dp else 2.dp)
                .background(
                    Brush.linearGradient(listOf(start.copy(alpha = .18f), end.copy(alpha = .06f))),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size)
                .background(
                    Brush.linearGradient(
                        listOf(start.copy(alpha = .92f), end.copy(alpha = .76f)),
                    ),
                    CircleShape,
                )
                .border(
                    width = if (selected) 1.4.dp else .8.dp,
                    color = Color.White.copy(alpha = if (selected) .78f else .45f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = displayName,
                    tint = Color.White.copy(alpha = .96f),
                    modifier = Modifier.size(size * .46f),
                )
            } else {
                Text(
                    text = avatarInitials(displayName),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }
        if (statusColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-1).dp, y = (-1).dp)
                    .size(12.dp)
                    .background(colors.surface, CircleShape)
                    .border(1.dp, colors.surface, CircleShape)
                    .background(statusColor, CircleShape),
            )
        }
    }
}

private fun avatarInitials(displayName: String): String {
    val words = displayName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val value = when {
        words.size >= 2 -> words.take(2).joinToString("") { it.take(1) }
        words.singleOrNull()?.all(Char::isDigit) == true -> "AI"
        else -> words.firstOrNull()?.take(2).orEmpty()
    }.uppercase()
    return value.ifBlank { "AI" }
}
