package io.github.mangi.eta.ui.screens.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import io.github.mangi.eta.agent.control.EtaAppControlAuditStore
import io.github.mangi.eta.agent.control.EtaAppControlRegistry
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.UUID

private data class ControlShortcut(
    val title: String,
    val icon: ImageVector,
    val route: AppRoute,
)

private data class EtaControlAuditSummary(
    val total: Int = 0,
    val failed: Int = 0,
    val lastTimestamp: Long = 0L,
)

private val controlShortcuts = listOf(
    ControlShortcut("模型", Icons.Rounded.Memory, AppRoute.ModelProviders),
    ControlShortcut("记忆", Icons.Rounded.AutoAwesome, AppRoute.Memory),
    ControlShortcut("角色与指令", Icons.Rounded.Description, AppRoute.Characters),
    ControlShortcut("能力", Icons.Rounded.Build, AppRoute.CapabilityCenter),
    ControlShortcut("自动任务", Icons.Rounded.Schedule, AppRoute.TaskCenter),
    ControlShortcut("权限", Icons.Rounded.Security, AppRoute.Permissions),
    ControlShortcut("设置", Icons.Rounded.Settings, AppRoute.Settings),
)

private const val CONTROL_SESSION_ID = "eta-app-control-console"
private val CONTROL_PROMPT = """
你是 Nexus 软件管家。你的职责是理解用户目标，并使用当前实际提供的工具完成对 Nexus 软件的查询与操作。
规则：
1. 软件能力必须先通过 eta_control 的 list/search 查询稳定 ID；不要猜测 ID。
2. 需要打开页面、读取或修改设置时调用 eta_control，不能只用文字声称已完成。
3. 自动任务、记忆、Skill、MCP、REST API、文件、终端、设备、A2A 等能力应调用各自对应工具；eta_control 负责软件入口和注册表能力。两者共同覆盖管家可用的全部公开接口。
4. 修改后要回读状态确认实际结果；需要排查此前操作或用户询问操作记录时调用 eta_control 的 audit。
5. 删除、清空、恢复、取消运行等不可逆或影响现有状态的操作，必须先说明影响并取得用户明确同意。可逆导航和读取可直接执行。
6. 工具失败时如实报告代码与原因，禁止虚构成功。答复保持简洁，清楚说明实际执行结果。
7. 用户提供模糊目标时，先读取相关状态再决定；不要反复询问已经能从软件内确认的信息。
""".trimIndent()

internal fun appControlRoute(key: String): AppRoute? = when (key) {
    "home" -> AppRoute.Home
    "models" -> AppRoute.ModelProviders
    "local_models" -> AppRoute.LocalModels
    "memory" -> AppRoute.Memory
    "capabilities" -> AppRoute.CapabilityCenter
    "skills" -> AppRoute.Skills
    "mcp" -> AppRoute.McpServers
    "prompt_injection" -> AppRoute.PromptInjection
    "api_connectors" -> AppRoute.ApiConnectors
    "automations" -> AppRoute.TaskCenter
    "subagents" -> AppRoute.Subagents
    "learning" -> AppRoute.SelfLearning
    "a2a" -> AppRoute.A2AAgents
    "metrics" -> AppRoute.AgentMetrics
    "permissions" -> AppRoute.Permissions
    "speech" -> AppRoute.SpeechSettings
    "speech_recognition" -> AppRoute.SpeechRecognition
    "speech_synthesis" -> AppRoute.SpeechSynthesis
    "system_enhance" -> AppRoute.SystemEnhance
    "characters" -> AppRoute.Characters
    "workspace" -> AppRoute.Workspace
    "projects" -> AppRoute.Projects
    "shared_folders" -> AppRoute.SharedFolders
    "backup" -> AppRoute.DataBackup
    "terminal" -> AppRoute.Terminal
    "settings" -> AppRoute.Settings
    else -> when {
        key.startsWith("model_provider:") -> key.substringAfter(':').takeIf(String::isNotBlank)
            ?.let(AppRoute::ModelProviderDetail)
        key.startsWith("mcp_server:") -> key.substringAfter(':').takeIf(String::isNotBlank)
            ?.let(AppRoute::McpServerDetail)
        key.startsWith("meeting_room:") -> key.substringAfter(':').takeIf(String::isNotBlank)
            ?.let(AppRoute::MeetingRoom)
        else -> null
    }
}

