package io.github.mangi.eta.fork.conversations

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class ConversationArchiveWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        try {
            val conversations = EtaDatabase.get(applicationContext).conversationDao().conversationIdentities()
            val activeConversationIds = when (
                val query = AgentRuntimeClient(applicationContext, AndroidAgentLogger).queryActiveRuns()
            ) {
                is AgentRuntimeClient.ActiveRunsQuery.Known -> query.runs.mapTo(hashSetOf()) { it.conversationId }
                AgentRuntimeClient.ActiveRunsQuery.Unavailable -> error("RUNTIME_PRESENCE_UNAVAILABLE")
            }
            val pinnedIds = conversations.asSequence().filter { it.isPinned }.mapTo(hashSetOf()) { it.id }
            ConversationExtensionRepository.archiveEligibleConversations(
                context = applicationContext,
                now = now,
                eligibility = { conversationId ->
                    ConversationArchiveEligibility(
                        isPinned = conversationId in pinnedIds,
                        isMeeting = false,
                        isRunning = conversationId in activeConversationIds,
                        isExempt = false,
                    )
                },
            )
            ConversationExtensionRepository.recordArchiveCheck(
                context = applicationContext,
                checkedAt = now,
                error = null,
            )
            Result.success()
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            AndroidAgentLogger.warn(
                "Conversation auto archive failed: type=${failure.safeLogType()}",
            )
            runCatching {
                ConversationExtensionRepository.recordArchiveCheck(
                    context = applicationContext,
                    checkedAt = now,
                    error = failure.javaClass.simpleName,
                )
            }
            Result.retry()
        }
    }
}
