package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.ConversationMessageOriginEntity
import io.github.mangi.eta.ui.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

internal data class ConversationBranchDraft(
    val node: ConversationBranchEntity,
    val root: ConversationBranchEntity?,
    val origins: List<ConversationMessageOriginEntity>,
    val setup: io.github.mangi.eta.data.db.ConversationBranchSetupEntity? = null,
)

/** 只复制已持久化的前缀；绝不复用消息主键、运行控制或包含未来消息的摘要。 */
internal object AgentConversationBranchPlanner {
    data class Plan(val state: AgentChatHomeUiState, val draft: ConversationBranchDraft)

    fun create(
        sourceId: String,
        source: AgentChatHomeUiState,
        positions: Map<String, Int>,
        messageId: String,
        parent: ConversationBranchEntity?,
        childId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
        verifiedPrefix: Boolean = false,
    ): Plan {
        require(source.isConversationLoaded && !source.hasEarlierMessages && !source.isStreaming && !source.isCompacting && source.messageEdit == null)
        require(sourceId != childId)
        val target = source.messages.indexOfFirst { it.id == messageId }
        require(target >= 0)
        require(!verifiedPrefix || target == source.messages.lastIndex)
        val targetMessage = source.messages[target]
        require(targetMessage is UserMessageUi || targetMessage is AgentMessageUi)
        val nextUser = ((target + 1) until source.messages.size).firstOrNull { source.messages[it] is UserMessageUi }
            ?: source.messages.size
        // 多块正文或工具批次中间的回复不能作为已结束的一轮。
        if (targetMessage is AgentMessageUi) require(source.messages.subList(target + 1, nextUser).none {
            it is AgentMessageUi || it is ToolActivityMessageUi || it is ToolSummaryMessageUi
        })
        val prefix = source.messages.take(target + 1)
        require(prefix.all { it.id in positions })
        val uiIds = prefix.associate { it.id to UUID.randomUUID().toString() }
        val original = source.journal.ifEmpty { source.history }
        val rawHistory = journalPrefix(source, original, target, nextUser, verifiedPrefix)
        val mode = if (rawHistory == null) "visible_reconstruction" else "journal"
        val transcript = rawHistory ?: reconstruct(prefix)
        // 未闭合/无效批次不能自动重放，也不能伪装成完整历史。
        require(closedTools(transcript)) { "BRANCH_UNFINISHED_TOOLS" }
        val transcriptIds = transcript.filter { it.messageId.isNotBlank() }.associate {
            it.messageId to (uiIds[it.messageId] ?: UUID.randomUUID().toString())
        }
        val callIds = mutableMapOf<String, String>()
        val history = transcript.map { entry ->
            val calls = entry.toolCallsJson.takeIf(String::isNotBlank)?.let(::JSONArray)
            if (calls != null) for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val old = call.getString("id")
                call.put("id", callIds.getOrPut(old) { "call_${UUID.randomUUID()}" })
                listOf("signature", "thought_signature", "encrypted_content").forEach(call::remove)
            }
            entry.copy(messageId = transcriptIds[entry.messageId].orEmpty(),
                contentJson = stripOpaqueBlocks(entry.contentJson),
                toolCallId = if (entry.toolCallId.isBlank()) "" else callIds.getValue(entry.toolCallId),
                toolCallsJson = calls?.toString().orEmpty(),
                contextSummary = false, compactedUserTurns = 0, summaryThroughUserTurn = 0)
        }
        val copied = prefix.map { message ->
            val id = uiIds.getValue(message.id)
            val revision = source.roleplayMessages.revisions[message.id].takeIf { source.roleplay != null }
            when (message) {
                is UserMessageUi -> message.copy(id = id, content = revision?.content ?: message.content,
                    isEdited = message.isEdited || revision != null)
                is AgentMessageUi -> message.copy(id = id, content = revision?.content ?: message.content,
                    isStreaming = false, usage = null, searchSuggestionsHtml = emptyList())
                is ThinkingMessageUi -> message.copy(id = id, isStreaming = false, collapsed = true)
                is ToolActivityMessageUi -> message.copy(id = id)
                is ToolSummaryMessageUi -> message.copy(id = id)
                is SystemNoticeMessageUi -> message.copy(id = id, running = false, contextTokens = null)
                else -> error("BRANCH_UNPERSISTED_MESSAGE")
            }
        }
        val links = source.roleplayMessages.links.entries.mapNotNull { (id, link) ->
            val newId = uiIds[id] ?: return@mapNotNull null
            val newTranscriptId = transcriptIds[link.transcriptMessageId] ?: return@mapNotNull null
            newId to link.copy(transcriptMessageId = newTranscriptId)
        }.toMap()
        val revisions = source.roleplayMessages.revisions.entries.mapNotNull { (id, revision) ->
            uiIds[id]?.takeIf { it in links }?.let { it to revision }
        }.toMap()
        val state = AgentChatHomeUiState(messages = copied, history = history, journal = history,
            input = "", isStreaming = false, thinkingEnabled = source.thinkingEnabled,
            reasoningEffort = source.reasoningEffort, modelId = source.modelId, roleplay = source.roleplay,
            roleplayMessages = RoleplayMessageState(links = links, revisions = revisions))
        return Plan(RoleplayConversationReducer.decorate(state), ConversationBranchDraft(
            node = ConversationBranchEntity(childId, parent?.familyId ?: sourceId, sourceId, sourceId,
                messageId, positions.getValue(messageId), mode, now),
            root = if (parent == null) ConversationBranchEntity(sourceId, sourceId, createdAt = now) else null,
            origins = prefix.map { ConversationMessageOriginEntity(uiIds.getValue(it.id), childId, sourceId, it.id, positions.getValue(it.id)) },
        ))
    }

    private fun journalPrefix(source: AgentChatHomeUiState, journal: List<ConversationMessage>, target: Int, nextUser: Int,
        verifiedPrefix: Boolean): List<ConversationMessage>? {
        if (journal.isEmpty() || journal.any { it.contextSummary || it.compactedUserTurns > 0 }) return null
        val users = source.messages.filterIsInstance<UserMessageUi>()
        val entries = journal.withIndex().filter { it.value.role == "user" }
        // 真实库验证的前缀从第一条开始对齐；后续用户记录不参与复制。旧无 ID 记录仍核对正文。
        if ((if (verifiedPrefix) entries.size < users.size else entries.size != users.size) || users.zip(entries).any { (ui, entry) ->
            if (entry.value.messageId.isNotBlank()) entry.value.messageId != ui.id
            else entry.value.content != ui.content && textContent(entry.value.contentJson) != ui.content
        }) return null
        val ordinal = source.messages.take(target + 1).count { it is UserMessageUi } - 1
        val userIndex = entries.getOrNull(ordinal)?.index ?: return null
        val end = if (source.messages[target] is UserMessageUi) userIndex + 1
            else entries.getOrNull(ordinal + 1)?.index ?: journal.size
        if (source.messages[target] is AgentMessageUi) {
            val answer = journal.subList(userIndex + 1, end).lastOrNull { it.role == "assistant" } ?: return null
            val message = source.messages[target] as AgentMessageUi
            val linked = source.roleplayMessages.links[message.id]?.transcriptMessageId
            if (answer.messageId.isNotBlank()) {
                if (linked != answer.messageId && message.id != answer.messageId && !message.id.startsWith("${answer.messageId}-")) return null
            } else if (answer.content != message.content && textContent(answer.contentJson) != message.content) return null
        }
        if (source.messages.subList(target + 1, nextUser).any { it is ThinkingMessageUi && it.isStreaming }) return null
        val prefix = journal.take(end)
        val projected = if (source.roleplay == null) prefix else RoleplayConversationReducer.projectJournal(
            source.copy(journal = prefix, history = prefix)) ?: return null
        // 先裁剪再投影，用户修订的说明可增加行数；未来修订和正文均不在输入内。
        return projected
    }

    private fun reconstruct(messages: List<AgentChatMessageUi>): List<ConversationMessage> = buildList {
        add(ConversationMessage("system", "此分支由已保存的可见消息重建，旧执行历史无法精确对齐。工具摘要只表示原会话记录，不代表分支已执行这些操作。"))
        messages.forEach { message -> when (message) {
            is UserMessageUi -> add(ConversationMessage("user", message.content + if (message.images.isEmpty()) "" else "\n[历史附件仍可在聊天中查看，未恢复旧模型图片状态]", messageId = message.id))
            is AgentMessageUi -> add(ConversationMessage("assistant", message.content, messageId = message.id))
            is ToolActivityMessageUi -> add(ConversationMessage("system", "原会话工具记录：${message.toolName}；${message.status}；${message.resultSummary.orEmpty()}"))
            else -> Unit
        } }
    }

    internal fun closedTools(messages: List<ConversationMessage>): Boolean = runCatching {
        val pending = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        messages.forEach { message ->
            if (message.role == "tool") {
                if (!pending.remove(message.toolCallId)) return false
            } else {
                if (pending.isNotEmpty()) return false
                if (message.toolCallsJson.isNotBlank()) {
                    if (message.role != "assistant") return false
                    val calls = JSONArray(message.toolCallsJson)
                    for (i in 0 until calls.length()) {
                        val id = calls.getJSONObject(i).getString("id")
                        if (id.isBlank() || !seen.add(id)) return false
                        pending += id
                    }
                }
            }
        }
        pending.isEmpty()
    }.getOrDefault(false)

    private fun textContent(raw: String): String = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { block ->
            if (block.optString("type") in setOf("text", "output_text", "input_text")) block.optString("text") else null
        } }.joinToString("\n")
    }.getOrDefault("")

    internal fun stripOpaqueBlocks(raw: String): String {
        if (raw.isBlank()) return ""
        val content = JSONTokener(raw).nextValue()
        fun clean(block: JSONObject): JSONObject? {
            if (block.optString("type") in setOf("thinking", "redacted_thinking", "reasoning", "thought")) return null
            listOf("signature", "thought_signature", "encrypted_content").forEach(block::remove)
            return block
        }
        if (content is JSONObject) return clean(content)?.toString().orEmpty()
        val array = content as JSONArray
        return JSONArray().also { result ->
            for (i in 0 until array.length()) {
                clean(array.getJSONObject(i))?.let(result::put)
            }
        }.toString().takeUnless { it == "[]" }.orEmpty()
    }
}
