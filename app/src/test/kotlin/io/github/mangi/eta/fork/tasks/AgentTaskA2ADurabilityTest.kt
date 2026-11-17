package io.github.mangi.eta.fork.tasks

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.fork.a2a.A2A_PROTOCOL_VERSION
import io.github.mangi.eta.fork.a2a.A2AAgentEntity
import io.github.mangi.eta.fork.a2a.A2ADatabase
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D03 outbound A2A chain: the logical run binds one remote task, a long wait is recoverable, a
 * restart queries that remote task, and the original prompt/message is never sent twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentTaskA2ADurabilityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetDatabases() {
        AgentTaskDatabase.closeForTests()
        context.deleteDatabase("eta_task.db")
        A2ADatabase.closeForTests()
    }

    @After
    fun closeDatabases() {
        AgentTaskDatabase.closeForTests()
        A2ADatabase.closeForTests()
    }

    @Test
    fun remoteTaskBindingSurvivesRestartAndPollsOriginalTaskWithoutResendingPrompt() = runBlocking {
        val sends = AtomicInteger()
        val gets = AtomicInteger()
        val sentTexts = CopyOnWriteArrayList<String>()
        val server = JsonRpcPeer { method, request ->
            when (method) {
                "SendMessage" -> {
                    sends.incrementAndGet()
                    val message = request.getJSONObject("params").getJSONObject("message")
                    sentTexts += message.getJSONArray("parts").getJSONObject(0).getString("text")
                    JSONObject().put(
                        "task",
                        a2aTask("remote-task-1", "ctx-1", "TASK_STATE_WORKING"),
                    )
                }
                "GetTask" -> {
                    val call = gets.incrementAndGet()
                    val state = if (call >= 2) "TASK_STATE_COMPLETED" else "TASK_STATE_WORKING"
                    a2aTask("remote-task-1", "ctx-1", state, artifact = if (call >= 2) "report ready" else null)
                }
                else -> throw IllegalStateException("unexpected method " + method)
            }
        }
        try {
            val agent = seedPeer(server.port, "agent-1")
            seedTask(context, "definition-1", agent.id, "run-1")
            val dao = AgentTaskDatabase.get(context).agentTaskDao()
            val runner = durableRunner(context, pollAttempts = 1, remoteWaitMillis = 60_000)
            val firstProcess = AgentTaskCoordinator(
                dao = dao,
                runner = runner,
                ownerId = "worker-1",
                clock = { 1_000 },
                idFactory = { "generated-1" },
            )

            firstProcess.tick()

            val waiting = requireNotNull(dao.run("run-1"))
            assertEquals(AgentTaskState.WAITING_REMOTE, waiting.state)
            assertEquals(agent.id, waiting.a2aAgentId)
            assertEquals("remote-task-1", waiting.a2aTaskId)
            assertEquals("ctx-1", waiting.a2aContextId)
            assertEquals("a2a-run:run-1:0", waiting.a2aMessageId)
            assertEquals(61_000L, waiting.scheduledFor)
            assertEquals(1, sends.get())
            assertEquals(1, gets.get())

            // A new process with a new dispatch identity only queries the bound remote task.
            val restartedProcess = AgentTaskCoordinator(
                dao = dao,
                runner = runner,
                ownerId = "worker-2",
                clock = { 61_000 },
                idFactory = { "generated-2" },
            )
            restartedProcess.tick()

            val done = requireNotNull(dao.run("run-1"))
            assertEquals(AgentTaskState.SUCCEEDED, done.state)
            assertEquals("a2a:remote-task-1", done.resultRef)
            assertEquals("remote-task-1", done.a2aTaskId)
            assertEquals(1, sends.get())
            assertEquals(listOf("生成报告"), sentTexts.toList())
            assertEquals(2, gets.get())
        } finally {
            server.stop()
        }
    }

    @Test
    fun inputRequiredKeepsLogicalRunAndContinuationTargetsOriginalRemoteTask() = runBlocking {
        val messages = CopyOnWriteArrayList<JSONObject>()
        val server = JsonRpcPeer { method, request ->
            when (method) {
                "SendMessage" -> {
                    val message = request.getJSONObject("params").getJSONObject("message")
                    messages += message
                    val state = if (messages.size == 1) "TASK_STATE_INPUT_REQUIRED" else "TASK_STATE_WORKING"
                    JSONObject().put(
                        "task",
                        a2aTask(
                            "remote-task-2",
                            "ctx-2",
                            state,
                            text = if (messages.size == 1) "需要更多信息" else null,
                        ),
                    )
                }
                "GetTask" -> a2aTask("remote-task-2", "ctx-2", "TASK_STATE_COMPLETED")
                else -> throw IllegalStateException("unexpected method " + method)
            }
        }
        try {
            val agent = seedPeer(server.port, "agent-2")
            seedTask(context, "definition-2", agent.id, "run-2")
            val dao = AgentTaskDatabase.get(context).agentTaskDao()
            val runner = durableRunner(context, pollAttempts = 1, remoteWaitMillis = 60_000)
            val coordinator = AgentTaskCoordinator(
                dao = dao,
                runner = runner,
                ownerId = "worker-1",
                clock = { 1_000 },
                idFactory = { "generated" },
            )

            coordinator.tick()

            val waiting = requireNotNull(dao.run("run-2"))
            assertEquals(AgentTaskState.WAITING_INPUT, waiting.state)
            assertEquals("remote-task-2", waiting.a2aTaskId)
            assertEquals("需要更多信息", dao.openAttention().single().body)
            assertEquals(1, messages.size)

            assertTrue(coordinator.resumeWaiting("run-2", "使用上次的报表"))

            val done = requireNotNull(dao.run("run-2"))
            assertEquals(AgentTaskState.SUCCEEDED, done.state)
            assertEquals("remote-task-2", done.a2aTaskId)
            assertEquals("a2a-run:run-2:1", done.a2aMessageId)
            assertEquals(1, done.a2aMessageSeq)
            assertEquals(2, messages.size)
            val continuation = messages[1]
            assertEquals("a2a-run:run-2:1", continuation.getString("messageId"))
            assertEquals("remote-task-2", continuation.getString("taskId"))
            assertEquals("ctx-2", continuation.getString("contextId"))
            assertEquals("使用上次的报表", continuation.getJSONArray("parts").getJSONObject(0).getString("text"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun expiredLeaseResumesBoundRemoteRunButLeavesUnboundDispatchFailed() = runBlocking {
        val agent = seedPeer(port = 0, id = "agent-3")
        seedTask(context, "definition-bound", agent.id, "bound-run")
        val dao = AgentTaskDatabase.get(context).agentTaskDao()

        val bound = requireNotNull(dao.claimNextQueued("worker-1", 1_000, 2_000))
        assertTrue(dao.markRunning(bound.runId, "worker-1", "runtime-1", 1_100, 2_000))
        assertTrue(dao.recordA2ADispatchIntent(bound.runId, agent.id, "a2a-run:bound-run:0", 0, 1_200))
        assertTrue(dao.bindA2ARemoteTask(bound.runId, agent.id, "remote-bound", "ctx-bound", 1_300))

        val resumed = dao.recoverExpiredLeases(2_001)
        assertEquals(1, resumed.resumedRemoteRuns)
        val waiting = requireNotNull(dao.run("bound-run"))
        assertEquals(AgentTaskState.WAITING_REMOTE, waiting.state)
        assertEquals("remote-bound", waiting.a2aTaskId)
        assertNull(waiting.errorCode)
        assertTrue(dao.cancel("bound-run", 2_500))

        seedTask(context, "definition-unknown", agent.id, "unknown-run", now = 3_000)
        val unknown = requireNotNull(dao.claimNextQueued("worker-1", 3_000, 4_000))
        assertTrue(dao.markRunning(unknown.runId, "worker-1", "runtime-2", 3_100, 4_000))
        assertTrue(dao.recordA2ADispatchIntent(unknown.runId, agent.id, "a2a-run:unknown-run:0", 0, 3_200))

        val failed = dao.recoverExpiredLeases(4_001)
        assertEquals(1, failed.failedRunningRuns)
        val unknownRun = requireNotNull(dao.run("unknown-run"))
        assertEquals(AgentTaskState.A2A_REVIEW, unknownRun.state)
        assertEquals("A2A_DISPATCH_UNKNOWN", unknownRun.errorCode)
        assertNull(unknownRun.a2aTaskId)
        assertTrue(
            dao.events("unknown-run").any { it.eventType == "A2A_DISPATCH_UNKNOWN" },
        )
    }

    @Test
    fun unknownDispatchReceiptWaitsForOneTimeReviewWithoutReplayingPrompt() = runBlocking {
        val calls = AtomicInteger()
        val server = JsonRpcPeer { method, _ ->
            calls.incrementAndGet()
            throw IllegalStateException("invalid receipt for " + method)
        }
        try {
            val agent = seedPeer(server.port, "agent-4")
            seedTask(context, "definition-4", agent.id, "run-4")
            val dao = AgentTaskDatabase.get(context).agentTaskDao()
            val coordinator = AgentTaskCoordinator(
                dao = dao,
                runner = durableRunner(context, pollAttempts = 1),
                ownerId = "worker-1",
                clock = { 1_000 },
                idFactory = { "generated" },
            )

            coordinator.tick()

            val review = requireNotNull(dao.run("run-4"))
            assertEquals(AgentTaskState.A2A_REVIEW, review.state)
            assertEquals("A2A_DISPATCH_UNKNOWN", review.errorCode)
            assertEquals("a2a-run:run-4:0", review.a2aMessageId)
            assertNull(review.a2aTaskId)
            assertTrue(dao.observeReviewRuns().first().any { it.id == "run-4" })
            assertEquals(1, calls.get())
            try {
                dao.recordA2AReviewDecision("run-4", "arbitrary", 1_999)
                throw AssertionError("decision allowlist should reject unknown values")
            } catch (_: IllegalArgumentException) {
                // Expected allowlist enforcement.
            }
            assertTrue(dao.recordA2AReviewDecision("run-4", "RESTART_REQUESTED", 2_000))
            assertFalse(dao.recordA2AReviewDecision("run-4", "CHECKED", 2_001))
            assertEquals(AgentTaskState.REVIEW_DECIDED, dao.run("run-4")?.state)
            assertTrue(dao.observeReviewRuns().first().none { it.id == "run-4" })
            assertEquals("RESTART_REQUESTED", dao.run("run-4")?.a2aReviewDecision)
            assertEquals(1, calls.get())
            assertFalse(dao.cancelA2AReview("run-4", 2_002))
        } finally {
            server.stop()
        }
    }

    @Test
    fun deadlineCrossedDuringRunnerMovesToReviewInSameTick() = runBlocking {
        seedTask(context, "definition-crossing-deadline", "peer", "run-crossing-deadline")
        val dao = AgentTaskDatabase.get(context).agentTaskDao()
        var now = 1_000L
        val coordinator = AgentTaskCoordinator(
            dao = dao,
            runner = AgentTaskRunner {
                assertTrue(
                    dao.recordA2ADispatchIntent(
                        runId = it.taskRunId,
                        agentId = "peer",
                        messageId = "a2a-run:run-crossing-deadline:0",
                        messageSeq = 0,
                        now = now,
                    ),
                )
                val deadline = requireNotNull(dao.run(it.taskRunId)?.a2aDeadlineAt)
                now = deadline + 1
                AgentTaskExecutionResult.WaitingRemote(delayMillis = 60_000, status = "TASK_STATE_WORKING")
            },
            ownerId = "deadline-worker",
            clock = { now },
            idFactory = { "generated" },
        )

        coordinator.tick()

        val review = requireNotNull(dao.run("run-crossing-deadline"))
        assertEquals(AgentTaskState.A2A_REVIEW, review.state)
        assertEquals("A2A_WAIT_EXPIRED", review.errorCode)
        assertTrue(review.a2aDeadlineAt!! < now)
    }

    @Test
    fun remoteDeadlinePersistsAcrossCoordinatorRestartAndExpiresIntoReview() = runBlocking {
        val sends = AtomicInteger()
        val server = JsonRpcPeer { method, request ->
            when (method) {
                "SendMessage" -> {
                    sends.incrementAndGet()
                    JSONObject().put(
                        "task",
                        a2aTask("remote-expiring", "ctx-expiring", "TASK_STATE_WORKING"),
                    )
                }
                "GetTask" -> a2aTask("remote-expiring", "ctx-expiring", "TASK_STATE_WORKING")
                else -> error("unexpected method $method")
            }
        }
        try {
            val agent = seedPeer(server.port, "agent-deadline")
            seedTask(context, "definition-deadline", agent.id, "run-deadline")
            val dao = AgentTaskDatabase.get(context).agentTaskDao()
            var now = System.currentTimeMillis()
            AgentTaskCoordinator(
                dao = dao,
                runner = durableRunner(context, pollAttempts = 1, remoteWaitMillis = 60_000),
                ownerId = "worker-1",
                clock = { now },
                idFactory = { "one" },
            ).tick()
            val first = requireNotNull(dao.run("run-deadline"))
            assertEquals(AgentTaskState.WAITING_REMOTE, first.state)
            val persistedDeadline = requireNotNull(first.a2aDeadlineAt)
            assertTrue(persistedDeadline > now)

            // A later poll and process reconstruction retain the original absolute deadline.
            now = first.scheduledFor
            AgentTaskCoordinator(
                dao = dao,
                runner = durableRunner(context, pollAttempts = 1, remoteWaitMillis = 60_000),
                ownerId = "poll-worker",
                clock = { now },
                idFactory = { "poll" },
            ).tick()
            assertEquals(persistedDeadline, requireNotNull(dao.run("run-deadline")).a2aDeadlineAt)

            val restarted = AgentTaskCoordinator(
                dao = dao,
                runner = durableRunner(context, pollAttempts = 1),
                ownerId = "worker-2",
                clock = { persistedDeadline },
                idFactory = { "two" },
            )
            restarted.tick()
            val expired = requireNotNull(dao.run("run-deadline"))
            assertEquals(AgentTaskState.A2A_REVIEW, expired.state)
            assertEquals("A2A_WAIT_EXPIRED", expired.errorCode)
            assertEquals(1, sends.get())
            assertTrue(restarted.cancelRun("run-deadline"))
            assertEquals(AgentTaskState.CANCELED, dao.run("run-deadline")?.state)
            assertEquals("CANCELED", dao.run("run-deadline")?.a2aReviewDecision)
            assertFalse(restarted.cancelRun("run-deadline"))
            assertTrue(!dao.recordA2AReviewDecision("run-deadline", "CHECKED", persistedDeadline + 1_000L))
        } finally {
            server.stop()
        }
    }

    private fun durableRunner(
        context: android.content.Context,
        pollAttempts: Int,
        remoteWaitMillis: Long = 60_000,
    ) = DefaultAgentTaskRunner(
        context = context,
        stateProvider = { AutomationState(charging = true, network = true, unlocked = true, battery = 100) },
        a2aPollAttempts = pollAttempts,
        a2aPollIntervalMillis = 0,
        a2aRemoteWaitMillis = remoteWaitMillis,
    )

    private suspend fun seedPeer(port: Int, id: String): A2AAgentEntity {
        val peer = A2AAgentEntity(
            id = id,
            displayName = "Peer " + id,
            cardUrl = "http://127.0.0.1:" + port + "/card/" + id,
            endpointUrl = "http://127.0.0.1:" + port + "/a2a",
            tenant = null,
            protocolVersion = A2A_PROTOCOL_VERSION,
            authSecretAlias = null,
            trustState = "MANUAL_ALLOWLIST",
            cardJson = "{}",
            enabled = true,
            discoveredAt = 1,
            updatedAt = 1,
        )
        A2ADatabase.get(context).dao().upsertAgent(peer)
        return peer
    }

    private suspend fun seedTask(
        context: android.content.Context,
        definitionId: String,
        targetId: String,
        runId: String,
        now: Long = 1_000,
    ) {
        val dao = AgentTaskDatabase.get(context).agentTaskDao()
        dao.insertScheduledTask(
            definition = AgentTaskDefinitionEntity(
                id = definitionId,
                title = definitionId,
                runKind = AgentTaskRunKind.A2A,
                targetId = targetId,
                promptTemplate = "生成报告",
                createdAt = now,
                updatedAt = now,
            ),
            schedule = AgentTaskScheduleEntity(
                id = definitionId + "-schedule",
                definitionId = definitionId,
                kind = AgentScheduleKind.ONCE,
                startAt = now,
                endAt = null,
                intervalMinutes = null,
                localHour = null,
                localMinute = null,
                weekdaysMask = null,
                timeZoneId = "UTC",
                missedRunPolicy = MissedRunPolicy.RUN_ONCE,
                lastScheduledFor = null,
                nextRunAt = null,
                paused = true,
                revision = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        dao.insertRunIfAbsent(
            AgentTaskRunEntity(
                id = runId,
                definitionId = definitionId,
                scheduleId = definitionId + "-schedule",
                scheduledFor = now,
                attempt = 1,
                idempotencyKey = "manual:" + runId,
                state = AgentTaskState.QUEUED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun a2aTask(
        id: String,
        contextId: String,
        state: String,
        text: String? = null,
        artifact: String? = null,
    ): JSONObject {
        val status = JSONObject().put("state", state)
        if (text != null) {
            status.put(
                "message",
                JSONObject()
                    .put("messageId", "remote-status-" + id)
                    .put("role", "ROLE_AGENT")
                    .put("parts", JSONArray().put(JSONObject().put("text", text))),
            )
        }
        return JSONObject().put("id", id).put("contextId", contextId).put("status", status).apply {
            if (artifact != null) {
                put(
                    "artifacts",
                    JSONArray().put(
                        JSONObject()
                            .put("artifactId", "artifact-1")
                            .put("parts", JSONArray().put(JSONObject().put("text", artifact))),
                    ),
                )
            }
        }
    }

    private class JsonRpcPeer(
        private val handler: (String, JSONObject) -> JSONObject,
    ) {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a2a") { exchange ->
                try {
                    val request = JSONObject(exchange.requestBody.bufferedReader().readText())
                    val result = handler(request.getString("method"), request)
                    val body = JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", request.getString("id"))
                        .put("result", result)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } catch (_: Exception) {
                    exchange.sendResponseHeaders(500, -1)
                } finally {
                    exchange.close()
                }
            }
            start()
        }

        val port: Int get() = server.address.port

        fun stop() = server.stop(0)
    }
}

