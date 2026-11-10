package io.github.mangi.eta.ui.screens.memory

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.memory.*
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
    val context = LocalContext.current.applicationContext
    var settings by remember { mutableStateOf(MemoryAutomationSettingsStore.read(context)) }
    fun update(value: MemoryAutomationSettings) {
        settings = value
        MemoryAutomationSettingsStore.save(context, value)
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MemoryPolicyGroup(
            title = "记忆提取",
            icon = Icons.Rounded.Memory,
            tone = EtaIconTone.MEMORY,
            status = if (settings.extractMemory) "自动提取" else "已停止",
            statusTone = if (settings.extractMemory) EtaStatusTone.Positive else EtaStatusTone.Neutral,
        ) {
            SwitchPreference(title = "自动提取记忆", summary = "先经过软件管家质量门禁，通过后才进入人工批准区", checked = settings.extractMemory,
                onCheckedChange = { update(settings.copy(extractMemory = it)) })
            SwitchPreference(title = "偏好与习惯", checked = settings.capturePreferences,
                onCheckedChange = { update(settings.copy(capturePreferences = it)) })
            SwitchPreference(title = "事实与项目决定", checked = settings.captureDecisions,
                onCheckedChange = { update(settings.copy(captureDecisions = it)) })
            SwitchPreference(title = "可复用步骤与纠错", summary = "通用经验进入自学习，局部流程按作用域保存", checked = settings.captureProcedures,
                onCheckedChange = { update(settings.copy(captureProcedures = it)) })
        }
        MemoryPolicyGroup(
            title = "空闲整理",
            icon = Icons.Rounded.Schedule,
            tone = EtaIconTone.AUTOMATION,
            status = if (settings.idleEnabled) "已计划" else "关闭",
            statusTone = if (settings.idleEnabled) EtaStatusTone.Positive else EtaStatusTone.Neutral,
        ) {
            SwitchPreference(title = "空闲时自动整理", summary = "由系统安排后台运行时间", checked = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(idleEnabled = it)) })
            SwitchPreference(title = "仅充电时", checked = settings.chargingOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(chargingOnly = it)) })
            SwitchPreference(title = "仅熄屏时", checked = settings.screenOffOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(screenOffOnly = it)) })
            SwitchPreference(title = "仅非计费网络", summary = "通常为 Wi-Fi", checked = settings.unmeteredOnly, enabled = settings.idleEnabled,
                onCheckedChange = { update(settings.copy(unmeteredOnly = it)) })
            NumberChoice("检查间隔", listOf(15, 30, 60, 180, 360), settings.intervalMinutes, "分钟") { update(settings.copy(intervalMinutes = it)) }
            NumberChoice("任务结束后等待", listOf(1, 5, 10, 30, 60), settings.quietMinutes, "分钟") { update(settings.copy(quietMinutes = it)) }
        }
        MemoryPolicyGroup(
            title = "用量与保留",
            icon = Icons.Rounded.DataUsage,
            tone = EtaIconTone.DATA,
            status = "每日 ${settings.dailyLimit} 次",
        ) {
            NumberChoice("每日请求上限", listOf(1, 3, 6, 12, 20), settings.dailyLimit, "次") { update(settings.copy(dailyLimit = it)) }
            NumberChoice("每次整理来源数", listOf(1, 2, 3, 5), settings.batchSize, "个") { update(settings.copy(batchSize = it)) }
            NumberChoice("局部记忆有效期", listOf(7, 14, 30, 90, 180), settings.retentionDays, "天") { update(settings.copy(retentionDays = it)) }
            BasicComponent(title = "双阶段审核", summary = "软件管家拒绝空泛、重复和缺证据内容；通过后才由你确认生效")
            BasicComponent(title = "冲突与过期", summary = "冲突始终由你处理；过期条目停止供 Agent 使用")
            BasicComponent(title = "整理模型", summary = "使用当前选中的模型，受每日请求额度限制")
        }
    }
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
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).etaGlassSurface(accent = tone.accent, cornerRadius = 18.dp),
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
