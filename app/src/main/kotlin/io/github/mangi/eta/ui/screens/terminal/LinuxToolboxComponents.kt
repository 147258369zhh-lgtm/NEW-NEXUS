package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexusSettingsCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Each capability keeps its explanation and actions together, without a cramped trailing button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinuxToolboxCard(
    title: String,
    summary: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    status: String? = null,
    busy: Boolean = false,
    tone: EtaIconTone = EtaIconTone.TOOL,
    actions: @Composable () -> Unit = {},
) {
    NexusSettingsCard(modifier.padding(horizontal = 16.dp, vertical = 6.dp), insideMargin = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(22.dp))
                Text(title, style = MiuixTheme.textStyles.headline2, modifier = Modifier.weight(1f))
                if (busy) InfiniteProgressIndicator(size = 18.dp)
            }
            Text(summary, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            status?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

/** Capability rows keep their current state beside compact trailing installation actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinuxToolboxCapabilityCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    status: String? = null,
    busy: Boolean = false,
    tone: EtaIconTone = EtaIconTone.TOOL,
    actions: @Composable () -> Unit = {},
) {
    NexusSettingsCard(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val actionWidth = if (maxWidth < 280.dp) 64.dp else 112.dp
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MiuixTheme.textStyles.headline2, modifier = Modifier.weight(1f))
                        if (busy) InfiniteProgressIndicator(size = 18.dp)
                    }
                    status?.let {
                        Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    }
                }
                FlowRow(
                    Modifier.width(actionWidth),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { actions() }
            }
        }
    }
}

@Composable
internal fun LinuxToolboxQuickActions(terminal: String, files: String, enabled: Boolean, onTerminal: () -> Unit, onFiles: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NexaCompactAction(terminal, onTerminal, Modifier.weight(1f), enabled = enabled)
        NexaCompactAction(files, onFiles, Modifier.weight(1f), enabled = enabled)
    }
}
