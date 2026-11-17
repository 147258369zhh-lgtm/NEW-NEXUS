package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.skill.*
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillEvolutionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { LearningDatabase.get(context).skillEvolutionDao() }
    val recipes by dao.observe().collectAsState(emptyList())
    var enabled by remember { mutableStateOf(SkillEvolution.enabled(context)) }
    var dailyLimit by remember { mutableIntStateOf(SkillEvolution.dailyLimit(context)) }
    var autoPublish by remember { mutableStateOf(SkillEvolution.autoPublish(context)) }
    var notice by remember { mutableStateOf(SkillEvolution.notice(context)) }
    var selected by remember { mutableStateOf<SkillRecipe?>(null) }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    fun changeSettings(nextEnabled: Boolean = enabled, nextLimit: Int = dailyLimit, nextAutoPublish: Boolean = autoPublish) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val after = withContext(Dispatchers.IO) {
                    io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(context).applyJson(
                        "settings.skill.evolution", org.json.JSONObject().put("value", org.json.JSONObject()
                            .put("enabled", nextEnabled).put("dailyLimit", nextLimit).put("autoPublish", nextAutoPublish)),
                        io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER, "skill_evolution")
                }
                enabled = after.getBoolean("enabled"); dailyLimit = after.getInt("dailyLimit")
                autoPublish = after.getBoolean("autoPublish")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled
            } catch (_: Exception) { message = "设置未保存，请重试"
            } finally { saving = false }
        }
    }
    val target = selected
    if (target != null) {
        BackHandler { selected = null }
        SkillRecipeDetail(target, onBack = { selected = null })
        return
    }
    LaunchedEffect(recipes) { notice = SkillEvolution.notice(context) }
    MiuixScaffoldPage("经验技能", onBack, actions = {
        IconButton(onClick = { SkillEvolution.enqueue(context, immediate = true); message = "已加入整理队列" }) { Icon(Icons.Rounded.Refresh, "整理经验") }
    }) {
        item {
            SwitchPreference(title = "自动积累执行经验", checked = enabled, enabled = !saving,
                onCheckedChange = { changeSettings(nextEnabled = it) })
        }
        item {
            SwitchPreference(title = "自动发布已验证技能", summary = "保留来源、版本与回滚", checked = autoPublish, enabled = !saving,
                onCheckedChange = { changeSettings(nextAutoPublish = it) })
        }
        item {
            val limits = listOf(1, 3, 5, 10, 20, 30)
            OverlayDropdownPreference(title = "每日提炼额度", items = limits.map { "$it 次" }, selectedIndex = limits.indexOf(dailyLimit).coerceAtLeast(0),
                onSelectedIndexChange = { changeSettings(nextLimit = limits[it]) })
        }
        if (message.isNotBlank()) item { Text(message, modifier = Modifier.padding(horizontal = 20.dp), style = MiuixTheme.textStyles.footnote1) }
        notice?.let { current -> item { BasicComponent(
            title = "技能自学习",
            summary = current,
            endActions = { IconButton(onClick = {
                SkillEvolution.dismissNotice(context)
                notice = null
            }) { Icon(Icons.Rounded.Close, "关闭通知") } },
        ) } }
        item { SmallTitle("能力候选 · ${recipes.size}") }
        if (recipes.isEmpty()) item { ListEmptyState(title = "暂无能力候选", summary = "任务执行后自动积累可复用的成功流程", icon = Icons.Rounded.AutoAwesome) }
        recipes.forEach { recipe -> item(key = recipe.id) {
            var evidence by remember(recipe.updatedAt) { mutableIntStateOf(0) }
            LaunchedEffect(recipe.updatedAt) { evidence = withContext(Dispatchers.IO) { SkillEvolution.evidenceForDraft(context, recipe).size } }
            DetailListRow(recipe.title, "${skillRecipeState(recipe.state)} · $evidence / ${SkillEvolution.MIN_EVIDENCE} 个来源 · ${skillScopeLabel(recipe.scope)}",
                Icons.Rounded.AutoAwesome, preview = recipe.description,
                value = if (recipe.version > 0) "v${recipe.version}${if (recipe.state == "update_butler_approved") " · 待更新" else ""}" else null,
                onClick = { selected = recipe })
        } }
    }
}

internal fun skillRecipeState(state: String) = when (state) {
    "review" -> "待软件管家复核"
    "butler_approved" -> "已验证，等待自动发布"
    "update_collecting" -> "更新积累中"
    "update_butler_approved" -> "更新已验证，等待自动发布"
    "update_rejected" -> "更新未通过"
    "active" -> "已启用"
    "rejected" -> "未通过，已拒绝"
    "paused" -> "已暂停"
    "merged" -> "已合并"
    "conflict" -> "文件版本冲突，已停用"
    else -> "积累中"
}

