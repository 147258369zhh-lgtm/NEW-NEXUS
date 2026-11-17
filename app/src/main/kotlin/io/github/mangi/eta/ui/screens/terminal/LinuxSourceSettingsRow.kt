package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Source
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxSourceSettings
import io.github.mangi.eta.agent.terminal.LinuxLatestVersionChecker
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.DialogChoicePreference
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.components.NexusSettingsCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun LinuxSourceSettingsRow(context: Context) {
    var entries by remember(context) { mutableStateOf(LinuxSourceSettings.entries(context)) }
    var show by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var distribution by remember { mutableIntStateOf(0) }
    var kind by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("") }
    var security by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableIntStateOf(0) }
    var preferenceRevision by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf(LinuxLatestVersionChecker(context).recorded()) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dist = if (distribution == 0) LinuxDistribution.ALPINE else LinuxDistribution.DEBIAN
    val kinds = LinuxSourceSettings.kindsFor(dist)
    val selectedKind = kinds[kind.coerceIn(kinds.indices)]
    NexusSettingsCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        DetailListRow(title = stringResource(R.string.linux_sources_title), icon = Icons.Rounded.Source, tone = EtaIconTone.DATA,
            value = if (entries.isEmpty()) stringResource(R.string.linux_sources_defaults) else stringResource(R.string.linux_sources_custom_count, entries.size), onClick = { show = true })
    }
    if (show) WindowDialog(show = true, title = stringResource(R.string.linux_sources_title), onDismissRequest = { show = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NexaSegmentedTabs(listOf(stringResource(R.string.linux_sources_priority_tab), stringResource(R.string.linux_sources_custom_tab), stringResource(R.string.linux_sources_latest_tab)), category, { category = it })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if(category != 2) {
                    DialogChoicePreference(title = stringResource(R.string.linux_sources_distribution), value = dist.displayName(), options = listOf("Alpine", "Debian"), selectedIndex = distribution,
                        onSelectedIndexChange = { distribution = it; kind = 0; error = null })
                    DialogChoicePreference(title = stringResource(R.string.linux_sources_purpose), value = context.getString(sourceLabel(selectedKind)), options = kinds.map { context.getString(sourceLabel(it)) }, selectedIndex = kind,
                        onSelectedIndexChange = { kind = it; error = null })
                }
                when(category) {
                    0 -> {
                        val sources = remember(dist, selectedKind, entries, preferenceRevision) { LinuxSourceSettings.available(context, dist, selectedKind) }
                        val selected = remember(dist, selectedKind, preferenceRevision) { LinuxSourceSettings.selectedId(context, dist, selectedKind) }
                        NexusSettingsCard {
                            Text(stringResource(R.string.linux_sources_transport_note), modifier = Modifier.padding(12.dp), style = MiuixTheme.textStyles.footnote1)
                            sources.forEach { entry ->
                                DetailListRow(title = when(entry.id) { "china" -> stringResource(R.string.linux_sources_china); "overseas" -> stringResource(R.string.linux_sources_overseas); else -> entry.name }, subtitle = entry.archiveUrl + (entry.securityUrl?.let { "\n$it" } ?: ""), icon = Icons.Rounded.Source,
                                    value = if(entry.id == selected) stringResource(R.string.linux_sources_preferred) else stringResource(R.string.linux_sources_prefer), onClick = { LinuxSourceSettings.select(context, dist, selectedKind, entry.id); preferenceRevision++ })
                                if(entry.id.startsWith("source-")) NexaCompactAction(stringResource(R.string.linux_sources_delete), onClick = { entries = LinuxSourceSettings.delete(context, entry.id); preferenceRevision++ }, modifier = Modifier.padding(8.dp))
                            }
                        }
                        if(selectedKind == LinuxSourceSettings.JDK) Text(stringResource(R.string.linux_sources_jdk_note), style = MiuixTheme.textStyles.footnote1)
                    }
                    1 -> {
                        NexusSettingsCard {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextField(value = name, onValueChange = { name = it.take(80) }, label = stringResource(R.string.linux_sources_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                                TextField(value = url, onValueChange = { url = it.take(2048) }, label = stringResource(R.string.linux_sources_url), singleLine = true, modifier = Modifier.fillMaxWidth())
                                if(selectedKind == LinuxSourceSettings.APT) TextField(value = security, onValueChange = { security = it.take(2048) }, label = stringResource(R.string.linux_sources_security), singleLine = true, modifier = Modifier.fillMaxWidth())
                                Text(stringResource(R.string.linux_sources_template_note), style = MiuixTheme.textStyles.footnote1)
                            }
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.linux_sources_latest_note), style = MiuixTheme.textStyles.footnote1)
                        NexaCompactAction(if(checking) stringResource(R.string.linux_sources_checking) else stringResource(R.string.linux_sources_check), enabled = !checking, onClick = {
                            checking = true
                            scope.launch { try {
                                val checker = LinuxLatestVersionChecker(context)
                                LinuxLatestVersionChecker.ids.forEach { checker.check(it); latest = checker.recorded() }
                            } finally { checking = false } }
                        })
                        latest.forEach { row -> DetailListRow(title = row.id, subtitle = if (row.error != null) stringResource(R.string.linux_sources_failed) else row.officialUrl, icon = Icons.Rounded.Source, value = row.version ?: stringResource(R.string.linux_sources_failed)) }
                    }
                }
            }
            error?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NexaCompactAction(stringResource(R.string.linux_sources_close), onClick = { show = false }, modifier = Modifier.weight(1f))
                if(category == 1) NexaCompactAction(stringResource(R.string.linux_sources_save), enabled = name.isNotBlank() && url.isNotBlank(), modifier = Modifier.weight(1f), onClick = {
                    runCatching {
                        entries = LinuxSourceSettings.add(context, name, dist, selectedKind, url, if(selectedKind == LinuxSourceSettings.APT) security else null)
                        LinuxSourceSettings.select(context, dist, selectedKind, entries.last().id)
                    }.onSuccess { name = ""; url = ""; security = ""; error = null; preferenceRevision++; category = 0 }
                        .onFailure { error = it.message ?: context.getString(R.string.linux_sources_invalid) }
                })
            }
        }
    }
}

private fun sourceLabel(kind: String): Int = when(kind) {
    LinuxSourceSettings.ROOTFS -> R.string.linux_sources_rootfs
    LinuxSourceSettings.APK -> R.string.linux_sources_apk
    LinuxSourceSettings.APT -> R.string.linux_sources_apt
    LinuxSourceSettings.UV -> R.string.linux_sources_uv
    LinuxSourceSettings.NODE -> R.string.linux_sources_node
    LinuxSourceSettings.GO -> R.string.linux_sources_go
    LinuxSourceSettings.JDK -> R.string.linux_sources_jdk
    LinuxSourceSettings.MAVEN -> R.string.linux_sources_maven
    LinuxSourceSettings.GRADLE -> R.string.linux_sources_gradle
    LinuxSourceSettings.NPM -> R.string.linux_sources_npm
    LinuxSourceSettings.PYPI -> R.string.linux_sources_pypi
    LinuxSourceSettings.PYTHON -> R.string.linux_sources_python
    LinuxSourceSettings.RUST_DIST -> R.string.linux_sources_rust
    else -> R.string.linux_sources_generic
}
