package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemoryReadResult
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.fork.learning.LearningDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal class MemorySearchTool(
    private val context: Context,
    private val scopes: List<AgentMemoryScope>,
    private val semanticScorer: MemorySemanticScorer? = null,
    private val searchReviewed: suspend (List<AgentMemoryScope>, String, Int) -> MemoryRetrievalResult = { allowed, query, limit ->
        MemoryReviewRepository(LearningDatabase.get(context)).searchExplained(allowed, query, limit, semanticScorer)
    },
    private val readDocument: suspend (AgentMemoryScope, String, Int) -> AgentMemoryReadResult = { scope, query, maxChars ->
        AgentMemoryRepository.read(scope, query = query, maxChars = maxChars)
    },
    private val isEnabled: suspend () -> Boolean = { AgentMemoryRepository.isEnabled() },
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult = try {
        val args = JSONObject(call.argumentsJson)
        val query = args.getString("query").trim().take(500)
        require(query.isNotEmpty()) { "query 不能为空" }
        if (!runBlocking { isEnabled() }) error("记忆已关闭")
        val allowed = scopes.distinctBy { it.key }
        val failures = JSONArray()
        var successfulSources = 0
        fun <T> source(name: String, block: () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            AndroidAgentLogger.warn("Memory search $name unavailable: type=${failure.safeLogType()}")
            failures.put(name)
            null
        }
        val retrieval = source("reviewed") { runBlocking { searchReviewed(allowed, query, args.optInt("limit", 12).coerceIn(1, 30)) } }
        if (retrieval != null) successfulSources++
        var remaining = 8000
        var budgetLimited = false
        val result = JSONObject().put("ok", true).put("retrieval", if (retrieval == null) JSONObject().put("status", "unavailable") else JSONObject()
            .put("mode", retrieval.mode.wire)
            .put("degraded", retrieval.mode == MemoryRetrievalMode.KEYWORD_ONLY)
            .put("semantic", JSONObject().put("status", retrieval.semanticStatus.wire)
                .put("reason", retrieval.semanticReason))
            .put("signals", JSONArray(retrieval.weights.keys.toList()))
            .put("weights", JSONObject(retrieval.weights)))
            .put("entries", JSONArray().also { array -> retrieval?.entries?.forEach { ranked ->
                val entry = ranked.entry
                if (entry.scope !in allowed.map { it.key }) return@forEach
                val size = entry.content.length + entry.evidence.length + entry.topic.length + entry.source.length
                if (size > remaining) { budgetLimited = true; return@forEach }
                remaining -= size
                val match = JSONObject()
                    .put("keyword", rounded(ranked.explanation.keyword))
                    .put("source", rounded(ranked.explanation.source))
                    .put("time", rounded(ranked.explanation.freshness))
                    .put("scope", rounded(ranked.explanation.scope))
                    .put("matched_terms", JSONArray(ranked.explanation.matchedTerms))
                    .put("matched_fields", JSONArray(ranked.explanation.matchedFields))
                    .put("reason", ranked.explanation.reasons.joinToString("；"))
                ranked.explanation.semantic?.let { match.put("semantic", rounded(it)) }
                array.put(JSONObject().put("id", entry.id).put("scope", entry.scope).put("layer", entry.layer)
                    .put("topic", entry.topic).put("content", entry.content)
                    .put("source", entry.source).put("evidence", entry.evidence)
                    .put("confidence", rounded(entry.confidence))
                    .put("created_at", entry.createdAt).put("confirmed_at", entry.updatedAt)
                    .put("expires_at", entry.expiresAt ?: JSONObject.NULL)
                    .put("score", rounded(ranked.explanation.score)).put("match", match))
            } })
            .put("documents", JSONArray().also { array -> allowed.forEach {
                if (remaining <= 0) { budgetLimited = true; return@forEach }
                val read = source("document:${it.key}") { runBlocking { readDocument(it, query, minOf(2000, remaining)) } } ?: return@forEach
                successfulSources++
                val content = read.content.take(remaining)
                if (content.length < read.content.length || read.hasMore) budgetLimited = true
                if (read.matchedLines > 0) array.put(JSONObject().put("scope", it.key).put("revision", read.snapshot.revision)
                    .put("matched_lines", read.matchedLines).put("content", content)
                    .put("match", JSONObject().put("kind", "line_keyword").put("keyword", true)))
                remaining -= content.length
            } })
        result.put("ok", successfulSources > 0).put("partial", failures.length() > 0)
            .put("unavailable_sources", failures).put("budget_limited", budgetLimited)
        AgentModelClient.ToolResult(result.toString(), sensitive = true)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (e: Exception) { AgentModelClient.ToolResult(JSONObject().put("ok", false).put("message", e.message.orEmpty()).toString(), sensitive = true) }

    companion object {
        private fun rounded(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0

        fun schema(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", "memory_search").put("description", "检索当前允许作用域内的用户已确认记忆和 MEMORY.md，按关键词、来源、时间和作用域混合排序并返回命中原因；有真实语义索引时叠加语义分数，缺失时明确降级，不伪造相似度。不会返回待确认或过期条目。")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("maxLength", 500))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30)))
                .put("required", JSONArray().put("query"))))
    }
}