@Composable
internal fun AppControlScreen(
    onBack: () -> Unit,
    onNavigate: (AppRoute) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember(context.applicationContext) {
        AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
    }
    val historyStore = remember(context.applicationContext) { AppControlHistoryStore(context.applicationContext) }
    val auditStore = remember(context.applicationContext) { EtaAppControlAuditStore(context.applicationContext) }
    val capabilities = remember { EtaAppControlRegistry.list() }
    val toolStarts = remember { mutableMapOf<String, Long>() }
    val listState = rememberLazyListState()
    var messages by remember { mutableStateOf<List<AppControlMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var config by remember { mutableStateOf<AgentModelClient.ModelConfig?>(null) }
    var loadingConfig by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var activeRunId by remember { mutableStateOf<String?>(null) }
    var auditSummary by remember { mutableStateOf<EtaControlAuditSummary>(EtaControlAuditSummary()) }

    fun updateAssistant(id: String, transform: (AppControlMessage) -> AppControlMessage) {
        messages = messages.map { message -> if (message.id == id) transform(message) else message }
    }

    fun saveMessages() {
        val snapshot = messages
        scope.launch(Dispatchers.IO) { runCatching { historyStore.save(snapshot) } }
    }

    fun submit() {
        val prompt = draft.trim()
        val modelConfig = config
        if (prompt.isEmpty() || running || modelConfig == null) return
        val priorMessages = messages
        val userMessage = AppControlMessage(
            id = UUID.randomUUID().toString(),
            role = AppControlMessageRole.USER,
            text = prompt,
        )
        val assistantId = UUID.randomUUID().toString()
        val assistantMessage = AppControlMessage(
            id = assistantId,
            role = AppControlMessageRole.ASSISTANT,
            text = "",
            toolStatus = "正在理解",
        )
        val runId = UUID.randomUUID().toString()
        messages = (priorMessages + userMessage + assistantMessage).takeLast(80)
        draft = ""
        running = true
        activeRunId = runId
        scope.launch {
            runCatching {
                val request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = modelConfig.copy(
                        systemPrompt = modelConfig.systemPrompt.trimEnd() + "\n\n" + CONTROL_PROMPT,
                    ),
                    images = emptyList(),
                    history = priorMessages.filter { !it.isError }.map { message ->
                        AgentModelClient.ConversationMessage(
                            role = if (message.role == AppControlMessageRole.USER) "user" else "assistant",
                            content = message.text,
                        )
                    },
                    modelSessionId = CONTROL_SESSION_ID,
                )
                val result = withContext(Dispatchers.IO) {
                    val events = Channel<AgentEvent>(Channel.UNLIMITED)
                    val collector = launch(Dispatchers.Main.immediate) {
                        for (event in events) {
                            when (event) {
                                is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
                                    updateAssistant(assistantId) { message ->
                                        message.copy(text = message.text + event.delta, toolStatus = "")
                                    }
                                }
                                is AgentEvent.ToolStarted -> updateAssistant(assistantId) { message ->
                                    toolStarts[event.toolCallId] = SystemClock.elapsedRealtime()
                                    message.copy(toolStatus = "正在执行 ${event.name.controlToolName()}")
                                }
                                is AgentEvent.ToolFinished -> {
                                    val startedAt = toolStarts.remove(event.toolCallId)
                                    if (event.name != "eta_control") {
                                        val success = event.success != false
                                        scope.launch(Dispatchers.IO) {
                                            runCatching {
                                                auditStore.append(
                                                    operation = "tool",
                                                    capabilityId = "tool:${event.name}",
                                                    parameters = org.json.JSONObject().put("toolName", event.name),
                                                    success = success,
                                                    errorCode = if (success) "" else "TOOL_FAILED",
                                                    durationMs = startedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L,
                                                )
                                            }
                                        }
                                    }
                                    updateAssistant(assistantId) { message ->
                                        message.copy(
                                            toolStatus = if (event.success != false) {
                                                "${event.name.controlToolName()} 已完成"
                                            } else {
                                                "${event.name.controlToolName()} 未完成"
                                            },
                                        )
                                    }
                                }
                                is AgentEvent.RunFailed -> updateAssistant(assistantId) { message ->
                                    message.copy(text = event.reason, isError = true, toolStatus = "")
                                }
                                else -> Unit
                            }
                        }
                    }
                    try {
                        runtime.run(request) { event -> events.trySend(event) }
                    } finally {
                        events.close()
                        collector.join()
                    }
                }
                if (!result.ok) {
                    updateAssistant(assistantId) { message ->
                        message.copy(
                            text = message.text.ifBlank { result.error ?: "软件管家运行失败" },
                            isError = true,
                            toolStatus = "",
                        )
                    }
                } else if (messages.firstOrNull { it.id == assistantId }?.text.isNullOrBlank()) {
                    updateAssistant(assistantId) { message ->
                        message.copy(
                            text = result.content.ifBlank { "操作已完成" },
                            toolStatus = "",
                        )
                    }
                }
            }.onFailure { error ->
                updateAssistant(assistantId) { message ->
                    message.copy(
                        text = message.text.ifBlank { error.message ?: "软件管家运行失败" },
                        isError = true,
                        toolStatus = "",
                    )
                }
            }
            running = false
            activeRunId = null
            saveMessages()
            auditSummary = withContext(Dispatchers.IO) {
                val summary = runCatching { auditStore.summary() }.getOrNull()
                EtaControlAuditSummary(
                    total = summary?.optInt("total", 0) ?: 0,
                    failed = summary?.optInt("failed", 0) ?: 0,
                    lastTimestamp = summary?.optLong("lastTimestamp", 0L) ?: 0L,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        messages = withContext(Dispatchers.IO) { historyStore.load() }
        auditSummary = withContext(Dispatchers.IO) {
            val summary = runCatching { auditStore.summary() }.getOrNull()
            EtaControlAuditSummary(
                total = summary?.optInt("total", 0) ?: 0,
                failed = summary?.optInt("failed", 0) ?: 0,
                lastTimestamp = summary?.optLong("lastTimestamp", 0L) ?: 0L,
            )
        }
        config = withContext(Dispatchers.IO) { RuntimeConfigRepository.currentRuntimeConfig() }
        loadingConfig = false
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex + 1)
    }

    MiuixScaffold(title = "软件管家", onBack = onBack) { padding, _, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = sidePadding + 14.dp,
                    end = sidePadding + 14.dp,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "control_status") {
                    ControlStatusCard(
                        model = config,
                        loading = loadingConfig,
                        running = running,
                        capabilityCount = capabilities.size,
                        writeCount = capabilities.count { it.danger == EtaControlDanger.WRITE },
                        confirmationCount = capabilities.count { it.danger == EtaControlDanger.CONFIRM },
                        audit = auditSummary,
                        onStop = {
                            activeRunId?.let(runtime::cancelRun)
                        },
                    )
                }
                item(key = "control_shortcuts") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 1.dp),
                    ) {
                        items(controlShortcuts, key = { it.title }) { shortcut ->
                            ControlShortcutItem(shortcut) { onNavigate(shortcut.route) }
                        }
                    }
                }
                if (messages.isEmpty()) {
                    item(key = "control_intro") {
                        ControlIntroduction(onExampleClick = { draft = it })
                    }
                }
                items(messages, key = AppControlMessage::id) { message ->
                    ControlMessageCard(message)
                }
            }
            ControlComposer(
                value = draft,
                onValueChange = { draft = it.take(12_000) },
                canSend = draft.isNotBlank() && !running && config != null,
                running = running,
                onSubmit = ::submit,
            )
        }
    }
}

