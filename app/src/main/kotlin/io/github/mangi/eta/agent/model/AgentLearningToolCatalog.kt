package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentLearningToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "learning_feedback",
                description = "记录用户明确给出的运行纠正、评分或失败标签，供后续评测使用。只在用户明确要求记录时调用，不保存完整提示词、密码或验证码。",
                parameters = JSONObject().put("type", "object")
                    .put("properties", JSONObject()
                        .put("run_id", JSONObject().put("type", "string"))
                        .put("outcome", JSONObject().put("type", "string").put("enum", JSONArray().put("success").put("failure").put("correction")))
                        .put("rating", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 5))
                        .put("correction", JSONObject().put("type", "string").put("maxLength", 2_000))
                        .put("tags", JSONObject().put("type", "array").put("maxItems", 12))
                    )
                    .put("required", JSONArray().put("run_id").put("outcome")),
            ),
        )
    }
}
