package io.github.mangi.eta.fork.a2a

import android.app.Application
import androidx.room.Room
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AInboundTaskCoordinatorTest {
    private lateinit var database: A2ADatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            A2ADatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `synchronous task is persisted and duplicate message id is idempotent`() = runBlocking {
        val calls = AtomicInteger()
        val histories = mutableListOf<Int>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    calls.incrementAndGet()
                    histories += request.history.size
                    return "answer for ${request.prompt}"
                }
            },
            clock = { 1_000L },
            idFactory = sequenceOf("context-1", "task-1", "task-2").iterator()::next,
        )
        val request = request(messageId = "message-1", contextId = null, prompt = "hello")
        val first = handler.sendMessage(request)
        val firstTask = requireNotNull(first.task)
        assertEquals(A2ATaskState.COMPLETED, firstTask.status.state)
        assertEquals("answer for hello", firstTask.artifacts.single().parts.single().text)

        val retry = handler.sendMessage(request)
        assertEquals(firstTask.id, retry.task?.id)
        assertEquals(1, calls.get())

        val second = handler.sendMessage(request(messageId = "message-2", contextId = "context-1", prompt = "again"))
        assertEquals(A2ATaskState.COMPLETED, second.task?.status?.state)
        assertEquals(listOf(0, 2), histories)
    }

    @Test
    fun `immediate task can be polled and canceled`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    gate.await()
                    return "late answer"
                }
            },
            idFactory = sequenceOf("context-2", "task-2").iterator()::next,
        )
        val response = handler.sendMessage(
            request(messageId = "message-2", contextId = null, prompt = "wait")
                .copy(configuration = A2ASendMessageConfiguration(returnImmediately = true)),
        )
        val taskId = requireNotNull(response.task?.id)
        val canceled = handler.cancelTask(taskId)
        assertEquals(A2ATaskState.CANCELED, canceled.status.state)
        assertEquals(A2ATaskState.CANCELED, handler.getTask(taskId).status.state)
    }

    @Test
    fun `task receipts and contexts are only visible to their credential owner`() = runBlocking {
        val handler = PersistentA2AInboundTaskHandler(database.dao(), object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest) = "owner answer"
        })
        val original = request("owned-message", "owned-context", "hello")
        val owner = A2AToolAccess.readOnly("credential-owner")
        val other = A2AToolAccess.readOnly("other-credential")
        val task = requireNotNull(handler.sendMessage(original, owner).task)
        handler.authorizeTask(task.id, owner.peerId)
        assertTrue(runCatching { handler.authorizeTask(task.id, other.peerId) }.isFailure)
        assertTrue(runCatching { handler.sendMessage(original, other) }.isFailure)
        assertTrue(runCatching { handler.sendMessage(request("other-message", "owned-context", "hello"), other) }.isFailure)
        assertEquals(A2ATaskState.COMPLETED, handler.getTask(task.id).status.state)
        handler.close()
    }

    @Test
    fun `executor failure becomes a terminal task with a status message`() = runBlocking {
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    throw A2AProtocolException("model unavailable")
                }
            },
            idFactory = sequenceOf("context-3", "task-3").iterator()::next,
        )
        val task = requireNotNull(
            handler.sendMessage(request(messageId = "message-3", contextId = null, prompt = "fail")).task,
        )
        assertEquals(A2ATaskState.FAILED, task.status.state)
        // The peer only learns the generic local failure; the raw model error stays on this device.
        assertEquals(
            "Local Agent execution failed; review the task on the device.",
            task.status.message?.parts?.single()?.text,
        )
        assertTrue(task.artifacts.isEmpty())
    }

    @Test
    fun `pending tasks are recovered after process restart without replaying an interrupted turn`() = runBlocking {
        val dao = database.dao()
        dao.insertInboundTask(
            A2AInboundTaskEntity(
                taskId = "recover-submitted",
                requestMessageId = "recover-message-1",
                contextId = "recover-context",
                prompt = "first",
                state = A2ATaskState.SUBMITTED.name,
                statusMessage = null,
                answer = null,
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        dao.insertInboundTask(
            A2AInboundTaskEntity(
                taskId = "recover-working",
                requestMessageId = "recover-message-2",
                contextId = "recover-context",
                prompt = "second",
                state = A2ATaskState.WORKING.name,
                statusMessage = null,
                answer = null,
                createdAt = 20,
                updatedAt = 20,
            ),
        )
        val executed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = dao,
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    executed += request.taskId
                    return "recovered ${request.prompt}"
                }
            },
        )

        assertEquals(2, handler.recoverPendingTasks())
        awaitInboundState(dao, setOf("recover-submitted"), A2ATaskState.COMPLETED)
        awaitInboundState(dao, setOf("recover-working"), A2ATaskState.FAILED)
        // A SUBMITTED row never ran; a WORKING row lost its in-memory pause identity and must not
        // be replayed, because the previous process may already have completed side effects.
        assertEquals(setOf("recover-submitted"), executed)
        handler.close()
    }

    @Test
    fun `recovery adopts a complete artifact instead of invoking the model again`() = runBlocking {
        val dao = database.dao()
        dao.insertInboundTask(
            A2AInboundTaskEntity(
                taskId = "artifact-task",
                requestMessageId = "artifact-message",
                contextId = "artifact-context",
                prompt = "prompt",
                state = A2ATaskState.WORKING.name,
                statusMessage = null,
                answer = null,
                createdAt = 10,
                updatedAt = 10,
            ),
        )
        val artifact = A2AArtifact(
            artifactId = "artifact-task-result",
            parts = listOf(A2APart(text = "already produced")),
        )
        dao.upsertInboundArtifact(
            A2AInboundArtifactEntity(
                taskId = "artifact-task",
                artifactId = artifact.artifactId,
                rawJson = A2A_JSON.encodeToString(artifact),
                receivedAt = 11,
            ),
        )
        val calls = AtomicInteger()
        val handler = PersistentA2AInboundTaskHandler(
            dao = dao,
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    calls.incrementAndGet()
                    return "should not run"
                }
            },
        )

        assertEquals(1, handler.recoverPendingTasks())
        val recovered = requireNotNull(dao.inboundTask("artifact-task"))
        assertEquals(A2ATaskState.COMPLETED.name, recovered.state)
        assertEquals("already produced", recovered.answer)
        assertEquals(0, calls.get())
        handler.close()
    }

    @Test
    fun `closing the endpoint cancels runtime work and persists a terminal state`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val runtimeCancels = AtomicInteger()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    entered.complete(Unit)
                    awaitCancellation()
                }

                override fun cancel(taskId: String) {
                    runtimeCancels.incrementAndGet()
                }
            },
            idFactory = sequenceOf("close-context", "close-task").iterator()::next,
        )
        val response = handler.sendMessage(
            request(messageId = "close-message", contextId = null, prompt = "wait")
                .copy(configuration = A2ASendMessageConfiguration(returnImmediately = true)),
        )
        entered.await()

        handler.close()

        assertEquals(1, runtimeCancels.get())
        assertEquals(
            A2ATaskState.CANCELED.name,
            database.dao().inboundTask(requireNotNull(response.task).id)?.state,
        )
    }

    @Test
    fun `http endpoint exposes persistent task and polling fallback`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "persistent-test-token"
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    delay(75)
                    return "polled: ${request.prompt}"
                }
            },
            idFactory = sequenceOf("context-http", "task-http").iterator()::next,
        )
        val engine = A2AInboundEngine(port, { token }, handler)
        engine.start()
        try {
            val client = A2AHttpClient(a2aPeerPolicyForManualEndpoint("http://127.0.0.1:$port/a2a"))
            val sent = client.sendMessage(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = request(messageId = "http-message", contextId = null, prompt = "hello")
                    .copy(configuration = A2ASendMessageConfiguration(returnImmediately = true)),
                bearerToken = token,
            )
            val sentTask = requireNotNull(sent.task)
            val taskId = sentTask.id
            assertTrue(sentTask.status.state == A2ATaskState.SUBMITTED ||
                sentTask.status.state == A2ATaskState.WORKING)
            val completed = client.waitForTask(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = A2AGetTaskRequest(id = taskId),
                bearerToken = token,
                timeoutMillis = 3_000,
                pollIntervalMillis = 50,
            )
            assertEquals(A2ATaskState.COMPLETED, completed.status.state)
            assertEquals("polled: hello", completed.artifacts.single().parts.single().text)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `input required task survives restart and resumes the same task without rerunning the original prompt`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        var calls = 0
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return if (calls++ == 0) {
                    A2AInboundExecutionOutcome.InputRequired("Which report?")
                } else {
                    A2AInboundExecutionOutcome.Completed("report ready")
                }
            }
        }
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 1_000L },
            idFactory = sequenceOf("context-in", "task-in").iterator()::next,
        )
        val first = requireNotNull(
            handler.sendMessage(request(messageId = "message-in", contextId = null, prompt = "open the report")).task,
        )
        assertEquals(A2ATaskState.INPUT_REQUIRED, first.status.state)
        assertEquals("Which report?", first.status.message?.parts?.single()?.text)
        handler.close()

        val restarted = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 2_000L },
        )
        assertEquals(1, restarted.recoverPendingTasks())
        assertEquals(A2ATaskState.INPUT_REQUIRED, restarted.getTask("task-in").status.state)
        assertEquals(1, requests.size)

        val continuation = continuation(
            messageId = "message-in-2",
            taskId = "task-in",
            contextId = "context-in",
            text = "the weekly one",
        )
        val resumed = requireNotNull(restarted.sendMessage(continuation).task)
        assertEquals("task-in", resumed.id)
        assertEquals("context-in", resumed.contextId)
        assertEquals(A2ATaskState.COMPLETED, resumed.status.state)
        assertEquals("report ready", resumed.artifacts.single().parts.single().text)
        assertEquals(2, requests.size)
        assertEquals("the weekly one", requests[1].prompt)
        assertTrue(requests[1].continuation)
        assertTrue(requests[1].history.any { it.role == "user" && it.content == "open the report" })
        assertFalse(requests[1].history.any { it.content == "the weekly one" })

        val retry = requireNotNull(restarted.sendMessage(continuation).task)
        assertEquals("task-in", retry.id)
        assertEquals(A2ATaskState.COMPLETED, retry.status.state)
        assertEquals(2, requests.size)
        restarted.close()
    }

    @Test
    fun `http continuation resumes the same task and deduplicates retries`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "continuation-test-token"
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        var calls = 0
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String =
                    error("executeOutcome must be used")

                override suspend fun executeOutcome(
                    request: A2AInboundExecutionRequest,
                ): A2AInboundExecutionOutcome {
                    requests += request
                    return if (calls++ == 0) {
                        A2AInboundExecutionOutcome.InputRequired("Which quarter?")
                    } else {
                        A2AInboundExecutionOutcome.Completed("quarterly report")
                    }
                }
            },
            idFactory = sequenceOf("http-context", "http-task").iterator()::next,
        )
        val engine = A2AInboundEngine(port, { token }, handler)
        engine.start()
        try {
            val endpoint = "http://127.0.0.1:$port/a2a"
            val client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(endpoint))
            val first = requireNotNull(
                client.sendMessage(
                    endpointUrl = endpoint,
                    request = request("http-input-1", null, "build the report"),
                    bearerToken = token,
                ).task,
            )
            assertEquals(A2ATaskState.INPUT_REQUIRED, first.status.state)
            val followUp = continuation(
                messageId = "http-input-2",
                taskId = first.id,
                contextId = requireNotNull(first.contextId),
                text = "Q3",
            )
            val resumed = requireNotNull(client.sendMessage(endpoint, followUp, token).task)
            assertEquals(first.id, resumed.id)
            assertEquals(first.contextId, resumed.contextId)
            assertEquals(A2ATaskState.COMPLETED, resumed.status.state)
            assertEquals("quarterly report", resumed.artifacts.single().parts.single().text)
            assertEquals(2, requests.size)
            assertEquals("Q3", requests[1].prompt)
            assertTrue(requests[1].continuation)

            val retry = requireNotNull(client.sendMessage(endpoint, followUp, token).task)
            assertEquals(A2ATaskState.COMPLETED, retry.status.state)
            assertEquals(2, requests.size)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `auth required resumes only within the stored grant and rejects permission expansion`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        var calls = 0
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return if (calls++ == 0) {
                    A2AInboundExecutionOutcome.AuthRequired(
                        "approve the requested tool",
                        authPause(tool = "write_file", digest = "c".repeat(64)),
                    )
                } else {
                    A2AInboundExecutionOutcome.Completed("approved and done")
                }
            }
        }
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 5_000L },
            idFactory = sequenceOf("context-auth", "task-auth").iterator()::next,
        )
        val stored = A2AToolAccess.readOnly("peer-a")
        val first = requireNotNull(
            handler.sendMessage(request("auth-1", null, "use the terminal"), stored).task,
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)
        assertEquals(1, requests.size)

        val escalation = continuation(
            messageId = "auth-2",
            taskId = "task-auth",
            contextId = "context-auth",
            text = "approved",
            requestedTools = listOf("terminal"),
        )
        val rejected = requireNotNull(
            handler.sendMessage(escalation, A2AToolAccess.restricted("peer-a")).task,
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, rejected.status.state)
        assertTrue(
            rejected.status.message?.parts?.single()?.text.orEmpty().startsWith("Authorization required:"),
        )
        assertEquals(1, requests.size)
        assertFalse(requireNotNull(database.dao().inboundTask("task-auth")).accessJson.orEmpty().contains("terminal"))

        val approval = continuation(
            messageId = "auth-3",
            taskId = "task-auth",
            contextId = "context-auth",
            text = "",
        )
        val waiting = requireNotNull(handler.sendMessage(approval, stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, waiting.status.state)
        assertTrue(handler.decideAuthorization("task-auth", approve = true, currentAccess = stored))
        val completed = requireNotNull(handler.getTask("task-auth").let { A2ASendMessageResponse(it) }.task)
        assertEquals(A2ATaskState.COMPLETED, completed.status.state)
        assertEquals(2, requests.size)
        assertEquals(stored.allowedTools, requests[1].access.allowedTools)
        assertFalse(requests[1].access.allowedTools.contains("terminal"))
        val approvalGrant = requireNotNull(requests[1].localApproval)
        assertEquals("task-auth", approvalGrant.taskId)
        assertEquals("auth-3", approvalGrant.messageId)
        assertEquals(1, approvalGrant.continuationCount)
        assertEquals(1, requests[1].continuationCount)
        assertTrue(approvalGrant.allowedTools.contains("request_user_input"))
        assertFalse(approvalGrant.allowedTools.contains("terminal"))
        assertEquals(approvalGrant.allowedTools.size, approvalGrant.allowedToolEffects.size)

        val retry = requireNotNull(handler.sendMessage(approval, stored).task)
        assertEquals(A2ATaskState.COMPLETED, retry.status.state)
        assertEquals(2, requests.size)
        handler.close()
    }

    @Test
    fun `initial authorization pause can be approved within the stored grant`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-initial", "task-initial").iterator()::next,
        )
        val stored = A2AToolAccess(
            peerId = "peer-initial",
            tier = A2AToolTier.RESTRICTED,
            allowedTools = setOf("write_file", "read_file"),
        )
        val first = requireNotNull(handler.sendMessage(request("initial-1", null, "write the report"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)
        val pending = database.dao().inboundContinuations("task-initial").single()
        assertEquals("AUTH_REQUIRED_PENDING", pending.outcome)
        assertEquals("context-initial", pending.contextId)
        assertEquals(1, requests.size)

        assertTrue(handler.decideAuthorization("task-initial", approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.COMPLETED, handler.getTask("task-initial").status.state)
        assertEquals(2, requests.size)
        val resumed = requests[1]
        assertEquals("task-initial", resumed.taskId)
        assertEquals("context-initial", resumed.contextId)
        assertTrue(resumed.continuation)
        assertEquals(1, resumed.continuationCount)
        assertNotEquals("the original prompt must not be replayed", "write the report", resumed.prompt)
        assertEquals("write the report", resumed.history.first().content)
        assertEquals(stored.allowedTools, resumed.access.allowedTools)
        val grant = requireNotNull(resumed.localApproval)
        assertEquals(pending.messageId, grant.messageId)
        assertEquals(1, grant.continuationCount)
        assertEquals(
            stored.allowedTools - A2AToolAccess.HIGH_RISK_TOOLS + A2AInboundRuntimeOutcome.INPUT_TOOL,
            grant.allowedTools,
        )
        assertEquals(grant.allowedTools.size, grant.allowedToolEffects.size)
        handler.close()
    }

    @Test
    fun `initial authorization pause can be denied without running the task`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-denied", "task-denied").iterator()::next,
        )
        val stored = A2AToolAccess("peer-denied", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("denied-1", null, "write the report"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)

        assertTrue(handler.decideAuthorization("task-denied", approve = false, currentAccess = stored))
        assertEquals(A2ATaskState.REJECTED, handler.getTask("task-denied").status.state)
        assertEquals(1, requests.size)
        assertFalse(handler.decideAuthorization("task-denied", approve = true, currentAccess = stored))
        assertEquals(1, requests.size)
        handler.close()
    }

    @Test
    fun `initial authorization decision fails closed when the current grant shrank`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-narrowed", "task-narrowed").iterator()::next,
        )
        val stored = A2AToolAccess("peer-narrowed", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("narrowed-1", null, "write the report"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)

        val narrowed = stored.copy(allowedTools = stored.allowedTools - "write_file")
        assertFalse(handler.decideAuthorization("task-narrowed", approve = true, currentAccess = narrowed))
        assertEquals(A2ATaskState.AUTH_REQUIRED, handler.getTask("task-narrowed").status.state)
        assertEquals(1, requests.size)
        handler.close()
    }

    @Test
    fun `narrowed continuation reports a generic authorization message without leaking tools`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-missing", "task-missing").iterator()::next,
        )
        val stored = A2AToolAccess("peer-missing", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("missing-1", null, "write the report"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)

        val narrowed = stored.copy(allowedTools = stored.allowedTools - "write_file")
        val response = requireNotNull(
            handler.sendMessage(
                continuation("missing-2", "task-missing", "context-missing", ""),
                narrowed,
            ).task,
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, response.status.state)
        val status = response.status.message?.parts?.single()?.text.orEmpty()
        assertEquals(
            "Authorization required: the device owner must review this continuation.",
            status,
        )
        // The peer must not learn this device's tool inventory from the pause status.
        assertFalse(status.contains("write_file"))
        assertEquals(1, requests.size)
        handler.close()
    }

    @Test
    fun `authorization without a pending continuation fails closed`() = runBlocking {
        val dao = database.dao()
        val stored = A2AToolAccess("peer-orphan", A2AToolTier.RESTRICTED, setOf("write_file"))
        dao.insertInboundTask(
            A2AInboundTaskEntity(
                taskId = "task-orphan",
                requestMessageId = "orphan-1",
                contextId = "context-orphan",
                prompt = "write the report",
                state = A2ATaskState.AUTH_REQUIRED.name,
                statusMessage = "Authorization required",
                answer = null,
                createdAt = 1,
                updatedAt = 2,
                accessJson = A2A_JSON.encodeToString(stored),
            ),
        )
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = dao,
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
        )

        assertFalse(handler.decideAuthorization("task-orphan", approve = true, currentAccess = stored))
        assertFalse(handler.decideAuthorization("task-orphan", approve = false, currentAccess = stored))
        assertEquals(A2ATaskState.AUTH_REQUIRED, handler.getTask("task-orphan").status.state)
        assertTrue(requests.isEmpty())
        handler.close()
    }

    private fun authFirstExecutor(requests: MutableList<A2AInboundExecutionRequest>) = object : A2AInboundExecutor {
        override suspend fun execute(request: A2AInboundExecutionRequest): String =
            error("executeOutcome must be used")

        override suspend fun executeOutcome(request: A2AInboundExecutionRequest): A2AInboundExecutionOutcome {
            requests += request
            return if (requests.size == 1) {
                A2AInboundExecutionOutcome.AuthRequired("approve write_file", authPause())
            } else {
                A2AInboundExecutionOutcome.Completed("approved and done")
            }
        }
    }

    private fun authPause(
        tool: String = "write_file",
        effect: String = io.github.zhh_lgtm.nexus.kernel.api.EffectClass.REVERSIBLE_WRITE.name,
        digest: String = "a".repeat(64),
        settledCalls: List<String> = emptyList(),
        settledEffectful: Boolean = false,
    ) = AgentRuntimeWire.RunPause(
        kind = AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
        reason = "The operation needs approval from the device owner before it can run.",
        pendingCallId = "call-$tool",
        pendingTool = tool,
        pendingEffect = effect,
        pendingArgumentsDigest = digest,
        pendingExecuted = false,
        settledCalls = settledCalls,
        settledEffectful = settledEffectful,
    )

    @Test
    fun `recovery runs a submitted continuation instead of replaying the original prompt`() = runBlocking {
        val dao = database.dao()
        val access = A2AToolAccess(
            peerId = "peer-r",
            tier = A2AToolTier.READ_ONLY,
            allowedTools = setOf("read_file"),
        )
        dao.insertInboundTask(
            A2AInboundTaskEntity(
                taskId = "continued-task",
                requestMessageId = "continued-message",
                contextId = "continued-context",
                prompt = "original prompt",
                state = A2ATaskState.SUBMITTED.name,
                statusMessage = null,
                answer = null,
                createdAt = 10,
                updatedAt = 20,
                continuationMessageId = "continued-follow-up",
                continuationInput = "use the third quarter",
                continuationCount = 1,
                accessJson = A2A_JSON.encodeToString(access),
            ),
        )
        // acceptInboundContinuation persists the row in the same transaction as the WORKING claim,
        // so a crash before dispatch always leaves this durable ACCEPTED continuation behind.
        dao.insertInboundContinuation(
            A2AInboundContinuationEntity(
                messageId = "continued-follow-up",
                taskId = "continued-task",
                contextId = "continued-context",
                input = "use the third quarter",
                outcome = "ACCEPTED",
                acceptedAt = 20,
            ),
        )
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = dao,
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String {
                    requests += request
                    return "continued answer"
                }
            },
        )

        assertEquals(1, handler.recoverPendingTasks())
        awaitInboundState(dao, setOf("continued-task"), A2ATaskState.COMPLETED)
        assertEquals(1, requests.size)
        assertEquals("use the third quarter", requests.single().prompt)
        assertTrue(requests.single().continuation)
        assertTrue(requests.single().history.any { it.content == "original prompt" })
        assertEquals(setOf("read_file"), requests.single().access.allowedTools)
        handler.close()
    }

    @Test
    fun `runtime input pause leaves the task waiting and never replays the original prompt`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return if (requests.size == 1) {
                    A2AInboundRuntimeOutcome.map(
                        AgentRuntimeWire.RunResult(
                            runId = request.taskId,
                            ok = false,
                            content = "",
                            error = "需要补充输入后才能继续本次任务",
                            failureCode = "A2A_INPUT_REQUIRED",
                            pause = AgentRuntimeWire.RunPause(
                                kind = AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED,
                                question = "Which key should be rotated?",
                                options = listOf("prod", "staging"),
                            ),
                        ),
                        request.access,
                    )
                } else {
                    A2AInboundExecutionOutcome.Completed("rotated")
                }
            }
        }
        val stored = A2AToolAccess.restricted("peer-pause")
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 1_000L },
            idFactory = sequenceOf("context-pause", "task-pause").iterator()::next,
        )
        val first = requireNotNull(
            handler.sendMessage(request("pause-1", null, "rotate the key"), stored).task,
        )
        assertEquals(A2ATaskState.INPUT_REQUIRED, first.status.state)
        val remoteStatus = first.status.message?.parts?.single()?.text.orEmpty()
        assertEquals("需要设备所有者提供输入。", remoteStatus)
        listOf("Which key should be rotated?", "prod", "staging").forEach { secret ->
            assertFalse(remoteStatus.contains(secret))
        }
        assertTrue(first.artifacts.isEmpty())
        assertEquals(1, requests.size)
        assertEquals(stored.allowedTools, requests.single().access.allowedTools)
        handler.close()

        val restarted = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 2_000L },
        )
        assertEquals(1, restarted.recoverPendingTasks())
        assertEquals(A2ATaskState.INPUT_REQUIRED, restarted.getTask("task-pause").status.state)
        assertEquals("recovery must not replay the paused turn", 1, requests.size)

        val continuation = continuation(
            messageId = "pause-2",
            taskId = "task-pause",
            contextId = "context-pause",
            text = "prod",
        )
        val resumed = requireNotNull(restarted.sendMessage(continuation, stored).task)
        assertEquals("task-pause", resumed.id)
        assertEquals("context-pause", resumed.contextId)
        assertEquals(A2ATaskState.COMPLETED, resumed.status.state)
        assertEquals(2, requests.size)
        assertEquals("prod", requests[1].prompt)
        assertTrue(requests[1].continuation)
        assertEquals(stored.allowedTools, requests[1].access.allowedTools)

        val retry = requireNotNull(restarted.sendMessage(continuation, stored).task)
        assertEquals(A2ATaskState.COMPLETED, retry.status.state)
        assertEquals("duplicate continuation must not replay", 2, requests.size)
        restarted.close()
    }

    @Test
    fun `approval pause stores a bounded local summary and keeps it off the wire`() = runBlocking {
        val readDigest = "b".repeat(64)
        val pendingDigest = "c".repeat(64)
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return if (requests.size == 1) {
                    A2AInboundExecutionOutcome.AuthRequired(
                        "The operation needs approval from the device owner before it can run.",
                        authPause(
                            tool = "write_file",
                            digest = pendingDigest,
                            settledCalls = listOf("read_file|READ|$readDigest"),
                        ),
                    )
                } else {
                    A2AInboundExecutionOutcome.Completed("approved and done")
                }
            }
        }
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 5_000L },
            idFactory = sequenceOf("context-summary", "task-summary").iterator()::next,
        )
        val stored = A2AToolAccess(
            peerId = "peer-summary",
            tier = A2AToolTier.RESTRICTED,
            allowedTools = setOf("write_file", "read_file"),
        )
        val first = requireNotNull(
            handler.sendMessage(request("summary-1", null, "secret prompt text"), stored).task,
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)

        val row = requireNotNull(database.dao().inboundTask("task-summary"))
        val json = requireNotNull(row.approvalSummaryJson)
        val slot = database.dao().inboundContinuations("task-summary").single().messageId
        assertEquals(json.length, row.approvalSummaryChars)
        assertTrue(row.approvalSummaryChars in 1..A2AInboundApprovalSummary.MAX_CHARS)
        val summary = requireNotNull(
            A2AInboundApprovalSummary.decode(json, row.approvalSummaryDigest, row.approvalSummaryChars, slot),
        )
        assertEquals("write_file", summary.tool)
        assertEquals("REVERSIBLE_WRITE", summary.effect)
        assertEquals(pendingDigest, summary.argumentsDigest)
        assertEquals(listOf("read_file|READ|$readDigest"), summary.settledCalls)
        // The ledger keeps the pending call identity only, never transcript or argument values.
        assertFalse(json.contains("secret prompt text"))
        assertFalse(json.contains("approved and done"))

        val remote = handler.getTask("task-summary")
        val remoteText = buildString {
            remote.history.forEach { message -> message.parts.forEach { append(it.text.orEmpty()) } }
            remote.artifacts.forEach { artifact -> artifact.parts.forEach { append(it.text.orEmpty()) } }
            remote.status.message?.parts?.forEach { append(it.text.orEmpty()) }
        }
        listOf("write_file", "read_file", pendingDigest, "REVERSIBLE_WRITE", slot).forEach { secret ->
            assertFalse("the remote task must not expose $secret", remoteText.contains(secret))
        }

        assertTrue(handler.decideAuthorization("task-summary", approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.COMPLETED, handler.getTask("task-summary").status.state)
        val resumed = requests[1]
        assertEquals(listOf("read_file|READ|$readDigest"), resumed.settledCalls)
        val grant = requireNotNull(resumed.localApproval)
        assertEquals("write_file", grant.pendingTool)
        assertEquals("REVERSIBLE_WRITE", grant.pendingEffect)
        assertEquals(pendingDigest, grant.pendingArgumentsDigest)
        handler.close()
    }

    @Test
    fun `a second pause in one task gets a new approval slot`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return when (requests.size) {
                    1 -> A2AInboundExecutionOutcome.AuthRequired(
                        "pause one",
                        authPause(tool = "write_file", digest = "c".repeat(64)),
                    )
                    2 -> A2AInboundExecutionOutcome.AuthRequired(
                        "pause two",
                        authPause(tool = "read_file", digest = "d".repeat(64)),
                    )
                    else -> A2AInboundExecutionOutcome.Completed("done twice")
                }
            }
        }
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 5_000L },
            idFactory = sequenceOf("context-two", "task-two").iterator()::next,
        )
        val stored = A2AToolAccess(
            peerId = "peer-two",
            tier = A2AToolTier.RESTRICTED,
            allowedTools = setOf("write_file", "read_file"),
        )
        val first = requireNotNull(handler.sendMessage(request("two-1", null, "first pause"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)
        val firstSlot = database.dao().inboundContinuations("task-two").single().messageId
        val firstJson = requireNotNull(database.dao().inboundTask("task-two")).approvalSummaryJson

        assertTrue(handler.decideAuthorization("task-two", approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.AUTH_REQUIRED, handler.getTask("task-two").status.state)
        val secondSlot = database.dao().inboundContinuations("task-two")
            .single { it.outcome == "AUTH_REQUIRED_PENDING" }
            .messageId
        val secondJson = requireNotNull(database.dao().inboundTask("task-two")).approvalSummaryJson
        assertNotEquals(firstSlot, secondSlot)
        assertNotEquals(firstJson, secondJson)
        assertEquals(2, requests.size)

        assertTrue(handler.decideAuthorization("task-two", approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.COMPLETED, handler.getTask("task-two").status.state)
        assertEquals(3, requests.size)
        assertEquals("read_file", requests[2].localApproval?.pendingTool)
        assertEquals("d".repeat(64), requests[2].localApproval?.pendingArgumentsDigest)
        handler.close()
    }

    @Test
    fun `pause after an effectful settled call fails closed`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val executor = object : A2AInboundExecutor {
            override suspend fun execute(request: A2AInboundExecutionRequest): String =
                error("executeOutcome must be used")

            override suspend fun executeOutcome(
                request: A2AInboundExecutionRequest,
            ): A2AInboundExecutionOutcome {
                requests += request
                return A2AInboundExecutionOutcome.AuthRequired(
                    "approval requested after a completed write",
                    authPause(
                        settledCalls = listOf("write_file|REVERSIBLE_WRITE|${"e".repeat(64)}"),
                    ),
                )
            }
        }
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = executor,
            clock = { 5_000L },
            idFactory = sequenceOf("context-effectful", "task-effectful").iterator()::next,
        )
        val stored = A2AToolAccess("peer-effectful", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("effectful-1", null, "write then pause"), stored).task)
        assertEquals(A2ATaskState.FAILED, first.status.state)
        val status = first.status.message?.parts?.single()?.text.orEmpty()
        assertTrue(status, status.contains("无法安全续跑"))
        assertEquals(1, requests.size)
        assertTrue(database.dao().inboundContinuations("task-effectful").isEmpty())
        assertFalse(handler.decideAuthorization("task-effectful", approve = true, currentAccess = stored))
        assertEquals(1, requests.size)
        handler.close()
    }

    @Test
    fun `damaged approval summary forbids the resume`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-damaged", "task-damaged").iterator()::next,
        )
        val stored = A2AToolAccess("peer-damaged", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("damaged-1", null, "write"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)
        val row = requireNotNull(database.dao().inboundTask("task-damaged"))
        database.dao().saveInboundApprovalSummary(
            taskId = "task-damaged",
            json = row.approvalSummaryJson,
            digest = "0".repeat(64),
            chars = row.approvalSummaryChars,
            updatedAt = 6_000L,
        )

        assertFalse(handler.decideAuthorization("task-damaged", approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.FAILED, handler.getTask("task-damaged").status.state)
        assertEquals(1, requests.size)
        handler.close()
    }

    @Test
    fun `approval resumes within the stored grant even when the local grant is wider`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val handler = PersistentA2AInboundTaskHandler(
            dao = database.dao(),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-wider", "task-wider").iterator()::next,
        )
        val stored = A2AToolAccess("peer-wider", A2AToolTier.RESTRICTED, setOf("write_file", "read_file"))
        val first = requireNotNull(handler.sendMessage(request("wider-1", null, "write the report"), stored).task)
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)

        val wider = A2AToolAccess(
            "peer-wider",
            A2AToolTier.RESTRICTED,
            stored.allowedTools + "terminal",
        )
        assertTrue(handler.decideAuthorization("task-wider", approve = true, currentAccess = wider))
        assertEquals(A2ATaskState.COMPLETED, handler.getTask("task-wider").status.state)
        assertEquals(2, requests.size)
        // Execution never widens beyond the task's original grant...
        assertEquals(stored.allowedTools, requests[1].access.allowedTools)
        // ...and the exact pending call is still released once.
        val grant = requireNotNull(requests[1].localApproval)
        assertEquals("write_file", grant.pendingTool)
        assertEquals("a".repeat(64), grant.pendingArgumentsDigest)
        handler.close()
    }

    @Test
    fun `cancel that wins before the working claim never starts runtime`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val real = database.dao()
        val taskId = "task-claim-race"
        val handler = PersistentA2AInboundTaskHandler(
            dao = CancelBeforeWorkingClaim(delegate = real, targetTaskId = taskId),
            executor = authFirstExecutor(requests),
            clock = { 5_000L },
            idFactory = sequenceOf("context-claim-race", taskId).iterator()::next,
        )
        val stored = A2AToolAccess("peer-claim-race", A2AToolTier.RESTRICTED, setOf("write_file"))
        val first = requireNotNull(
            handler.sendMessage(request("claim-race-1", null, "write the report"), stored).task,
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, first.status.state)
        assertEquals(1, requests.size)

        // The approval transaction has already written WORKING when the remote cancel races in.
        // The cancel wins the claim, so the approved turn must never reach the Runtime.
        assertTrue(handler.decideAuthorization(taskId, approve = true, currentAccess = stored))
        assertEquals(A2ATaskState.CANCELED, handler.getTask(taskId).status.state)
        assertEquals("the canceled turn must not start runtime", 1, requests.size)
        handler.close()
    }

    /**
     * Delegates every DAO call to the real Room DAO. The second WORKING claim for [targetTaskId]
     * is preceded by a remote cancel, which reproduces the cancel that lands between the approval
     * transaction and the Runtime dispatch (the first claim belongs to the pausing turn).
     */
    private class CancelBeforeWorkingClaim(
        private val delegate: A2ADao,
        private val targetTaskId: String,
    ) : A2ADao by delegate {
        private var claims = 0

        override suspend fun claimInboundTaskWorking(taskId: String, updatedAt: Long): Int {
            // The first claim belongs to the turn that pauses for authorization; the cancel has
            // to land on the resume that follows the owner approval.
            if (taskId == targetTaskId && ++claims == 2) {
                delegate.cancelInboundTaskIfActive(
                    taskId = taskId,
                    statusMessage = "Canceled by the remote peer",
                    updatedAt = updatedAt,
                )
            }
            return delegate.claimInboundTaskWorking(taskId, updatedAt)
        }
    }

    @Test
    fun `peer continuation racing a runtime pause never starts runtime`() = runBlocking {
        val requests = mutableListOf<A2AInboundExecutionRequest>()
        val real = database.dao()
        val taskId = "task-pause-race"
        val handler = PersistentA2AInboundTaskHandler(
            dao = PauseBeforeContinuationClaim(delegate = real, targetTaskId = taskId),
            executor = object : A2AInboundExecutor {
                override suspend fun execute(request: A2AInboundExecutionRequest): String =
                    error("executeOutcome must be used")

                override suspend fun executeOutcome(
                    request: A2AInboundExecutionRequest,
                ): A2AInboundExecutionOutcome {
                    requests += request
                    return A2AInboundExecutionOutcome.InputRequired("Which report?")
                }
            },
            clock = { 5_000L },
            idFactory = sequenceOf("context-pause-race", taskId).iterator()::next,
        )
        val stored = A2AToolAccess.readOnly("peer-pause-race")
        val first = requireNotNull(
            handler.sendMessage(request("pause-race-1", null, "open the report"), stored).task,
        )
        assertEquals(A2ATaskState.INPUT_REQUIRED, first.status.state)
        assertEquals(1, requests.size)

        // The coordinator read INPUT_REQUIRED, then the runtime pause won the row first. The peer
        // continuation must not claim AUTH_REQUIRED and resume the paused call without approval.
        val failure = runCatching {
            handler.sendMessage(
                continuation("pause-race-2", taskId, "context-pause-race", "the weekly one"),
                stored,
            )
        }.exceptionOrNull()
        assertTrue("expected the continuation to be refused, got $failure", failure is A2AProtocolException)
        assertEquals(A2ATaskState.AUTH_REQUIRED, handler.getTask(taskId).status.state)
        assertEquals("the racing continuation must not start runtime", 1, requests.size)
        val task = requireNotNull(real.inboundTask(taskId))
        assertTrue(task.continuationMessageId.isNullOrBlank())
        assertTrue(real.inboundContinuations(taskId).isEmpty())
        handler.close()
    }

    /**
     * Delegates every DAO call to the real Room DAO. The first peer continuation claim is preceded
     * by the runtime pause winning the row, which reproduces a continuation that races the pause
     * after the coordinator already read INPUT_REQUIRED.
     */
    private class PauseBeforeContinuationClaim(
        private val delegate: A2ADao,
        private val targetTaskId: String,
    ) : A2ADao by delegate {
        override suspend fun acceptInboundContinuation(
            continuation: A2AInboundContinuationEntity,
            updatedAt: Long,
        ): Boolean {
            if (continuation.taskId == targetTaskId) {
                delegate.transitionInboundTask(
                    taskId = targetTaskId,
                    expectedState = A2ATaskState.INPUT_REQUIRED.name,
                    newState = A2ATaskState.AUTH_REQUIRED.name,
                    statusMessage = "Authorization required",
                    answer = null,
                    updatedAt = updatedAt,
                )
            }
            return delegate.acceptInboundContinuation(continuation, updatedAt)
        }
    }

    private fun request(messageId: String, contextId: String?, prompt: String) =
        A2ASendMessageRequest(
            message = A2AMessage(
                messageId = messageId,
                role = A2ARole.USER,
                contextId = contextId,
                parts = listOf(A2APart(text = prompt, mediaType = "text/plain")),
            ),
        )

    private fun continuation(
        messageId: String,
        taskId: String,
        contextId: String,
        text: String,
        requestedTools: List<String>? = null,
    ) = A2ASendMessageRequest(
        message = A2AMessage(
            messageId = messageId,
            role = A2ARole.USER,
            contextId = contextId,
            taskId = taskId,
            parts = listOf(A2APart(text = text, mediaType = "text/plain")),
            metadata = requestedTools?.let { tools ->
                buildJsonObject {
                    put("nexus.requestedTools", JsonArray(tools.map(::JsonPrimitive)))
                }
            },
        ),
    )

    private suspend fun awaitInboundState(
        dao: A2ADao,
        taskIds: Set<String>,
        state: A2ATaskState,
    ) {
        repeat(200) {
            if (taskIds.all { dao.inboundTask(it)?.state == state.name }) return
            delay(25)
        }
        assertTrue("Tasks did not reach $state", taskIds.all { dao.inboundTask(it)?.state == state.name })
    }
}
