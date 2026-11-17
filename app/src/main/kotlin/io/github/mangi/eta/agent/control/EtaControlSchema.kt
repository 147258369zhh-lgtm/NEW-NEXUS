package io.github.mangi.eta.agent.control

import org.json.JSONArray
import org.json.JSONObject

/** Small JSON-Schema subset used to keep model-provided capability parameters inside the contract. */
internal object EtaControlSchema {
    fun validate(schema: JSONObject, parameters: JSONObject) {
        validateObject(schema, parameters, 0)
    }

    private fun validateObject(schema: JSONObject, parameters: JSONObject, depth: Int) {
        if (depth > 24) fail("参数嵌套过深")
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
            if (!parameters.has(key)) continue
            val child = properties.optJSONObject(key) ?: continue
            validateValue(key, child, parameters.opt(key), depth + 1)
        }
    }

    private fun validateValue(path: String, schema: JSONObject, value: Any?, depth: Int) {
        if (depth > 24) fail("参数嵌套过深")
        val types = when (val type = schema.opt("type")) {
            is String -> setOf(type)
            is JSONArray -> buildSet {
                for (index in 0 until type.length()) add(type.optString(index))
            }
            null -> emptySet()
            else -> emptySet()
        }
        if (value == null || value == JSONObject.NULL) {
            if (types.isNotEmpty() && "null" !in types) fail("$path 不能为 null")
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
        if (value is JSONObject && schema.length() > 0) {
            validateObject(schema, value, depth)
        }
        if (value is JSONArray) {
            if (value.length() < schema.optInt("minItems", 0) || value.length() > schema.optInt("maxItems", Int.MAX_VALUE)) {
                fail("$path 的条目数超出范围")
            }
            if (schema.optBoolean("uniqueItems") && (0 until value.length()).map { canonical(value.opt(it)) }.distinct().size != value.length()) {
                fail("$path 包含重复条目")
            }
            schema.optJSONObject("items")?.let { itemSchema ->
                for (index in 0 until value.length()) {
                    validateValue("$path[$index]", itemSchema, value.opt(index), depth + 1)
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
            if (!number.isFinite()) fail("$path 必须为有限数值")
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
        "integer" -> value is Number && runCatching { value.toString().toBigDecimal().stripTrailingZeros().scale() <= 0 }.getOrDefault(false)
        "number" -> value is Number
        "null" -> value == JSONObject.NULL
        else -> true
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.opt(it)) }
        is Number -> runCatching { value.toString().toBigDecimal().stripTrailingZeros() }.getOrNull()
        else -> value
    }

    private fun fail(message: String): Nothing = throw EtaControlException("INVALID_PARAMETERS", message)
}
