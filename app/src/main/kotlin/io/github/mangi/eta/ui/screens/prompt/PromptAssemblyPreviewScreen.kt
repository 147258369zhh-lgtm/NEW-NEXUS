package io.github.mangi.eta.ui.screens.prompt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.prompt.*
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Uses the production resolver locally; no model request and no credential access. */
@Composable
internal fun PromptAssemblyPreviewScreen(store: PromptInjectionUiStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val models = store.providers.flatMap { provider -> provider.models.map { provider to it } }
    var modelKey by rememberSaveable { mutableStateOf("") }
    val selected = models.firstOrNull { "${it.first.id}/${it.second.modelId}" == modelKey } ?: models.firstOrNull()
    val model = selected?.let { (provider, selectedModel) -> AgentModelClient.ModelConfig(
        providerId = provider.id, providerName = provider.name, model = selectedModel.modelId,
        contextWindow = selectedModel.effectiveContextWindow, systemPrompt = provider.systemPrompt.orEmpty(),
        baseUrl = "", apiKey = "",
    ) }
    var typeName by rememberSaveable { mutableStateOf(PromptScopeType.GLOBAL.wireValue) }
    val type = PromptScopeType.fromWire(typeName) ?: PromptScopeType.GLOBAL
    var targetJson by rememberSaveable { mutableStateOf(PromptTarget.GLOBAL.toJson().toString()) }
    val target = runCatching { PromptTarget.fromJson(org.json.JSONObject(targetJson)) }.getOrNull() ?: PromptTarget.GLOBAL
    var stageName by rememberSaveable { mutableStateOf(PromptStage.NORMAL.wireValue) }
    val stage = PromptStage.fromWire(stageName)
    var query by rememberSaveable { mutableStateOf("") }
    var runScope by remember { mutableStateOf(PromptRunScope()) }
    var macros by remember { mutableStateOf(PromptMacroContext()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(targetJson, modelKey) {
        loading = true
        var scope = when (target.type) {
            PromptScopeType.GLOBAL, PromptScopeType.MODEL -> PromptRunScope()
            PromptScopeType.PROJECT -> PromptRunScope(projectId = target.primaryId)
            PromptScopeType.CONVERSATION -> PromptRunScope(conversationId = target.primaryId)
            PromptScopeType.SUBAGENT -> PromptRunScope(subagentName = target.primaryId)
            PromptScopeType.MEETING -> PromptRunScope(meetingRoomId = target.primaryId)
            PromptScopeType.MEETING_PARTICIPANT -> PromptRunScope(meetingRoomId = target.primaryId, meetingParticipant = target.secondaryId)
            PromptScopeType.CHARACTER -> PromptRunScope(characterId = target.primaryId)
            PromptScopeType.AUTOMATION -> PromptRunScope(automationId = target.primaryId)
        }
        macros = PromptMacroContext()
        if (scope.conversationId.isNotBlank()) {
            runCatching { ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, scope.conversationId) }
                .getOrNull()?.let { scope = scope.copy(projectId = it.projectId) }
            runCatching { RoleplayRunContext.resolve(context, scope.conversationId, model?.knownContextWindow, false) }
                .getOrNull()?.let { role ->
                    scope = scope.copy(characterId = role.characterId)
                    macros = PromptMacroContext(role.card, role.userName, role.userDescription)
                }
        } else if (scope.characterId.isNotBlank()) {
            CharacterRepository.initialize(context)
            runCatching { CharacterRepository.get(scope.characterId) }.getOrNull()?.let { macros = PromptMacroContext(it.card) }
        }
        if (scope.meetingRoomId.isNotBlank()) runCatching {
            ProjectRepository.bindingForSubject(context, ProjectSubjectType.MEETING, scope.meetingRoomId)
        }.getOrNull()?.let { scope = scope.copy(projectId = it.projectId) }
        runScope = scope
        loading = false
    }
    val resolved = model?.let { PromptInjectionResolver.resolve(store.config, it, runScope.copy(stage = stage), macros) }
    BackHandler { onBack() }
    MiuixScaffoldPage(title = "行为方案组装预览", onBack = onBack) {
        item(key = "preview_note") {
            PromptSectionNote("用当前已保存方案和生产装配规则预览，不调用模型。选择对象后继承全局、模型及已绑定项目；可分别检查主持/成员和各阶段。这里展示模板片段的完整内容、来源与省略原因；角色卡、世界书、职责、技能和提供商的后续转换以真实运行记录为准。")
        }
        item(key = "preview_model") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型与上下文预算", style = MiuixTheme.textStyles.body1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    models.forEach { (provider, candidate) ->
                        val key = "${provider.id}/${candidate.modelId}"
                        EtaChoiceChip(text = provider.name + " / " + candidate.displayName.ifBlank { candidate.modelId },
                            selected = selected?.let { it.first.id == provider.id && it.second.modelId == candidate.modelId } == true,
                            onClick = { modelKey = key })
                    }
                }
                if (models.isEmpty()) Text("请先添加模型以计算对应预算。", style = MiuixTheme.textStyles.footnote1)
            }
        }
        item(key = "preview_stage") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("运行阶段", style = MiuixTheme.textStyles.body1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptStage.entries.filter { it != PromptStage.AUTO && it != PromptStage.UNSUPPORTED }.forEach { option ->
                        EtaChoiceChip(text = option.title, selected = option == stage, onClick = { stageName = option.wireValue })
                    }
                }
                Text("当前对象：" + promptTargetLabel(store, target, "全局"), style = MiuixTheme.textStyles.footnote1)
            }
        }
        item(key = "preview_scope") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择范围对象", style = MiuixTheme.textStyles.body1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptScopeType.entries.filter { it != PromptScopeType.MODEL }.forEach { option ->
                        EtaChoiceChip(text = stringResource(option.titleRes()), selected = type == option,
                            onClick = { typeName = option.wireValue; query = ""; if (option == PromptScopeType.GLOBAL) targetJson = PromptTarget.GLOBAL.toJson().toString() })
                    }
                }
                if (type != PromptScopeType.GLOBAL) TextField(value = query, onValueChange = { query = it.take(120) },
                    label = "筛选对象", singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        if (type != PromptScopeType.GLOBAL) store.targets(type).filter { query.isBlank() || it.title.contains(query, true) || it.detail.contains(query, true) }
            .forEach { option -> item(key = "preview_target_${option.target.key}") {
                TextButton(text = (if (target.key == option.target.key) "✓ " else "") + option.title + " · " + option.detail,
                    enabled = option.enabled, onClick = { targetJson = option.target.toJson().toString() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            } }
        if (!loading && resolved != null) {
            item(key = "preview_result") { PromptSectionNote("实际选入 ${resolved.templateIds.size} 个方案、${resolved.characterCount} 字符；超预算内容整块省略，当前用户明确要求优先，模板不能扩大真实工具权限。") }
            resolved.fragments.forEachIndexed { index, fragment -> item(key = "preview_fragment_$index") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fragment.templateName + " / " + fragment.title.ifBlank { "未命名块" }, style = MiuixTheme.textStyles.body1)
                        Text("来源：${fragment.source} · ${fragment.scope}\n${fragment.inheritance.title} · " +
                            (if (fragment.position == PromptBlockPosition.SYSTEM_PREFIX) "系统前置" else "当前输入之后的临时用户提示") + "\n" + fragment.status,
                            style = MiuixTheme.textStyles.footnote1)
                        if (fragment.content.isNotBlank()) Text(fragment.content, style = MiuixTheme.textStyles.body2)
                    }
                }
            } }
        }
    }
}
