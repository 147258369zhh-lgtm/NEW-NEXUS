package io.github.mangi.eta.agent.model

import org.junit.Assert.assertSame
import org.junit.Test
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes

class LiteRtLmProviderRoutingTest {
    @Test
    fun `reserved built-in provider ids select their exact in-process engine`() {
        val builtIn = config(LocalModelRepository.BUILTIN_PROVIDER_ID)
        val gguf = config(LocalModelRepository.BUILTIN_GGUF_PROVIDER_ID)
        val mnn = config(LocalModelRepository.BUILTIN_MNN_PROVIDER_ID)
        val custom = config("eta-local-model-runtime-deadbeef")

        assertSame(LiteRtLmAgentProvider, ProviderClientFactory.getClient(builtIn))
        assertSame(LlamaCppAgentProvider, ProviderClientFactory.getClient(gguf))
        assertSame(MnnAgentProvider, ProviderClientFactory.getClient(mnn))
        assertSame(OpenAiChatCompletionsProvider, ProviderClientFactory.getClient(custom))
    }

    @Test
    fun `reserved ids win before oauth and protocol routing`() {
        // Chats, meeting rooms and sub-agents all resolve their provider through this factory,
        // so a reserved id must never fall through to a hosted protocol or OAuth profile.
        val builtIn = config(LocalModelRepository.BUILTIN_PROVIDER_ID).copy(
            providerType = ProviderTypes.ANTHROPIC,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        val gguf = config(LocalModelRepository.BUILTIN_GGUF_PROVIDER_ID).copy(
            providerType = ProviderTypes.ANTHROPIC,
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )

        assertSame(LiteRtLmAgentProvider, ProviderClientFactory.getClient(builtIn))
        assertSame(LlamaCppAgentProvider, ProviderClientFactory.getClient(gguf))
    }

    private fun config(providerId: String) = AgentModelClient.ModelConfig(
        providerId = providerId,
        providerName = "Local",
        baseUrl = "http://127.0.0.1:9379/v1",
        apiKey = "",
        model = "lm-test",
        systemPrompt = "",
    )
}
