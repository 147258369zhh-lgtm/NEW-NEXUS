package io.github.mangi.eta.ui.screens.meeting

import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingRoomUiState
import io.github.mangi.eta.fork.meeting.MeetingToolActivity
import io.github.mangi.eta.fork.meeting.MeetingToolPhase
import io.github.mangi.eta.fork.meeting.MeetingTaskStatus
import io.github.mangi.eta.fork.meeting.MeetingStreamActivity
import io.github.mangi.eta.fork.meeting.MeetingStreamPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentArtifactReference
import io.github.mangi.eta.ui.app.nexusArtifactContentPart
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.ui.components.ConversationAuthorRole
import io.github.mangi.eta.ui.components.ConversationMessageIdentity
import io.github.mangi.eta.ui.components.AgentIdentityPalette
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import org.json.JSONObject

internal data class MeetingConversationTimeline(
    val messages: List<AgentChatMessageUi>,
    val identities: Map<String, ConversationMessageIdentity>,
    val completedReplyIds: Set<String>,
)

internal class MeetingTimelineMetadataCache {
    private data class StreamEntry(val payload: String, val content: String, val value: StreamMetadata?)
    private data class ToolEntry(val payload: String, val value: ToolMetadata?)

    internal data class StreamMetadata(val activity: MeetingStreamActivity, val isHost: Boolean)
    internal data class ToolMetadata(val activity: MeetingToolActivity, val isHost: Boolean)

    private val streams = mutableMapOf<String, StreamEntry>()
    private val tools = mutableMapOf<String, ToolEntry>()

    fun retain(messageIds: Set<String>) {
        streams.keys.retainAll(messageIds)
        tools.keys.retainAll(messageIds)
    }

    fun stream(id: String, payload: String, content: String): StreamMetadata? {
        val existing = streams[id]
        if (existing != null && existing.payload == payload && existing.content == content) return existing.value
        val value = MeetingStreamActivity.decode(payload, content)?.let {
            StreamMetadata(it, JSONObject(payload).optBoolean("is_host"))
        }
        streams[id] = StreamEntry(payload, content, value)
        return value
    }

    fun tool(id: String, payload: String): ToolMetadata? {
        val existing = tools[id]
        if (existing != null && existing.payload == payload) return existing.value
        val value = MeetingToolActivity.decode(payload)?.let {
            ToolMetadata(it.durable(), JSONObject(payload).optBoolean("is_host"))
        }
        tools[id] = ToolEntry(payload, value)
        return value
    }
}

