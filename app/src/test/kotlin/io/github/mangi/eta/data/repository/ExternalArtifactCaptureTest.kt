package io.github.mangi.eta.data.repository

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.capability.*
import io.github.mangi.eta.agent.mcp.*
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.model.*
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ExternalArtifactCaptureTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun owner() = AgentRunController().apply { bindExecutionIdentity(RunRequest(RunId("external-artifact-run"), RunSource.CHAT,
        RunMode.CHAT, HostId("native-android"), projectId = "project-original")) }
    @Test fun actualApiBinaryResponseKeepsItsBytesAndTheExistingHostRecordsAndSharesTheFile() {
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0, -1, 12)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/report") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/pdf")
                exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"../../report.pdf\"")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }; start()
        }
        try {
            val action = ApiConnectorAction("report", "Report", ApiHttpMethod.GET, "/report", """{"type":"object","properties":{}}""", enabled = true)
            val definition = ApiConnectorDefinition(id = "report-api", name = "Reports", baseUrl = "http://127.0.0.1:${server.address.port}",
                actions = listOf(action))
            // The store normalizes GET to destructive=false; freeze the validated definition
            // just as the runtime does, so the authorization recheck compares equal actions.
            val stored = ApiConnectorStore(context).upsert(definition).single { it.id == definition.id }
            val snapshot = ApiConnectorSnapshot(listOf(ApiConnectorRunTool("api_report", stored, stored.activeActions.single())))
            val schemas = JSONArray().also(snapshot::appendModelTools)
            val binding = RunHostBinding(HostId("external-files"), listOf(ToolDescriptor("api_report", "api", "Report", "Report", "schema://report",
                effectClass = EffectClass.READ, replayPolicy = ReplayPolicy.AUTOMATIC, timeoutMs = 10_000, hostKind = HostKind.CONNECTOR)), schemas, ApiToolExecutor(context, snapshot))
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), listOf(binding), captureResult = { call, result ->
                RunArtifactCapture.externalResult(context, call, result, "conversation", "original-chat")
            }).use { host ->
                val call = AgentModelClient.ToolCall("download-report", "api_report", "{}")
                val result = RuntimeInvocationScope.withController(owner()) { host.execute(call) }
                val json = JSONObject(result.content)
                assertTrue(json.toString(), json.getBoolean("ok")); assertTrue(result.files.isEmpty())
                val saved = NexusArtifactLedger(context).forSubject("conversation", "original-chat").single()
                assertEquals(json.getString("artifact_id"), saved.id); assertEquals("project-original", saved.projectId)
                assertEquals("application/pdf", saved.mediaType); assertEquals(bytes.size.toLong(), saved.sizeBytes)
                assertArrayEquals(bytes, File(TerminalPrivateStorage.workspace(context.filesDir), saved.path).readBytes())
                assertTrue(saved.path.endsWith("report.pdf")); assertFalse(saved.path.contains(".."))
                assertNotNull(NexusArtifactAccess(context).readableUri(saved.id))
            }
        } finally { server.stop(0) }
    }
    @Test fun realMcpEmbeddedResourceBecomesALocalFileButAnUnfetchedRemoteLinkDoesNot() {
        val bytes = "MCP report body".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val result = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("result", JSONObject().put("resultType", "complete")
                    .put("content", JSONArray().put(JSONObject().put("type", "resource").put("resource", JSONObject().put("uri", "memory://reports/report.txt")
                        .put("mimeType", "text/plain").put("blob", Base64.getEncoder().encodeToString(bytes))))
                        .put(JSONObject().put("type", "resource_link").put("uri", "https://example.invalid/unfetched.txt").put("name", "Link")))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, result.size.toLong()); exchange.responseBody.use { it.write(result) }
            }; start()
        }
        try {
            val definition = McpToolDefinition("report", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting("mcp-files", "Files", "http://127.0.0.1:${server.address.port}/mcp", lastProtocolVersion = McpProtocolMode.LATEST)
            McpToolExecutor(McpRunSnapshot(listOf(McpRunTool("mcp_report", setting, definition, null))), available = { true }).use { executor ->
                val call = AgentModelClient.ToolCall("mcp-download", "mcp_report", "{}")
                val result = RuntimeInvocationScope.withController(owner()) {
                    RunArtifactCapture.externalResult(context, call, executor.execute(call), "meeting", "meeting-original")
                }
                assertTrue(JSONObject(result.content).getBoolean("ok"))
                val record = NexusArtifactLedger(context).forSubject("meeting", "meeting-original").single()
                assertArrayEquals(bytes, File(TerminalPrivateStorage.workspace(context.filesDir), record.path).readBytes())
                assertEquals(1, NexusArtifactLedger(context).list().size)
            }
        } finally { server.stop(0) }
    }
    @Test fun failedResultsNeverRegisterFilesAndNativeVoiceReceiptsKeepTheirRealMetadata() {
        val call = AgentModelClient.ToolCall("voice-output", "voice_synthesize", "{}")
        RuntimeInvocationScope.withController(owner()) {
            val rejected = RunArtifactCapture.externalResult(context, call, AgentModelClient.ToolResult("{\"ok\":false}",
                files = listOf(AgentModelClient.ToolFile("bad.txt", "text/plain", byteArrayOf(1)))), "conversation", "chat")
            assertTrue(rejected.files.isEmpty()); assertTrue(NexusArtifactLedger(context).list().isEmpty())
            val audio = File(TerminalPrivateStorage.workspace(context.filesDir), "audio/voice.wav").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val raw = JSONObject().put("ok", true).put("path", audio.absolutePath).put("mime_type", "audio/wav").put("bytes", audio.length())
            val result = JSONObject(RunArtifactCapture.fileResult(context, call, raw.toString(), "conversation", "chat"))
            assertTrue(result.toString(), result.has("artifact_id"))
            val artifact = NexusArtifactLedger(context).forSubject("conversation", "chat").single()
            assertEquals("audio/wav", artifact.mediaType); assertEquals(4L, artifact.sizeBytes)
        }
    }
}
