package io.github.mangi.eta.agent.capability

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusRunHostsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun native() = InMemoryCapabilityDirectory(emptyList())
    private fun wire() = AgentRuntimeWire.RunRequest("host-run", "fixture", AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid", apiKey = "", model = "fixture", systemPrompt = ""), emptyList())

    @Test fun actualApiBindingUsesOneDescriptorForSearchActivationPermissionExecutionAndRevocation() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/read") { exchange ->
                calls.incrementAndGet()
                val body = "{\"value\":42}".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val action = ApiConnectorAction("read", "Read report", ApiHttpMethod.GET, "/read",
                """{"type":"object","properties":{},"additionalProperties":false}""", enabled = true, destructive = false)
            val connector = ApiConnectorDefinition("host-test", "Reports", baseUrl = "http://127.0.0.1:${server.address.port}", actions = listOf(action))
            ApiConnectorStore(context).upsert(connector)
            val name = ApiConnectorSnapshot.modelToolName(connector.id, action.name)
            val snapshot = ApiConnectorSnapshot(listOf(ApiConnectorRunTool(name, connector, action)))
            NexusRunHosts(native(), snapshot.bindings(context)).use { hosts ->
                val descriptor = hosts.describe(name)!!
                assertEquals(EffectClass.READ, descriptor.effectClass)
                assertEquals(HostKind.CONNECTOR, descriptor.hostKind)
                val lazy = NexusLazyToolCatalog(hosts, toolAllowed = hosts::isAvailable, discoveryAllowed = { true },
                    additionalSchemas = JSONArray().also(snapshot::appendModelTools))
                val found = lazy.execute("tool_search", JSONObject().put("query", "Reports"))!!
                assertTrue(found.toString().contains(name))
                assertEquals("READ", lazy.execute("tool_describe", JSONObject().put("tool_id", name))!!.getString("effect"))
                assertTrue(lazy.execute("tool_activate", JSONObject().put("tool_id", name))!!.getBoolean("ok"))
                assertEquals(1, lazy.localSchemas(JSONArray()).length())
                val decision = NexusExecutionGate.authorize(context, wire(), name, JSONObject(), AgentRunController(),
                    System.currentTimeMillis() + 60_000, inputSchema = hosts.schema(name), toolDescriptor = descriptor, toolHostId = hosts.hostId(name))
                assertEquals(CapabilityAuthorization.Allowed, decision)
                assertNull(EtaAppControlConfirmation.pending.value)
                assertTrue(JSONObject(hosts.execute(AgentModelClient.ToolCall("read", name, "{}")).content).getBoolean("ok"))
                assertEquals(1, calls.get())
                assertEquals(2, hosts.snapshot().size)
                ApiConnectorService(context).setActionEnabled(connector.id, action.name, false)
                assertEquals(0, lazy.localSchemas(JSONArray()).length())
                assertEquals("HOST_TOOL_UNAVAILABLE", JSONObject(hosts.execute(AgentModelClient.ToolCall("later", name, "{}")).content).getString("code"))
                assertEquals(1, calls.get())
            }
        } finally { server.stop(0) }
    }

    @Test fun writeDescriptorRequiresLocalConfirmationWithResolvedHostAndKeepsNoSecretsInHostStatus() = runBlocking {
        val action = ApiConnectorAction("publish", "Publish report", ApiHttpMethod.POST, "/publish", "{\"type\":\"object\"}", enabled = true)
        val connector = ApiConnectorDefinition("writer", "Writer", baseUrl = "https://example.com", actions = listOf(action))
        ApiConnectorStore(context).upsert(connector)
        val name = ApiConnectorSnapshot.modelToolName(connector.id, action.name)
        val hosts = NexusRunHosts(native(), ApiConnectorSnapshot(listOf(ApiConnectorRunTool(name, connector, action))).bindings(context))
        try {
            val descriptor = hosts.describe(name)!!
            assertEquals(EffectClass.HIGH_RISK, descriptor.effectClass)
            val work = async(Dispatchers.IO) { NexusExecutionGate.authorize(context, wire(), name, JSONObject(), AgentRunController(),
                System.currentTimeMillis() + 60_000, inputSchema = hosts.schema(name), toolDescriptor = descriptor, toolHostId = hosts.hostId(name)) }
            try {
                val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertEquals(hosts.hostId(name)!!.value, approval.parameters.single { it.first == "host_id" }.second)
                assertTrue(EtaAppControlConfirmation.cancel(approval.id))
                assertEquals("LOCAL_CONFIRMATION_DENIED", (withTimeout(3000) { work.await() } as CapabilityAuthorization.Denied).code)
            } finally { work.cancelAndJoin(); EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) } }
            val controller = AgentRunController().apply { bindHostSnapshot { JSONArray(hosts.snapshot()) } }
            val state = AgentRuntimeWire.ActiveRunSnapshot("host-run", "conversation", hostStatesJson = controller.hostsJson())
            assertEquals(state, AgentRuntimeWire.activeRunsFromBundle(AgentRuntimeWire.activeRunsToBundle(listOf(state))).single())
        } finally { hosts.close() }
        assertTrue(hosts.snapshot().all { it.getString("state") == "STOPPED" })
        assertTrue(hosts.list().isEmpty())
    }

    @Test fun cancellingOneChildHttpCallDoesNotCloseTheSharedHostForTheNextChild() = runBlocking {
        val entered = CountDownLatch(1); val serverRelease = CountDownLatch(1)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/read") { exchange ->
                val first = requests.incrementAndGet() == 1
                if (first) {
                    exchange.sendResponseHeaders(200, 0)
                    try { exchange.responseBody.write('{'.code); exchange.responseBody.flush(); entered.countDown(); serverRelease.await(5, TimeUnit.SECONDS) }
                    finally { exchange.close() }
                } else {
                    val body = "{\"value\":42}".toByteArray(); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
                }
            }
            start()
        }
        val controller = AgentRunController()
        try {
            val action = ApiConnectorAction("read", "Read", ApiHttpMethod.GET, "/read", "{\"type\":\"object\"}", enabled = true, destructive = false)
            val connector = ApiConnectorDefinition("shared-host", "Shared host", baseUrl = "http://127.0.0.1:${server.address.port}", actions = listOf(action))
            ApiConnectorStore(context).upsert(connector)
            val name = ApiConnectorSnapshot.modelToolName(connector.id, action.name)
            NexusRunHosts(native(), ApiConnectorSnapshot(listOf(ApiConnectorRunTool(name, connector, action))).bindings(context)).use { hosts ->
                val task = async(Dispatchers.IO) { RuntimeInvocationScope.withController(controller) { hosts.execute(AgentModelClient.ToolCall("first", name, "{}")) } }
                try {
                    assertTrue(entered.await(3000, TimeUnit.MILLISECONDS)); controller.cancel()
                    assertFalse(JSONObject(withTimeout(3000) { task.await() }.content).getBoolean("ok"))
                    val second = RuntimeInvocationScope.withController(AgentRunController()) { hosts.execute(AgentModelClient.ToolCall("second", name, "{}")) }
                    assertTrue(JSONObject(second.content).getBoolean("ok"))
                    assertEquals(2, requests.get())
                } finally { controller.cancel(); task.cancelAndJoin() }
            }
        } finally { serverRelease.countDown(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    @Test fun hostConcurrencyIsBoundedAndFailuresReleaseTheSlotWithoutLosingHealthDetails() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val id = HostId("bounded-host")
        val tool = "external_fixture"
        val descriptor = ToolDescriptor(tool, "fixture", "Fixture", "Fixture", "fixture://input",
            effectClass = EffectClass.READ, replayPolicy = ReplayPolicy.AUTOMATIC, timeoutMs = 5000, hostKind = HostKind.CONNECTOR)
        val schema = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", tool)
            .put("parameters", JSONObject().put("type", "object"))))
        val provider = object : CapabilityToolProvider {
            override fun contains(toolName: String) = toolName == tool
            override fun close() { release.countDown() }
            override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                calls.incrementAndGet()
                if (toolCall.id == "first") { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
                if (toolCall.id == "fail") throw IllegalStateException("private failure detail")
                return AgentModelClient.ToolResult("{\"ok\":true}")
            }
        }
        NexusRunHosts(native(), listOf(RunHostBinding(id, listOf(descriptor), schema, provider,
            maxConcurrentCalls = 1, displayName = "Fixture host"))).use { hosts ->
            val first = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("first", tool, "{}")) }
            var queued: Deferred<AgentModelClient.ToolResult>? = null
            try {
                assertTrue(entered.await(2000, TimeUnit.MILLISECONDS))
                val state = hosts.snapshot().single { it.getString("hostId") == id.value }
                assertEquals(1, state.getInt("active_calls"))
                assertEquals("Fixture host", state.getString("name"))
                val queuedTask = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("queued", tool, "{}")) }
                queued = queuedTask
                withTimeout(2000) { while (hosts.snapshot().single { it.getString("hostId") == id.value }.getInt("waiting_calls") != 1) delay(10) }
                assertFalse(queuedTask.isCompleted)
                assertEquals(1, calls.get())
                release.countDown(); withTimeout(2000) { first.await() }
                assertTrue(JSONObject(withTimeout(2000) { queuedTask.await() }.content).getBoolean("ok"))
                assertEquals(2, calls.get())
                val failed = hosts.execute(AgentModelClient.ToolCall("fail", tool, "{}"))
                assertEquals("HOST_CALL_FAILED", JSONObject(failed.content).getString("code"))
                val failedState = hosts.snapshot().single { it.getString("hostId") == id.value }
                assertEquals("DEGRADED", failedState.getString("state"))
                assertEquals("HOST_CALL_FAILED", failedState.getString("last_error_code"))
                assertFalse(failedState.toString().contains("private"))
                assertTrue(JSONObject(hosts.execute(AgentModelClient.ToolCall("later", tool, "{}")).content).getBoolean("ok"))
                assertEquals("READY", hosts.snapshot().single { it.getString("hostId") == id.value }.getString("state"))
            } finally { release.countDown(); queued?.cancelAndJoin(); first.cancelAndJoin() }
        }
    }

    @Test fun queuedCallsRespectCancellationRevocationAndAdmissionDeadline() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val available = java.util.concurrent.atomic.AtomicBoolean(true)
        val descriptor = ToolDescriptor("queue_fixture", "fixture", "Fixture", "Fixture", "fixture://input",
            effectClass = EffectClass.READ, replayPolicy = ReplayPolicy.AUTOMATIC, timeoutMs = 300, hostKind = HostKind.CONNECTOR)
        val schema = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", descriptor.id).put("parameters", JSONObject().put("type", "object"))))
        val provider = object : CapabilityToolProvider {
            override fun contains(toolName: String) = toolName == descriptor.id
            override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                calls.incrementAndGet()
                if (toolCall.id == "first") { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                return AgentModelClient.ToolResult("""{"ok":true}""")
            }
            override fun close() { release.countDown() }
        }
        NexusRunHosts(native(), listOf(RunHostBinding(HostId("queued-host"), listOf(descriptor), schema, provider,
            available = { available.get() }, maxConcurrentCalls = 1))).use { hosts ->
            val first = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("first", descriptor.id, "{}")) }
            val controller = AgentRunController()
            var cancelled: Deferred<Throwable?>? = null
            var revoked: Deferred<AgentModelClient.ToolResult>? = null
            try {
                assertTrue(entered.await(2000, TimeUnit.MILLISECONDS))
                val cancelledTask = async(Dispatchers.IO) {
                    runCatching { RuntimeInvocationScope.withController(controller) {
                        hosts.execute(AgentModelClient.ToolCall("cancel", descriptor.id, "{}"))
                    } }.exceptionOrNull()
                }
                cancelled = cancelledTask
                withTimeout(2000) { while (hosts.snapshot().single { it.getString("hostId") == "queued-host" }.getInt("waiting_calls") != 1) delay(5) }
                controller.cancel()
                assertTrue(withTimeout(2000) { cancelledTask.await() } is AgentRunCancelledException)
                assertEquals("HOST_BUSY", JSONObject(hosts.execute(AgentModelClient.ToolCall("timeout", descriptor.id, "{}")).content).getString("code"))
                val revokedTask = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("revoked", descriptor.id, "{}")) }
                revoked = revokedTask
                withTimeout(2000) { while (hosts.snapshot().single { it.getString("hostId") == "queued-host" }.getInt("waiting_calls") != 1) delay(5) }
                available.set(false)
                release.countDown()
                assertEquals("HOST_TOOL_UNAVAILABLE", JSONObject(withTimeout(2000) { revokedTask.await() }.content).getString("code"))
                withTimeout(2000) { first.await() }
                assertEquals(1, calls.get())
                assertEquals(0, hosts.snapshot().single { it.getString("hostId") == "queued-host" }.getInt("active_calls"))
                available.set(true)
                assertTrue(JSONObject(hosts.execute(AgentModelClient.ToolCall("later", descriptor.id, "{}")).content).getBoolean("ok"))
                assertEquals(2, calls.get())
            } finally { controller.cancel(); release.countDown(); cancelled?.cancelAndJoin(); revoked?.cancelAndJoin(); first.cancelAndJoin() }
        }
    }

    @Test fun queuedCallRechecksAuthorizationAfterAdmissionBeforeExecuting() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val permitted = java.util.concurrent.atomic.AtomicBoolean(true)
        val calls = AtomicInteger()
        val descriptor = ToolDescriptor("dispatch_fixture", "fixture", "Fixture", "Fixture", "fixture://input",
            effectClass = EffectClass.HIGH_RISK, replayPolicy = ReplayPolicy.REQUIRE_USER, timeoutMs = 5000, hostKind = HostKind.CONNECTOR)
        val schema = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", descriptor.id).put("parameters", JSONObject().put("type", "object"))))
        val provider = object : CapabilityToolProvider {
            override fun contains(toolName: String) = toolName == descriptor.id
            override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                calls.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS)
                return AgentModelClient.ToolResult("""{"ok":true}""")
            }
            override fun close() { release.countDown() }
        }
        NexusRunHosts(native(), listOf(RunHostBinding(HostId("dispatch-host"), listOf(descriptor), schema, provider,
            maxConcurrentCalls = 1)), beforeDispatch = {
                if (permitted.get()) null else AgentModelClient.ToolResult("""{"ok":false,"code":"REVOKED"}""")
            }).use { hosts ->
            val first = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("first", descriptor.id, "{}")) }
            var queued: Deferred<AgentModelClient.ToolResult>? = null
            try {
                assertTrue(entered.await(2000, TimeUnit.MILLISECONDS))
                val waiting = async(Dispatchers.IO) { hosts.execute(AgentModelClient.ToolCall("waiting", descriptor.id, "{}")) }
                queued = waiting
                withTimeout(2000) { while (hosts.snapshot().single { it.getString("hostId") == "dispatch-host" }.getInt("waiting_calls") != 1) delay(5) }
                permitted.set(false); release.countDown()
                assertEquals("REVOKED", JSONObject(withTimeout(2000) { waiting.await() }.content).getString("code"))
                withTimeout(2000) { first.await() }
                assertEquals(1, calls.get())
            } finally { release.countDown(); queued?.cancelAndJoin(); first.cancelAndJoin() }
        }
    }
}
