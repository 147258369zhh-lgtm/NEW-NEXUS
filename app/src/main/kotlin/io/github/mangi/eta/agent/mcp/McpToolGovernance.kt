package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject

internal enum class McpRisk { SAFE, REVIEW }

internal data class McpToolAssessment(
    val accepted: Boolean,
    val risk: McpRisk,
    val reason: String,
)

/** Keeps remote schemas bounded and makes risky tools visible before they are enabled. */
internal object McpToolGovernance {
    fun assess(tool: McpToolDefinition): McpToolAssessment {
        val name = tool.name.trim()
        if (name.isBlank() || name.length > 80 || !name.matches(Regex("[A-Za-z0-9._-]+"))) {
            return McpToolAssessment(false, McpRisk.REVIEW, "tool name is invalid")
        }
        val schema = runCatching { JSONObject(tool.inputSchemaJson) }.getOrNull()
            ?: return McpToolAssessment(false, McpRisk.REVIEW, "input schema is not JSON")
        if (tool.inputSchemaJson.toByteArray().size > MAX_SCHEMA_BYTES) {
            return McpToolAssessment(false, McpRisk.REVIEW, "input schema is too large")
        }
        val text = "$name ${tool.description}".lowercase()
        val risky = RISKY_WORDS.any(text::contains) || schema.optJSONObject("properties")?.length() ?: 0 > 40
        return McpToolAssessment(true, if (risky) McpRisk.REVIEW else McpRisk.SAFE,
            if (risky) "requires explicit user review" else "accepted")
    }

    private val RISKY_WORDS = listOf("delete", "remove", "send", "publish", "payment", "transfer", "execute", "shell", "密码", "删除", "发送")
    private const val MAX_SCHEMA_BYTES = 64 * 1024
}
