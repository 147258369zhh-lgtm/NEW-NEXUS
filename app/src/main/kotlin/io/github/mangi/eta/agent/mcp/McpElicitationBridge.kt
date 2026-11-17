package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.agent.runtime.RunUserInput
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpProtocolMode
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** Protocol forms are projected onto the existing durable input chain; answers grant no permissions. */
internal class McpElicitationBridge(private val context: Context, private val server: McpServerSetting) {
    fun handle(params: JSONObject): JSONObject {
        RuntimeInvocationScope.controller?.throwIfStopped() ?: error("MCP_INPUT_RUN_CONTEXT_REQUIRED")
        require(params.optString("mode", "form") == "form") { "MCP_URL_ELICITATION_NOT_SUPPORTED: 请在受控浏览器完成登录；普通表单不能收集凭据" }
        val fields = fields(params)
        val binding = McpElicitationScope.current
        val progress = binding?.session?.progress(binding.inputRequestId)
        val result = JSONObject(progress?.optJSONObject("values")?.toString() ?: "{}")
        val completedPage = progress?.optInt("completed_page", -1) ?: -1
        fields.chunked(6).forEachIndexed { page, group ->
            if (page <= completedPage) return@forEachIndexed
            val questions = JSONArray(group.map { it.question() })
            var pageComplete = false
            var validationMessage = ""
            for (attempt in 0 until 3) {
                val response = McpElicitationScope.withPage(page) { RunUserInput.execute(context, JSONObject()
                    .put("question", "MCP「${server.name}」：${params.optString("message").take(1200)}${if (fields.size > 6) "（${page + 1}/${(fields.size + 5) / 6}）" else ""}$validationMessage")
                    .put("questions", questions).put("source", "mcp")
                    .put("mcp_server_id", server.id), timeoutMillis = 5 * 60_000L) }
                when (response.optString("status")) {
                    "answered" -> Unit
                    "declined" -> return JSONObject().put("action", "decline")
                    else -> return JSONObject().put("action", "cancel")
                }
                val answers = response.getJSONArray("answers")
                try {
                    val values = group.map { field ->
                        val answer = (0 until answers.length()).map { answers.getJSONObject(it) }.single { it.optString("id", it.optString("qid")) == field.name }
                        field.name to field.value(answer)
                    }
                    values.forEach { (name, value) -> if (value != null) result.put(name, value) else result.remove(name) }
                    binding?.session?.saveProgress(binding.inputRequestId, result, page)
                    pageComplete = true
                    break
                } catch (_: IllegalArgumentException) {
                    validationMessage = "\n上次回答未满足字段类型或范围，请按说明修正后重新提交。"
                } catch (_: java.net.URISyntaxException) {
                    validationMessage = "\n网址格式不符，请输入完整的有效地址。"
                } catch (_: java.time.format.DateTimeParseException) {
                    validationMessage = "\n日期格式不符，请使用 YYYY-MM-DD 或含时区的日期时间。"
                }
            }
            require(pageComplete) { "MCP_INPUT_VALIDATION_LIMIT" }
        }
        return JSONObject().put("action", "accept").put("content", result)
    }

    /** Used only with a previously committed native form and the matching opaque server request. */
    fun acceptRecovered(session: McpContinuationSession, requestId: String, page: Int, params: JSONObject, answer: JSONObject) {
        when (answer.optString("status")) {
            "declined" -> { session.saveResponse(requestId, JSONObject().put("action", "decline")); return }
            "answered" -> Unit
            else -> { session.saveResponse(requestId, JSONObject().put("action", "cancel")); return }
        }
        val pages = fields(params).chunked(6)
        require(page in pages.indices)
        val answers = answer.getJSONArray("answers")
        require(answers.length() == pages[page].size)
        val progress = session.progress(requestId)
        val values = JSONObject(progress.optJSONObject("values")?.toString() ?: "{}")
        pages[page].forEach { field ->
            val row = (0 until answers.length()).map(answers::getJSONObject).single { it.getString("id") == field.name }
            val value = field.value(row)
            if (value != null) values.put(field.name, value) else values.remove(field.name)
        }
        session.saveProgress(requestId, values, page)
    }