internal fun skillScopeLabel(scope: String): String = when {
    scope == "global" -> "全局"
    scope.startsWith("project:") -> "项目记忆"
    scope.startsWith("meeting:") -> "会议室记忆"
    scope.startsWith("conversation:") -> "当前会话"
    scope.startsWith("task:") -> "任务记忆"
    else -> "当前范围"
}

@Composable
private fun SkillRecipeDetail(initial: SkillRecipe, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { LearningDatabase.get(context).skillEvolutionDao() }
    var recipe by remember(initial.id) { mutableStateOf(initial) }
    var body by remember(initial.id) { mutableStateOf(initial.body) }
    var evidence by remember { mutableStateOf(emptyList<SkillExperience>()) }
    var versions by remember { mutableStateOf(emptyList<SkillRecipeVersion>()) }
    var eligibleEvidence by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var mergeTargets by remember { mutableStateOf(emptyList<SkillRecipe>()) }
    LaunchedEffect(recipe.updatedAt) {
        val loaded = withContext(Dispatchers.IO) { Triple(dao.evidence(recipe.id), dao.versions(recipe.id), dao.recipes()) }
        eligibleEvidence = withContext(Dispatchers.IO) { SkillEvolution.evidenceForDraft(context, recipe).size }
        evidence = loaded.first; versions = loaded.second
        mergeTargets = loaded.third.filter { it.id != recipe.id && it.scope == recipe.scope && it.tools.lines().toSet() == recipe.tools.lines().toSet() && it.state !in setOf("rejected", "merged", "conflict") }
    }
    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = withContext(Dispatchers.IO) { block() }; recipe = withContext(Dispatchers.IO) { dao.recipe(recipe.id) ?: recipe } }
            catch (failure: Exception) { message = failure.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    MiuixScaffoldPage(recipe.title, onBack, actions = {
        IconButton(
            enabled = !busy && recipe.state != "rejected" &&
                eligibleEvidence >= SkillEvolution.MIN_EVIDENCE,
            onClick = { action { SkillEvolution.publish(context, recipe.id, recipe.updatedAt, body) } },
        ) { Icon(Icons.Rounded.Check, if (recipe.version > 0) "确认更新" else "确认启用") }
    }) {
        item { SmallTitle("${skillRecipeState(recipe.state)} · ${skillScopeLabel(recipe.scope)} · $eligibleEvidence / ${SkillEvolution.MIN_EVIDENCE}") }
        if (message.isNotBlank()) item { Text(message, modifier = Modifier.padding(20.dp), style = MiuixTheme.textStyles.body2) }
        item { TextField(value = body, onValueChange = { body = it }, label = "技能正文", minLines = 12, maxLines = 40,
            enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        item { SmallTitle("已验证工具") }
        recipe.tools.lines().filter(String::isNotBlank).forEach { name -> item(key = "tool:$name") {
            DetailListRow(io.github.mangi.eta.agent.overlay.toolDisplayName(name), name, iconForTool(name))
        } }
        item { SmallTitle("来源 · ${evidence.size}") }
        evidence.take(20).forEach { source -> item(key = source.runId) {
            val task = runCatching { org.json.JSONObject(source.digest).optString("task") }.getOrDefault("")
            val label = when (source.state) { "accepted" -> "成功执行证据"; "used_success" -> "调用后完成"; "used_failure" -> "调用后未完成"; else -> "来源记录" }
            DetailListRow(label, source.runId.take(36), Icons.Rounded.ReceiptLong, preview = task.ifBlank { source.detail })
        } }
        item { SmallTitle("版本记录") }
        versions.forEach { version -> item(key = "version:${version.version}") {
            DetailListRow("v${version.version}", "载入此版本后可重新验证发布", Icons.Rounded.History, onClick = { body = version.body; message = "已载入 v${version.version}，发布后生成新版本" })
        } }
        if (recipe.version == 0 && recipe.state != "merged" && mergeTargets.isNotEmpty()) item {
            OverlayDropdownPreference(title = "合并重复候选", items = listOf("选择目标技能") + mergeTargets.map { it.title }, selectedIndex = 0,
                onSelectedIndexChange = { index -> mergeTargets.getOrNull(index - 1)?.let { target -> action { SkillEvolution.merge(context, recipe.id, target.id, recipe.updatedAt); "来源证据已合并到 ${target.title}" } } })
        }
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("暂停", enabled = !busy, modifier = Modifier.weight(1f), onClick = { action {
                    SkillEvolution.pause(context, recipe.id, recipe.updatedAt); "已暂停"
                } })
                TextButton("拒绝候选", enabled = !busy && recipe.version == 0, modifier = Modifier.weight(1f), onClick = { action {
                    SkillEvolution.reject(context, recipe.id, recipe.updatedAt); "已拒绝，后续同类经验不再自动提炼"
                } })
            }
        }
    }
}
