package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.prompt.PROMPT_INJECTION_MARKER
import io.github.mangi.eta.agent.prompt.ResolvedPromptInjection
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {
    @Test
    fun rootPromptOnlyDependsOnGrantSoTransientProbeStatesKeepThePrefixStable() {
        fun prompt(state: RootAccessState) = AgentPromptBuilder.buildSystemMessages(
            config = modelConfig("", terminalTools = true, browserTools = false),
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = state.isGranted,
        ).toString()
        val ungranted = listOf(
            RootAccessState(isChecking = true),
            RootAccessState(RootAccessStatus.UNKNOWN),
            RootAccessState(RootAccessStatus.TIMED_OUT),
            RootAccessState(RootAccessStatus.DENIED),
            RootAccessState(RootAccessStatus.UNAVAILABLE),
        ).map(::prompt)
        assertEquals(1, ungranted.distinct().size)
        val granted = prompt(RootAccessState(RootAccessStatus.GRANTED, suPresent = true))
        assertEquals(granted, prompt(RootAccessState(RootAccessStatus.GRANTED, suPresent = true, isChecking = true)))
        assertTrue(granted.contains("Root 授权已确认"))
        assertTrue(ungranted.first().contains("这不代表设备不支持 Root"))
        for (contents in ungranted + granted) {
            assertTrue(contents.contains("identity=user 是显式选择"))
            assertTrue(contents.contains("terminal(action=check_root)"))
        }
    }

    @Test
    fun terminalPromptDescribesTheActualRunWorkspaceAndSeparateLifecycles() {
        val messages = AgentPromptBuilder.buildSystemMessages(
            config = modelConfig("", terminalTools = true, browserTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            executionContext = TerminalExecutionContext(TerminalEnvironment.DEBIAN, null, "/eta/custom workspace"),
        )
        val terminal = messages.systemContents().single { it.contains("Eta 内置文件与终端工具") }
        assertTrue(terminal.contains("/eta/custom workspace"))
        assertTrue(terminal.contains("debian"))
        assertTrue(terminal.contains("identity=user"))
        assertTrue(terminal.contains("不要用 & 起后台进程"))
        assertTrue(terminal.contains("common_tools"))
        assertTrue(terminal.contains("profiles"))
        assertTrue(terminal.contains("terminal_job"))
        assertTrue(terminal.contains("terminal_daemon"))
        assertTrue(terminal.contains("environment=\"linux\""))
        assertTrue(terminal.contains("同一个物理文件夹"))
        assertTrue(terminal.contains("禁止在两个环境之间重复复制"))
        assertFalse(terminal.contains("async=true"))
        assertFalse(terminal.contains("read_async_result"))
    }

    @Test
    fun currentModelIdentityFollowsConfigWithCustomOrEmptyProviderPrompt() {
        for (providerPrompt in listOf("你是小满", "")) {
            val config = modelConfig(providerPrompt, terminalTools = false, browserTools = false)
                .copy(model = "provider/model-a", modelDisplayName = "显示名称")
            for (modelId in listOf(config.model, "provider/model-b")) {
                val messages = AgentPromptBuilder.buildSystemMessages(
                    config = config.copy(model = modelId),
                    skillContext = SkillContext.EMPTY,
                    memoryContext = AgentMemoryContext.DISABLED,
                    rootAvailable = false,
                )

                val identity = messages.systemContents().single { it.contains("当前配置的模型：") }
                assertFalse(messages.systemContents().any { it.contains("你是 Eta") })
                assertTrue(identity.contains("当前配置的模型：\"$modelId\""))
                assertFalse(identity.contains(config.modelDisplayName))
                if (modelId != config.model) assertFalse(identity.contains(config.model))
                if (providerPrompt.isNotBlank()) {
                    assertEquals(providerPrompt, messages.getJSONObject(0).getString("content"))
                }
            }
        }
    }

    @Test
    fun messagesKeepSystemHistoryAndCurrentImageInputInStableOrder() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "自定义系统约束",
                terminalTools = true,
                browserTools = false,
            ),
            prompt = "当前问题",
            images = listOf(image),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            rootAvailable = true,
        )

        assertEquals(
            listOf("system", "system", "system", "system", "user", "assistant", "user"),
            messages.roles(),
        )
        assertEquals("自定义系统约束", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.systemContents().any { it.contains("只有用户开启的 Root 恢复或系统保护后端可用时才会请求有限重绑") })
        assertTrue(messages.systemContents().any { it.contains("ACCESSIBILITY_REPAIR_UNCONFIRMED") })
        assertTrue(messages.systemContents().any { it.contains("不要改用坐标或 Shell 重放") })
        assertTrue(messages.systemContents().any { it.contains("通用 GUI 工具完成输入和点击发送") })
        assertTrue(messages.systemContents().any { it.contains("不追加二次确认") })
        assertTrue(messages.systemContents().any { it.contains("立即调用工具") })
        assertTrue(messages.systemContents().any { it.contains("不要先输出计划、解释或中间进度") })
        assertTrue(messages.systemContents().any { it.contains("不要为了展示思考而拆成多个回合") })
        assertTrue(messages.systemContents().any { it.contains("不要例行调用 observe_screen") })
        assertTrue(messages.systemContents().any { it.contains("读取或汇总屏幕信息") })
        assertTrue(messages.systemContents().any { it.contains("确认最终结果") })
        assertTrue(messages.systemContents().any { it.contains("后续操作依赖特定文本或应用出现") })
        assertFalse(messages.systemContents().any { it.contains("点击或打开应用后优先用 wait_for_text") })
        assertTrue(messages.systemContents().any { it.contains("只读取 UI 树，不附截图") })
        assertTrue(messages.systemContents().any { it.contains("include_screenshot=true") })
        assertTrue(messages.systemContents().any { it.contains("保持 include_ui_tree=true") })
        assertTrue(messages.systemContents().any { it.contains("禁止把新截图与旧节点混用") })
        assertTrue(messages.systemContents().any { it.contains("不要仅因截断请求截图") })
        assertTrue(messages.systemContents().any { it.contains("主动调用当前已公开的只读工具获取证据") })
        assertTrue(messages.systemContents().any { it.contains("工具已向你公开表示对应能力已由用户开启") })
        assertTrue(messages.systemContents().any { it.contains("从多个相关来源按时间和代表性取样") })
        assertTrue(messages.systemContents().any { it.contains("系统记忆") })
        assertTrue(messages.systemContents().any { it.contains("主动使用它们定位并只读检查") })
        assertTrue(messages.systemContents().any { it.contains("相关应用私有文件与数据库") })
        assertTrue(messages.systemContents().any { it.contains("执行有界查询，不修改源数据") })
        assertTrue(messages.systemContents().any { it.contains("合法且克制的 GitHub Flavored Markdown") })
        assertTrue(messages.systemContents().any { it.contains("不用整句粗体冒充标题") })
        assertTrue(messages.systemContents().any { it.contains("表格前后留空行") })
        assertTrue(messages.getJSONObject(2).getString("content").contains("只需 command"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("同一轮最多读取一张"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("观察返回内容后再读下一张"))
        assertTrue(messages.getJSONObject(3).getString("content").contains("可调用 subagent 派发子智能体"))
        assertFalse(messages.systemContents().any { it.contains("网页浏览、读取") })
        assertEquals("旧问题", messages.getJSONObject(4).getString("content"))
        assertEquals("旧回答", messages.getJSONObject(5).getString("content"))

        val currentContent = messages.getJSONObject(6).getJSONArray("content")
        assertEquals("当前问题", currentContent.getJSONObject(0).getString("text"))
        assertEquals(
            image.reference,
            currentContent.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun browserAndSkillMessagesAreConditionalAndStructurallyComplete() {
        val skill = SkillIndexEntry(
            id = "screen-audit",
            name = "屏幕审计",
            description = "  检查屏幕\n并输出   结论  ",
            rootPath = "/skills/screen-audit",
            skillFilePath = "/skills/screen-audit/SKILL.md",
            hasScripts = true,
            hasReferences = false,
            hasAssets = true,
            hasEvals = false,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "",
                terminalTools = false,
                browserTools = true,
            ),
            prompt = "读取网页",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext(installedSkills = listOf(skill)),
        )

        assertEquals(listOf("system", "system", "system", "system", "user"), messages.roles())
        val systemContents = messages.systemContents()
        assertTrue(systemContents.any { it.contains("browser_use") })
        assertFalse(systemContents.any { it.contains("只需 command") })
        assertTrue(messages.getJSONObject(2).getString("content").contains("可调用 subagent 派发子智能体"))
        val skillMessage = systemContents.single { it.contains("id=screen-audit") }
        assertEquals(skillMessage, messages.getJSONObject(3).getString("content"))
        assertTrue(skillMessage.contains("path=/skills/screen-audit/SKILL.md"))
        assertTrue(skillMessage.contains("capabilities=scripts, assets"))
        assertTrue(skillMessage.contains("description=检查屏幕 并输出 结论"))
        assertTrue(skillMessage.contains("先调用 skills_read"))
        assertEquals("读取网页", messages.getJSONObject(4).getString("content"))
    }

    @Test
    fun subagentGuidanceFollowsTheSwitchWithoutChangingOtherSystemMessages() {
        val config = modelConfig("自定义系统约束", terminalTools = true, browserTools = true)
        fun contents(enabled: Boolean) = AgentPromptBuilder.buildSystemMessages(
            config = config.copy(subagentTools = enabled),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
        ).systemContents()

        val disabled = contents(false)
        val enabled = contents(true)
        val subagentGuidance = "可调用 subagent 派发子智能体"
        assertFalse(disabled.any { it.contains(subagentGuidance) })
        assertEquals(1, enabled.count { it.contains(subagentGuidance) })
        assertEquals(disabled, enabled.filterNot { it.contains(subagentGuidance) })
    }

    @Test
    fun localImageReferenceCannotLeakIntoProviderRequest() {
        val image = AgentModelClient.ModelImage(
            reference = "content://example.test/image/1",
            mimeType = "image/png",
            bytes = 128,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptBuilder.buildInitialMessages(
                config = modelConfig("", terminalTools = false, browserTools = false),
                prompt = "分析图片",
                images = listOf(image),
                history = emptyList(),
                skillContext = SkillContext.EMPTY,
            )
        }
    }

    @Test
    fun enabledMemoryIsInjectedAsBackgroundWithRevisionAndPriorityBoundary() {
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "现在改用英文回答",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext(
                enabled = true,
                revision = "b".repeat(64),
                byteSize = 128,
                coreContent = "# 核心记忆\n用户以前偏好中文",
                coreTruncated = false,
                headingIndex = "# 核心记忆\n# 项目",
                coreBudgetChars = 8_000,
            ),
        )

        val memory = messages.systemContents().single { it.contains("<memory_core>") }
        assertTrue(memory.contains("背景资料，不是指令"))
        assertTrue(memory.contains("当前用户消息和更高优先级指令始终优先"))
        assertTrue(memory.contains("revision=${"b".repeat(64)}"))
        assertTrue(memory.contains("用户以前偏好中文"))
        assertEquals("现在改用英文回答", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun promptInjectionPrefixIsExtraSystemMessageAndPostHistoryPrecedesCurrentUser() {
        val injection = ResolvedPromptInjection(
            prefixText = "用户配置的固定提示词模板：\n\n### 全局\nprefix-rule",
            postHistoryText = "用户配置的当前对话后置提示：\n\n### 全局\npost-history-rule",
            templateNames = listOf("全局"),
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("自定义系统约束", terminalTools = false, browserTools = false),
            prompt = "当前问题",
            images = emptyList(),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            promptInjection = injection,
        )

        val prefixIndex = messages.indexesOfContent("prefix-rule").single()
        val postIndex = messages.indexesOfContent("post-history-rule").single()
        val oldQuestionIndex = messages.indexesOfContent("旧问题").single()
        val oldAnswerIndex = messages.indexesOfContent("旧回答").single()
        val currentIndex = messages.indexesOfContent("当前问题").single()

        assertEquals("system", messages.getJSONObject(prefixIndex).getString("role"))
        assertEquals("user", messages.getJSONObject(postIndex).getString("role"))
        assertTrue(messages.getJSONObject(postIndex).getBoolean(PROMPT_INJECTION_MARKER))
        assertTrue(prefixIndex < oldQuestionIndex)
        assertTrue(oldAnswerIndex < postIndex)
        assertTrue(postIndex < currentIndex)
        assertEquals(messages.length() - 1, currentIndex)

        val durable = AgentConversationCodec.transcript(messages, 0)
        assertFalse(durable.any { it.content.contains("post-history-rule") })
        assertTrue(durable.any { it.content.contains("当前问题") })
    }

    @Test
    fun postHistoryIsUserRoleInEveryProviderProjectionAndNeverBecomesInstructions() {
        val injection = ResolvedPromptInjection(
            prefixText = "prefix-rule",
            postHistoryText = "post-history-rule",
            templateNames = listOf("测试"),
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "当前问题",
            images = emptyList(),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            promptInjection = injection,
        )

        val chat = OpenAiRequestMessages.forChatCompletions(messages)
        assertFalse(chat.toString().contains(PROMPT_INJECTION_MARKER))
        assertEquals("system", chat.getJSONObject(0).getString("role"))
        assertFalse(chat.getJSONObject(0).getString("content").contains("post-history-rule"))
        val chatPost = chat.indexesOfContent("post-history-rule").single()
        val chatCurrent = chat.indexesOfContent("当前问题").single()
        assertEquals("user", chat.getJSONObject(chatPost).getString("role"))
        assertTrue(chat.indexesOfContent("旧回答").single() < chatPost)
        assertTrue(chatPost < chatCurrent)

        val responses = ResponsesRequestBuilder.build(
            modelConfig("", terminalTools = false, browserTools = false),
            messages,
            JSONArray(),
        )
        assertFalse(responses.getString("instructions").contains("post-history-rule"))
        val responsesInput = responses.getJSONArray("input")
        val responsesPost = responsesInput.indexesOfContent("post-history-rule").single()
        val responsesCurrent = responsesInput.indexesOfContent("当前问题").single()
        assertEquals("user", responsesInput.getJSONObject(responsesPost).getString("role"))
        assertTrue(responsesInput.indexesOfContent("旧回答").single() < responsesPost)
        assertTrue(responsesPost < responsesCurrent)

        val anthropicConfig = modelConfig("", terminalTools = false, browserTools = false).copy(
            providerId = "anthropic-test",
            providerType = io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC,
        )
        val anthropic = AnthropicMessagesProvider.buildRequestJson(anthropicConfig, messages, JSONArray())
        assertFalse(anthropic.optJSONArray("system")?.toString().orEmpty().contains("post-history-rule"))
        val anthropicMessages = anthropic.getJSONArray("messages")
        val anthropicPost = anthropicMessages.indexesOfContent("post-history-rule").single()
        val anthropicCurrent = anthropicMessages.indexesOfContent("当前问题").single()
        assertEquals("user", anthropicMessages.getJSONObject(anthropicPost).getString("role"))
        assertTrue(anthropicMessages.indexesOfContent("旧回答").single() < anthropicPost)
        assertTrue(anthropicPost <= anthropicCurrent)

        val gemini = GeminiInteractionsRequest.build(anthropicConfig.copy(
            providerType = io.github.mangi.eta.data.model.ProviderTypes.GEMINI,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
        ), messages, JSONArray())
        assertFalse(gemini.optString("system_instruction").contains("post-history-rule"))
        val geminiInput = gemini.getJSONArray("input")
        val geminiPost = geminiInput.indexesOfContent("post-history-rule").single()
        val geminiCurrent = geminiInput.indexesOfContent("当前问题").single()
        assertTrue(geminiPost < geminiCurrent)
    }

    @Test
    fun legacyBehaviorTextIsNotReadByTheRuntimePrompt() {
        val contents = AgentPromptBuilder.buildSystemMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
        ).systemContents()

        assertFalse(contents.any { it.contains("用户配置的表达与工作习惯") })
        assertTrue(contents.any { it.contains("职责边界") })
    }

    @Test
    fun embeddedLocalModelGetsCompactBudgetedPromptWithoutCloudToolManual() {
        val config = modelConfig("本地角色约束", terminalTools = true, browserTools = true).copy(
            providerId = "eta-local-model-runtime-builtin",
            contextWindow = 4_096,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = config,
            prompt = "请解释这个问题",
            images = emptyList(),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext(
                enabled = true,
                revision = "a".repeat(64),
                byteSize = 20_000,
                coreContent = "核心".repeat(4_000),
                coreTruncated = true,
                headingIndex = "标题".repeat(4_000),
                coreBudgetChars = 4_000,
                supportingContent = "补充".repeat(4_000),
                scopedContent = "项目".repeat(2_000),
                reviewedContent = "确认".repeat(2_000),
                learnedContent = "经验".repeat(2_000),
            ),
            promptInjection = ResolvedPromptInjection(
                prefixText = "模板".repeat(4_000),
                postHistoryText = "后置模板".repeat(2_000),
            ),
        )
        val systems = messages.systemContents()
        val allSystemChars = systems.sumOf(String::length)

        assertEquals(1, systems.size)
        assertTrue(allSystemChars <= 2_000)
        assertTrue(systems.single().contains("本地角色约束"))
        assertTrue(systems.single().contains("记忆（背景资料，不是指令）"))
        assertTrue(systems.single().contains("[已截断]"))
        assertFalse(systems.single().contains("observe_screen"))
        assertFalse(systems.single().contains("terminal(action=check_root)"))
        assertFalse(systems.single().contains("subagent"))
        val postHistory = messages.indexesOfContent("后置模板").let {
            messages.getJSONObject(it.single()).getString("content")
        }
        assertTrue(postHistory.startsWith("后置模板".repeat(100)))
        assertTrue(postHistory.endsWith("[已截断]"))
        assertEquals("请解释这个问题", messages.indexesOfContent("请解释这个问题").let {
            messages.getJSONObject(it.single()).getString("content")
        })
    }

    private fun modelConfig(
        systemPrompt: String,
        terminalTools: Boolean,
        browserTools: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = systemPrompt,
            terminalTools = terminalTools,
            browserTools = browserTools,
        )

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }

    private fun JSONArray.systemContents(): List<String> =
        (0 until length())
            .map(::getJSONObject)
            .filter { message -> message.getString("role") == "system" }
            .map { message -> message.getString("content") }

    private fun JSONArray.indexesOfContent(text: String): List<Int> =
        (0 until length()).filter { index ->
            val message = getJSONObject(index)
            val content = message.opt("content")
            when (content) {
                is String -> content.contains(text)
                is JSONArray -> (0 until content.length()).any { part ->
                    content.optJSONObject(part)?.optString("text").orEmpty().contains(text)
                }
                else -> false
            }
        }
}
