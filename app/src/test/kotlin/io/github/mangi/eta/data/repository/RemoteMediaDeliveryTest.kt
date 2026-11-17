package io.github.mangi.eta.data.repository

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.capability.NexusToolDescriptors
import io.github.mangi.eta.agent.capability.NexusToolEffectClassifier
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.tool.AgentMediaDownloadTool
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RemoteMediaDeliveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun owner() = AgentRunController().apply {
        bindExecutionIdentity(RunRequest(RunId("remote-media"), RunSource.CHAT, RunMode.CHAT, HostId("native-android")))
    }

    @Test fun explicitDownloadFollowsBoundedRedirectsWithoutCookiesAndPublishesRealMimeAndReceipts() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val cookie = AtomicReference<String?>()
        val authorization = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/start") { exchange ->
                exchange.responseHeaders.add("Set-Cookie", "session=private")
                exchange.responseHeaders.add("Location", "/result")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            }
            createContext("/result") { exchange ->
                cookie.set(exchange.requestHeaders.getFirst("Cookie"))
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val args = JSONObject().put("url", "http://127.0.0.1:${server.address.port}/start").put("name", "无后缀成果")
                .put("expected_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            val result = RuntimeInvocationScope.withController(owner()) {
                AgentMediaDownloadTool.execute(context, AgentModelClient.ToolCall("download", "download_media", args.toString()), args, "conversation", "chat")
            }
            val receipt = result.artifacts.single()
            assertEquals("image/png", receipt.mediaType)
            assertEquals("无后缀成果", receipt.name)
            assertNotNull(NexusArtifactAccess(context).readableUri(receipt.id))
            assertEquals(receipt.id, NexusArtifactLedger(context).forSubject("conversation", "chat").single().id)
            assertNull(cookie.get()); assertNull(authorization.get())
            assertEquals(EffectClass.EXTERNAL_SIDE_EFFECT, NexusToolDescriptors.describe("download_media")?.effectClass)
            assertEquals(NexusToolEffectClassifier.classify("download_media"), NexusToolDescriptors.describe("download_media")?.effectClass)
        } finally { server.stop(0) }
    }

    @Test fun rejectedSizeOrDigestNeverCreatesAVerifiedArtifact() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/data") { exchange ->
                exchange.sendResponseHeaders(200, 5)
                exchange.responseBody.use { it.write(byteArrayOf(1, 2, 3, 4, 5)) }
            }
            start()
        }
        try {
            RuntimeInvocationScope.withController(owner()) {
                val args = JSONObject().put("url", "http://127.0.0.1:${server.address.port}/data").put("max_bytes", 4)
                assertThrows(IllegalStateException::class.java) {
                    AgentMediaDownloadTool.execute(context, AgentModelClient.ToolCall("limit", "download_media", args.toString()), args, "meeting", "room")
                }
                args.put("max_bytes", 1024).put("expected_sha256", "0".repeat(64))
                assertThrows(IllegalStateException::class.java) {
                    AgentMediaDownloadTool.execute(context, AgentModelClient.ToolCall("hash", "download_media", args.toString()), args, "meeting", "room")
                }
            }
            assertTrue(NexusArtifactLedger(context).list().isEmpty())
        } finally { server.stop(0) }
    }

    @Test fun explicitMcpResourceReadUsesProtocolMethodAndArchivesReturnedBytesWithNativeMediaMime() {
        val method = AtomicReference<String>()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                method.set(request.getString("method"))
                val response = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("result", JSONObject().put("resultType", "complete").put("contents", JSONArray().put(JSONObject()
                        .put("uri", "memory://generated/clip").put("mimeType", "video/mp4")
                        .put("blob", Base64.getEncoder().encodeToString(bytes))))).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong()); exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        try {
            val setting = McpServerSetting("media-mcp", "Media", "http://127.0.0.1:${server.address.port}/mcp", lastProtocolVersion = McpProtocolMode.LATEST)
            val reader = McpRunSnapshot.resourceReader(setting, null)
            McpToolExecutor(McpRunSnapshot(listOf(reader)), available = { true }).use { executor ->
                val call = AgentModelClient.ToolCall("resource", reader.modelName, "{\"uri\":\"memory://generated/clip\"}")
                val result = RuntimeInvocationScope.withController(owner()) {
                    RunArtifactCapture.externalResult(context, call, executor.execute(call), "meeting", "room")
                }
                assertEquals("resources/read", method.get())
                assertEquals("video/mp4", result.artifacts.single().mediaType)
                assertNotNull(NexusArtifactAccess(context).readableUri(result.artifacts.single().id))
            }
        } finally { server.stop(0) }
    }
}
