package io.github.mangi.eta.fork.tasks

import org.json.JSONArray
import org.json.JSONObject

internal data class TaskCompletionCursor(val finishedAt: Long, val runIds: Set<String>) {
    fun accepts(run: AgentTaskRunEntity): Boolean = run.finishedAt?.let {
        it > finishedAt || (it == finishedAt && run.id !in runIds)
    } == true

    fun advance(run: AgentTaskRunEntity): TaskCompletionCursor =
        TaskCompletionCursor(requireNotNull(run.finishedAt), if (run.finishedAt == finishedAt) runIds + run.id else setOf(run.id))

    fun encode(): String = JSONObject().put("version", 1).put("finishedAt", finishedAt)
        .put("runIds", JSONArray(runIds.sorted())).toString()

    companion object {
        fun legacy(run: AgentTaskRunEntity, completed: List<AgentTaskRunEntity>): TaskCompletionCursor? =
            run.finishedAt?.takeIf { it >= 0 }?.let { finishedAt ->
                TaskCompletionCursor(finishedAt, completed.filter { it.finishedAt == finishedAt && it.id <= run.id }.map { it.id }.toSet())
            }

        fun parse(raw: String): TaskCompletionCursor? = runCatching {
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            val ids = json.getJSONArray("runIds")
            TaskCompletionCursor(json.getLong("finishedAt").also { require(it >= 0) },
                (0 until ids.length()).map { ids.getString(it) }.toSet())
        }.getOrNull()

        fun baseline(completed: List<AgentTaskRunEntity>, now: Long): TaskCompletionCursor {
            val finishedAt = completed.mapNotNull { it.finishedAt?.takeIf { time -> time >= 0 } }.maxOrNull() ?: now
            return TaskCompletionCursor(finishedAt, completed.filter { it.finishedAt == finishedAt }.map { it.id }.toSet())
        }
    }
}
