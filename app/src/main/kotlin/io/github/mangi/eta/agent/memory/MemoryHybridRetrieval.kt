package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemoryScopeType
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal enum class MemoryRetrievalMode(val wire: String) {
    HYBRID("hybrid"),
    KEYWORD_ONLY("keyword_only"),
}

internal enum class MemorySemanticStatus(val wire: String) {
    UNAVAILABLE("unavailable"),
    AVAILABLE("available"),
    FAILED("failed"),
}

/**
 * A real text embedding or memory index service may implement this seam.
 * Leave it unbound unless such a service exists; this module never synthesizes
 * vectors or similarity values locally.
 */
internal fun interface MemorySemanticScorer {
    suspend fun scores(query: String, entries: List<MemoryEntry>): Map<String, Double>
}

internal data class MemoryMatchExplanation(
    val keyword: Double,
    val source: Double,
    val freshness: Double,
    val scope: Double,
    val semantic: Double?,
    val score: Double,
    val matchedTerms: List<String>,
    val matchedFields: List<String>,
    val reasons: List<String>,
)

internal data class RankedMemoryEntry(
    val entry: MemoryEntry,
    val explanation: MemoryMatchExplanation,
)

internal data class MemoryRetrievalResult(
    val mode: MemoryRetrievalMode,
    val semanticStatus: MemorySemanticStatus,
    val semanticReason: String,
    val weights: Map<String, Double>,
    val entries: List<RankedMemoryEntry>,
)

/**
 * Hybrid ranking over the existing memory_entries table. Lexical, source,
 * freshness and scope signals are always computed locally. A semantic score is
 * used only when a real scorer is supplied and returns valid values; otherwise
 * the result explicitly degrades to keyword/source/time/scope ranking.
 */
internal object MemoryHybridRetriever {
    private const val FRESHNESS_WINDOW_MILLIS = 30L * 86_400_000L
    private const val MIN_KEYWORD = 0.10
    private const val MIN_SOURCE = 0.50
    private const val MIN_SEMANTIC = 0.30

    private const val SEMANTIC_WEIGHT = 0.45
    private const val HYBRID_KEYWORD_WEIGHT = 0.30
    private const val KEYWORD_ONLY_WEIGHT = 0.75
    private const val SOURCE_WEIGHT = 0.10
    private const val TIME_WEIGHT = 0.08
    private const val SCOPE_WEIGHT = 0.07

    private const val NO_SEMANTIC_REASON =
        "未安装可用的文本 embedding 或记忆索引服务；已降级为关键词、来源、时间和作用域排序"

