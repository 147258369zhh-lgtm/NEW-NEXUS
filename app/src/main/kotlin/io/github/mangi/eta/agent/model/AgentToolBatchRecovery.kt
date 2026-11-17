package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.capability.NexusToolDescriptors
import io.github.zhh_lgtm.nexus.application.api.ReplayDecision
import io.github.zhh_lgtm.nexus.application.api.ReplayVerdict
import org.json.JSONArray
import org.json.JSONObject

/**
 * 中断记录中的未知结果显式标记，避免恢复时重放已执行工具或提交悬空 tool call。
 * 每个未知结果都按 [NexusToolDescriptors] 的 descriptor 走 [io.github.zhh_lgtm.nexus.application.api.ReplayPolicyEngine]，
 * checkpoint 恢复、Runtime 取消和模型失败三条路径因此得到同一份重放结论。
 */
internal object AgentToolBatchRecovery {
    fun completeInterrupted(
        messages: List<AgentModelClient.ConversationMessage>,
        verdictFor: (String) -> ReplayVerdict = NexusToolDescriptors::replayVerdict,
    ): List<AgentModelClient.ConversationMessage> {
        // 未取得结果的 callId → 工具名；恢复文案必须知道调用了什么才能判定重放策略。
        val open = linkedMapOf<String, String>()
        val recovered = mutableListOf<AgentModelClient.ConversationMessage>()
        fun closeBatch() {
            open.forEach { (id, name) ->
                val verdict = verdictFor(name)
                recovered += AgentModelClient.ConversationMessage(role = "tool", toolCallId = id,
                    content = interruptedResult(name, verdict))
            }
            open.clear()
        }
        messages.forEach { message ->
            if (message.role != "tool") closeBatch()
            recovered += message
            if (message.toolCallsJson.isNotBlank()) {
                val calls = JSONArray(message.toolCallsJson)
                for (index in 0 until calls.length()) {
                    val call = calls.getJSONObject(index)
                    open[call.getString("id")] = call.optJSONObject("function")?.optString("name").orEmpty()
                }
            }
            if (message.role == "tool") open.remove(message.toolCallId)
        }
        closeBatch()
        return recovered
    }

    private fun interruptedResult(toolName: String, verdict: ReplayVerdict): String =
        JSONObject()
            .put("ok", false)
            .put("tool", toolName)
            .put("code", codeFor(verdict))
            .put("replay", verdict.decision.name.lowercase())
            .put("message", messageFor(toolName, verdict))
            .also { json -> verdict.verifierId?.let { json.put("verifier", it) } }
            .toString()

    private fun codeFor(verdict: ReplayVerdict): String = when (verdict.decision) {
        ReplayDecision.RESUME_AUTOMATIC -> "TOOL_INTERRUPTED_REPLAY_SAFE"
        ReplayDecision.VERIFY_THEN_RESUME -> "TOOL_INTERRUPTED_VERIFY_FIRST"
        ReplayDecision.REQUIRE_USER -> when (verdict.code) {
            "REPLAY_UNKNOWN_TOOL", "REPLAY_UNKNOWN_DESCRIPTOR" -> "TOOL_INTERRUPTED_UNKNOWN_TOOL"
            else -> "TOOL_INTERRUPTED_USER_CONFIRMATION"
        }
        ReplayDecision.NEVER -> "TOOL_INTERRUPTED_NEVER_REPLAY"
    }

    private fun messageFor(toolName: String, verdict: ReplayVerdict): String {
        val prefix = "工具批次已中断，未取得该调用的完整结果"
        return when (verdict.decision) {
            ReplayDecision.RESUME_AUTOMATIC -> "$prefix；$toolName 只读，可以安全重试。"
            ReplayDecision.VERIFY_THEN_RESUME ->
                "$prefix；重试 $toolName 之前必须先调用 ${verdict.verifierId} 核对是否已经生效。"
            ReplayDecision.REQUIRE_USER ->
                "$prefix；执行状态未知，需要用户确认后才能重试 $toolName，不要自动重放。"
            ReplayDecision.NEVER ->
                "$prefix；$toolName 不可重放，只保留待核对事实，不要再次执行。"
        }
    }
}
