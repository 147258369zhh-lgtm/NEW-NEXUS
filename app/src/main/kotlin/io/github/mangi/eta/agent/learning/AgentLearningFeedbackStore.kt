package io.github.mangi.eta.agent.learning

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONObject

internal data class AgentLearningFeedback(
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val outcome: String,
    val rating: Int? = null,
    val correction: String = "",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("run_id", runId).put("outcome", outcome)
        .put("rating", rating ?: JSONObject.NULL).put("correction", correction.take(2_000))
        .put("tags", tags.map { it.take(40) }.take(12)).put("created_at", createdAt)
}

/** Append-only, bounded feedback log. It stores corrections, never prompts or credentials. */
internal class AgentLearningFeedbackStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "learning/feedback.jsonl")

    @Synchronized
    fun append(feedback: AgentLearningFeedback) {
        require(feedback.runId.isNotBlank())
        require(feedback.rating == null || feedback.rating in 1..5)
        file.parentFile?.mkdirs()
        file.appendText(feedback.toJson().toString() + "\n", Charsets.UTF_8)
        trimIfNeeded()
    }

    @Synchronized
    fun recent(limit: Int = 100): List<AgentLearningFeedback> = if (!file.isFile) emptyList() else
        file.readLines(Charsets.UTF_8).asReversed().take(limit.coerceIn(1, 500)).mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                AgentLearningFeedback(
                    id = json.optString("id"), runId = json.optString("run_id"),
                    outcome = json.optString("outcome"), rating = json.optInt("rating").takeIf { !json.isNull("rating") },
                    correction = json.optString("correction"), tags = json.optJSONArray("tags")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                    createdAt = json.optLong("created_at"),
                )
            }.getOrNull()
        }

    private fun trimIfNeeded() {
        if (file.length() <= MAX_BYTES) return
        val keep = file.readLines(Charsets.UTF_8).takeLast(MAX_LINES)
        file.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024L; const val MAX_LINES = 2_000 }
}