    private fun fields(params: JSONObject): List<Field> {
        require(params.optString("mode", "form") == "form") { "MCP_URL_ELICITATION_NOT_SUPPORTED" }
        val schema = params.getJSONObject("requestedSchema")
        require(schema.optString("type") == "object") { "MCP_INPUT_SCHEMA_UNSUPPORTED" }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val keys = properties.keys().asSequence().toList()
        require(keys.size in 1..24 && keys.all { it.length in 1..100 }) { "MCP_INPUT_PROPERTY_LIMIT" }
        val requiredJson = schema.optJSONArray("required") ?: JSONArray()
        val required = (0 until requiredJson.length()).map { requiredJson.getString(it) }.toSet()
        require(required.all { it in keys }) { "MCP_INPUT_REQUIRED_PROPERTY_UNKNOWN" }
        // Validate the entire form before asking any of it. Nested objects are never flattened silently.
        return keys.map { name -> Field(name, properties.getJSONObject(name), name in required) }
    }

    private class Field(val name: String, val schema: JSONObject, val required: Boolean) {
        private val type = schema.optString("type", "string")
        private val choices: List<Pair<Any, String>>
        private val multiple = type == "array"
        init {
            require(!SECRET.containsMatchIn(name + " " + schema.optString("title") + " " + schema.optString("description"))) { "MCP_SECRET_INPUT_REQUIRES_SECURE_FLOW" }
            require(type in setOf("string", "integer", "number", "boolean", "array")) { "MCP_INPUT_NESTED_SCHEMA_UNSUPPORTED" }
            choices = if (type == "boolean") listOf(true to "是", false to "否") else {
                val source = if (multiple) schema.getJSONObject("items") else schema
                require(!multiple || source.optString("type", "string") == "string") { "MCP_INPUT_ARRAY_SCHEMA_UNSUPPORTED" }
                val enumeration = source.optJSONArray("enum")
                val alternatives = source.optJSONArray(if (multiple) "anyOf" else "oneOf")
                when {
                    enumeration != null -> (0 until enumeration.length()).map { index ->
                        val value = enumeration.get(index)
                        require(value is String) { "MCP_INPUT_ENUM_SCHEMA_UNSUPPORTED" }
                        value to (source.optJSONArray("enumNames")?.optString(index)?.takeIf(String::isNotBlank) ?: value)
                    }
                    alternatives != null -> (0 until alternatives.length()).map { index ->
                        val entry = alternatives.getJSONObject(index)
                        val value = entry.get("const")
                        require(value is String)
                        value to entry.optString("title", value as String)
                    }
                    else -> emptyList()
                }
            }
            require(!multiple || choices.isNotEmpty()) { "MCP_INPUT_ARRAY_ENUM_REQUIRED" }
            require(choices.size <= 12 && choices.map { it.first }.distinct().size == choices.size)
            require(choices.all { it.first.toString().length <= 200 && it.second.length in 1..200 }) { "MCP_INPUT_ENUM_LIMIT" }
            require(!schema.has("pattern")) { "MCP_INPUT_PATTERN_SCHEMA_UNSUPPORTED" }
            listOf("minimum", "maximum").forEach { bound ->
                if (schema.has(bound)) require(schema.opt(bound) is Number && schema.get(bound).toString().toBigDecimalOrNull() != null) { "MCP_INPUT_NUMERIC_BOUND_INVALID" }
            }
            require(!schema.has("exclusiveMinimum") && !schema.has("exclusiveMaximum") && !schema.has("multipleOf")) { "MCP_INPUT_NUMERIC_SCHEMA_UNSUPPORTED" }
            require(schema.optString("format") in setOf("", "email", "uri", "url", "date", "date-time")) { "MCP_INPUT_FORMAT_UNSUPPORTED" }
            require(!schema.has("anyOf") && !schema.has("allOf") && !schema.has("not")) { "MCP_INPUT_SCHEMA_UNSUPPORTED" }
            if (type == "string") require(schema.optInt("maxLength", 4000) in 1..4000 && schema.optInt("minLength", 0) in 0..schema.optInt("maxLength", 4000))
            if (multiple) require(schema.optInt("minItems", 0) in 0..choices.size && schema.optInt("maxItems", choices.size) in 1..choices.size)
        }
        fun question(): JSONObject = JSONObject().put("id", name)
            .put("question", buildString {
                append(schema.optString("title", name).ifBlank { name }.take(300))
                schema.optString("description").takeIf(String::isNotBlank)?.let { append("\n").append(it.take(1000)) }
                if (type == "integer" || type == "number") append("\n请输入${if (type == "integer") "整数" else "数字"}")
                schema.opt("minimum")?.let { append("，最小值 ").append(it) }
                schema.opt("maximum")?.let { append("，最大值 ").append(it) }
                schema.optString("format").takeIf(String::isNotBlank)?.let { append("\n格式：").append(it) }
            }).put("kind", if (multiple) "multiple" else if (choices.isNotEmpty()) "single" else "text")
            .put("required", required && !(multiple && schema.optInt("minItems", 0) == 0) && !(type == "string" && choices.isEmpty() && schema.optInt("minLength", 0) == 0))
            .put("allow_other", false)
            .put("max_length", schema.optInt("maxLength", 4000))
            .put("min_selections", if (multiple) schema.optInt("minItems", if (required) 1 else 0) else 0)
            .put("max_selections", if (multiple) schema.optInt("maxItems", choices.size) else 1)
            .put("options", JSONArray(choices.mapIndexed { index, entry -> JSONObject().put("id", "choice-$index").put("label", entry.second) }))

