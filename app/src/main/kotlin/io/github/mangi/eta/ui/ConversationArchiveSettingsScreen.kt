package io.github.mangi.eta.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.conversations.ConversationArchiveScheduler
import io.github.mangi.eta.fork.conversations.ConversationArchiveSettings
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val archiveIdleDayPresets = listOf(7, 14, 30, 60, 90)

@Composable
internal fun ConversationArchiveSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val checkStartedMessage = stringResource(R.string.conversation_archive_check_started)
    val settingsFlow = remember(applicationContext) {
        ConversationExtensionRepository.snapshot(applicationContext)
    }
    val snapshot by settingsFlow.collectAsState(initial = null)
    val settings = snapshot?.archiveSettings ?: ConversationArchiveSettings()

    fun updateSettings(transform: (ConversationArchiveSettings) -> ConversationArchiveSettings) {
        scope.launch {
            val updated = ConversationExtensionRepository.updateArchiveSettings(applicationContext, transform)
            ConversationArchiveScheduler.sync(applicationContext, updated)
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.conversation_archive_settings_title),
        onBack = onBack,
    ) {
        item(key = "archive_overview") {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .etaGlassSurface(accent = EtaIconTone.DATA.accent, cornerRadius = 18.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EtaIconTile(Icons.Rounded.Archive, tone = EtaIconTone.DATA, size = 42.dp, glyphSize = 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("会话归档", style = MiuixTheme.textStyles.title3, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            if (settings.enabled) "空闲 ${settings.idleDays} 天后自动归档" else "自动归档已关闭",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    EtaStatusPill(
                        if (settings.enabled) "已启用" else "关闭",
                        tone = if (settings.enabled) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                    )
                }
            }
        }
        item(key = "archive_behavior") {
            SmallTitle(stringResource(R.string.conversation_archive_group_behavior))
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                    .etaGlassSurface(accent = EtaIconTone.AUTOMATION.accent, cornerRadius = 16.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_enabled),
                    summary = stringResource(R.string.conversation_archive_enabled_summary),
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        updateSettings { it.copy(enabled = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.conversation_archive_idle_days),
                    items = archiveIdleDayPresets.map { days ->
                        stringResource(R.string.conversation_archive_days_value, days)
                    },
                    selectedIndex = archiveIdleDayPresets.indexOf(settings.idleDays)
                        .takeIf { it >= 0 }
                        ?: archiveIdleDayPresets.indexOf(30),
                    onSelectedIndexChange = { index ->
                        archiveIdleDayPresets.getOrNull(index)?.let { days ->
                            updateSettings { it.copy(idleDays = days) }
                        }
                    },
                )
            }
        }

        item(key = "archive_exemptions") {
            SmallTitle(stringResource(R.string.conversation_archive_group_exemptions))
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                    .etaGlassSurface(accent = EtaIconTone.TOOL.accent, cornerRadius = 16.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_pinned),
                    checked = settings.exemptPinned,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptPinned = exempt) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_meetings),
                    checked = settings.exemptMeetings,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptMeetings = exempt) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_running),
                    checked = settings.exemptRunning,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptRunning = exempt) }
                    },
                )
            }
        }

        item(key = "archive_check") {
            SmallTitle(stringResource(R.string.conversation_archive_group_check))
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                    .etaGlassSurface(accent = EtaIconTone.SYSTEM.accent, cornerRadius = 16.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                BasicComponent(
                    title = stringResource(R.string.conversation_archive_check_now),
                    summary = settings.lastError?.let { error ->
                        stringResource(R.string.conversation_archive_last_error, error)
                    } ?: settings.lastCheckedAt?.let { checkedAt ->
                        stringResource(
                            R.string.conversation_archive_last_checked,
                            DateUtils.getRelativeTimeSpanString(
                                checkedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                        )
                    } ?: stringResource(R.string.conversation_archive_never_checked),
                    summaryColor = if (settings.lastError != null) {
                        BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                    } else {
                        BasicComponentDefaults.summaryColor(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    enabled = settings.enabled,
                    onClick = {
                        ConversationArchiveScheduler.checkNow(applicationContext)
                        Toast.makeText(
                            context,
                            checkStartedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}
