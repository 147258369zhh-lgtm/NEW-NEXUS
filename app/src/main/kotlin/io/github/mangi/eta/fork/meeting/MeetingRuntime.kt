package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.prompt.PromptRunScope
import io.github.mangi.eta.agent.runtime.AgentExecutionPort
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import io.github.mangi.eta.agent.subagent.SubagentPermissionCatalog
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.a2a.A2AAgentRepository
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.a2a.A2AHttpClient
import io.github.mangi.eta.fork.a2a.A2AMessage
import io.github.mangi.eta.fork.a2a.A2APart
import io.github.mangi.eta.fork.a2a.A2ARole
import io.github.mangi.eta.fork.a2a.A2ATask
import io.github.mangi.eta.fork.a2a.A2ATaskState
import io.github.mangi.eta.fork.a2a.a2aPeerPolicyForManualEndpoint
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * 会议室运行器：主持人只负责选择和汇总，参与者通过独立模型上下文执行任务。
 * 所有真实任务先写入 meeting_tasks，再进入运行状态，便于进程重启后识别未完成项。
 */
internal object MeetingRuntime {
    private val activeRooms = ConcurrentHashMap.newKeySet<String>()
    private const val MAX_WORK_RESULT_CHARS = 12_000
    private const val MAX_SUMMARY_INPUT_CHARS = 48_000

