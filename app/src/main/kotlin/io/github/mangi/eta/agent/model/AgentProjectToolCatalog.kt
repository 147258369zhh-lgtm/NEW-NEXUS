package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentProjectToolCatalog {
    val names = setOf("project_workspace_status", "project_workspace_publish")
    fun appendTo(tools: JSONArray) {
        tools.put(AgentToolSchema.function("project_workspace_status", "查看本次运行绑定的项目工作副本、原目录只读状态、文件变更与发布指纹。首次使用按授权目录建立副本；文件和终端均使用该副本。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
                .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)))
                .put("additionalProperties", false)))
        tools.put(AgentToolSchema.function("project_workspace_publish", "将明确选择的工作副本文件写回原项目。先读 project_workspace_status，提供其 change_fingerprint。必须本机确认；原文件已变化则拒绝。不会删除原文件，不自动重放未确认发布。",
            JSONObject().put("type", "object").put("properties", JSONObject()
                .put("paths", JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 32).put("uniqueItems", true)
                    .put("items", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 2048)))
                .put("change_fingerprint", JSONObject().put("type", "string").put("minLength", 64).put("maxLength", 64)))
                .put("required", JSONArray(listOf("paths", "change_fingerprint"))).put("additionalProperties", false)))
    }
}
