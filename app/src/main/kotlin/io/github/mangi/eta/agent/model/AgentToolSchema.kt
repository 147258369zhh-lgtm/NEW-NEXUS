package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    private val functionNamePattern = Regex("[A-Za-z0-9_-]{1,64}")

    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "必填。normalized（推荐）：x、y 取 0–999，相对整块屏幕的比例，看截图时用它，不受图片缩放影响；" +
                    "screen：真实屏幕像素，ui_nodes 的 center/bounds 就是这个坐标；" +
                    "screenshot：最近一次附图的原始像素，仅当你确定看到的是未缩放原图时使用。",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )

    /**
     * Chat Completions gateways validate function schemas more strictly than the local runtime.
     * Drop a malformed declaration instead of allowing one catalog entry to fail the whole run.
     */
    fun sanitizeFunctionCatalog(source: JSONArray): JSONArray {
        val names = linkedSetOf<String>()
        return JSONArray().also { sanitized ->
            for (index in 0 until source.length()) {
                val tool = source.optJSONObject(index) ?: continue
                val function = tool.optJSONObject("function") ?: continue
                val name = function.optString("name").trim()
                if (!functionNamePattern.matches(name) || name in names) continue
                val parameters = function.optJSONObject("parameters") ?: continue
                if (!validSchema(parameters, requireObjectRoot = true)) continue
                names += name
                sanitized.put(JSONObject(tool.toString()))
            }
        }
    }

    private fun validSchema(schema: JSONObject, requireObjectRoot: Boolean = false): Boolean {
        if (requireObjectRoot && schema.optString("type") != "object") return false

        val properties = if (schema.has("properties")) {
            schema.optJSONObject("properties") ?: return false
        } else {
            null
        }
        val required = if (schema.has("required")) {
            schema.optJSONArray("required") ?: return false
        } else {
            null
        }
        if (required != null) {
            val seen = mutableSetOf<String>()
            for (index in 0 until required.length()) {
                val name = required.optString(index).takeIf(String::isNotBlank) ?: return false
                if (!seen.add(name)) return false
                if (properties != null && !properties.has(name)) return false
            }
        }
        if (properties != null) {
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val child = properties.optJSONObject(key) ?: return false
                if (!validSchema(child)) return false
            }
        }
        if (schema.has("items")) {
            val items = schema.optJSONObject("items") ?: return false
            if (!validSchema(items)) return false
        }
        for (keyword in listOf("oneOf", "anyOf", "allOf")) {
            if (!schema.has(keyword)) continue
            val variants = schema.optJSONArray(keyword) ?: return false
            if (variants.length() == 0) return false
            for (index in 0 until variants.length()) {
                val variant = variants.optJSONObject(index) ?: return false
                if (!validSchema(variant)) return false
            }
        }
        return true
    }
}