@Composable
private fun ControlStatusCard(
    model: AgentModelClient.ModelConfig?,
    loading: Boolean,
    running: Boolean,
    capabilityCount: Int,
    writeCount: Int,
    confirmationCount: Int,
    audit: EtaControlAuditSummary,
    onStop: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val ready = model?.supportsTools == true
    val statusColor = when {
        running -> scheme.primary
        ready -> androidx.compose.ui.graphics.Color(0xFF2FA66A)
        else -> scheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 15.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    scheme.primary.copy(alpha = 0.20f),
                                    scheme.primary.copy(alpha = 0.06f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                loading -> "正在读取模型"
                                running -> "正在执行"
                                ready -> "管理员在线"
                                else -> "需要配置"
                            },
                            style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = model?.modelDisplayName?.takeIf(String::isNotBlank)
                            ?: model?.model
                            ?: "尚未配置可用模型",
                        style = MiuixTheme.textStyles.footnote1,
                        color = scheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && !model.supportsTools) {
                        Text(
                            text = "当前模型未声明工具调用能力",
                            style = MiuixTheme.textStyles.footnote2,
                            color = scheme.error,
                        )
                    }
                }
                if (running) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(scheme.surfaceContainerHigh)
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = "停止",
                            modifier = Modifier.size(16.dp),
                            tint = scheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainer.copy(alpha = 0.62f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControlMetric("公开接口", capabilityCount.toString(), Modifier.weight(1f))
                ControlMetric("可直接写", writeCount.toString(), Modifier.weight(1f))
                ControlMetric("强确认", confirmationCount.toString(), Modifier.weight(1f))
                ControlMetric(
                    label = "审计记录",
                    value = if (audit.failed > 0) "${audit.total} · ${audit.failed} 失败" else audit.total.toString(),
                    modifier = Modifier.weight(1.35f),
                )
            }
        }
    }
}

