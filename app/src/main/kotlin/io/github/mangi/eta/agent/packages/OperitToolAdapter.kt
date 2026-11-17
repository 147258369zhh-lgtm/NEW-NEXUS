package io.github.mangi.eta.agent.packages

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Translates only known contracts; every resulting native call still traverses the Run executor. */
internal object OperitToolAdapter {
    fun invoke(name: String, args: JSONObject, native: (String, JSONObject) -> JSONObject): JSONObject {
        fun call(tool: String, parameters: JSONObject = JSONObject()) = native(tool, parameters)
        fun data(result: JSONObject) = if (result.optBoolean("ok", true)) JSONObject().put("ok", true).put("data", result) else result
        fun fileArgs() = JSONObject().also { target ->
            listOf("path", "environment", "identity", "cwd", "expected_revision").forEach { key -> if (args.has(key)) target.put(key, args.get(key)) }
        }
        return when (name) {
            "call_chat_model" -> {
                val type = args.getString("function_type").also { require(it.length in 1..80) }
                val turns = when (val raw = args.get("turns")) { is String -> JSONArray(raw); is JSONArray -> raw; else -> error("PACKAGE_MODEL_TURNS_INVALID") }
                require(turns.length() in 1..32) { "PACKAGE_MODEL_TURNS_LIMIT" }
                val normalized = JSONArray()
                for (index in 0 until turns.length()) {
                    val turn = turns.getJSONObject(index)
                    val role = turn.getString("kind").uppercase()
                    require(role in setOf("SYSTEM", "USER", "ASSISTANT")) { "PACKAGE_MODEL_TEXT_TURNS_ONLY" }
                    normalized.put(JSONObject().put("role", role.lowercase()).put("content", turn.getString("content")))
                }
                val prompt = "Process this bounded text-model request. Follow the supplied conversation roles; return only the requested answer. No tools.\n" + normalized
                require(prompt.length <= 16_000) { "PACKAGE_MODEL_INPUT_LIMIT" }
                val result = call("subagent", JSONObject().put("subagent_type", "package-text-model")
                    .put("description", "插件模型请求：$type".take(120)).put("prompt", prompt)
                    .put("role", "Process the supplied text conversation. Do not execute tools or claim device actions.")
                    .put("tools", JSONArray()).apply {
                        if (args.has("enable_thinking") && !boolean(args, "enable_thinking", true)) put("reasoning_effort", "off")
                    })
                if (!result.optBoolean("ok")) result else JSONObject().put("ok", true).put("data", JSONObject()
                    .put("text", result.getString("summary")).put("finishReason", "stop")
                    .put("modelName", result.optString("model_id")).put("subagentId", result.optString("subagent_id")))
            }
            "http_request" -> {
                if (boolean(args, "ignore_ssl", false)) return denied("PACKAGE_TLS_REQUIRED")
                if (boolean(args, "stream", false)) return denied("PACKAGE_STREAM_USE_SSE")
                val headers = objectValue(args.opt("headers"))
                val method = args.optString("method", "GET").uppercase(java.util.Locale.ROOT)
                val bodyType = args.optString("body_type", "text")
                val bodyValue = args.opt("body")
                val contentType = headers.keys().asSequence().firstOrNull { it.equals("content-type", true) }?.let(headers::getString)
                    ?: when (bodyType) { "json" -> "application/json; charset=utf-8"; "form" -> "application/x-www-form-urlencoded"; else -> "text/plain; charset=utf-8" }
                val body = if (bodyType == "form" && bodyValue != null && bodyValue != JSONObject.NULL) {
                    val values = objectValue(bodyValue)
                    values.keys().asSequence().joinToString("&") { key -> encodeForm(key) + "=" + encodeForm(values.optString(key)) }
                } else bodyValue?.takeUnless { it == JSONObject.NULL }?.toString()
                val parameters = JSONObject().put("method", method).put("headers", headers).put("content_type", contentType)
                    .put("max_bytes", 48 * 1024)
                body?.let { parameters.put("body", it) }
                val response = call("connector_call", JSONObject().put("protocol", "rest").put("action", "invoke")
                    .put("endpoint", args.getString("url")).put("arguments", parameters))
                if (response.optBoolean("ok", true)) JSONObject().put("ok", true).put("data", response.getJSONObject("result")) else response
            }
            "read_file_full", "read_file_part", "read_file_chunk" -> {
                val parameters = fileArgs().put("max_bytes", 16000).put("max_lines", 2000)
                if (name == "read_file_chunk") parameters.put("offset_bytes", args.getLong("offset_bytes"))
                if (name == "read_file_part") {
                    val start = args.optInt("start_line", 1).coerceAtLeast(1)
                    parameters.put("start_line", start).put("max_lines", (args.optInt("end_line", start + 199) - start + 1).coerceIn(1, 2000))
                }
                data(call("read_file", parameters))
            }
            "write_file", "create_file" -> data(call("write_file", fileArgs().put("content", if (name == "create_file") args.getString("new") else args.getString("content"))
                .put("append", boolean(args, "append", false))))
            "edit_file", "apply_file" -> {
                if (name == "apply_file" && args.optString("type") !in setOf("edit", "replace", "create")) return denied("PACKAGE_FILE_OPERATION_UNSUPPORTED")
                if (name == "apply_file" && args.optString("type") == "create") data(call("write_file", fileArgs().put("content", args.getString("new"))))
                else data(call("edit_file", fileArgs().put("old_text", args.getString("old")).put("new_text", args.getString("new"))))
            }
            "file_info", "file_exists" -> {
                val result = call("stat_file", fileArgs())
                if (result.optBoolean("ok", true)) {
                    val stat = result.getJSONObject("file")
                    stat.put("exists", true).put("isDirectory", stat.optString("kind") == "directory")
                    JSONObject().put("ok", true).put("data", stat)
                } else if (name == "file_exists" && result.optString("code") == "FILE_NOT_FOUND") {
                    JSONObject().put("ok", true).put("data", JSONObject().put("exists", false))
                } else result
            }
            "list_files" -> data(call("list_directory", fileArgs().put("limit", 200)))
            "make_directory", "delete_file" -> data(call(name, fileArgs().apply {
                if (name == "make_directory") put("create_parents", boolean(args, "create_parents", false))
                else put("recursive", boolean(args, "recursive", false))
            }))
            "copy_file", "move_file" -> {
                if (args.optString("source_environment", args.optString("environment", "android")) != args.optString("dest_environment", args.optString("environment", "android")))
                    return denied("PACKAGE_CROSS_ENVIRONMENT_COPY_UNSUPPORTED")
                data(call(name, JSONObject().put("source", args.getString("source")).put("destination", args.getString("destination"))
                    .put("environment", args.optString("source_environment", args.optString("environment", "android")))))
            }
            "find_files" -> data(call("glob_files", fileArgs().put("pattern", args.getString("pattern")).put("limit", 200)))
            "grep_code" -> data(call("grep_files", fileArgs().put("query", args.getString("pattern")).put("limit", 200)))
            "execute_shell", "execute_hidden_terminal_command", "create_terminal_session", "execute_in_terminal_session", "close_terminal_session" -> {
                val parameters = JSONObject().put("action", when (name) { "create_terminal_session" -> "open"; "close_terminal_session" -> "close"; else -> "exec" })
                listOf("cwd", "identity").forEach { key -> if (args.has(key)) parameters.put(key, args.get(key)) }
                parameters.put("environment", args.optString("environment", if (name == "execute_shell") "android" else "linux"))
                if (name !in setOf("create_terminal_session", "close_terminal_session")) parameters.put("command", args.getString("command"))
                if (args.has("session_id")) parameters.put("session_id", args.getString("session_id"))
                if (args.has("timeout_ms")) parameters.put("timeout_ms", args.getInt("timeout_ms").coerceIn(1, 120000))
                val result = call("terminal", parameters)
                if (result.optBoolean("ok", true) || result.optBoolean("timed_out")) {
                    result.put("output", result.optString("stdout")).put("error", result.optString("stderr"))
                        .put("exitCode", result.optInt("exit_code")).put("timedOut", result.optBoolean("timed_out"))
                    JSONObject().put("ok", true).put("data", result)
                } else result
            }
            "device_info", "get_device_info" -> data(call("device_status"))
            "get_device_location" -> data(call("get_current_location"))
            "get_notifications" -> data(call("recent_notifications", JSONObject().put("limit", args.optInt("limit", 10).coerceIn(1, 50))))
            "start_app", "app_launch" -> data(call("launch_app", JSONObject().put("package_name", args.getString("package_name"))))
            "sleep" -> data(call("wait", JSONObject().put("duration_ms", args.optInt("duration_ms", 1000).coerceIn(1, 10000))))
            "set_input_text" -> data(call("type_text", JSONObject().put("text", args.getString("text"))))
            "tap", "long_press" -> {
                val parameters = JSONObject().put("x", args.getInt("x")).put("y", args.getInt("y")).put("coordinate_space", "screen")
                if (args.has("duration_ms")) parameters.put("duration_ms", args.getInt("duration_ms"))
                data(call(name, parameters))
            }
            "press_key" -> data(call("press_key", JSONObject().put("button", args.optString("key", args.optString("button")))))
            "get_page_info", "capture_screenshot" -> data(call("observe_screen", JSONObject()))
            "browser_navigate" -> data(call("browser_use", JSONObject().put("action", "navigate").put("url", args.getString("url"))))
            else -> denied("PACKAGE_OPERIT_API_UNAVAILABLE").put("api", name)
        }
    }

    private fun encodeForm(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun objectValue(value: Any?): JSONObject = when (value) { is JSONObject -> JSONObject(value.toString()); is String -> JSONObject(value.ifBlank { "{}" }); else -> JSONObject() }
    private fun boolean(args: JSONObject, key: String, default: Boolean) = when (val value = args.opt(key)) {
        null -> default
        is Boolean -> value
        is String -> when (value.lowercase()) { "true", "1" -> true; "false", "0", "" -> false; else -> error("INVALID_BOOLEAN:$key") }
        else -> error("INVALID_BOOLEAN:$key")
    }
    private fun denied(code: String) = JSONObject().put("ok", false).put("code", code)
}
