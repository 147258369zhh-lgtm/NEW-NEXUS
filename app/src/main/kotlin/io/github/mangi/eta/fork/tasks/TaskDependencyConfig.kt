package io.github.mangi.eta.fork.tasks

import org.json.JSONArray
import org.json.JSONObject

/** Durable task-to-task completion dependencies stored in an automation trigger's source field. */
internal data class TaskDependencyConfig(val taskIds: List<String>, val mode: Mode) {
    enum class Mode { ALL, ANY }

    init {
        require(taskIds.size in 1..20) { "请选择 1–20 个来源任务" }
        require(taskIds.all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }) { "来源任务 ID 无效" }
        require(taskIds.distinct().size == taskIds.size) { "来源任务不能重复" }
    }

    fun encode(): String = JSONObject().put("tasks", JSONArray(taskIds)).put("mode", mode.name).toString()

    companion object {
        /** Parses only the dependency JSON format; callers may retain legacy single-ID sources unchanged. */
        fun parse(source: String): TaskDependencyConfig {
            require(source.length in 1..4096) { "任务依赖配置无效" }
            val json = JSONObject(source)
            val tasks = json.getJSONArray("tasks")
            val ids = (0 until tasks.length()).map { index ->
                tasks.getString(index).also { require(it.isNotBlank()) { "来源任务 ID 无效" } }
            }
            require(json.keys().asSequence().all { it in setOf("tasks", "mode") }) { "任务依赖配置包含未知字段" }
            return TaskDependencyConfig(ids, Mode.valueOf(json.getString("mode").uppercase()))
        }

        fun isConfig(source: String): Boolean = source.trimStart().startsWith("{")

        /** Returns the completion(s) one trigger fire consumes; null means ALL is still waiting. */
        internal fun selectCompletions(
            taskIds: List<String>,
            candidates: Map<String, AgentTaskRunEntity>,
            mode: Mode,
        ): Map<String, AgentTaskRunEntity>? {
            val ordered = candidates.entries.sortedWith(
                compareBy<Map.Entry<String, AgentTaskRunEntity>> { it.value.finishedAt }.thenBy { it.value.id },
            )
            return when (mode) {
                Mode.ALL -> if (taskIds.all(candidates::containsKey)) taskIds.associateWith { candidates.getValue(it) } else null
                Mode.ANY -> ordered.firstOrNull()?.let { mapOf(it.key to it.value) }
            }
        }
    }
}