    suspend fun retrieve(
        entries: List<MemoryEntry>,
        query: String,
        scopes: List<AgentMemoryScope>,
        now: Long,
        limit: Int,
        semanticScorer: MemorySemanticScorer? = null,
    ): MemoryRetrievalResult {
        val queryTokens = MemoryRetrievalText.tokens(query)
        val compactQuery = MemoryRetrievalText.compact(query)
        if (queryTokens.isEmpty() && compactQuery.isEmpty()) {
            return MemoryRetrievalResult(
                mode = MemoryRetrievalMode.KEYWORD_ONLY,
                semanticStatus = MemorySemanticStatus.UNAVAILABLE,
                semanticReason = NO_SEMANTIC_REASON,
                weights = keywordOnlyWeights(),
                entries = emptyList(),
            )
        }

        val allowedScopes = scopes.mapTo(linkedSetOf()) { it.key }
        val candidates = entries.filter { entry ->
            entry.scope in allowedScopes &&
                entry.status == "active" &&
                (entry.expiresAt == null || entry.expiresAt > now)
        }
        val semantic = readSemanticScores(semanticScorer, query, candidates)
        val hybrid = semantic.status == MemorySemanticStatus.AVAILABLE

        val ranked = candidates.mapNotNull { entry ->
            val text = buildString {
                append(entry.topic).append('\n')
                append(entry.content).append('\n')
                append(entry.evidence)
            }
            val textTokens = MemoryRetrievalText.tokens(text)
            val sourceTokens = MemoryRetrievalText.tokens(entry.source)
            val keywordTerms = queryTokens.filter { it in textTokens }
            val sourceTerms = queryTokens.filter { it in sourceTokens }
            val keyword = lexicalScore(queryTokens, textTokens, compactQuery, text)
            val source = if (queryTokens.isEmpty()) 0.0 else sourceTerms.size.toDouble() / queryTokens.size
            val semanticValue = semantic.values?.get(entry.id)
                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            val relevant = keyword >= MIN_KEYWORD || source >= MIN_SOURCE ||
                (semanticValue != null && semanticValue >= MIN_SEMANTIC)
            if (!relevant) return@mapNotNull null

            val freshness = freshnessScore(now, entry.updatedAt)
            val scopeScore = scopeScore(entry.scope)
            val keywordWeight = if (semanticValue == null) KEYWORD_ONLY_WEIGHT else HYBRID_KEYWORD_WEIGHT
            val score = (semanticValue ?: 0.0) * SEMANTIC_WEIGHT +
                keyword * keywordWeight +
                source * SOURCE_WEIGHT +
                freshness * TIME_WEIGHT +
                scopeScore * SCOPE_WEIGHT
            val matchedTerms = (keywordTerms + sourceTerms).distinct().take(8)
            val matchedFields = matchedFields(entry, matchedTerms)
            RankedMemoryEntry(
                entry = entry,
                explanation = MemoryMatchExplanation(
                    keyword = keyword,
                    source = source,
                    freshness = freshness,
                    scope = scopeScore,
                    semantic = semanticValue,
                    score = score.coerceIn(0.0, 1.0),
                    matchedTerms = matchedTerms,
                    matchedFields = matchedFields,
                    reasons = reasons(
                        semanticValue = semanticValue,
                        keywordTerms = keywordTerms,
                        sourceTerms = sourceTerms,
                        entry = entry,
                        now = now,
                    ),
                ),
            )
        }.sortedWith(
            compareByDescending<RankedMemoryEntry> { it.explanation.score }
                .thenByDescending { it.entry.updatedAt }
                .thenBy { it.entry.id }
        ).take(limit.coerceIn(1, 30))

        return MemoryRetrievalResult(
            mode = if (hybrid) MemoryRetrievalMode.HYBRID else MemoryRetrievalMode.KEYWORD_ONLY,
            semanticStatus = semantic.status,
            semanticReason = semantic.reason,
            weights = if (hybrid) hybridWeights() else keywordOnlyWeights(),
            entries = ranked,
        )
    }

    private data class SemanticScores(
        val status: MemorySemanticStatus,
        val reason: String,
        val values: Map<String, Double>?,
    )

