package io.github.mangi.eta.ui

import android.app.Application
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProviderSettingsLoadStateTest {
    @Test fun loadingAndEmptyAreDistinctStatesAndProviderFailuresStayVisible() = runBlocking {
        val empty = providerSettingsLoadState(flowOf(emptyList())).toList().single()
        val failed = providerSettingsLoadState(flow<List<ProviderSetting>> {
            throw IllegalStateException("database unavailable")
        }).toList().single()
        val populated = providerSettingsLoadState(flowOf(listOf(OpenAiCompatibleProviderSetting(
            id = "provider", name = "Provider", baseUrl = "https://example.invalid",
        )))).toList().single()

        assertEquals(ProviderSettingsLoadState.Loaded(emptyList()), empty)
        assertEquals(ProviderSettingsLoadState.Failed, failed)
        assertEquals("provider", (populated as ProviderSettingsLoadState.Loaded).providers.single().id)
    }
}
