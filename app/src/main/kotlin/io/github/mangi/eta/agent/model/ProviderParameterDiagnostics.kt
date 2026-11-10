package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** 有界的服务端参数诊断，不承载失败分类或重试策略。 */
internal object ProviderParameterDiagnostics {
    /** 只投影协议枚举和已知字段位置，服务端 message/description 可能回显请求内容。 */
    fun describe(error: JSONObject?): String? {
        if (error == null) return null
        val codes = listOf("status", "code", "type").map(error::optString)
            .filter { it in parameterErrorCodes }.distinct()
        val fields = linkedSetOf<String>()
        safeParameterPath(error.optString("param"))?.let(fields::add)
        val details = error.optJSONArray("details")
        for (index in 0 until (details?.length() ?: 0)) {
            val violations = details?.optJSONObject(index)?.optJSONArray("fieldViolations") ?: continue
            for (violation in 0 until violations.length()) {
                safeParameterPath(violations.optJSONObject(violation)?.optString("field").orEmpty())
                    ?.let(fields::add)
                if (fields.size >= 3) break
            }
            if (fields.size >= 3) break
        }
        val hint = messageHint(error.optString("message"))
        return (codes.take(1) + fields.take(3).map { "字段 $it" } + listOfNotNull(hint))
            .distinct()
            .joinToString("；")
            .ifEmpty { null }
    }

    private fun messageHint(raw: String): String? {
        val message = raw.lowercase()
        return when {
            listOf("tool", "function", "schema").any(message::contains) -> "工具定义不受支持"
            listOf("message", "content", "role", "tool_call_id").any(message::contains) -> "消息历史格式无效"
            listOf("stream", "include_usage").any(message::contains) -> "流式参数不受支持"
            listOf("reasoning", "thinking", "effort").any(message::contains) -> "思考参数不受支持"
            listOf("max_tokens", "max_completion_tokens", "output token").any(message::contains) -> "输出长度参数无效"
            listOf("temperature", "top_p").any(message::contains) -> "采样参数不受支持"
            "model" in message -> "模型名称或权限无效"
            else -> null
        }
    }

    private fun safeParameterPath(raw: String): String? {
        if (raw.length > 192 || !parameterPath.matches(raw)) return null
        val segments = raw.split('.')
        if (segments.first().substringBefore('[') !in requestParameterRoots) return null
        return segments.joinToString(".") { segment ->
            if (segment.substringBefore('[') in publicParameterNames) segment else "<自定义字段>"
        }
    }

    private val parameterErrorCodes = setOf(
        "INVALID_ARGUMENT", "FAILED_PRECONDITION", "invalid_request", "invalid_argument",
        "invalid_request_error", "unsupported_parameter", "model_not_found",
    )
    private val requestParameterRoots = setOf(
        "model", "input", "system_instruction", "tools", "generation_config", "response_format",
        "stream", "store", "previous_interaction_id", "background", "safety_settings", "service_tier",
        "messages", "max_tokens", "max_completion_tokens", "max_output_tokens", "tool_choice",
        "stream_options", "include_usage", "reasoning", "reasoning_effort", "temperature", "top_p",
    )
    private val publicParameterNames = requestParameterRoots + setOf(
        "content", "type", "role", "name", "description", "function", "parameters", "properties", "items",
        "required", "additionalProperties", "oneOf", "anyOf", "allOf", "enum", "const", "format",
        "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern",
        "thinking_level", "thinking_summaries", "tool_choice", "max_output_tokens", "temperature", "top_p",
        "text", "data", "uri", "mime_type", "schema", "arguments", "result", "call_id", "signature",
    )
    private val parameterPath = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]{1,6}])?(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]{1,6}])?)*")
}
