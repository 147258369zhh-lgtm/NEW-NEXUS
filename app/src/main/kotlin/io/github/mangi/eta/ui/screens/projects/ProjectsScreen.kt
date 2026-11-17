package io.github.mangi.eta.ui.screens.projects

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.projects.ProjectDraft
import io.github.mangi.eta.fork.projects.ProjectEntity
import io.github.mangi.eta.fork.projects.ProjectException
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectOption
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.ui.app.WorkspaceArtifactStore
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.screens.terminal.WorkspaceHomePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import top.yukonga.miuix.kmp.basic.BasicComponent
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Project roots are intentionally URI identities: moving a conversation never moves files. */
@Composable
internal fun ProjectsScreen(
    onBack: () -> Unit,
    onOpenSubject: (ProjectSubjectOption) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenMemory: (String) -> Unit = {},
    pageTitle: String? = null,
    aboveContent: @Composable () -> Unit = {},
    onOpenProjectFiles: ((ProjectEntity) -> Unit)? = null,
    onOpenWorkspaceCopy: ((ProjectEntity, String) -> Unit)? = null,
    onOpenIndependentWorkspace: (() -> Unit)? = null,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var projectsRefreshKey by remember { mutableIntStateOf(0) }
    val projectsLoad by produceState<ProjectsLoadState>(ProjectsLoadState.Loading, context, projectsRefreshKey) {
        value = ProjectsLoadState.Loading
        try {
            var receivedSnapshot = false
            ProjectRepository.projects(context).collect { loaded ->
                receivedSnapshot = true
                value = ProjectsLoadState.Loaded(loaded)
            }
            if (!receivedSnapshot) value = ProjectsLoadState.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = ProjectsLoadState.Failed
        }
    }
    val projects = (projectsLoad as? ProjectsLoadState.Loaded)?.projects.orEmpty()
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var selectedRoot by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ProjectEntity?>(null) }
    var openCopy by remember { mutableStateOf<String?>(null) }
    val rootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        selectedRoot = uri
    }

    if (openCopy != null) {
        io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen(initialPath = openCopy!!, onBack = { openCopy = null })
        return
    }
    MiuixScaffoldPage(
        title = pageTitle ?: stringResource(R.string.projects_title),
        onBack = onBack,
        aboveContent = aboveContent,
    ) {
        item(key = "project-management") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("项目", style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                        NexaCompactAction(stringResource(R.string.projects_new), onClick = {
                            name = ""; selectedRoot = null; error = null; showCreate = true
                        }, icon = Icons.Rounded.FolderOpen)
                    }
                    NexaSegmentedTabs(
                        tabs = listOf("使用中 ${projects.count { it.archivedAt == null }}", "全部 ${projects.size}"),
                        selectedTabIndex = if (showArchived) 1 else 0,
                        onTabSelected = { showArchived = it == 1 },
                    )
                }
            }
        }
        if (onOpenIndependentWorkspace != null) item(key = "workspace-home") {
            WorkspaceHomePanel(onOpenFiles = onOpenIndependentWorkspace)
        }
        when (projectsLoad) {
            ProjectsLoadState.Loading -> item(key = "projects-loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfiniteProgressIndicator(size = 22.dp)
                    Text(stringResource(R.string.projects_loading), modifier = Modifier.padding(start = 10.dp))
                }
            }
            ProjectsLoadState.Failed -> item(key = "projects-failed") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.projects_load_failed), color = MiuixTheme.colorScheme.error)
                        TextButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { projectsRefreshKey++ },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            is ProjectsLoadState.Loaded -> {
                if (projects.any { showArchived || it.archivedAt == null }) item(key = "project-list-heading") {
                    Text("项目列表", style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                projects.filter { showArchived || it.archivedAt == null }.forEach { project ->
                    item(key = project.id) { ProjectRow(context, project, scope, onError = { error = it }, onDetails = { details = project },
                        onFiles = onOpenProjectFiles?.let { open -> { open(project) } }) }
                }
                if (shouldShowProjectsEmptyState(projectsLoad, showArchived)) {
                    item(key = "projects-empty") {
                        Text(stringResource(R.string.projects_empty), modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
        if (!showCreate) error?.let { failure ->
            item(key = "projects-operation-error") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(failure, color = MiuixTheme.colorScheme.error)
                        NexaCompactAction(stringResource(R.string.action_close), onClick = { error = null })
                    }
                }
            }
        }
    }

    if (showCreate) {
        WindowDialog(show = true, title = stringResource(R.string.projects_new), summary = stringResource(R.string.projects_new_summary), onDismissRequest = { if (!busy) showCreate = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it.take(120) }, label = stringResource(R.string.projects_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                ArrowPreference(
                    title = selectedRoot?.let { stringResource(R.string.projects_root_selected, it.lastPathSegment ?: it.toString()) } ?: stringResource(R.string.projects_choose_root),
                    onClick = { rootLauncher.launch(null) },
                )
                error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                MiuixDialogActions(
                    confirmText = if (busy) stringResource(R.string.projects_saving) else stringResource(R.string.projects_create),
                    confirmEnabled = !busy && name.isNotBlank() && selectedRoot != null,
                    onCancel = { if (!busy) showCreate = false },
                    onConfirm = {
                        val root = selectedRoot ?: return@MiuixDialogActions
                        busy = true
                        scope.launch {
                            runCatching {
                                ProjectRepository.create(context, ProjectDraft(name.trim(), "app", root.toString(), root.lastPathSegment ?: root.toString(), "SAF"))
                            }.onSuccess { showCreate = false }.onFailure { error = when ((it as? ProjectException)?.code) { "ROOT_ALREADY_REGISTERED" -> context.getString(R.string.projects_root_exists); else -> context.getString(R.string.projects_save_failed) } }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    details?.let { project ->
        ProjectDetailsDialog(
            context = context,
            project = project,
            scope = scope,
            onDismiss = { details = null },
            onOpenCopy = { path ->
                if (onOpenWorkspaceCopy != null) {
                    details = null
                    onOpenWorkspaceCopy(project, path)
                } else openCopy = path
            },
            onOpenSubject = onOpenSubject,
            onOpenTask = onOpenTask,
            onOpenMemory = onOpenMemory,
        )
    }
}

@Composable
private fun ProjectRow(context: Context, project: ProjectEntity, scope: kotlinx.coroutines.CoroutineScope, onError: (String) -> Unit, onDetails: () -> Unit, onFiles: (() -> Unit)? = null) {
    var confirmDelete by remember(project.id) { mutableStateOf(false) }
    var showActions by remember(project.id) { mutableStateOf(false) }
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        BasicComponent(title = project.name, summary = project.rootDisplayName)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexaCompactAction(stringResource(R.string.projects_details), onClick = onDetails, modifier = Modifier.weight(1f))
            if (onFiles != null) NexaCompactAction("文件", onClick = onFiles, modifier = Modifier.weight(1f), icon = Icons.Rounded.FolderOpen)
            NexaCompactAction("更多", onClick = { showActions = true }, modifier = Modifier.weight(1f))
        }
    }
    if (showActions) {
        WindowDialog(show = true, title = project.name, onDismissRequest = { showActions = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ArrowPreference(
                    title = if (project.archivedAt == null) stringResource(R.string.projects_archive) else stringResource(R.string.projects_restore),
                    onClick = {
                        showActions = false
                        scope.launch { runCatching { ProjectRepository.setArchived(context, project.id, project.archivedAt == null) }.onFailure { onError(context.getString(R.string.projects_save_failed)) } }
                    },
                )
                ArrowPreference(title = stringResource(R.string.projects_delete), onClick = { showActions = false; confirmDelete = true })
                NexaCompactAction(stringResource(R.string.action_close), onClick = { showActions = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (confirmDelete) {
        WindowDialog(show = true, title = stringResource(R.string.projects_delete), summary = stringResource(R.string.projects_delete_summary), onDismissRequest = { confirmDelete = false }) {
            MiuixDialogActions(confirmText = stringResource(R.string.projects_delete), destructive = true, onCancel = { confirmDelete = false }, onConfirm = {
                scope.launch { runCatching { ProjectRepository.delete(context, project.id) }.onFailure { onError(context.getString(R.string.projects_save_failed)) }; confirmDelete = false }
            })
        }
    }
}

@Composable
private fun ProjectDetailsDialog(
    context: Context,
    project: ProjectEntity,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onOpenCopy: (String) -> Unit,
    onOpenSubject: (ProjectSubjectOption) -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenMemory: (String) -> Unit,
) {
    var options by remember(project.id) { mutableStateOf<List<ProjectSubjectOption>>(emptyList()) }
    var selected by remember(project.id) { mutableStateOf<ProjectSubjectOption?>(null) }
    var showSelector by remember(project.id) { mutableStateOf(false) }
    var confirmMove by remember(project.id) { mutableStateOf(false) }
    var saving by remember(project.id) { mutableStateOf(false) }
    var bindings by remember(project.id) { mutableStateOf<List<io.github.mangi.eta.fork.projects.ProjectSubjectBinding>>(emptyList()) }
    var artifacts by remember(project.id) { mutableStateOf(emptyList<io.github.zhh_lgtm.nexus.application.api.ArtifactRecord>()) }
    var tasks by remember(project.id) { mutableStateOf<List<io.github.mangi.eta.fork.tasks.AgentTaskDefinitionEntity>?>(null) }
    var detailsRefreshKey by remember(project.id) { mutableIntStateOf(0) }
    var detailsLoadState by remember(project.id) { mutableStateOf(ProjectDetailsDataLoadState.Loading) }
    var showFiles by remember(project.id) { mutableStateOf(false) }
    var showCopies by remember(project.id) { mutableStateOf(false) }
    var localError by remember(project.id) { mutableStateOf<String?>(null) }
    var bindingSaved by remember(project.id) { mutableStateOf(false) }
    LaunchedEffect(project.id, detailsRefreshKey) {
        detailsLoadState = ProjectDetailsDataLoadState.Loading
        try {
            val loadedBindings = ProjectRepository.bindingsForProject(context, project.id)
            val loadedOptions = ProjectRepository.subjectOptions(context)
            val loadedArtifacts = withContext(Dispatchers.IO) {
                io.github.mangi.eta.data.repository.NexusArtifactLedger(context).forProject(project.id)
            }
            val loadedTasks = try {
                withContext(Dispatchers.IO) {
                    io.github.mangi.eta.fork.tasks.AgentTaskDatabase.get(context).agentTaskDao().definitionsNow()
                        .filter { runCatching { io.github.mangi.eta.fork.tasks.AutomationSpec.parse(it.policyJson).projectId == project.id }.getOrDefault(false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            bindings = loadedBindings
            options = loadedOptions
            artifacts = loadedArtifacts
            tasks = loadedTasks
            detailsLoadState = ProjectDetailsDataLoadState.Loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            detailsLoadState = ProjectDetailsDataLoadState.Failed
        }
    }
    if (showCopies) {
        ProjectWorkingCopiesDialog(context, project, onOpenCopy, onDismiss = { showCopies = false })
        return
    }
    if (showFiles) {
        io.github.mangi.eta.ui.components.NexusArtifactBrowser(projectId = project.id, title = project.name, onDismiss = { showFiles = false })
        return
    }
    val optionBySubject = remember(options) { options.associateBy { it.type to it.id } }
    val sortedBindings = remember(bindings, optionBySubject) {
        bindings.sortedByDescending { optionBySubject[it.subjectType to it.subjectId]?.updatedAt ?: 0L }
    }
    WindowDialog(show = !showSelector && !confirmMove, title = project.name, summary = project.rootDisplayName, onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "overview") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EtaIconTile(Icons.Rounded.FolderOpen, tone = EtaIconTone.SYSTEM)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.projects_bind_summary), style = MiuixTheme.textStyles.body2)
                        Text(
                            text = if (hasProjectDetailsSnapshot(detailsLoadState)) {
                                "${bindings.size} 个对话或会议室 · ${artifacts.size} 个文件"
                            } else {
                                stringResource(
                                    if (detailsLoadState == ProjectDetailsDataLoadState.Loading) R.string.projects_details_loading
                                    else R.string.projects_details_load_failed,
                                )
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    EtaStatusPill(if (project.archivedAt == null) "使用中" else "已归档")
                }
                }
                item(key = "select_subject") {
                ArrowPreference(
                    title = stringResource(R.string.project_select_subject),
                    summary = selected?.let {
                        val title = projectSubjectTitle(it)
                        if (it.projectName != null) stringResource(R.string.project_move_selected, title, it.projectName) else title
                    } ?: stringResource(R.string.project_select_subject_summary),
                    onClick = { if (!saving && detailsLoadState == ProjectDetailsDataLoadState.Loaded) showSelector = true },
                )
                }
                item(key = "working_copies") {
                io.github.mangi.eta.ui.components.DetailListRow(title = "工作副本", icon = Icons.Rounded.FolderOpen, tone = EtaIconTone.DATA,
                    onClick = { showCopies = true })
                }
                localError?.let { message ->
                    item(key = "error") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = MiuixTheme.colorScheme.error, modifier = Modifier.padding(2.dp))
                        Text(message, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                    }
                    TextButton(text = stringResource(R.string.action_retry), onClick = {
                        localError = null
                        selected = null
                        detailsRefreshKey++
                    })
                    }
                }
                if (bindingSaved) item(key = "binding_saved") { Text(stringResource(R.string.project_binding_saved),
                    color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1) }
                if (hasProjectDetailsSnapshot(detailsLoadState) && bindings.isNotEmpty()) {
                    item(key = "subjects_heading") { ProjectDialogHeading(stringResource(R.string.projects_bound_subjects), Icons.Rounded.Link) }
                    items(sortedBindings, key = { "subject:${it.subjectType}:${it.subjectId}" }) { binding ->
                        Card(Modifier.fillMaxWidth()) {
                            val option = optionBySubject[binding.subjectType to binding.subjectId]
                            if (option != null) {
                                ArrowPreference(title = projectSubjectTitle(option),
                                    summary = stringResource(if (option.type == ProjectSubjectType.MEETING)
                                        R.string.project_subject_meeting else R.string.project_subject_conversation),
                                    onClick = { onOpenSubject(option) })
                            } else Text(stringResource(R.string.project_subject_unavailable),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
                if (hasProjectDetailsSnapshot(detailsLoadState)) {
                    val projectTasks = tasks
                    item(key = "tasks_heading") {
                        ProjectDialogHeading(if (projectTasks == null) "自动任务" else "自动任务 · ${projectTasks.size}", Icons.Rounded.AccountTree)
                    }
                    if (projectTasks.isNullOrEmpty()) {
                        item(key = "tasks_empty") { Card(Modifier.fillMaxWidth()) {
                            Text(if (projectTasks == null) "无法读取关联任务" else "暂无关联任务", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                        } }
                    } else items(projectTasks, key = { "task:${it.id}" }) { task ->
                        Card(Modifier.fillMaxWidth()) {
                            ArrowPreference(title = task.title, summary = if (task.enabled) "已启用" else "已暂停",
                                onClick = { onOpenTask(task.id) })
                        }
                    }
                }
                item(key = "project_memory") {
                ArrowPreference(title = "项目记忆", summary = "查看和管理本项目的记忆", onClick = { onOpenMemory(project.id) })
                }
                item(key = "artifacts") {
                ProjectDialogSection(
                    title = if (hasProjectDetailsSnapshot(detailsLoadState)) {
                        stringResource(R.string.projects_artifacts, artifacts.size)
                    } else {
                        stringResource(R.string.projects_artifacts_title)
                    },
                    icon = Icons.Rounded.AccountTree,
                ) {
                    if (detailsLoadState == ProjectDetailsDataLoadState.Loading) {
                        Text(stringResource(R.string.projects_details_loading), modifier = Modifier.padding(12.dp))
                    } else if (detailsLoadState == ProjectDetailsDataLoadState.Failed) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(stringResource(R.string.projects_details_load_failed), color = MiuixTheme.colorScheme.error)
                            TextButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { detailsRefreshKey++ },
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    } else if (artifacts.isEmpty()) {
                        Text(stringResource(R.string.projects_artifacts_empty), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
                    } else artifacts.take(5).forEach { artifact ->
                        Text(artifact.label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1,
                            modifier = Modifier.clickable { showFiles = true }.padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                    top.yukonga.miuix.kmp.basic.TextButton(text = stringResource(R.string.projects_artifacts_open), onClick = { showFiles = true })
                }
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 14.dp),
                confirmText = if (saving) stringResource(R.string.projects_saving) else stringResource(R.string.projects_bind),
                confirmEnabled = !saving && selected != null,
                onCancel = { if (!saving) onDismiss() },
                onConfirm = { if (selected != null) confirmMove = true },
            )
        }
    }
    if (showSelector) {
        ProjectSubjectPicker(
            options = options.filter { it.projectId != project.id },
            onSelect = { selected = it; localError = null; bindingSaved = false; showSelector = false },
            onDismiss = { showSelector = false },
        )
    }
    if (confirmMove) {
        val option = selected
        WindowDialog(show = true, title = stringResource(R.string.projects_bind), onDismissRequest = { if (!saving) confirmMove = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (option != null) {
                    val origin = option.projectName ?: stringResource(R.string.project_subject_no_project)
                    Text(stringResource(R.string.project_confirm_assignment, projectSubjectTitle(option), origin, project.name))
                }
                MiuixDialogActions(confirmText = if (saving) stringResource(R.string.projects_saving) else stringResource(R.string.projects_bind),
                    confirmEnabled = !saving && option != null, onCancel = { if (!saving) confirmMove = false }, onConfirm = {
                        val choice = option ?: return@MiuixDialogActions
                        localError = null
                        bindingSaved = false
                        saving = true
                        scope.launch {
                            try {
                                ProjectRepository.bindSubjectIfUnchanged(context, project.id, choice)
                                selected = null
                                bindingSaved = true
                                detailsRefreshKey++
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                localError = if ((failure as? ProjectException)?.code in setOf("BINDING_CHANGED", "SUBJECT_NOT_FOUND")) {
                                    context.getString(R.string.project_subject_changed)
                                } else context.getString(R.string.projects_save_failed)
                            } finally { saving = false; confirmMove = false }
                        }
                    })
            }
        }
    }
}

@Composable
private fun projectSubjectTitle(option: ProjectSubjectOption): String =
    option.title.ifBlank { stringResource(if (option.type == ProjectSubjectType.MEETING) R.string.project_untitled_meeting else R.string.project_untitled_conversation) }

@Composable
private fun ProjectSubjectPicker(options: List<ProjectSubjectOption>, onSelect: (ProjectSubjectOption) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = options.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || it.projectName?.contains(query.trim(), ignoreCase = true) == true }
    WindowDialog(show = true, title = stringResource(R.string.project_select_subject), onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.project_subject_search), singleLine = true, modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                if (filtered.isEmpty()) item { Text(stringResource(R.string.project_subject_empty), modifier = Modifier.padding(16.dp)) }
                items(filtered.size, key = { index -> "${filtered[index].type}:${filtered[index].id}" }) { index ->
                    val option = filtered[index]
                    val kind = stringResource(if (option.type == ProjectSubjectType.MEETING) R.string.project_subject_meeting else R.string.project_subject_conversation)
                    val updated = Instant.ofEpochMilli(option.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    val projectLabel = option.projectName ?: stringResource(R.string.project_subject_no_project)
                    ArrowPreference(title = projectSubjectTitle(option), summary = "$kind · $updated · $projectLabel", onClick = { onSelect(option) })
                }
            }
            TextButton(text = stringResource(R.string.action_cancel), onClick = onDismiss)
        }
    }
}

@Composable
private fun ProjectDialogSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        ProjectDialogHeading(title, icon)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 3.dp)) { content() } }
    }
}

@Composable
private fun ProjectDialogHeading(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(2.dp))
        Text(title, style = MiuixTheme.textStyles.body2)
    }
}
