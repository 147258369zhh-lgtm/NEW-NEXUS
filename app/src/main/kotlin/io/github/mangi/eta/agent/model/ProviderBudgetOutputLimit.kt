package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** Applied after custom fields and on every compatibility retry; a retry cannot remove the cap. */
internal object ProviderBudgetOutputLimit {
    fun isParameterRejection(status: Int, body: String): Boolean = status == 400 && runCatching {
        val error = JSONObject(body)
        !error.has("usage") && error.optJSONObject("error")?.optString("type") == "invalid_request_error"
    }.getOrDefault(false)

    fun apply(body: JSONObject, request: ProviderRequest, endpoint: EndpointKind): JSONObject {
        // The ChatGPT connection forbids output-cap fields. Its server owns the output reserve;
        // keep the input budget conservative without adding a parameter that rejects every call.
        val contextCeiling = request.contextOutputTokenCeiling.takeUnless {
            request.config.authMode == io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT
        }
        val ceiling = listOfNotNull(request.budgetOutputTokenCeiling, contextCeiling).minOrNull() ?: return body
        require(ceiling > 0)
        val expectedModel = request.effectiveConfig.model.let { if (endpoint == EndpointKind.GEMINI_INTERACTIONS) it.removePrefix("models/") else it }
        require(body.optString("model") == expectedModel) { "MODEL_COST_MODEL_MISMATCH" }
        val generation = body.optJSONObject("generation_config")
        if (request.budgetOutputTokenCeiling != null && listOf("n", "best_of", "candidate_count").any { body.optInt(it, 1) != 1 || generation?.optInt(it, 1)?.let { value -> value != 1 } == true }) {
            throw AgentModelFailure("MODEL_COST_BOUND_UNSUPPORTED", false, "费用限额请求只允许生成一份回复，请调整模型的多候选参数。")
        }
        val tools = body.optJSONArray("tools")
        if (request.budgetOutputTokenCeiling != null && tools != null && (0 until tools.length()).any { index ->
            val type = tools.optJSONObject(index)?.optString("type").orEmpty()
            type !in if (endpoint == EndpointKind.ANTHROPIC_MESSAGES) setOf("", "custom") else setOf("function")
        }) throw AgentModelFailure("MODEL_COST_BOUND_UNSUPPORTED", false, "费用限额请求包含无法按 Token 限定价格的服务端工具。")
        when (endpoint) {
            EndpointKind.CHAT_COMPLETIONS -> {
                val model = request.effectiveConfig.model.lowercase()
                val key = if (body.has("max_completion_tokens") || model.startsWith("o1") ||
                    model.startsWith("o3") || model.startsWith("o4") || model.startsWith("gpt-5")) "max_completion_tokens" else "max_tokens"
                val existing = listOf("max_tokens", "max_completion_tokens").mapNotNull {
                    body.optLong(it, 0).takeIf { value -> value > 0 }
                }.minOrNull()
                body.remove("max_tokens"); body.remove("max_completion_tokens")
                body.put(key, minOf(existing ?: ceiling.toLong(), ceiling.toLong()))
            }
            EndpointKind.RESPONSES -> cap(body, "max_output_tokens", ceiling)
            EndpointKind.ANTHROPIC_MESSAGES -> cap(body, "max_tokens", ceiling)
            EndpointKind.GEMINI_INTERACTIONS -> {
                val options = generation ?: JSONObject().also { body.put("generation_config", it) }
                cap(options, "max_output_tokens", ceiling)
            }
        }
        val outputLimit = when (endpoint) {
            EndpointKind.CHAT_COMPLETIONS -> body.optInt("max_completion_tokens", body.optInt("max_tokens", ceiling))
            EndpointKind.RESPONSES -> body.optInt("max_output_tokens", ceiling)
            EndpointKind.ANTHROPIC_MESSAGES -> body.optInt("max_tokens", ceiling)
            EndpointKind.GEMINI_INTERACTIONS -> body.optJSONObject("generation_config")?.optInt("max_output_tokens", ceiling) ?: ceiling
        }
        // Several compatible providers expose a separate thinking budget. Keep it inside the
        // now-enforced total completion cap, leaving room for the actual answer.
        if (body.optLong("thinking_budget", 0) > 0) {
            body.put("thinking_budget", minOf(body.getLong("thinking_budget"), maxOf(1, outputLimit - minOf(256, outputLimit / 4)).toLong()))
        }
        body.optJSONObject("thinking")?.takeIf { it.optString("type") == "enabled" && it.optLong("budget_tokens", 0) > 0 }?.let { thinking ->
            val available = outputLimit - 256
            if (available < 1_024) throw AgentModelFailure("CONTEXT_REASONING_RESERVE_TOO_SMALL", false,
                "当前上下文窗口无法同时容纳已启用的思考预算和回复，请增大窗口或调整思考设置。")
            thinking.put("budget_tokens", minOf(thinking.getLong("budget_tokens"), available.toLong()))
        }
        return body
    }

    private fun cap(body: JSONObject, key: String, ceiling: Int) {
        val value = body.optLong(key, 0).takeIf { it > 0 } ?: ceiling.toLong()
        body.put(key, minOf(value, ceiling.toLong()))
    }
}
