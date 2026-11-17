package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.model.AgentModelClient
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
    private const val MAX_HISTORY_IMAGE_BYTES = 3 * 1024 * 1024

    private fun historyImage(reference: String, mime: String): String? = runCatching {
        if (reference.startsWith("data:image/") && reference.length <= 2 * 1024 * 1024) return@runCatching reference
        val file = if (reference.startsWith("file:")) File(Uri.parse(reference).path ?: "") else return@runCatching null
        if (!file.isFile || file.length() !in 1..io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES.toLong()) return@runCatching null
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES) return@runCatching null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val image = io.github.mangi.eta.agent.media.AgentModelImageEncoder.attachment(bytes, "meeting_history", mime)
            ?: return@runCatching null
        image.reference
    }.getOrNull()

    fun forParticipant(
        messages: List<MeetingMessageEntity>,
        participantProfile: String,
        attachments: Map<String, io.github.mangi.eta.data.repository.ConversationAttachments> = emptyMap(),
        isHost: Boolean = true,
    ): List<AgentModelClient.ConversationMessage> {
        val selected = ArrayDeque<AgentModelClient.ConversationMessage>()
        var remainingChars = MAX_HISTORY_CHARS
        var remainingImages = 8
        var remainingImageBytes = MAX_HISTORY_IMAGE_BYTES
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
            val storedImages = attachments[message.id]?.images.orEmpty()
            val images = storedImages.take(remainingImages).mapNotNull { image ->
                historyImage(image.uri, image.mimeType)?.let { reference ->
                    if (reference.length > remainingImageBytes) return@let null
                    remainingImageBytes -= reference.length
                    AgentModelClient.ModelImage(reference = reference, mimeType = reference.substringAfter("data:").substringBefore(';'),
                        bytes = 0, source = "meeting_history")
                }
            }
            remainingImages -= images.size
            val content = prefix + body + if (images.size < storedImages.size)
                "\n[部分历史图片无法读取或超过本轮图片限额；需要时请重新附加，未发送占位图片]" else ""
            val bounded = content.take(remainingChars)
            if (bounded.isBlank()) break
            selected.addFirst(
                AgentModelClient.ConversationMessage(
                    role = role,
                    content = bounded,
                    messageId = "meeting-history-${message.id}",
                    contentJson = images.takeIf { it.isNotEmpty() }?.let {
                        io.github.mangi.eta.agent.model.AgentConversationCodec.userMessage(bounded, images).getJSONArray("content").toString()
                    }.orEmpty(),
                ),
            )
            remainingChars -= bounded.length
            if (selected.size >= MAX_MESSAGES || remainingChars <= 0) break
        }
        return selected.toList()
    }
}
