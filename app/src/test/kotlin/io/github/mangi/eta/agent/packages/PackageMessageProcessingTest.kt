package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageMessageProcessingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun row(kind: String) = JSONObject().put("kind", kind).put("id", kind.lowercase())
        .put("function", JSONObject().put("nexus_callback", "$kind/${kind.lowercase()}/function"))
    private fun record() = CapabilityPackage("fixture.messages", "Messages", "", "1", "sandbox", "a".repeat(64), emptyList(), enabled = true,
        mainEntry = "main.js", timeoutSeconds = 20, registrations = JSONArray(listOf(row("MessageProcessingPlugin"), row("ChatRuntimeHook"))).toString(),
        enabledExtensions = setOf("MessageProcessingPlugin", "ChatRuntimeHook"))
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "Fixture",
        supportsTools = false, autoCompactionEnabled = false)
    private fun noProvider() = object : AgentProviderClient {
        override val id = "must-not-request"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse = error("A matched processor owns the submission")
    }
    @After fun close() = NexusExecutionStore.closeForTests()

    @Test fun matchedProcessorRunsBeforeTheProviderAndPersistsTheActualResponseInTheOriginalRun() {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController()
            val run = RunRequest(RunId("processor-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, run); recorder.bindController(controller); recorder.start()
            val seen = mutableListOf<String>(); var probes = 0; var executions = 0
            val chain = PackageHookChain(listOf(record()), { _, row, event, payload, _ ->
                if (row.getString("kind") == "ChatRuntimeHook") { assertEquals("state_changed", event); seen += payload.getString("state"); null }
                else if (payload.getBoolean("probeOnly")) {
                    probes++; assertEquals("Create report", payload.getString("messageContent")); assertEquals(2, payload.getJSONArray("chatHistory").length())
                    JSONObject().put("matched", true)
                } else { executions++; JSONObject().put("matched", true).put("chunks", JSONArray(listOf("Report ", "complete"))) }
            }, controller, "chat", "fixture")
            val emitted = mutableListOf<AgentEvent>(); var saved = emptyList<AgentModelClient.ConversationMessage>()
            val response = AgentModelClient.complete(config(), "Create report", AgentModelClient.ToolExecutor { error("No tools") },
                history = listOf(AgentModelClient.ConversationMessage("user", "old"), AgentModelClient.ConversationMessage("assistant", "old answer")),
                provider = noProvider(), extensions = chain, runController = controller,
                onEvent = { recorder.record(it); emitted += it }, onTranscript = { saved = it })
            assertEquals("Report complete", response.content); assertEquals(1, probes); assertEquals(1, executions)
            assertEquals(response.transcript, saved)
            assertEquals(listOf("Report ", "complete"), emitted.filterIsInstance<AgentEvent.AssistantBlockDelta>().map { it.delta })
            assertEquals(listOf("processing", "receiving", "completed"), seen)
            assertTrue(emitted.none { it is AgentEvent.ProviderRequestStarted })
            recorder.finish(AgentRuntimeWire.RunResult("processor-run", true, response.content), false)
            assertEquals(RunState.SUCCEEDED, store.find(run.runId)!!.state)
            assertEquals(1, store.items(run.runId).count { it.kind == ItemKind.ASSISTANT_MESSAGE })
        }
    }

    @Test fun noMatchUsesOriginalProviderButMatchedFailureNeverRestartsThroughTheProvider() {
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "original"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                requests++; return ProviderResponse(JSONObject().put("role", "assistant").put("content", "original answer"))
            }
        }
        fun chain(matches: Boolean) = PackageHookChain(listOf(record()), { _, row, _, payload, _ ->
            if (row.getString("kind") == "ChatRuntimeHook") null
            else if (payload.getBoolean("probeOnly")) matches else throw AgentModelFailure("MODEL_CONNECTION_FAILED", true, "Processor failed after claiming input")
        }, AgentRunController(), "chat", "fixture")
        assertEquals("original answer", AgentModelClient.complete(config(), "input", AgentModelClient.ToolExecutor { error("unused") }, provider = provider, extensions = chain(false)).content)
        val failure = runCatching { AgentModelClient.complete(config(), "input", AgentModelClient.ToolExecutor { error("unused") }, provider = provider, extensions = chain(true)) }.exceptionOrNull()
        assertTrue(failure is AgentModelExecutionException); assertEquals(1, requests)
        assertFalse(AgentModelFallbackPolicy.decide(AgentModelFallbackPolicy.classify(failure?.cause), true, true).useFallback)
    }

    @Test fun actualHookBridgePublishesFromAnotherThreadBeforeCallbackCompletionWithoutDuplicatingTheFinalText() = runBlocking {
        val installed = install(); val controller = AgentRunController()
        val firstOutput = CountDownLatch(1); val finish = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<AgentEvent>())
        val runner = PackageHookRunner { file, _, _, host ->
            val source = JSONObject(file.readText()); val probe = source.getJSONObject("arguments").getJSONObject("event").getJSONObject("eventPayload").getBoolean("probeOnly")
            if (probe) JSONObject().put("ok", true).put("value", JSONObject().put("matched", true))
            else {
                assertTrue(source.getBoolean("stream_mode"))
                CompletableFuture.runAsync {
                    assertTrue(host("package:message-output", JSONObject().put("value", JSONObject().put("chunk", "First "))).getBoolean("ok"))
                }.get(3, TimeUnit.SECONDS)
                firstOutput.countDown(); assertTrue(finish.await(3, TimeUnit.SECONDS))
                host("package:message-output", JSONObject().put("value", JSONObject().put("chunk", "second")))
                JSONObject().put("ok", true).put("value", JSONObject().put("matched", true).put("text", "First second"))
            }
        }
        PackageHookInvoker(context, controller, AgentModelClient.ToolExecutor { error("No tools") }, { _, _ -> false }, runner).use { invoker ->
            val chain = PackageHookChain(listOf(installed), invoker::invoke, controller, "chat", "fixture")
            val running = async(Dispatchers.IO) { AgentModelClient.complete(config(), "input", AgentModelClient.ToolExecutor { error("No tools") },
                provider = noProvider(), extensions = chain, runController = controller, onEvent = { events += it }) }
            try {
                assertTrue(withContext(Dispatchers.IO) { firstOutput.await(3, TimeUnit.SECONDS) })
                assertFalse(running.isCompleted)
                assertEquals(listOf("First "), events.filterIsInstance<AgentEvent.AssistantBlockDelta>().map { it.delta })
                finish.countDown()
                val result = withTimeout(4000) { running.await() }
                assertEquals("First second", result.content)
                assertEquals(listOf("First ", "second"), events.filterIsInstance<AgentEvent.AssistantBlockDelta>().map { it.delta })
                assertEquals(1, events.filterIsInstance<AgentEvent.AssistantBlockStart>().size)
                assertNull(events.filterIsInstance<AgentEvent.AssistantBlockEnd>().single().replacementContent)
            } finally { finish.countDown(); running.cancelAndJoin(); controller.cancel() }
        }
    }

    @Test fun correctedFinalOutputReplacesTheDraftAndProcessorFailureKeepsPartialTextWithoutProviderReplay() {
        fun chain(controller: AgentRunController, fail: Boolean) = PackageHookChain(listOf(record()), { _, row, _, payload, _ ->
            if (row.getString("kind") == "ChatRuntimeHook") null
            else if (payload.getBoolean("probeOnly")) true
            else {
                PackageMessageOutput.sink!!.invoke("Live draft")
                if (fail) error("Processor failed after live output")
                JSONObject().put("matched", true).put("text", "Corrected final")
            }
        }, controller, "chat", "fixture")
        val controller = AgentRunController(); val events = mutableListOf<AgentEvent>()
        val response = AgentModelClient.complete(config(), "input", AgentModelClient.ToolExecutor { error("No tools") }, provider = noProvider(),
            extensions = chain(controller, false), runController = controller, onEvent = events::add)
        assertEquals("Corrected final", response.transcript.single().content)
        assertEquals("Corrected final", events.filterIsInstance<AgentEvent.AssistantBlockEnd>().single().replacementContent)
        val failed = runCatching { AgentModelClient.complete(config(), "input", AgentModelClient.ToolExecutor { error("No tools") },
            provider = noProvider(), extensions = chain(controller, true), runController = controller) }.exceptionOrNull() as AgentModelExecutionException
        assertEquals("Live draft", failed.transcript.single().content)
        assertFalse(AgentModelFallbackPolicy.decide(AgentModelFallbackPolicy.classify(failed.cause), true, true).useFallback)
    }

    @Test fun probeCannotPublishIntermediateTextEvenWhenThereIsAnOuterOutputSink() {
        val installed = install(); var frames = 0
        val runner = PackageHookRunner { _, _, _, host ->
            host("package:message-output", JSONObject().put("value", JSONObject().put("chunk", "Must not display")))
            JSONObject().put("ok", true).put("value", true)
        }
        PackageHookInvoker(context, AgentRunController(), AgentModelClient.ToolExecutor { error("No tools") }, { _, _ -> false }, runner).use { invoker ->
            val failure = runCatching { PackageMessageOutput.withSink({ frames++ }) {
                invoker.invoke(installed, row("MessageProcessingPlugin"), "", JSONObject().put("probeOnly", true), 1000)
            } }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("OUTPUT_NOT_EXPECTED")); assertEquals(0, frames)
        }
    }

    private fun install(): CapabilityPackage {
        val store = CapabilityPackageStore(context)
        val zipFile = File.createTempFile("processor-", ".toolpkg", context.cacheDir)
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.messages","main":"main.js","api_version":"1.0.0"}""", "main.js" to "exports.registerToolPkg = () => true;").forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        val installed = store.install(store.stage(Uri.fromFile(zipFile)))
        store.saveRegistration(installed, JSONArray().put(row("MessageProcessingPlugin")))
        store.configure(installed.id, enabled = true, allowedTools = setOf("write_file"), enabledExtensions = setOf("MessageProcessingPlugin"))
        return store.list().single()
    }

    @Test fun processorProbeCannotWriteAndExecutionRequiresLocalApprovalBeforeUsingTheOriginalNativeExecutor() = runBlocking {
        val installed = install(); val owner = AgentRunController(); var writes = 0
        val runner = PackageHookRunner { file, timeout, _, host ->
            val event = JSONObject(file.readText()).getJSONObject("arguments").getJSONObject("event")
            assertEquals("toolpkg_message_processing", event.getString("event"))
            val probe = event.getJSONObject("eventPayload").getBoolean("probeOnly")
            assertEquals(if (probe) 1000L else 20_000L, timeout)
            val result = host("write_file", JSONObject().put("path", "report.txt").put("content", "report"))
            assertEquals(!probe, result.getBoolean("ok"))
            JSONObject().put("ok", true).put("value", if (probe) JSONObject().put("matched", true) else "Saved report")
        }
        PackageHookInvoker(context, owner, AgentModelClient.ToolExecutor { writes++; AgentModelClient.ToolResult("{\"ok\":true}") }, { _, _ -> false }, runner).use { invoker ->
            invoker.invoke(installed, row("MessageProcessingPlugin"), "", JSONObject().put("probeOnly", true), 1000)
            assertEquals(0, writes); assertNull(EtaAppControlConfirmation.pending.value)
            val call = async(Dispatchers.IO) { invoker.invoke(installed, row("MessageProcessingPlugin"), "", JSONObject().put("probeOnly", false), 20_000) }
            try {
                val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertEquals(0, writes); assertEquals("packages.automatic_write", approval.capabilityId)
                EtaAppControlConfirmation.approve(approval.id)
                assertEquals("Saved report", withTimeout(3000) { call.await() }); assertEquals(1, writes)
            } finally { call.cancelAndJoin(); owner.cancel() }
        }
    }
}
