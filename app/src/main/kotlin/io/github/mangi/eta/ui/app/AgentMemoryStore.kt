package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 记忆页的读取、编辑与保存状态；与会话和 Agent run 无共享状态。 */
internal class AgentMemoryStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    fun refreshMemory() {
        loadScope(memoryState.selectedScope)
    }

    fun selectScope(scope: AgentMemoryScope) {
        if (memoryState.isSaving || memoryState.isLoading) return
        if (scope == memoryState.selectedScope) return
        if (memoryState.hasUnsavedChanges) {
            memoryState = memoryState.copy(notice = appContext.getString(R.string.memory_scope_unsaved))
            return
        }
        loadScope(scope)
    }

    private fun loadScope(selectedScope: AgentMemoryScope) {
        memoryState = memoryState.copy(isLoading = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = AgentMemoryRepository.snapshot(selectedScope)
                val enabled = AgentMemoryRepository.isEnabled()
                val scopes = (listOf(AgentMemoryScope.GLOBAL) + AgentMemoryRepository.listScopes())
                    .distinctBy { it.key }
                val contextWindow = RuntimeConfigRepository.currentRuntimeConfig()?.contextWindow
                LoadedMemory(snapshot, enabled, scopes, AgentMemoryContextBuilder.coreBudgetChars(contextWindow))
            }.fold(
                onSuccess = { loaded ->
                    withContext(Dispatchers.Main) {
                        memoryState = AgentMemoryUiState(
                            enabled = loaded.enabled,
                            isLoading = false,
                            draft = loaded.snapshot.content,
                            savedContent = loaded.snapshot.content,
                            savedRevision = loaded.snapshot.revision,
                            draftBytes = loaded.snapshot.byteSize,
                            coreBudgetChars = loaded.coreBudget,
                            scopes = loaded.scopes,
                            selectedScope = selectedScope,
                        )
                    }
                },
                onFailure = { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_ui_load_failed") {
                        "Agent memory UI load failed: type=${throwable.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        memoryState = memoryState.copy(
                            isLoading = false,
                            notice = appContext.getString(R.string.state_ui_failed_to_read_memory_please_try_again_later_caeaa6),
                        )
                    }
                },
            )
        }
    }

    private data class LoadedMemory(
        val snapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot,
        val enabled: Boolean,
        val scopes: List<AgentMemoryScope>,
        val coreBudget: Int,
    )

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(
            draft = content,
            draftBytes = content.toByteArray(Charsets.UTF_8).size,
            notice = null,
        )
    }

    fun setMemoryEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { AgentMemoryRepository.setEnabled(enabled) }
                .fold(
                    onSuccess = {
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(enabled = enabled, notice = null)
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_toggle_failed") {
                            "Agent memory setting update failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(notice = appContext.getString(R.string.state_ui_memory_switch_failed_to_save_83b5d6))
                        }
                    },
                )
        }
    }

    fun saveMemory() {
        if (!memoryState.canSave) return
        val target = memoryState.draft
        val selectedScope = memoryState.selectedScope
        val revision = memoryState.savedRevision
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { replaceIfCurrent(selectedScope, target, revision) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                savedContent = snapshot.content,
                                savedRevision = snapshot.revision,
                                draft = if (memoryState.draft == target) {
                                    snapshot.content
                                } else {
                                    memoryState.draft
                                },
                                draftBytes = memoryState.draft.toByteArray(Charsets.UTF_8).size,
                                notice = appContext.getString(R.string.state_ui_memory_saved_a2c61c),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_save_failed") {
                            "Agent memory UI save failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_save_failed_1f501e),
                            )
                        }
                    },
                )
        }
    }

    fun clearMemory() {
        if (memoryState.isSaving || memoryState.isLoading) return
        val selectedScope = memoryState.selectedScope
        val revision = memoryState.savedRevision
        memoryState = memoryState.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            runCatching { replaceIfCurrent(selectedScope, "", revision) }
                .fold(
                    onSuccess = { snapshot ->
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                draft = "",
                                savedContent = "",
                                savedRevision = snapshot.revision,
                                draftBytes = 0,
                                notice = appContext.getString(R.string.state_ui_memory_cleared_b415bb),
                            )
                        }
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_memory_ui_clear_failed") {
                            "Agent memory UI clear failed: type=${throwable.safeLogType()}"
                        }
                        withContext(Dispatchers.Main) {
                            memoryState = memoryState.copy(
                                isSaving = false,
                                notice = throwable.message ?: appContext.getString(R.string.state_ui_memory_clearing_failed_7f0aba),
                            )
                        }
                    },
                )
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    private fun replaceIfCurrent(scope: AgentMemoryScope, content: String, revision: String): io.github.mangi.eta.data.repository.AgentMemorySnapshot =
        when (val result = AgentMemoryRepository.replaceAllIfRevision(scope, content, revision)) {
            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Success -> result.snapshot
            is io.github.mangi.eta.data.repository.AgentMemoryWriteResult.Conflict -> throw IllegalStateException("记忆已由其他会话更新，当前草稿已保留。请重新打开文档核对后保存。")
        }
}
