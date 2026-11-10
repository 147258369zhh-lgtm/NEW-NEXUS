package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import io.github.mangi.eta.data.repository.LocalModelRuntime
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModelStartupCoordinatorTest {
    @Test
    fun `resumes only selected built in model with automatic flags`() {
        val selected = record()
        val other = record(
            id = "other",
            provider = "eta-local-model-runtime-gguf",
            selection = "other-selection",
            format = LocalModelFormat.GGUF,
        )

        assertEquals(
            selected,
            LocalModelStartupCoordinator.chooseRecord(
                providerId = "eta-local-model-runtime-builtin",
                modelId = "selection",
                models = listOf(other, selected),
            ),
        )
    }

    @Test
    fun `does not start a model the user did not select`() {
        assertNull(
            LocalModelStartupCoordinator.chooseRecord(
                providerId = "eta-local-model-runtime-builtin",
                modelId = "another-model",
                models = listOf(record()),
            ),
        )
    }

    @Test
    fun `does not auto start when either preference is disabled`() {
        assertNull(
            LocalModelStartupCoordinator.chooseRecord(
                providerId = "eta-local-model-runtime-builtin",
                modelId = "selection",
                models = listOf(
                    record(runtimeSettings = LocalModelRuntimeSettings(autoStart = false)),
                ),
            ),
        )
    }

    private fun record(
        id: String = "record",
        provider: String = "eta-local-model-runtime-builtin",
        selection: String = "selection",
        format: LocalModelFormat = LocalModelFormat.LITERT_LM,
        runtimeSettings: LocalModelRuntimeSettings = LocalModelRuntimeSettings(),
    ) = LocalModelRecord(
        id = id,
        displayName = "Local model",
        fileName = if (format == LocalModelFormat.GGUF) "model.gguf" else "model.litertlm",
        format = format,
        sizeBytes = 343_000_000L,
        sha256 = "sha-$id",
        sourceUrl = null,
        installedAt = 1L,
        license = "Apache-2.0",
        contextWindow = runtimeSettings.contextWindow,
        boundProviderId = provider,
        boundModelSelectionId = selection,
        runtime = LocalModelRuntime.BUILT_IN_PENDING,
        runtimeSettings = runtimeSettings,
    )
}
