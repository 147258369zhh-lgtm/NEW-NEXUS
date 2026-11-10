package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.LearningDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class MemorySearchTool(private val context: Context, private val scopes: List<AgentMemoryScope>) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult = try {
        val args = JSONObject(call.argumentsJson)
        val query = args.getString("query").trim().take(500)
        require(query.isNotEmpty()) { "query 不能为空" }
        if (!runBlocking { AgentMemoryRepository.isEnabled() }) error("记忆已关闭")
        val entries = runBlocking { MemoryReviewRepository(LearningDatabase.get(context)).search(scopes, query, args.optInt("limit", 12)) }
        val result = JSONObject().put("ok", true).put("entries", JSONArray().also { array -> entries.forEach {
            array.put(JSONObject().put("id", it.id).put("scope", it.scope).put("topic", it.topic).put("content", it.content)
                .put("source", it.source).put("evidence", it.evidence).put("confirmed_at", it.updatedAt)
                .put("expires_at", it.expiresAt ?: JSONObject.NULL))
        } }).put("documents", JSONArray().also { array -> scopes.forEach {
            val read = AgentMemoryRepository.read(it, query = query, maxChars = 2000)
            if (read.matchedLines > 0) array.put(JSONObject().put("scope", it.key).put("revision", read.snapshot.revision).put("content", read.content))
        } })
        AgentModelClient.ToolResult(result.toString(), sensitive = true)
    } catch (e: Exception) { AgentModelClient.ToolResult(JSONObject().put("ok", false).put("message", e.message.orEmpty()).toString(), sensitive = true) }

    companion object {
        fun schema(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "memory_search").put("description", "检索当前允许作用域内的用户已确认记忆和 MEMORY.md，返回内容、来源与时间；不会返回待确认或过期条目。")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("maxLength", 500))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30)))
                .put("required", JSONArray().put("query"))))
    }
}
