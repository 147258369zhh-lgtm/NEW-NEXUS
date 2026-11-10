package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.conversations.ConversationFolderException
import io.github.mangi.eta.fork.conversations.ConversationFolderTree
import io.github.mangi.eta.fork.conversations.MAX_CONVERSATION_FOLDER_DEPTH
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal sealed interface ConversationFolderDialogRequest {
    data class Menu(val folder: ConversationFolderUi) : ConversationFolderDialogRequest
    data class Create(val parentId: String?) : ConversationFolderDialogRequest
    data class Rename(val folder: ConversationFolderUi) : ConversationFolderDialogRequest
    data class MoveFolder(val folder: ConversationFolderUi) : ConversationFolderDialogRequest
    data class MoveConversation(val conversation: ConversationSummaryUi) : ConversationFolderDialogRequest
    data class Delete(val folder: ConversationFolderUi) : ConversationFolderDialogRequest
}

@Composable
internal fun ConversationFolderDialogs(
    request: ConversationFolderDialogRequest?,
    folders: List<ConversationFolderUi>,
    onRequestChange: (ConversationFolderDialogRequest?) -> Unit,
    onCreate: suspend (String, String?) -> Unit,
    onRename: suspend (String, String) -> Unit,
    onMoveFolder: suspend (String, String?) -> Unit,
    onMoveConversation: suspend (String, String?) -> Unit,
    onDelete: suspend (String) -> Unit,
) {
    if (request == null) return
    key(request) {
        var name by remember { mutableStateOf((request as? ConversationFolderDialogRequest.Rename)?.folder?.name.orEmpty()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val tree = remember(folders) { ConversationFolderTree(folders, { it.id }, { it.parentId }) }
        val dismiss = { if (!busy) onRequestChange(null) }
        fun submit(operation: suspend () -> Unit) {
            if (busy) return
            busy = true
            error = null
            scope.launch {
                try {
                    operation()
                    onRequestChange(null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = (failure as? ConversationFolderException)?.code ?: "UNKNOWN"
                } finally {
                    busy = false
                }
            }
        }
        val title = when (request) {
            is ConversationFolderDialogRequest.Menu -> request.folder.name
            is ConversationFolderDialogRequest.Create -> stringResource(R.string.conversation_folder_create_title)
            is ConversationFolderDialogRequest.Rename -> stringResource(R.string.conversation_folder_rename_title)
            is ConversationFolderDialogRequest.MoveFolder -> stringResource(R.string.folder_tree_move)
            is ConversationFolderDialogRequest.MoveConversation -> stringResource(R.string.conversation_folder_move_title)
            is ConversationFolderDialogRequest.Delete -> stringResource(R.string.folder_tree_delete)
        }
        WindowDialog(show = true, cornerRadius = DialogDefaults.CornerRadius, title = title, onDismissRequest = dismiss) {
            Column {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    when (request) {
                        is ConversationFolderDialogRequest.Menu -> {
                            TextButton(
                                text = stringResource(R.string.folder_tree_create_child),
                                enabled = tree.ancestors(request.folder.id).size + 2 <= MAX_CONVERSATION_FOLDER_DEPTH,
                                onClick = { onRequestChange(ConversationFolderDialogRequest.Create(request.folder.id)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(stringResource(R.string.conversation_folder_rename_action),
                                onClick = { onRequestChange(ConversationFolderDialogRequest.Rename(request.folder)) }, modifier = Modifier.fillMaxWidth())
                            TextButton(stringResource(R.string.folder_tree_move),
                                onClick = { onRequestChange(ConversationFolderDialogRequest.MoveFolder(request.folder)) }, modifier = Modifier.fillMaxWidth())
                            TextButton(stringResource(R.string.folder_tree_delete),
                                onClick = { onRequestChange(ConversationFolderDialogRequest.Delete(request.folder)) }, modifier = Modifier.fillMaxWidth())
                        }
                        is ConversationFolderDialogRequest.Create, is ConversationFolderDialogRequest.Rename -> {
                            val parentId = when (request) {
                                is ConversationFolderDialogRequest.Create -> request.parentId
                                is ConversationFolderDialogRequest.Rename -> folders.firstOrNull { it.id == request.folder.id }?.parentId
                            }
                            Text(text = parentId?.let { id ->
                                (tree.ancestors(id) + folders.filter { it.id == id }).joinToString(" / ") { it.name }
                            } ?: stringResource(R.string.folder_tree_root), style = MiuixTheme.textStyles.footnote2)
                            TextField(value = name, onValueChange = { name = it; error = null },
                                enabled = !busy, label = stringResource(R.string.conversation_folder_name_hint),
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        is ConversationFolderDialogRequest.MoveFolder, is ConversationFolderDialogRequest.MoveConversation -> {
                            fun move(destination: String?) = submit {
                                when (request) {
                                    is ConversationFolderDialogRequest.MoveFolder -> onMoveFolder(request.folder.id, destination)
                                    is ConversationFolderDialogRequest.MoveConversation -> onMoveConversation(request.conversation.id, destination)
                                }
                            }
                            TextButton(text = stringResource(if (request is ConversationFolderDialogRequest.MoveFolder) R.string.folder_tree_root else R.string.conversation_folder_none),
                                enabled = !busy && (request !is ConversationFolderDialogRequest.MoveFolder || tree.canMove(request.folder.id, null)),
                                onClick = { move(null) }, modifier = Modifier.fillMaxWidth())
                            tree.rows.forEach { row ->
                                val folder = row.item
                                TextButton(text = (tree.ancestors(folder.id) + folder).joinToString(" / ") { it.name },
                                    enabled = !busy && (request !is ConversationFolderDialogRequest.MoveFolder || tree.canMove(request.folder.id, folder.id)),
                                    onClick = { move(folder.id) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                        is ConversationFolderDialogRequest.Delete -> Text(stringResource(R.string.folder_tree_delete_message))
                    }
                }
                error?.let { code ->
                    Text(text = stringResource(when (code) {
                        "INVALID_NAME" -> R.string.folder_tree_invalid_name
                        "DUPLICATE_NAME" -> R.string.folder_tree_duplicate
                        "NOT_FOUND", "CONVERSATION_NOT_FOUND" -> R.string.folder_tree_not_found
                        "DEPTH_LIMIT" -> R.string.folder_tree_depth_limit
                        "CYCLE" -> R.string.folder_tree_cycle
                        else -> R.string.folder_tree_failed
                    }), color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
                if (busy) Text(stringResource(R.string.folder_tree_saving), modifier = Modifier.padding(top = 12.dp))
                when (request) {
                    is ConversationFolderDialogRequest.Create, is ConversationFolderDialogRequest.Rename, is ConversationFolderDialogRequest.Delete -> {
                        MiuixDialogActions(
                            confirmText = stringResource(when (request) {
                                is ConversationFolderDialogRequest.Create -> R.string.conversation_folder_create_confirm
                                is ConversationFolderDialogRequest.Delete -> R.string.action_delete
                                else -> R.string.action_save
                            }),
                            confirmEnabled = !busy && (request is ConversationFolderDialogRequest.Delete || name.isNotBlank()),
                            cancelEnabled = !busy, destructive = request is ConversationFolderDialogRequest.Delete,
                            onCancel = dismiss,
                            onConfirm = { submit {
                                when (request) {
                                    is ConversationFolderDialogRequest.Create -> onCreate(name, request.parentId)
                                    is ConversationFolderDialogRequest.Rename -> onRename(request.folder.id, name)
                                    is ConversationFolderDialogRequest.Delete -> onDelete(request.folder.id)
                                }
                            } }, modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    else -> TextButton(stringResource(R.string.action_cancel), onClick = dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
