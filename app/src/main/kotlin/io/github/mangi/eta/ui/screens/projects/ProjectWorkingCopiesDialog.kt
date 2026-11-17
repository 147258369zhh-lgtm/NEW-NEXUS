package io.github.mangi.eta.ui.screens.projects

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.fork.projects.ProjectEntity
import io.github.mangi.eta.fork.projects.ProjectWorkspaceRepository
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ProjectWorkingCopiesDialog(context: Context, project: ProjectEntity, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    var rows by remember(project.id) { mutableStateOf<List<ProjectWorkspaceRepository.Copy>>(emptyList()) }
    var loadState by remember(project.id) { mutableStateOf(ProjectDetailsDataLoadState.Loading) }
    var refreshKey by remember(project.id) { mutableIntStateOf(0) }
    LaunchedEffect(project.id, refreshKey) {
        loadState = ProjectDetailsDataLoadState.Loading
        try {
            rows = withContext(Dispatchers.IO) { ProjectWorkspaceRepository.copies(context, project.id) }
            loadState = ProjectDetailsDataLoadState.Loaded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadState = ProjectDetailsDataLoadState.Failed }
    }
    WindowDialog(show = true, title = stringResource(R.string.project_copies_title), onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (loadState) {
                ProjectDetailsDataLoadState.Loading -> item { Text(stringResource(R.string.project_copies_loading), modifier = Modifier.padding(12.dp)) }
                ProjectDetailsDataLoadState.Failed -> {
                    item {
                        Column {
                            Text(stringResource(R.string.project_copies_load_failed), color = MiuixTheme.colorScheme.error)
                            TextButton(text = stringResource(R.string.action_retry), onClick = { refreshKey++ })
                        }
                    }
                }
                ProjectDetailsDataLoadState.Loaded -> if (rows.isEmpty()) item { Text(stringResource(R.string.project_copies_empty),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(12.dp)) }
            }
            if (loadState == ProjectDetailsDataLoadState.Loaded) items(rows, key = { it.relativePath }) { copy ->
                DetailListRow(title = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(copy.createdAt)),
                    icon = Icons.Rounded.FolderOpen, tone = EtaIconTone.DATA, onClick = { onOpen(copy.relativePath) })
            }
        }
    }
}
