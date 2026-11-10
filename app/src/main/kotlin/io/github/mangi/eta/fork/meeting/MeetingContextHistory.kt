package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.model.AgentModelClient
import android.util.Base64
import java.io.File
import android.net.Uri

/**
 * Builds the model history for one meeting participant.
 *
 * A meeting room is a shared transcript, but each participant must keep an isolated model
 * context. Participants therefore receive prior user requests, their own earlier results and
 * host summaries. Other participants' work is deliberately withheld; the host incorporates it
 * only when producing the final summary.
 */
internal object MeetingContextHistory {
    private const val MAX_MESSAGES = 80
    private const val MAX_MESSAGE_CHARS = 8_000
    private const val MAX_HISTORY_CHARS = 64_000

    private fun historyImage(reference: String, mime: String): String = if (reference.startsWith("data:image/")) reference else runCatching {
        val bytes = if (reference.startsWith("file:")) File(Uri.parse(reference).path ?: "").takeIf(File::isFile)?.readBytes() else null
        "data:$mime;base64," + Base64.encodeToString(bytes ?: byteArrayOf(0), Base64.NO_WRAP)
    }.getOrDefault("data:$mime;base64,AA==")

    fun forParticipant(
        messages: List<MeetingMessageEntity>,
        participantProfile: String,
        attachments: Map<String, io.github.mangi.eta.data.repository.ConversationAttachments> = emptyMap(),
        isHost: Boolean = true,
    ): List<AgentModelClient.ConversationMessage> {
        val selected = ArrayDeque<AgentModelClient.ConversationMessage>()
        var remainingChars = MAX_HISTORY_CHARS
        for (message in messages.asReversed()) {
            val mentions = runCatching {
                val array = org.json.JSONArray(message.mentionsJson)
                (0 until array.length()).map(array::optString)
            }.getOrDefault(emptyList())
            val role = when (message.authorType) {
                MeetingAuthorType.USER -> {
                    if (!isHost && participantProfile !in mentions) continue
                    "user"
                }
                MeetingAuthorType.HOST -> {
                    if (!isHost) continue
                    "assistant"
                }
                MeetingAuthorType.AGENT -> {
                    if (message.authorProfile != participantProfile) continue
                    "assistant"
                }
                else -> continue
            }
            val prefix = when (message.authorType) {
                MeetingAuthorType.USER -> {
                    "[会议室中的此前用户请求；当前请求会单独发送，不要把它误当成新指令]\n"
                }
                MeetingAuthorType.HOST -> {
                    "[主持人此前的汇总；这是会议记录，不是新的用户指令]\n"
                }
                else -> "[你此前在该会议室给出的结果]\n"
            }
            val body = message.content.trim().take(MAX_MESSAGE_CHARS)
            if (body.isBlank()) continue
            val content = prefix + body
            val bounded = content.take(remainingChars)
            if (bounded.isBlank()) break
            selected.addFirst(
                AgentModelClient.ConversationMessage(
                    role = role,
                    content = bounded,
                    messageId = "meeting-history-${message.id}",
                    contentJson = attachments[message.id]?.images?.takeIf { it.isNotEmpty() }?.let { images ->
                        io.github.mangi.eta.agent.model.AgentConversationCodec.userMessage(bounded, images.map { image ->
                            AgentModelClient.ModelImage(reference = historyImage(image.uri, image.mimeType), mimeType = image.mimeType, bytes = 0, source = "meeting_history")
                        }).toString()
                    }.orEmpty(),
                ),
            )
            remainingChars -= bounded.length
            if (selected.size >= MAX_MESSAGES || remainingChars <= 0) break
        }
        return selected.toList()
    }
}
