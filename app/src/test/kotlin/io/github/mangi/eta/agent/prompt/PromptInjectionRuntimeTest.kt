package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.runtime.AgentBoundaryStore
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 运行时接入：模板存储是唯一注入来源，模型与会话对象决定实际生效的模板。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PromptInjectionRuntimeTest {
    @Before
    fun setup() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test
    fun runtimeInjectionUsesMigratedTemplateInsteadOfRereadingLegacyBehavior() {
        assertTrue(AgentBoundaryStore.saveBehavior("旧版表达习惯"))
        val model = modelConfig()
        val migrated = PromptInjectionStore.resolve(model, PromptRunScope())
        assertTrue(migrated.prefixText.contains("旧版表达习惯"))

        // 迁移完成后模板是事实源：再次修改行为字段不会改变运行时注入。
        assertTrue(AgentBoundaryStore.saveBehavior("新的表达习惯"))
        val again = PromptInjectionStore.resolve(model, PromptRunScope())
        assertTrue(again.prefixText.contains("旧版表达习惯"))
        assertFalse(again.prefixText.contains("新的表达习惯"))
    }

    @Test
    fun subagentResolvesItsOwnModelWhileInheritingConversationScope() {
        val parentModel = template("parent-model", "parent-model-rule")
        val childModel = template("child-model", "child-model-rule")
        val conversation = template("conversation", "conversation-rule")
        val subagent = template("subagent", "subagent-rule")
        listOf(parentModel, childModel, conversation, subagent).forEach { assertTrue(PromptInjectionStore.saveTemplate(it)) }
        assertTrue(PromptInjectionStore.bind(parentModel.id, PromptTarget.model("provider-a", "parent-model")))
        assertTrue(PromptInjectionStore.bind(childModel.id, PromptTarget.model("provider-a", "child-model")))
        assertTrue(PromptInjectionStore.bind(conversation.id, PromptTarget.conversation("conversation-a")))
        assertTrue(PromptInjectionStore.bind(subagent.id, PromptTarget.subagent("coder")))

        val parentScope = PromptRunScope(conversationId = "conversation-a")
        val parent = PromptInjectionStore.resolve(modelConfig(model = "parent-model"), parentScope)
        assertTrue(parent.prefixText.contains("parent-model-rule"))
        assertTrue(parent.prefixText.contains("conversation-rule"))
        assertFalse(parent.prefixText.contains("child-model-rule"))
        assertFalse(parent.prefixText.contains("subagent-rule"))

        val child = PromptInjectionStore.resolve(
            modelConfig(model = "child-model"),
            parentScope.forSubagent("coder"),
        )
        assertTrue(child.prefixText.contains("child-model-rule"))
        assertTrue(child.prefixText.contains("conversation-rule"))
        assertTrue(child.prefixText.contains("subagent-rule"))
        assertFalse(child.prefixText.contains("parent-model-rule"))
    }

    @Test
    fun resolvedInjectionReachesSystemPrefixAndPostHistorySlot() {
        val prefix = PromptTemplate(
            name = "前缀",
            blocks = listOf(PromptBlock(title = "前缀", content = "prefix-content")),
        )
        val post = PromptTemplate(
            name = "后置",
            blocks = listOf(
                PromptBlock(title = "后置", content = "post-content", position = PromptBlockPosition.POST_HISTORY),
            ),
        )
        assertTrue(PromptInjectionStore.saveTemplate(prefix))
        assertTrue(PromptInjectionStore.saveTemplate(post))
        assertTrue(PromptInjectionStore.bind(prefix.id, PromptTarget.GLOBAL))
        assertTrue(PromptInjectionStore.bind(post.id, PromptTarget.conversation("conversation-a")))

        val injection = PromptInjectionStore.resolve(
            modelConfig(),
            PromptRunScope(conversationId = "conversation-a"),
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(),
            prompt = "当前问题",
            images = emptyList(),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "旧问题")),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            promptInjection = injection,
        )
        val systemContents = (0 until messages.length())
            .map(messages::getJSONObject)
            .filter { it.getString("role") == "system" }
            .map { it.getString("content") }
        assertTrue(systemContents.any { it.contains("prefix-content") })
        assertTrue(
            "postHistoryText=${injection.postHistoryText}",
            injection.postHistoryText.contains("post-content"),
        )
        val postIndex = (0 until messages.length()).first { index ->
            messages.getJSONObject(index).messageText().contains("post-content")
        }
        val currentIndex = (0 until messages.length()).last { index ->
            messages.getJSONObject(index).messageText().contains("当前问题")
        }
        assertTrue(postIndex < currentIndex)
        assertTrue(messages.getJSONObject(postIndex).getString("role") == "user")
        assertTrue(messages.getJSONObject(postIndex).getBoolean(PROMPT_INJECTION_MARKER))
        assertTrue(messages.getJSONObject(currentIndex).getString("role") == "user")
    }

    private fun org.json.JSONObject.messageText(): String {
        val content = opt("content")
        if (content is String) return content
        if (content is JSONArray) {
            return (0 until content.length()).joinToString("\n") { index ->
                content.optJSONObject(index)?.optString("text").orEmpty()
            }
        }
        return ""
    }

    private fun template(name: String, content: String) = PromptTemplate(
        name = name,
        blocks = listOf(PromptBlock(title = name, content = content)),
    )

    private fun modelConfig(model: String = "parent-model") = AgentModelClient.ModelConfig(
        providerId = "provider-a",
        providerName = "Provider A",
        baseUrl = "https://example.invalid/v1",
        apiKey = "test",
        model = model,
        systemPrompt = "base",
        terminalTools = false,
        browserTools = false,
    )
}
