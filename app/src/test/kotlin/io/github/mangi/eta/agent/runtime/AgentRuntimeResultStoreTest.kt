package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.db.EtaDatabase
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRuntimeResultStoreTest {
    @Test
    @Config(sdk = [36], application = android.app.Application::class)
    fun recoveryBeyondOnePageKeepsSameTimestampResultsAndDoesNotInterruptOrAcknowledgeThem() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
            android.content.ComponentName(context, "androidx.room.MultiInstanceInvalidationService"))
        val source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
        val ids = (0 until 17).map { "paged-${it.toString().padStart(2, '0')}" }
        ids.reversed().forEach { runId ->
            createCheckpoint(runId)
            assertTrue(AgentRuntimeResultStore.add(context, completedRun(runId, 20L)))
        }
        AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(
            AgentRuntimeWire.EntryHandoff("other-entry", AgentRuntimeWire.MEETING_HANDOFF_SOURCE, "room"),
            AgentRuntimeWire.RunResult("other-entry", true, "member"), 1L))
        val main = android.os.Looper.getMainLooper()
        val service = android.os.Messenger(object : android.os.Handler(main) {
            override fun handleMessage(message: android.os.Message) {
                val response = when (message.what) {
                    AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                        val request = message.data
                        val page = AgentRuntimeResultStore.pendingPage(context, request.getString("handoff_source"),
                            request.getLong(AgentRuntimeWire.KEY_RESULT_AFTER_CREATED_AT, Long.MIN_VALUE),
                            request.getString(AgentRuntimeWire.KEY_RESULT_AFTER_RUN_ID).orEmpty())
                        android.os.Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE).apply {
                            data = AgentRuntimeWire.completedRunsToBundle(page)
                        }
                    }
                    AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                        val runId = AgentRuntimeWire.runIdFromBundle(message.data)
                        val result = AgentRuntimeResultStore.readOwned(context, runId,
                            message.data.getString("context_owner").orEmpty())!!.result
                        android.os.Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply { data = AgentRuntimeWire.toBundle(result) }
                    }
                    else -> error("Recovery cannot acknowledge before the consumer saves: ${message.what}")
                }
                message.replyTo.send(response)
            }
        })
        var registration: android.content.ServiceConnection? = null
        val host = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: android.content.Intent, connection: android.content.ServiceConnection, flags: Int): Boolean {
                registration = connection
                connection.onServiceConnected(android.content.ComponentName(this, "Runtime"), service.binder)
                return true
            }
            override fun unbindService(connection: android.content.ServiceConnection) = Unit
        }
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val query = try {
            val future = worker.submit<AgentRuntimeClient.CompletedRunsQuery> {
                AgentRuntimeClient(host, io.github.mangi.eta.core.AndroidAgentLogger).queryCompletedRuns(source)
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (!future.isDone && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(main).idle()
                Thread.sleep(2)
            }
            future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            registration?.onBindingDied(android.content.ComponentName(host, "Runtime"))
            org.robolectric.Shadows.shadowOf(main).idle()
            worker.shutdownNow()
        }
        assertTrue(query is AgentRuntimeClient.CompletedRunsQuery.Known)
        val recovered = (query as AgentRuntimeClient.CompletedRunsQuery.Known).runs
        val plan = io.github.mangi.eta.ui.app.AgentRunRecoveryCoordinator.plan(
            checkpoints = AgentRunCheckpointStore.list(context), completedRuns = recovered,
            activeStateKnown = true, terminalStateKnown = true,
            activeRunIds = emptySet(), locallyObservedRunIds = emptySet(),
        )
        assertEquals(ids, plan.completed.map { it.result.result.runId })
        assertTrue(plan.interrupted.isEmpty())
        assertEquals(18, AgentRuntimeResultStore.list(context).size)
        assertEquals(17, AgentRunCheckpointStore.list(context).size)
    }

    @Test fun resultRecoveryPageForOneEntryCannotBeStarvedByOtherEntries() {
        repeat(9) { index ->
            AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(
                AgentRuntimeWire.EntryHandoff("meeting-$index", AgentRuntimeWire.MEETING_HANDOFF_SOURCE, "room"),
                AgentRuntimeWire.RunResult("meeting-$index", true, "member"), index.toLong()))
        }
        val result = AgentRuntimeWire.CompletedRun(AgentRuntimeWire.EntryHandoff("ui", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            AgentRuntimeWire.RunResult("ui", true, "answer"), 10L)
        AgentRuntimeResultStore.add(context, result)
        assertEquals(8, AgentRuntimeResultStore.pendingPage(context).size)
        assertEquals("ui", AgentRuntimeResultStore.pendingPage(context, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE).single().result.runId)
        assertEquals(10, AgentRuntimeResultStore.list(context).size)
    }

    @Test
    // Legacy SQLite uses an in-memory backend on Windows; reopening it cannot verify persistence.
    @SQLiteMode(SQLiteMode.Mode.NATIVE)
    fun rewriteTargetSurvivesDatabaseReopenInResultHeaderArchiveAndCheckpoint() {
        val runId = "rewrite-storage-${System.nanoTime()}"
        val target = "assistant-original-1-0"
        val handoff = AgentRuntimeWire.EntryHandoff(runId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation-1")
        val result = AgentRuntimeWire.RunResult(runId, true, "新的措辞",
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = target)
        val request = AgentRuntimeWire.RunRequest(
            runId = runId, prompt = "原始措辞", handoff = handoff, images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1", apiKey = "", model = "model", systemPrompt = ""),
            operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = target,
        )
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(handoff, emptyList(), result, 1L))
        AgentRuntimeResultStore.add(context, AgentRuntimeWire.CompletedRun(handoff, result, 1L))
        EtaDatabase.closeForTests()

        assertEquals(target, AgentRuntimeResultStore.pendingPage(context).single().result.rewriteTargetMessageId)
        assertEquals(target, AgentRuntimeResultStore.readOwned(context, runId, handoff.payload)!!.result.rewriteTargetMessageId)
        assertEquals(target, AgentRunArchiveStore.list(context).single().result.rewriteTargetMessageId)
        assertEquals(target, AgentRunCheckpointStore.list(context).single().rewriteTargetMessageId)
    }

    @Test
    fun emptyLegacyTranscriptFallsBackToSuccessfulAssistantContent() {
        val runId = "legacy-${System.nanoTime()}"
        AgentRuntimeResultStore.add(
            context,
            AgentRuntimeWire.CompletedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = runId,
                    source = "agent_ui",
                    payload = "conversation-1",
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = true,
                    content = "旧版本结果",
                ),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(listOf("assistant"), restored.result.transcript.map { it.role })
        assertEquals("旧版本结果", restored.result.transcript.single().content)
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun acknowledgementBeforeCompletionPreventsLateResultWriteBack() {
        val runId = "ack-before-add-${System.nanoTime()}"
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "obsolete result",
            ),
            createdAt = System.currentTimeMillis(),
        )

        AgentRuntimeResultStore.remove(context, runId)

        assertFalse(AgentRuntimeResultStore.add(context, completedRun))
        assertTrue(AgentRuntimeResultStore.list(context).none { it.result.runId == runId })
    }

    @Test
    fun saveAndLoadPreservesTranscript() {
        val runId = "transcript-${System.nanoTime()}"
        val transcript = listOf(
            AgentModelClient.ConversationMessage(
                role = "assistant",
                toolCallsJson = "[{\"id\":\"call-1\"}]",
            ),
            AgentModelClient.ConversationMessage(
                role = "tool",
                toolCallId = "call-1",
                content = "{\"ok\":true}",
            ),
            AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
        )
        val completedRun = AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = "breeno",
                payload = "{}",
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = true,
                content = "完成",
                transcript = transcript,
            ),
            createdAt = System.currentTimeMillis(),
        )

        assertTrue(AgentRuntimeResultStore.add(context, completedRun))

        val restored = AgentRuntimeResultStore.list(context).single { it.result.runId == runId }
        assertEquals(transcript.map { it.role }, restored.result.transcript.map { it.role })
        assertEquals("call-1", restored.result.transcript[1].toolCallId)
        assertEquals("完成", restored.result.transcript.last().content)
    }

    @Test
    fun pausedResultKeepsPauseDetailsForADrainAfterReconnect() {
        val runId = "pause-outbox-${System.nanoTime()}"
        val handoff = AgentRuntimeWire.EntryHandoff(runId, "a2a", "context-1")
        val pause = AgentRuntimeWire.RunPause(
            kind = AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
            reason = "The operation needs approval from the device owner before it can run.",
            pendingCallId = "call-1",
            pendingTool = "write_file",
            pendingEffect = "REVERSIBLE_WRITE",
            pendingArgumentsDigest = "a".repeat(64),
        )
        assertTrue(
            AgentRuntimeResultStore.add(
                context,
                AgentRuntimeWire.CompletedRun(
                    handoff = handoff,
                    result = AgentRuntimeWire.RunResult(
                        runId = runId,
                        ok = false,
                        content = "",
                        error = "需要授权后才能继续本次任务",
                        failureCode = "A2A_AUTH_REQUIRED",
                        pause = pause,
                    ),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        )

        // drain 以引用返回结果，再按 handoff owner 读取完整终态。
        assertEquals(runId, AgentRuntimeResultStore.pendingPage(context).single().result.contextSnapshotRef)
        val restored = AgentRuntimeResultStore.readOwned(context, runId, handoff.payload)!!.result
        assertEquals(pause, restored.pause)
    }

    @Test
    fun acknowledgementRemovesCommittedCheckpoint() {
        val runId = "checkpoint-${System.nanoTime()}"
        val request = AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "检查运行状态",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )
        val recorder = AgentRunCheckpointRecorder.create(context, request)!!
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.seal()
        assertTrue(
            AgentRuntimeResultStore.add(
                context,
                AgentRuntimeWire.CompletedRun(
                    handoff = requireNotNull(request.handoff),
                    result = AgentRuntimeWire.RunResult(
                        runId = runId,
                        ok = true,
                        content = "完成",
                    ),
                    createdAt = System.currentTimeMillis(),
                ),
            )
        )

        assertEquals(
            runId,
            AgentRunCheckpointStore.list(context).single().runId,
        )

        AgentRuntimeResultStore.remove(context, runId)

        assertTrue(AgentRuntimeResultStore.list(context).none { it.result.runId == runId })
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
    }

    @Test
    fun unacknowledgedResultsAndCheckpointsAreNotEvictedByCount() {
        val now = System.currentTimeMillis()
        repeat(9) { index ->
            val runId = "capacity-$index"
            createCheckpoint(runId)
            assertTrue(
                AgentRuntimeResultStore.add(
                    context,
                    completedRun(runId, createdAt = now + index),
                )
            )
        }

        assertEquals(9, AgentRuntimeResultStore.list(context).size)
        assertTrue(AgentRuntimeResultStore.list(context).any { it.result.runId == "capacity-0" })
        assertTrue(AgentRunCheckpointStore.list(context).any { it.runId == "capacity-0" })
        assertEquals(9, AgentRunCheckpointStore.list(context).size)
    }

    @Test
    fun unacknowledgedResultsAndCheckpointsAreNotEvictedByAge() {
        val runId = "expired-result"
        createCheckpoint(runId)

        assertTrue(
            AgentRuntimeResultStore.add(
                context,
                completedRun(
                    runId = runId,
                    createdAt = System.currentTimeMillis() - 13L * 60L * 60L * 1000L,
                ),
            )
        )

        assertEquals(runId, AgentRuntimeResultStore.list(context).single().result.runId)
        assertEquals(runId, AgentRunCheckpointStore.list(context).single().runId)
    }

    private fun createCheckpoint(runId: String) {
        val recorder = AgentRunCheckpointRecorder.create(
            context,
            AgentRuntimeWire.RunRequest(
                runId = runId,
                prompt = "测试",
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.com/v1",
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                ),
                images = emptyList(),
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = runId,
                    source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                    payload = "conversation-1",
                ),
            ),
        )!!
        recorder.accept(AgentEvent.RunStarted(0, 0, 1, false))
        recorder.seal()
    }

    private fun completedRun(
        runId: String,
        createdAt: Long,
    ) = AgentRuntimeWire.CompletedRun(
        handoff = AgentRuntimeWire.EntryHandoff(
            id = runId,
            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            payload = "conversation-1",
        ),
        result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = true,
            content = "完成",
        ),
        createdAt = createdAt,
    )
}
