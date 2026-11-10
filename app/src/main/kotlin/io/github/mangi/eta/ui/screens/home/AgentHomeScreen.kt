package io.github.mangi.eta.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.components.AgentChatBody
import io.github.mangi.eta.ui.components.AgentConversationLoadState
import io.github.mangi.eta.ui.components.LocalRoleplayChat
import io.github.mangi.eta.ui.components.LocalAgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import io.github.mangi.eta.ui.components.ConversationStage
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.roleplayChatUi
import io.github.mangi.eta.ui.components.NexusNotepadDialog
import io.github.zhh_lgtm.nexus.application.api.WorkflowSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentChatHome：首屏为聊天主舞台。
 *
 * 顶部入口统一由 [io.github.mangi.eta.ui.app.AgentAppShell] 提供，
 * 本 Screen 只负责消息流、Run trace、工具摘要和底部输入框。
 */
@Composable
internal fun AgentHomeScreen(
    state: AgentChatHomeUiState,
    modelPickerState: AgentModelPickerUiState,
    conversationKey: String?,
    onAction: (AgentHomeAction) -> Unit,
    onSubmitMessage: (String, Boolean) -> Boolean,
    onNewConversation: () -> Unit,
    isLoadingConversation: Boolean = false,
    conversationLoadFailed: Boolean = false,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showNotepad by remember { mutableStateOf(false) }
    val workflowSnapshot by produceState<WorkflowSnapshot?>(
        initialValue = null,
        conversationKey,
        state.messages.size,
        state.isStreaming,
    ) {
        value = conversationKey?.takeIf(String::isNotBlank)?.let { id ->
            withContext(Dispatchers.IO) { GoalPlanNotepadStore.get(context).snapshot(id) }
        }
    }
    // key 内保持完整分支，避免提前返回遗留无法转换为 DEX 的编译器标记。
    ConversationStage(ConversationSubject.chat(conversationKey)) {
        if (!state.isConversationLoaded) {
            AgentConversationLoadState(
                isLoading = isLoadingConversation,
                failed = conversationLoadFailed,
                onRetry = { onAction(AgentHomeAction.RetryConversationLoad) },
                modifier = modifier,
            )
        } else {
            val browser = remember(conversationKey) {
                BrowserSessionRegistry.get(conversationKey ?: BrowserSessionRegistry.MANUAL_SESSION_KEY)
            }
            CompositionLocalProvider(
                LocalRoleplayChat provides state.roleplayChatUi(),
                LocalAgentBrowserSession provides browser,
            ) {
                AgentChatBody(
                    messages = state.messages,
                    modelPickerState = modelPickerState,
                    isCompacting = state.isCompacting,
                    input = state.input,
                    isStreaming = state.isStreaming,
                    reasoningEffort = state.reasoningEffort,
                    workflowMode = state.workflowMode,
                    workflowSnapshot = workflowSnapshot,
                    availableReasoningEfforts = state.availableReasoningEfforts,
                    pendingImages = state.pendingImages,
                    pendingFileReferences = state.pendingFileReferences,
                    queuedMessages = state.queuedMessages,
                    inputSyncVersion = state.inputSyncVersion,
                    onInputSyncApplied = { version, text ->
                        onAction(AgentHomeAction.InputSyncApplied(conversationKey, version, text))
                    },
                    focusInputOnSync = state.focusInputOnSync,
                    messageEdit = state.messageEdit,
                    onReasoningEffortChange = { onAction(AgentHomeAction.ReasoningEffortChanged(it)) },
                    onWorkflowModeChange = { onAction(AgentHomeAction.WorkflowModeChanged(it)) },
                    onCompactContext = { onAction(AgentHomeAction.CompactContext) },
                    onConfigureContextBudget = { onAction(AgentHomeAction.ConfigureContextBudget) },
                    canCompactContext = state.canCompactContext,
                    onModelSelected = { onAction(AgentHomeAction.ModelSelected(it)) },
                    onSubmit = { text -> onSubmitMessage(text, false) },
                    onInterruptAndSubmit = { text -> onSubmitMessage(text, true) },
                    onSteerQueuedMessage = { id -> onAction(AgentHomeAction.SteerQueuedMessage(id)) },
                    onRecallQueuedMessage = { id, draft -> onAction(AgentHomeAction.RecallQueuedMessage(id, draft)) },
                    onRemoveQueuedMessage = { id -> onAction(AgentHomeAction.RemoveQueuedMessage(id)) },
                    onStop = { onAction(AgentHomeAction.StopRun) },
                    onAttachImage = { uri -> onAction(AgentHomeAction.ImageAttached(uri)) },
                    onRemoveImage = { id -> onAction(AgentHomeAction.RemoveImage(id)) },
                    onAttachFiles = { uris -> onAction(AgentHomeAction.FilesAttached(uris)) },
                    onAttachFolder = { uri -> onAction(AgentHomeAction.FolderAttached(uri)) },
                    onAttachFilePath = { path -> onAction(AgentHomeAction.FilePathAttached(path)) },
                    onRemoveFileReference = { id -> onAction(AgentHomeAction.RemoveFileReference(id)) },
                    onEditMessage = { id -> onAction(AgentHomeAction.EditMessage(id)) },
                    onCancelMessageEdit = { onAction(AgentHomeAction.CancelMessageEdit) },
                    onDeleteMessage = { id -> onAction(AgentHomeAction.DeleteMessage(id)) },
                    onRegenerateMessage = { id -> onAction(AgentHomeAction.RegenerateMessage(id)) },
                    onForkMessage = { id -> onAction(AgentHomeAction.ForkMessage(id)) },
                    onSelectReplyCandidate = { id, index -> onAction(AgentHomeAction.SelectReplyCandidate(id, index)) },
                    onSuggestionClick = { prompt ->
                        onAction(AgentHomeAction.SubmitMessage(prompt))
                    },
                    onRunTraceClick = { onAction(AgentHomeAction.ExpandRunTrace) },
                    onOpenPermissions = { onAction(AgentHomeAction.OpenPermissions) },
                    onOpenBrowser = { onAction(AgentHomeAction.OpenBrowser) },
                    onNewConversation = onNewConversation,
                    onOpenNotepad = conversationKey?.takeIf(String::isNotBlank)?.let {
                        { showNotepad = true }
                    },
                    hasEarlierMessages = state.hasEarlierMessages,
                    messageStartIndex = state.messageStartIndex,
                    historyLoadState = historyLoadState,
                    onLoadEarlierMessages = { onAction(AgentHomeAction.LoadEarlierMessages) },
                    onHistoryViewportChanged = { onAction(AgentHomeAction.HistoryViewportChanged(it)) },
                    modifier = modifier,
                )
                if (showNotepad && conversationKey != null) {
                    NexusNotepadDialog(
                        context = context,
                        scopeId = conversationKey,
                        onDismiss = { showNotepad = false },
                        onAgentAction = { prompt ->
                            showNotepad = false
                            onSubmitMessage(prompt, false)
                        },
                    )
                }
            }
        }
    }
}
