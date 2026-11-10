package fuck.andes.agent.model

import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.ProviderSourceRegistry
import org.json.JSONObject

internal object ProviderReasoning {
    fun applyOpenAiCompatibleRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        when (sourceType(config)) {
            ProviderSourceTypes.BAILIAN,
            ProviderSourceTypes.SILICONFLOW -> {
                request.put("enable_thinking", config.thinkingEnabled)
            }

            ProviderSourceTypes.DEEPSEEK -> {
                request.put(
                    "thinking",
                    JSONObject().put("type", if (config.thinkingEnabled) "enabled" else "disabled")
                )
                if (config.thinkingEnabled) {
                    request.put("reasoning_effort", "high")
                }
            }

            ProviderSourceTypes.MOONSHOT -> {
                applyMoonshotThinking(request, config)
            }

            ProviderSourceTypes.MIMO -> {
                request.put(
                    "thinking",
                    JSONObject().put("type", if (config.thinkingEnabled) "enabled" else "disabled")
                )
            }

            ProviderSourceTypes.MINIMAX -> {
                applyMiniMaxThinking(request, config)
            }

            ProviderSourceTypes.OPENROUTER -> {
                request.put(
                    "reasoning",
                    JSONObject().put("effort", if (config.thinkingEnabled) "high" else "none")
                )
            }

            ProviderSourceTypes.OPENAI,
            ProviderSourceTypes.STEPFUN,
            ProviderSourceTypes.CUSTOM -> {
                applyReasoningEffort(request, config)
            }
        }
    }

    fun applyAnthropicRequest(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        if (!config.thinkingEnabled) return
        request.put(
            "thinking",
            JSONObject()
                .put("type", "adaptive")
                .put("display", "summarized")
        )
        request.put(
            "output_config",
            JSONObject().put("effort", "medium")
        )
    }

    private fun applyMoonshotThinking(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        val model = config.model.trim().lowercase()
        if (model.startsWith("kimi-k2.7-code")) {
            return
        }
        val thinking = JSONObject()
            .put("type", if (config.thinkingEnabled) "enabled" else "disabled")
        if (config.thinkingEnabled && model.startsWith("kimi-k2.6")) {
            thinking.put("keep", "all")
        }
        request.put("thinking", thinking)
    }

    private fun applyMiniMaxThinking(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        val model = config.model.trim().lowercase()
        when {
            model.startsWith("minimax-m3") -> {
                request.put(
                    "thinking",
                    JSONObject().put("type", if (config.thinkingEnabled) "adaptive" else "disabled")
                )
            }

            model.startsWith("minimax-m2") -> {
                if (config.thinkingEnabled) {
                    request.put("thinking", JSONObject().put("type", "adaptive"))
                }
            }

            else -> {
                request.put(
                    "thinking",
                    JSONObject().put("type", if (config.thinkingEnabled) "adaptive" else "disabled")
                )
            }
        }
    }

    private fun applyReasoningEffort(
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ) {
        if (config.thinkingEnabled) {
            request.put("reasoning_effort", "high")
            return
        }
        if (supportsNoneReasoningEffort(config.model)) {
            request.put("reasoning_effort", "none")
        }
    }

    private fun supportsNoneReasoningEffort(model: String): Boolean {
        val normalized = model.trim().lowercase()
        if (normalized.contains("pro")) return false
        return normalized.startsWith("gpt-5") || normalized.startsWith("o")
    }

    private fun sourceType(config: AgentModelClient.ModelConfig): String =
        ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
}
