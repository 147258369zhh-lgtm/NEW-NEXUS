package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import org.junit.Assert.*
import org.junit.Test

class PromptBehaviorAssemblyTest {
    private val model = AgentModelClient.ModelConfig(providerId = "p", baseUrl = "https://example.invalid",
        apiKey = "test", model = "m", systemPrompt = "provider-rule")
    private fun template(name: String, vararg blocks: PromptBlock) = PromptTemplate(name = name, blocks = blocks.toList())
    private fun block(text: String, stages: Set<PromptStage> = emptySet()) = PromptBlock(title = "rule", content = text, stages = stages)

    @Test fun projectAndStageAreBothRequiredAndHostBindingSurvives() {
        val project = template("project", block("project-work", setOf(PromptStage.MEETING_WORK)))
        val host = template("host", block("host-plan", setOf(PromptStage.MEETING_PLANNING)))
        val config = PromptInjectionConfig(listOf(project, host), listOf(
            PromptScopeBinding(project.id, PromptTarget.project("a")),
            PromptScopeBinding(host.id, PromptTarget.meetingParticipant("room", "meeting-host"))))
        val scope = PromptRunScope(projectId = "a", meetingRoomId = "room", meetingParticipant = "meeting-host",
            meetingHost = true, stage = PromptStage.MEETING_PLANNING)
        val result = PromptInjectionResolver.resolve(config, model, scope)
        assertTrue(result.prefixText.contains("host-plan"))
        assertFalse(result.prefixText.contains("project-work"))
        assertTrue(result.fragments.any { it.status.startsWith("阶段不匹配") })
        assertFalse(PromptInjectionResolver.resolve(config, model, scope.copy(projectId = "b", stage = PromptStage.MEETING_WORK)).prefixText.contains("project-work"))
        assertEquals(scope, PromptRunScope.fromJson(scope.toJson().toString()))
        assertEquals(PromptStage.SUBAGENT, scope.forSubagent("coder").effectiveStage)
    }

    @Test fun replacementAndDisableAffectInheritedTemplatesOnly() {
        val global = template("global", block("inherited"))
        val local = template("local", block("specific"))
        val config = PromptInjectionConfig(listOf(global, local), listOf(
            PromptScopeBinding(global.id, PromptTarget.GLOBAL),
            PromptScopeBinding(local.id, PromptTarget.conversation("c"), inheritance = PromptInheritance.REPLACE)))
        val result = PromptInjectionResolver.resolve(config, model, PromptRunScope(conversationId = "c"))
        assertFalse(result.prefixText.contains("inherited"))
        assertTrue(result.prefixText.contains("specific"))
        assertEquals(listOf(local.id), result.templateIds)
        assertTrue(result.fragments.any { it.status.contains("替换") })
        val disabled = config.copy(bindings = config.bindings.map { if (it.target.type == PromptScopeType.CONVERSATION) it.copy(inheritance = PromptInheritance.DISABLE) else it })
        assertTrue(PromptInjectionResolver.resolve(disabled, model, PromptRunScope(conversationId = "c")).isEmpty)
        assertEquals("provider-rule", model.systemPrompt)
    }

    @Test fun specificCompleteBlockSurvivesOversizedGlobalAndOmissionsAreAccurate() {
        val global = template("large", block("x".repeat(70_000)))
        val local = template("local", block("specific-rule"))
        val result = PromptInjectionResolver.resolve(PromptInjectionConfig(listOf(global, local), listOf(
            PromptScopeBinding(global.id, PromptTarget.GLOBAL),
            PromptScopeBinding(local.id, PromptTarget.conversation("c")))), model, PromptRunScope(conversationId = "c"))
        assertEquals(listOf(local.id), result.templateIds)
        assertTrue(result.prefixText.contains("specific-rule"))
        assertTrue(result.fragments.single { it.templateId == global.id }.status.contains("预算不足"))
        assertFalse(result.prefixText.contains("x".repeat(100)))
    }

    @Test fun characterMacrosUseFrozenCardAndMissingContextDoesNotSendRawMacros() {
        val card = CharacterCardCodec.decodeJson("""{"name":"阿澄","description":"研究员"}""")
        val template = template("persona", block("{{char}} 对 {{user}}：{{description}} / {{model}}"))
        val config = PromptInjectionConfig(listOf(template), listOf(PromptScopeBinding(template.id, PromptTarget.GLOBAL)))
        val resolved = PromptInjectionResolver.resolve(config, model, PromptRunScope(characterId = "character"), PromptMacroContext(card, "小林"))
        assertTrue(resolved.prefixText.contains("阿澄 对 小林：研究员 / m"))
        assertFalse(resolved.prefixText.contains("{{"))
        val absent = PromptInjectionResolver.resolve(config, model, PromptRunScope())
        assertTrue(absent.isEmpty)
        assertTrue(absent.fragments.single().status.contains("缺少宏上下文"))
    }

    @Test fun nativeRoundTripPreservesRestrictionsAndLegacyConfigStillLoads() {
        val template = template("phase", block("summary-only", setOf(PromptStage.MEETING_SUMMARY)))
        val config = PromptInjectionConfig(listOf(template), listOf(PromptScopeBinding(template.id, PromptTarget.project("p"), inheritance = PromptInheritance.REPLACE)))
        val exported = PromptImportCodec.exportNative(config)
        val decoded = PromptInjectionConfig.fromJson(exported)!!
        assertEquals(config, decoded)
        val inspected = PromptImportCodec.inspect(exported.toByteArray(), "behavior.json") as PromptImportResult.Success
        assertEquals(config.bindings, inspected.preview.bindings)
        assertEquals(template.blocks.single().stages, inspected.preview.templates.single().blocks.single().stages)
        listOf(PromptImportCodec.exportMarkdownTemplate(template) to "behavior.md", PromptImportCodec.exportPlainTextTemplate(template) to "behavior.txt").forEach { (text, name) ->
            val imported = PromptImportCodec.inspect(text.toByteArray(), name) as PromptImportResult.Success
            assertEquals(template.blocks.single().stages, imported.preview.templates.single().blocks.single().stages)
        }
        val legacy = config.toJson().put("version", 1)
        assertNotNull(PromptInjectionConfig.fromJson(legacy.toString()))
        val invalid = PromptBlock.fromJson(block("never").toJson().put("stages", org.json.JSONArray().put("future-stage")))
        val invalidConfig = config.copy(templates = listOf(template.copy(blocks = listOf(invalid))))
        assertTrue(PromptInjectionResolver.resolve(invalidConfig, model, PromptRunScope(projectId = "p")).isEmpty)
    }
}
