package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object EtaAppControlToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "eta_control",
                description = "Discover and operate every public Nexus capability through stable ids. Use list/search before guessing. " +
                    "get reads current values; execute performs the declared operation; audit returns recent software-butler calls with " +
                    "credentials redacted; undo restores an eligible settings auditId after checking for later changes. " +
                    "Parameters are schema-validated. CONFIRM capabilities follow the Run's approval policy: full access executes within its task; smart uses an independent review; manual asks the user. Agents cannot change the approval mode or issue their own authority.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(listOf("list", "search", "get", "execute", "audit", "undo"))),
                            )
                            .put("id", JSONObject().put("type", "string"))
                            .put("auditId", JSONObject().put("type", "string"))
                            .put("query", JSONObject().put("type", "string"))
                            .put("category", JSONObject().put("type", "string"))
                            .put("parameters", JSONObject().put("type", "object"))
                            .put(
                                "limit",
                                JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 300),
                            ),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
