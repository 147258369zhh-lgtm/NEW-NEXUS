package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject

internal data class DshViewDiagnostic(val path: String, val code: String, val message: String)

/** Inspect only the public view schema. Diagnostics carry paths and codes, never bound user content. */
internal object DshViewDiagnostics {
    private val text = setOf("size", "weight", "italic", "underline", "strike", "align", "maxLines", "color")
    private val container = setOf("card", "background", "padding", "gap", "align", "columns", "dividers")
    private val enums = mapOf("size" to setOf("display", "title", "subtitle", "body", "caption", "mono"),
        "weight" to setOf("regular", "medium", "semibold", "bold"), "align" to setOf("start", "center", "end"),
        "card" to setOf("plain", "surface", "tinted", "outlined"), "padding" to setOf("S", "M", "L"), "gap" to setOf("S", "M", "L"))
    private val colors = setOf("red", "blue", "orange", "indigo", "amber", "violet", "green", "pink", "teal", "gray", "accent")
    fun inspect(source: JSONObject, path: String, data: Any?): List<DshViewDiagnostic> = buildList {
        fun add(code: String, key: String, message: String) { if (size < 64) add(DshViewDiagnostic("$path.$key", code, message)) }
        val type = source.optString("type")
        val isContainer = type in setOf("stack", "row", "grid", "form", "collapsible", "tabs", "accordion", "carousel", "timeline")
        val allowed = if (isContainer) container else if (type in setOf("text", "metric", "callout")) text else emptySet()
        val style = DshViewDocument.value(source.opt("style"), data)
        if (style != null && style != JSONObject.NULL && style !is JSONObject) add("STYLE_TYPE", "style", "样式应为对象，已忽略")
        if (style is JSONObject) style.keys().forEach { key ->
            val value = DshViewDocument.value(style.opt(key), data)
            val valid = when {
                key !in allowed -> { add("STYLE_UNSUPPORTED", "style.$key", "该样式不属于当前控件，已忽略"); true }
                key in enums -> value is String && value in enums.getValue(key)
                key in setOf("italic", "underline", "strike", "dividers") -> value is Boolean
                key == "maxLines" -> value is Number && value.toDouble() % 1.0 == 0.0 && value.toInt() in 1..20
                key in setOf("color", "background") -> value is String && (value in colors || value.matches(Regex("#[a-fA-F0-9]{6}")))
                key == "columns" -> value == "equal" || value is JSONArray && value.length() in 1..8 && (0 until value.length()).all {
                    val number = value.opt(it); number is Number && number.toDouble() % 1.0 == 0.0 && number.toInt() in 1..12
                }
                else -> true
            }
            if (!valid) add("STYLE_VALUE", "style.$key", "样式值无效，使用默认值或有效范围")
        }
        (DshViewDocument.value(source.opt("spans"), data) as? JSONArray)?.let { spans ->
            if (spans.length() > 32) add("SPAN_LIMIT", "spans", "行内样式超过 32 项，已截取")
            for (index in 0 until minOf(spans.length(), 32)) {
                val span = DshViewDocument.value(spans.opt(index), data) as? JSONObject ?: continue
                if (span.keys().asSequence().any { it in setOf("link", "url", "href", "action") })
                    add("SPAN_ACTION_IGNORED", "spans.$index", "行内动作或链接不执行")
            }
        }
        if (type == "actions") (DshViewDocument.value(source.opt("items"), data) as? JSONArray)?.let { items ->
            for (index in 0 until minOf(items.length(), 4)) if (items.optJSONObject(index)?.optString("style") == "primary")
                add("PLUGIN_BUTTON_STYLE", "items.$index.style", "使用插件按钮样式")
        }
        if (type == "chart") DshChartData.parse(source, data).warnings.forEach { add("CHART_DATA", "chart", it) }
    }
}
