package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderBudgetOutputLimitTest {
    @Test fun contextReserveCapsOutputAndStillAllowsHostedToolsWithoutACostBudget() {
        val body = JSONObject().put("model", "fixture").put("max_output_tokens", 32_000)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        ProviderBudgetOutputLimit.apply(body, request.copy(budgetOutputTokenCeiling = null,
            contextOutputTokenCeiling = 1_024), EndpointKind.RESPONSES)
        assertEquals(1_024, body.getInt("max_output_tokens"))
    }
    @Test fun explicitThinkingBudgetFitsInsideTheEnforcedCompletionReserve() {
        val body = JSONObject().put("model", "fixture").put("thinking_budget", 32_768)
        ProviderBudgetOutputLimit.apply(body, request.copy(budgetOutputTokenCeiling = null,
            contextOutputTokenCeiling = 2_048), EndpointKind.CHAT_COMPLETIONS)
        assertEquals(2_048, body.getInt("max_tokens"))
        assertTrue(body.getInt("thinking_budget") < body.getInt("max_tokens"))
    }
    private val request = ProviderRequest(AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "",
        model = "fixture", systemPrompt = ""), JSONArray(), JSONArray(), budgetOutputTokenCeiling = 512)

    @Test fun everyProtocolAppliesTheBudgetAfterCustomFieldsWithoutRaisingSmallerLimits() {
        for ((endpoint, key) in listOf(EndpointKind.CHAT_COMPLETIONS to "max_tokens", EndpointKind.RESPONSES to "max_output_tokens",
            EndpointKind.ANTHROPIC_MESSAGES to "max_tokens", EndpointKind.GEMINI_INTERACTIONS to "max_output_tokens")) {
            for (existing in listOf<Long?>(null, 64, 100_000)) {
                val body = JSONObject().put("model", "fixture")
                val target = if (endpoint == EndpointKind.GEMINI_INTERACTIONS) JSONObject().also { body.put("generation_config", it) } else body
                existing?.let { target.put(key, it) }
                ProviderBudgetOutputLimit.apply(body, request, endpoint)
                assertEquals(minOf(existing ?: 512L, 512L), target.getLong(key))
            }
        }
    }

    @Test fun reasoningChatModelsUseCompletionLimitAndModelOverridesAreRejected() {
        val body = JSONObject().put("model", "gpt-5-fixture").put("max_tokens", 900).put("max_completion_tokens", 200)
        ProviderBudgetOutputLimit.apply(body, request.copy(config = request.config.copy(model = "gpt-5-fixture")), EndpointKind.CHAT_COMPLETIONS)
        assertFalse(body.has("max_tokens")); assertEquals(200L, body.getLong("max_completion_tokens"))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderBudgetOutputLimit.apply(JSONObject().put("model", "different"), request, EndpointKind.RESPONSES)
        }
    }

    @Test fun unlimitedRequestsKeepTheirExistingParameters() {
        val body = JSONObject().put("model", "fixture").put("max_tokens", 100_000)
        ProviderBudgetOutputLimit.apply(body, request.copy(budgetOutputTokenCeiling = null), EndpointKind.CHAT_COMPLETIONS)
        assertEquals(100_000, body.getInt("max_tokens"))
    }

    @Test fun unknownErrorsAndReportedUsageAreNeverAssumedToBeFree() {
        assertTrue(ProviderBudgetOutputLimit.isParameterRejection(400, """{"error":{"type":"invalid_request_error"}}"""))
        assertFalse(ProviderBudgetOutputLimit.isParameterRejection(400, """{"error":{"message":"unknown"}}"""))
        assertFalse(ProviderBudgetOutputLimit.isParameterRejection(500, """{"error":{"type":"invalid_request_error"}}"""))
        assertFalse(ProviderBudgetOutputLimit.isParameterRejection(400, """{"usage":{"input_tokens":2},"error":{"type":"invalid_request_error"}}"""))
    }

    @Test fun customMultiCandidateOrServerToolRequestsCannotBypassTheReservation() {
        for (body in listOf(JSONObject().put("n", 2), JSONObject().put("best_of", 4),
            JSONObject().put("generation_config", JSONObject().put("candidate_count", 2)),
            JSONObject().put("tools", JSONArray().put(JSONObject().put("type", "web_search"))))) {
            val failure = assertThrows(AgentModelFailure::class.java) {
                ProviderBudgetOutputLimit.apply(body.put("model", "fixture"), request, EndpointKind.RESPONSES)
            }
            assertEquals("MODEL_COST_BOUND_UNSUPPORTED", failure.code)
        }
        val prefixed = request.copy(config = request.config.copy(model = "models/gemini"))
        ProviderBudgetOutputLimit.apply(JSONObject().put("model", "models/gemini"), prefixed, EndpointKind.CHAT_COMPLETIONS)
        ProviderBudgetOutputLimit.apply(JSONObject().put("model", "gemini"), prefixed, EndpointKind.GEMINI_INTERACTIONS)
    }
}
