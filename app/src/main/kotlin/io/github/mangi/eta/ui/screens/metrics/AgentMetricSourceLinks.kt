package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRunMetricsRecord
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.meeting.MeetingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun AgentMetricSourceLinks(record: AgentRunMetricsRecord, onOpenConversation: (String) -> Unit,
    onOpenMeeting: (String) -> Unit, onOpenTasks: () -> Unit) {
    val context = LocalContext.current
    val chatExists by remember(context, record.conversationId) {
        if (record.conversationId.isBlank()) flowOf(false)
        else EtaDatabase.get(context).conversationDao().conversationExistsFlow(record.conversationId)
            .catch { emit(false) }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = false)
    val roomExists by remember(context, record.roomId) {
        if (record.roomId.isBlank()) flowOf(false)
        else MeetingRepository.room(context, record.roomId).map { it != null }.catch { emit(false) }.flowOn(Dispatchers.IO)
    }.collectAsState(initial = false)
    if (record.conversationId.isNotBlank()) ArrowPreference(title = stringResource(R.string.metrics_open_chat),
        summary = if (chatExists) null else stringResource(R.string.metrics_source_unavailable), enabled = chatExists,
        onClick = { onOpenConversation(record.conversationId) })
    if (record.roomId.isNotBlank()) ArrowPreference(title = stringResource(R.string.metrics_open_meeting),
        summary = if (roomExists) null else stringResource(R.string.metrics_source_unavailable), enabled = roomExists,
        onClick = { onOpenMeeting(record.roomId) })
    if (record.taskId.isNotBlank()) ArrowPreference(title = stringResource(R.string.metrics_open_tasks), onClick = onOpenTasks)
}
