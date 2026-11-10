package io.github.mangi.eta.agent.runtime

import androidx.room.Room
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class LocalModelMetricsCheckpointTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var agentDatabase: AgentMetricsDatabase

    @Before fun setUp() {
        AgentMetricsDatabase.closeForTests()
        LocalModelMetricsDatabase.closeForTests()
        context.deleteDatabase(LocalModelMetricsDatabase.NAME)
        runBlocking { LocalModelMetricsStore.clear(context) }
        agentDatabase = Room.inMemoryDatabaseBuilder(context, AgentMetricsDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        agentDatabase.close()
        AgentMetricsDatabase.closeForTests()
        LocalModelMetricsDatabase.closeForTests()
        context.deleteDatabase(LocalModelMetricsDatabase.NAME)
    }

    @Test fun repeatedFailureCheckpointsUpsertOneRequestSample() = runBlocking {
        var clock = 0L
        val metrics = LocalRuntimeMetrics(
            backend = LocalRuntimeBackend.CPU,
            modelFormat = "LITERTLM",
            modelId = "local-model",
            firstTokenMs = 120,
            promptTokens = 300,
            generatedTokens = 12,
            contextWindow = 4_096,
            finishReason = "failure",
            failureCode = "GENERATION_FAILED",
            startedAt = 100,
            completedAt = 500,
        )
        val telemetry = AgentRunTelemetry(
            AgentRuntimeWire.RunRequest(
                runId = "failed-run",
                prompt = "private",
                images = emptyList(),
                config = AgentModelClient.ModelConfig(
                    baseUrl = "local://embedded",
                    apiKey = "",
                    model = "local-model",
                    providerId = "litert_lm_builtin",
                    systemPrompt = "",
                ),
            ),
            wallClock = { 1_000 + clock },
            monotonicClock = { clock },
        )
        telemetry.accept(AgentEvent.ProviderRequestStarted(1))
        telemetry.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(
            inputTokens = 300,
            outputTokens = 12,
            localRuntime = metrics,
        )))
        telemetry.accept(AgentEvent.RunFailed("failed"))
        val first = telemetry.finish(false)
        val laterCheckpoint = first.copy(revision = first.revision + 1, updatedAt = first.updatedAt + 1)

        val samples = withContext(Dispatchers.IO) {
            // AgentRunMetricsStore.save is invoked by its dedicated writer thread in production.
            AgentRunMetricsStore.save(context, first, agentDatabase)
            AgentRunMetricsStore.save(context, laterCheckpoint, agentDatabase)
            LocalModelMetricsStore.samples(context).first()
        }

        assertEquals(
            "request=${first.requests.singleOrNull()}; samples=${samples.map { "${it.runId}:${it.requestId}" }}",
            1,
            samples.size,
        )
        assertEquals(
            "samples=${samples.map { it.metrics }}",
            "GENERATION_FAILED",
            samples.single().metrics.failureCode,
        )
        assertEquals(
            "samples=${samples.map { it.metrics }}",
            12,
            samples.single().metrics.generatedTokens ?: -1,
        )
    }
}
