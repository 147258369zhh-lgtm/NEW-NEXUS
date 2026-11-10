package io.github.mangi.eta.ui.screens.conversations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.ConversationBranchTree
import io.github.mangi.eta.ui.screens.metrics.metricStatusLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.mangi.eta.data.repository.ConversationBranchExport
import io.github.mangi.eta.data.repository.ConversationBranchSetupCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ConversationBranchesScreen(
    conversationId: String?, onBack: () -> Unit, onOpenConversation: (String) -> Unit,
    onDelete: (String) -> Unit, isBusy: (String) -> Boolean, onOpenRun: (String) -> Unit,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val data by remember(context, reload) {
        val dao = EtaDatabase.get(context).conversationDao()
        combine(dao.branchesFlow(), dao.conversationIdentitiesFlow(), dao.branchSetupsFlow()) { nodes, identities, setups -> Triple(nodes, identities, setups) }
            .onEach { loading = false; failed = false }.catch { loading = false; failed = true }
    }.collectAsState(initial = Triple(emptyList<ConversationBranchEntity>(), emptyList(), emptyList<ConversationBranchSetupEntity>()))
    var runsFailed by remember { mutableStateOf(false) }
    val runs by remember(context, reload) {
        AgentRunMetricsStore.records(context).onEach { runsFailed = false }.catch { runsFailed = true }
    }.collectAsState(initial = emptyList())
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var search by remember { mutableStateOf("") }
    var setupBusy by remember { mutableStateOf(emptySet<String>()) }
    var setupFailed by remember { mutableStateOf(emptySet<String>()) }
    var useCurrentId by remember { mutableStateOf<String?>(null) }
    var allFamilies by remember { mutableStateOf(conversationId == null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var sourceNode by remember { mutableStateOf<ConversationBranchEntity?>(null) }
    var sourceRows by remember { mutableStateOf<List<ConversationMessageEntity>>(emptyList()) }
    var sourceLoading by remember { mutableStateOf(false) }
    var sourceFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var exportJob by remember { mutableStateOf<Job?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf(false) }
    var exportCancelled by remember { mutableStateOf(false) }
    var exportedCount by remember { mutableStateOf<Int?>(null) }
    var choosingDestination by remember { mutableStateOf(false) }
    var exportAll by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        choosingDestination = false
        if (uri != null) {
            exporting = true; exportError = false; exportCancelled = false; exportedCount = null
            exportJob = scope.launch {
                try {
                    exportedCount = ConversationBranchExport.export(context, uri, conversationId, exportAll)
                } catch (_: TimeoutCancellationException) { exportError = true }
                catch (cancelled: CancellationException) { exportCancelled = true; throw cancelled }
                catch (_: Exception) { exportError = true }
                finally { exporting = false; exportJob = null }
            }
        }
    }
    val identities = data.second.associateBy { it.id }
    val family = data.first.firstOrNull { it.conversationId == conversationId }?.familyId ?: conversationId
    val nodes = if (allFamilies) data.first else data.first.filter { it.familyId == family }.ifEmpty {
        identities[conversationId]?.let { listOf(ConversationBranchEntity(it.id, it.id, createdAt = it.createdAt)) }.orEmpty()
    }
    val tree = remember(nodes, identities, collapsed, search) {
        ConversationBranchTree.search(nodes, search, collapsed) { identities[it]?.title.orEmpty() }
    }
    val rows = tree.rows
    val runsByConversation = remember(runs) { runs.groupBy { it.conversationId }.mapValues { (_, values) -> values.sortedByDescending { it.startedAt } } }
    val setups = data.third.associateBy { it.conversationId }
    fun initialize(id: String, current: Boolean) {
        setupBusy = setupBusy + id; setupFailed = setupFailed - id
        scope.launch {
            try { if (!ConversationBranchSetupCoordinator.ensure(context, id, current)) setupFailed = setupFailed + id }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { setupFailed = setupFailed + id }
            finally { setupBusy = setupBusy - id }
        }
    }
    LaunchedEffect(sourceNode, reload) {
        val node = sourceNode ?: return@LaunchedEffect
        sourceLoading = true; sourceFailed = false; sourceRows = emptyList()
        try {
            sourceRows = withContext(Dispatchers.IO) {
                val db = EtaDatabase.get(context)
                db.withTransaction {
                    val dao = db.conversationDao()
                    val row = dao.messageRow(node.sourceConversationId.orEmpty(), node.sourceMessageId.orEmpty())
                        ?: return@withTransaction emptyList()
                    dao.messageRowsRange(row.conversationId, maxOf(0, row.sortIndex - 2), row.sortIndex + 2)
                        .map { dao.restoreMessage(it).let { restored -> restored.copy(content = restored.content.take(640), imagesJson = "[]") } }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { sourceFailed = true }
        finally { sourceLoading = false }
    }
    MiuixScaffoldPage(title = stringResource(R.string.branch_graph_title), onBack = onBack) {
        item(key = "intro") {
            Card(Modifier.padding(12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.branch_graph_note), style = MiuixTheme.textStyles.body2)
                    TextField(value = search, onValueChange = { search = it.take(128) }, singleLine = true,
                        label = stringResource(R.string.branch_search), modifier = Modifier.fillMaxWidth())
                    if (loading) Text(stringResource(R.string.metrics_loading))
                    if (failed || runsFailed) {
                        Text(stringResource(R.string.branch_load_failed), color = MiuixTheme.colorScheme.error)
                        TextButton(stringResource(R.string.metrics_refresh), onClick = { loading = true; reload++ })
                    }
                    TextButton(stringResource(if (allFamilies) R.string.branch_show_current else R.string.branch_show_all),
                        onClick = { allFamilies = !allFamilies }, enabled = conversationId != null)
                    if (tree.invalid) Text(stringResource(R.string.branch_invalid_graph), color = MiuixTheme.colorScheme.error)
                    if (exporting) {
                        Text(stringResource(R.string.branch_exporting), style = MiuixTheme.textStyles.footnote1)
                        TextButton(stringResource(R.string.action_cancel), onClick = { exportJob?.cancel() })
                    } else TextButton(stringResource(R.string.branch_export), enabled = !loading && !failed && !choosingDestination,
                        onClick = { choosingDestination = true; exportAll = allFamilies; exportLauncher.launch("Nexus-conversation-branches.json") })
                    if (exportError) Text(stringResource(R.string.branch_export_failed), color = MiuixTheme.colorScheme.error)
                    if (exportCancelled) Text(stringResource(R.string.branch_export_cancelled), style = MiuixTheme.textStyles.footnote1)
                    exportedCount?.let { count -> Text(stringResource(R.string.branch_export_done, count), style = MiuixTheme.textStyles.footnote1) }
                }
            }
        }
        if (!loading && rows.isEmpty()) item(key = "empty") {
            Text(stringResource(if (search.isBlank()) R.string.branch_empty else R.string.branch_search_empty), Modifier.padding(24.dp), style = MiuixTheme.textStyles.body2)
        }
        items(rows, key = { it.node.conversationId }) { row ->
            val node = row.node
            val identity = identities[node.conversationId]
            val related = runsByConversation[node.conversationId].orEmpty()
            val busy = isBusy(node.conversationId) || node.conversationId in setupBusy
            val setup = setups[node.conversationId]
            val lineColor = MiuixTheme.colorScheme.primary.copy(alpha = .45f)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp).height(IntrinsicSize.Min)) {
                if (row.depth > 0) Canvas(Modifier.width((minOf(row.depth, 4) * 12 + 8).dp).fillMaxHeight()) {
                    val x = size.width - 8.dp.toPx()
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height / 2), strokeWidth = 1.dp.toPx())
                    drawLine(lineColor, Offset(x, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 1.dp.toPx())
                }
                Card(Modifier.weight(1f)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(identity?.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.conversation_unnamed),
                            style = MiuixTheme.textStyles.body1)
                        Text(stringResource(R.string.branch_depth, row.depth + 1), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (node.sourceConversationId != null) {
                            val sourceTitle = identities[node.sourceConversationId]?.title?.takeIf(String::isNotBlank)
                                ?: stringResource(if (node.sourceConversationId in identities) R.string.conversation_unnamed else R.string.branch_source_missing)
                            Text(stringResource(R.string.branch_source_line, sourceTitle, (node.sourceSortIndex ?: 0) + 1),
                                style = MiuixTheme.textStyles.footnote1)
                        }
                        if (node.historyMode == "visible_reconstruction") Text(stringResource(R.string.branch_reconstructed_note),
                            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (setup != null && setup.completedAt == null) {
                            Text(stringResource(when {
                                node.conversationId in setupBusy -> R.string.branch_stage_inherit_settings
                                setup.errorCode == "FOLDER_UNAVAILABLE" -> R.string.branch_setup_folder_failed
                                setup.errorCode == "PROJECT_UNAVAILABLE" -> R.string.branch_setup_project_failed
                                setup.errorCode != null || node.conversationId in setupFailed -> R.string.branch_setup_failed
                                else -> R.string.branch_setup_pending
                            }), style = MiuixTheme.textStyles.footnote1, color = if (node.conversationId !in setupBusy &&
                                (setup.errorCode != null || node.conversationId in setupFailed)) MiuixTheme.colorScheme.error
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(stringResource(R.string.branch_setup_retry), modifier = Modifier.weight(1f), enabled = !busy,
                                    onClick = { initialize(node.conversationId, false) })
                                TextButton(stringResource(R.string.branch_setup_use_current), modifier = Modifier.weight(1f), enabled = !busy,
                                    onClick = { useCurrentId = node.conversationId })
                            }
                        }
                        Row(Modifier.fillMaxWidth()) {
                            TextButton(stringResource(if (node.conversationId == conversationId) R.string.branch_open_current else R.string.branch_open),
                                onClick = { onOpenConversation(node.conversationId) }, enabled = identity != null, modifier = Modifier.weight(1f))
                            if (node.sourceMessageId != null) TextButton(stringResource(R.string.branch_view_source), onClick = { sourceNode = node }, modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth()) {
                            if (row.hasChildren && !tree.invalid && search.isBlank()) TextButton(stringResource(
                                if (node.conversationId in collapsed) R.string.branch_expand else R.string.branch_collapse),
                                onClick = { collapsed = if (node.conversationId in collapsed) collapsed - node.conversationId else collapsed + node.conversationId }, modifier = Modifier.weight(1f))
                            TextButton(stringResource(R.string.branch_delete), onClick = { deleteId = node.conversationId }, enabled = !busy && identity != null, modifier = Modifier.weight(1f))
                        }
                        if (busy) Text(stringResource(R.string.branch_busy), style = MiuixTheme.textStyles.footnote1)
                        if (related.isNotEmpty()) {
                            Text(stringResource(R.string.branch_runs_count, related.size), style = MiuixTheme.textStyles.footnote1)
                            related.take(3).forEach { run -> TextButton(
                                text = run.modelName.ifBlank { run.modelId }.ifBlank { stringResource(R.string.branch_run) } + " · " + metricStatusLabel(run.status),
                                onClick = { onOpenRun(run.recordId) }) }
                        }
                    }
                }
            }
        }
    }
    WindowDialog(show = useCurrentId != null, title = stringResource(R.string.branch_setup_use_current),
        summary = stringResource(R.string.branch_setup_use_current_note), onDismissRequest = { useCurrentId = null }) {
        MiuixDialogActions(confirmText = stringResource(R.string.branch_setup_use_current),
            confirmEnabled = useCurrentId?.let { !isBusy(it) && it !in setupBusy && it in identities } == true,
            onCancel = { useCurrentId = null }, onConfirm = { useCurrentId?.let { initialize(it, true) }; useCurrentId = null })
    }
    WindowDialog(show = deleteId != null, title = stringResource(R.string.branch_delete),
        summary = stringResource(R.string.branch_delete_note), onDismissRequest = { deleteId = null }) {
        MiuixDialogActions(confirmText = stringResource(R.string.branch_delete), destructive = true,
            confirmEnabled = deleteId?.let { !isBusy(it) && it in identities } == true,
            onCancel = { deleteId = null }, onConfirm = { deleteId?.let(onDelete); deleteId = null })
    }
    WindowDialog(show = sourceNode != null, title = stringResource(R.string.branch_view_source), onDismissRequest = { sourceNode = null }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (sourceLoading) Text(stringResource(R.string.metrics_loading))
            else if (sourceFailed) {
                Text(stringResource(R.string.branch_load_failed), color = MiuixTheme.colorScheme.error)
                TextButton(stringResource(R.string.metrics_refresh), onClick = { reload++ })
            } else if (sourceRows.isEmpty()) Text(stringResource(R.string.branch_source_missing_note))
            else sourceRows.forEach { message ->
                Text(stringResource(if (message.id == sourceNode?.sourceMessageId) R.string.branch_exact_source else R.string.branch_near_source,
                    message.sortIndex + 1), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                Text(message.content.ifBlank { message.resultSummary.orEmpty() }.take(640), style = MiuixTheme.textStyles.body2)
            }
            sourceNode?.sourceConversationId?.takeIf { it in identities }?.let { id ->
                TextButton(stringResource(R.string.branch_open_source), onClick = { sourceNode = null; onOpenConversation(id) })
            }
            TextButton(stringResource(R.string.action_cancel), onClick = { sourceNode = null })
        }
    }
}
