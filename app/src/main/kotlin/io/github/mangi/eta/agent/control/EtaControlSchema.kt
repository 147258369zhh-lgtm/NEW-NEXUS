package io.github.mangi.eta.agent.control

import org.json.JSONArray
import org.json.JSONObject

/** Small JSON-Schema subset used to keep model-provided capability parameters inside the contract. */
internal object EtaControlSchema {
    fun validate(schema: JSONObject, parameters: JSONObject) {
        if (schema.length() == 0) {
            if (parameters.length() > 0) fail("该能力不接受参数")
            return
        }
        val expected = schema.optString("type")
        if (expected.isNotBlank() && expected != "object") fail("能力参数根节点必须是 object")
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (index in 0 until required.length()) {
            val key = required.optString(index)
            if (key.isNotBlank() && !parameters.has(key)) fail("缺少参数 $key")
        }
        if (schema.optBoolean("additionalProperties", true).not()) {
            val allowed = buildSet {
                val keys = properties.keys()
                while (keys.hasNext()) add(keys.next())
            }
            val keys = parameters.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in allowed) fail("不支持参数 $key")
            }
        }
        val keys = properties.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!parameters.has(key) || parameters.isNull(key)) continue
            val child = properties.optJSONObject(key) ?: continue
            validateValue(key, child, parameters.opt(key))
        }
    }

    private fun validateValue(path: String, schema: JSONObject, value: Any?) {
        val types = when (val type = schema.opt("type")) {
            is String -> setOf(type)
            is JSONArray -> buildSet {
                for (index in 0 until type.length()) add(type.optString(index))
            }
            null -> emptySet()
            else -> emptySet()
        }
        if (value == null || value == JSONObject.NULL) {
            if ("null" !in types) fail("$path 不能为 null")
            return
        }
        if (types.isNotEmpty() && types.none { matches(it, value) }) {
            fail("$path 类型应为 ${types.joinToString(" 或 ")}")
        }
        val enumValues = schema.optJSONArray("enum")
        if (enumValues != null) {
            val allowed = buildSet {
                for (index in 0 until enumValues.length()) add(enumValues.opt(index)?.toString())
            }
            if (value.toString() !in allowed) fail("$path 必须是 ${allowed.joinToString(", ")} 之一")
        }
        if (value is JSONObject && schema.has("properties")) {
            validate(schema, value)
        }
        if (value is JSONArray) {
            schema.optJSONObject("items")?.let { itemSchema ->
                for (index in 0 until value.length()) {
                    if (value.isNull(index)) continue
                    validateValue("$path[$index]", itemSchema, value.opt(index))
                }
            }
        }
        if (value is String) {
            schema.optInt("minLength", 0).let { minimum ->
                if (value.length < minimum) fail("$path 至少需要 $minimum 个字符")
            }
            schema.optInt("maxLength", Int.MAX_VALUE).let { maximum ->
                if (value.length > maximum) fail("$path 不能超过 $maximum 个字符")
            }
        }
        if (value is Number) {
            val number = value.toDouble()
            if (schema.has("minimum") && number < schema.optDouble("minimum")) {
                fail("$path 不能小于 ${schema.opt("minimum")}")
            }
            if (schema.has("maximum") && number > schema.optDouble("maximum")) {
                fail("$path 不能大于 ${schema.opt("maximum")}")
            }
        }
    }

    private fun matches(type: String, value: Any): Boolean = when (type) {
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "boolean" -> value is Boolean
        "integer" -> value is Int || value is Long || value is Short || value is Byte
        "number" -> value is Number
        "null" -> value == JSONObject.NULL
        else -> true
    }

    private fun fail(message: String): Nothing = throw EtaControlException("INVALID_PARAMETERS", message)
}
