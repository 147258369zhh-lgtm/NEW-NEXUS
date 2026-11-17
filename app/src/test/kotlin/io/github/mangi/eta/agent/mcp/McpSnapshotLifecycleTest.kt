package io.github.mangi.eta.agent.mcp

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class McpSnapshotLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tool = McpToolDefinition("read", inputSchemaJson = """{"type":"object"}""")
    @Before fun setup() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        McpServerRepository.init(context)
    }
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun localResourceReaderDoesNotCollideWithAServerToolUsingTheSameDefinitionName() = runBlocking(Dispatchers.IO) {
        val remoteTool = tool.copy(name = "nexa_read_resource")
        McpServerRepository.addUnconfigured(McpServerSetting("same-name", "Same name", "https://same.invalid/mcp",
            tools = listOf(remoteTool), enabledToolNames = setOf(remoteTool.name), lastProtocolVersion = McpProtocolMode.LEGACY))
        val snapshot = McpRunSnapshot.load()
        assertEquals(2, snapshot.tools.size)
        val remote = snapshot.tools.single { !it.resourceReader }
        val local = snapshot.tools.single { it.resourceReader }
        assertEquals(remote.definition.name, local.definition.name)
        assertNotEquals(remote.modelName, local.modelName)
        assertTrue(remote.modelName.startsWith("mcp_"))
        assertTrue(local.modelName.startsWith("mcp__nexa_"))
        assertTrue(local.modelName.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val longPrefix = "sameprefix" + "x".repeat(200)
        val longNames = listOf("one", "two").map { suffix ->
            McpRunSnapshot.resourceReader(local.server.copy(id = longPrefix + suffix), null).modelName
        }
        assertEquals(2, longNames.distinct().size)
        assertTrue(longNames.all { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) })
        assertSame(remote, snapshot.resolve(remote.modelName))
        assertSame(local, snapshot.resolve(local.modelName))
    }

    @Test fun largeCatalogKeepsLaterEnabledToolsAndEveryServersResourceReaderDiscoverable() = runBlocking(Dispatchers.IO) {
        McpServerRepository.addUnconfigured(McpServerSetting("early", "Early", "https://early.invalid/mcp",
            tools = listOf(tool), enabledToolNames = setOf(tool.name), lastProtocolVersion = McpProtocolMode.LEGACY))
        val laterTools = (1..79).map { tool.copy(name = "read_$it") }
        McpServerRepository.addUnconfigured(McpServerSetting("later", "Later", "https://later.invalid/mcp",
            tools = laterTools, enabledToolNames = laterTools.map { it.name }.toSet(), lastProtocolVersion = McpProtocolMode.LEGACY))
        val snapshot = McpRunSnapshot.load()
        assertEquals(82, snapshot.tools.size)
        assertEquals(setOf("early", "later"), snapshot.tools.filter { it.resourceReader }.map { it.server.id }.toSet())
        assertEquals((laterTools + tool).map { it.name }.toSet(), snapshot.tools.filterNot { it.resourceReader }.map { it.definition.name }.toSet())
        snapshot.tools.forEach { assertSame(it, snapshot.resolve(it.modelName)) }
    }

    @Test fun resourceReadSupportIsCheckedLazilyAndFailureCannotDisableOrdinaryTools() = runBlocking(Dispatchers.IO) {
        val supportsRead = AtomicBoolean(false)
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                val method = request.getString("method")
                methods += method
                val response = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                if (method == "resources/read" && !supportsRead.get()) {
                    response.put("error", JSONObject().put("code", -32601).put("message", "Method not found"))
                } else {
                    val result = JSONObject().put("resultType", "complete")
                    if (method == "resources/read") {
                        result.put("contents", JSONArray().put(JSONObject().put("uri", "memory://result")
                            .put("mimeType", "text/plain").put("text", "actual resource")))
                    } else {
                        result.put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ordinary works"))
                            .put(JSONObject().put("type", "resource_link").put("uri", "memory://result")))
                    }
                    response.put("result", result)
                }
                val bytes = response.toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            McpServerRepository.addUnconfigured(McpServerSetting("lazy", "Lazy", "http://127.0.0.1:${http.address.port}/mcp",
                tools = listOf(tool), enabledToolNames = setOf(tool.name), protocolMode = McpProtocolMode.LATEST,
                lastProtocolVersion = McpProtocolMode.LATEST, toolsExpireAt = Long.MAX_VALUE))
            val snapshot = McpRunSnapshot.load()
            assertTrue(methods.isEmpty())
            val reader = snapshot.tools.single { it.resourceReader }
            val remote = snapshot.tools.single { !it.resourceReader }
            McpToolExecutor(snapshot).use { executor ->
                val readCall = AgentModelClient.ToolCall("resource", reader.modelName, """{"uri":"memory://result"}""")
                val unsupported = executor.execute(readCall)
                assertFalse(JSONObject(unsupported.content).getBoolean("ok"))
                assertTrue(unsupported.files.isEmpty())
                val normal = executor.execute(AgentModelClient.ToolCall("normal", remote.modelName, "{}"))
                assertTrue(JSONObject(normal.content).getBoolean("ok"))
                assertTrue(normal.content.contains(reader.modelName))
                supportsRead.set(true)
                val read = executor.execute(readCall.copy(id = "supported-resource"))
                assertEquals("actual resource", read.files.single().bytes.toString(Charsets.UTF_8))
            }
            assertEquals(listOf("resources/read", "tools/call", "resources/read"), methods.toList())
        } finally { http.stop(0) }
    }

    @Test fun lateDiscoveryPreservesDisabledServerAndToolChoicesAndCannotRestoreOldEndpoint() = runBlocking(Dispatchers.IO) {
        val configured = McpServerRepository.addUnconfigured(McpServerSetting("one", "One", "https://one.invalid/mcp",
            tools = listOf(tool), enabledToolNames = setOf(tool.name)))
        val discovery = configured.copy(lastProtocolVersion = McpProtocolMode.LATEST, lastCheckedAt = 100)
        McpServerRepository.setToolEnabled(configured.id, tool.name, false)
        val disabledTool = McpServerRepository.serverById(configured.id)!!
        McpServerRepository.update(disabledTool, disabledTool.copy(enabled = false))
        val merged = McpServerRepository.saveDiscovery(configured, discovery, null)!!
        assertFalse(merged.enabled)
        assertTrue(merged.enabledToolNames.isEmpty())
        McpServerRepository.update(merged, merged.copy(url = "https://changed.invalid/mcp"))
        assertNull(McpServerRepository.saveDiscovery(configured, discovery, null))
        McpServerRepository.recordDiscoveryFailure(configured, null, "stale failure")
        assertEquals("https://changed.invalid/mcp", McpServerRepository.serverById(configured.id)!!.url)
        assertNull(McpServerRepository.serverById(configured.id)!!.lastError)
    }

    @Test fun cancelledPreparationExitsBeforeDiscoveryAndProtocolChangeRevokesFrozenBinding() = runBlocking(Dispatchers.IO) {
        val configured = McpServerRepository.addUnconfigured(McpServerSetting("two", "Two", "https://two.invalid/mcp",
            tools = listOf(tool), enabledToolNames = setOf(tool.name), lastProtocolVersion = McpProtocolMode.LEGACY))
        val snapshot = McpRunSnapshot.load()
        val binding = snapshot.bindings().single()
        val name = snapshot.tools.single { !it.resourceReader }.modelName
        assertTrue(binding.available(name))
        McpServerRepository.update(configured, configured.copy(protocolMode = McpProtocolMode.LATEST))
        assertFalse(binding.available(name))
        val controller = AgentRunController().apply { cancel() }
        val failure = RuntimeInvocationScope.withController(controller) {
            runCatching { runBlocking(RuntimeInvocationScope.coroutineContext()) { McpRunSnapshot.load() } }.exceptionOrNull()
        }
        assertTrue(failure is AgentRunCancelledException)
        binding.executor.close()
    }

    @Test fun duplicateNamesRevokeAuthorizationAndBlockFrozenAndDebugCalls() = runBlocking(Dispatchers.IO) {
        val requests = AtomicInteger()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            start()
        }
        try {
            val configured = McpServerRepository.addUnconfigured(McpServerSetting("duplicates", "Test",
                "http://127.0.0.1:${http.address.port}/mcp", tools = listOf(tool),
                enabledToolNames = setOf(tool.name), lastProtocolVersion = McpProtocolMode.LEGACY))
            val snapshot = McpRunSnapshot.load()
            val binding = snapshot.bindings().single()
            val name = snapshot.tools.single { !it.resourceReader }.modelName
            val duplicate = tool.copy(description = "A different tool with the same name")
            val merged = McpServerRepository.saveDiscovery(configured, configured.copy(tools = listOf(tool, duplicate)), null)!!
            assertTrue(merged.activeTools.isEmpty())
            assertTrue(merged.enabledToolNames.isEmpty())
            assertFalse(binding.available(name))
            val result = binding.executor.execute(AgentModelClient.ToolCall("call", name, "{}"))
            assertEquals("MCP_TOOL_UNAVAILABLE", JSONObject(result.content).getString("code"))
            assertTrue(runCatching { McpServerManager.callTool(configured.id, tool.name, JSONObject()) }.isFailure)
            assertTrue(runCatching { McpServerRepository.setToolEnabled(configured.id, tool.name, true) }.isFailure)
            McpServerRepository.setToolEnabled(configured.id, tool.name, false)
            assertEquals(0, requests.get())
            val recovered = McpServerRepository.saveDiscovery(merged, merged.copy(tools = listOf(tool)), null)!!
            assertTrue(recovered.enabledToolNames.isEmpty())
            binding.executor.close()
        } finally {
            http.stop(0)
        }
    }

    @Test fun historicalDuplicatesAndModelNameCollisionsAreNotProjected() = runBlocking(Dispatchers.IO) {
        val duplicate = tool.copy(description = "Different schema owner")
        val configured = McpServerRepository.addUnconfigured(McpServerSetting("old", "Old", "https://old.invalid/mcp",
            tools = listOf(tool, duplicate), enabledToolNames = setOf(tool.name), lastProtocolVersion = McpProtocolMode.LEGACY))
        assertTrue(configured.activeTools.isEmpty())
        val snapshot = McpRunSnapshot.load()
        assertTrue(snapshot.tools.none { !it.resourceReader })
        assertTrue(snapshot.tools.single().resourceReader)
        val recovered = McpServerRepository.saveDiscovery(configured, configured.copy(tools = listOf(tool)), null)!!
        assertTrue(recovered.enabledToolNames.isEmpty())
        val collision = McpRunSnapshot(listOf(McpRunTool("same", configured, tool, null),
            McpRunTool("same", configured, duplicate, null)))
        assertTrue(collision.tools.isEmpty())
        assertNull(collision.resolve("same"))
    }

    @Test fun staleSettingsCannotRestoreAConcurrentChange() = runBlocking(Dispatchers.IO) {
        val original = McpServerRepository.addUnconfigured(McpServerSetting("stale", "Stale", "https://example.invalid/mcp"))
        McpServerRepository.update(original, original.copy(enabled = false))
        assertTrue(runCatching { McpServerRepository.update(original, original.copy(name = "Old edit")) }.isFailure)
        assertFalse(McpServerRepository.serverById(original.id)!!.enabled)
    }
}
