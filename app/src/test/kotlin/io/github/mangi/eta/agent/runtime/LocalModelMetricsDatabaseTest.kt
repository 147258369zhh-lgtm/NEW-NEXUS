package io.github.mangi.eta.agent.runtime

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class LocalModelMetricsDatabaseTest {
    private val database = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(),
        LocalModelMetricsDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun close() = database.close()

    @Test fun cacheAndKvAggregatesUseOnlyReportedSamples() = runBlocking {
        val dao = database.dao()
        dao.put(entity("first", backend = "GPU", prompt = 1_000, cacheHit = 800, kvUsed = 1_024))
        dao.put(entity("second", backend = "CPU", prompt = 500, cacheHit = null, kvUsed = 2_048))

        val samples = dao.samplesFlow(null, 10).first()
        val aggregate = requireNotNull(dao.aggregateFlow(null).first())

        assertEquals(2, samples.size)
        assertEquals(2L, aggregate.sampleCount)
        assertEquals(800L, aggregate.totalCacheHitTokens)
        assertEquals(1_500L, aggregate.totalPromptTokens)
        assertEquals(3_072L, aggregate.totalKvUsedTokens)
        assertEquals(4_096L, aggregate.totalKvCapacityTokens)
    }

    private fun entity(
        id: String,
        backend: String,
        prompt: Int,
        cacheHit: Int?,
        kvUsed: Int,
    ) = LocalModelMetricEntity(
        sampleId = id,
        runId = "run",
        requestId = id,
        providerId = "local",
        modelId = "model",
        modelFormat = "GGUF",
        backend = backend,
        backendDetail = null,
        loadMs = 100,
        firstTokenMs = 50,
        promptTokens = prompt,
        generatedTokens = 20,
        tokensPerSecond = 10.0,
        promptCacheHitTokens = cacheHit,
        promptCacheReusedTokens = cacheHit,
        kvCacheUsedTokens = kvUsed,
        kvCacheCapacityTokens = 2_048,
        contextWindow = 2_048,
        prefillMs = 40,
        decodeMs = 2_000,
        totalMs = 2_040,
        finishReason = "stop",
        failureCode = null,
        startedAt = 1,
        completedAt = 2,
    )
}
