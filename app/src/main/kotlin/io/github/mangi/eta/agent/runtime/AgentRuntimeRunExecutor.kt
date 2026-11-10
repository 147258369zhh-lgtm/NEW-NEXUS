package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.AccessibilityRunGate
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.capability.NexusCapabilityDirectory
import io.github.mangi.eta.agent.capability.NexusCapabilityPolicyAdapter
import io.github.mangi.eta.agent.capability.NexusLazyToolCatalog
import io.github.mangi.eta.agent.capability.NexusToolDiscoveryCatalog
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentModelFallbackPolicy
import io.github.mangi.eta.agent.model.ModelFallbackProgress
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.memory.MemoryAutomationCapture
import io.github.mangi.eta.agent.memory.MemoryAutomationSettingsStore
import io.github.mangi.eta.agent.memory.MemoryReviewRepository
import io.github.mangi.eta.agent.memory.MemorySearchTool
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.prompt.PromptRunScope
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentExecutor
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.capability.ApiConnectorSnapshot
import io.github.mangi.eta.agent.capability.ApiToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.fork.learning.AgentLearningTools
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val guiControl: GuiControlCoordinator,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = AgentRunArchiveStore.createEventBuffer(request.handoff)
        val experienceEvents = mutableListOf<AgentEvent>()
        val usedSkillIds = linkedSetOf<String>()
        var experienceVerified = true
        var selectedSkillPolicy: AgentToolPolicy? = null
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var browserBinding: AutoCloseable? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        MemoryAutomationCapture.activeRuns.incrementAndGet()
        MemoryAutomationSettingsStore.touch(appContext)
        val result = try {
            runController.throwIfCancelled()
            val setupConversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            check(runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                io.github.mangi.eta.data.repository.ConversationBranchSetupCoordinator.ensure(appContext, setupConversationId)
            }) { appContext.getString(io.github.mangi.eta.R.string.branch_setup_required) }
            runController.throwIfCancelled()
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            var skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val workflowModeInstruction = when (uiPayload?.workflowMode?.uppercase()) {
                "PLAN" -> "用户已选择计划模式。只允许编写或修订计划、会话记事本和提醒草稿；项目文件、设置、终端、GUI、Root、插件安装和外部消息保持只读或禁止，直到用户切换模式。"
                "GOAL" -> "用户已选择目标模式。把当前请求视为持久 Goal：先明确完成条件与计划，再在硬预算内推进；达到软预算、需要增加权限或最终完成时必须等待用户确认。"
                else -> null
            }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            memoryConversationId = conversationId
            val meetingId = request.handoff?.takeIf { it.source == AgentRuntimeWire.MEETING_HANDOFF_SOURCE }
                ?.payload?.trim()?.takeIf { it.isNotBlank() }
            val taskId = request.handoff?.takeIf { it.source == "scheduled_task" }
                ?.payload?.trim()?.takeIf { it.isNotBlank() }
            val projectId = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                when {
                    conversationId != null -> ProjectRepository.bindingForSubject(
                        appContext, ProjectSubjectType.CONVERSATION, conversationId,
                    )?.projectId
                    meetingId != null -> ProjectRepository.bindingForSubject(
                        appContext, ProjectSubjectType.MEETING, meetingId,
                    )?.projectId
                    taskId != null -> io.github.mangi.eta.fork.tasks.AgentTaskDatabase.get(appContext).agentTaskDao().definition(taskId)?.let {
                        io.github.mangi.eta.fork.tasks.AutomationSpec.parse(it.policyJson).projectId
                    }
                    else -> null
                }
            }
            val projectMemoryScope = projectId?.let(AgentMemoryScope::project)
            val runMemoryScope = when {
                taskId != null -> AgentMemoryScope.task(taskId)
                meetingId != null -> AgentMemoryScope.meeting(meetingId)
                conversationId != null -> AgentMemoryScope.conversation(conversationId)
                else -> AgentMemoryScope.GLOBAL
            }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            // 提示词注入的作用域由请求与本次 run 的实际对象共同确定；请求已给出的标识优先。
            val promptScope = request.promptScope.withDefaults(
                conversationId = conversationId,
                characterId = roleplayContext?.characterId,
                meetingRoomId = meetingId,
                automationId = taskId,
            )
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryScopes = listOfNotNull(AgentMemoryScope.GLOBAL, projectMemoryScope, runMemoryScope).distinctBy { it.key }
            skillContext = skillContext.copy(installedSkills = runBlocking {
                val dao = io.github.mangi.eta.fork.learning.LearningDatabase.get(appContext).skillEvolutionDao()
                skillContext.installedSkills.filter { skill ->
                    if (!skill.id.startsWith("auto-")) true else dao.recipe(skill.id)?.let { recipe ->
                        recipe.version > 0 && recipe.state in setOf("active", "review") && (recipe.scope == "global" || recipe.scope in memoryScopes.map { it.key })
                    } == true
                }
            })
            val reviewRepository = MemoryReviewRepository(LearningDatabase.get(appContext))
            val memoryContext = (if (memoryEnabled) {
                runCatching {
                    ConversationMemoryPins.contextFor(
                        conversationId = conversationId,
                        snapshot = AgentMemoryRepository.snapshot(),
                        contextWindow = request.config.contextWindow,
                    ).copy(
                        scopedContent = memoryScopes.filter { it != AgentMemoryScope.GLOBAL }.joinToString("\n\n") { scoped ->
                            val snapshot = AgentMemoryRepository.snapshot(scoped)
                            "[${scoped.key}] revision=${snapshot.revision}\n${snapshot.content.take(2400)}"
                        }.take(6000),
                        reviewedContent = runBlocking { reviewRepository.context(memoryScopes) },
                    )
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }).copy(learnedContent = if (roleplayContext == null && LearningSettingsStore.isEnabled(appContext)) {
                runCatching { runBlocking { LearningRepository.get(appContext).context() } }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_learning_context_failed") {
                        "Agent learning context unavailable: type=${throwable.safeLogType()}"
                    }
                    ""
                }
            } else "")
            val memorySearch = MemorySearchTool(appContext, memoryScopes)
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                runBlocking {
                    while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                        runController.throwIfCancelled()
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val apiSnapshot = runCatching { ApiConnectorSnapshot.load(appContext) }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_api_snapshot_failed") {
                    "REST API tool snapshot unavailable: type=${throwable.safeLogType()}"
                }
                ApiConnectorSnapshot.EMPTY
            }
            val externalTools = JSONArray().also { tools ->
                mcpSnapshot.appendModelTools(tools)
                apiSnapshot.appendModelTools(tools)
            }
            val browserKey = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            browserBinding = BrowserSessionRegistry.acquireRun(browserKey, request.runId)
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(
                    session, AgentEvent.GuiControlChanged(status), archivedEvents,
                    entrySurfaceGuard, checkpointRecorder,
                )
            }
            val accessibilityGate = AccessibilityRunGate(
                controller = runController,
                gui = checkNotNull(guiRun),
                serviceAvailable = AgentAccessibilityService::isAvailable,
                requestRecovery = { AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext) },
                onEvent = { event ->
                    acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
                },
            )
            val executor = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = request.runId,
                browserSession = BrowserSessionRegistry.get(browserKey),
                guiControl = guiRun,
                automationWritesAllowed = { request.handoff?.source != "scheduled_task" },
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                memoryScope = AgentMemoryScope.GLOBAL,
                projectMemoryScope = projectMemoryScope,
                runMemoryScope = runMemoryScope,
                workflowScopeId = conversationId ?: meetingId ?: taskId,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeGuiAcquisition = accessibilityGate::prepare,
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityService.isAvailable()
                        } else {
                            null
                        }
                        when {
                            accessibility == false -> ToolExecutionDecision.Reject(
                                code = "ACCESSIBILITY_DISCONNECTED",
                                message = "无障碍在取得屏幕操作权后断开，本次未执行；请重新观察，恢复等待将释放操作权。",
                            )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                virtualDisplayEnabled = { currentPermissions().virtualDisplay },
            )
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                providers = listOf(
                    McpToolExecutor(mcpSnapshot),
                    ApiToolExecutor(appContext, apiSnapshot),
                ),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                ConversationHistoryTool {
                    val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                    val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                        .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                    journal + session.transcript
                }
            }
            val subagentAllowed = {
                request.config.subagentTools && currentPermissions().subagentTools
            }
            val learningRepository = LearningRepository.get(appContext)
            val learningTools = if (LearningSettingsStore.isEnabled(appContext)) {
                AgentLearningTools(learningRepository)
            } else {
                null
            }
            val subagentExecutor = SubagentExecutor(
                conversationKey = browserKey,
                parentController = runController,
                promptScope = promptScope,
                configProvider = { profileName, explicitProviderId, explicitModelId, explicitReasoningEffort ->
                    val resolved = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(
                            primaryConfig = request.config,
                            profileName = profileName,
                            explicitProviderId = explicitProviderId,
                            explicitModelId = explicitModelId,
                            explicitReasoningEffort = explicitReasoningEffort,
                        )
                    }
                    resolved.copy(
                        config = AgentRuntimePolicy.constrain(resolved.config, currentPermissions()),
                    )
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                baseToolExecutor = AgentModelClient.ToolExecutor { call ->
                    if (request.toolPolicy.isAllowed(call.name) && (selectedSkillPolicy?.isAllowed(call.name) != false)) routingExecutor.execute(call)
                    else AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "PARENT_TOOL_BOUNDARY_DENIED").toString())
                },
                skillContext = skillContext,
                memoryContext = memoryContext,
                mcpTools = externalTools,
                executionContext = executor.terminalExecutionContext,
                metricsScope = session.telemetry?.scope,
                inheritedToolPolicy = request.toolPolicy,
                currentToolPolicy = { request.toolPolicy.intersect(selectedSkillPolicy ?: AgentToolPolicy.UNRESTRICTED) },
                capabilityDirectory = NexusCapabilityDirectory.get(appContext),
                onProgress = { progressEvent ->
                    acceptEvent(
                        session,
                        progressEvent,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                },
            )
            val runTools = JSONArray(externalTools.toString()).also { tools ->
                if (subagentAllowed()) AgentSubagentToolCatalog.appendSchemas(tools)
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
                if (learningTools != null) {
                    val schemas = AgentLearningTools.schemas()
                    for (index in 0 until schemas.length()) tools.put(schemas.getJSONObject(index))
                }
                if (memoryEnabled && roleplayContext == null) tools.put(MemorySearchTool.schema())
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                if (!request.toolPolicy.isAllowed(call.name) || selectedSkillPolicy?.isAllowed(call.name) == false) {
                    AgentModelClient.ToolResult(
                        JSONObject()
                            .put("ok", false)
                            .put("code", "TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY")
                            .put("message", "工具 ${call.name} 不在本次运行的权限范围内")
                            .toString(),
                    )
                } else if (call.name in AgentSubagentToolCatalog.names) {
                    if (!subagentAllowed()) {
                        AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", "SUBAGENT_DISABLED")
                                .put("message", "Subagent 权限已关闭")
                                .toString(),
                        )
                    } else if (call.name == AgentSubagentToolCatalog.PROFILE_TOOL_NAME) {
                        subagentExecutor.executeProfileManagement(call)
                    } else {
                        subagentExecutor.execute(call.round, call)
                    }
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else if (learningTools != null && call.name in AgentLearningTools.NAMES) {
                    learningTools.execute(call)
                } else if (call.name == "memory_search" && roleplayContext == null && memoryEnabled) {
                    memorySearch.execute(call)
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "skills_read") runCatching { JSONObject(result.content) }.getOrNull()
                        ?.takeIf { it.optBoolean("ok") }?.optString("id")?.takeIf(String::isNotBlank)?.let { id ->
                            usedSkillIds.add(id)
                            val recipe = runBlocking { LearningDatabase.get(appContext).skillEvolutionDao().recipe(id) }
                            if (recipe != null) {
                                val skillPolicy = AgentToolPolicy(
                                    recipe.tools.lines() + listOf(
                                        "skills_read", "skills_read_resource", "skills_list",
                                        "get_current_context", "release_gui",
                                    ),
                                )
                                selectedSkillPolicy = selectedSkillPolicy?.union(skillPolicy) ?: skillPolicy
                            }
                        }
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let {
                                ConversationMemoryPins.recordOwnWrite(conversationId, it.optString("revision"))
                                if (taskId == null) io.github.mangi.eta.fork.tasks.AutomationTriggers.signal(appContext,
                                    io.github.mangi.eta.fork.tasks.AutomationTrigger.MEMORY_UPDATED, request.runId + ":" + it.optString("revision"))
                            }
                    }
                }
            }
            val lazyToolCatalog = if (
                request.config.supportsTools && !request.config.isEmbeddedLocalTextModel()
            ) {
                NexusLazyToolCatalog(
                    directory = NexusCapabilityDirectory.get(appContext),
                    toolAllowed = { toolName ->
                        request.toolPolicy.isAllowed(toolName) &&
                            (selectedSkillPolicy?.isAllowed(toolName) != false)
                    },
                    discoveryAllowed = {
                        request.toolPolicy.allowsToolDiscovery(NexusToolDiscoveryCatalog.NAMES) &&
                            (selectedSkillPolicy?.allowsToolDiscovery(NexusToolDiscoveryCatalog.NAMES) != false)
                    },
                    authorize = { descriptor ->
                        NexusCapabilityPolicyAdapter.authorize(
                            descriptor = descriptor,
                            capabilities = AgentToolCapabilities.capture(appContext),
                            taskId = taskId ?: request.runId,
                            highRiskApproved = request.handoff?.source != "a2a",
                        )
                    },
                    additionalSchemas = runTools,
                )
            } else {
                null
            }
            val fallbackProgress = ModelFallbackProgress()
            val fallbackToolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(
                    JSONObject().put("ok", false).put("code", "FALLBACK_TOOLS_DISABLED")
                        .put("message", "备用模型仅提供文字答复，不执行工具").toString(),
                )
            }
            fun completeWith(config: AgentModelClient.ModelConfig, fallback: Boolean = false): AgentModelClient.ModelResponse.Text {
                session.telemetry?.modelAttempt(config)
                return AgentModelClient.complete(
                    config = config,
                    metricsScope = session.telemetry?.scope,
                    sessionId = request.effectiveModelSessionId,
                    operationId = request.runId,
                    initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                    initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                    roleplayContext = roleplayContext,
                    rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                    compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                    compactionSelection = request.compactionSelection,
                    onContextSnapshot = { snapshot ->
                        // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                        ConversationMemoryPins.forget(conversationId)
                        val committed = snapshot.copy(operationId = request.runId)
                        AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                        session.updateContext(committed)
                    },
                    onTranscript = { transcript ->
                        AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                        session.updateTranscript(transcript)
                    },
                    capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                    workflowModeInstruction = workflowModeInstruction,
                    prompt = request.prompt,
                    assistantScreenContext = request.assistantScreenContext,
                    toolExecutor = if (fallback) fallbackToolExecutor else runToolExecutor,
                    images = request.images,
                    history = request.history,
                    runController = runController,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    additionalTools = if (fallback) JSONArray() else runTools,
                    lazyToolCatalog = lazyToolCatalog.takeUnless { fallback },
                    toolFilter = if (!fallback) { schemas ->
                        request.toolPolicy.filterSchemas(schemas).let { parentFiltered ->
                            (selectedSkillPolicy ?: AgentToolPolicy.UNRESTRICTED).filterSchemas(parentFiltered)
                        }
                    } else { _: JSONArray -> JSONArray() },
                    executionContext = executor.terminalExecutionContext,
                    initialRound = if (fallback) fallbackProgress.lastRound + 1 else 1,
                    textOnlyFallback = fallback,
                    promptScope = promptScope,
                ) { event ->
                    if (event is AgentEvent.ToolFinished && event.success != true) experienceVerified = false
                    if (event is AgentEvent.ToolStarted || event is AgentEvent.ToolFinished) {
                        if (experienceEvents.size >= 100) experienceEvents.removeAt(0)
                        experienceEvents.add(event)
                    }
                    fallbackProgress.accept(event)
                    timing.accept(event)
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }
            }
            val completedResponse = try {
                completeWith(request.config)
            } catch (primaryFailure: AgentModelExecutionException) {
                val failureKind = AgentModelFallbackPolicy.classify(primaryFailure.cause)
                val canRestart = fallbackProgress.canRestart && primaryFailure.transcript.isEmpty()
                val isChat = request.operation == AgentRuntimeWire.OP_CHAT
                if (!AgentModelFallbackPolicy.decide(failureKind, true, true, canRestart, isChat).useFallback) throw primaryFailure
                val fallbackConfig = try {
                    runBlocking { RuntimeConfigRepository.fallbackRuntimeConfig(request.config) }
                } catch (failure: Exception) {
                    AndroidAgentLogger.warn("Backup model configuration unavailable: type=${failure.safeLogType()}")
                    throw primaryFailure
                }
                val decision = AgentModelFallbackPolicy.decide(
                    kind = failureKind,
                    fallbackConfigured = fallbackConfig != null,
                    userEnabled = fallbackConfig != null,
                    canRestart = canRestart,
                    isChat = isChat,
                )
                if (!decision.useFallback || runController.isCancelled) throw primaryFailure
                AndroidAgentLogger.warn("Primary model failed (${failureKind.name}); retrying once with the configured backup model")
                acceptEvent(
                    session,
                    AgentEvent.ModelRetryScheduled(
                        round = fallbackProgress.lastRound,
                        attempt = 1,
                        maxAttempts = 1,
                        delayMs = 0,
                        reasonCode = "fallback_${failureKind.name.lowercase()}",
                    ),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
                completeWith(requireNotNull(fallbackConfig), fallback = true)
            }
            response = completedResponse
            if (experienceVerified && roleplayContext == null && request.operation == AgentRuntimeWire.OP_CHAT) {
                runCatching { runBlocking { io.github.mangi.eta.agent.skill.SkillEvolution.capture(
                    appContext, request.runId, projectMemoryScope?.key ?: "global", request.prompt, experienceEvents) } }
                    .onFailure { AndroidAgentLogger.warn("Skill experience capture unavailable: type=${it.safeLogType()}") }
            }
            if (roleplayContext == null && uiPayload != null && request.operation == AgentRuntimeWire.OP_CHAT) {
                runCatching { runBlocking { MemoryAutomationCapture.capture(appContext, request.runId,
                    "conversation:${conversationId.orEmpty()}/run:${request.runId}",
                    (listOf(request.prompt) + uiPayload.supplements.map { it.text }).joinToString("\n"), memoryScopes) } }
                    .onFailure { AndroidAgentLogger.warn("Memory capture unavailable: type=${it.safeLogType()}") }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}",
                    throwable,
                )
                if (LearningSettingsStore.automaticCaptureEnabled(appContext)) {
                    runCatching {
                        runBlocking {
                            LearningRepository.get(appContext).recordFailure(
                                errorCode = requestFailure?.code ?: throwable.javaClass.simpleName,
                                message = requestFailure?.message ?: throwable.message.orEmpty(),
                                sourceRef = "run:${request.runId}",
                            )
                        }
                    }.onFailure { learningFailure ->
                        AndroidAgentLogger.warnThrottled("agent_learning_capture_failed") {
                            "Learning evidence capture failed: type=${learningFailure.safeLogType()}"
                        }
                    }
                }
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            val usedSkillsSucceeded = response != null &&
                !runController.isCancelled &&
                experienceEvents.filterIsInstance<AgentEvent.ToolFinished>().none { it.success == false }
            runCatching {
                io.github.mangi.eta.agent.skill.SkillUsageStore(appContext).recordRun(
                    skillIds = usedSkillIds,
                    successful = usedSkillsSucceeded,
                )
            }
            runCatching { runBlocking { io.github.mangi.eta.agent.skill.SkillEvolution.recordUse(appContext, request.runId, usedSkillIds.toList(),
                usedSkillsSucceeded) } }
            MemoryAutomationCapture.activeRuns.decrementAndGet()
            MemoryAutomationSettingsStore.touch(appContext)
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents.orEmpty())
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents.orEmpty()) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>?,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        if (event !is AgentEvent.GuiControlChanged) archivedEvents?.add(event)
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }
}
