package io.github.mangi.eta.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.NexusArtifactLedger
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun NexusArtifactBrowser(subjectType: String? = null, subjectId: String? = null, projectId: String? = null,
    title: String? = null, records: List<ArtifactRecord>? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ledger = remember(context) { NexusArtifactLedger(context) }
    val access = remember(context) { NexusArtifactAccess(context) }
    val scope = rememberCoroutineScope()
    var files by remember(subjectType, subjectId, projectId) { mutableStateOf<List<ArtifactRecord>>(emptyList()) }
    var busy by remember(subjectType, subjectId, projectId) { mutableStateOf(true) }
    var loaded by remember(subjectType, subjectId, projectId) { mutableStateOf(false) }
    var error by remember(subjectType, subjectId, projectId) { mutableStateOf<String?>(null) }
    var boundProject by remember(subjectType, subjectId, projectId) { mutableStateOf(projectId) }
    var sourceNames by remember(subjectType, subjectId, projectId, records) { mutableStateOf<Map<Pair<String, String>, String>>(emptyMap()) }
    suspend fun refresh() {
        loaded = false
        try {
            val snapshot = withContext(Dispatchers.IO) {
                val binding = projectId ?: if (subjectType in setOf("conversation", "meeting") && subjectId != null) {
                    ProjectRepository.bindingForSubject(context,
                        if (subjectType == "meeting") ProjectSubjectType.MEETING else ProjectSubjectType.CONVERSATION, subjectId)?.projectId
                } else null
                val loadedRecords = when {
                    records != null -> records
                    subjectType != null && subjectId != null -> ledger.forSubject(subjectType, subjectId)
                    projectId != null -> ledger.forProject(projectId)
                    else -> ledger.list(200)
                }
                val names = mutableMapOf<Pair<String, String>, String>()
                if (loadedRecords.any { it.subjectType in setOf("conversation", "meeting") && it.subjectId != null }) {
                    val options = try { ProjectRepository.subjectOptions(context) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    options.forEach { option ->
                        val type = if (option.type == ProjectSubjectType.MEETING) "meeting" else "conversation"
                        names[type to option.id] = option.title
                    }
                }
                if (loadedRecords.any { it.subjectType == "task" }) {
                    val definitions = try { AgentTaskDatabase.get(context).agentTaskDao().definitionsNow() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { emptyList() }
                    definitions.forEach { task ->
                        names["task" to task.id] = task.title
                    }
                }
                Triple(binding, loadedRecords, names)
            }
            boundProject = snapshot.first
            files = snapshot.second
            sourceNames = snapshot.third
            loaded = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = context.getString(R.string.artifact_browser_load_failed)
        }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = context.getString(R.string.artifact_browser_action_failed) }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) action {
            try {
                uris.forEach { uri -> check(WorkspaceFileStore(context).importFile(uri, subjectType, subjectId, boundProject)) }
            } finally {
                // A partially imported selection must still appear if a later file fails.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                refresh()
            }
        }
    }
    LaunchedEffect(subjectType, subjectId, projectId, records) {
        busy = true
        error = null
        try { refresh() } finally { busy = false }
    }
    val pendingApproval by io.github.mangi.eta.agent.control.EtaAppControlConfirmation.pending.collectAsState()
    val pendingInputs by io.github.mangi.eta.agent.runtime.RunUserInput.pending.collectAsState()
    WindowDialog(show = pendingApproval == null && pendingInputs.isEmpty(), title = title ?: stringResource(R.string.projects_artifacts_title), onDismissRequest = onDismiss) {
        val scheme = MiuixTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (records == null) {
                    IconButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !busy && loaded) {
                        Icon(Icons.Rounded.UploadFile, stringResource(R.string.artifact_browser_import), Modifier.size(20.dp), tint = scheme.primary)
                    }
                }
                IconButton(onClick = { action { refresh() } }, enabled = !busy) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.artifact_browser_refresh), Modifier.size(20.dp), tint = scheme.primary)
                }
            }
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = scheme.error) }
            if (!loaded && !busy) TextButton(text = stringResource(R.string.action_retry), onClick = { action { refresh() } })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy && !loaded) item { Text(stringResource(R.string.artifact_browser_loading),
                    Modifier.padding(vertical = 20.dp), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary) }
                if (loaded && files.isEmpty()) item { Text(stringResource(R.string.artifact_browser_empty),
                    Modifier.padding(vertical = 20.dp), style = MiuixTheme.textStyles.body2, color = scheme.onSurfaceVariantSummary) }
                items(if (loaded) files else emptyList(), key = { it.id }) { file ->
                    Row(Modifier.fillMaxWidth().background(scheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy && file.status == ArtifactStatus.VERIFIED) { action { access.launch(file.id) } }
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, Modifier.size(21.dp), tint = scheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(file.label, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val details = if (file.status == ArtifactStatus.VERIFIED) file.sizeBytes?.let {
                                android.text.format.Formatter.formatShortFileSize(context, it)
                            } ?: file.path else stringResource(if (file.status == ArtifactStatus.PENDING) R.string.artifact_browser_pending else R.string.artifact_browser_unavailable)
                            Text(details, style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val source = when (file.subjectType) {
                                "conversation" -> "会话"
                                "meeting" -> "会议室"
                                "task" -> "自动任务"
                                else -> if (file.runId == "manual-import") "手动导入" else "运行成果"
                            }
                            val sourceName = file.subjectType?.let { type ->
                                file.subjectId?.let { id -> sourceNames[type to id] }
                            }?.takeIf(String::isNotBlank)
                            val created = java.time.Instant.ofEpochMilli(file.createdAt).atZone(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                            Text(listOfNotNull(source, sourceName, created).joinToString(" · "),
                                style = MiuixTheme.textStyles.footnote2, color = scheme.onSurfaceVariantSummary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { action { access.launch(file.id, share = true) } }, enabled = !busy && file.status == ArtifactStatus.VERIFIED) {
                            Icon(Icons.Rounded.Share, stringResource(R.string.artifact_browser_share), Modifier.size(18.dp), tint = scheme.primary)
                        }
                    }
                }
            }
        }
    }
}
