package io.github.mangi.eta.fork.a2a

import android.app.Application
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AAgentRepositoryCompatibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun isolateDatabase() = A2ADatabase.closeForTests()
    @After fun closeDatabase() = A2ADatabase.closeForTests()

    @Test fun streamingReceiptIsDurableBeforeCompletionAndTheStreamIsKeptOpen() = runBlocking {
        val release = CountDownLatch(1)
        val receipt = CountDownLatch(1)
        val sends = AtomicInteger()
        val reads = AtomicInteger()
        val server = server().apply {
            createContext("/a2a") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().readText())
                val id = request.getString("id")
                if (request.getString("method") == "GetTask") {
                    reads.incrementAndGet()
                    val body = envelope(id, task("TASK_STATE_COMPLETED", "final answer")).toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                } else {
                    sends.incrementAndGet()
                    assertEquals("SendStreamingMessage", request.getString("method"))
                    exchange.responseHeaders.set("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { output ->
                        output.write("data: ${envelope(id, JSONObject().put("task", task("TASK_STATE_SUBMITTED")))}\n\n".toByteArray())
                        output.flush()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        output.write("data: ${envelope(id, JSONObject().put("statusUpdate", JSONObject()
                            .put("taskId", "remote-task").put("contextId", "remote-context")
                            .put("status", JSONObject().put("state", "TASK_STATE_COMPLETED"))))}\n\n".toByteArray())
                        output.flush()
                    }
                }
            }
            start()
        }
        try {
            val dao = A2ADatabase.get(context).dao()
            val peer = peer(server.address.port)
            dao.upsertAgent(peer)
            A2AHttpClient(a2aPeerPolicyForManualEndpoint(peer.endpointUrl)).use { client ->
                val repository = A2AAgentRepository(context, client, dao)
                val pending = async(Dispatchers.IO) {
                    repository.sendMessage(peer.id, message(), "local-task", returnImmediately = true,
                        preventReplay = true, onTaskReceived = { receipt.countDown() })
                }
                try {
                    assertTrue(receipt.await(3, TimeUnit.SECONDS))
                    assertEquals("remote-task", dao.task("local-task")!!.remoteTaskId)
                    assertFalse("Do not disconnect Hermes after its initial receipt", pending.isCompleted)
                    release.countDown()
                    val result = withTimeout(3000) { pending.await() }
                    assertEquals("final answer", result.task!!.resultText())
                    assertEquals(1, sends.get())
                    assertEquals(1, reads.get())
                } finally { release.countDown(); pending.cancel() }
            }
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun onlyExplicitUnsupportedMethodAllowsANonStreamingFallback() = runBlocking {
        for (unsupported in listOf(true, false)) {
            val sends = AtomicInteger()
            val server = server().apply {
                createContext("/a2a") { exchange ->
                    sends.incrementAndGet()
                    val request = JSONObject(exchange.requestBody.bufferedReader().readText())
                    val result = if (request.getString("method") == "SendMessage") {
                        envelope(request.getString("id"), JSONObject().put("task", task("TASK_STATE_COMPLETED")))
                    } else if (unsupported) {
                        JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id"))
                            .put("error", JSONObject().put("code", -32601).put("message", "Method not found")).toString()
                    } else "invalid response"
                    val body = result.toByteArray()
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
            try {
                val dao = A2ADatabase.get(context).dao()
                val peer = peer(server.address.port).copy(id = "peer-$unsupported")
                dao.upsertAgent(peer)
                A2AHttpClient(a2aPeerPolicyForManualEndpoint(peer.endpointUrl)).use { client ->
                    val result = runCatching {
                        A2AAgentRepository(context, client, dao).sendMessage(peer.id, message(), "local-$unsupported", true, true)
                    }
                    assertEquals(unsupported, result.isSuccess)
                    assertEquals(if (unsupported) 2 else 1, sends.get())
                    if (!unsupported) assertEquals("SEND_UNCONFIRMED", dao.task("local-false")!!.errorCode)
                }
            } finally { server.shutdown() }
        }
    }

    @Test fun explicitEndpointOverrideSurvivesCardRefresh() = runBlocking {
        var declaredEndpoint = "http://100.110.70.49:9900/"
        val server = server().apply {
            createContext("/card") { exchange ->
                val body = """{"name":"Hermes fixture","description":"fixture","version":"1.0","supportedInterfaces":[{"url":"$declaredEndpoint","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val cardUrl = "http://127.0.0.1:${server.address.port}/card"
            val endpoint = "http://127.0.0.1:${server.address.port}/a2a"
            A2AHttpClient(a2aPeerPolicyForManualEndpoint(cardUrl)).use { client ->
                val repository = A2AAgentRepository(context, client)
                assertTrue(runCatching { repository.discoverAndAddAgent("", cardUrl) }.isFailure)
                val added = repository.discoverAndAddAgent("", cardUrl, endpointOverride = endpoint)
                assertEquals(endpoint, added.endpointUrl)
                declaredEndpoint = "http://100.110.70.50:9900/"
                assertEquals(endpoint, repository.refreshAgentCard(added.id).endpointUrl)
            }
        } finally { server.shutdown() }
    }

    @Test fun editingExistingPairingKeepsItsTaskOwnershipAndCredentialWithoutAllowingANewHost() = runBlocking {
        val dao = A2ADatabase.get(context).dao()
        val secrets = A2ASecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
        val alias = secrets.save("fixture-existing-credential", "fixture-secret")
        val saved = peer(9900).copy(
            id = "existing-pairing",
            cardUrl = "http://127.0.0.1:9900/card",
            endpointUrl = "http://100.110.70.49:9900/",
            authSecretAlias = alias,
            enabled = false,
            cardJson = """{"name":"Hermes fixture","description":"fixture","version":"1.0","supportedInterfaces":[{"url":"http://100.110.70.49:9900/","protocolBinding":"JSONRPC","protocolVersion":"1.0"}],"defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}""",
        )
        dao.upsertAgent(saved)
        dao.upsertTask(A2ATaskEntity("kept-local-task", saved.id, "kept-remote-task", "kept-context", "COMPLETED",
            null, false, null, null, null, 1, 1))
        A2AHttpClient(a2aPeerPolicyForManualEndpoint(saved.cardUrl)).use { client ->
            val repository = A2AAgentRepository(context, client, dao, secrets)
            val edited = repository.updateAgentConnection(saved.id, "Repaired Hermes", "http://127.0.0.1:9900/", bearerToken = "")
            assertEquals(saved.id, edited.id)
            assertEquals("http://127.0.0.1:9900/", edited.endpointUrl)
            assertEquals(alias, edited.authSecretAlias)
            assertEquals("fixture-secret", secrets.load(edited.authSecretAlias))
            assertFalse(edited.enabled)
            assertEquals(saved.id, dao.task("kept-local-task")!!.agentId)
            assertEquals(saved.id, dao.observeAgents().first().single().id)
            assertTrue(runCatching {
                repository.updateAgentConnection(saved.id, "wrong host", "http://127.0.0.2:9900/", bearerToken = "must-not-save")
            }.isFailure)
            assertEquals("fixture-secret", secrets.load(alias))
            assertEquals("http://127.0.0.1:9900/", dao.agent(saved.id)!!.endpointUrl)
        }
    }

    private fun message() = A2AMessage("message", A2ARole.USER, listOf(A2APart(text = "fixture request")))
    private fun task(state: String, text: String? = null) = JSONObject().put("id", "remote-task")
        .put("contextId", "remote-context").put("status", JSONObject().put("state", state).apply {
            if (text != null) put("message", JSONObject().put("messageId", "answer").put("role", "ROLE_AGENT")
                .put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))
        })
    private fun envelope(id: String, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
    private fun peer(port: Int) = A2AAgentEntity("peer", "Hermes fixture", "http://127.0.0.1:$port/card",
        "http://127.0.0.1:$port/a2a", null, A2A_PROTOCOL_VERSION, null, "MANUAL_ALLOWLIST",
        """{"capabilities":{"streaming":true}}""", true, 1, 1)
    private fun server() = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    }
    private fun HttpServer.shutdown() { stop(0); (executor as ExecutorService).shutdownNow() }
}