        fun value(answer: JSONObject): Any? {
            val selected = answer.optJSONArray("selected_ids") ?: JSONArray()
            val text = answer.optString("text").trim()
            if (selected.length() == 0 && text.isEmpty()) {
                if (required && multiple && schema.optInt("minItems", 0) == 0) return JSONArray()
                if (required && type == "string" && choices.isEmpty() && schema.optInt("minLength", 0) == 0) return ""
                require(!required); return null
            }
            if (choices.isNotEmpty()) {
                val values = (0 until selected.length()).map { index ->
                    val id = selected.getString(index)
                    require(id.startsWith("choice-")); choices[id.removePrefix("choice-").toInt()].first
                }
                if (multiple) {
                    require(values.size >= schema.optInt("minItems", 0) && values.size <= schema.optInt("maxItems", choices.size))
                    return JSONArray(values)
                }
                require(values.size == 1); return values.single()
            }
            return when (type) {
                "integer", "number" -> {
                    val number = requireNotNull(text.toBigDecimalOrNull()) { "MCP_INPUT_NUMBER_INVALID" }
                    if (type == "integer") require(number.stripTrailingZeros().scale() <= 0) { "MCP_INPUT_INTEGER_REQUIRED" }
                    require(number.precision() <= 1000 && kotlin.math.abs(number.scale().toLong()) <= 1000) { "MCP_INPUT_NUMBER_LIMIT" }
                    schema.opt("minimum")?.toString()?.toBigDecimalOrNull()?.let { require(number >= it) { "MCP_INPUT_BELOW_MINIMUM" } }
                    schema.opt("maximum")?.toString()?.toBigDecimalOrNull()?.let { require(number <= it) { "MCP_INPUT_ABOVE_MAXIMUM" } }
                    number
                }
                else -> {
                    require(text.length >= schema.optInt("minLength", 0) && text.length <= schema.optInt("maxLength", 4000))
                    when (schema.optString("format")) {
                        "email" -> require(text.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
                        "uri" -> require(java.net.URI(text).isAbsolute)
                        "date" -> java.time.LocalDate.parse(text)
                        "date-time" -> java.time.OffsetDateTime.parse(text)
                        "url" -> require(java.net.URI(text).let { it.isAbsolute && it.scheme in setOf("http", "https") })
                        "" -> Unit
                        else -> error("MCP_INPUT_FORMAT_UNSUPPORTED")
                    }
                    text
                }
            }
        }
    }

