package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.LocalModelRepository

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient {
        if (config.providerId == LocalModelRepository.BUILTIN_PROVIDER_ID) {
            return LiteRtLmAgentProvider
        }
        if (config.providerId == LocalModelRepository.BUILTIN_GGUF_PROVIDER_ID) {
            return LlamaCppAgentProvider
        }
        if (config.providerId == LocalModelRepository.BUILTIN_MNN_PROVIDER_ID) {
            return MnnAgentProvider
        }
        ProviderConnectionProfile.forAuthMode(config.authMode)?.let { profile ->
            return when (profile.endpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
        }
        return when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> when (config.openAiEndpointMode) {
                OpenAiEndpointMode.RESPONSES -> OpenAiResponsesProvider
                else -> OpenAiChatCompletionsProvider
            }
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            ProviderTypes.GEMINI -> GeminiInteractionsProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
    }
}
