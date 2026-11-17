package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.data.model.ConversationSubject
import org.json.JSONObject

internal data class PackagePersistedMessage(val subject: ConversationSubject, val id: String, val sender: String, val content: String,
    val timestamp: Long, val inputTokens: Long? = null, val outputTokens: Long? = null, val cachedTokens: Long? = null)

internal object PackageMessageEvents {
    fun committed(context: Context, messages: List<PackagePersistedMessage>) {
        messages.forEach { message ->
            PackageApplicationEvents.emit(context, "ChatMessageHook", "message_persisted", payload(message), message.subject)
        }
    }
    internal fun payload(message: PackagePersistedMessage): JSONObject = JSONObject().put("chatId", message.subject.id)
        .put("messageId", message.id).put("subjectType", message.subject.kind.value).put("timestamp", message.timestamp)
        .put("sender", message.sender).put("roleName", message.sender).put("content", message.content.take(32_000))
        .put("truncated", message.content.length > 32_000).put("completedAt", message.timestamp)
        .put("inputTokens", message.inputTokens ?: JSONObject.NULL).put("outputTokens", message.outputTokens ?: JSONObject.NULL)
        .put("cachedInputTokens", message.cachedTokens ?: JSONObject.NULL).put("displayMode", "TEXT").put("isFavorite", false)
}
