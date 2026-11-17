package io.github.mangi.eta.agent.packages

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.connector.*
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.capability.NexusExecutionGate
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.terminal.FileToolDispatcher
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OperitToolAdapterTest {
    @org.junit.Test fun textModelCallsReuseABoundedToolFreeChildInsteadOfAnUntrackedProviderRequest() {
        val args = org.json.JSONObject().put("function_type", "TRANSLATION").put("record_token_usage", "false").put("turns",
            org.json.JSONArray().put(org.json.JSONObject().put("kind", "SYSTEM").put("content", "Translate into Chinese"))
                .put(org.json.JSONObject().put("kind", "USER").put("content", "Hello"))).put("enable_thinking", "false")
        val result = OperitToolAdapter.invoke("call_chat_model", args) { name, parameters ->
            org.junit.Assert.assertEquals("subagent", name)
            org.junit.Assert.assertEquals(0, parameters.getJSONArray("tools").length())
            org.junit.Assert.assertEquals("off", parameters.getString("reasoning_effort"))
            org.junit.Assert.assertTrue(parameters.getString("prompt").contains("Translate into Chinese"))
            org.json.JSONObject().put("ok", true).put("summary", "你好").put("subagent_id", "child-1").put("model_id", "fixture")
        }
        org.junit.Assert.assertEquals("你好", result.getJSONObject("data").getString("text"))
        org.junit.Assert.assertEquals("stop", result.getJSONObject("data").getString("finishReason"))
    }
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun actualFileBackendCreatesReadsCopiesMovesAndDeletesWithCanonicalPermissionNames() {
        val workspace = File(context.cacheDir, "adapter-files").apply { mkdirs() }
        val backend = LocalFileToolBackend(workspace)
        val calls = mutableListOf<String>()
        fun invoke(name: String, args: JSONObject): JSONObject = OperitToolAdapter.invoke(name, args) { native, parameters ->
            calls += native
            JSONObject(FileToolDispatcher.execute(backend, native, parameters))
        }
        assertTrue(invoke("make_directory", JSONObject().put("path", "reports/sub").put("create_parents", "true")).getBoolean("ok"))
        assertTrue(invoke("write_file", JSONObject().put("path", "reports/sub/report.txt").put("content", "first").put("append", "false")).getBoolean("ok"))
        invoke("write_file", JSONObject().put("path", "reports/sub/report.txt").put("content", "second").put("append", "false"))
        val read = invoke("read_file_full", JSONObject().put("path", "reports/sub/report.txt"))
        assertEquals("second", read.getJSONObject("data").getString("content"))
        assertTrue(invoke("copy_file", JSONObject().put("source", "reports/sub/report.txt").put("destination", "copied.txt")).getBoolean("ok"))
        assertTrue(invoke("move_file", JSONObject().put("source", "copied.txt").put("destination", "moved.txt")).getBoolean("ok"))
        assertEquals("second", File(workspace, "moved.txt").readText())
        assertFalse(File(workspace, "copied.txt").exists())
        val exists = invoke("file_exists", JSONObject().put("path", "moved.txt")).getJSONObject("data")
        assertTrue(exists.getBoolean("exists")); assertFalse(exists.getBoolean("isDirectory"))
        assertTrue(invoke("delete_file", JSONObject().put("path", "moved.txt")).getBoolean("ok"))
        assertFalse(invoke("file_exists", JSONObject().put("path", "moved.txt")).getJSONObject("data").getBoolean("exists"))
        assertEquals(listOf("make_directory", "write_file", "write_file", "read_file", "copy_file", "move_file", "stat_file", "delete_file", "stat_file"), calls)
        val guarded = JSONObject(FileToolDispatcher.execute(backend, "delete_file", JSONObject().put("path", workspace.absolutePath).put("recursive", true)))
        assertFalse(guarded.getBoolean("ok"))
        assertTrue(workspace.isDirectory)
    }

    @Test fun originalHttpParametersReachActualRestTransportWithoutIgnoringTlsOrRedirecting() = runBlocking {
        val posted = AtomicReference<String>()
        val method = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/rest") { exchange ->
                posted.set(exchange.requestBody.bufferedReader().readText()); method.set(exchange.requestMethod)
                val body = "{\"result\":42}".toByteArray(); exchange.responseHeaders.set("Set-Cookie", "private=secret")
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            createContext("/redirect") { exchange -> exchange.responseHeaders.set("Location", "/rest"); exchange.sendResponseHeaders(302, -1); exchange.close() }
            start()
        }
        try {
            val endpoint = "http://127.0.0.1:${server.address.port}"
            val result = withContext(Dispatchers.IO) {
                OperitToolAdapter.invoke("http_request", JSONObject().put("url", "$endpoint/rest").put("method", "PUT")
                    .put("body_type", "form").put("body", JSONObject().put("title", "a b")).put("headers", "{}")) { native, args ->
                    assertEquals("connector_call", native); assertEquals("rest", args.getString("protocol"))
                    RestConnectorTransport(args.getString("endpoint"), null, ConnectorSecurityManager()).use { transport ->
                        val reply = runBlocking { transport.invoke(ConnectorInvocation("request", args.getJSONObject("arguments").put("confirmed", true))) }
                        JSONObject().put("ok", true).put("result", reply.json)
                    }
                }
            }
            assertEquals("PUT", method.get()); assertEquals("title=a+b", posted.get())
            assertEquals(42, JSONObject(result.getJSONObject("data").getString("content")).getInt("result"))
            assertFalse(result.toString().contains("private=secret"))
            RestConnectorTransport("$endpoint/redirect", null, ConnectorSecurityManager()).use { transport ->
                val response = transport.invoke(ConnectorInvocation("request", JSONObject().put("confirmed", true)))
                assertEquals(302, response.json.getInt("statusCode"))
                assertEquals("PUT", method.get())
            }
            var nativeCalled = false
            val tls = OperitToolAdapter.invoke("http_request", JSONObject().put("url", "$endpoint/rest").put("ignore_ssl", "true")) { _, _ -> nativeCalled = true; JSONObject() }
            assertEquals("PACKAGE_TLS_REQUIRED", tls.getString("code")); assertFalse(nativeCalled)
        } finally { server.stop(0) }
    }

    @Test fun deleteFileNeedsActualLocalApprovalAndRemoteRunsCannotInvokeIt() = runBlocking {
        val wire = AgentRuntimeWire.RunRequest("delete-check", "fixture", AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "", model = "fixture", systemPrompt = ""), emptyList())
        val work = async(Dispatchers.IO) { NexusExecutionGate.authorize(context, wire, "delete_file",
            JSONObject().put("path", "/fixture/report.txt"), AgentRunController(), System.currentTimeMillis() + 5000) }
        try {
            val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            assertTrue(EtaAppControlConfirmation.cancel(approval.id))
            assertEquals("LOCAL_CONFIRMATION_DENIED", (withTimeout(3000) { work.await() } as CapabilityAuthorization.Denied).code)
            val remote = wire.copy(handoff = AgentRuntimeWire.EntryHandoff(id = "fixture", source = "a2a", payload = "fixture"))
            assertEquals("REMOTE_HIGH_RISK_DENIED", (NexusExecutionGate.authorize(context, remote, "delete_file",
                JSONObject().put("path", "/fixture/report.txt"), AgentRunController(), System.currentTimeMillis() + 5000) as CapabilityAuthorization.Denied).code)
        } finally { work.cancelAndJoin(); EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) } }
    }

    @Test fun terminalContractPreservesExitCodeAndTimeoutAndUnsupportedApisDoNotGuess() {
        val result = OperitToolAdapter.invoke("execute_in_terminal_session", JSONObject().put("session_id", "session").put("command", "task").put("timeout_ms", "1000")) { native, args ->
            assertEquals("terminal", native); assertEquals("exec", args.getString("action")); assertEquals(1000, args.getInt("timeout_ms"))
            JSONObject().put("ok", false).put("timed_out", true).put("exit_code", -2).put("stdout", "partial").put("stderr", "timeout")
        }
        assertEquals(-2, result.getJSONObject("data").getInt("exitCode"))
        assertTrue(result.getJSONObject("data").getBoolean("timedOut"))
        var called = false
        val unsupported = OperitToolAdapter.invoke("unknown_native_method", JSONObject()) { _, _ -> called = true; JSONObject() }
        assertFalse(called); assertEquals("PACKAGE_OPERIT_API_UNAVAILABLE", unsupported.getString("code"))
    }
}
