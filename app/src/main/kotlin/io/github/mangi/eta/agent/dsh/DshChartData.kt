package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.text.NumberFormat
import kotlin.math.abs

internal data class DshChartSeries(val name: String, val values: List<Double?>)
internal data class DshChartSlice(val label: String, val value: BigDecimal, val fraction: Float, val indices: List<Int>)
internal data class DshChartData(val kind: String, val categories: List<String>, val series: List<DshChartSeries>,
    val unit: String, val caption: String, val magnitude: Double, val minimum: Float, val maximum: Float,
    val slices: List<DshChartSlice>, val warnings: List<String>) {
    val count get() = categories.size
    val range get() = (maximum - minimum).takeIf { it > 0 } ?: 1f
    fun normalized(value: Double): Float = (value / magnitude).toFloat()
    fun describe(index: Int): String = categories[index] + " · " + series.joinToString(" · ") {
        "${it.name} ${it.values.getOrNull(index)?.let(::format) ?: "—"}$unit"
    }
    companion object {
        fun parse(source: JSONObject, data: Any?): DshChartData {
            fun value(raw: Any?) = DshViewDocument.value(raw, data)
            fun text(raw: Any?, maximum: Int = 80) = DshProtocol.text(value(raw), maximum)
            val warnings = mutableListOf<String>()
            val kind = text(source.opt("kind")).ifBlank { "line" }.let {
                if (it in setOf("line", "bar", "hbar", "donut")) it else { warnings += "不支持的图表类型，已使用折线图"; "line" }
            }
            val rawSeries = value(source.opt("series")) as? JSONArray ?: JSONArray()
            val rawCategories = value(source.opt("categories")) as? JSONArray ?: JSONArray()
            if (rawSeries.length() > 6 || rawCategories.length() > 60) warnings += "图表已按 6 组、60 点上限截取"
            var invalid = false
            val rows = (0 until minOf(rawSeries.length(), 6)).map { index ->
                val row = value(rawSeries.opt(index)) as? JSONObject ?: JSONObject()
                val values = value(row.opt("values")) as? JSONArray ?: JSONArray()
                if (values.length() > 60 && warnings.none { it.contains("上限") }) warnings += "图表已按 6 组、60 点上限截取"
                DshChartSeries(text(row.opt("name")).ifBlank { "系列 ${index + 1}" }, (0 until minOf(values.length(), 60)).map { point ->
                    when (val number = value(values.opt(point))) {
                        null, JSONObject.NULL -> null
                        is Number -> number.toDouble().takeIf(Double::isFinite).also { if (it == null) invalid = true }
                        is String -> number.toDoubleOrNull()?.takeIf(Double::isFinite).also { if (it == null) invalid = true }
                        else -> { invalid = true; null }
                    }
                })
            }
            if (invalid) warnings += "无效数值已保留为空缺"
            val count = maxOf(rawCategories.length().coerceAtMost(60), rows.maxOfOrNull { it.values.size } ?: 0)
            val categories = (0 until count).map { text(rawCategories.opt(it)).ifBlank { "${it + 1}" } }
            val series = rows.map { it.copy(values = it.values + List(count - it.values.size) { null }) }
            val finite = series.flatMap { it.values }.filterNotNull()
            val magnitude = finite.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
            val normalized = finite.map { (it / magnitude).toFloat() }
            val min = minOf(0f, normalized.minOrNull() ?: 0f); val max = maxOf(0f, normalized.maxOrNull() ?: 0f)
            val positive = series.firstOrNull()?.values.orEmpty().mapIndexedNotNull { index, number ->
                number?.takeIf { it > 0 }?.let { Triple(categories[index], BigDecimal.valueOf(it), listOf(index)) }
            }
            if (kind == "donut" && series.firstOrNull()?.values.orEmpty().any { it != null && it < 0 }) warnings += "环形图仅显示正值，负值未绘制"
            val pieces = if (positive.size <= 8) positive else positive.take(7) + Triple("其他", positive.drop(7).fold(BigDecimal.ZERO) { sum, item -> sum + item.second },
                positive.drop(7).flatMap { it.third })
            val total = pieces.fold(BigDecimal.ZERO) { sum, item -> sum + item.second }
            val slices = pieces.map { DshChartSlice(it.first, it.second, it.second.divide(total, MathContext.DECIMAL64).toFloat(), it.third) }
            return DshChartData(kind, categories, series, text(source.opt("unit"), 24), text(source.opt("caption"), 200), magnitude, min, max,
                slices, warnings.distinct())
        }
        fun format(value: Double): String = if (value != 0.0 && (abs(value) >= 1_000_000 || abs(value) < .001)) DecimalFormat("0.###E0").format(value)
            else NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }.format(value)
        fun format(value: BigDecimal): String = if (value.abs() >= BigDecimal("1000000") || value.signum() != 0 && value.abs() < BigDecimal("0.001"))
            DecimalFormat("0.###E0").format(value) else NumberFormat.getNumberInstance().apply { maximumFractionDigits = 3 }.format(value)
    }
}
