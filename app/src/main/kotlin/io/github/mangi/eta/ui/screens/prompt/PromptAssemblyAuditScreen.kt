package io.github.mangi.eta.ui.screens.prompt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.prompt.PromptAssemblyAuditStore
import io.github.mangi.eta.agent.prompt.PromptInheritance
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun PromptAssemblyAuditScreen(store: PromptInjectionUiStore, onEdit: (PromptTemplate) -> Unit, onBack: () -> Unit) {
    var rows by remember { mutableStateOf(PromptAssemblyAuditStore.recent()) }
    BackHandler { onBack() }
    MiuixScaffoldPage(title = "本轮生效记录", onBack = onBack, actions = {
        TextButton(text = "刷新", onClick = { rows = PromptAssemblyAuditStore.recent() })
    }) {
        item(key = "evidence_note") {
            PromptSectionNote("记录来自提供商转换前的实际请求入口，已经过本轮角色投影和上下文处理；各提供商仍会转换消息，本地模型还会压缩工具协议。这里不代表服务端最终接收内容或执行成功。只保留最近 8 个运行的元数据，不保存输入、密钥或提示词正文。")
        }
        item(key = "phase_boundary") {
            PromptSectionNote("本次行为模板覆盖普通对话、会议规划/工作/协调/汇总/访谈、子 Agent 和自动任务。记忆提取、Skill 专用模型任务、对话修整和上下文压缩继续使用各自专用指令，不受普通行为模板控制；它们不在此列表中。")
        }
        if (rows.isEmpty()) item(key = "audit_empty") {
            PromptSectionNote("还没有请求记录。开始一次普通对话、会议、子 Agent 或自动任务后可在这里查看其实际作用范围、阶段和预算省略。")
        }
        rows.forEachIndexed { index, row ->
            item(key = "audit_$index") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(row.optString("stage") + " · " + row.optString("model"), style = MiuixTheme.textStyles.body1)
                        Text("运行：" + row.optString("operation_id") + "\n" +
                            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(row.optLong("time"))),
                            style = MiuixTheme.textStyles.footnote1)
                        val runScope = row.optJSONObject("scope") ?: JSONObject()
                        val scopeNames = listOf("project_id" to "项目", "conversation_id" to "会话", "meeting_room_id" to "会议",
                            "meeting_participant" to "成员/主持", "subagent_name" to "子 Agent", "character_id" to "角色", "automation_id" to "自动任务")
                        scopeNames.forEach { (key, label) -> runScope.optString(key).takeIf(String::isNotBlank)?.let {
                            Text("$label：$it", style = MiuixTheme.textStyles.footnote1)
                        } }
                        jsonRows(row.optJSONArray("sources")).forEach { source ->
                            Text(source.optString("source") + " · " + source.optString("position") + "\n" + source.optString("status"),
                                style = MiuixTheme.textStyles.footnote1)
                        }
                        val fragments = jsonRows(row.optJSONArray("fragments"))
                        if (fragments.isEmpty()) Text("本轮没有适用的行为方案指令块。", style = MiuixTheme.textStyles.footnote1)
                        fragments.forEach { fragment ->
                            val position = if (fragment.optString("position") == "post_history") "当前输入之后的临时用户提示" else "系统前置"
                            Text(fragment.optString("template") + " / " + fragment.optString("title").ifBlank { "未命名块" },
                                style = MiuixTheme.textStyles.body2)
                            Text("来源：" + fragment.optString("source") + " · " + fragment.optString("scope") + "\n" +
                                position + " · " + PromptInheritance.fromWire(fragment.optString("inheritance")).title + "\n" +
                                fragment.optString("status") + " · " + fragment.optInt("content_chars") + " 字符" +
                                if (!fragment.isNull("message_index")) " · 请求消息 #" + (fragment.optInt("message_index") + 1) else "",
                                style = MiuixTheme.textStyles.footnote1)
                            store.template(fragment.optString("template_id"))?.let { template ->
                                TextButton(text = "查看当前方案正文（可能已修改）", onClick = { onEdit(template) })
                            }
                        }
                        val tools = row.optJSONArray("tools") ?: JSONArray()
                        Text("本轮实际公开 ${tools.length()} 个工具；执行前仍复核权限。按需工具尚未激活时仅显示发现工具。" +
                            if (row.optBoolean("hosted_web_search")) "已启用提供商网页搜索。" else "", style = MiuixTheme.textStyles.footnote1)
                        if (tools.length() > 0) Text((0 until tools.length()).joinToString("、") { tools.optString(it) },
                            style = MiuixTheme.textStyles.footnote1)
                    }
                }
            }
        }
    }
}

private fun jsonRows(array: JSONArray?): List<JSONObject> = array?.let { values ->
    (0 until values.length()).mapNotNull { values.optJSONObject(it) }
}.orEmpty()
