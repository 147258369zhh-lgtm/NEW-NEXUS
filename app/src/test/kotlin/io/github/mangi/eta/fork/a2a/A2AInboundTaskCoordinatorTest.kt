package io.github.mangi.eta.fork.a2a

import android.app.Application
import androidx.room.Room
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertEquals("model unavailable", task.status.message?.parts?.single()?.text)
        assertTrue(task.artifacts.isEmpty())
    }

    @Test
    fun `pending tasks are recovered after process restart`() = runBlocking {
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
        awaitInboundState(dao, setOf("recover-submitted", "recover-working"), A2ATaskState.COMPLETED)
        assertEquals(setOf("recover-submitted", "recover-working"), executed)
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

    private fun request(messageId: String, contextId: String?, prompt: String) =
        A2ASendMessageRequest(
            message = A2AMessage(
                messageId = messageId,
                role = A2ARole.USER,
                contextId = contextId,
                parts = listOf(A2APart(text = prompt, mediaType = "text/plain")),
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
