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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.icu.text.BreakIterator
import java.util.Locale

internal enum class AgentIdentityPalette { Default, MeetingVivid }

private val DefaultAgentIdentityColors = listOf(
    Color(0xFF287FE8), Color(0xFF19A87F), Color(0xFFEB8543),
    Color(0xFFD64D80), Color(0xFF6577E3), Color(0xFF10A5B9),
)

private val MeetingAgentIdentityColors = listOf(
    Color(0xFF3998FF) to Color(0xFF1D72E0),
    Color(0xFF24C99A) to Color(0xFF0AA47B),
    Color(0xFFFFA15B) to Color(0xFFF47445),
    Color(0xFFF76CA8) to Color(0xFFE44386),
    Color(0xFFB188FF) to Color(0xFF8F58E8),
    Color(0xFF31C8E7) to Color(0xFF109FCA),
)

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
    singleInitial: Boolean = false,
    palette: AgentIdentityPalette = AgentIdentityPalette.Default,
) {
    val colors = MiuixTheme.colorScheme
    val identity = seed.hashCode().toUInt().toLong()
    val (start, end) = when (palette) {
        AgentIdentityPalette.Default -> {
            val colors = DefaultAgentIdentityColors
            colors[(identity % colors.size).toInt()] to colors[((identity / colors.size + 2L) % colors.size).toInt()]
        }
        AgentIdentityPalette.MeetingVivid -> MeetingAgentIdentityColors[(identity % MeetingAgentIdentityColors.size).toInt()]
    }
    Box(
        modifier = modifier.size(size + 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size + if (selected) 4.dp else 2.dp)
                .background(
                    if (selected) start.copy(alpha = .24f) else Color.Transparent,
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size)
                .background(
                    Brush.linearGradient(
                        listOf(start, if (palette == AgentIdentityPalette.Default) end.copy(alpha = .88f) else end),
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
                val density = LocalDensity.current
                val style = if (singleInitial) MiuixTheme.textStyles.footnote1.copy(
                    fontSize = with(density) { (size * 0.48f).toSp() },
                    lineHeight = with(density) { (size * 0.65f).toSp() },
                ) else MiuixTheme.textStyles.footnote1
                Text(
                    text = remember(displayName, singleInitial) { avatarInitials(displayName, singleInitial) },
                    style = style,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.semantics { contentDescription = displayName },
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

private fun avatarInitials(displayName: String, singleInitial: Boolean = false): String {
    fun takeGraphemes(text: String, count: Int): String {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        var end = iterator.first()
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return text.substring(0, end)
            end = next
        }
        return text.substring(0, end)
    }
    val words = displayName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (singleInitial) return words.firstOrNull()?.let { takeGraphemes(takeGraphemes(it, 1).uppercase(), 1) }.orEmpty().ifBlank { "A" }
    val value = when {
        words.size >= 2 -> words.take(2).joinToString("") { takeGraphemes(it, 1) }
        words.singleOrNull()?.all(Char::isDigit) == true -> "AI"
        else -> words.firstOrNull()?.let { takeGraphemes(it, 2) }.orEmpty()
    }.uppercase()
    return value.ifBlank { "AI" }
}
