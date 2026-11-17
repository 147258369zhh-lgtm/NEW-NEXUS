package io.github.mangi.eta.ui.screens.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.projects.ProjectEntity
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectOption
import io.github.mangi.eta.fork.projects.ProjectWorkspaceRepository
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexusArtifactBrowser
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** One page shell at a time; a selected project changes file scope, never file ownership. */
@Composable
internal fun ProjectsWorkspaceScreen(
    onBack: () -> Unit,
    onOpenSubject: (ProjectSubjectOption) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenMemory: (String) -> Unit = {},
    initialFiles: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    var filesSelected by rememberSaveable { mutableStateOf(initialFiles) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var copyPath by rememberSaveable { mutableStateOf<String?>(null) }
    var showScope by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    val projectsLoad by produceState<ProjectsLoadState>(ProjectsLoadState.Loading, context, refresh) {
        value = ProjectsLoadState.Loading
        try {
            var receivedSnapshot = false
            ProjectRepository.projects(context).collect {
                receivedSnapshot = true
                value = ProjectsLoadState.Loaded(it)
            }
            if (!receivedSnapshot) value = ProjectsLoadState.Failed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = ProjectsLoadState.Failed }
    }
    val projects = (projectsLoad as? ProjectsLoadState.Loaded)?.projects.orEmpty()
    val project = projects.find { it.id == projectId }
    val returnToProjects: () -> Unit = { copyPath = null; filesSelected = false }
    val filesBack: () -> Unit = {
        if (copyPath != null) copyPath = null else returnToProjects()
    }
    val navigation: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NexaCompactAction("项目与文件", onClick = returnToProjects)
            NexaCompactAction(
                text = project?.name ?: if (projectId == null) "文件工作区" else "选择项目",
                icon = Icons.Rounded.FolderOpen,
                onClick = { showScope = true },
                modifier = Modifier.weight(1f),
            )
        }
    }
    val openCopy: (ProjectEntity, String) -> Unit = { owner, path ->
        projectId = owner.id
        copyPath = path
        filesSelected = true
    }
    BackHandler(enabled = filesSelected) { filesBack() }
    when {
        !filesSelected -> stateHolder.SaveableStateProvider("projects") {
            ProjectsScreen(onBack = onBack, onOpenSubject = onOpenSubject,
                onOpenTask = onOpenTask, onOpenMemory = onOpenMemory,
                pageTitle = "项目与文件",
                onOpenIndependentWorkspace = { projectId = null; copyPath = null; filesSelected = true },
                onOpenProjectFiles = { owner -> projectId = owner.id; copyPath = null; filesSelected = true },
                onOpenWorkspaceCopy = openCopy)
        }
        projectId == null -> stateHolder.SaveableStateProvider("independent-workspace") {
            WorkspaceScreen(onBack = filesBack, pageTitle = "文件工作区", aboveContent = navigation)
        }
        projectsLoad !is ProjectsLoadState.Loaded || project == null -> MiuixScaffoldPage(
            title = "项目文件", onBack = filesBack, aboveContent = navigation,
        ) {
            item {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when (projectsLoad) {
                            ProjectsLoadState.Loading -> stringResource(R.string.projects_loading)
                            ProjectsLoadState.Failed -> stringResource(R.string.projects_load_failed)
                            is ProjectsLoadState.Loaded -> "项目已不存在，请选择其他文件范围"
                        })
                        if (projectsLoad == ProjectsLoadState.Failed) NexaCompactAction(
                            stringResource(R.string.action_retry), onClick = { refresh++ })
                        NexaCompactAction("文件工作区", onClick = { projectId = null; copyPath = null })
                    }
                }
            }
        }
        copyPath != null -> stateHolder.SaveableStateProvider("copy:${project.id}:${copyPath}") {
            WorkspaceScreen(onBack = { copyPath = null }, initialPath = copyPath!!,
                projectId = project.id, pageTitle = "工作副本", aboveContent = navigation)
        }
        else -> stateHolder.SaveableStateProvider("project-files:${project.id}") {
            ProjectFilesPage(project, filesBack, navigation, onOpenCopy = { openCopy(project, it) })
        }
    }
    if (showScope) WindowDialog(show = true, title = "文件范围", onDismissRequest = { showScope = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                item(key = "independent") {
                    ArrowPreference(title = "文件工作区", summary = "通用文件管理与生成文件归档设置",
                        onClick = { projectId = null; copyPath = null; showScope = false })
                }
                when (projectsLoad) {
                    ProjectsLoadState.Loading -> item { Text(stringResource(R.string.projects_loading), Modifier.padding(12.dp)) }
                    ProjectsLoadState.Failed -> item {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.projects_load_failed), color = MiuixTheme.colorScheme.error)
                            NexaCompactAction(stringResource(R.string.action_retry), onClick = { refresh++ })
                        }
                    }
                    is ProjectsLoadState.Loaded -> {
                        if (projects.isEmpty()) item { Text(stringResource(R.string.projects_empty), Modifier.padding(12.dp)) }
                        items(projects.sortedWith(compareBy<ProjectEntity> { it.archivedAt != null }.thenBy { it.name }), key = { it.id }) { owner ->
                            ArrowPreference(title = owner.name,
                                summary = owner.rootDisplayName + if (owner.archivedAt != null) " · 已归档" else "",
                                onClick = { projectId = owner.id; copyPath = null; showScope = false })
                        }
                    }
                }
            }
            NexaCompactAction(stringResource(R.string.action_close), onClick = { showScope = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProjectFilesPage(
    project: ProjectEntity,
    onBack: () -> Unit,
    aboveContent: @Composable () -> Unit,
    onOpenCopy: (String) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    var showArtifacts by remember(project.id) { mutableStateOf(false) }
    var copies by remember(project.id) { mutableStateOf<List<ProjectWorkspaceRepository.Copy>>(emptyList()) }
    var loadState by remember(project.id) { mutableStateOf(ProjectDetailsDataLoadState.Loading) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(project.id, refresh) {
        loadState = ProjectDetailsDataLoadState.Loading
        try {
            copies = withContext(Dispatchers.IO) { ProjectWorkspaceRepository.copies(context, project.id) }
            loadState = ProjectDetailsDataLoadState.Loaded
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadState = ProjectDetailsDataLoadState.Failed }
    }
    MiuixScaffoldPage(title = "项目文件", onBack = onBack, aboveContent = aboveContent,
        actions = { NexaCompactAction("刷新", onClick = { refresh++ }, modifier = Modifier.padding(end = 12.dp)) }) {
        item(key = "project-root") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicComponent(title = project.name, summary = project.rootDisplayName)
                ArrowPreference(title = "关联文件", summary = "查看、导入和管理归属于本项目的文件",
                    onClick = { showArtifacts = true })
            }
        }
        item(key = "working-copies-heading") {
            Text("工作副本", style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        when (loadState) {
            ProjectDetailsDataLoadState.Loading -> item { Text(stringResource(R.string.project_copies_loading), Modifier.padding(20.dp)) }
            ProjectDetailsDataLoadState.Failed -> item {
                Text(stringResource(R.string.project_copies_load_failed), Modifier.padding(20.dp), color = MiuixTheme.colorScheme.error)
            }
            ProjectDetailsDataLoadState.Loaded -> {
                if (copies.isEmpty()) item {
                    Text(stringResource(R.string.project_copies_empty), Modifier.padding(20.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                items(copies, key = { it.relativePath }) { copy ->
                    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                        DetailListRow(title = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(copy.createdAt)),
                            icon = Icons.Rounded.FolderOpen, tone = EtaIconTone.DATA, onClick = { onOpenCopy(copy.relativePath) })
                    }
                }
            }
        }
    }
    if (showArtifacts) NexusArtifactBrowser(projectId = project.id, title = project.name, onDismiss = { showArtifacts = false })
}
