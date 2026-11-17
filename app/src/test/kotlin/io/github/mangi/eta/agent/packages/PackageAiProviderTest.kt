package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.control.EtaAppControlAuditStore
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [WidgetAtomicFileShadow::class])
class PackageAiProviderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun environmentStore() = PackageEnvironmentStore(context,
        McpSecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") })
    @Before fun setup() { EtaDatabase.closeForTests(); context.deleteDatabase("eta.db"); SettingsDataStore.init(context); ProviderRepository.init(context); Prefs.initLocal(context) }
    @After fun close() { EtaDatabase.closeForTests(); NexusExecutionStore.closeForTests() }
    internal suspend fun install(endpoint: String = "https://example.com/v1/chat/completions"): Pair<PackageAiDeclaration, ProviderSetting> {
        val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("provider-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.ai","main":"main.js","api_version":"1.0.1"}""", "main.js" to "exports.registerToolPkg=()=>true;").forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val record = store.install(store.stage(Uri.fromFile(archive)))
        val row = JSONObject().put("kind", "AiProvider").put("id", "fixture").put("displayName", "插件模型")
        listOf("listModels", "sendMessage", "testConnection", "calculateInputTokens").forEach { row.put(it, JSONObject()
            .put("function", JSONObject().put("nexus_callback", "AiProvider/fixture/$it/function"))) }
        store.saveRegistration(record, JSONArray().put(row))
        store.configure(record.id, enabled = true, enabledExtensions = setOf("AiProvider"))
        val registry = PackageAiRegistry(context); val declaration = registry.declarations().single()
        val provider = registry.connect(declaration) as CustomProviderSetting
        val configured = ProviderSettingsService(context).save(provider.copy(baseUrl = endpoint, apiKey = "private-provider-key"), provider)
        return declaration to configured
    }
    private fun config(provider: ProviderSetting) = RuntimeConfigRepository.buildRuntimeConfig(provider,
        Model("fixture-model", "fixture-model", "Fixture", contextWindowOverride = 128, toolCall = false))
    private fun body(config: AgentModelClient.ModelConfig, method: String = "POST") = JSONObject().put("url", config.baseUrl)
        .put("method", method).put("headers", JSONObject().put("Authorization", "Bearer ${config.apiKey}"))
        .put("body", JSONObject().put("model", config.model).put("messages", JSONArray()).put("stream", true).toString())

    @Test fun uninstallRemovesPackageProviderSelectionBindingEnvironmentStateAndVersionFiles() = runBlocking {
        val (declaration, configured) = install()
        val model = Model("package-uninstall-model", "fixture-model", "Uninstall fixture", contextWindow = 2048, toolCall = false)
        ModelRepository.syncRemoteModels(configured.id, listOf(model), setOf(model.id))
        RuntimeConfigRepository.setSelectedModelId(model.id)
        val environment = environmentStore()
        environment.put(declaration.record.id, "TOKEN", "private-package-value")
        PackageStateStore(context).invoke(declaration.record.id, JSONObject().put("action", "put").put("key", "enabled")
            .put("expectedRevision", 0).put("value", true), writable = true)
        val version = File(context.filesDir, "capability-packages/versions/${declaration.record.id}")
        assertTrue(version.isDirectory)

        CapabilityPackageStore(context, environment).delete(declaration.record.id, declaration.record.digest)

        assertNull(ProviderRepository.providerById(configured.id))
        assertFalse(PackageAiRegistry(context).available(configured.id))
        assertFalse(File(version.path).exists())
        assertNull(environment.value(declaration.record.id, "TOKEN"))
        assertTrue(PackageStateStore(context).invoke(declaration.record.id,
            JSONObject().put("action", "get").put("key", "enabled").put("expectedRevision", 0), writable = false).isNull("value"))
        assertNotEquals(configured.id, SettingsDataStore.settings().selectedProviderId)
        val bindings = File(context.filesDir, "capability-packages/provider-bindings.json")
        assertTrue(!bindings.exists() || !bindings.readText().contains(configured.id))
    }

    @Test fun catalogBecomesARealOriginalModelSelectionAndDisablingTheExtensionRevokesIt() = runBlocking {
        val (declaration, provider) = install()
        val invoker = PackageAiInvoker(context, PackageHookRunner { file, _, _, _ ->
            val event = JSONObject(file.readText()).getJSONObject("arguments").getJSONObject("event")
            assertEquals("toolpkg_ai_provider_list_models", event.getString("event"))
            JSONObject().put("ok", true).put("value", JSONObject().put("models", JSONArray().put(JSONObject()
                .put("id", "fixture-model").put("name", "Fixture model").put("contextWindow", 4096))))
        })
        val models = PackageAiCatalog(context, invoker).list(provider, AgentRunController())
        assertEquals(false, models.single().toolCall)
        ModelRepository.syncRemoteModels(provider.id, models, models.map { it.modelId }.toSet())
        RuntimeConfigRepository.setSelectedModelId(models.single().id)
        val actual = RuntimeConfigRepository.currentRuntimeConfig()!!
        assertEquals(provider.id, actual.providerId); assertEquals("fixture-model", actual.model)
        assertEquals("package_ai_provider", ProviderClientFactory.getClient(actual).id)
        CapabilityPackageStore(context).configure(declaration.record.id, enabledExtensions = emptySet())
        assertFalse(PackageAiRegistry(context).available(provider.id))
        assertTrue(runCatching { PackageAiCatalog(context, invoker).list(provider, AgentRunController()) }.isFailure)
    }

    @Test fun actualHttpGenerationUsesFrozenBudgetAndServerUsageInsteadOfThePluginsZeroCounters() = runBlocking {
        val responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Reply\"}}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":3}}\n\ndata: [DONE]\n\n"
        var transmitted: JSONObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                transmitted = JSONObject(exchange.requestBody.bufferedReader().readText())
                exchange.responseHeaders.set("Content-Type", "text/event-stream")
                val bytes = responseBody.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }; start()
        }
        try {
            val (_, provider) = install("http://127.0.0.1:${server.address.port}/v1/chat/completions")
            val cfg = config(provider)
            val runner = PackageHookRunner { _, _, _, host ->
                host("package:provider-http-start", body(cfg))
                while (!host("package:provider-http-next", JSONObject()).getBoolean("done")) { }
                host("package:provider-output", JSONObject().put("value", JSONObject().put("chunk", "Reply")))
                JSONObject().put("ok", true).put("value", JSONObject().put("text", "Reply").put("usage", JSONObject().put("input", 0).put("output", 0)))
            }
            val providerClient = PackageAiAgentProvider(context, PackageAiInvoker(context, runner))
            NexusExecutionStore(context).use { store ->
                val run = RunRequest(RunId("plugin-model-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"), budget = RunBudget(hardRounds = 3, costLimitMicros = 10_000))
                val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController(); recorder.bindController(controller); recorder.start()
                RunModelBudgetCoordinator(store, { AgentTokenPrice.create("USD", "1", "2", "0", "0", now = 0) }) { _, failure -> controller.abort(failure) }.bind(controller, run.runId)
                val events = mutableListOf<ProviderEvent>()
                val result = BudgetedAgentProvider.wrap(providerClient).complete(ProviderRequest(cfg,
                    JSONArray().put(JSONObject().put("role", "user").put("content", "private input")), JSONArray()), controller, events::add)
                assertEquals("Reply", result.assistantMessage.getString("content"))
                assertEquals(128, transmitted!!.getInt("max_tokens"))
                assertEquals(1, store.modelBudgetRequests(run.runId).size)
                assertEquals(14L, store.modelBudgetTotals(run.runId).knownCostMicros)
                assertEquals(0L, store.modelBudgetTotals(run.runId).unknownCharges)
                assertEquals(8, events.filterIsInstance<ProviderEvent.Usage>().last().usage.inputTokens)
                assertEquals(listOf("Reply"), events.filterIsInstance<ProviderEvent.BlockDelta>().map { it.delta })
                recorder.finish(AgentRuntimeWire.RunResult("plugin-model-run", true, "Reply"), false)
            }
            val audit = EtaAppControlAuditStore(context).list().toString()
            assertFalse(audit.contains("private-provider-key")); assertFalse(audit.contains("private input"))
        } finally { server.stop(0) }
    }

    @Test fun configuredOriginAndOneRequestBoundaryRejectCredentialExfiltrationAndUnaccountedRetries() = runBlocking {
        val (_, provider) = install()
        val cfg = config(provider)
        val http = PackageProviderHttp(cfg, AgentRunController(), ProviderRequest(cfg, JSONArray(), JSONArray()), {})
        try {
            assertTrue(runCatching { http.start(body(cfg).put("url", "https://other.example.com/v1/chat/completions")) }.isFailure)
            assertTrue(runCatching { http.start(body(cfg).put("url", "https://example.com/other")) }.isFailure)
        } finally { http.close() }
    }

    @Test fun packageRevocationClosesARealBlockedProviderResponseAndDiscardsTheLateReply() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                try { exchange.responseBody.write('d'.code); exchange.responseBody.flush(); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
                finally { exchange.close() }
            }; start()
        }
        val (declaration, provider) = install("http://127.0.0.1:${server.address.port}/v1/chat/completions")
        val cfg = config(provider); val owner = AgentRunController()
        val invoker = PackageAiInvoker(context, PackageHookRunner { _, _, _, host ->
            host("package:provider-http-start", body(cfg))
            while (!host("package:provider-http-next", JSONObject()).getBoolean("done")) { }
            JSONObject().put("ok", true).put("value", JSONObject().put("text", "late"))
        })
        val task = async(Dispatchers.IO) { runCatching { PackageAiAgentProvider(context, invoker).complete(ProviderRequest(cfg, JSONArray(), JSONArray()), owner) } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            CapabilityPackageStore(context).configure(declaration.record.id, enabled = false)
            assertTrue(withTimeout(3000) { task.await() }.isFailure)
        } finally { owner.cancel(); release.countDown(); task.cancelAndJoin(); server.stop(0) }
    }

    @Test fun explicitlyStructuredToolCallsExecuteThroughTheOriginalAgentLoopAndCannotInventTools() = runBlocking {
        val (_, provider) = install()
        var callbacks = 0; var tools = 0
        val invoker = PackageAiInvoker(context, PackageHookRunner { file, _, _, _ ->
            val event = JSONObject(file.readText()).getJSONObject("arguments").getJSONObject("event").getJSONObject("eventPayload")
            val value = JSONObject().put("text", if (++callbacks == 1) "" else "Tool result verified")
            if (callbacks == 1) value.put("toolCalls", JSONArray().put(JSONObject().put("id", "inspect")
                .put("name", "get_current_context").put("arguments", JSONObject())))
            else assertTrue(event.getJSONArray("chatHistory").toString().contains("TOOL_RESULT"))
            JSONObject().put("ok", true).put("value", value)
        })
        val cfg = config(provider).copy(supportsTools = true, contextWindow = 128_000, autoCompactionEnabled = false)
        val events = mutableListOf<AgentEvent>()
        val response = AgentModelClient.complete(cfg, "Inspect context", AgentModelClient.ToolExecutor { call ->
            tools++; assertEquals("get_current_context", call.name); AgentModelClient.ToolResult("{\"ok\":true,\"context\":\"verified\"}")
        }, provider = PackageAiAgentProvider(context, invoker), onEvent = events::add)
        assertEquals("Tool result verified", response.content); assertEquals(1, tools); assertEquals(2, callbacks)
        assertEquals(true, events.filterIsInstance<AgentEvent.ToolFinished>().single().success)
        val denied = PackageAiInvoker(context, PackageHookRunner { _, _, _, _ -> JSONObject().put("ok", true).put("value",
            JSONObject().put("text", "").put("toolCalls", JSONArray().put(JSONObject().put("id", "forged").put("name", "unpublished_tool").put("arguments", JSONObject())))) })
        assertTrue(runCatching { PackageAiAgentProvider(context, denied).complete(ProviderRequest(cfg,
            JSONArray(), JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "get_current_context").put("parameters", JSONObject())))), AgentRunController()) }.isFailure)
    }

    @Test fun unverifiedPluginUsageDoesNotBecomeFreeAndLimitRejectionHappensBeforeAnHttpRequest() = runBlocking {
        val (_, provider) = install()
        val cfg = config(provider)
        val invoker = PackageAiInvoker(context, PackageHookRunner { _, _, _, _ -> JSONObject().put("ok", true)
            .put("value", JSONObject().put("text", "Reply without server receipt").put("usage", JSONObject().put("input", 0).put("output", 0))) })
        NexusExecutionStore(context).use { store ->
            val run = RunRequest(RunId("unknown-plugin-price"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"), budget = RunBudget(hardRounds = 3, costLimitMicros = 10_000))
            val recorder = NexusExecutionRecorder(store, run); val owner = AgentRunController(); recorder.bindController(owner); recorder.start()
            RunModelBudgetCoordinator(store, { AgentTokenPrice.create("USD", "1", "1", "0", "0", now = 0) }) { _, failure -> owner.abort(failure) }.bind(owner, run.runId)
            val client = BudgetedAgentProvider.wrap(PackageAiAgentProvider(context, invoker))
            val request = ProviderRequest(cfg, JSONArray(), JSONArray())
            client.complete(request, owner) { }
            assertEquals(1L, store.modelBudgetTotals(run.runId).unknownCharges)
            val failure = runCatching { client.complete(request, owner) { } }.exceptionOrNull() as AgentModelFailure
            assertEquals("MODEL_COST_UNVERIFIED", failure.code)
            recorder.finish(AgentRuntimeWire.RunResult("unknown-plugin-price", false, "", "unverified"), false)
        }
        val http = PackageProviderHttp(cfg.copy(baseUrl = "https://example.com/custom"), AgentRunController(),
            ProviderRequest(cfg, JSONArray(), JSONArray(), budgetOutputTokenCeiling = 128), {})
        assertTrue(runCatching { http.start(body(cfg).put("url", "https://example.com/custom")) }.isFailure)
        http.close()
    }

    @Test fun terminalSseMarkerEndsTheProviderWithoutWaitingForTheServerToCloseAndCancellationInterruptsBodyRead() = runBlocking {
        val release = CountDownLatch(1); val blocked = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            createContext("/done") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0)
                try { exchange.responseBody.write("data: [DONE]\n\n".toByteArray()); exchange.responseBody.flush(); release.await(5, TimeUnit.SECONDS) }
                finally { exchange.close() }
            }
            createContext("/blocked") { exchange ->
                exchange.responseHeaders.set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0)
                try { exchange.responseBody.write('d'.code); exchange.responseBody.flush(); blocked.countDown(); release.await(5, TimeUnit.SECONDS) }
                finally { exchange.close() }
            }; start()
        }
        try {
            val (_, provider) = install("http://127.0.0.1:${server.address.port}/done")
            val cfg = config(provider); val owner = AgentRunController()
            PackageProviderHttp(cfg, owner, ProviderRequest(cfg, JSONArray(), JSONArray()), {}).use { http ->
                http.start(body(cfg))
                assertEquals("data: [DONE]\n\n", http.next().getString("chunk"))
                assertTrue(withTimeout(1500) { withContext(Dispatchers.IO) { http.next().getBoolean("done") } })
            }
            val blockedConfig = cfg.copy(baseUrl = "http://127.0.0.1:${server.address.port}/blocked")
            PackageProviderHttp(blockedConfig, owner, ProviderRequest(blockedConfig, JSONArray(), JSONArray()), {}).use { http ->
                val task = async(Dispatchers.IO) { runCatching {
                    http.start(body(blockedConfig)); while (!http.next().getBoolean("done")) { }
                } }
                try { assertTrue(blocked.await(3000, TimeUnit.MILLISECONDS)); owner.cancel(); assertTrue(withTimeout(2000) { task.await() }.isFailure) }
                finally { owner.cancel(); task.cancelAndJoin() }
            }
        } finally { release.countDown(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    @Test fun declaredConnectionProbeAndTokenEstimateCallbacksHaveRealConsumersAndEstimatesCannotStartHttp() = runBlocking {
        val (_, provider) = install()
        val methods = mutableListOf<String>()
        val invoker = PackageAiInvoker(context, PackageHookRunner { file, _, _, host ->
            val event = JSONObject(file.readText()).getJSONObject("arguments").getJSONObject("event")
            val name = event.getString("event")
            methods += name
            val result = if (name.endsWith("calculate_input_tokens")) {
                assertTrue(runCatching { host("package:provider-http-start", body(config(provider))) }.isFailure)
                JSONObject().put("tokens", 41)
            } else {
                assertTrue(name.endsWith("test_connection"))
                JSONObject().put("success", true).put("message", "Controlled connection result")
            }
            JSONObject().put("ok", true).put("value", result)
        })
        assertEquals("Controlled connection result", PackageAiCatalog(context, invoker).test(provider, AgentRunController()))
        val client = BudgetedAgentProvider.wrap(PackageAiAgentProvider(context, invoker))
        assertEquals(41, client.estimateInputTokens(ProviderRequest(config(provider), JSONArray().put(JSONObject().put("role", "user").put("content", "estimate input")), JSONArray()), AgentRunController()))
        assertEquals(listOf("toolpkg_ai_provider_test_connection", "toolpkg_ai_provider_calculate_input_tokens"), methods)
    }
}
