package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import kotlinx.coroutines.flow.first

internal data class MemoryManagementTargets(
    val scopes: List<AgentMemoryScope>,
    val names: Map<String, String>,
    val characters: Map<String, String>,
    val unavailableSources: List<String> = emptyList(),
)

/** Discover actual objects and existing files; opening the catalog never creates a memory file. */
internal object MemoryManagementCatalog {
    suspend fun load(context: Context): MemoryManagementTargets {
        // Character data is intentionally lazy at startup. The memory catalog is also an
        // independent entry point and must bind its owner before querying the role list.
        CharacterRepository.initialize(context.applicationContext)
        val scopes = linkedMapOf(AgentMemoryScope.GLOBAL.key to AgentMemoryScope.GLOBAL)
        val names = linkedMapOf("global" to "全局记忆")
        val unavailable = mutableListOf<String>()
        suspend fun <T> source(name: String, fallback: T, read: suspend () -> T): T = try { read() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                unavailable += name
                io.github.mangi.eta.core.AndroidAgentLogger.warn("Memory catalog source unavailable: $name type=${failure.javaClass.simpleName}")
                fallback
            }
        fun add(type: String, id: String, name: String) {
            val scope = AgentMemoryScope.fromPath(type, id) ?: return
            scopes[scope.key] = scope
            names[scope.key] = name
        }
        source("项目", emptyList()) { ProjectRepository.projects(context).first() }.forEach { add("project", it.id, it.name) }
        source("会话", emptyList()) { EtaDatabase.get(context).conversationDao().conversationIdentities() }.forEach { add("conversation", it.id, it.title) }
        source("任务", emptyList()) { AgentTaskDatabase.get(context).agentTaskDao().definitionsNow() }.forEach { add("task", it.id, it.title) }
        source("会议", emptyList()) { MeetingDatabase.get(context).meetingDao().roomsFlow().first() }.forEach { add("meeting", it.id, it.title) }
        source("已保存记忆", emptyList()) { AgentMemoryRepository.listScopes() }.forEach { scopes[it.key] = it }
        source("经验", emptyList()) { LearningDatabase.get(context).memoryDao().entries() }.forEach { entry ->
            val key = entry.scope
            AgentMemoryScope.fromPath(key.substringBefore(':'), key.substringAfter(':', ""))?.let { scopes[it.key] = it }
        }
        val characters = source("角色", emptyList()) { CharacterRepository.list() }.associate { it.id to it.card.name }.toMutableMap()
        source("已保存角色记忆", emptyList()) { CharacterMemoryRepository.listCharacterIds(context) }
            .forEach { characters.putIfAbsent(it, "已保存角色 · ${it.takeLast(8)}") }
        characters.forEach { (id, name) -> names["character:$id"] = name }
        return MemoryManagementTargets(scopes.values.toList(), names, characters, unavailable)
    }
}
