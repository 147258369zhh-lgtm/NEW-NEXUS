package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.AccessibilityRunGate
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.capability.NexusCapabilityDirectory
import io.github.mangi.eta.agent.capability.NexusCapabilityPolicyAdapter
import io.github.mangi.eta.agent.capability.NexusExecutionGate
import io.github.mangi.eta.agent.capability.NexusLazyToolCatalog
import io.github.mangi.eta.agent.capability.NexusToolDiscoveryCatalog
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.model.AgentContextLink
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunBudgetGuard
import io.github.mangi.eta.agent.model.AgentRunExtensions
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
import io.github.mangi.eta.agent.memory.MemorySearchTool
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
import io.github.mangi.eta.agent.skill.SkillCoordination
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SelectedSkillResolver
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
import io.github.mangi.eta.fork.learning.AgentLearningTools
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import kotlinx.coroutines.runBlocking
import io.github.zhh_lgtm.nexus.kernel.api.intersect
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
    private val modelTaskProvider: (AgentModelClient.ModelConfig) -> io.github.mangi.eta.agent.model.AgentProviderClient =
        io.github.mangi.eta.agent.model.ProviderClientFactory::getClient,
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
        if (request.operation == AgentRuntimeWire.OP_MODEL_TASK) return executeModelTask(session, request)
        val runController = session.controller
        val canonicalRun = session.executionRequest ?: NexusRunMapper.fromLegacy(request).request
        val runBudgetGuard = AgentRunBudgetGuard(canonicalRun.budget,
            approveSoftLimit = { RunBudgetConfirmation.await(runController, it) },
            approveHardLimit = { RunBudgetConfirmation.await(runController, it) })
        val grantStartedAt = System.currentTimeMillis()
        fun grantExpiresAt(): Long {
            val duration = runBudgetGuard.effectiveBudget().hardDurationMs ?: 60L * 60_000L
            return if (duration >= Long.MAX_VALUE - grantStartedAt) Long.MAX_VALUE else grantStartedAt + duration
        }
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
        var executionDeadline: AutoCloseable? = null
        var packageHooks: AgentRunExtensions = AgentRunExtensions.NONE
        var recoveredInput: RecoveredRunInputStore.Prepared? = null
        val timing = AgentRunTiming(AndroidAgentLogger)

        // 失败清理需要在 try 外拿到会话 id。
        var memoryConversationId: String? = null
        var learningFailureCaptureAllowed = false
        var learningFailureScope = "global"
        var learningIsolation = AgentToolPolicy.UNRESTRICTED
        MemoryAutomationCapture.activeRuns.incrementAndGet()
        val result = try {
            if (request.operation == AgentRuntimeWire.OP_RESUME_INPUT) {
                recoveredInput = RecoveredRunInputStore(appContext).use { it.claimExecution(appContext, request.prompt, request.runId) }
                recoveredInput?.takeIf { it.exact }?.let { recovery ->
                    require(recovery.providerId == request.config.providerId && recovery.model == request.config.model) {
                        "原工具结果恢复需要原模型绑定；当前模型已变化，本轮未启动模型请求或重放工具"
                    }
                }
            }
            val approvalStore = io.github.mangi.eta.agent.approval.ApprovalLearningStore(appContext)
            val permissionState = runBlocking { io.github.mangi.eta.data.datastore.SettingsDataStore.agentPermissionState() }
            val savedPolicy = approvalStore.rememberedPolicy(canonicalRun.runId.value)
                ?: recoveredInput?.originalRunId?.let(approvalStore::rememberedPolicy)
            val savedTools = savedPolicy?.let { stored ->
                fun strings(name: String): List<String>? = stored.optJSONArray(name)?.let { list ->
                    (0 until list.length()).map(list::getString)
                }
                AgentToolPolicy(strings("allowed_tools"), strings("denied_tools").orEmpty())
            } ?: request.toolPolicy
            val approvalPolicy = io.github.mangi.eta.agent.approval.RunPolicySnapshot(
                canonicalRun.runId.value, savedPolicy?.optLong("captured_at") ?: System.currentTimeMillis(),
                savedPolicy?.optLong("captured_revision") ?: permissionState.revision,
                savedPolicy?.optString("mode")?.let { io.github.mangi.eta.data.model.AgentPermissionMode.fromWireValue(it) }
                    ?: permissionState.mode, request.toolPolicy.intersect(savedTools).intersect(AgentBoundaryStore.load()),
            )
            approvalPolicy.persistRestriction = { approvalStore.rememberPolicy(approvalPolicy) }
            approvalStore.rememberPolicy(approvalPolicy)
            io.github.mangi.eta.agent.approval.ActiveRunPolicies.register(approvalPolicy)
            val approvalSession = io.github.mangi.eta.agent.approval.RunApprovalSession(appContext,
                recoveredInput?.messages?.lastOrNull { it.role == "user" }?.content ?: request.prompt,
                canonicalRun.projectId ?: "conversation:${AgentConversationIdentity.fromHandoff(request.handoff, request.runId)}")
            runController.bindApprovalPolicy(approvalPolicy,
                reviewer = { input -> io.github.mangi.eta.agent.approval.IndependentApprovalReviewer.review(request.config, input, runController) },
                session = approvalSession)
            fun currentRunToolPolicy() = request.toolPolicy.intersect(approvalPolicy.toolPolicy).intersect(learningIsolation)
                .intersect(AgentBoundaryStore.load()).intersect(selectedSkillPolicy ?: AgentToolPolicy.UNRESTRICTED)
            val authorizedModes = java.util.concurrent.ConcurrentHashMap<String, io.github.mangi.eta.data.model.AgentPermissionMode>()
            MemoryAutomationSettingsStore.touch(appContext)
            runController.throwIfCancelled()
            executionDeadline = runBudgetGuard.watch(runController)
            session.beginExecution()
            session.prepareInput(request)
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
            val packageSubject = RuntimePackageActions.subject(request.handoff)
            val conversationId = uiPayload?.conversationId?.takeIf { it.isNotBlank() }
                ?: packageSubject?.takeIf { it.kind == io.github.mangi.eta.data.model.ConversationSubject.Kind.CHAT }?.id
            memoryConversationId = conversationId
            val meetingId = request.handoff?.takeIf { it.source == AgentRuntimeWire.MEETING_HANDOFF_SOURCE }
                ?.payload?.trim()?.takeIf { it.isNotBlank() } ?: packageSubject?.takeIf { it.kind == io.github.mangi.eta.data.model.ConversationSubject.Kind.MEETING }?.id
            val taskId = request.handoff?.takeIf { it.source == "scheduled_task" }
                ?.payload?.trim()?.takeIf { it.isNotBlank() }
            val workflowScopeId = conversationId ?: meetingId ?: taskId
            val projectId = canonicalRun.projectId
            val projectWorkspace = io.github.mangi.eta.fork.projects.ProjectWorkspaceRepository.forRun(appContext, projectId, request.runId)
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
            learningFailureCaptureAllowed = roleplayContext == null
            if (roleplayContext != null) learningIsolation = AgentToolPolicy(disallowedTools = listOf("learning_*"))
            learningFailureScope = if (taskId != null || meetingId != null) runMemoryScope.key else projectMemoryScope?.key ?: runMemoryScope.key
            // 提示词注入的作用域由请求与本次 run 的实际对象共同确定；请求已给出的标识优先。
            val promptScope = request.promptScope.withDefaults(
                projectId = projectId,
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
                skillContext.installedSkills.filter { skill ->
                    if (!skill.id.startsWith("auto-")) true else io.github.mangi.eta.agent.skill.SkillEvolution.runtimeRecipe(appContext, skill.id)?.let { recipe ->
                        recipe.scope == "global" || recipe.scope in memoryScopes.map { it.key }
                    } == true
                }
            })
            val requestedSkillIds = uiPayload?.selectedSkillIds.orEmpty()
            val selectedSkills = SelectedSkillResolver.resolve(skillContext.installedSkills, requestedSkillIds)
            require(selectedSkills.error == null) {
                when (selectedSkills.error) {
                    "SELECTED_SKILL_SELECTION_INVALID" -> appContext.getString(io.github.mangi.eta.R.string.chat_skill_selection_invalid)
                    else -> appContext.getString(io.github.mangi.eta.R.string.chat_skill_unavailable)
                }
            }
            if (requestedSkillIds.isNotEmpty()) {
                skillContext = skillContext.copy(
                    installedSkills = selectedSkills.skills,
                    selectedSkillIds = selectedSkills.skills.map { it.id },
                )
            }
            val skillCoordination = SkillCoordination.plan(
                skillContext.installedSkills,
                request.prompt,
                explicitlySelectedIds = skillContext.selectedSkillIds,
            )
            require(!skillContext.selectedSkillIds.isNotEmpty() || !skillCoordination.requiresConfirmation) {
                appContext.getString(io.github.mangi.eta.R.string.chat_skill_conflict)
            }
            val memoryContext = if (roleplayContext != null) AgentMemoryContext.DISABLED else {
                runCatching { runBlocking {
                    io.github.mangi.eta.agent.memory.TaskMemoryContextLoader.build(
                        appContext, memoryScopes, request.prompt, request.config.contextWindow)
                } }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            }
            val memorySearch = MemorySearchTool(appContext, memoryScopes)
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有。首轮只做短等待，避免 Root 授权弹窗把普通会话
                // 卡住数十秒；探测完成后，后续工具目录轮次会取得最新能力。
                runBlocking {
                    val settled = kotlinx.coroutines.withTimeoutOrNull(2_000) {
                        while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                            runController.throwIfCancelled()
                            kotlinx.coroutines.delay(100)
                        }
                        true
                    } != null
                    if (!settled) {
                        AndroidAgentLogger.info("Root probe still running; continuing with current capabilities")
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = RuntimeInvocationScope.withController(runController) { runBlocking(RuntimeInvocationScope.coroutineContext()) {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    if (throwable is kotlinx.coroutines.CancellationException || throwable is AgentRunCancelledException ||
                        throwable is AgentRunPauseException) throw throwable
                    runController.throwIfStopped()
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            } }
            val apiSnapshot = runCatching { ApiConnectorSnapshot.load(appContext) }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_api_snapshot_failed") {
                    "REST API tool snapshot unavailable: type=${throwable.safeLogType()}"
                }
                ApiConnectorSnapshot.EMPTY
            }
            val a2aSnapshot = runCatching { runBlocking { io.github.mangi.eta.agent.capability.A2ARunSnapshot.load(appContext) } }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_a2a_snapshot_failed") { "A2A tool snapshot unavailable: type=${throwable.safeLogType()}" }
                io.github.mangi.eta.agent.capability.A2ARunSnapshot.EMPTY
            }
            val packageSnapshot = runCatching { io.github.mangi.eta.agent.packages.PackageRunSnapshot.load(appContext,
                request.handoff?.takeIf { request.operation == AgentRuntimeWire.OP_PACKAGE_ACTION && it.source == RuntimePackageActions.SOURCE }
                    ?.let { JSONObject(it.payload).optString("tool") }, uiAction = request.handoff?.takeIf { request.operation == AgentRuntimeWire.OP_PACKAGE_ACTION }
                    ?.let { JSONObject(it.payload).optJSONObject("arguments")?.optString("actionId")?.isNotBlank() } == true) }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_package_snapshot_failed") { "Package tool snapshot unavailable: type=${throwable.safeLogType()}" }
                io.github.mangi.eta.agent.packages.PackageRunSnapshot.EMPTY
            }
            val dshSnapshot = runCatching { io.github.mangi.eta.agent.dsh.DshRunSnapshot.load(appContext, when {
                meetingId != null -> io.github.mangi.eta.data.model.ConversationSubject.meeting(meetingId).key
                conversationId != null -> io.github.mangi.eta.data.model.ConversationSubject.chat(conversationId).key
                else -> null
            }) }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_dsh_snapshot_failed") { "DSH tool snapshot unavailable: type=${throwable.safeLogType()}" }
                io.github.mangi.eta.agent.dsh.DshRunSnapshot.EMPTY
            }
            val externalTools = JSONArray().also { tools ->
                mcpSnapshot.appendModelTools(tools)
                apiSnapshot.appendModelTools(tools)
                a2aSnapshot.appendModelTools(tools)
                packageSnapshot.appendModelTools(tools)
                dshSnapshot.appendModelTools(tools)
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
                executionSelection = runBlocking(kotlinx.coroutines.Dispatchers.IO + RuntimeInvocationScope.coroutineContext()) {
                    runController.throwIfCancelled()
                    io.github.mangi.eta.agent.terminal.LinuxEnvironmentSettingsRepository.executionSelection(appContext)
                        .also { runController.throwIfCancelled() }
                },
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
                artifactSubjectType = when {
                    meetingId != null -> "meeting"
                    conversationId != null -> "conversation"
                    taskId != null -> "task"
                    else -> null
                },
                artifactSubjectId = meetingId ?: conversationId ?: taskId,
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
                projectWorkspace = projectWorkspace,
            )
            toolExecutor = executor
            val runHosts = io.github.mangi.eta.agent.capability.NexusRunHosts(NexusCapabilityDirectory.get(appContext),
                mcpSnapshot.bindings() + apiSnapshot.bindings(appContext) + a2aSnapshot.bindings(appContext) + packageSnapshot.bindings(appContext) + dshSnapshot.bindings(appContext),
                localHosts = listOf(executor.linuxHost(NexusCapabilityDirectory.get(appContext))),
                captureResult = { call, result -> io.github.mangi.eta.data.repository.RunArtifactCapture.externalResult(appContext, call, result,
                    when { meetingId != null -> "meeting"; conversationId != null -> "conversation"; taskId != null -> "task"; else -> null },
                    meetingId ?: conversationId ?: taskId) },
                beforeDispatch = { call ->
                    val code = when {
                        System.currentTimeMillis() >= grantExpiresAt() -> "GRANT_EXPIRED"
                        !currentRunToolPolicy().isAllowed(call.name) ->
                            "TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY"
                        authorizedModes[call.id]?.let { it != runBlocking { io.github.mangi.eta.agent.permission.AgentPermissionPolicy.effectiveMode(runController) } } == true ->
                            "APPROVAL_POLICY_CHANGED"
                        else -> null
                    }
                    code?.let { AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", it)
                        .put("message", "等待期间工具授权已变化，本次未执行").toString()) }
                })
            runController.bindHostSnapshot { JSONArray(runHosts.snapshot()) }
            val routingExecutor = RoutingToolExecutor(
                local = executor,
                providers = listOf(
                    runHosts,
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
            val learningTools = if (roleplayContext == null && LearningSettingsStore.isEnabled(appContext)) {
                AgentLearningTools(learningRepository, memoryScopes.map { it.key }.toSet(), learningFailureScope,
                    sourceRef = "run:${request.runId}", observedEvidence = {
                        val observations = experienceEvents.filterIsInstance<AgentEvent.ToolFinished>()
                            .filter { it.name !in AgentLearningTools.NAMES }.takeLast(5)
                        if (observations.isEmpty()) "用户原话（候选做法尚未验证）：${request.prompt.take(1000)}"
                        else observations.joinToString("\n") {
                            "tool:${it.name} call:${it.toolCallId} success:${it.success} effect_unknown:${it.effectUnknown}: ${it.resultSummary}"
                        }.take(1200)
                    })
            } else {
                null
            }
            var additionalToolSchemas = emptyMap<String, JSONObject>()
            fun authorizeToolCall(call: AgentModelClient.ToolCall, controller: AgentRunController = runController): AgentModelClient.ToolResult? {
                controller.throwIfCancelled()
                if (controller.approvalPolicy == null) controller.bindApprovalPolicy(approvalPolicy,
                    runController.approvalReviewer, approvalSession)
                if (call.name == "skills_read") {
                    val identifier = runCatching {
                        JSONObject(call.argumentsJson.ifBlank { "{}" }).optString("skillId")
                    }.getOrDefault("")
                    skillCoordination.blockFor(identifier)?.let { blocked ->
                        return AgentModelClient.ToolResult(
                            JSONObject()
                                .put("ok", false)
                                .put("code", blocked.code)
                                .put("message", blocked.message)
                                .toString(),
                        )
                    }
                }
                if (!runHosts.isAvailable(call.name)) return AgentModelClient.ToolResult(JSONObject().put("ok", false)
                    .put("code", "HOST_TOOL_UNAVAILABLE").put("message", "该外部工具已停用或连接配置已变化").toString())
                if (!currentRunToolPolicy().isAllowed(call.name)) {
                    return AgentModelClient.ToolResult(JSONObject().put("ok", false)
                        .put("code", "TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY").put("message", "工具不在本次运行权限范围内").toString())
                }
                val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
                    ?: return AgentModelClient.ToolResult(JSONObject().put("ok", false)
                        .put("code", "INVALID_ARGUMENT").put("message", "工具参数必须是 JSON object").toString())
                if (recoveredInput?.uncertainTools?.isNotEmpty() == true && call.name != "request_user_input" &&
                    runHosts.descriptor(call.name, arguments)?.effectClass != io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ) {
                    return AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "RECOVERY_EFFECT_VERIFICATION_REQUIRED")
                        .put("message", "中断批次含执行状态未知的操作，本次恢复仅核对资料和整理答复；需用户检查后另行明确下达操作，未重放副作用").toString())
                }
                // Execution-side hard stop: an inbound A2A call that already settled before an
                // owner approval must never run a second time, even with identical arguments.
                if (controller.isSettledA2ACall(
                        call.name,
                        runHosts.descriptor(call.name, arguments)?.effectClass
                            ?: io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(call.name),
                        io.github.mangi.eta.agent.capability.A2AToolCallIdentity.canonicalArgumentsDigest(call.argumentsJson),
                    )
                ) {
                    return AgentModelClient.ToolResult(
                        JSONObject()
                            .put("ok", false)
                            .put("code", "A2A_ALREADY_SETTLED")
                            .put("status", "already_settled")
                            .put("message", "该调用在本机审批前已经执行；为避免重复副作用，本次未再次执行。")
                            .toString(),
                    )
                }
                if (!runHosts.isAvailable(call.name, arguments)) return AgentModelClient.ToolResult(JSONObject().put("ok", false)
                    .put("code", "HOST_TOOL_UNAVAILABLE").put("message", "所选 Linux 环境未就绪、权限已变化或连接不可用，本次未执行").toString())
                val authorization = runBlocking(RuntimeInvocationScope.coroutineContext()) {
                    NexusExecutionGate.authorize(
                        context = appContext, request = request, toolName = call.name, arguments = arguments,
                        controller = controller, grantExpiresAt = grantExpiresAt(),
                        inputSchema = additionalToolSchemas[call.name],
                        toolDescriptor = runHosts.descriptor(call.name, arguments), toolHostId = runHosts.hostId(call.name, arguments),
                        workflowScopeId = conversationId ?: meetingId ?: taskId,
                    )
                }
                if (authorization is io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization.Denied) {
                    return AgentModelClient.ToolResult(JSONObject().put("ok", false)
                        .put("code", authorization.code).put("message", authorization.reason).toString())
                }
                controller.throwIfCancelled()
                if (selectedSkillPolicy?.isAllowed(call.name) == false) {
                    return AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "SKILL_POLICY_REVOKED").toString())
                }
                authorizedModes[call.id] = runBlocking { io.github.mangi.eta.agent.permission.AgentPermissionPolicy.effectiveMode(controller) }
                return null
            }
            val subagentExecutor = SubagentExecutor(
                conversationKey = browserKey,
                parentController = runController,
                promptScope = promptScope,
                configProvider = { profileName, explicitProviderId, explicitModelId, explicitReasoningEffort, estimatedInputTokens ->
                    val resolved = runBlocking {
                        RuntimeConfigRepository.subagentRuntimeConfig(
                            primaryConfig = if (request.operation == AgentRuntimeWire.OP_PACKAGE_ACTION)
                                requireNotNull(RuntimeConfigRepository.currentRuntimeConfig()) { "请先配置模型，插件模型调用未启动" } else request.config,
                            profileName = profileName,
                            explicitProviderId = explicitProviderId,
                            explicitModelId = explicitModelId,
                            explicitReasoningEffort = explicitReasoningEffort,
                            allowCandidateRouting = true,
                            estimatedInputTokens = estimatedInputTokens,
                        )
                    }
                    resolved.copy(
                        config = AgentRuntimePolicy.constrain(resolved.config, currentPermissions()),
                    )
                },
                capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                baseToolExecutor = AgentModelClient.ToolExecutor { call ->
                    routingExecutor.execute(call)
                },
                authorizeChildTool = { call, childController ->
                    runBudgetGuard.beforeChildToolCall(runController)
                    authorizeToolCall(call, childController)
                },
                childExecutionFactory = session::openChildExecution,
                skillContext = skillContext,
                memoryContext = memoryContext,
                memoryContextForTask = { query, window ->
                    if (roleplayContext != null) AgentMemoryContext.DISABLED else runBlocking {
                        io.github.mangi.eta.agent.memory.TaskMemoryContextLoader.build(appContext, memoryScopes, query, window)
                    }
                },
                mcpTools = externalTools,
                executionContext = executor.terminalExecutionContext,
                metricsScope = session.telemetry?.scope,
                inheritedToolPolicy = currentRunToolPolicy(),
                currentToolPolicy = ::currentRunToolPolicy,
                capabilityDirectory = runHosts,
                toolAvailable = runHosts::isAvailable,
                allowHighRiskTools = request.handoff?.source != "a2a",
                extensionsFactory = { child, native, model -> io.github.mangi.eta.agent.packages.PackageHookChain.load(appContext, child,
                    conversationId ?: meetingId, model, native = native,
                    readAllowed = { name, args -> runHosts.descriptor(name, args)?.effectClass == io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ }) },
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
            val subagentSettings = runBlocking { RuntimeConfigRepository.subagentSettings() }
            val subagentParallelism = subagentSettings.resolvedMaxParallelAgents
            val runTools = JSONArray(externalTools.toString()).also { tools ->
                if (subagentAllowed()) AgentSubagentToolCatalog.appendSchemas(tools, maxParallelAgents = subagentParallelism)
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
                if (learningTools != null) {
                    val schemas = AgentLearningTools.schemas()
                    for (index in 0 until schemas.length()) tools.put(schemas.getJSONObject(index))
                }
                if (memoryEnabled && roleplayContext == null) tools.put(MemorySearchTool.schema())
            }
            additionalToolSchemas = buildMap {
                for (index in 0 until runTools.length()) {
                    val function = runTools.getJSONObject(index).getJSONObject("function")
                    put(function.getString("name"), function.getJSONObject("parameters"))
                }
            }
            fun a2aToolEffect(call: AgentModelClient.ToolCall): io.github.zhh_lgtm.nexus.kernel.api.EffectClass {
                val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
                return args?.let { runHosts.descriptor(call.name, it)?.effectClass }
                    ?: io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(call.name)
            }
            fun a2aToolDigest(call: AgentModelClient.ToolCall): String =
                io.github.mangi.eta.agent.capability.A2AToolCallIdentity.canonicalArgumentsDigest(call.argumentsJson)
            fun recordSettledA2AToolCall(call: AgentModelClient.ToolCall) {
                if (!runController.isRemoteA2AInbound) return
                // Any returned ToolResult means the executor was entered. Failures and unknown
                // results can still have changed state, so they count as settled too. The call
                // waiting at the approval gate throws before returning and is never recorded.
                runController.recordSettledA2AToolCall(call.name, a2aToolEffect(call), a2aToolDigest(call))
            }
            fun executeRoutedToolCall(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                val denied = authorizeToolCall(call)
                packageHooks.permissionChecked(call, denied == null)
                if (denied == null) packageHooks.toolStarting(call)
                if (denied != null) return denied
                if (call.name in AgentSubagentToolCatalog.names) {
                    return if (!subagentAllowed()) {
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
                }
                if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    return historyTool.execute(call)
                }
                if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    return characterMemoryTools.execute(call)
                }
                if (call.name in AgentLearningTools.NAMES) {
                    return learningTools?.execute(call) ?: AgentModelClient.ToolResult(
                        JSONObject().put("ok", false).put("code", "LEARNING_DISABLED")
                            .put("message", "当前会话不允许访问现实经验库").toString())
                }
                if (call.name == "memory_search" && roleplayContext == null && memoryEnabled) {
                    return memorySearch.execute(call)
                }
                return routingExecutor.execute(call).also { result ->
                    if (call.name == "skills_read") runCatching { JSONObject(result.content) }.getOrNull()
                        ?.takeIf { it.optBoolean("ok") }?.optString("id")?.takeIf(String::isNotBlank)?.let { id ->
                            usedSkillIds.add(id)
                            val recipe = runBlocking { io.github.mangi.eta.agent.skill.SkillEvolution.runtimeRecipe(appContext, id) }
                            val declaredTools = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                skillIndexService.findInstalledSkill(id)
                            }?.declaration?.allowedTools.orEmpty()
                            val allowedTools = (
                                recipe?.tools?.lines()?.filter(String::isNotBlank).orEmpty() +
                                    declaredTools
                                ).distinct()
                            if (allowedTools.isNotEmpty()) {
                                val skillPolicy = AgentToolPolicy(
                                    allowedTools + SkillCoordination.controlTools(),
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
            val routedRunToolExecutor = AgentModelClient.ToolExecutor { call ->
                try {
                    executeRoutedToolCall(call).also {
                        recordSettledA2AToolCall(call)
                        val payload = runCatching { JSONObject(it.content) }.getOrNull()
                        approvalStore.executionResult(canonicalRun.runId.value, call.name, a2aToolDigest(call),
                            if (payload?.optBoolean("ok", true) == false) payload.optString("code", "failed") else "returned")
                    }
                } catch (pause: AgentRunPauseException) {
                    // The approval gate aborts before the pending call starts. This is the only
                    // executor exception that proves the call was not already in flight.
                    throw pause
                } catch (failure: Throwable) {
                    // A thrown executor may have performed an external effect before returning.
                    // Preserve it in the local settle ledger before the AgentLoop converts it to a
                    // tool error and lets the model continue.
                    recordSettledA2AToolCall(call)
                    runCatching { approvalStore.executionResult(canonicalRun.runId.value, call.name, a2aToolDigest(call), "uncertain_or_failed") }
                    throw failure
                }
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                RuntimeInvocationScope.withController(runController) {
                    RuntimeInvocationScope.withNestedTools(AgentModelClient.ToolExecutor { nested ->
                        runBudgetGuard.beforeToolCall()
                        routedRunToolExecutor.execute(nested)
                    }) {
                        packageHooks.executeTool(call) { routedRunToolExecutor.execute(call) }
                    }
                }
            }
            if (request.operation == AgentRuntimeWire.OP_CHAT) packageHooks = io.github.mangi.eta.agent.packages.PackageHookChain.load(appContext,
                runController, conversationId ?: meetingId, request.config.model,
                native = AgentModelClient.ToolExecutor { call ->
                    runBudgetGuard.beforeToolCall()
                    io.github.mangi.eta.agent.packages.PackageHookToolReceipts.execute(call, runToolExecutor) { event ->
                        acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
                    }
                },
                readAllowed = { name, args -> runHosts.descriptor(name, args)?.effectClass == io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ })
            val lazyToolCatalog = if (
                request.config.supportsTools && !request.config.isEmbeddedLocalTextModel()
            ) {
                NexusLazyToolCatalog(
                    directory = runHosts,
                    toolAllowed = { toolName ->
                        currentRunToolPolicy().isAllowed(toolName) && runHosts.isAvailable(toolName)
                    },
                    discoveryAllowed = {
                        currentRunToolPolicy().allowsToolDiscovery(NexusToolDiscoveryCatalog.NAMES)
                    },
                    authorize = { descriptor ->
                        NexusCapabilityPolicyAdapter.authorize(
                            descriptor = descriptor,
                            capabilities = AgentToolCapabilities.capture(appContext),
                            taskId = canonicalRun.runId.value,
                            highRiskApproved = request.handoff?.source != "a2a",
                            validUntil = grantExpiresAt(),
                            resolvedHostId = runHosts.hostId(descriptor.id),
                        )
                    },
                    additionalSchemas = runTools,
                    validUntil = grantExpiresAt(),
                    validUntilProvider = ::grantExpiresAt,
                )
            } else {
                null
            }
            val fallbackProgress = ModelFallbackProgress()
            val compactionConfig = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val meetingRoomId = request.promptScope.meetingRoomId.takeIf(String::isNotBlank)
                val conversationId = request.promptScope.conversationId.takeIf(String::isNotBlank)
                    ?: request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                        ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
                        ?.takeIf(String::isNotBlank)
                val scopedBinding = meetingRoomId?.let {
                    io.github.mangi.eta.fork.meeting.MeetingRepository.roomCompactionBinding(appContext, it)
                } ?: conversationId?.let {
                    io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
                        .compactionBinding(appContext, it)
                }
                RuntimeConfigRepository.compactionRuntimeConfig(request.config, scopedBinding)
            }
            val fallbackToolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(
                    JSONObject().put("ok", false).put("code", "FALLBACK_TOOLS_DISABLED")
                        .put("message", "备用模型仅提供文字答复，不执行工具").toString(),
                )
            }
            fun completeWith(config: AgentModelClient.ModelConfig, fallback: Boolean = false): AgentModelClient.ModelResponse.Text {
                recoveredInput?.takeIf { it.mcpContinuationId != null }?.let { recovery ->
                    val continuationId = requireNotNull(recovery.mcpContinuationId)
                    val origin = recovery.mcpOriginRunId ?: recovery.originalRunId
                    val originalCall = io.github.mangi.eta.agent.mcp.McpContinuationRecovery.recoveryCall(
                        appContext, continuationId, origin, recovery.toolCallId)
                    check(originalCall.id == recovery.toolCallId && originalCall.name == recovery.toolName) { "MCP续传工具归属不匹配" }
                    val blocked = authorizeToolCall(originalCall, runController)
                    check(blocked == null) { "MCP续传未获得当前工具授权，未发送续传请求；已保存用户回答" }
                    runBudgetGuard.beforeToolCall()
                    acceptEvent(session, AgentEvent.ToolStarted(1, originalCall.id, originalCall.name, "从已保存的MCP请求状态续传"),
                        archivedEvents, entrySurfaceGuard, checkpointRecorder)
                    val actual = RuntimeInvocationScope.withController(runController) {
                        RuntimeInvocationScope.withToolInvocation(RuntimeInvocationScope.ToolInvocation(originalCall.id, originalCall.name,
                            config.providerId, config.model, AgentConversationCodec.encodeTranscriptForStorage(recovery.messages))) {
                            val resumed = io.github.mangi.eta.agent.mcp.McpContinuationRecovery.resume(appContext,
                                continuationId, origin, recovery.toolCallId, requireNotNull(recovery.mcpInputRequestId),
                                recovery.mcpInputPage, recovery.answerResult)
                            io.github.mangi.eta.data.repository.RunArtifactCapture.externalResult(appContext, originalCall, resumed,
                                when { meetingId != null -> "meeting"; conversationId != null -> "conversation"; taskId != null -> "task"; else -> null },
                                meetingId ?: conversationId ?: taskId)
                        }
                    }
                    var continued = io.github.mangi.eta.agent.model.AgentToolBatchRecovery.completeInterrupted(recovery.messages +
                        AgentModelClient.ConversationMessage("tool", actual.content, toolCallId = recovery.toolCallId))
                    if (actual.images.isNotEmpty() && config.supportsImageInput) continued = continued +
                        AgentConversationCodec.fromJsonObject(AgentConversationCodec.userMessage("MCP续传返回的真实工具观察图片", actual.images))
                    recoveredInput = recovery.copy(messages = continued, mcpContinuationId = null)
                    acceptEvent(session, AgentEvent.ToolFinished(1, originalCall.id, originalCall.name,
                        "MCP续传已返回真实工具结果", actual.images.size, actual.images.sumOf { it.bytes },
                        success = runCatching { JSONObject(actual.content).optBoolean("ok", true) }.getOrDefault(true),
                        artifacts = actual.artifacts), archivedEvents, entrySurfaceGuard, checkpointRecorder)
                }
                session.telemetry?.modelAttempt(config)
                return AgentModelClient.complete(
                    config = config,
                    compactionConfig = compactionConfig,
                    metricsScope = session.telemetry?.scope,
                    sessionId = request.effectiveModelSessionId,
                    operationId = request.runId,
                    initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                    initialSupplementIndex = uiPayload?.lastSupplementIndex ?: request.handoff?.payload
                        ?.let { AgentExternalArchivePayload.from(it)?.supplements?.maxOfOrNull { supplement -> supplement.index } } ?: 0,
                    roleplayContext = roleplayContext,
                    rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                    compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                    compactionSelection = request.compactionSelection,
                    onContextSnapshot = { snapshot ->
                        // 压缩会把历史里的记忆写入摘要掉，下一轮需要重新注入最新记忆。
                        ConversationMemoryPins.forget(conversationId)
                        val committed = linkContext(snapshot, request, conversationId, workflowScopeId)
                        AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                        session.updateContext(committed)
                    },
                    onTranscript = { transcript ->
                        AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                        session.updateTranscript(transcript)
                    },
                    capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                    workflowModeInstruction = workflowModeInstruction,
                    prompt = if (recoveredInput != null) "" else request.prompt,
                    assistantScreenContext = request.assistantScreenContext,
                    toolExecutor = if (fallback) fallbackToolExecutor else runToolExecutor,
                    images = request.images,
                    history = recoveredInput?.messages ?: request.history,
                    runController = runController,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    additionalTools = if (fallback) JSONArray() else runTools,
                    lazyToolCatalog = lazyToolCatalog.takeUnless { fallback },
                    toolFilter = if (!fallback) { schemas ->
                        currentRunToolPolicy().filterSchemas(schemas).let { parentFiltered ->
                            val selected = (selectedSkillPolicy ?: AgentToolPolicy.UNRESTRICTED).filterSchemas(parentFiltered)
                            JSONArray().also { visible -> for (i in 0 until selected.length()) {
                                val schema = selected.getJSONObject(i)
                                if (runHosts.isAvailable(schema.getJSONObject("function").getString("name"))) visible.put(schema)
                            } }
                        }
                    } else { _: JSONArray -> JSONArray() },
                    executionContext = executor.terminalExecutionContext,
                    initialRound = if (fallback) fallbackProgress.lastRound + 1 else 1,
                    textOnlyFallback = fallback,
                    resumeToolResult = recoveredInput != null,
                    promptScope = promptScope,
                    runBudget = canonicalRun.budget,
                    budgetGuard = runBudgetGuard,
                    extensions = if (fallback) AgentRunExtensions.NONE else packageHooks,
                    maxParallelAgents = subagentParallelism,
                    captureModelMedia = { round, files ->
                        RuntimeInvocationScope.withController(runController) {
                            io.github.mangi.eta.data.repository.RunArtifactCapture.modelResult(appContext, request.runId, round, files,
                                when { meetingId != null -> "meeting"; conversationId != null -> "conversation"; taskId != null -> "task"; else -> null },
                                meetingId ?: conversationId ?: taskId)
                        }
                    },
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
                if (request.operation == AgentRuntimeWire.OP_PACKAGE_ACTION) RuntimePackageActions.execute(request, runToolExecutor,
                    runBudgetGuard, runController) { event -> acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder) }
                else completeWith(request.config)
            } catch (primaryFailure: AgentModelExecutionException) {
                val failureKind = AgentModelFallbackPolicy.classify(primaryFailure.cause)
                val canRestart = fallbackProgress.canRestart && primaryFailure.transcript.isEmpty()
                val isChat = request.operation == AgentRuntimeWire.OP_CHAT && request.handoff?.source != AgentRuntimeWire.MEETING_HANDOFF_SOURCE
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
                contextSnapshot = completedResponse.contextSnapshot
                    ?.let { linkContext(it, request, conversationId, workflowScopeId) },
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            ConversationMemoryPins.forget(memoryConversationId)
            cancelled = runController.abortFailure == null && (runController.isCancelled || throwable is AgentRunCancelledException)
            val modelFailure = throwable as? AgentModelExecutionException
            // try 内的会话标识在 catch 作用域不可见；UI 入口可由 handoff 重建同一身份。
            val failureScopeId = memoryConversationId
                ?: request.handoff
                    ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                    ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
                    ?.takeIf { it.isNotBlank() }
            val pause = (runController.abortFailure as? AgentRunPauseException)?.pause
                ?: (throwable as? AgentRunPauseException)?.pause
            val message = if (cancelled) {
                "已停止"
            } else {
                pause?.let { (runController.abortFailure as? AgentRunPauseException)?.message } ?: throwable.message
                    ?: throwable.javaClass.simpleName
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
                if (learningFailureCaptureAllowed && LearningSettingsStore.automaticCaptureEnabled(appContext)) {
                    runCatching {
                        runBlocking {
                            LearningRepository.get(appContext).recordFailure(
                                errorCode = requestFailure?.code ?: throwable.javaClass.simpleName,
                                message = requestFailure?.message ?: throwable.message.orEmpty(),
                                sourceRef = "run:${request.runId}",
                                scope = learningFailureScope,
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
                failureCode = pause?.let { "A2A_${it.kind}" } ?: (runController.abortFailure as? AgentModelFailure)?.code ?: (modelFailure?.cause as? AgentModelFailure)?.code ?: (throwable as? AgentModelFailure)?.code,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                // A pause ends the run before an answer exists, but the entry surface still needs
                // the incremental messages already produced by this turn. The transcript is local
                // runtime state; callers decide how to store it and never publish it to a peer.
                transcript = modelFailure?.transcript?.takeIf { it.isNotEmpty() }
                    ?: session.transcript,
                contextSnapshot = modelFailure?.contextSnapshot
                    ?.let { linkContext(it, request, failureScopeId, failureScopeId) } ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
                pause = pause,
            )
        } finally {
            if (recoveredInput != null) runCatching { RecoveredRunInputStore(appContext).use { it.finish(request.runId, response != null) } }
            val usedSkillsSucceeded = io.github.mangi.eta.agent.skill.SkillRunQuality.outcome(response != null,
                runController.isCancelled, experienceEvents)
            runCatching {
                io.github.mangi.eta.agent.skill.SkillUsageStore(appContext).recordRun(
                    skillIds = usedSkillIds,
                    successful = usedSkillsSucceeded,
                    runId = request.runId,
                )
            }
            runCatching { runBlocking { io.github.mangi.eta.agent.skill.SkillEvolution.recordUse(appContext, request.runId, usedSkillIds.toList(),
                usedSkillsSucceeded) } }
            MemoryAutomationCapture.activeRuns.decrementAndGet()
            runCatching { MemoryAutomationSettingsStore.touch(appContext) }
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
            runCatching { executionDeadline?.close() }
            runCatching { packageHooks.close() }
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
        val committed = session.complete(result) { publishResult ->
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, publishResult, archivedEvents.orEmpty()) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = session.terminalResult ?: result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed && session.terminalResult?.ok == true },
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
        if (event is AgentEvent.PluginUiFrame) { session.emit(event); return }
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

    /**
     * 四层上下文关联只写标识与游标；作用域无法确定时不写关联，恢复时沿用旧的安全策略。
     * 事件、摘要与工作集正文仍存于原有 store，本链接不复制数据。
     */
    private fun linkContext(
        snapshot: AgentContextSnapshot,
        request: AgentRuntimeWire.RunRequest,
        conversationId: String?,
        workflowScopeId: String?,
    ): AgentContextSnapshot {
        val linked = snapshot.copy(operationId = request.runId)
        val scope = workflowScopeId?.takeIf { it.isNotBlank() } ?: return linked
        val workflow = runCatching {
            io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore.get(appContext).snapshot(scope)
        }.getOrNull() ?: return linked
        return linked.copy(
            link = AgentContextLink.create(
                runId = request.runId,
                ledgerRunId = NexusRunMapper.canonicalRunId(request.runId).value,
                conversationId = conversationId?.takeIf { it.isNotBlank() } ?: scope,
                scopeId = scope,
                compactedThrough = snapshot.coveredUserTurns,
                messages = snapshot.messages,
                checkpointRef = AgentContextLink.runtimeCheckpointRef(request.runId),
                mode = workflow.mode.name,
                goalId = workflow.goal?.goalId?.value,
                goalRevision = workflow.goal?.revision ?: 0,
                planRevision = workflow.plan?.revision ?: 0,
            ),
        )
    }

    private fun executeModelTask(session: AgentRuntimeSession, request: AgentRuntimeWire.RunRequest): Outcome {
        val controller = session.controller
        var response: AgentModelClient.ModelResponse.Text? = null
        val result = try {
            val kind = RuntimeModelTasks.kind(request)
            require(request.history.isEmpty()) { "MODEL_TASK_HISTORY_NOT_ALLOWED" }
            require(request.images.isEmpty() || kind == RuntimeModelTaskKind.NOTEPAD && request.config.supportsImageInput && request.images.size <= 4) { "MODEL_TASK_IMAGES_NOT_ALLOWED" }
            require(request.prompt.length in 1..64_000 && request.config.systemPrompt.length in 1..16_000) { "MODEL_TASK_INPUT_INVALID" }
            controller.throwIfCancelled()
            session.beginExecution()
            session.prepareInput(request)
            val config = RuntimeModelTasks.restrictedConfig(request.config, request.config.systemPrompt)
            val budget = (session.executionRequest?.budget ?: NexusRunMapper.fromLegacy(request).request.budget)
                .intersect(io.github.zhh_lgtm.nexus.kernel.api.RunBudget(hardDurationMs = kind.timeoutMs))
            val guard = AgentRunBudgetGuard(budget)
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", config.systemPrompt))
                .put(if (request.images.isEmpty()) JSONObject().put("role", "user").put("content", request.prompt)
                    else AgentConversationCodec.userMessage(request.prompt, request.images))
            val transcript = JSONArray()
            fun event(value: AgentEvent) { acceptEvent(session, value, null, null, null) }
            session.telemetry?.modelAttempt(config)
            event(AgentEvent.RunStarted(0, 0, 0, false))
            val loop = io.github.mangi.eta.agent.model.AgentLoop(config, messages, JSONArray(),
                io.github.mangi.eta.agent.model.BudgetedAgentProvider.wrap(modelTaskProvider(config)),
                AgentModelClient.ToolExecutor { error("MODEL_TASK_TOOLS_FORBIDDEN") }, controller,
                io.github.mangi.eta.agent.model.AgentTraceFormatter(), ::event,
                sessionId = request.effectiveModelSessionId, transcript = transcript, systemCount = 1, operationId = request.runId,
                onContextSnapshot = session::updateContext, onTranscript = session::updateTranscript,
                purpose = kind.purpose, metricsScope = session.telemetry?.scope, budgetGuard = guard)
            val completed = guard.watch(controller).use { loop.run().also { guard.checkDuration(); controller.throwIfCancelled() } }
            response = AgentModelClient.ModelResponse.Text(completed.content, contextSnapshot = loop.contextSnapshot(),
                reasoningContent = completed.reasoningContent,
                transcript = AgentConversationCodec.transcript(transcript, 0, completed.sensitiveToolCallIds))
            AgentRuntimeWire.RunResult(request.runId, true, completed.content, reasoningContent = completed.reasoningContent,
                transcript = response.transcript, contextSnapshot = response.contextSnapshot, operation = request.operation)
        } catch (failure: Exception) {
            val cancelled = controller.isUserCancelled || failure is AgentRunCancelledException
            val error = if (cancelled) "已停止" else failure.message ?: "MODEL_TASK_FAILED"
            if (!cancelled) runCatching { acceptEvent(session, AgentEvent.RunFailed(error), null, null, null) }
            AgentRuntimeWire.RunResult(request.runId, false, "", error, transcript = session.transcript,
                failureCode = (controller.abortFailure as? AgentModelFailure)?.code ?: (failure as? AgentModelFailure)?.code,
                contextSnapshot = session.contextSnapshot, operation = request.operation)
        }
        if (session.isTerminal) return Outcome(session.terminalResult ?: result, null, shouldUpdateHost = false)
        val committed = session.complete(result) { outcome ->
            runCatching { persistArtifacts(request, outcome, emptyList()) }.onFailure {
                AndroidAgentLogger.error("Model task result persistence failed: type=${it.safeLogType()}")
            }
        }
        return Outcome(session.terminalResult ?: result, null, completedRequest = request.takeIf { committed },
            response = response.takeIf { committed && session.terminalResult?.ok == true }, shouldUpdateHost = false)
    }
}
