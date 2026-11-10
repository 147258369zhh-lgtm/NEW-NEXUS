package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import io.github.mangi.eta.data.repository.LocalRuntimeBackendPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelNativeRequestPolicyTest {
    @Test
    fun `only allocation settings force a native engine reload`() {
        val loaded = settings()

        assertFalse(loaded.requiresNativeEngineReload(
            loaded.copy(
                temperature = 0.2f,
                topK = 20,
                topP = 0.8f,
                maxOutputTokens = 512,
                thinkingEnabled = true,
                idleUnloadMinutes = 30,
            ),
        ))
        assertTrue(loaded.requiresNativeEngineReload(loaded.copy(backend = LocalRuntimeBackendPolicy.CPU)))
        assertTrue(loaded.requiresNativeEngineReload(loaded.copy(threads = 6)))
        assertTrue(loaded.requiresNativeEngineReload(loaded.copy(contextWindow = 4_096)))
        assertTrue(loaded.requiresNativeEngineReload(loaded.copy(imageInputEnabled = true)))
    }

    @Test
    fun `hot settings stay inside an already allocated context and backend`() {
        val loaded = settings().copy(
            backend = LocalRuntimeBackendPolicy.CPU,
            threads = 4,
            contextWindow = 4_096,
        )
        val requested = loaded.copy(
            backend = LocalRuntimeBackendPolicy.GPU,
            threads = 8,
            contextWindow = 32_768,
            temperature = 0.25f,
            topK = 24,
            topP = 0.82f,
            maxOutputTokens = 768,
            thinkingEnabled = true,
        )

        val effective = requested.withLoadedNativeConstraints(loaded)

        assertTrue(effective.backend == LocalRuntimeBackendPolicy.CPU)
        assertTrue(effective.threads == 4)
        assertTrue(effective.contextWindow == 4_096)
        assertTrue(effective.temperature == 0.25f)
        assertTrue(effective.topK == 24)
        assertTrue(effective.topP == 0.82f)
        assertTrue(effective.maxOutputTokens == 768)
        assertTrue(effective.thinkingEnabled)
    }

    @Test
    fun `llama cpp maps every editable sampling output and thinking setting`() {
        val arguments = settings().copy(
            temperature = 0.33f,
            topK = 27,
            topP = 0.81f,
            maxOutputTokens = 777,
            thinkingEnabled = true,
        ).llamaCppGenerationArguments()

        assertArrayEquals(
            floatArrayOf(0.33f, 0.81f, 0.05f, 1.1f, 0f, 0f),
            arguments.floats,
            0f,
        )
        assertArrayEquals(
            intArrayOf(27, 64, 777, -1, 0, 1, 1, 256),
            arguments.ints,
        )

        val thinkingDisabled = argumentsFor(thinking = false)
        assertArrayEquals(
            intArrayOf(27, 64, 777, -1, 1, 1, 1, 256),
            thinkingDisabled.ints,
        )
    }

    @Test
    fun `mnn maps every editable sampling output and thinking setting`() {
        val arguments = settings().copy(
            temperature = 0.42f,
            topK = 31,
            topP = 0.86f,
            maxOutputTokens = 888,
            thinkingEnabled = true,
        ).mnnGenerationArguments()

        assertArrayEquals(floatArrayOf(0.42f, 0.86f, 0.05f, 1.1f), arguments.floats, 0f)
        assertArrayEquals(intArrayOf(31, 888, 0, 0, 0), arguments.ints)

        val thinkingDisabled = settings().copy(
            temperature = 0.42f,
            topK = 31,
            topP = 0.86f,
            maxOutputTokens = 888,
            thinkingEnabled = false,
        ).mnnGenerationArguments()
        assertArrayEquals(intArrayOf(31, 888, 1, 0, 0), thinkingDisabled.ints)
    }

    @Test
    fun `lite runtime output budget leaves room for the current prompt`() {
        assertEquals(1_024, settings().liteRtOutputBudget())
        assertEquals(512, settings().copy(contextWindow = 2_048).liteRtOutputBudget())
        assertEquals(
            256,
            settings().copy(contextWindow = 1_024, maxOutputTokens = 1_024).liteRtOutputBudget(),
        )
        assertEquals(
            128,
            settings().copy(contextWindow = 512, maxOutputTokens = 1_024).liteRtOutputBudget(),
        )
    }

    @Test
    fun `all local engines cap configured output to one quarter of context`() {
        val constrained = settings().copy(contextWindow = 2_048, maxOutputTokens = 8_192)

        assertEquals(512, constrained.generationOutputBudget())
        assertEquals(512, constrained.liteRtOutputBudget())
        assertEquals(512, constrained.llamaCppGenerationArguments().ints[2])
        assertEquals(512, constrained.mnnGenerationArguments().ints[1])
    }

    private fun settings() = LocalModelRuntimeSettings(
        backend = LocalRuntimeBackendPolicy.AUTO,
        threads = 4,
        contextWindow = 8_192,
        maxOutputTokens = 1_024,
        temperature = 0.7f,
        topK = 40,
        topP = 0.95f,
    )

    private fun argumentsFor(thinking: Boolean): NativeGenerationArguments =
        settings().copy(
            temperature = 0.33f,
            topK = 27,
            topP = 0.81f,
            maxOutputTokens = 777,
            thinkingEnabled = thinking,
        ).llamaCppGenerationArguments()
}
