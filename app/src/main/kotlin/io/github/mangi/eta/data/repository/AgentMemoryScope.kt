package io.github.mangi.eta.data.repository

/** Persistent memory scopes. GLOBAL keeps the original memory/MEMORY.md location. */
enum class AgentMemoryScopeType(val path: String) {
    GLOBAL("global"),
    PROJECT("project"),
    CONVERSATION("conversation"),
    TASK("task"),
    MEETING("meeting"),
    ;

    companion object {
        fun fromPath(value: String): AgentMemoryScopeType? = entries.firstOrNull { it.path == value }
    }
}

data class AgentMemoryScope(
    val type: AgentMemoryScopeType,
    val id: String? = null,
) {
    init {
        if (type == AgentMemoryScopeType.GLOBAL) require(id == null) { "GLOBAL scope cannot have an id" }
        else require(id != null && ID_PATTERN.matches(id)) { "memory scope id is invalid" }
    }

    val key: String get() = if (type == AgentMemoryScopeType.GLOBAL) type.path else "${type.path}:$id"

    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val GLOBAL = AgentMemoryScope(AgentMemoryScopeType.GLOBAL)

        fun project(id: String) = AgentMemoryScope(AgentMemoryScopeType.PROJECT, id)
        fun conversation(id: String) = AgentMemoryScope(AgentMemoryScopeType.CONVERSATION, id)
        fun task(id: String) = AgentMemoryScope(AgentMemoryScopeType.TASK, id)
        fun meeting(id: String) = AgentMemoryScope(AgentMemoryScopeType.MEETING, id)

        fun fromPath(type: String, id: String): AgentMemoryScope? =
            AgentMemoryScopeType.fromPath(type)?.takeIf { it != AgentMemoryScopeType.GLOBAL }
                ?.let { runCatching { AgentMemoryScope(it, id) }.getOrNull() }
    }
}
