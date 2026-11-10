package io.github.mangi.eta.ui.screens.metrics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentMetricsExport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentMetricsExportControls(recordIds: List<String>, enabled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingIds by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var status by rememberSaveable { mutableStateOf<Int?>(null) }
    var exportedRuns by rememberSaveable { mutableIntStateOf(0) }
    var missingRuns by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (status == R.string.metrics_export_working && job == null) status = R.string.metrics_export_cancelled
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val selected = pendingIds
        pendingIds = null
        if (uri == null || selected == null) return@rememberLauncherForActivityResult
        status = R.string.metrics_export_working
        job = scope.launch {
            try {
                val result = AgentMetricsExport.export(context, uri, selected)
                exportedRuns = result.exportedRuns
                missingRuns = result.missingRuns
                status = R.string.metrics_export_success
            } catch (_: TimeoutCancellationException) {
                status = R.string.metrics_export_timeout
            } catch (cancelled: CancellationException) {
                status = R.string.metrics_export_cancelled
                throw cancelled
            } catch (_: Exception) {
                status = R.string.metrics_export_failed
            } finally { job = null }
        }
    }
    val tooMany = recordIds.size > AgentMetricsExport.MAX_RUNS
    ArrowPreference(title = stringResource(R.string.metrics_export_title),
        summary = stringResource(if (tooMany) R.string.metrics_export_limit else R.string.metrics_export_note),
        enabled = enabled && recordIds.isNotEmpty() && !tooMany && job == null && pendingIds == null,
        onClick = {
            pendingIds = recordIds.toList()
            try {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                launcher.launch("Nexus-run-diagnostics-$stamp.json")
            } catch (_: Exception) {
                pendingIds = null
                status = R.string.metrics_export_failed
            }
        })
    status?.let {
        val text = if (it == R.string.metrics_export_success) stringResource(it, exportedRuns, missingRuns) else stringResource(it)
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MiuixTheme.textStyles.footnote1,
            color = if (it == R.string.metrics_export_failed || it == R.string.metrics_export_timeout) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    if (job != null) ArrowPreference(title = stringResource(R.string.action_cancel), onClick = { job?.cancel() })
}