/** Display projection only: preserve stored order, member identity and the original readable export. */
internal fun MeetingRoomUiState.conversationTimeline(
    summaryPreviewLines: Int,
    toolInterruptedDetail: String? = null,
    sensitiveToolDetail: String? = null,
    streamPreviewDetail: String? = null,
    streamInterruptedDetail: String? = null,
    previous: MeetingConversationTimeline? = null,
    metadataCache: MeetingTimelineMetadataCache = MeetingTimelineMetadataCache(),
    checkCancelled: () -> Unit = {},
): MeetingConversationTimeline {
    val summaryId = latestSummary?.id
    val latestUserIndex = messages.indexOfLast { it.authorType == MeetingAuthorType.USER }
    val waitEvents = timelineEvents.filter { it.roomId == room.id && it.type.startsWith("accessibility_wait:") }
        .groupBy { it.type.removePrefix("accessibility_wait:") }
        .mapValues { (_, events) -> events.maxBy { it.id } }
    val toolEvents = timelineEvents.filter { it.roomId == room.id && it.type.startsWith(MeetingToolActivity.EVENT_PREFIX) }
        .groupBy { it.type.removePrefix(MeetingToolActivity.EVENT_PREFIX) }
        .mapValues { (_, events) -> events.maxBy { it.id } }
    val streamEvents = timelineEvents.filter { it.roomId == room.id && it.type.startsWith(MeetingStreamActivity.EVENT_PREFIX) }
        .groupBy { it.type.removePrefix(MeetingStreamActivity.EVENT_PREFIX) }
        .mapValues { (_, events) -> events.maxBy { it.id } }
    val identities = linkedMapOf<String, ConversationMessageIdentity>()
    val completedReplyIds = linkedSetOf<String>()
    val decodedTools = mutableMapOf<String, MeetingToolActivity>()
    val tasksById = latestRoundTasks.associateBy { it.id }
    metadataCache.retain(messages.mapTo(hashSetOf()) { it.id })
    val projected = messages.mapIndexed { index, message ->
        if (index % 64 == 0) checkCancelled()
        val streamEvent = streamEvents[message.id]
        val streamMetadata = streamEvent?.takeIf { message.authorType != MeetingAuthorType.USER }
            ?.let { metadataCache.stream(message.id, it.payloadJson, message.content) }
            ?.takeIf { it.activity.messageId == message.id }
        val stream = streamMetadata?.activity?.takeIf { message.authorType == MeetingAuthorType.SYSTEM }
        val toolEvent = toolEvents[message.id]
        val toolMetadata = toolEvent?.takeIf { message.authorType == MeetingAuthorType.SYSTEM }
            ?.let { metadataCache.tool(message.id, it.payloadJson) }
            ?.takeIf { it.activity.messageId == message.id }
        val tool = toolMetadata?.activity
        if (tool != null) decodedTools[message.id] = tool
        val role = when (message.authorType) {
            MeetingAuthorType.USER -> ConversationAuthorRole.User
            MeetingAuthorType.HOST -> ConversationAuthorRole.Host
            MeetingAuthorType.AGENT -> ConversationAuthorRole.Agent
            else -> if (stream != null) {
                if (streamMetadata?.isHost == true) ConversationAuthorRole.Host else ConversationAuthorRole.Agent
            } else if (tool != null) {
                if (toolMetadata?.isHost == true) ConversationAuthorRole.Host else ConversationAuthorRole.Agent
            } else ConversationAuthorRole.System
        }
        val summary = message.id == summaryId
        identities[message.id] = ConversationMessageIdentity(
            name = message.authorName,
            role = role,
            createdAt = message.createdAt,
            isSummary = summary,
            previewLines = summaryPreviewLines.coerceIn(0, 12).takeIf { summary },
            workGroup = stream?.runId ?: tool?.runId ?: message.taskId ?: message.authorProfile,
            avatarSeed = message.authorProfile ?: message.authorName,
            avatarPalette = AgentIdentityPalette.MeetingVivid,
        )
        if (stream != null) {
            val task = message.taskId?.let(tasksById::get)
            val active = isRunning && index > latestUserIndex && (message.taskId == null ||
                task?.status == MeetingTaskStatus.QUEUED || task?.status == MeetingTaskStatus.RUNNING)
            val running = active && stream.phase == MeetingStreamPhase.STREAMING
            val interrupted = stream.phase == MeetingStreamPhase.INTERRUPTED ||
                (!active && stream.phase == MeetingStreamPhase.STREAMING)
            val content = listOfNotNull(stream.content.takeIf(String::isNotEmpty),
                streamPreviewDetail.takeIf { stream.truncated }, streamInterruptedDetail.takeIf { interrupted }).joinToString("\n\n")
            return@mapIndexed if (stream.kind == AgentEvent.AssistantBlockKind.THINKING) {
                ThinkingMessageUi(message.id, content, isStreaming = running, collapsed = !running)
            } else {
                AgentMessageUi(message.id, content, isStreaming = running,
                    contentParts = (if (running) emptyList() else io.github.mangi.eta.data.repository.NexusContentParts.text(message.id, content)) +
                        stream.artifacts.map { nexusArtifactContentPart("${message.id}:${it.id}", it.id, it.name, it.mediaType) })
            }
        }
        if (tool != null) {
            val task = message.taskId?.let(tasksById::get)
            val active = isRunning && index > latestUserIndex && (message.taskId == null ||
                task?.status == MeetingTaskStatus.QUEUED || task?.status == MeetingTaskStatus.RUNNING)
            val status = when (tool.phase) {
                MeetingToolPhase.RUNNING -> if (active) ToolActivityStatusUi.Running else ToolActivityStatusUi.Unknown
                MeetingToolPhase.SUCCESS -> ToolActivityStatusUi.Success
                MeetingToolPhase.FAILED -> ToolActivityStatusUi.Failed
                MeetingToolPhase.UNKNOWN, MeetingToolPhase.INTERRUPTED -> ToolActivityStatusUi.Unknown
            }
            return@mapIndexed ToolActivityMessageUi(
                id = message.id, toolName = tool.name, status = status,
                argumentsSummary = tool.arguments, command = tool.command, imageCount = tool.imageCount,
                executionItemId = io.github.mangi.eta.agent.runtime.NexusExecutionIds.tool(tool.runId, tool.round, tool.callId, tool.hosted),
                resultSummary = when {
                    AgentSensitiveToolPolicy.isSensitive(tool.name) -> sensitiveToolDetail
                    tool.phase == MeetingToolPhase.INTERRUPTED || (tool.phase == MeetingToolPhase.RUNNING && !active) ->
                        listOfNotNull(tool.result, toolInterruptedDetail).joinToString("\n").ifBlank { null }
                    else -> tool.result
                },
            )
        }
        when (role) {
            ConversationAuthorRole.User -> UserMessageUi(message.id, message.content, images = messageAttachments[message.id]?.images.orEmpty().map { it.dataUrl })
            ConversationAuthorRole.Agent, ConversationAuthorRole.Host -> {
                completedReplyIds += message.id
                AgentMessageUi(message.id, message.content,
                    contentParts = io.github.mangi.eta.data.repository.NexusContentParts.text(message.id, message.content) +
                        streamMetadata?.activity?.artifacts.orEmpty().map {
                            nexusArtifactContentPart("${message.id}:${it.id}", it.id, it.name, it.mediaType)
                        })
            }
            ConversationAuthorRole.System -> {
                val event = waitEvents[message.id]
                val phase = event?.let { runCatching {
                    val payload = JSONObject(it.payloadJson)
                    val expectedId = "accessibility-${payload.getString("run_id")}-${payload.getInt("outage")}"
                    if (expectedId == message.id) AccessibilityWaitPhase.fromWireValue(payload.getString("phase")) else null
                }.getOrNull() }
                if (phase != null) {
                    SystemNoticeMessageUi(
                        id = message.id,
                        code = SystemNoticeCode.AccessibilityWaiting,
                        detail = phase.wireValue,
                        running = isRunning && index > latestUserIndex && phase == AccessibilityWaitPhase.WAITING,
                    )
                } else {
                    // Legacy notices have no typed metadata. Keep their text readable without guessing an error state.
                    AgentMessageUi(message.id, message.content, renderMarkdown = false)
                }
            }
        }
    }
    val previousMessages = previous?.messages?.associateBy { it.id }.orEmpty()
    val withFiles = projected.flatMap { message ->
        val tool = decodedTools[message.id]
        val artifacts = tool?.takeIf { it.phase == MeetingToolPhase.SUCCESS }?.let { activity ->
            activity.artifacts.ifEmpty { activity.artifactId?.let { id -> listOf(AgentArtifactReference(id, activity.artifactName ?: "文件")) }.orEmpty() }
        }.orEmpty()
        listOf(message) + artifacts.distinctBy { it.id }.mapIndexed { index, artifact ->
            val id = if (index == 0) "${message.id}:file" else "${message.id}:file:${artifact.id}"
            identities[id] = identities.getValue(message.id)
            AgentMessageUi(id, artifact.name,
                contentParts = listOf(nexusArtifactContentPart(id, artifact.id, artifact.name, artifact.mediaType)))
        }
    }
    val stableMessages = withFiles.map { message -> previousMessages[message.id]?.takeIf { it == message } ?: message }
    return MeetingConversationTimeline(stableMessages, identities, completedReplyIds)
}
