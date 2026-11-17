package io.github.mangi.eta.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ArchiveEntry
import io.github.mangi.eta.data.repository.ArchiveEntryType
import io.github.mangi.eta.data.repository.UnifiedConversationArchiveRepository
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val archiveIdleDayPresets = listOf(7, 14, 30, 60, 90)

@Composable
internal fun ConversationArchiveSettingsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit = {},
    onOpenMeeting: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val checkStartedMessage = stringResource(R.string.conversation_archive_check_started)
    val settingsFlow = remember(applicationContext) {
        ConversationExtensionRepository.observeArchiveSettings(applicationContext)
    }
    val settings by settingsFlow.collectAsState(initial = ConversationArchiveSettings())
    var query by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(0) }
    var selectedPage by remember { mutableStateOf(0) }
    val recordsListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    var entries by remember { mutableStateOf(emptyList<ArchiveEntry>()) }
    var cursor by remember { mutableStateOf<ArchiveEntry?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var restoringKey by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { java.text.SimpleDateFormat("yyyy年M月d日", java.util.Locale.getDefault()) }
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val dayGroups = remember(entries) { entries.groupBy { dateFormat.format(java.util.Date(it.archivedAt)) } }

    LaunchedEffect(query, selectedType, refresh, cursor) {
        loading = true
        loadError = null
        try {
            if (cursor == null) delay(300)
            val page = UnifiedConversationArchiveRepository.page(applicationContext, query,
                when (selectedType) { 1 -> ArchiveEntryType.Conversation; 2 -> ArchiveEntryType.Meeting; else -> null }, cursor)
            entries = if (cursor == null) page.entries else (entries + page.entries).distinctBy { it.key }
            hasMore = page.hasMore
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = "归档读取失败，请重试"
        } finally {
            loading = false
        }
    }

    fun resetResults() {
        cursor = null
        entries = emptyList()
        hasMore = false
        loading = true
    }

    fun updateSettings(transform: (ConversationArchiveSettings) -> ConversationArchiveSettings) {
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    ConversationExtensionRepository.updateArchiveSettings(applicationContext, transform)
                }
                ConversationArchiveScheduler.sync(applicationContext, updated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "归档设置更新失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.conversation_archive_settings_title),
        onBack = onBack,
        listState = if (selectedPage == 0) recordsListState else settingsListState,
        aboveContent = {
            NexaSegmentedTabs(listOf("归档记录", "归档设置"), selectedPage,
                onTabSelected = { selectedPage = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        },
    ) {
        if (selectedPage == 0) {
        item(key = "archive_center_search") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("普通会话与会议室统一保存；打开查看会保留归档状态。",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextField(value = query, onValueChange = { value ->
                    val next = value.take(256)
                    if (next != query) { query = next; resetResults() }
                },
                    label = "搜索标题或消息内容", singleLine = true, modifier = Modifier.fillMaxWidth())
                NexaSegmentedTabs(listOf("全部", "普通会话", "会议室"), selectedType,
                    onTabSelected = { selectedType = it; resetResults() })
                Text(if (query.isBlank()) "按归档时间排列" else "搜索全部归档的标题和真实消息内容",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (entries.isEmpty()) item(key = "archive_empty") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(when { loading -> "正在读取归档…"; loadError != null -> "暂时无法读取归档";
                        query.isNotBlank() -> "没有匹配的归档"; else -> "暂无归档" }, style = MiuixTheme.textStyles.title3)
                    Text(loadError ?: when { loading -> "搜索会检查保存的消息内容。";
                        query.isNotBlank() -> "试试其他关键词或类型筛选。";
                        selectedType == 1 -> "归档后的普通会话会出现在这里。";
                        selectedType == 2 -> "归档后的会议室会出现在这里。";
                        else -> "归档后的会话和会议室会按日期显示在这里。" },
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (loadError != null) NexaCompactAction("重试", onClick = { refresh++ })
                }
            }
        }
        dayGroups.forEach { (day, group) ->
            item(key = "archive_day:$day") { SmallTitle(day) }
            group.forEach { entry ->
                item(key = "archive_entry:${entry.key}") {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(entry.title.ifBlank { if (entry.type == ArchiveEntryType.Meeting) "未命名会议室" else "未命名会话" },
                                    modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.title3,
                                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                EtaStatusPill(if (entry.type == ArchiveEntryType.Meeting) "会议室" else "普通会话", tone = EtaStatusTone.Neutral)
                            }
                            Text("${timeFormat.format(java.util.Date(entry.archivedAt))} 归档 · ${entry.detail}",
                                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(entry.preview.ifBlank { "暂无文本消息" }, style = MiuixTheme.textStyles.footnote1,
                                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                NexaCompactAction("打开查看", onClick = {
                                    if (entry.type == ArchiveEntryType.Meeting) onOpenMeeting(entry.id) else onOpenConversation(entry.id)
                                }, enabled = restoringKey == null)
                                NexaCompactAction(if (restoringKey == entry.key) "正在恢复…" else "恢复", enabled = restoringKey == null,
                                    onClick = {
                                        restoringKey = entry.key
                                        scope.launch {
                                            try {
                                                UnifiedConversationArchiveRepository.restore(applicationContext, entry)
                                                resetResults()
                                                refresh++
                                                Toast.makeText(context, "已恢复到原会话列表", Toast.LENGTH_SHORT).show()
                                            } catch (cancelled: CancellationException) { throw cancelled
                                            } catch (_: Exception) {
                                                Toast.makeText(context, "恢复失败，请重试", Toast.LENGTH_SHORT).show()
                                            } finally { restoringKey = null }
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
        if (entries.isNotEmpty() && (hasMore || loadError != null)) item(key = "archive_more") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loadError != null) Text(checkNotNull(loadError), color = MiuixTheme.colorScheme.error)
                NexaCompactAction(if (loading) "正在读取…" else if (loadError != null) "重试" else "加载更多", enabled = !loading,
                    onClick = { if (loadError != null) refresh++ else cursor = entries.lastOrNull() })
            }
        }
        } else {
        item(key = "archive_overview") {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
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
                        Text("自动归档", style = MiuixTheme.textStyles.title3, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
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
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
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
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
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
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
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
}
