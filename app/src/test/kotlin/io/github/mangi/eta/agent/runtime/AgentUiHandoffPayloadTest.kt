package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentUiHandoffPayloadTest {
    @Test fun selectedSkillsRoundTripAndOlderPayloadsDefaultToNone() {
        val payload = AgentUiHandoffPayload(
            conversationId = "conversation",
            selectedSkillIds = listOf("writing", "search"),
        )
        assertEquals(listOf("writing", "search"), AgentUiHandoffPayload.from(payload.toJson()).selectedSkillIds)
        assertEquals(
            emptyList<String>(),
            AgentUiHandoffPayload.from("""{"type":"agent_ui_handoff","version":2,"conversationId":"old"}""").selectedSkillIds,
        )
    }
}
