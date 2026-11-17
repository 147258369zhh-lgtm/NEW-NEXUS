package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.ResolvedPromptInjection
import io.github.mangi.eta.agent.prompt.PromptRunScope
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.capability.NexusLazyToolCatalog
import io.github.mangi.eta.agent.capability.NexusToolDescriptors
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderEndpoint
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val traceFormatter = AgentTraceFormatter()

    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                    deviceSensitiveReadTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                    deviceSensitiveActionTools =
                        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                    autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
                    subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        compactionConfig: ModelConfig = config,
        compactionProvider: AgentProviderClient? = null,
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        additionalTools: JSONArray = JSONArray(),
        executionContext: TerminalExecutionContext? = null,
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        lazyToolCatalog: NexusLazyToolCatalog? = null,
        workflowModeInstruction: String? = null,
        sessionId: String = java.util.UUID.randomUUID().toString(),
        compactOnly: Boolean = false,
        compactionSelection: IntRange? = null,
        operationId: String = sessionId,
        initialUserMessageId: String = "user-$operationId",
        initialSupplementIndex: Int = 0,
        roleplayContext: RoleplayRunContext? = null,
        rewriteReply: Boolean = false,
        assistantScreenContext: String = "",
        toolFilter: ((JSONArray) -> JSONArray)? = null,
        onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
        onTranscript: (List<ConversationMessage>) -> Unit = {},
        initialRound: Int = 1,
        textOnlyFallback: Boolean = false,
        resumeToolResult: Boolean = false,
        metricsScope: io.github.mangi.eta.agent.runtime.AgentMetricScope? = null,
        promptScope: PromptRunScope = PromptRunScope(),
        runBudget: RunBudget = AgentRunBudgets.default(),
        budgetGuard: AgentRunBudgetGuard = AgentRunBudgetGuard(runBudget, initialRound,
            approveSoftLimit = { io.github.mangi.eta.agent.runtime.RunBudgetConfirmation.await(runController, it) },
            approveHardLimit = { io.github.mangi.eta.agent.runtime.RunBudgetConfirmation.await(runController, it) }),
        extensions: AgentRunExtensions = AgentRunExtensions.NONE,
        maxParallelAgents: Int = io.github.mangi.eta.data.model.SubagentSettings.DEFAULT_MAX_PARALLEL_AGENTS,
        captureModelMedia: (Int, List<ToolFile>) -> List<io.github.mangi.eta.agent.runtime.AgentArtifactReference> = { _, _ -> emptyList() },
        onEvent: (AgentEvent) -> Unit = {}
    ): ModelResponse.Text {
        val provider = BudgetedAgentProvider.wrap(provider)
        // Signed tool responses must keep their original catalogue until the chain ends.
        // A catalogue containing only discovery tools would otherwise stay locked even
        // after tool_activate succeeds. Publish the policy-filtered schemas up front.
        val useLazyDiscovery = lazyToolCatalog != null && provider.capabilities.endpoint !in setOf(
            EndpointKind.ANTHROPIC_MESSAGES, EndpointKind.GEMINI_INTERACTIONS,
        )
        if (useLazyDiscovery && !compactOnly && !rewriteReply && !textOnlyFallback && config.supportsTools) {
            lazyToolCatalog?.restoreUsedTools(history)
        }
        val effectiveCompactionProvider = compactionProvider ?: if (
            config.providerId == compactionConfig.providerId &&
                config.baseUrl == compactionConfig.baseUrl &&
                config.authMode == compactionConfig.authMode &&
                config.model == compactionConfig.model
        ) {
            provider
        } else {
            ProviderClientFactory.getClient(compactionConfig)
        }
        if (runBudget.costLimitMicros != null && runController.modelBudget == null) {
            throw AgentModelFailure("MODEL_COST_BUDGET_OWNER_REQUIRED", false, "费用预算缺少可靠执行记录，本次请求未启动。")
        }
        val webSchema = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "web_search")))
        var config = if (config.hostedWebSearchEnabled && (!io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load().isAllowed("web_search") ||
                toolFilter?.invoke(webSchema)?.length() == 0)) config.copy(hostedWebSearchEnabled = false) else config
        config.validate()
        if (images.isNotEmpty() && !config.supportsImageInput) {
            throw AgentModelFailure(
                code = "MODEL_IMAGE_INPUT_UNSUPPORTED",
                retryable = false,
                message = "当前模型未声明图片输入能力。请在模型设置中启用“图片输入”，或切换到支持视觉的模型。",
            )
        }
        val prompt = if (compactOnly || rewriteReply || textOnlyFallback) prompt else extensions.input(prompt, images.size)
        // 这里已经拿到本次请求最终生效的模型；提示词注入在本次 complete 内只解析一次，
        // 主模型、备用模型与子 Agent 各自用自己实际生效的配置冻结同一份结果。
        val promptInjection = if (compactOnly) ResolvedPromptInjection()
        else resolvePromptInjection(config, promptScope, roleplayContext)
        require(initialRound > 0)
        val initialCapabilities = capabilitiesProvider()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            images,
            history,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            roleplayContext = roleplayContext,
            executionContext = executionContext,
            promptInjection = promptInjection,
            lazyToolDiscovery = useLazyDiscovery && lazyToolCatalog?.enabled() == true,
            workflowModeInstruction = workflowModeInstruction,
            resumeToolResult = resumeToolResult,
        )
        val currentUserIndex = (0 until messages.length()).last { index ->
            messages.optJSONObject(index)?.let { it.optString("role") == "user" && !it.optBoolean(io.github.mangi.eta.agent.prompt.PROMPT_INJECTION_MARKER) } == true
        }
        if (rewriteReply) {
            messages.put(currentUserIndex, AgentConversationCodec.userTextMessage(
                "请只改写下面这条角色回复，保持已有事实与实际工具结果，以当前角色设定改善表达。" +
                    "这不是重新执行任务；不得调用任何工具、重读设备、更新记忆或编造缺失证据。只输出替代正文。\n" +
                    "<reply_to_rewrite>\n$prompt\n</reply_to_rewrite>",
            ))
        } else if (!compactOnly && !resumeToolResult) {
            messages.getJSONObject(currentUserIndex).put("_eta_message_id", initialUserMessageId)
            AssistantScreenContextProjection.attach(messages.getJSONObject(currentUserIndex), assistantScreenContext)
        }
        if (!compactOnly && !resumeToolResult && config.providerType == ProviderTypes.GEMINI) {
            GeminiEphemeralState.markRunStart(messages.getJSONObject(currentUserIndex))
        }
        if (compactOnly || resumeToolResult) messages.remove(currentUserIndex)
        val transcript = JSONArray()
        // 旧 history 中的无效消息可能在组装时被跳过，系统边界不能由 history 条数倒推。
        val systemCount = AgentPromptBuilder.buildSystemMessages(
            config, skillContext, memoryContext, initialCapabilities.rootAvailable, roleplayContext,
            executionContext, taskPrompt = prompt, promptInjection = promptInjection,
            lazyToolDiscovery = useLazyDiscovery && lazyToolCatalog?.enabled() == true,
            workflowModeInstruction = workflowModeInstruction,
        ).length()
        val cloudToolsEnabled = runCatching {
            kotlinx.coroutines.runBlocking {
                val context = io.github.mangi.eta.data.datastore.SettingsDataStore.applicationContext()
                val cloud = io.github.mangi.eta.data.repository.CloudToolsSettingsRepository.resolveRuntimeConfig(context)
                cloud.settings.enabled || io.github.mangi.eta.agent.cloud.CloudToolAvailability
                    .isAvailable(AgentCloudToolCatalog.MEDIA, cloud)
            }
        }.getOrDefault(false)
        fun toolsFor(capabilities: AgentToolCapabilities): JSONArray {
            if (rewriteReply || textOnlyFallback || !config.supportsTools) return JSONArray()
            val fullTools = AgentToolCatalog.build(
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                deviceDirectTools = config.deviceDirectTools,
                deviceSensitiveReadTools = config.deviceSensitiveReadTools,
                deviceSensitiveActionTools = config.deviceSensitiveActionTools,
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryContext.enabled,
                memoryWritable = roleplayContext == null,
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
                cloudTools = cloudToolsEnabled,
            )
            var candidateTools = if (useLazyDiscovery) checkNotNull(lazyToolCatalog).localSchemas(fullTools) else fullTools
            if (!useLazyDiscovery) {
                for (index in 0 until additionalTools.length()) {
                    candidateTools.put(additionalTools.opt(index))
                }
                candidateTools = lazyToolCatalog?.authorizedSchemas(candidateTools) ?: candidateTools
            }
            val policyFiltered = toolFilter?.invoke(candidateTools) ?: candidateTools
            val withDiscovery = if (useLazyDiscovery) checkNotNull(lazyToolCatalog).appendDiscoverySchemas(policyFiltered) else policyFiltered
            val boundaryFiltered = io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load()
                .filterSchemas(withDiscovery)
            return AgentToolSchema.sanitizeFunctionCatalog(boundaryFiltered)
        }
        val tools = if (compactOnly || rewriteReply || textOnlyFallback) toolsFor(initialCapabilities)
            else extensions.prepare(messages, systemCount, toolsFor(initialCapabilities))
        val auditedProvider = object : AgentProviderClient by provider {
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (!compactOnly) runCatching {
                    io.github.mangi.eta.agent.prompt.PromptAssemblyAuditStore.record(operationId, promptScope, request, promptInjection)
                }
                return provider.complete(request, runController, onEvent)
            }
        }
        if (config.hostedWebSearchEnabled && extensions.tools(webSchema).length() == 0) config = config.copy(hostedWebSearchEnabled = false)
        val emitEvent: (AgentEvent) -> Unit = { event ->
            onEvent(event)
            if (!compactOnly && !rewriteReply && !textOnlyFallback) extensions.event(event)
        }
        emitEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        if (!compactOnly && !rewriteReply && !textOnlyFallback) {
            var processedTranscript = emptyList<ConversationMessage>()
            val processedStream = StringBuilder()
            var processorBlockStarted = false
            fun processorDelta(chunk: String) {
                if (chunk.isEmpty()) return
                budgetGuard.checkDuration(); runController.throwIfStopped()
                require(processedStream.length.toLong() + chunk.length <= 256_000) { "PACKAGE_MESSAGE_OUTPUT_LIMIT" }
                if (!processorBlockStarted) {
                    // Isolated processor output arrives on a Binder callback thread. Do not reenter its locked hook chain here.
                    onEvent(AgentEvent.RoundStarted(initialRound, messages.length()))
                    onEvent(AgentEvent.AssistantBlockStart(initialRound, AgentEvent.AssistantBlockKind.TEXT, 0))
                    processorBlockStarted = true
                }
                processedStream.append(chunk)
                onEvent(AgentEvent.AssistantBlockDelta(initialRound, AgentEvent.AssistantBlockKind.TEXT, 0, chunk.length, chunk))
            }
            try {
                val processed = budgetGuard.watch(runController).use {
                    extensions.processStreaming(messages, systemCount, config.knownContextWindow, ::processorDelta).also {
                        budgetGuard.checkDuration(); runController.throwIfCancelled()
                    }
                }
                if (processed != null) {
                    val text = processed.text
                    if (!processorBlockStarted) processed.chunks.forEach(::processorDelta)
                    else if (text.startsWith(processedStream.toString())) processorDelta(text.substring(processedStream.length))
                    extensions.event(AgentEvent.AssistantBlockStart(initialRound, AgentEvent.AssistantBlockKind.TEXT, 0))
                    val completed = listOf(ConversationMessage(role = "assistant", content = text))
                    processedTranscript = completed
                    onTranscript(completed)
                    emitEvent(AgentEvent.AssistantBlockEnd(initialRound, AgentEvent.AssistantBlockKind.TEXT, 0, contentChars = text.length,
                        replacementContent = text.takeIf { it != processedStream.toString() }))
                    emitEvent(AgentEvent.AssistantReceived(initialRound, text.length, "", emptyList()))
                    extensions.finished(true)
                    budgetGuard.checkDuration(); runController.throwIfCancelled()
                    return ModelResponse.Text(content = text, transcript = completed)
                }
                check(!processorBlockStarted) { "消息处理插件输出后不能撤销匹配" }
            } catch (failure: Throwable) {
                if (processedTranscript.isEmpty() && processedStream.isNotEmpty()) processedTranscript = listOf(ConversationMessage("assistant", processedStream.toString()))
                extensions.finished(false)
                throw AgentModelExecutionException(cause = runController.abortFailure ?: failure, contextSnapshot = null,
                    reasoningContent = "", transcript = processedTranscript)
            }
        }
        // 工具目录与系统提示都是请求前缀，按轮重算会让一次瞬时状态抖动废掉整段缓存。
        // Root 授权真正变化时整体重建；其余能力只增不减：短暂不可用的工具仍保留，执行时自行报告错误码。
        var promptRootAvailable = initialCapabilities.rootAvailable
        var publishedTools = tools
        fun toolsForRound(): JSONArray {
            val capabilities = capabilitiesProvider()
            if (capabilities.rootAvailable != promptRootAvailable) {
                val systemMessages = AgentPromptBuilder.buildSystemMessages(
                    config, skillContext, memoryContext, capabilities.rootAvailable, roleplayContext,
                    executionContext, taskPrompt = prompt, promptInjection = promptInjection,
                    lazyToolDiscovery = useLazyDiscovery && lazyToolCatalog?.enabled() == true,
                    workflowModeInstruction = workflowModeInstruction,
                )
                for (index in 0 until systemMessages.length()) {
                    messages.put(index, systemMessages.getJSONObject(index))
                }
                promptRootAvailable = capabilities.rootAvailable
                publishedTools = extensions.prepare(messages, systemCount, toolsFor(capabilities))
                return publishedTools
            }
            if (lazyToolCatalog != null) {
                publishedTools = extensions.tools(toolsFor(capabilities))
                return publishedTools
            }
            val published = toolNames(publishedTools)
            val added = toolsFor(capabilities).let { fresh ->
                (0 until fresh.length()).map(fresh::getJSONObject).filter { toolName(it) !in published }
            }
            if (added.isNotEmpty()) {
                publishedTools = JSONArray(publishedTools.toString()).also { merged -> added.forEach(merged::put) }
            }
            publishedTools = AgentToolSchema.sanitizeFunctionCatalog(
                io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load()
                    .filterSchemas(toolFilter?.invoke(publishedTools) ?: publishedTools),
            )
            return extensions.tools(publishedTools)
        }
        val loop = AgentLoop(
            transcript = transcript,
            systemCount = systemCount,
            operationId = operationId,
            onContextSnapshot = if (rewriteReply) ({ _ -> }) else onContextSnapshot,
            onTranscript = onTranscript,
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = auditedProvider,
            // 目录在运行内只增不减，可见不再等于可用：执行前用同一规则复核，
            // 中途失去的能力返回与目录过滤一致的错误码，而不是让底层接口给出含糊的失败。
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (!io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load().isAllowed(call.name)) {
                    return@ToolExecutor ToolResult(JSONObject().put("ok", false).put("code", "GLOBAL_TOOL_BOUNDARY_DENIED")
                        .put("message", "该工具已在全局权限边界中关闭").toString())
                }
                val lazyResult = lazyToolCatalog?.execute(
                    call.name,
                    JSONObject(call.argumentsJson.ifBlank { "{}" }),
                )
                if (lazyResult != null) return@ToolExecutor ToolResult(lazyResult.toString())
                val unavailable = AgentToolRequirements.find(call.name)
                    ?.let { capabilitiesProvider().unavailableCode(call.name) }
                if (unavailable == null) {
                    toolExecutor.execute(call)
                } else {
                    ToolResult(
                        JSONObject().put("ok", false).put("code", unavailable)
                            .put("message", "该工具当前不可用（$unavailable），本次未执行").toString(),
                    )
                }
            },
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = emitEvent,
            captureModelMedia = captureModelMedia,
            purpose = when {
                rewriteReply -> ProviderRequestPurpose.REPLY_REWRITE
                textOnlyFallback -> ProviderRequestPurpose.FALLBACK
                else -> ProviderRequestPurpose.CHAT
            },
            roleplayContext = roleplayContext,
            initialSupplementIndex = initialSupplementIndex,
            initialRound = initialRound,
            toolsForRound = ::toolsForRound,
            metricsScope = metricsScope,
            budgetGuard = budgetGuard,
            maxParallelAgents = maxParallelAgents,
            // 批次并发只取本地 descriptor，且插件/包 Hook 运行仍保持原有串行顺序。
            toolBatchSlot = if (extensions === AgentRunExtensions.NONE) {
                NexusToolDescriptors::batchSlot
            } else {
                { _ -> ToolBatchSlot.SERIAL }
            },
            compactionConfig = compactionConfig,
            compactionProvider = effectiveCompactionProvider,
        )
        val result = try {
            budgetGuard.watch(runController).use {
                (if (compactOnly) loop.compactOnly(compactionSelection) else loop.run()).also {
                    budgetGuard.checkDuration()
                    runController.throwIfCancelled()
                }
            }.also { runController.throwIfCancelled() }
        } catch (throwable: Throwable) {
            if (!compactOnly && !rewriteReply && !textOnlyFallback) extensions.finished(false)
            throw AgentModelExecutionException(
                cause = runController.abortFailure ?: throwable,
                contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentToolBatchRecovery.completeInterrupted(AgentConversationCodec.transcript(
                    transcript,
                    0,
                    loop.sensitiveToolCallIdsSnapshot(),
                )),
            )
        }
        if (!compactOnly && !rewriteReply && !textOnlyFallback) extensions.finished(true)
        return ModelResponse.Text(
            content = result.content,
            contextSnapshot = if (rewriteReply) null else loop.contextSnapshot(),
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                transcript,
                0,
                result.sensitiveToolCallIds,
            ),
        )
    }

    private fun toolName(tool: JSONObject): String? = tool.optJSONObject("function")?.optString("name")

    /** 注入只是附加内容：模型标识不完整或解析失败时保持原始提示，不能中断本次请求。 */
    private fun resolvePromptInjection(
        config: ModelConfig,
        scope: PromptRunScope,
        roleplay: RoleplayRunContext?,
    ): ResolvedPromptInjection =
        if (config.providerId.isBlank() || config.model.isBlank()) {
            ResolvedPromptInjection()
        } else {
            runCatching { PromptInjectionStore.resolve(config, scope,
                io.github.mangi.eta.agent.prompt.PromptMacroContext(roleplay?.card,
                    roleplay?.userName ?: "用户", roleplay?.userDescription.orEmpty())) }
                .onSuccess { resolved ->
                    if (resolved.skippedScopes.isNotEmpty()) {
                        AndroidAgentLogger.warn("Prompt injection skipped invalid scopes=${resolved.skippedScopes.joinToString(",")}")
                    }
                }
                .getOrElse { ResolvedPromptInjection() }
        }

    private fun toolNames(tools: JSONArray): Set<String> =
        (0 until tools.length()).mapNotNullTo(mutableSetOf()) { toolName(tools.getJSONObject(it)) }

    internal fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        when {
            authMode == ProviderAuthModes.API_KEY -> require(
                apiKey.isNotBlank() || ProviderEndpoint.isLocalOrPrivate(baseUrl) || io.github.mangi.eta.agent.packages.PackageAiRegistry.isPackageProvider(providerId),
            ) { "请先配置 API Key" }
            ProviderAuthModes.isOAuth(authMode) -> {
                require(providerId.isNotBlank()) { "账号登录缺少服务商标识，请重新选择服务商" }
                require(apiKey.isEmpty()) { "账号登录配置不能包含 API Key" }
            }
            else -> error("不支持的服务商认证方式")
        }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用思考，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage>,
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(AgentConversationCodec.userMessage(text, images))

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        traceFormatter.summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        traceFormatter.summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        traceFormatter.summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val hostedWebSearchEnabled: Boolean = false,
        val supportsSearchSuggestions: Boolean = false,
        /**
         * Model configuration maps an unknown catalog capability to false before Runtime so
         * providers do not receive unsupported tool declarations. The default remains true for
         * older serialized Runtime payloads that predate explicit capability fields.
         */
        val supportsTools: Boolean = true,
        /** Older runtime payloads remain readable; model-backed requests set this explicitly. */
        val supportsImageInput: Boolean = true,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        val deviceDirectTools: Boolean = true,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val autoCompactionEnabled: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
        val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
        val authMode: String = ProviderAuthModes.API_KEY,
    ) {
        fun isEmbeddedLocalTextModel(): Boolean = providerId in setOf(
            "eta-local-model-runtime-builtin",
            "eta-local-model-runtime-gguf",
            "eta-local-model-runtime-mnn",
        )

        val usesHostedWebSearch: Boolean
            get() = hostedWebSearchEnabled && when (providerType) {
                ProviderTypes.OPENAI_COMPATIBLE -> openAiEndpointMode == OpenAiEndpointMode.RESPONSES
                ProviderTypes.GEMINI -> supportsSearchSuggestions && GeminiModelCapabilities.supportsHostedSearch(model)
                else -> false
            }

        val hostedWebSearchToolName: String
            get() = if (providerType == ProviderTypes.GEMINI) "google_search" else "web_search"

        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        /** Declared provider window; missing metadata uses the conservative compaction fallback. */
        val knownContextWindow: Int?
            get() = contextWindow?.takeIf { it > 0 }
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val contextSummary: Boolean = false,
        val compactedUserTurns: Int = 0,
        val summaryThroughUserTurn: Int = 0,
        val messageId: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val round: Int = 1,
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
        val command: String? = null,
        val files: List<ToolFile> = emptyList(),
        /** Verified locally captured receipts, separate from untrusted remote result JSON. */
        val artifacts: List<io.github.mangi.eta.agent.runtime.AgentArtifactReference> = emptyList(),
    )
    data class ToolFile(val name: String, val mediaType: String, val bytes: ByteArray)

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown",
        /** 截图已具有可上传编码，跨进程物化时保留字节，不走附件转码。 */
        val preserveOriginal: Boolean = false,
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
            val contextSnapshot: AgentContextSnapshot? = null,
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
    val contextSnapshot: AgentContextSnapshot? = null,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)
