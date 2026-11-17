package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.control.EtaAppControlAuditStore
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
class PackageHookChainTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun row(kind: String, id: String = kind.lowercase()) = JSONObject().put("kind", kind).put("id", id)
        .put("function", JSONObject().put("nexus_callback", "$kind/$id/function"))
    private fun record(vararg kinds: String) = CapabilityPackage("fixture.hooks", "Hooks", "", "1", "sandbox", "a".repeat(64), emptyList(),
        enabled = true, mainEntry = "main.js", registrations = JSONArray(kinds.map { row(it) }).toString(), enabledExtensions = kinds.toSet())
    private fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "Original system"))
        .put(JSONObject().put("role", "user").put("content", "old").put("_eta_message_id", "old-user"))
        .put(JSONObject().put("role", "assistant").put("content", "old reply"))
        .put(JSONObject().put("role", "user").put("content", "current").put("_eta_message_id", "current-user"))

    @Test fun theActualModelClientUsesProcessedInputSystemAndHistoryHooksWhileToolsCanOnlyBeReduced() {
        val events = mutableListOf<String>(); val controller = AgentRunController()
        val hooks = record("ChatInputHook", "PromptInputHook", "SystemPromptComposeHook", "ToolPromptComposeHook", "PromptFinalizeHook")
        val chain = PackageHookChain(listOf(hooks), { _, row, stage, payload, _ ->
            events += stage
            when (row.getString("kind")) {
                "ChatInputHook" -> { assertTrue(payload.getBoolean("hasAttachments")); JSONObject().put("action", "replace").put("text", "edited") }
                "PromptInputHook" -> JSONObject().put("processedInput", payload.getString("processedInput") + " refined")
                "SystemPromptComposeHook" -> JSONObject().put("systemPrompt", payload.getString("systemPrompt") + "\nCustom extension")
                "ToolPromptComposeHook" -> JSONObject().put("toolPrompt", "Use declared tools only")
                else -> JSONObject().put("availableTools", JSONArray())
            }
        }, controller, "chat", "fixture")
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "extension-fixture"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests++; onEvent(ProviderEvent.RequestStarted)
                assertTrue(request.messages.getJSONObject(0).getString("content").contains("Custom extension"))
                assertTrue(request.messages.getJSONObject(0).getString("content").contains("Use declared tools only"))
                assertTrue(request.messages.toString().contains("edited refined")); assertEquals(0, request.effectiveTools.length())
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        // The image flag is checked at the same input adapter; image bytes need not be sent to a network.
        assertEquals("edited refined", chain.input("original", 1))
        val system = messages(); val narrowed = chain.prepare(system, 1, JSONArray().put(tool("read_file")))
        assertEquals(0, narrowed.length()); assertTrue(system.getJSONObject(0).getString("content").contains("Custom extension"))
        val inputOnly = object : AgentRunExtensions {
            override fun input(prompt: String, attachmentCount: Int) = "edited refined"
            override fun prepare(messages: JSONArray, systemCount: Int, tools: JSONArray) = chain.prepare(messages, systemCount, tools)
            override fun tools(tools: JSONArray) = chain.tools(tools)
        }
        val response = AgentModelClient.complete(config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture-key", model = "fixture", systemPrompt = "Original system",
            supportsTools = true, autoCompactionEnabled = false), provider = provider, prompt = "original", runController = controller,
            toolExecutor = AgentModelClient.ToolExecutor { error("No tool should execute") }, skillContext = SkillContext(emptyList()), memoryContext = AgentMemoryContext.DISABLED,
            capabilitiesProvider = { io.github.mangi.eta.agent.tool.AgentToolCapabilities(rootAvailable = false) }, extensions = inputOnly)
        assertEquals("done", response.content); assertEquals(1, requests)
        assertTrue(events.containsAll(listOf("submit_requested", "before_process", "after_compose_system_prompt", "before_send")))
    }

    @Test fun toolExpansionAndBrokenToolHistoryAreRejectedWithoutChangingOriginalMessages() {
        val original = messages(); val before = original.toString()
        val record = record("PromptFinalizeHook")
        val chain = PackageHookChain(listOf(record), { _, _, _, _, _ -> JSONObject().put("availableTools", JSONArray().put(JSONObject().put("name", "terminal"))) }, AgentRunController(), "chat", "model")
        assertThrows(IllegalArgumentException::class.java) { chain.prepare(original, 1, JSONArray().put(tool("read_file"))) }
        assertEquals(before, original.toString())
        val protected = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", "call").put("function", JSONObject().put("name", "read_file")))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call").put("content", "result"))
            .put(JSONObject().put("role", "user").put("_eta_message_id", "current").put("content", "current"))
        val replacement = JSONArray().put(JSONObject().put("kind", "SYSTEM").put("content", "system").put("metadata", JSONObject().put("nexusOriginalIndex", 0)))
            .put(JSONObject().put("kind", "TOOL").put("content", "result").put("metadata", JSONObject().put("nexusOriginalIndex", 2)))
            .put(JSONObject().put("kind", "USER").put("content", "current").put("metadata", JSONObject().put("nexusOriginalIndex", 3)))
        assertThrows(IllegalArgumentException::class.java) { PackageHookChain.replaceHistory(protected, replacement, 1) }
        assertEquals(4, protected.length())
    }

    @Test fun interceptionBlocksActualCallsAndPostResultFailuresCannotRewriteAnAlreadySettledReceipt() {
        val record = record("ToolLifecycleHook")
        val chain = PackageHookChain(listOf(record), { _, _, event, _, _ -> if (event == "tool_call_intercept") JSONObject().put("action", "block").put("reason", "blocked by fixture") else null }, AgentRunController(), null, "model")
        var calls = 0
        val result = chain.executeTool(AgentModelClient.ToolCall("blocked", "write_file", "{}")) { calls++; AgentModelClient.ToolResult("{\"ok\":true}") }
        assertEquals(0, calls); assertEquals("PACKAGE_TOOL_BLOCKED", JSONObject(result.content).getString("code"))
        val observer = PackageHookChain(listOf(record), { _, _, event, _, _ -> if (event == "tool_execution_result") error("late observer failure") else null }, AgentRunController(), null, "model")
        val receipt = observer.executeTool(AgentModelClient.ToolCall("settled", "read_file", "{}")) { calls++; AgentModelClient.ToolResult("{\"ok\":true,\"value\":42}") }
        assertEquals(1, calls); assertEquals(42, JSONObject(receipt.content).getInt("value"))
        val cancelled = AgentRunController()
        val stopObserver = PackageHookChain(listOf(record), { _, _, event, _, _ -> if (event == "tool_execution_result") { cancelled.cancel(); cancelled.throwIfCancelled() }; null }, cancelled, null, "model")
        val stoppedReceipt = stopObserver.executeTool(AgentModelClient.ToolCall("settled-before-stop", "write_file", "{}")) { AgentModelClient.ToolResult("{\"ok\":true,\"receipt\":\"saved\"}") }
        assertEquals("saved", JSONObject(stoppedReceipt.content).getString("receipt")); assertTrue(cancelled.isCancelled)
    }

    @Test fun actualInstalledExtensionUsesExistingSandboxPayloadAuditsAndRevocationAndCannotBypassNativeReadPermissions() {
        val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("hooks-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.hooks","main":"main.js","api_version":"1.0.0"}""", "main.js" to "exports.registerToolPkg = () => true;").forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        val installed = store.install(store.stage(Uri.fromFile(archive))); val registration = row("SystemPromptComposeHook")
        store.saveRegistration(installed, JSONArray().put(registration))
        store.configure(installed.id, enabled = true, allowedTools = setOf("read_file", "terminal"), enabledExtensions = setOf("SystemPromptComposeHook"))
        val configured = store.list().single()
        var nativeCalls = 0
        lateinit var activeChain: PackageHookChain
        val runner = PackageHookRunner { file, _, _, host ->
            val payload = JSONObject(file.readText()); assertEquals("__nexus_callback__", payload.getString("function"))
            assertEquals("after_compose_system_prompt", payload.getJSONObject("arguments").getJSONObject("event").getString("eventName"))
            assertFalse(host("terminal", JSONObject()).getBoolean("ok"))
            val callback = java.util.concurrent.FutureTask { host("read_file", JSONObject().put("path", "allowed")) }
            val thread = Thread(callback, "fixture-binder").apply { isDaemon = true; start() }
            try { assertTrue(callback.get(2, java.util.concurrent.TimeUnit.SECONDS).getBoolean("ok")) } finally { thread.interrupt() }
            JSONObject().put("ok", true).put("value", JSONObject().put("systemPrompt", "result"))
        }
        val owner = AgentRunController()
        PackageHookInvoker(context, owner, AgentModelClient.ToolExecutor { call -> nativeCalls++; assertTrue(PackageHookInvoker.insideHook)
            assertNull(activeChain.intercept(call)); activeChain.completed(call, AgentModelClient.ToolResult("{\"ok\":true}"))
            AgentModelClient.ToolResult("{\"ok\":true}") },
            readAllowed = { name, _ -> name == "read_file" }, runner = runner).use { invoker ->
            activeChain = PackageHookChain(listOf(configured), invoker::invoke, owner, null, "fixture")
            val prompt = JSONArray().put(JSONObject().put("role", "system").put("content", "private text")).put(JSONObject().put("role", "user").put("content", "request"))
            activeChain.prepare(prompt, 1, JSONArray())
            assertEquals("result", prompt.getJSONObject(0).getString("content")); assertEquals(1, nativeCalls)
            store.configure(installed.id, enabledExtensions = emptySet())
            assertTrue(runCatching { invoker.invoke(configured, registration, "after_compose_system_prompt", JSONObject(), 1000) }.isFailure)
        }
        val audit = EtaAppControlAuditStore(context).list().filter { it.optString("capabilityId") == "packages.hook" }
        assertEquals(2, audit.size); assertFalse(audit.toString().contains("private text")); assertTrue(audit.last().getBoolean("success"))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("package-hook-") })
    }

    @Test fun lifecycleEventsFollowTheRealGateAndSettledResultCannotBeRewrittenByObservers() {
        val events = mutableListOf<String>()
        val hook = record("ToolLifecycleHook")
        val chain = PackageHookChain(listOf(hook), { _, _, name, payload, _ ->
            events += name
            if (name == "tool_permission_checked") assertFalse(payload.getBoolean("allowed"))
            JSONObject().put("action", "allow").put("success", true)
        }, AgentRunController(), "chat", "model")
        val call = AgentModelClient.ToolCall("denied", "write_file", "{}")
        val result = chain.executeTool(call) {
            chain.permissionChecked(call, false)
            AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"DENIED\"}")
        }
        assertFalse(JSONObject(result.content).getBoolean("ok"))
        assertEquals(listOf("tool_call_requested", "tool_call_intercept", "tool_permission_checked", "tool_execution_result", "tool_execution_error", "tool_execution_finished"), events)
        events.clear()
        val successful = PackageHookChain(listOf(hook), { _, _, name, _, _ -> events += name; null }, AgentRunController(), "chat", "model")
        val receipt = successful.executeTool(call) {
            successful.permissionChecked(call, true); successful.toolStarting(call)
            AgentModelClient.ToolResult("{\"ok\":true}")
        }
        assertTrue(JSONObject(receipt.content).getBoolean("ok"))
        assertEquals(listOf("tool_call_requested", "tool_call_intercept", "tool_permission_checked", "tool_execution_started", "tool_execution_result", "tool_execution_finished"), events)
        events.clear()
        assertTrue(runCatching { successful.executeTool(call) {
            successful.permissionChecked(call, true); successful.toolStarting(call); error("unknown execution outcome")
        } }.isFailure)
        assertEquals(listOf("tool_call_requested", "tool_call_intercept", "tool_permission_checked", "tool_execution_started", "tool_execution_error", "tool_execution_finished"), events)
    }
}
