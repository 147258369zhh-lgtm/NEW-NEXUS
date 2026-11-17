package io.github.mangi.eta.agent.capability

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.fork.a2a.*
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2ARunHostsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun isolateDatabase() = A2ADatabase.closeForTests()
    @After fun closeDatabase() = A2ADatabase.closeForTests()

    @Test fun savedPeerIsDiscoverableAndUsesExistingTasksWithDuplicateAndRevocationProtection() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a2a") { exchange ->
                calls.incrementAndGet()
                val request = JSONObject(exchange.requestBody.bufferedReader().readText())
                val method = request.getString("method")
                val task = JSONObject().put("id", "remote-task").put("status", JSONObject()
                    .put("state", if (method == "CancelTask") "TASK_STATE_CANCELED" else "TASK_STATE_COMPLETED"))
                    .put("artifacts", JSONArray().put(JSONObject().put("artifactId", "report").put("name", "Report")
                        .put("parts", JSONArray().put(JSONObject().put("text", "done")))))
                val result = if (method == "SendMessage") JSONObject().put("task", task) else task
                val body = JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id")).put("result", result).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val peer = peer("peer", server.address.port)
            val dao = A2ADatabase.get(context).dao()
            dao.upsertAgent(peer)
            val snapshot = A2ARunSnapshot.load(context)
            val schemas = JSONArray().also(snapshot::appendModelTools)
            assertEquals(3, schemas.length())
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context)).use { hosts ->
                val send = A2ARunSnapshot.toolName(peer.id, "send")
                val status = A2ARunSnapshot.toolName(peer.id, "status")
                val cancel = A2ARunSnapshot.toolName(peer.id, "cancel")
                assertEquals(HostKind.A2A, hosts.describe(send)!!.hostKind)
                assertEquals(EffectClass.HIGH_RISK, hosts.describe(send)!!.effectClass)
                assertEquals(EffectClass.READ, hosts.describe(status)!!.effectClass)
                assertTrue(hosts.list("remote").any { it.id == send })
                val call = AgentModelClient.ToolCall("call-1", send, """{"message":"make report"}""")
                val sent = JSONObject(hosts.execute(call).content)
                assertTrue(sent.toString(), sent.getBoolean("ok"))
                val localId = sent.getString("local_task_id")
                assertEquals("done", sent.getString("text"))
                assertEquals("remote-task", dao.task(localId)!!.remoteTaskId)
                assertEquals("PENDING", dao.observeArtifacts(localId).first().single().quarantineState)
                assertTrue(JSONObject(hosts.execute(call).content).getBoolean("already_registered"))
                assertEquals(1, calls.get())
                val args = JSONObject().put("local_task_id", localId).toString()
                assertEquals("COMPLETED", JSONObject(hosts.execute(AgentModelClient.ToolCall("read", status, args)).content).getString("state"))
                assertEquals("CANCELED", JSONObject(hosts.execute(AgentModelClient.ToolCall("cancel", cancel, args)).content).getString("state"))
                assertEquals(3, calls.get())
                dao.setAgentEnabled(peer.id, false, System.currentTimeMillis())
                assertFalse(hosts.isAvailable(send))
                assertEquals("HOST_TOOL_UNAVAILABLE", JSONObject(hosts.execute(AgentModelClient.ToolCall("later", send, """{"message":"again"}""")).content).getString("code"))
                assertEquals(3, calls.get())
            }
        } finally { server.stop(0) }
    }

    @Test fun missingReceiptIsRetainedAndNotResentAndOtherPeersTasksCannotBeRead() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a2a") { exchange ->
                calls.incrementAndGet()
                val body = "invalid".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val dao = A2ADatabase.get(context).dao()
            val first = peer("first", server.address.port)
            val second = peer("second", server.address.port)
            dao.upsertAgent(first); dao.upsertAgent(second)
            val snapshot = A2ARunSnapshot.load(context)
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context)).use { hosts ->
                val call = AgentModelClient.ToolCall("uncertain", A2ARunSnapshot.toolName(first.id, "send"), """{"message":"do work"}""")
                val initial = JSONObject(hosts.execute(call).content)
                assertFalse(initial.getBoolean("ok"))
                assertTrue(initial.toString(), initial.has("local_task_id"))
                val localId = initial.getString("local_task_id")
                assertEquals("SEND_UNCONFIRMED", dao.task(localId)!!.errorCode)
                assertTrue(JSONObject(hosts.execute(call).content).getBoolean("unconfirmed"))
                val args = JSONObject().put("local_task_id", localId).toString()
                val other = hosts.execute(AgentModelClient.ToolCall("cross-peer", A2ARunSnapshot.toolName(second.id, "status"), args))
                assertFalse(JSONObject(other.content).getBoolean("ok"))
                assertEquals(1, calls.get())
            }
        } finally { server.stop(0) }
    }

    @Test fun interruptedTaskCanBeContinuedWithItsSavedContextWithoutReplayingInput() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/a2a") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().readText())
                val nth = calls.incrementAndGet()
                val message = request.getJSONObject("params").getJSONObject("message")
                if (nth == 2) {
                    assertEquals("remote-task", message.getString("taskId"))
                    assertEquals("saved-context", message.getString("contextId"))
                }
                val task = JSONObject().put("id", "remote-task").put("contextId", "saved-context")
                    .put("status", JSONObject().put("state", if (nth == 1) "TASK_STATE_INPUT_REQUIRED" else "TASK_STATE_COMPLETED")
                        .put("message", JSONObject().put("messageId", "answer-$nth").put("role", "ROLE_AGENT")
                            .put("parts", JSONArray().put(JSONObject().put("text", if (nth == 1) "which project?" else "continued answer")))))
                val body = JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
                    .put("result", JSONObject().put("task", task)).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val peer = peer("continuation", server.address.port)
            A2ADatabase.get(context).dao().upsertAgent(peer)
            val snapshot = A2ARunSnapshot.load(context)
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context)).use { hosts ->
                val send = A2ARunSnapshot.toolName(peer.id, "send")
                val first = JSONObject(hosts.execute(AgentModelClient.ToolCall("initial", send, """{"message":"build it"}""")).content)
                assertEquals("INPUT_REQUIRED", first.getString("state"))
                val args = JSONObject().put("message", "the reader").put("local_task_id", first.getString("local_task_id")).toString()
                val followup = AgentModelClient.ToolCall("followup", send, args)
                val second = JSONObject(hosts.execute(followup).content)
                assertEquals("continued answer", second.getString("text"))
                assertEquals(first.getString("local_task_id"), second.getString("local_task_id"))
                assertTrue(JSONObject(hosts.execute(followup).content).getBoolean("already_registered"))
                assertEquals(2, calls.get())
            }
        } finally { server.stop(0) }
    }

    private fun peer(id: String, port: Int) = A2AAgentEntity(id, "Remote $id", "http://127.0.0.1:$port/card/$id",
        "http://127.0.0.1:$port/a2a", null, A2A_PROTOCOL_VERSION, null, "MANUAL_ALLOWLIST",
        """{"description":"Remote reports"}""", true, 1, 1)
}
