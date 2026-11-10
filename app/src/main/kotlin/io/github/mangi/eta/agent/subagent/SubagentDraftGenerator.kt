package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object SubagentDraftGenerator {
    suspend fun generate(seed: String): SubagentRoleSections = withContext(Dispatchers.IO) {
        require(seed.isNotBlank()) { "请先粘贴任务描述、选中内容或操作记录" }
        val current = RuntimeConfigRepository.currentRuntimeConfig()
            ?: error("请先在模型提供商中配置并启用主模型")
        val config = current.copy(
            systemPrompt = "你是严谨的 Agent 角色设计师。只输出调用方要求的 JSON 对象。",
            thinkingEnabled = false,
        )
        val response = AgentModelClient.complete(
            config = config,
            prompt = SubagentRoleAuthoring.generationPrompt(seed),
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult("草稿生成阶段不允许调用工具")
            },
            memoryContext = AgentMemoryContext.DISABLED,
            textOnlyFallback = true,
        )
        SubagentRoleAuthoring.parseGeneratedJson(response.content)
    }
}