    private companion object {
        val SECRET = Regex("password|passwd|passcode|secret|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|credential|private[_ -]?key|密钥|口令|密码|付款凭据", RegexOption.IGNORE_CASE)
    }
}

internal object McpInputRoundTrips {
    fun capabilities(enabled: Boolean): JSONObject = if (enabled) JSONObject().put("elicitation", JSONObject().put("form", JSONObject())) else JSONObject()

    fun complete(initial: JSONObject, baseParams: JSONObject, handler: ((JSONObject) -> JSONObject)?,
        recovery: McpRecoveryContext? = null, method: String = "tools/call", send: (JSONObject) -> JSONObject): JSONObject {
        var result = initial
        var session = recovery?.begin(method, baseParams, initial)
        repeat(8) {
            if (result.optString("resultType") != "input_required") return result
            RuntimeInvocationScope.controller?.throwIfStopped()
            val requests = result.optJSONObject("inputRequests")
            require(requests != null || result.has("requestState")) { "MCP_INPUT_REQUIRED_INVALID" }
            require(requests == null || requests.length() <= 8) { "MCP_INPUT_REQUEST_LIMIT" }
            val responses = JSONObject()
            requests?.keys()?.asSequence()?.toList()?.forEach { id ->
                require(id.length in 1..200)
                val request = requests.getJSONObject(id)
                require(request.optString("method") == "elicitation/create") { "MCP_INPUT_METHOD_UNSUPPORTED" }
                val callback = handler ?: error("MCP_INPUT_RUN_CONTEXT_REQUIRED")
                val current = session
                val response = current?.response(id) ?: if (current != null) McpElicitationScope.withInput(current, id) {
                    callback(request.optJSONObject("params") ?: JSONObject())
                } else callback(request.optJSONObject("params") ?: JSONObject())
                current?.saveResponse(id, response)
                responses.put(id, response)
            }
            val retry = JSONObject(baseParams.toString())
            if (requests != null) retry.put("inputResponses", responses)
            if (result.has("requestState")) {
                val opaque = result.get("requestState")
                require(opaque is String && opaque.length <= 1024 * 1024) { "MCP_REQUEST_STATE_INVALID" }
                retry.put("requestState", opaque)
            }
            session?.ready(retry)
            session?.dispatched() // Durable dispatch precedes the wire send; unknown outcome is never replayed.
            result = send(retry)
            session?.received(result)
            if (result.optString("resultType") == "input_required") {
                if (result.optString("requestState").isEmpty()) { session?.unrecoverable("MCP_OPAQUE_STATE_REQUIRED"); session = null }
                else if (session == null) session = recovery?.begin(method, baseParams, result)
            }
        }
        error("MCP_INPUT_ROUND_TRIP_LIMIT")
    }

    fun reverseReply(message: JSONObject, handler: ((JSONObject) -> JSONObject)?, version: String?): JSONObject {
        val id = message.opt("id")
        require(id is Number || id is String && id.length <= 200) { "MCP_REVERSE_ID_INVALID" }
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
        if (message.optString("method") != "elicitation/create" || handler == null || version != McpProtocolMode.LEGACY) {
            return reply.put("error", JSONObject().put("code", -32601).put("message", "Client request method is not supported"))
        }
        return try { reply.put("result", handler(message.optJSONObject("params") ?: JSONObject())) }
        catch (stopped: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { throw stopped }
        catch (paused: io.github.mangi.eta.agent.runtime.AgentRunPauseException) { throw paused }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { reply.put("error", JSONObject().put("code", -32602).put("message", "Elicitation schema or answer is not supported")) }
    }
}
