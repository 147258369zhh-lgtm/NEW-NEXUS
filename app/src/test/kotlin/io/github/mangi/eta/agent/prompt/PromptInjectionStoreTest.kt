package io.github.mangi.eta.agent.prompt

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentBoundaryStore
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PromptInjectionStoreTest {
    @Before
    fun setUp() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test
    fun oneTemplateCanTargetSeveralIndependentScopesButOneScopeHasOneOwner() {
        val first = PromptTemplate(name = "first", blocks = listOf(PromptBlock(title = "rule", content = "one")))
        val second = PromptTemplate(name = "second", blocks = listOf(PromptBlock(title = "rule", content = "two")))
        assertTrue(PromptInjectionStore.saveTemplate(first))
        assertTrue(PromptInjectionStore.saveTemplate(second))

        val conversation = PromptTarget.conversation("conversation-a")
        val model = PromptTarget.model("provider-a", "deepseek-flash")
        assertTrue(PromptInjectionStore.bind(first.id, model))
        assertTrue(PromptInjectionStore.bind(first.id, conversation))
        assertTrue(PromptInjectionStore.bind(second.id, conversation))

        assertEquals(first.id, PromptInjectionStore.binding(model)?.templateId)
        assertEquals(second.id, PromptInjectionStore.binding(conversation)?.templateId)
        assertEquals(1, PromptInjectionStore.bindingsFor(first.id).size)
    }

    @Test
    fun deletingTemplateAlsoRemovesItsBindings() {
        val template = PromptTemplate(name = "temporary", blocks = listOf(PromptBlock(title = "rule", content = "content")))
        assertTrue(PromptInjectionStore.saveTemplate(template))
        assertTrue(PromptInjectionStore.bind(template.id, PromptTarget.GLOBAL))
        assertTrue(PromptInjectionStore.deleteTemplate(template.id))

        assertNull(PromptInjectionStore.binding(PromptTarget.GLOBAL))
        assertTrue(PromptInjectionStore.templates().isEmpty())
    }

    @Test
    fun nativeConfigurationRoundTripsBlocksAndBindings() {
        val template = PromptTemplate(
            name = "研究助手",
            description = "保持中文和引用",
            blocks = listOf(
                PromptBlock(id = "prefix", title = "工作规则", content = "先取证再回答", order = 10),
                PromptBlock(id = "post", title = "后置提醒", content = "标明不确定性", position = PromptBlockPosition.POST_HISTORY, order = 20),
            ),
            source = "imported",
        )
        val config = PromptInjectionConfig(
            templates = listOf(template),
            bindings = listOf(PromptScopeBinding(template.id, PromptTarget.model("provider-a", "deepseek-flash"))),
        )

        assertTrue(PromptInjectionStore.replace(config))
        val exported = PromptInjectionStore.exportConfig()
        Prefs.localAgentPreferences()!!.edit().remove("agent_prompt_injection_v2").commit()
        assertTrue(PromptInjectionStore.importConfig(exported))

        val restored = PromptInjectionStore.load()
        assertEquals(template.id, restored.templates.single().id)
        assertEquals(2, restored.templates.single().blocks.size)
        assertEquals(PromptBlockPosition.POST_HISTORY, restored.templates.single().blocks[1].position)
        assertEquals(PromptTarget.model("provider-a", "deepseek-flash"), restored.bindings.single().target)
    }

    @Test
    fun legacyBehaviorIsMigratedOnceIntoAGlobalTemplate() {
        assertTrue(AgentBoundaryStore.saveBehavior("回答简洁，先给结论。"))

        val first = PromptInjectionStore.load()
        val second = PromptInjectionStore.load()

        assertEquals("回答简洁，先给结论。", first.templates.single().blocks.single().content)
        assertEquals(PromptTarget.GLOBAL, first.bindings.single().target)
        assertEquals(first.templates.single().id, second.templates.single().id)
    }

    @Test
    fun corruptConfigurationFailsClosedWithoutClearingRawData() {
        Prefs.localAgentPreferences()!!.edit().putString("agent_prompt_injection_v2", "{broken").commit()

        assertTrue(PromptInjectionStore.load().templates.isEmpty())
        assertEquals("{broken", Prefs.localAgentPreferences()!!.getString("agent_prompt_injection_v2", null))
        assertFalse(PromptInjectionStore.importConfig("not json"))
        assertTrue(PromptInjectionStore.saveTemplate(PromptTemplate(
            name = "recovered",
            blocks = listOf(PromptBlock(title = "rule", content = "safe")),
        )))
        assertEquals(
            "{broken",
            Prefs.localAgentPreferences()!!.getString(PromptInjectionStore.CORRUPT_BACKUP_KEY, null),
        )
    }

    @Test
    fun duplicateTemplateIdsResolveDeterministicallyToTheFirstTemplate() {
        val first = PromptTemplate(
            id = "duplicate",
            name = "first",
            blocks = listOf(PromptBlock(title = "rule", content = "first-rule")),
        )
        val second = PromptTemplate(
            id = "duplicate",
            name = "second",
            blocks = listOf(PromptBlock(title = "rule", content = "second-rule")),
        )
        val config = PromptInjectionConfig(
            templates = listOf(first, second),
            bindings = listOf(PromptScopeBinding(first.id, PromptTarget.GLOBAL)),
        )

        assertTrue(PromptInjectionStore.replace(config))
        val restored = PromptInjectionStore.load()
        assertEquals(1, restored.templates.size)
        assertEquals("first-rule", restored.templates.single().blocks.single().content)
    }
}
