package io.github.mangi.eta.fork.learning

import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 学习工具只创建候选和检索已确认规则；晋升必须由用户在审计页面完成。 */
internal class AgentLearningTools(
    private val repository: LearningRepository,
) {
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val arguments = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { throwable -> return failure("LEARNING_ARGUMENTS_INVALID", throwable.message) }
        return when (call.name) {
            SEARCH_TOOL -> {
                val query = arguments.optString("query").trim()
                if (query.isBlank()) return failure("LEARNING_QUERY_REQUIRED", "query 不能为空")
                val rules = runBlocking {
                    repository.search(query, arguments.optInt("limit", 12))
                }
                AgentModelClient.ToolResult(JSONObject().put("ok", true).put(
                    "rules",
                    JSONArray().also { array ->
                        rules.forEach { rule ->
                            array.put(JSONObject()
                                .put("id", rule.id)
                                .put("kind", rule.kind.wireValue)
                                .put("title", rule.title)
                                .put("content", rule.content)
                                .put("version", rule.currentVersion))
                        }
                    },
                ).toString())
            }
            PROPOSE_TOOL -> {
                val kind = LearningKind.fromWire(arguments.optString("kind"))
                    ?: return failure("LEARNING_KIND_INVALID", "kind 必须是 fact、preference、procedure 或 failure_fix")
                val result = runBlocking {
                    repository.propose(LearningProposalInput(
                        kind = kind,
                        title = arguments.optString("title"),
                        content = arguments.optString("content"),
                        evidence = arguments.optString("evidence"),
                        sourceRef = arguments.optString("source_ref").ifBlank { "agent-proposal" },
                    ))
                }
                proposalResult(result)
            }
            else -> failure("LEARNING_TOOL_UNKNOWN", "未知学习工具")
        }
    }

    private fun proposalResult(result: LearningProposalResult): AgentModelClient.ToolResult =
        when (result) {
            is LearningProposalResult.Created -> proposalCandidateResult(result.candidate)
            is LearningProposalResult.Existing -> proposalCandidateResult(result.candidate, deduplicated = true)
            LearningProposalResult.BlockedByPolicy -> failure(
                "LEARNING_BLOCKED_BY_POLICY", "用户已禁止记忆，或内容包含不能持久化的高敏感信息",
            )
            LearningProposalResult.Empty -> failure(
                "LEARNING_CONTENT_EMPTY", "候选内容为空或脱敏后不足",
            )
        }

    private fun proposalCandidateResult(
        candidate: LearningCandidateView,
        deduplicated: Boolean = false,
    ): AgentModelClient.ToolResult {
        val rejected = candidate.status == LearningStatus.REJECTED
        return AgentModelClient.ToolResult(JSONObject()
            .put("ok", !rejected)
            .put("code", if (rejected) "LEARNING_BUTLER_REJECTED" else "")
            .put("candidate_id", candidate.id)
            .put("status", candidate.status.wireValue)
            .put("deduplicated", deduplicated)
            .put("butler_review", candidate.reviewSummary)
            .put("requires_user_confirmation", candidate.status == LearningStatus.BUTLER_APPROVED)
            .toString())
    }

    companion object {
        const val SEARCH_TOOL = "learning_search"
        const val PROPOSE_TOOL = "learning_propose"
        val NAMES = setOf(SEARCH_TOOL, PROPOSE_TOOL)

        fun schemas(): JSONArray = JSONArray()
            .put(tool(
                name = SEARCH_TOOL,
                description = "检索已经用户确认并晋升的学习规则。开始可能重复的任务或处理已知失败前先查询。",
                properties = JSONObject()
                    .put("query", property("string", "要检索的任务、技术或错误关键词"))
                    .put("limit", property("integer", "最多返回条数，1-20", minimum = 1, maximum = 20)),
                required = JSONArray().put("query"),
            ))
            .put(tool(
                name = PROPOSE_TOOL,
                description = "把可复用经验提交给软件管家审核。只有通过证据、具体性和可执行性门禁的候选才会进入用户批准区；不会直接修改 MEMORY.md、Skill、权限或系统提示。",
                properties = JSONObject()
                    .put("kind", property("string", "fact、preference、procedure 或 failure_fix"))
                    .put("title", property("string", "简短、可检索的候选标题"))
                    .put("content", property("string", "经过归纳的规则或经验，不含密钥和个人数据"))
                    .put("evidence", property("string", "支持该候选的具体观察或失败证据"))
                    .put("source_ref", property("string", "来源消息、运行或工具标识")),
                required = JSONArray().put("kind").put("title").put("content"),
            ))

        private fun tool(
            name: String,
            description: String,
            properties: JSONObject,
            required: JSONArray,
        ) = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", properties)
                    .put("required", required)))

        private fun property(
            type: String,
            description: String,
            minimum: Int? = null,
            maximum: Int? = null,
        ) = JSONObject()
            .put("type", type)
            .put("description", description)
            .also { target ->
                minimum?.let { target.put("minimum", it) }
                maximum?.let { target.put("maximum", it) }
            }

        private fun failure(code: String, message: String?): AgentModelClient.ToolResult =
            AgentModelClient.ToolResult(JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message.orEmpty().ifBlank { code })
                .toString())
    }
}
