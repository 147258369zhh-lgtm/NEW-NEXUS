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
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.ui.components.ConversationAuthorRole
import io.github.mangi.eta.ui.components.ConversationMessageIdentity
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

/** Display projection only: preserve stored order, member identity and the original readable export. */
internal fun MeetingRoomUiState.conversationTimeline(
    summaryPreviewLines: Int,
    toolInterruptedDetail: String? = null,
    sensitiveToolDetail: String? = null,
    streamPreviewDetail: String? = null,
    streamInterruptedDetail: String? = null,
    previous: MeetingConversationTimeline? = null,
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
    val projected = messages.mapIndexed { index, message ->
        val streamEvent = streamEvents[message.id]
        val stream = streamEvent?.let { MeetingStreamActivity.decode(it.payloadJson, message.content) }
            ?.takeIf { it.messageId == message.id && message.authorType == MeetingAuthorType.SYSTEM }
        val toolEvent = toolEvents[message.id]
        val tool = toolEvent?.let { MeetingToolActivity.decode(it.payloadJson) }
            ?.takeIf { it.messageId == message.id && message.authorType == MeetingAuthorType.SYSTEM }
            ?.durable()
        val role = when (message.authorType) {
            MeetingAuthorType.USER -> ConversationAuthorRole.User
            MeetingAuthorType.HOST -> ConversationAuthorRole.Host
            MeetingAuthorType.AGENT -> ConversationAuthorRole.Agent
            else -> if (stream != null) {
                if (JSONObject(streamEvent.payloadJson).optBoolean("is_host")) ConversationAuthorRole.Host else ConversationAuthorRole.Agent
            } else if (tool != null) {
                if (JSONObject(toolEvent.payloadJson).optBoolean("is_host")) ConversationAuthorRole.Host else ConversationAuthorRole.Agent
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
        )
        if (stream != null) {
            val task = message.taskId?.let { id -> latestRoundTasks.firstOrNull { it.id == id } }
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
                AgentMessageUi(message.id, content, isStreaming = running)
            }
        }
        if (tool != null) {
            val task = message.taskId?.let { id -> latestRoundTasks.firstOrNull { it.id == id } }
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
                AgentMessageUi(message.id, message.content)
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
    val stableMessages = projected.map { message -> previousMessages[message.id]?.takeIf { it == message } ?: message }
    return MeetingConversationTimeline(stableMessages, identities, completedReplyIds)
}
