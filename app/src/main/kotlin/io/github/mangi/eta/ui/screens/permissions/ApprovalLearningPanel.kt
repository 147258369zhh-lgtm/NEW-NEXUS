package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.approval.ApprovalLearningStore
import io.github.mangi.eta.agent.approval.ApprovalPreference
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

/** Repository-backed management stays on the existing permissions approval page. */
@Composable
internal fun ApprovalLearningPanel() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ApprovalLearningStore(context) }
    val coroutineScope = rememberCoroutineScope()
    val revision by ApprovalLearningStore.changes.collectAsState()
    var enabled by remember { mutableStateOf(true) }
    var days by remember { mutableIntStateOf(7) }
    var preferences by remember { mutableStateOf(emptyList<ApprovalPreference>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var visibleCount by remember { mutableIntStateOf(50) }
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) { runCatching { Triple(store.learningEnabled(), store.validityDays(), store.list()) } }
            .onSuccess {
            enabled = it.first; days = it.second; preferences = it.third
        }.onFailure { error = "审批偏好暂时无法读取；不会使用未验证的偏好" }
    }
    fun mutate(action: () -> Unit) {
        coroutineScope.launch {
            error = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull()?.let { "审批偏好未保存，请重试" } }
        }
    }
    SmallTitle("审批偏好")
    Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Column {
            SwitchPreference(title = "学习真实批准偏好", checked = enabled,
                summary = "只记录你实际点击的批准、拒绝和撤销；模型反馈和关闭弹窗不算授权。暂停后仍保留记录。",
                onCheckedChange = { next -> mutate { store.setLearningEnabledFromUi(next) } })
            ArrowPreference(title = "新证据有效期：${days}天", summary = "点按切换1天、7天或30天；不延长已有记录",
                onClick = { mutate { store.setValidityDaysFromUi(when (days) { 1 -> 7; 7 -> 30; else -> 1 }) } })
            Text("相同项目、工具、参数及效果反复获准后形成候选，智能审批仍独立审查。未知账号或外部连接版本不复用。",
                modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            error?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MiuixTheme.colorScheme.error) }
            if (preferences.isEmpty()) Text("尚无真实用户审批记录", modifier = Modifier.padding(16.dp),
                style = MiuixTheme.textStyles.body2)
            preferences.take(visibleCount).forEach { preference ->
                val status = when {
                    preference.revoked -> "已撤销"
                    preference.expiresAt <= System.currentTimeMillis() -> "已到期"
                    preference.refusals > 0 -> "有拒绝记录，再次操作交给你"
                    preference.isCandidate -> "窄范围候选"
                    else -> "证据积累中"
                }
                val expiry = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(preference.expiresAt))
                ArrowPreference(title = "${preference.tool} · $status",
                    summary = "${preference.project.take(100)}\n${preference.targetLabel.take(400)}\n批准${preference.approvals}次，拒绝${preference.refusals}次；到期 $expiry\n仅相同完整参数；点按撤销并删除可复用偏好",
                    onClick = { mutate { store.revokeFromUi(preference.key) } })
            }
            if (preferences.size > visibleCount) ArrowPreference(title = "显示更多审批偏好",
                summary = "还有${preferences.size - visibleCount}条", onClick = { visibleCount += 50 })
        }
    }
}
