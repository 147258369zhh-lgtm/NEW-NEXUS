package io.github.mangi.eta.fork.meeting

import android.app.Application
import android.content.ComponentName
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.fork.a2a.*
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MeetingRemoteCancellationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        Prefs.initLocal(context)
        shadowOf(context).declareComponentUnbindable(ComponentName(context, "androidx.room.MultiInstanceInvalidationService"))
        MeetingDatabase.closeForTests(); A2ADatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db"); context.deleteDatabase("eta_a2a.db")
    }
    @After fun close() { MeetingDatabase.closeForTests(); A2ADatabase.closeForTests() }

    @Test fun stoppingMeetingRequestsRemoteCancellationAndKeepsItsTaskIdentity() = runBlocking(Dispatchers.IO) {
        val submitted = CompletableDeferred<Unit>()
        val cancelled = AtomicInteger()
        val sends = AtomicInteger()
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/a2a") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                val method = request.getString("method")
                val task = JSONObject().put("id", "remote-work").put("contextId", "remote-context")
                    .put("status", JSONObject().put("state", if (method == "CancelTask") "TASK_STATE_CANCELED" else "TASK_STATE_WORKING"))
                val result = if (method == "SendMessage") {
                    sends.incrementAndGet()
                    assertTrue(request.getJSONObject("params").getJSONObject("configuration").getBoolean("returnImmediately"))
                    JSONObject().put("task", task)
                } else {
                    assertEquals("remote-work", request.getJSONObject("params").getString("id"))
                    if (method == "CancelTask") cancelled.incrementAndGet()
                    task
                }
                val body = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id")).put("result", result).toString().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                if (method == "SendMessage") submitted.complete(Unit)
            }
            start()
        }
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/a2a"
            val dao = A2ADatabase.get(context).dao()
            dao.upsertAgent(A2AAgentEntity("remote", "Remote", "$endpoint/card", endpoint, null,
                A2A_PROTOCOL_VERSION, null, "manual", "{}", true, 1, 1))
            val room = MeetingRepository.createRoom(context, "Remote cancellation",
                MeetingParticipantDraft("general", "Host", "host"),
                listOf(MeetingParticipantDraft("remote_worker", "Remote", "work", providerId = "a2a:remote")))
            val run = launch { MeetingRuntime.runTurn(context, room, "@remote_worker 执行任务") }
            try {
                withTimeout(5_000) {
                    submitted.await()
                    while (dao.observeTasks().first().none { it.remoteTaskId == "remote-work" }) delay(10)
                }
                withTimeout(7_000) { run.cancelAndJoin() }
                assertEquals(1, sends.get())
                assertEquals(1, cancelled.get())
                val persisted = dao.observeTasks().first().single()
                assertEquals("remote-work", persisted.remoteTaskId)
                assertNotNull(persisted.cancelRequestedAt)
                assertFalse(persisted.cancelUnconfirmed)
            } finally { run.cancelAndJoin() }
        } finally { server.stop(0); pool.shutdownNow() }
    }
}