    private suspend fun readSemanticScores(
        scorer: MemorySemanticScorer?,
        query: String,
        entries: List<MemoryEntry>,
    ): SemanticScores {
        if (scorer == null) {
            return SemanticScores(MemorySemanticStatus.UNAVAILABLE, NO_SEMANTIC_REASON, null)
        }
        if (entries.isEmpty()) {
            return SemanticScores(
                MemorySemanticStatus.AVAILABLE,
                "语义索引已绑定；当前没有可检索条目",
                emptyMap(),
            )
        }
        return try {
            val raw = scorer.scores(query, entries)
            if (raw.isEmpty()) {
                SemanticScores(MemorySemanticStatus.FAILED, "语义索引未返回可用分数，已回退", null)
            } else if (raw.values.any { !it.isFinite() || it !in 0.0..1.0 }) {
                SemanticScores(MemorySemanticStatus.FAILED, "语义索引返回了无效分数，已回退", null)
            } else {
                val knownIds = entries.mapTo(hashSetOf()) { it.id }
                val usable = raw.filterKeys { it in knownIds }
                if (usable.size != entries.size) {
                    SemanticScores(
                        MemorySemanticStatus.FAILED,
                        "语义索引仅返回 ${usable.size}/${entries.size} 条分数，已整体回退",
                        null,
                    )
                } else {
                    SemanticScores(
                        MemorySemanticStatus.AVAILABLE,
                        "语义索引已返回 ${usable.size}/${entries.size} 条分数",
                        usable,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            SemanticScores(
                MemorySemanticStatus.FAILED,
                "语义索引调用失败：${failure.javaClass.simpleName}，已回退",
                null,
            )
        }
    }

    private fun lexicalScore(
        queryTokens: List<String>,
        textTokens: List<String>,
        compactQuery: String,
        text: String,
    ): Double {
        val coverage = if (queryTokens.isEmpty()) {
            0.0
        } else {
            queryTokens.count { it in textTokens }.toDouble() / queryTokens.size
        }
        val phrase = compactQuery.isNotEmpty() &&
            MemoryRetrievalText.compact(text).contains(compactQuery)
        return if (phrase) maxOf(coverage, 0.95) else coverage
    }

    private fun freshnessScore(now: Long, updatedAt: Long): Double {
        val age = (now - updatedAt).coerceAtLeast(0L)
        return 1.0 / (1.0 + age.toDouble() / FRESHNESS_WINDOW_MILLIS)
    }

    private fun scopeScore(scope: String): Double = runCatching {
        when (MemoryAutomationPolicy.parseScope(scope).type) {
            AgentMemoryScopeType.GLOBAL -> 0.45
            AgentMemoryScopeType.PROJECT -> 0.72
            AgentMemoryScopeType.TASK,
            AgentMemoryScopeType.CONVERSATION,
            AgentMemoryScopeType.MEETING -> 1.0
        }
    }.getOrDefault(0.45)

    private fun matchedFields(entry: MemoryEntry, terms: List<String>): List<String> {
        if (terms.isEmpty()) return emptyList()
        return listOf(
            "topic" to entry.topic,
            "content" to entry.content,
            "evidence" to entry.evidence,
            "source" to entry.source,
        ).filter { (_, value) ->
            val tokens = MemoryRetrievalText.tokens(value)
            terms.any { it in tokens }
        }.map { it.first }
    }

    private fun reasons(
        semanticValue: Double?,
        keywordTerms: List<String>,
        sourceTerms: List<String>,
        entry: MemoryEntry,
        now: Long,
    ): List<String> = buildList {
        if (semanticValue != null) add("语义索引分数 ${format(semanticValue)}")
        if (keywordTerms.isNotEmpty()) {
            add("关键词命中：${keywordTerms.take(6).joinToString("、")}")
        }
        if (sourceTerms.isNotEmpty()) add("来源命中：${entry.source}")
        add("时间：${freshnessLabel(now, entry.updatedAt)}")
        add("作用域：${scopeLabel(entry.scope)}")
    }

    private fun freshnessLabel(now: Long, updatedAt: Long): String {
        val age = (now - updatedAt).coerceAtLeast(0L)
        val hours = age / 3_600_000.0
        val days = hours / 24.0
        return when {
            hours < 1 -> "刚刚确认"
            days < 1 -> "今天确认"
            days < 30 -> "${days.toInt().coerceAtLeast(1)} 天前确认"
            days < 365 -> "${(days / 30).toInt().coerceAtLeast(1)} 个月前确认"
            else -> "${(days / 365).toInt().coerceAtLeast(1)} 年前确认"
        }
    }

    private fun scopeLabel(scope: String): String = runCatching {
        when (MemoryAutomationPolicy.parseScope(scope).type) {
            AgentMemoryScopeType.GLOBAL -> "全局"
            AgentMemoryScopeType.PROJECT -> "项目"
            AgentMemoryScopeType.CONVERSATION -> "当前会话"
            AgentMemoryScopeType.TASK -> "当前任务"
            AgentMemoryScopeType.MEETING -> "当前会议"
        }
    }.getOrDefault(scope)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun hybridWeights(): Map<String, Double> = linkedMapOf(
        "semantic" to SEMANTIC_WEIGHT,
        "keyword" to HYBRID_KEYWORD_WEIGHT,
        "source" to SOURCE_WEIGHT,
        "time" to TIME_WEIGHT,
        "scope" to SCOPE_WEIGHT,
    )

    private fun keywordOnlyWeights(): Map<String, Double> = linkedMapOf(
        "keyword" to KEYWORD_ONLY_WEIGHT,
        "source" to SOURCE_WEIGHT,
        "time" to TIME_WEIGHT,
        "scope" to SCOPE_WEIGHT,
    )
}

private object MemoryRetrievalText {
    private val CJK_RUN = Regex("[\\u4e00-\\u9fff]+")
    private val ASCII_TOKEN = Regex("[a-z0-9_]{2,}")
    private val SEPARATORS = Regex("[\\p{P}\\p{Z}\\s]+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(SEPARATORS, " ")
        .trim()

    fun compact(text: String): String = normalize(text).replace(" ", "")

    fun tokens(text: String): List<String> {
        val normalized = normalize(text)
        val output = linkedSetOf<String>()
        ASCII_TOKEN.findAll(normalized).forEach { output += it.value }
        CJK_RUN.findAll(normalized).forEach { match ->
            val run = match.value
            if (run.length == 1) {
                output += run
            } else {
                for (index in 0 until run.length - 1) {
                    output += run.substring(index, index + 2)
                }
            }
        }
        return output.toList()
    }
}
