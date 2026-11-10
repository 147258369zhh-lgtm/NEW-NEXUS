package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptInjectionResolverTest {
    @Test
    fun globalModelAndConversationTemplatesComposeInStableOrder() {
        val global = template("global", "global-rule")
        val model = template("model", "model-rule")
        val conversation = template("conversation", "conversation-rule")
        val config = PromptInjectionConfig(
            templates = listOf(global, model, conversation),
            bindings = listOf(
                PromptScopeBinding(conversation.id, PromptTarget.conversation("conversation-a")),
                PromptScopeBinding(global.id, PromptTarget.GLOBAL),
                PromptScopeBinding(model.id, PromptTarget.model("provider-a", "deepseek-flash")),
            ),
        )

        val result = PromptInjectionResolver.resolve(config, modelConfig(), PromptRunScope(conversationId = "conversation-a"))

        assertTrue(result.prefixText.indexOf("global-rule") < result.prefixText.indexOf("model-rule"))
        assertTrue(result.prefixText.indexOf("model-rule") < result.prefixText.indexOf("conversation-rule"))
        assertEquals(listOf("global", "model", "conversation"), result.templateNames)
    }

    @Test
    fun modelAndConversationBindingsDoNotLeakToOtherRuns() {
        val model = template("model", "only-model")
        val conversation = template("conversation", "only-conversation")
        val config = PromptInjectionConfig(
            templates = listOf(model, conversation),
            bindings = listOf(
                PromptScopeBinding(model.id, PromptTarget.model("provider-a", "deepseek-flash")),
                PromptScopeBinding(conversation.id, PromptTarget.conversation("conversation-a")),
            ),
        )

        val modelOnly = PromptInjectionResolver.resolve(config, modelConfig(), PromptRunScope(conversationId = "conversation-b"))
        assertTrue(modelOnly.prefixText.contains("only-model"))
        assertFalse(modelOnly.prefixText.contains("only-conversation"))

        val differentModel = PromptInjectionResolver.resolve(
            config,
            modelConfig(model = "another-model"),
            PromptRunScope(conversationId = "conversation-b"),
        )
        assertTrue(differentModel.isEmpty)
    }

    @Test
    fun meetingParticipantIsMoreSpecificThanRoomAndModel() {
        val model = template("model", "model")
        val room = template("room", "room")
        val participant = template("participant", "participant")
        val config = PromptInjectionConfig(
            templates = listOf(model, room, participant),
            bindings = listOf(
                PromptScopeBinding(model.id, PromptTarget.model("provider-a", "deepseek-flash")),
                PromptScopeBinding(room.id, PromptTarget.meeting("room-a")),
                PromptScopeBinding(participant.id, PromptTarget.meetingParticipant("room-a", "coder")),
            ),
        )

        val result = PromptInjectionResolver.resolve(
            config,
            modelConfig(),
            PromptRunScope(meetingRoomId = "room-a", meetingParticipant = "coder"),
        )

        assertTrue(result.prefixText.indexOf("greater model") < 0)
        assertTrue(result.prefixText.indexOf("\nmodel\n") < result.prefixText.indexOf("\nroom\n"))
        assertTrue(result.prefixText.indexOf("\nroom\n") < result.prefixText.indexOf("\nparticipant\n"))
    }

    @Test
    fun sameTemplateBoundToMultipleScopesIsInjectedOnce() {
        val shared = template("shared", "shared-rule")
        val config = PromptInjectionConfig(
            templates = listOf(shared),
            bindings = listOf(
                PromptScopeBinding(shared.id, PromptTarget.GLOBAL),
                PromptScopeBinding(shared.id, PromptTarget.model("provider-a", "deepseek-flash")),
                PromptScopeBinding(shared.id, PromptTarget.conversation("conversation-a")),
            ),
        )

        val result = PromptInjectionResolver.resolve(config, modelConfig(), PromptRunScope(conversationId = "conversation-a"))

        assertEquals(1, Regex("shared-rule").findAll(result.prefixText).count())
    }

    @Test
    fun postHistoryBlocksStayOutOfTheSystemPrefix() {
        val post = template("post", "after-history", PromptBlockPosition.POST_HISTORY)
        val config = PromptInjectionConfig(
            templates = listOf(post),
            bindings = listOf(PromptScopeBinding(post.id, PromptTarget.GLOBAL)),
        )

        val result = PromptInjectionResolver.resolve(config, modelConfig(), PromptRunScope())

        assertFalse(result.prefixText.contains("after-history"))
        assertTrue(result.postHistoryText.contains("after-history"))
        assertTrue(result.postHistoryText.contains("position=\"post_history\""))
    }

    @Test
    fun allScopesStackFromGlobalToParticipant() {
        val global = template("global", "rule-global")
        val model = template("model", "rule-model")
        val room = template("room", "rule-room")
        val subagent = template("subagent", "rule-subagent")
        val conversation = template("conversation", "rule-conversation")
        val participant = template("participant", "rule-participant")
        val config = PromptInjectionConfig(
            templates = listOf(participant, conversation, subagent, room, model, global),
            bindings = listOf(
                PromptScopeBinding(participant.id, PromptTarget.meetingParticipant("room-a", "coder")),
                PromptScopeBinding(conversation.id, PromptTarget.conversation("conversation-a")),
                PromptScopeBinding(subagent.id, PromptTarget.subagent("coder")),
                PromptScopeBinding(room.id, PromptTarget.meeting("room-a")),
                PromptScopeBinding(model.id, PromptTarget.model("provider-a", "deepseek-flash")),
                PromptScopeBinding(global.id, PromptTarget.GLOBAL),
            ),
        )

        val result = PromptInjectionResolver.resolve(
            config,
            modelConfig(),
            PromptRunScope(
                conversationId = "conversation-a",
                meetingRoomId = "room-a",
                meetingParticipant = "coder",
                subagentName = "coder",
            ),
        )

        assertEquals(
            listOf("global", "model", "room", "subagent", "conversation", "participant"),
            result.templateNames,
        )
        val positions = result.templateNames.map { name -> result.prefixText.indexOf("rule-$name") }
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun modelScopeRequiresBothProviderAndModelToMatch() {
        val model = template("model", "provider-a-rule")
        val config = PromptInjectionConfig(
            templates = listOf(model),
            bindings = listOf(PromptScopeBinding(model.id, PromptTarget.model("provider-a", "deepseek-flash"))),
        )

        assertTrue(PromptInjectionResolver.resolve(config, modelConfig(), PromptRunScope()).prefixText.contains("provider-a-rule"))
        assertTrue(
            PromptInjectionResolver.resolve(
                config,
                modelConfig(providerId = "provider-b"),
                PromptRunScope(),
            ).isEmpty,
        )
        assertTrue(
            PromptInjectionResolver.resolve(
                config,
                modelConfig(model = "deepseek-pro"),
                PromptRunScope(),
            ).isEmpty,
        )
        assertTrue(
            PromptInjectionResolver.resolve(
                config,
                modelConfig(model = "deepseek-flash-preview"),
                PromptRunScope(),
            ).isEmpty,
        )
    }

    @Test
    fun invalidParticipantScopeDoesNotDisableValidGlobalInjection() {
        val global = template("global", "global-must-survive")
        val participant = template("participant", "participant-rule")
        val participantTarget = PromptTarget.meetingParticipant("room-a", "coder")
        val config = PromptInjectionConfig(
            templates = listOf(global, participant),
            bindings = listOf(
                PromptScopeBinding(global.id, PromptTarget.GLOBAL),
                PromptScopeBinding(participant.id, participantTarget),
            ),
        )

        val result = PromptInjectionResolver.resolve(
            config,
            modelConfig(),
            PromptRunScope(meetingParticipant = "coder"),
        )

        assertTrue(result.prefixText.contains("global-must-survive"))
        assertFalse(result.prefixText.contains("participant-rule"))
    }

    @Test
    fun oversizedInjectionNeverEndsWithAnUnclosedTemplateTag() {
        val large = template("large", "x".repeat(70_000))
        val small = template("small", "small-after-large")
        val config = PromptInjectionConfig(
            templates = listOf(large, small),
            bindings = listOf(
                PromptScopeBinding(large.id, PromptTarget.GLOBAL),
                PromptScopeBinding(small.id, PromptTarget.conversation("conversation-a")),
            ),
        )

        val text = PromptInjectionResolver.resolve(
            config,
            modelConfig(),
            PromptRunScope(conversationId = "conversation-a"),
        ).prefixText

        assertTrue(text.lastIndexOf("<eta_prompt_template") < text.lastIndexOf("</eta_prompt_template>"))
        assertTrue(text.length <= 64_000)
    }

    private fun template(
        name: String,
        content: String,
        position: PromptBlockPosition = PromptBlockPosition.SYSTEM_PREFIX,
    ) = PromptTemplate(
        name = name,
        blocks = listOf(PromptBlock(title = name, content = content, position = position)),
    )

    private fun modelConfig(
        model: String = "deepseek-flash",
        providerId: String = "provider-a",
    ) = AgentModelClient.ModelConfig(
        providerId = providerId,
        providerName = "Provider A",
        baseUrl = "https://example.invalid/v1",
        apiKey = "test",
        model = model,
        systemPrompt = "base",
    )
}
