package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.memory.*
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.etaGlassSurface
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MemoryAutomationSettingsPanel() {
    val panel = rememberMemoryAutomationSettingsPanel()
    Column {
        panel.tabs()
        panel.content()
    }
}

internal class MemoryAutomationSettingsPanelState(
    val error: String?,
    val listState: LazyListState,
    val tabs: @Composable () -> Unit,
    val content: @Composable () -> Unit,
)

@Composable
internal fun rememberMemoryAutomationSettingsPanel(): MemoryAutomationSettingsPanelState {
    val context = LocalContext.current.applicationContext
    var settings by remember { mutableStateOf(MemoryAutomationSettingsStore.read(context)) }
    val scope = rememberCoroutineScope()
    val codec = remember { Json { encodeDefaults = true } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val captureListState = rememberLazyListState()
    val scheduleListState = rememberLazyListState()
    val limitsListState = rememberLazyListState()
    LaunchedEffect(context) { MemoryAutomationSettingsStore.observe(context).collect { settings = it } }
    fun update(value: MemoryAutomationSettings) {
        if (busy) return
        busy = true
        scope.launch {
            error = null
            try {
                withContext(Dispatchers.IO) {
                    NexusSettingsControlPlane.get(context).applyJson("settings.memory.automation",
                        JSONObject().put("value", JSONObject(codec.encodeToString(value))),
                        SettingsActorKind.LOCAL_USER, "memory_policy")
                }
                settings = MemoryAutomationSettingsStore.read(context)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { error = "策略未保存，请重试"
            } finally { busy = false }
        }
    }
    return MemoryAutomationSettingsPanelState(
        error = error,
        listState = when (section) { 0 -> captureListState; 1 -> scheduleListState; else -> limitsListState },
        tabs = {
        NexusSettingsTabs(
            tabs = listOf(
                stringResource(R.string.settings_tab_memory_capture),
                stringResource(R.string.settings_tab_memory_schedule),
                stringResource(R.string.settings_tab_memory_limits),
            ),
            selectedTab = section,
            onTabSelected = { section = it },
        )
        },
        content = {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
        if (section == 0) MemoryPolicyGroup(
            title = "记忆提取",
            icon = Icons.Rounded.Memory,
            tone = EtaIconTone.MEMORY,
            status = if (settings.extractMemory) "自动提取" else "已停止",
            statusTone = if (settings.extractMemory) EtaStatusTone.Positive else EtaStatusTone.Neutral,
        ) {
            SwitchPreference(title = "自动提取记忆", checked = settings.extractMemory,
                onCheckedChange = { update(settings.copy(extractMemory = it)) })
            SwitchPreference(title = "偏好与习惯", checked = settings.capturePreferences,
                onCheckedChange = { update(settings.copy(capturePreferences = it)) })
            SwitchPreference(title = "事实与项目决定", checked = settings.captureDecisions,
                onCheckedChange = { update(settings.copy(captureDecisions = it)) })
            SwitchPreference(title = "可复用步骤与纠错", checked = settings.captureProcedures,
                onCheckedChange = { update(settings.copy(captureProcedures = it)) })
        }
        if (section == 1) MemoryPolicyGroup(
            title = "空闲整理",
            icon = Icons.Rounded.Schedule,
            tone = EtaIconTone.AUTOMATION,
            status = if (settings.idleEnabled) "已计划" else "关闭",
            statusTone = if (settings.idleEnabled) EtaStatusTone.Positive else EtaStatusTone.Neutral,
        ) {
            SwitchPreference(title = "空闲时自动整理", checked = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(idleEnabled = it)) })
            SwitchPreference(title = "仅充电时", checked = settings.chargingOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(chargingOnly = it)) })
            SwitchPreference(title = "仅熄屏时", checked = settings.screenOffOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(screenOffOnly = it)) })
            SwitchPreference(title = "仅非计费网络", checked = settings.unmeteredOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(unmeteredOnly = it)) })
            NumberChoice("检查间隔", listOf(15, 30, 60, 180, 360), settings.intervalMinutes, "分钟") { update(settings.copy(intervalMinutes = it)) }
            NumberChoice("任务结束后等待", listOf(1, 5, 10, 30, 60), settings.quietMinutes, "分钟") { update(settings.copy(quietMinutes = it)) }
        }
        if (section == 2) MemoryPolicyGroup(
            title = "用量与保留",
            icon = Icons.Rounded.DataUsage,
            tone = EtaIconTone.DATA,
            status = "每日 ${settings.dailyLimit} 次",
        ) {
            NumberChoice("每日请求上限", listOf(1, 3, 6, 12, 20), settings.dailyLimit, "次") { update(settings.copy(dailyLimit = it)) }
            NumberChoice("每次整理来源数", listOf(1, 2, 3, 5), settings.batchSize, "个") { update(settings.copy(batchSize = it)) }
            NumberChoice("局部记忆有效期", listOf(7, 14, 30, 90, 180), settings.retentionDays, "天") { update(settings.copy(retentionDays = it)) }
        }
    }
    })
}

@Composable
private fun MemoryPolicyGroup(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: EtaIconTone,
    status: String,
    statusTone: EtaStatusTone = EtaStatusTone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).etaGlassSurface(accent = tone.accent, cornerRadius = 18.dp),
        colors = CardDefaults.defaultColors(colors.surfaceContainer, colors.onSurface),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EtaIconTile(icon, tone = tone, size = 34.dp, glyphSize = 18.dp, enabled = statusTone != EtaStatusTone.Neutral || status != "关闭")
                Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                EtaStatusPill(status, tone = statusTone)
            }
            HorizontalDivider(color = colors.dividerLine.copy(alpha = .55f))
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), content = content)
        }
    }
}

@Composable
private fun NumberChoice(title: String, choices: List<Int>, value: Int, unit: String, onChange: (Int) -> Unit) {
    val values = (choices + value).distinct().sorted()
    OverlayDropdownPreference(title = title, items = values.map { "$it $unit" }, selectedIndex = values.indexOf(value),
        onSelectedIndexChange = { values.getOrNull(it)?.let(onChange) })
}
