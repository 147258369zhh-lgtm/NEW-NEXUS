package io.github.mangi.eta.ui.screens.meeting

import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingRoomSnapshot
import io.github.mangi.eta.fork.meeting.MeetingTaskStatus
import io.github.mangi.eta.fork.meeting.MeetingStreamActivity
import io.github.mangi.eta.fork.meeting.MeetingStreamPhase
import io.github.mangi.eta.agent.runtime.AgentEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Produces a self-contained transcript without depending on Compose or Android storage APIs. */
internal object MeetingMarkdownExporter {
    fun export(
        snapshot: MeetingRoomSnapshot,
        exportedAt: Long = System.currentTimeMillis(),
    ): String = buildString {
        append("# ").append(snapshot.room.title.replace('\n', ' ').trim()).append("\n\n")
        append("- 主持人：")
            .append(snapshot.participants.firstOrNull { it.isHost }?.displayName ?: snapshot.room.hostProfile)
            .append('\n')
        append("- 状态：").append(snapshot.room.status).append('\n')
        snapshot.room.archivedAt?.let { archivedAt ->
            append("- 归档：已归档（").append(formatTime(archivedAt)).append("）\n")
        }
        append("- 创建时间：").append(formatTime(snapshot.room.createdAt)).append('\n')
        append("- 导出时间：").append(formatTime(exportedAt)).append("\n\n")

        append("## 参与者\n\n")
        snapshot.participants.forEach { participant ->
            append("- ")
            if (participant.isHost) append("主持人 ")
            append(participant.displayName)
                .append(" (`@").append(participant.profileName).append("`)")
            if (!participant.enabled) append(" · 已停用")
            participant.modelId?.takeIf(String::isNotBlank)?.let { append(" · 模型 ").append(it) }
            append('\n')
        }

        append("\n## 对话记录\n")
        val streamMetadata = snapshot.timelineEvents.filter { it.roomId == snapshot.room.id && it.type.startsWith(MeetingStreamActivity.EVENT_PREFIX) }
            .groupBy { it.type.removePrefix(MeetingStreamActivity.EVENT_PREFIX) }.mapValues { (_, events) -> events.maxBy { it.id } }
        snapshot.messages.forEach { message ->
            val stream = streamMetadata[message.id]?.let { MeetingStreamActivity.decode(it.payloadJson, message.content) }
                ?.takeIf { it.messageId == message.id && message.authorType == MeetingAuthorType.SYSTEM }
            append("\n### ")
                .append(message.authorName)
                .append(" · ")
                .append(
                    when (message.authorType) {
                        MeetingAuthorType.USER -> "用户"
                        MeetingAuthorType.HOST -> "主持人"
                        MeetingAuthorType.AGENT -> "Agent"
                        MeetingAuthorType.SYSTEM -> if (stream != null) {
                            if (stream.kind == AgentEvent.AssistantBlockKind.THINKING) "思考过程" else "过程输出"
                        } else "系统"
                        else -> message.authorType
                    },
                )
                .append(" · ")
                .append(formatTime(message.createdAt))
                .append("\n\n")
                .append(message.content.trim())
                .append('\n')
            snapshot.messageAttachments[message.id]?.let { attachments ->
                attachments.images.forEachIndexed { index, image ->
                    append("\n![附件图片 ").append(index + 1).append("](<").append(image.uri.replace(">", "%3E")).append(">)\n")
                }
                if (attachments.images.isNotEmpty()) append("\n> 图片链接指向本机文件；迁移完整内容请使用完整备份。\n")
            }
            if (stream != null) {
                if (stream.truncated) append("\n> 实时预览已截断；完整结果以最终答复为准。\n")
                when (stream.phase) {
                    MeetingStreamPhase.STREAMING -> append("\n> 导出时尚未完成，仅包含已收到的部分文字。\n")
                    MeetingStreamPhase.INTERRUPTED -> append("\n> 此次输出已中断，保留部分文字。\n")
                    else -> Unit
                }
            }
        }

        if (snapshot.tasks.isNotEmpty()) {
            append("\n## Agent 任务\n\n")
            snapshot.tasks.forEach { task ->
                append("- `").append(task.participantProfile).append("` · ")
                    .append(taskStatusLabel(task.status))
                task.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                    append(" · ").append(error.replace('\n', ' '))
                }
                append('\n')
            }
        }
    }

    fun defaultFileName(title: String): String {
        val safeTitle = title
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|\\p{C}]"), "")
            .trim()
            .take(40)
            .trim()
            .ifBlank { "meeting" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "Nexus-$safeTitle-$stamp.md"
    }

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

    private fun taskStatusLabel(status: String): String = when (status) {
        MeetingTaskStatus.QUEUED -> "排队"
        MeetingTaskStatus.RUNNING -> "执行中"
        MeetingTaskStatus.COMPLETED -> "已完成"
        MeetingTaskStatus.FAILED -> "失败"
        MeetingTaskStatus.CANCELLED -> "已取消"
        else -> status
    }
}