@Composable
private fun ControlMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(modifier) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ControlShortcutItem(shortcut: ControlShortcut, onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .widthIn(min = 78.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = shortcut.icon,
            contentDescription = null,
            tint = scheme.primary,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = shortcut.title,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ControlIntroduction(onExampleClick: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "直接说你想怎么调整",
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "我会先读取真实状态，再通过公开接口执行、回读结果并留下脱敏审计。高风险操作会单独确认。",
                style = MiuixTheme.textStyles.body2,
                color = scheme.onSurfaceVariantSummary,
                lineHeight = 20.sp,
            )
            ControlPromptExample("把记忆整理改成每天凌晨执行", onExampleClick)
            ControlPromptExample("审核最近的学习候选，垃圾经验不要交给我批准", onExampleClick)
            ControlPromptExample("检查会议室、子 Agent 和当前模型的运行状态", onExampleClick)
            ControlPromptExample("导出最近一次失败操作和对应审计记录", onExampleClick)
        }
    }
}

@Composable
private fun ControlPromptExample(text: String, onClick: (String) -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainer)
            .clickable { onClick(text) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ControlMessageCard(message: AppControlMessage) {
    val scheme = MiuixTheme.colorScheme
    val isUser = message.role == AppControlMessageRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, top = 2.dp)
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (message.isError) Icons.Rounded.ErrorOutline else Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = if (message.isError) scheme.error else scheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) scheme.primary else scheme.surfaceContainer)
                .border(
                    width = 1.dp,
                    color = if (isUser) scheme.primary else scheme.outline.copy(alpha = 0.22f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (message.text.isNotBlank()) {
                if (isUser) {
                    Text(
                        text = message.text,
                        style = MiuixTheme.textStyles.body2,
                        color = scheme.onPrimary,
                    )
                } else {
                    StaticMarkdown(
                        content = message.text,
                        tone = MarkdownTone.Answer,
                    )
                }
            } else if (message.toolStatus.isNotBlank()) {
                Text(
                    text = "正在整理结果…",
                    style = MiuixTheme.textStyles.body2,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
            if (message.toolStatus.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (message.toolStatus.contains("正在")) Icons.Rounded.Sync else Icons.Rounded.Build,
                        contentDescription = null,
                        tint = if (message.isError) scheme.error else scheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = message.toolStatus,
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (message.isError) scheme.error else scheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlComposer(
    value: String,
    onValueChange: (String) -> Unit,
    canSend: Boolean,
    running: Boolean,
    onSubmit: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.background.copy(alpha = 0.97f))
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outline.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp, max = 116.dp)
                    .padding(vertical = 7.dp),
                textStyle = MiuixTheme.textStyles.body2.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = if (running) "正在执行，请稍候" else "告诉软件管家要做什么",
                                style = MiuixTheme.textStyles.body2,
                                color = scheme.onSurfaceVariantSummary,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (canSend) scheme.primary else scheme.surfaceContainer)
                    .clickable(enabled = canSend, onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "发送",
                    tint = if (canSend) scheme.onPrimary else scheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

private fun String.controlToolName(): String = when (this) {
    "eta_control" -> "软件能力"
    "automation_manage" -> "自动任务"
    "memory_get", "memory_write" -> "记忆"
    "skills_list", "skills_read" -> "Skills"
    else -> this
}
