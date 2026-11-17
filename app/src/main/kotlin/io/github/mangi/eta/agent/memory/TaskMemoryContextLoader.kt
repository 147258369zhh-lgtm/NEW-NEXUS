package io.github.mangi.eta.agent.memory

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import kotlinx.coroutines.CancellationException

/** Main and child runs use the same ownership, retrieval, deduplication and total budget. */
internal object TaskMemoryContextLoader {
    suspend fun build(
        context: Context,
        scopes: List<AgentMemoryScope>,
        query: String,
        contextWindow: Int?,
        globalSnapshot: AgentMemorySnapshot? = null,
    ): AgentMemoryContext {
        val memoryEnabled = AgentMemoryRepository.isEnabled()
        val allowed = scopes.distinctBy { it.key }
        val keys = allowed.map { it.key }.toSet()
        val snapshots = if (memoryEnabled) allowed.mapNotNull { scope ->
            readSource("document") {
                scope to if (scope == AgentMemoryScope.GLOBAL && globalSnapshot != null) globalSnapshot
                else AgentMemoryRepository.snapshot(scope)
            }
        }.toMap() else emptyMap()
        val items = snapshots.flatMap { (scope, snapshot) ->
            TaskMemorySelector.documentItems(scope.key, snapshot.content, snapshot.revision)
        }.toMutableList()
        if (memoryEnabled) readSource("reviewed") {
            MemoryReviewRepository(LearningDatabase.get(context)).active(allowed)
        }.orEmpty().forEach {
            items += TaskMemoryItem(it.id, it.scope, it.content, "${it.source}@${it.updatedAt}", it.layer == "core", it.topic)
        }
        readSource("experience") {
            if (LearningSettingsStore.isEnabled(context)) LearningRepository.get(context).activeRules(keys) else emptyList()
        }.orEmpty().forEach {
            // Pinning affects management, not permission or permanent context residency.
            items += TaskMemoryItem(it.id, it.scope, it.content, "经验库 v${it.currentVersion}", title = it.title)
        }
        val selection = TaskMemorySelector.select(items, keys, query, contextWindow)
        val global = snapshots[AgentMemoryScope.GLOBAL]
        return AgentMemoryContext(
            enabled = memoryEnabled,
            revision = global?.revision.orEmpty(), byteSize = snapshots.values.sumOf { it.byteSize },
            coreContent = selection.render(), coreTruncated = selection.omitted.values.any { it == "budget" },
            headingIndex = "", coreBudgetChars = TaskMemorySelector.coreBudget(contextWindow),
            // When factual memory is disabled, the existing learning switch still governs experience.
            learnedContent = if (memoryEnabled) "" else selection.render(),
            selectedEntries = selection.selected.map(TaskMemorySelector::render),
            sourceRevisions = snapshots.entries.associate { it.key.key to it.value.revision },
        ).let { if (memoryEnabled) it else it.copy(coreContent = "") }
    }

    private suspend fun <T> readSource(name: String, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        AndroidAgentLogger.warn("Memory $name source unavailable: type=${failure.safeLogType()}")
        null
    }
}
