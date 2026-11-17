package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DshChartDataTest {
    private fun source(kind: String = "line", categories: List<String> = listOf("一", "二", "三"), vararg series: List<Any?>) =
        JSONObject().put("kind", kind).put("categories", JSONArray(categories)).put("series", JSONArray(series.mapIndexed { index, values ->
            JSONObject().put("name", "系列 $index").put("values", JSONArray(values)) }))
    @Test fun differentSeriesShareOneCategoryScaleAndMissingOrInvalidPointsAreNotZero() {
        val chart = DshChartData.parse(source(series = arrayOf(listOf(2, JSONObject.NULL, "bad"), listOf(5))), null)
        assertEquals(3, chart.count); assertEquals(listOf(5.0, null, null), chart.series[1].values)
        assertNull(chart.series[0].values[1]); assertNull(chart.series[0].values[2])
        assertTrue(chart.warnings.contains("无效数值已保留为空缺"))
        assertTrue(chart.describe(2).contains("—"))
    }
    @Test fun allValuesAndLabelsResolveBindingsAndHugeOpposingValuesKeepFiniteCoordinates() {
        fun binding(path: String) = JSONObject().put("$", path)
        val source = JSONObject().put("kind", binding("data.kind")).put("categories", binding("data.labels")).put("series", binding("data.series"))
        val data = JSONObject().put("kind", "bar").put("labels", JSONArray().put("high").put("low"))
            .put("series", JSONArray().put(JSONObject().put("values", JSONArray().put(Double.MAX_VALUE).put(-Double.MAX_VALUE))))
        val chart = DshChartData.parse(source, data)
        assertEquals("bar", chart.kind); assertEquals(listOf("high", "low"), chart.categories)
        assertEquals(-1f, chart.minimum); assertEquals(1f, chart.maximum); assertEquals(2f, chart.range)
        assertTrue(chart.normalized(Double.MAX_VALUE).isFinite()); assertTrue(DshChartData.format(1e-250).contains("E"))
    }
    @Test fun donutKeepsAccuratePercentagesAndAggregatesOnlyTheRemainingPositiveCategories() {
        val chart = DshChartData.parse(source("donut", (1..12).map(Int::toString), (1..12).map { 1e308 }), null)
        assertEquals(8, chart.slices.size); assertEquals("其他", chart.slices.last().label)
        assertEquals(listOf(7, 8, 9, 10, 11), chart.slices.last().indices)
        assertEquals(0, BigDecimal("5.0E+308").compareTo(chart.slices.last().value))
        assertEquals(1.0, chart.slices.sumOf { it.fraction.toDouble() }, .000001)
        val negative = DshChartData.parse(source("donut", series = arrayOf(listOf(-2, 0, 5))), null)
        assertEquals(1, negative.slices.size); assertEquals(1f, negative.slices.single().fraction)
        assertTrue(negative.warnings.any { it.contains("负值") })
    }
    @Test fun diagnosticsNameIgnoredFieldsAndBoundedDataWithoutLeakingTheBoundContent() {
        val view = JSONObject().put("v", 2).put("type", "text").put("text", "private message")
            .put("style", JSONObject().put("position", "absolute").put("maxLines", 99).put("color", "rgba(1,2,3,0)"))
            .put("spans", JSONArray().put(JSONObject().put("text", "secret user text").put("href", "private-url")))
        val report = DshViewDocument.parse(view, null).diagnostics
        assertTrue(report.any { it.path == "root.style.position" && it.code == "STYLE_UNSUPPORTED" })
        assertTrue(report.any { it.code == "SPAN_ACTION_IGNORED" })
        assertFalse(report.toString().contains("private")); assertFalse(report.toString().contains("secret"))
    }
}
