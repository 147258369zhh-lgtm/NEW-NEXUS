package io.github.mangi.eta.agent.mcp

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class McpProcessLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun cachedToolStartsLocalServiceAndIndividualCloseKeepsItAlive() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val bytes = """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[{"type":"text","text":"done"}]}}""".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val setting = setting(server.address.port)
        McpServerRepository.init(context)
        val definition = McpToolDefinition("read", inputSchemaJson = """{"type":"object"}""")
        try {
            repeat(2) {
                McpToolExecutor(McpRunSnapshot(listOf(McpRunTool("mcp_read", setting, definition, null))), available = { true }).use { executor ->
                    val result = executor.execute(AgentModelClient.ToolCall("call-$it", "mcp_read", "{}"))
                    assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
                }
                assertTrue(McpProcessManager.isRunning(setting.id))
            }
            val changed = runBlocking { runCatching { McpProcessManager.ensureReady(context, setting.copy(launchCommand = "exit 0")) } }
            assertTrue(changed.exceptionOrNull()?.message.orEmpty().contains("旧配置"))
            assertTrue(runBlocking { McpProcessManager.stopAndAwait(setting.id) })
            assertFalse(McpProcessManager.isRunning(setting.id))
        } finally { McpProcessManager.stop(setting.id); server.stop(0) }
    }

    @Test fun cancelledStartupStopsWaitingAndDoesNotReplaceAnotherClientsService() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val setting = setting(port)
        val controller = AgentRunController()
        val task = async(Dispatchers.IO) {
            runCatching {
                RuntimeInvocationScope.withController(controller) {
                    runBlocking(RuntimeInvocationScope.coroutineContext()) { McpProcessManager.ensureReady(context, setting) }
                }
            }
        }
        try {
            withTimeout(3000) {
                while (!McpProcessManager.isRunning(setting.id)) delay(10)
            }
            controller.cancel()
            assertTrue(withTimeout(2000) { task.await() }.isFailure)
            val changed = runCatching { McpProcessManager.ensureReady(context, setting.copy(launchCommand = "exit 0")) }
            assertTrue(changed.exceptionOrNull()?.message.orEmpty().contains("旧配置"))
        } finally { controller.cancel(); task.cancel(); McpProcessManager.stop(setting.id) }
    }

    private fun setting(port: Int) = McpServerSetting(
        id = UUID.randomUUID().toString(), name = "Local fixture",
        url = "http://127.0.0.1:$port/mcp", lastProtocolVersion = McpProtocolMode.LATEST,
        launchCommand = "exec sleep 30",
    )
}
