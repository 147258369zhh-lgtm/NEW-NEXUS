package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AgentMemoryScope

@Immutable
data class AgentMemoryUiState(
    val enabled: Boolean = true,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val draft: String = "",
    val savedContent: String = "",
    val savedRevision: String = "",
    val draftBytes: Int = 0,
    val maxBytes: Int = AgentMemoryStore.MAX_FILE_BYTES,
    val coreBudgetChars: Int = io.github.mangi.eta.agent.memory.TaskMemorySelector.coreBudget(null),
    val totalBudgetChars: Int = io.github.mangi.eta.agent.memory.TaskMemorySelector.budget(null),
    val scopes: List<AgentMemoryScope> = listOf(AgentMemoryScope.GLOBAL),
    val selectedScope: AgentMemoryScope = AgentMemoryScope.GLOBAL,
    val scopeNames: Map<String, String> = emptyMap(),
    val characters: Map<String, String> = emptyMap(),
    val notice: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = draft != savedContent
    val canSave: Boolean
        get() = !isLoading && !isSaving && hasUnsavedChanges &&
            draftBytes <= maxBytes
}
