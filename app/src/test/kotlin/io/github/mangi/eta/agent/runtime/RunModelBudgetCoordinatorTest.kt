package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunModelBudgetCoordinatorTest {
    @Test fun exhaustedModelRequestAllowanceCanBeExtendedTwiceWithoutResettingUsageOrRunIdentity() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val run = RunRequest(RunId("renewable-model-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"),
                budget = RunBudget(hardRounds = 1))
            val controller = AgentRunController()
            val recorder = NexusExecutionRecorder(store, run)
            recorder.bindController(controller); recorder.start()
            RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run.runId)
            val client = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted,
                ProviderEvent.Usage(AgentTokenUsage(inputTokens = 1, outputTokens = 1, billingBasis = AgentBillingTokens.OPENAI)))))
            client.complete(request, controller) {}
            repeat(2) {
                val limit = store.find(run.runId)!!.request.budget.hardRounds!!
                while (store.modelBudgetTotals(run.runId).requests < limit) client.complete(request, controller) {}
                val before = store.modelBudgetTotals(run.runId)
                val pendingRequest = async(Dispatchers.Default) { client.complete(request, controller) {} }
                try {
                    val confirmation = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertTrue(confirmation.capabilityId.startsWith("run_budget:extend:"))
                    assertEquals(before.requests, store.modelBudgetTotals(run.runId).requests)
                    EtaAppControlConfirmation.approve(confirmation.id)
                    withTimeout(3_000) { pendingRequest.await() }
                    assertEquals(before.requests + 1, store.modelBudgetTotals(run.runId).requests)
                    assertEquals((before.requests + 1) * 3, store.modelBudgetTotals(run.runId).knownCostMicros)
                    assertEquals(run.runId, store.find(run.runId)!!.request.runId)
                } finally { pendingRequest.cancelAndJoin() }
            }
            assertEquals(2, store.timeline(run.runId).count { it.event.type == RunEventType.BUDGET_INCREASED })
            recorder.finish(AgentRuntimeWire.RunResult("renewable-model-run", true, "done"), false)
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val config = AgentModelClient.ModelConfig(providerId = "fixture", baseUrl = "https://fixture.invalid", apiKey = "PRIVATE_KEY",
        model = "fixture-model", systemPrompt = "fixture", contextWindow = 128, autoCompactionEnabled = false)
    private val request get() = ProviderRequest(config, JSONArray().put(JSONObject().put("role", "user").put("content", "PRIVATE_PROMPT")), JSONArray())
    private fun start(store: NexusExecutionStore, budget: RunBudget): RunId {
        val run = RunRequest(RunId("priced-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"), budget = budget)
        NexusExecutionRecorder(store, run).start()
        return run.runId
    }
    private fun price(currency: String = "USD") = AgentTokenPrice.create(currency, "1", "2", "0.1", "0.2", now = 0)
    private fun provider(events: List<ProviderEvent>, id: String = "fixture") = object : AgentProviderClient {
        override val id = id
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            events.forEach(onEvent)
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", "fixture reply").put("finish_reason", "stop"))
        }
    }

    @Test fun streamedUsageIsMergedAndChargedOnceUsingTheFrozenModelPrice() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 3, costLimitMicros = 1_000))
            val controller = AgentRunController(); val frozen = price()
            var resolutions = 0
            RunModelBudgetCoordinator(store, { resolutions++; frozen }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val client = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted,
                ProviderEvent.Usage(AgentTokenUsage(inputTokens = 100, billingBasis = AgentBillingTokens.OPENAI)),
                ProviderEvent.Usage(AgentTokenUsage(outputTokens = 10, billingBasis = AgentBillingTokens.OPENAI)))))
            client.complete(request, controller) {}
            assertEquals(120L, store.modelBudgetTotals(run).knownCostMicros)
            val record = store.modelBudgetRequests(run).single()
            assertEquals(frozen.revision, record.request.priceRevision)
            assertEquals(100L, record.settlement?.inputTokens)
            assertEquals(10L, record.settlement?.outputTokens)
            assertEquals(1, resolutions)
            assertFalse(store.modelBudgetRequests(run).toString().contains("PRIVATE_"))
        }
    }

    @Test fun compatibilityRetriesConsumeSeparateSlotsBeforeTheirTransportCanProceed() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 2))
            val controller = AgentRunController()
            RunModelBudgetCoordinator(store, { null }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val client = BudgetedAgentProvider.wrap(provider(List(3) { ProviderEvent.RequestStarted }))
            val failure = assertThrows(AgentModelFailure::class.java) { client.complete(request, controller) {} }
            assertEquals("RUN_TOTAL_MODEL_REQUESTS_EXCEEDED", failure.code)
            assertEquals(2L, store.modelBudgetTotals(run).requests)
            assertEquals(0L, store.modelBudgetTotals(run).openRequests)
            assertEquals(2L, store.modelBudgetTotals(run).unknownCharges)
        }
    }

    @Test fun missingUsagePreventsTheNextPricedRequestAndEmbeddedModelsRemainZeroCost() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 4, costLimitMicros = 1_000))
            val controller = AgentRunController()
            RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val local = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted), "llama_cpp_gguf_builtin"))
            local.complete(request, controller) {}
            assertEquals(0L, store.modelBudgetTotals(run).knownCostMicros)
            assertEquals(0L, store.modelBudgetTotals(run).unknownCharges)
            val remote = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted)))
            remote.complete(request, controller) {}
            val denied = assertThrows(AgentModelFailure::class.java) { remote.complete(request, controller) {} }
            assertEquals("MODEL_COST_UNVERIFIED", denied.code)
            assertEquals(2L, store.modelBudgetTotals(run).requests)
        }
    }

    @Test fun pricedParallelRequestsWaitForSettlementAndCancellationReleasesTheirWait() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 4, costLimitMicros = 1_000))
            val first = AgentRunController(); val second = AgentRunController()
            val coordinator = RunModelBudgetCoordinator(store, { price() }) { _, failure -> first.abort(failure); second.abort(failure) }
            coordinator.bind(first, run); coordinator.bind(second, run)
            val ticket = first.modelBudget!!.reserve(request, false)
            val waiting = async(Dispatchers.Default) { runCatching { second.modelBudget!!.reserve(request, false) } }
            try {
                delay(80)
                assertFalse(waiting.isCompleted)
                second.cancel()
                assertTrue(withTimeout(2_000) { waiting.await() }.isFailure)
                assertEquals(1L, store.modelBudgetTotals(run).requests)
                first.modelBudget!!.settle(ticket, AgentTokenUsage(inputTokens = 1, outputTokens = 1, billingBasis = AgentBillingTokens.OPENAI), true)
                assertEquals(3L, store.modelBudgetTotals(run).knownCostMicros)
            } finally { second.cancel(); waiting.cancelAndJoin() }
        }
    }

    @Test fun missingPriceStopsBeforeEnteringTheProvider() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 4, costLimitMicros = 100))
            val controller = AgentRunController()
            RunModelBudgetCoordinator(store, { null }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            var entered = false
            val delegate = object : AgentProviderClient by provider(emptyList()) {
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    entered = true; error("must not send")
                }
            }
            val error = assertThrows(AgentModelFailure::class.java) { BudgetedAgentProvider.wrap(delegate).complete(request, controller) {} }
            assertEquals("MODEL_COST_PRICE_REQUIRED", error.code)
            assertFalse(entered)
            assertEquals(0L, store.modelBudgetTotals(run).requests)
        }
    }

    @Test fun compactionAndFallbackUseTheSameRequestBudgetAndPrices() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 2, costLimitMicros = 100_000))
            val controller = AgentRunController()
            RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val delegate = provider(listOf(ProviderEvent.RequestStarted,
                ProviderEvent.Usage(AgentTokenUsage(inputTokens = 1, outputTokens = 1, billingBasis = AgentBillingTokens.OPENAI))))
            val summary = AgentContextSummarizer(config.copy(contextWindow = 4_096), delegate, controller, false).summarize(
                listOf(AgentModelClient.ConversationMessage("user", "fixture history")))
            assertEquals("fixture reply", summary)
            val wrapped = BudgetedAgentProvider.wrap(delegate)
            wrapped.complete(request.copy(config = config.copy(model = "backup-model"), purpose = ProviderRequestPurpose.FALLBACK), controller) {}
            val records = store.modelBudgetRequests(run)
            assertEquals(listOf("COMPACTION", "FALLBACK"), records.map { it.request.purpose })
            assertEquals("backup-model", records.last().request.modelId)
            assertEquals(6L, store.modelBudgetTotals(run).knownCostMicros)
            assertEquals("RUN_TOTAL_MODEL_REQUESTS_EXCEEDED", assertThrows(AgentModelFailure::class.java) {
                wrapped.complete(request, controller) {}
            }.code)
        }
    }

    @Test fun roundingNeverUnderstatesFractionalMicroCostsAndPricesRemainFrozenAcrossRequests() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 3))
            val controller = AgentRunController()
            var nextPrice = AgentTokenPrice.create("USD", "0.1", "0.1", "0.1", "0.1", now = 0)
            var resolved = 0
            RunModelBudgetCoordinator(store, { resolved++; nextPrice }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val client = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted,
                ProviderEvent.Usage(AgentTokenUsage(inputTokens = 1, outputTokens = 0, billingBasis = AgentBillingTokens.OPENAI)))))
            client.complete(request, controller) {}
            nextPrice = price()
            client.complete(request, controller) {}
            assertEquals(1, resolved)
            assertEquals(2L, store.modelBudgetTotals(run).knownCostMicros)
            assertTrue(store.modelBudgetRequests(run).all { it.request.inputRate == "0.1" })
        }
    }

    @Test fun insufficientReservationAndMissingWindowNeverEnterTheProvider() {
        for ((window, code) in listOf(128 to "RUN_COST_RESERVATION_EXCEEDED", null to "MODEL_COST_RESERVATION_REQUIRED")) {
            NexusExecutionStore(context).use { store ->
                val run = RunRequest(RunId("bounded-$code"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"),
                    budget = RunBudget(hardRounds = 4, costLimitMicros = 100))
                NexusExecutionRecorder(store, run).start()
                val controller = AgentRunController()
                RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run.runId)
                var entered = false
                val delegate = object : AgentProviderClient by provider(emptyList()) {
                    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                        entered = true; error("must not send")
                    }
                }
                val failure = assertThrows(AgentModelFailure::class.java) {
                    BudgetedAgentProvider.wrap(delegate).complete(request.copy(config = config.copy(contextWindow = window)), controller) {}
                }
                assertEquals(code, failure.code)
                assertFalse(entered)
                assertEquals(0L, store.modelBudgetTotals(run.runId).requests)
            }
        }
    }

    @Test fun providerOverrunPreservesReportedCostBeforeStoppingTheFamily() {
        NexusExecutionStore(context).use { store ->
            val run = start(store, RunBudget(hardRounds = 4, costLimitMicros = 1_000))
            val controller = AgentRunController()
            RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run)
            val client = BudgetedAgentProvider.wrap(provider(listOf(ProviderEvent.RequestStarted,
                ProviderEvent.Usage(AgentTokenUsage(inputTokens = 2_000, outputTokens = 1, billingBasis = AgentBillingTokens.OPENAI)))))
            val failure = assertThrows(AgentModelFailure::class.java) { client.complete(request, controller) {} }
            assertEquals("MODEL_COST_RESERVATION_OVERRUN", failure.code)
            assertEquals(2_002L, store.modelBudgetTotals(run).knownCostMicros)
            assertEquals(0L, store.modelBudgetTotals(run).reservedCostMicros)
            assertEquals(0L, store.modelBudgetTotals(run).openRequests)
            assertTrue(controller.isCancelled)
        }
    }

    @Test fun boundedRealHttpRetryKeepsItsOutputLimitAndSettlesRejectedParametersAtZero() {
        val bodies = java.util.Collections.synchronizedList(mutableListOf<JSONObject>())
        val workers = java.util.concurrent.Executors.newSingleThreadExecutor()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/chat/completions") { exchange ->
            bodies += JSONObject(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
            val rejected = bodies.size == 1
            val body = if (rejected) """{"error":{"type":"invalid_request_error","message":"stream_options is not supported"}}"""
                else """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":2}}"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (rejected) 400 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            NexusExecutionStore(context).use { store ->
                val run = start(store, RunBudget(hardRounds = 4, costLimitMicros = 1_000))
                val controller = AgentRunController()
                RunModelBudgetCoordinator(store, { price() }) { _, failure -> controller.abort(failure) }.bind(controller, run)
                val result = BudgetedAgentProvider.wrap(OpenAiChatCompletionsProvider).complete(
                    request.copy(config = config.copy(baseUrl = "http://127.0.0.1:${server.address.port}")), controller) {}
                assertEquals("ok", result.assistantMessage.getString("content"))
                assertEquals(2, bodies.size)
                assertTrue(bodies.all { it.getLong("max_tokens") == 128L })
                assertEquals(listOf(0L, 14L), store.modelBudgetRequests(run).map { it.settlement?.costMicros })
                assertEquals(14L, store.modelBudgetTotals(run).knownCostMicros)
                assertEquals(0L, store.modelBudgetTotals(run).openRequests)
            }
        } finally { server.stop(0); workers.shutdownNow() }
    }
}
