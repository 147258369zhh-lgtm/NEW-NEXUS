package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/** Actual provider-bound requests, not a second resolver or a list of selected templates. */
internal object PromptAssemblyAuditStore {
    private const val KEY = "nexa_prompt_assembly_audit_v1"
    private val lock = Any()

    fun recent(): List<JSONObject> = synchronized(lock) {
        runCatching {
            val rows = JSONArray(Prefs.localAgentPreferences()?.getString(KEY, "[]") ?: "[]")
            (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    fun record(operationId: String, scope: PromptRunScope, request: ProviderRequest, resolved: ResolvedPromptInjection) = synchronized(lock) {
        val messages = (0 until request.messages.length()).mapNotNull { request.messages.optJSONObject(it) }
        val allText = messages.map { it.optString("content") }
        val fragments = resolved.fragments.map { fragment ->
            val index = allText.indexOfFirst { fragment.content.isNotBlank() && it.contains(fragment.content) }
            val status = if (fragment.status == "已注入" && index < 0) "请求投影或压缩后省略/缩短" else fragment.status
            fragment.copy(status = status).toJson().apply {
                remove("content")
                put("content_chars", fragment.sourceCharacters)
                put("content_sha256", java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fragment.content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
                put("message_index", if (index >= 0) index else JSONObject.NULL)
            }
        }
        val sources = JSONArray()
        if (request.config.systemPrompt.isNotBlank()) {
            val index = allText.indexOfFirst { it.contains(request.config.systemPrompt) }
            sources.put(JSONObject().put("source", "模型提供商指令").put("position", "系统前缀")
                .put("status", if (index >= 0) "已提供" else "由角色替代或紧凑投影省略/缩短")
                .put("message_index", if (index >= 0) index else JSONObject.NULL))
        }
        if (scope.characterId.isNotBlank()) sources.put(JSONObject().put("source", "角色卡、世界书与剧情记忆")
            .put("position", "角色人格与深度/后置投影").put("status", "按本轮角色上下文与世界书触发条件投影"))
        if (scope.subagentName.isNotBlank() || scope.meetingParticipant.isNotBlank()) sources.put(JSONObject()
            .put("source", "Agent 职责：" + scope.subagentName.ifBlank { scope.meetingParticipant })
            .put("position", "运行阶段职责").put("status", "与阶段约束共同装配"))
        sources.put(JSONObject().put("source", "运行时合同、工作模式、技能及背景资料")
            .put("position", "系统消息").put("status", "以实际请求及各模块的独立选择为准"))
        val toolNames = (0 until request.effectiveTools.length()).mapNotNull { index ->
            request.effectiveTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")?.takeIf(String::isNotBlank)
        }
        val row = JSONObject().put("operation_id", operationId).put("time", System.currentTimeMillis())
            .put("scope", scope.toJson()).put("stage", scope.effectiveStage.title)
            .put("model", request.config.providerName + " / " + request.config.model)
            .put("purpose", request.purpose.name).put("fragments", JSONArray(fragments)).put("sources", sources)
            .put("tools", JSONArray(toolNames)).put("hosted_web_search", request.effectiveConfig.hostedWebSearchEnabled)
            .put("system_message_count", messages.count { it.optString("role") == "system" })
        // One latest request per operation; keep a small private diagnostic history.
        val rows = listOf(row) + recent().filter { it.optString("operation_id") != operationId }.take(7)
        Prefs.localAgentPreferences()?.edit()?.putString(KEY, JSONArray(rows).toString())?.apply()
        Unit
    }
}
