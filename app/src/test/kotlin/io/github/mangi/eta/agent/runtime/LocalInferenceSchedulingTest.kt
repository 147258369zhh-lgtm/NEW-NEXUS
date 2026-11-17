package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.*
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LocalInferenceSchedulingTest {
    @Test fun localProviderWrapperPreemptsOnlyBackgroundConsolidationAndReleasesTheEngine() = runBlocking {
        val entered = CountDownLatch(1); val released = CountDownLatch(1); val calls = AtomicInteger()
        val provider = BudgetedAgentProvider.wrap(object : AgentProviderClient {
            override val id = "llama_cpp_gguf_builtin"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (calls.incrementAndGet() == 1) {
                    val binding = runController.register { released.countDown() }
                    try { entered.countDown(); check(released.await(3, TimeUnit.SECONDS)); runController.throwIfStopped() }
                    finally { binding.close() }
                }
                return ProviderResponse(JSONObject().put("content", "ready").put("finish_reason", "stop"))
            }
        })
        val background = controller("background", RunSource.NOTE_CONSOLIDATION)
        val foreground = controller("foreground", RunSource.CHAT)
        val request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "http://localhost", apiKey = "", model = "model", systemPrompt = ""),
            org.json.JSONArray(), org.json.JSONArray())
        val work = async(Dispatchers.IO) { runCatching { provider.complete(request, background) {} } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val reply = withContext(Dispatchers.IO) { provider.complete(request, foreground) {} }
            assertEquals("ready", reply.assistantMessage.getString("content"))
            assertEquals("RESOURCE_YIELD", (withTimeout(3000) { work.await() }.exceptionOrNull() as AgentModelFailure).code)
            assertFalse(foreground.isCancelled)
            assertEquals(2, calls.get())
            assertEquals(0, LocalInferenceScheduling.snapshot().getJSONArray("queued").length())
            assertFalse(LocalInferenceScheduling.snapshot().has("activeRunId"))
        } finally { background.cancel(); foreground.cancel(); work.cancelAndJoin() }
    }

    @Test fun pressureDefersMaintenanceWithoutEnteringNativeCodeButKeepsForegroundUsable() {
        for (pressure in listOf(ResourcePressure(lowMemory = true), ResourcePressure(batterySaver = true), ResourcePressure(thermalThrottled = true))) {
            val scheduler = LocalInferenceScheduler { pressure }
            val blocked = assertThrows(AgentModelFailure::class.java) {
                scheduler.execute(controller("background", RunSource.NOTE_CONSOLIDATION), WorkloadType.NIGHTLY_CONSOLIDATION) { fail("Native code must not run") }
            }
            assertEquals("RESOURCE_YIELD", blocked.code)
            assertEquals("ready", scheduler.execute(controller("foreground", RunSource.CHAT), WorkloadType.FOREGROUND_CHAT) { "ready" })
            assertEquals(0, scheduler.snapshot().getJSONArray("queued").length())
        }
    }

    @Test fun pressureChangesCancelBackgroundResourcesWithoutAUserCancellation() = runBlocking {
        var pressure = ResourcePressure()
        val scheduler = LocalInferenceScheduler { pressure }
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val background = controller("maintenance", RunSource.NOTE_CONSOLIDATION)
        val work = async(Dispatchers.IO) { runCatching {
            scheduler.execute(background, WorkloadType.NIGHTLY_CONSOLIDATION) {
                val binding = background.register { released.countDown() }
                try { entered.countDown(); check(released.await(3, TimeUnit.SECONDS)); background.throwIfStopped() }
                finally { binding.close() }
            }
        } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            pressure = ResourcePressure(lowMemory = true); scheduler.pressureChanged()
            assertTrue(withTimeout(3000) { work.await() }.exceptionOrNull() is AgentModelFailure)
            assertFalse(background.isUserCancelled)
        } finally { background.cancel(); work.cancelAndJoin() }
    }

    private fun controller(id: String, source: RunSource) = AgentRunController().also {
        it.bindExecutionIdentity(RunRequest(RunId(id), source, RunMode.TASK, HostId("native-android")))
    }

    @Test fun manualWarmupWaitsForRealProviderGenerationAndQueuedCancellationReleasesItsSlot() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val controller = controller("active", RunSource.CHAT)
        val provider = BudgetedAgentProvider.wrap(object : AgentProviderClient {
            override val id = "llama_cpp_gguf_builtin"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                entered.countDown(); check(release.await(3, TimeUnit.SECONDS))
                return ProviderResponse(JSONObject().put("content", "done"))
            }
        })
        val request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "http://localhost", apiKey = "", model = "active-model", systemPrompt = ""),
            org.json.JSONArray(), org.json.JSONArray())
        val generation = async(Dispatchers.IO) { provider.complete(request, controller) {} }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        var nativeEntered = false
        val warmup = async { LocalInferenceScheduling.warmUp("warming-model", automatic = false) { nativeEntered = true } }
        try {
            withTimeout(2_000) {
                while (LocalInferenceScheduling.snapshot().getJSONArray("queued").length() == 0) delay(5)
            }
            assertFalse(nativeEntered)
            assertEquals("active-model", LocalInferenceScheduling.snapshot().getString("activeModelId"))
            warmup.cancel()
            withTimeout(2_000) { warmup.join() }
            assertFalse(nativeEntered)
            assertEquals(0, LocalInferenceScheduling.snapshot().getJSONArray("queued").length())
            assertFalse(controller.isCancelled)
            release.countDown(); withTimeout(2_000) { generation.await() }
            assertEquals("ready", LocalInferenceScheduling.warmUp("next", automatic = false) { "ready" })
        } finally { release.countDown(); controller.cancel(); warmup.cancelAndJoin(); generation.cancelAndJoin() }
    }

    @Test fun foregroundRequestPreemptsAutomaticWarmupThroughItsActualCoroutineCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val warmup = async { runCatching {
            LocalInferenceScheduling.warmUp("warming-model", automatic = true) { entered.complete(Unit); awaitCancellation() }
        } }
        try {
            withTimeout(2_000) { entered.await() }
            val foreground = controller("foreground", RunSource.CHAT)
            val value = withContext(Dispatchers.IO) { LocalInferenceScheduling.execute(foreground, "chat-model") { "reply" } }
            assertEquals("reply", value)
            val failure = withTimeout(2_000) { warmup.await() }.exceptionOrNull()
            assertEquals("RESOURCE_YIELD", (failure as AgentModelFailure).code)
            assertFalse(foreground.isCancelled)
        } finally { warmup.cancelAndJoin() }
    }

    @Test fun stoppingOneModelCancelsActiveAndQueuedOwnersWithoutStoppingAnotherModel() = runBlocking {
        val scheduler = LocalInferenceScheduler()
        val active = controller("active", RunSource.CHAT)
        val queued = controller("queued", RunSource.CHAT)
        val other = controller("other", RunSource.CHAT)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val running = async(Dispatchers.IO) { runCatching { scheduler.execute(active, WorkloadType.FOREGROUND_CHAT, "one") {
            val binding = active.register { release.countDown() }
            try { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); active.throwIfStopped() }
            finally { binding.close() }
        } } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val waiting = async(Dispatchers.IO) { runCatching { scheduler.execute(queued, WorkloadType.FOREGROUND_CHAT, "one") { fail("Stopped queue must not execute") } } }
        val separate = async(Dispatchers.IO) { scheduler.execute(other, WorkloadType.FOREGROUND_CHAT, "two") { "other result" } }
        try {
            withTimeout(2_000) { while (scheduler.snapshot().getJSONArray("queued").length() < 2) delay(5) }
            scheduler.stopModel("one").use {
                assertTrue(withTimeout(2_000) { running.await() }.isFailure)
                assertTrue(withTimeout(2_000) { waiting.await() }.isFailure)
                assertEquals("other result", withTimeout(2_000) { separate.await() })
                assertFalse(other.isCancelled)
                assertThrows(IllegalStateException::class.java) {
                    scheduler.execute(controller("blocked", RunSource.CHAT), WorkloadType.FOREGROUND_CHAT, "one") { fail("Stop barrier must reject") }
                }
            }
            assertEquals("restarted", scheduler.execute(controller("restart", RunSource.CHAT), WorkloadType.FOREGROUND_CHAT, "one") { "restarted" })
        } finally { release.countDown(); active.cancel(); queued.cancel(); other.cancel(); running.cancelAndJoin(); waiting.cancelAndJoin(); separate.cancelAndJoin() }
    }
}
