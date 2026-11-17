package io.github.mangi.eta.ui.screens.capability

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.ui.components.EtaChoiceChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalDshFeeds = staticCompositionLocalOf<DshConversationFeeds?> { null }
internal data class DshInteractionUi(val notice: String? = null, val running: Boolean = false, val stop: () -> Unit = {})
internal val LocalDshInteraction = staticCompositionLocalOf { DshInteractionUi() }

private fun dshIcon(name: String): ImageVector = when (name) {
    "chart", "coin" -> Icons.Rounded.QueryStats
    "translate" -> Icons.Rounded.Translate
    "speaker" -> Icons.Rounded.VolumeUp
    "clock" -> Icons.Rounded.Schedule
    "file", "document" -> Icons.Rounded.Description
    "check", "success" -> Icons.Rounded.CheckCircleOutline
    "warning" -> Icons.Rounded.WarningAmber
    else -> Icons.Rounded.Extension
}

@Composable
private fun nativeTone(tone: String): Color = when (tone) {
    "good" -> Color(0xFF209665)
    "warn" -> Color(0xFFAA7900)
    "bad" -> MiuixTheme.colorScheme.error
    "info" -> MiuixTheme.colorScheme.primary
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
internal fun DshComposerPlugins(generating: Boolean) {
    val feeds = LocalDshFeeds.current ?: return
    val actions = LocalDshConversationActions.current ?: return
    val content by remember(feeds) { feeds.snapshot.map { it.actions to it.strips }.distinctUntilChanged() }.collectAsState(emptyList<DshNativeAction>() to emptyList())
    val interaction = LocalDshInteraction.current
    if (content.first.isNotEmpty() || content.second.isNotEmpty() || interaction.notice != null || interaction.running) Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (content.first.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content.first.forEach { item -> key(item.target.host.id, item.target.pkg, item.target.id) {
                TooltipBox(text = item.title) { EtaChoiceChip(item.title, selected = false, enabled = item.ready && !interaction.running,
                    onClick = { actions.tap(item.target) }) }
            } }
        }
        if (!generating && content.second.isNotEmpty()) {
            var index by remember(content.second.map { it.target }) { mutableIntStateOf(0) }
            LaunchedEffect(content.second.map { it.target }) { while (content.second.size > 1) { delay(5000); index = (index + 1) % content.second.size } }
            val strip = content.second[index.coerceIn(0, content.second.lastIndex)]
            AnimatedContent(strip, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "dsh-status-strip") { row ->
                TooltipBox(text = row.author) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(enabled = row.tappable && !interaction.running) { actions.tap(row.target) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(dshIcon(row.icon), row.author, Modifier.size(16.dp), tint = nativeTone(row.tone))
                        Text(row.text, style = MiuixTheme.textStyles.footnote1, color = nativeTone(row.tone), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (row.tappable) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (interaction.running || interaction.notice != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (interaction.running) InfiniteProgressIndicator(Modifier.size(16.dp))
            Text(interaction.notice ?: "插件处理中", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
            if (interaction.running) IconButton(onClick = interaction.stop, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Stop, "停止插件操作", Modifier.size(16.dp)) }
        }
    }
}

@Composable
internal fun DshReplyNotes(messageId: String) {
    val feeds = LocalDshFeeds.current ?: return
    val key = DshHostRepository.localMessageKey(messageId)
    val rows by remember(feeds, key) { feeds.snapshot.map { it.notes[key].orEmpty() }.distinctUntilChanged() }.collectAsState(emptyList())
    if (rows.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row -> TooltipBox(text = row.author) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Extension, row.author, Modifier.size(12.dp), tint = nativeTone(row.tone))
                Text(row.text, style = MiuixTheme.textStyles.footnote1, color = nativeTone(row.tone))
            }
        } }
    }
}
