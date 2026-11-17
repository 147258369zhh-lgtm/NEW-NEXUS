package io.github.mangi.eta.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.projects.ProjectEntity
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectOption
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class ConversationProjectChoices(val subject: ProjectSubjectOption?, val projects: List<ProjectEntity>)

@Composable
internal fun ConversationProjectDialog(conversationId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember(conversationId) { mutableStateOf<String?>(null) }
    var selected by remember(conversationId) { mutableStateOf<ProjectEntity?>(null) }
    var busy by remember(conversationId) { mutableStateOf(false) }
    var refreshKey by remember(conversationId) { mutableIntStateOf(0) }
    val choices by produceState<ConversationProjectChoices?>(null, conversationId, refreshKey) {
        value = null
        try {
            val subject = ProjectRepository.subjectOptions(context)
                .firstOrNull { it.type == ProjectSubjectType.CONVERSATION && it.id == conversationId }
            val projects = ProjectRepository.projects(context).first().filter { it.archivedAt == null }
            value = ConversationProjectChoices(subject, projects)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = context.getString(R.string.projects_details_load_failed)
        }
    }
    val subject = choices?.subject
    if (selected == null) {
        WindowDialog(show = true, title = stringResource(R.string.project_add_conversation), onDismissRequest = onDismiss) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    error != null -> {
                        Text(error.orEmpty(), color = MiuixTheme.colorScheme.error)
                        TextButton(text = stringResource(R.string.action_retry), onClick = { error = null; refreshKey++ })
                    }
                    choices == null -> Text(stringResource(R.string.projects_loading))
                    subject == null -> Text(stringResource(R.string.project_subject_unavailable))
                    choices!!.projects.isEmpty() -> Text(stringResource(R.string.project_no_active_projects))
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(choices!!.projects, key = { it.id }) { project ->
                            ArrowPreference(title = project.name,
                                summary = if (project.id == subject.projectId) stringResource(R.string.project_current) else project.rootDisplayName,
                                onClick = { if (project.id != subject.projectId) selected = project })
                        }
                    }
                }
                TextButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
            }
        }
    } else {
        val target = selected ?: return
        WindowDialog(show = true, title = stringResource(R.string.project_add_conversation), onDismissRequest = { if (!busy) selected = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (subject?.projectName != null) stringResource(R.string.project_move_confirmation, subject.projectName, target.name)
                    else stringResource(R.string.project_bind_confirmation, target.name))
                error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                MiuixDialogActions(confirmText = stringResource(R.string.projects_bind), confirmEnabled = !busy && subject != null,
                    onCancel = { if (!busy) selected = null }, onConfirm = {
                        val option = subject ?: return@MiuixDialogActions
                        busy = true
                        scope.launch {
                            try {
                                ProjectRepository.bindSubjectIfUnchanged(context, target.id, option)
                                Toast.makeText(context, context.getString(R.string.project_binding_saved), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                selected = null
                                error = context.getString(R.string.project_subject_changed)
                            }
                            finally { busy = false }
                        }
                    })
            }
        }
    }
}