    private class ToolBoundary(val policy: AgentToolPolicy) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<ToolBoundary>
    }

    suspend fun runTurn(
        context: Context,
        roomId: String,
        rawText: String,
        attachments: ConversationAttachments = ConversationAttachments(),
        toolPolicy: AgentToolPolicy = AgentToolPolicy.UNRESTRICTED,
    ): Boolean = withContext(ToolBoundary(toolPolicy)) { runTurnInternal(context, roomId, rawText, attachments) }

    private suspend fun runTurnInternal(
        context: Context,
        roomId: String,
        rawText: String,
        attachments: ConversationAttachments,
    ): Boolean {
        val text = rawText.trim()
        if ((text.isEmpty() && attachments.isEmpty) || !activeRooms.add(roomId)) return false
        io.github.mangi.eta.agent.memory.MemoryAutomationSettingsStore.touch(context)
        try {
            val snapshot = MeetingRepository.snapshot(context, roomId).first() ?: return false
            runCatching {
                val projectId = io.github.mangi.eta.fork.projects.ProjectRepository.bindingForSubject(context,
                    io.github.mangi.eta.fork.projects.ProjectSubjectType.MEETING, roomId)?.projectId
                io.github.mangi.eta.agent.memory.MemoryAutomationCapture.capture(context, "meeting-${UUID.randomUUID()}",
                    "meeting:$roomId", text, listOfNotNull(io.github.mangi.eta.data.repository.AgentMemoryScope.GLOBAL,
                        projectId?.let(io.github.mangi.eta.data.repository.AgentMemoryScope::project),
                        io.github.mangi.eta.data.repository.AgentMemoryScope.meeting(roomId)))
            }
            val meetingSettings = MeetingSettingsStore.get(context).current
            // Capture history before appending the current user message. The current request is sent
            // as the new prompt, while this snapshot supplies only established room memory.
            val participantHistory = mutableMapOf<String, List<AgentModelClient.ConversationMessage>>()
            snapshot.participants.forEach { participant ->
                participantHistory[participant.profileName] = MeetingRepository.participantHistory(
                    context = context,
                    roomId = roomId,
                    profileName = participant.profileName,
                    messages = snapshot.messages,
                    isHost = participant.isHost,
                )
            }
            val routing = MeetingMentionRouter.route(
                text = text,
                availableProfiles = snapshot.participants.filter { it.enabled }.map { it.profileName },
                maxParticipants = meetingSettings.maxParallelParticipants,
            )
            MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.RUNNING)
            MeetingRepository.appendMessage(
                context = context,
                roomId = roomId,
                authorType = MeetingAuthorType.USER,
                authorProfile = null,
                authorName = "You",
                content = AgentFileReferencePromptCodec.format(text, attachments.files.map { it.reference }),
                attachments = attachments,
                mentions = when (routing) {
                    is MeetingRoutingDecision.Direct -> routing.mentions
                    is MeetingRoutingDecision.Parallel -> routing.mentions
                    is MeetingRoutingDecision.HostDispatch -> emptyList()
                    is MeetingRoutingDecision.UnknownMentions -> routing.mentions
                },
            )
            val host = snapshot.participants.firstOrNull { it.isHost }
                ?: snapshot.participants.firstOrNull { it.profileName == snapshot.room.hostProfile }
            if (host == null || !host.enabled) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = null,
                    authorName = "Meeting",
                    content = "主持人不可用、已停用或已被移除，本轮没有启动参与者。请在会议室设置中重新启用或选择主持人。",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.WAITING_FOR_USER)
                return true
            }

            if (routing is MeetingRoutingDecision.UnknownMentions) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = null,
                    authorName = host.displayName,
                    content = "未找到被提及的参与者：${routing.mentions.joinToString(", ") { "@$it" }}",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.WAITING_FOR_USER)
                return true
            }

            if (routing is MeetingRoutingDecision.HostDispatch &&
                !meetingSettings.autoDispatchWithoutMention
            ) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = host.profileName,
                    authorName = host.displayName,
                    content = "本轮未启动：当前设置要求明确指定执行者。请直接 @ 一位或多位 Agent。",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.WAITING_FOR_USER)
                return true
            }

            val targets = resolveTargets(
                context = context,
                roomId = roomId,
                text = text,
                routing = routing,
                host = host,
                participants = snapshot.participants,
                maxParticipants = meetingSettings.maxParallelParticipants,
                history = participantHistory.getValue(host.profileName),
            )
            if (targets.isEmpty()) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = host.profileName,
                    authorName = host.displayName,
                    content = "主持人没有选出可执行的参与者。请直接 @ 一位参与者，或在设置中启用至少一位 Agent。",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.WAITING_FOR_USER)
                return true
            }

            val tasks = targets.map { target ->
                MeetingRepository.createTask(
                    context = context,
                    roomId = roomId,
                    participantProfile = target.participant.profileName,
                    instruction = target.instruction,
                ) to target
            }
            val results = coroutineScope {
                tasks.map { (task, target) ->
                    async(Dispatchers.IO) {
                        executeTask(
                            context = context,
                            task = task,
                            target = target,
                            history = participantHistory[target.participant.profileName].orEmpty(),
                            attachments = attachments,
                        )
                    }
                }.awaitAll()
            }
            val successful = results.filter { it.content.isNotBlank() }
            if (successful.isEmpty()) {
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.FAILED)
                return true
            }

            if (meetingSettings.autoSummarizeResults) {
                val summary = summarize(
                    context = context,
                    host = host,
                    userText = text,
                    results = successful,
                    history = participantHistory.getValue(host.profileName),
                )
                val message = MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.HOST,
                    authorProfile = host.profileName,
                    authorName = host.displayName,
                    content = summary.content.ifBlank {
                        successful.joinToString("\n\n") { result ->
                            "${result.participant.displayName}\n${result.content}"
                        }
                    },
                    streamRunId = summary.streamRunId,
                )
                MeetingRepository.saveParticipantContext(
                    context = context,
                    roomId = roomId,
                    profileName = host.profileName,
                    snapshot = summary.snapshot,
                    lastMessageSortIndex = message.sortIndex,
                )
            }
            MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.WAITING_FOR_USER)
            return true
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = null,
                    authorName = "Meeting",
                    content = "本轮已停止。已完成的任务和结果会保留，可检查后重新发送。",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.FAILED)
            }
            throw cancellation
        } catch (throwable: Throwable) {
            withContext(NonCancellable) {
                MeetingRepository.appendMessage(
                    context = context,
                    roomId = roomId,
                    authorType = MeetingAuthorType.SYSTEM,
                    authorProfile = null,
                    authorName = "Meeting",
                    content = "本轮运行失败：${throwable.message ?: throwable.javaClass.simpleName}",
                )
                MeetingRepository.setRoomStatus(context, roomId, MeetingRoomStatus.FAILED)
            }
            return false
        } finally {
            activeRooms.remove(roomId)
        }
    }

    private suspend fun resolveTargets(
        context: Context,
        roomId: String,
        text: String,
        routing: MeetingRoutingDecision,
        host: MeetingParticipantEntity,
        participants: List<MeetingParticipantEntity>,
        maxParticipants: Int,
        history: List<AgentModelClient.ConversationMessage>,
    ): List<Target> {
        val enabledParticipants = participants.filter(MeetingParticipantEntity::enabled)
        val byProfile = enabledParticipants.associateBy { it.profileName }
        return when (routing) {
            is MeetingRoutingDecision.Direct -> listOfNotNull(
                byProfile[routing.participant]?.let {
                    Target(it, MeetingMentionRouter.stripLeadingMentions(text))
                },
            )
            is MeetingRoutingDecision.Parallel -> routing.participants.mapNotNull { name ->
                byProfile[name]?.let { Target(it, MeetingMentionRouter.stripLeadingMentions(text)) }
            }
            is MeetingRoutingDecision.HostDispatch -> {
                val plan = askHostToPlan(
                    context = context,
                    roomId = roomId,
                    host = host,
                    participants = enabledParticipants,
                    userText = text,
                    maxParticipants = maxParticipants,
                    history = history,
                )
                if (plan.assignments.isEmpty()) {
                    // Planning failure falls back to the host, never to leaking the raw request.
                    return listOf(Target(host, text))
                }
                val resolved = participants.associateBy { it.profileName } +
                    plan.created.associateBy { it.profileName }
                plan.assignments.distinctBy(HostAssignment::participant)
                    .take(maxParticipants)
                    .mapNotNull { assignment ->
                        resolved[assignment.participant]?.let { Target(it, assignment.instruction) }
                    }
            }
            is MeetingRoutingDecision.UnknownMentions -> emptyList()
        }
    }

    private suspend fun askHostToPlan(
        context: Context,
        roomId: String,
        host: MeetingParticipantEntity,
        participants: List<MeetingParticipantEntity>,
        userText: String,
        maxParticipants: Int,
        history: List<AgentModelClient.ConversationMessage>,
    ): HostPlan {
        val teammates = participants.filter { !it.isHost && it.enabled }
        val roster = teammates.joinToString("\n") { "- ${it.profileName}: ${it.profileSummary}" }
        val prompt = """
            你是受控会议室主持人，负责理解目标、拆解工作、分派任务并汇总结果。
            原始用户请求只交给你。参与者只能看到你写出的具体任务，不能看到完整原始请求。
            请从名册中选择 1 至 $maxParticipants 位参与者，并为每人输出独立、可执行、带交付格式的 instruction。
            如果名册缺少关键角色，可创建最多 3 位临时专家。profile_name 只能使用小写字母、数字、下划线或连字符。
            分派说明不能要求参与者向用户反问，也不要复制完整原始问题。
            只输出 JSON：
            {"create_agents":[{"profile_name":"visual-expert","display_name":"视觉专家","summary":"负责图像理解"}],
             "tasks":[{"participant":"profile-name","instruction":"独立任务说明"}],"reason":"一句话"}

            <roster>
            $roster
            </roster>

            <user_request>
            ${userText.take(24_000)}
            </user_request>
        """.trimIndent()
        return runCatching {
            parseHostPlan(completeAsParticipant(context, host, prompt, history, exposeStreams = false).content)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            HostPlan()
        }.let { plan ->
            val created = plan.createAgents.take(3).mapNotNull { draft ->
                runCatching {
                    MeetingRepository.createDynamicParticipant(
                        context = context,
                        roomId = roomId,
                        profileName = draft.profileName,
                        displayName = draft.displayName,
                        profileSummary = draft.summary,
                    )
                }.getOrNull()
            }
            val allowed = (teammates + created).mapTo(hashSetOf()) { it.profileName }
            plan.copy(
                assignments = plan.assignments.filter {
                    it.participant in allowed && it.instruction.isNotBlank()
                },
                created = created,
            )
        }
    }

    private suspend fun executeTask(
        context: Context,
        task: MeetingTaskEntity,
        target: Target,
        history: List<AgentModelClient.ConversationMessage>,
        attachments: ConversationAttachments = ConversationAttachments(),
    ): WorkResult = withContext(Dispatchers.IO) {
        MeetingRepository.updateTask(context, task, MeetingTaskStatus.RUNNING)
        try {
            val taskWithFiles = io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.format(
                request = target.instruction,
                references = attachments.files.map { it.reference },
            )
            val prompt = """
                你正在受控会议室中担任 ${target.participant.displayName}。
                独立完成下面这一项任务，不假设其他参与者的发言。
                如果需要读取用户明确附加的文件，只能通过任务中列出的文件引用访问。
                直接输出可汇总的工作结果；缺少关键信息时明确列出，不要向用户反问。

                <task>
                ${taskWithFiles.take(24_000)}
                </task>
            """.trimIndent()
            val completion = completeAsParticipant(
                context = context,
                participant = target.participant,
                prompt = prompt,
                history = history,
                taskId = task.id,
            )
            val normalized = completion.content.trim().ifBlank { "（没有返回文本结果）" }
            val message = MeetingRepository.appendMessage(
                context = context,
                roomId = task.roomId,
                authorType = MeetingAuthorType.AGENT,
                authorProfile = target.participant.profileName,
                authorName = target.participant.displayName,
                content = normalized,
                taskId = task.id,
                streamRunId = completion.streamRunId,
            )
            MeetingRepository.updateTask(
                context,
                task,
                MeetingTaskStatus.COMPLETED,
                resultMessageId = message.id,
            )
            MeetingRepository.saveParticipantContext(
                context = context,
                roomId = task.roomId,
                profileName = target.participant.profileName,
                snapshot = completion.snapshot,
                lastMessageSortIndex = message.sortIndex,
            )
            WorkResult(target.participant, normalized)
        } catch (timeout: TimeoutCancellationException) {
            val error = "请求超过会议室设置的等待时间"
            MeetingRepository.appendMessage(
                context = context,
                roomId = task.roomId,
                authorType = MeetingAuthorType.SYSTEM,
                authorProfile = target.participant.profileName,
                authorName = target.participant.displayName,
                content = error,
                taskId = task.id,
            )
            MeetingRepository.updateTask(
                context,
                task,
                MeetingTaskStatus.FAILED,
                errorMessage = error,
            )
            WorkResult(target.participant, "", error)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                MeetingRepository.updateTask(
                    context,
                    task,
                    MeetingTaskStatus.CANCELLED,
                    errorMessage = "本轮已停止",
                )
            }
            throw cancellation
        } catch (throwable: Throwable) {
            val error = throwable.message ?: throwable.javaClass.simpleName
            MeetingRepository.appendMessage(
                context = context,
                roomId = task.roomId,
                authorType = MeetingAuthorType.SYSTEM,
                authorProfile = target.participant.profileName,
                authorName = target.participant.displayName,
                content = "本轮任务未完成：${error.take(2_000)}",
                taskId = task.id,
            )
            MeetingRepository.updateTask(
                context,
                task,
                MeetingTaskStatus.FAILED,
                errorMessage = error.take(1_000),
            )
            WorkResult(target.participant, "", error)
        }
    }

    private suspend fun summarize(
        context: Context,
        host: MeetingParticipantEntity,
        userText: String,
        results: List<WorkResult>,
        history: List<AgentModelClient.ConversationMessage>,
    ): ParticipantCompletion {
        val material = results.joinToString("\n\n") { result ->
            "### ${result.participant.displayName} (${result.participant.profileName})\n" +
                result.content.take(MAX_WORK_RESULT_CHARS)
        }.take(MAX_SUMMARY_INPUT_CHARS)
        val prompt = """
            你是会议室主持人。请把各参与者的独立工作结果汇总成一份可执行答复。
            保留关键分歧和证据，不虚构未出现的结论；最后列出仍需用户决定的事项。

            <user_request>
            ${userText.take(16_000)}
            </user_request>

            <participant_results>
            $material
            </participant_results>
        """.trimIndent()
        return runCatching {
            completeAsParticipant(context, host, prompt, history).let { completion ->
                completion.copy(content = completion.content.trim())
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ParticipantCompletion("主持人汇总失败：${error.message ?: "未知错误"}\n\n$material", null)
        }
    }

    private suspend fun completeAsParticipant(
        context: Context,
        participant: MeetingParticipantEntity,
        prompt: String,
        history: List<AgentModelClient.ConversationMessage> = emptyList(),
        taskId: String? = null,
        exposeStreams: Boolean = true,
        attachments: ConversationAttachments = ConversationAttachments(),
    ): ParticipantCompletion = try {
        val meetingSettings = MeetingSettingsStore.get(context).current
        withTimeout(meetingSettings.requestTimeoutSeconds * 1_000L) {
            withContext(Dispatchers.IO) {
                participant.providerId?.takeIf { it.startsWith("a2a:") }?.let { binding ->
                    require(attachments.isEmpty) { "远程会议成员暂不支持本机附件，请先使用本机成员处理附件。" }
                    return@withContext ParticipantCompletion(
                        content = completeAsRemoteA2AParticipant(
                            context = context,
                            participant = participant,
                            remoteAgentId = binding.removePrefix("a2a:"),
                            prompt = prompt,
                        ),
                        snapshot = null,
                    )
                }
                val primary = RuntimeConfigRepository.currentRuntimeConfig()
                    ?: error("尚未配置可用的模型提供商")
                val resolved = RuntimeConfigRepository.subagentRuntimeConfig(
                    primaryConfig = primary,
                    profileName = participant.profileName,
                    explicitProviderId = participant.providerId,
                    explicitModelId = participant.modelId,
                )
                val profile = SubagentProfileCatalog.get(participant.profileName)
                    ?: SubagentProfile.resolve(
                        name = participant.profileName,
                        customRole = participant.profileSummary,
                    )
                val meetingSystemPrompt = buildString {
                    append(profile.buildSystemPrompt())
                    append("\n\n你当前处于受控会议室。所有参与者并行工作且上下文互相独立。")
                    append("你的输出将交给主持人汇总，不要声称其他参与者完成了未提供给自己的任务。")
                }
                val runtimeProfile = profile.withRuntimeToolPolicy(
                    toolsOverride = resolved.toolsOverride,
                    additionalDisallowedTools = resolved.disallowedTools,
                )
                val config = resolved.config.copy(
                    contextWindow = MeetingDatabase.get(context).meetingDao().room(participant.roomId)
                        ?.contextWindowOverride
                        ?.let { requested ->
                            val ceiling = resolved.config.knownContextWindow ?: resolved.config.contextWindow
                            ceiling?.let { minOf(it, requested) } ?: requested
                        }
                        ?: resolved.config.contextWindow,
                    systemPrompt = meetingSystemPrompt,
                    autoCompactionEnabled = resolved.config.autoCompactionEnabled &&
                        meetingSettings.meetingAutoCompactionEnabled,
                    subagentTools = false,
                    supportsSearchSuggestions = false,
                )
                val allowedTools = runtimeAllowedTools(runtimeProfile).toMutableSet().apply {
                    if (attachments.files.any { it.reference.kind == io.github.mangi.eta.agent.model.AgentFileReferenceKind.File }) {
                        add("read_file")
                        add("stat_file")
                    }
                    if (attachments.files.any { it.reference.kind == io.github.mangi.eta.agent.model.AgentFileReferenceKind.Directory }) {
                        add("list_directory")
                        add("glob_files")
                    }
                }.sorted()
                val runId = "meeting-${participant.roomId}-${participant.profileName}-${UUID.randomUUID()}"
                val request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = config,
                    promptScope = PromptRunScope(
                        meetingRoomId = participant.roomId,
                        meetingParticipant = participant.profileName,
                    ),
                    images = attachments.images.map { image -> io.github.mangi.eta.agent.media.AgentImageCodec
                        .fromTransferReference(context, image.uri, "meeting_attach") ?: error("附件图片已不可读取，请重新选择。") },
                    history = history,
                    modelSessionId = "meeting-${participant.roomId}-${participant.profileName}",
                    operation = AgentRuntimeWire.OP_CHAT,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = runId,
                        source = AgentRuntimeWire.MEETING_HANDOFF_SOURCE,
                        payload = participant.roomId,
                    ),
                    toolPolicy = AgentToolPolicy(
                        allowedTools = allowedTools,
                        disallowedTools = (
                            runtimeProfile.disallowedTools + SubagentProfile.FORBIDDEN_TOOLS
                        ).sorted(),
                    ).intersect(kotlinx.coroutines.currentCoroutineContext()[ToolBoundary]?.policy ?: AgentToolPolicy.UNRESTRICTED),
                )
                val result = MeetingExecutionTranscript.run(context, participant, runId, taskId, recordStreams = exposeStreams) { onEvent ->
                    AgentExecutionPort.run(context.applicationContext, request, onEvent)
                }
                if (!result.ok) error(result.error ?: "会议室 Agent 执行失败")
                ParticipantCompletion(result.content, result.contextSnapshot, runId.takeIf { exposeStreams })
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        RuntimeDebugTrace.meetingTimedOut(participant.roomId)
        throw timeout
    }

    private fun runtimeAllowedTools(profile: SubagentProfile): List<String> =
        if (profile.explicitTools?.contains("*") == true) {
            listOf("*")
        } else {
            buildSet {
                addAll(SubagentPermissionCatalog.defaultTools(profile))
                // Coder profiles may use dynamically discovered MCP tools that are not present in
                // the static permission catalog. Keep the same prefix predicate as SubagentProfile.
                if (profile.isToolAllowed("mcp_")) add("mcp_*")
            }.sorted()
        }

    private suspend fun completeAsRemoteA2AParticipant(
        context: Context,
        participant: MeetingParticipantEntity,
        remoteAgentId: String,
        prompt: String,
    ): String {
        val dao = A2ADatabase.get(context).dao()
        val agent = dao.agent(remoteAgentId)
            ?.takeIf { it.enabled }
            ?: error("A2A 参与者不存在或已停用")
        val repository = A2AAgentRepository(
            context = context,
            client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(agent.endpointUrl)),
            dao = dao,
        )
        val localTaskId = "meeting-${participant.roomId}-${participant.profileName}-${UUID.randomUUID()}"
        val response = repository.sendMessage(
            agentId = agent.id,
            message = A2AMessage(
                messageId = localTaskId,
                role = A2ARole.USER,
                parts = listOf(A2APart(text = prompt)),
            ),
            clientTaskId = localTaskId,
        )
        response.message?.let { message ->
            return message.parts.mapNotNull(A2APart::text).joinToString("\n").trim()
                .ifBlank { error("A2A 参与者返回空消息") }
        }
        var task = response.task ?: error("A2A 参与者未返回任务或消息")
        for (attempt in 0 until 60) {
            if (task.status.state.isTerminal || task.status.state.isInterrupted) break
            delay(2_000L)
            task = repository.getTask(agent.id, localTaskId)
        }
        if (!task.status.state.isTerminal) {
            error("A2A 任务在等待窗口内未完成：${task.status.state.name}")
        }
        if (task.status.state != A2ATaskState.COMPLETED) {
            error("A2A 任务未成功：${task.status.state.name}")
        }
        return task.outputText().ifBlank { error("A2A 任务完成但没有文本结果") }
    }

    private fun A2ATask.outputText(): String = buildList {
        status.message?.parts?.mapNotNull(A2APart::text)?.let(::addAll)
        history.asReversed().firstOrNull { it.role == A2ARole.AGENT }
            ?.parts?.mapNotNull(A2APart::text)?.let(::addAll)
        artifacts.forEach { artifact ->
            artifact.parts.mapNotNull(A2APart::text).let(::addAll)
        }
    }.distinct().joinToString("\n").trim()

    private fun parseHostPlan(raw: String): HostPlan {
        val jsonText = Regex("\\{[\\s\\S]*}").find(raw)?.value ?: return HostPlan()
        val root = runCatching { JSONObject(jsonText) }.getOrElse { return HostPlan() }
        val assignments = buildList {
            val array = root.optJSONArray("tasks") ?: return@buildList
            for (index in 0 until minOf(array.length(), 8)) {
                val item = array.optJSONObject(index) ?: continue
                val participant = item.optString("participant").trim().lowercase()
                val instruction = item.optString("instruction").trim().take(12_000)
                if (participant.isNotBlank() && instruction.isNotBlank()) {
                    add(HostAssignment(participant, instruction))
                }
            }
        }
        val createAgents = buildList {
            val array = root.optJSONArray("create_agents") ?: return@buildList
            for (index in 0 until minOf(array.length(), 3)) {
                val item = array.optJSONObject(index) ?: continue
                val profile = item.optString("profile_name").trim().lowercase()
                if (!Regex("[a-z0-9][a-z0-9_-]{1,47}").matches(profile)) continue
                add(
                    HostAgentDraft(
                        profileName = profile,
                        displayName = item.optString("display_name").trim().take(80).ifBlank { profile },
                        summary = item.optString("summary").trim().take(2_000),
                    ),
                )
            }
        }
        return HostPlan(assignments, createAgents)
    }

    private data class HostAssignment(val participant: String, val instruction: String)

    private data class HostAgentDraft(
        val profileName: String,
        val displayName: String,
        val summary: String,
    )

    private data class HostPlan(
        val assignments: List<HostAssignment> = emptyList(),
        val createAgents: List<HostAgentDraft> = emptyList(),
        val created: List<MeetingParticipantEntity> = emptyList(),
    )

    private data class Target(
        val participant: MeetingParticipantEntity,
        val instruction: String,
    )

    private data class WorkResult(
        val participant: MeetingParticipantEntity,
        val content: String,
        val error: String? = null,
    )

    private data class ParticipantCompletion(
        val content: String,
        val snapshot: AgentContextSnapshot?,
        val streamRunId: String? = null,
    )
}
