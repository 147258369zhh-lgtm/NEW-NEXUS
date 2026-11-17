package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.dsh.DshChartData
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.*

@Composable
internal fun DshChartView(source: JSONObject, data: Any?) {
    val chart = remember(source.toString(), data?.toString()) { DshChartData.parse(source, data) }
    if (chart.series.isEmpty() || chart.series.all { series -> series.values.all { it == null } }) {
        Text("暂无数据", style = MiuixTheme.textStyles.body2); return
    }
    val background = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
    val foreground = dshReadable(MiuixTheme.colorScheme.onSurfaceVariantSummary, background)
    val grid = foreground.copy(alpha = .16f)
    val palette = listOf(MiuixTheme.colorScheme.primary, Color(0xFF209665), Color(0xFFBA6B32), Color(0xFFBE587B), Color(0xFF4E94AE), Color(0xFF8D8C3B), Color(0xFF8554BB), Color(0xFF697382))
        .map { dshReadable(it, background, 3f) }
    var selection by remember(chart) { mutableIntStateOf(-1) }
    var hidden by remember(chart) { mutableStateOf<Set<Int>>(emptySet()) }
    val selectableCount = if (chart.kind == "donut") chart.slices.size else chart.count
    fun select(index: Int) { if (selectableCount > 0) selection = index.coerceIn(0, selectableCount - 1) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chart.caption.isNotBlank()) Text(chart.caption, style = MiuixTheme.textStyles.body2)
        if (chart.kind == "donut" && chart.slices.isEmpty()) Text("暂无正值数据", style = MiuixTheme.textStyles.body2)
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chart.kind != "donut") Column(Modifier.width(48.dp).height(180.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if (chart.kind == "hbar") listOf(0, chart.count / 2, chart.count - 1).distinct().forEach { index ->
                    Text(chart.categories[index], color = foreground, style = MiuixTheme.textStyles.footnote1, maxLines = 2)
                } else listOf(chart.maximum, (chart.maximum + chart.minimum) / 2, chart.minimum).forEach { tick ->
                    Text(DshChartData.format(tick * chart.magnitude), color = foreground, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
                }
            }
            Canvas(Modifier.weight(1f).height(180.dp)
                .semantics {
                    contentDescription = when (chart.kind) { "donut" -> "环形图"; "hbar" -> "横向柱形图"; "bar" -> "柱形图"; else -> "折线图" }
                    stateDescription = if (selection >= 0) {
                        if (chart.kind == "donut") chart.slices[selection].let { "${it.label} ${DshChartData.format(it.value)}${chart.unit}" } else chart.describe(selection)
                    } else "${chart.count} 项数据"
                    customActions = listOf(CustomAccessibilityAction("上一项") { select(if (selection < 0) 0 else selection - 1); true },
                        CustomAccessibilityAction("下一项") { select(selection + 1); true })
                }.pointerInput(chart) { detectTapGestures { point ->
                    if (chart.kind == "donut") {
                        val center = Offset(size.width / 2f, size.height / 2f); val radius = minOf(size.width, size.height) / 2f - 12.dp.toPx()
                        val distance = (point - center).getDistance()
                        if (distance < radius - 18.dp.toPx() || distance > radius + 18.dp.toPx()) return@detectTapGestures
                        val fraction = ((atan2((point.y - center.y).toDouble(), (point.x - center.x).toDouble()) * 180 / PI + 450) % 360 / 360).toFloat()
                        var end = 0f
                        select(chart.slices.indexOfFirst { end += it.fraction; fraction <= end }.takeIf { it >= 0 } ?: chart.slices.lastIndex)
                    } else {
                        val fraction = if (chart.kind == "hbar") point.y / size.height else point.x / size.width
                        select(if (chart.kind == "line") (fraction * (chart.count - 1)).roundToInt() else (fraction * chart.count).toInt())
                    }
                } }) {
                val inset = 5.dp.toPx(); val width = (size.width - 2 * inset).coerceAtLeast(1f); val height = (size.height - 2 * inset).coerceAtLeast(1f)
                if (chart.kind == "donut") {
                    val diameter = (minOf(size.width, size.height) - 28.dp.toPx()).coerceAtLeast(1f)
                    val origin = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    var start = -90f
                    chart.slices.forEachIndexed { index, slice ->
                        drawArc(palette[index], start, slice.fraction * 360, false, origin, Size(diameter, diameter),
                            style = Stroke(if (selection == index) 28.dp.toPx() else 22.dp.toPx()))
                        start += slice.fraction * 360
                    }
                } else {
                    fun y(value: Float) = inset + (chart.maximum - value) / chart.range * height
                    fun x(value: Float) = inset + (value - chart.minimum) / chart.range * width
                    repeat(3) { index ->
                        if (chart.kind == "hbar") drawLine(grid, Offset(inset + width * index / 2, inset), Offset(inset + width * index / 2, inset + height), 1.dp.toPx())
                        else drawLine(grid, Offset(inset, inset + height * index / 2), Offset(inset + width, inset + height * index / 2), 1.dp.toPx())
                    }
                    if (chart.kind == "hbar") drawLine(foreground.copy(alpha = .5f), Offset(x(0f), inset), Offset(x(0f), inset + height), 1.dp.toPx())
                    else drawLine(foreground.copy(alpha = .5f), Offset(inset, y(0f)), Offset(inset + width, y(0f)), 1.dp.toPx())
                    val visible = chart.series.indices.filterNot(hidden::contains)
                    visible.forEachIndexed { seriesSlot, seriesIndex ->
                        val row = chart.series[seriesIndex]
                        if (chart.kind in setOf("bar", "hbar")) {
                            val group = (if (chart.kind == "hbar") height else width) / chart.count.coerceAtLeast(1)
                            val thickness = group * .76f / visible.size.coerceAtLeast(1)
                            row.values.forEachIndexed point@{ index, number ->
                                if (number == null) return@point
                                val value = chart.normalized(number)
                                val a = if (chart.kind == "hbar") x(0f) else y(0f)
                                val b = if (chart.kind == "hbar") x(value) else y(value)
                                val position = inset + group * index + group * .12f + thickness * seriesSlot
                                drawRect(palette[seriesIndex], if (chart.kind == "hbar") Offset(minOf(a, b), position) else Offset(position, minOf(a, b)),
                                    if (chart.kind == "hbar") Size(abs(b - a), thickness) else Size(thickness, abs(b - a)))
                            }
                        } else {
                            val denominator = (chart.count - 1).coerceAtLeast(1)
                            var previous: Offset? = null
                            row.values.forEachIndexed { index, number ->
                                val point = number?.let { Offset(inset + if (chart.count == 1) width / 2 else index.toFloat() / denominator * width, y(chart.normalized(it))) }
                                if (point != null && previous != null) drawLine(palette[seriesIndex], previous!!, point, 2.dp.toPx(), StrokeCap.Round)
                                if (point != null) drawCircle(palette[seriesIndex], if (selection == index) 4.dp.toPx() else 2.dp.toPx(), point)
                                previous = point
                            }
                        }
                    }
                    if (selection >= 0) {
                        if (chart.kind == "hbar") {
                            val y = inset + height * (selection + .5f) / chart.count
                            drawLine(foreground, Offset(inset, y), Offset(inset + width, y), 1.dp.toPx())
                        } else {
                            val x = inset + if (chart.kind == "line") {
                                if (chart.count == 1) width / 2 else width * selection / (chart.count - 1)
                            } else width * (selection + .5f) / chart.count
                            drawLine(foreground, Offset(x, inset), Offset(x, inset + height), 1.dp.toPx())
                        }
                    }
                }
            }
        }
        if (chart.kind != "donut") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (chart.kind == "hbar") listOf(chart.minimum, (chart.minimum + chart.maximum) / 2, chart.maximum).forEach { tick ->
                Text(DshChartData.format(tick * chart.magnitude), style = MiuixTheme.textStyles.footnote1, color = foreground)
            } else listOf(0, chart.count / 2, chart.count - 1).distinct().forEach { index ->
                Text(chart.categories[index].take(24), style = MiuixTheme.textStyles.footnote1, color = foreground, modifier = Modifier.weight(1f), maxLines = 2)
            }
        }
        if (selection >= 0) Row(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = selection > 0, onClick = { select(selection - 1) }) { Icon(Icons.Rounded.ChevronLeft, "上一项") }
            Text(if (chart.kind == "donut") chart.slices[selection].let { "${it.label} · ${DshChartData.format(it.value)}${chart.unit} · ${DshChartData.format(it.fraction.toDouble() * 100)}%" }
                else chart.describe(selection), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
            IconButton(enabled = selection < selectableCount - 1, onClick = { select(selection + 1) }) { Icon(Icons.Rounded.ChevronRight, "下一项") }
        }
        if (chart.kind == "donut") chart.slices.forEachIndexed { index, slice ->
            Row(Modifier.fillMaxWidth().clickable { select(index) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(palette[index], RoundedCornerShape(2.dp)))
                Text(slice.label, Modifier.weight(1f), style = MiuixTheme.textStyles.body2, fontWeight = if (selection == index) FontWeight.SemiBold else null)
                Text("${DshChartData.format(slice.value)}${chart.unit} · ${DshChartData.format(slice.fraction.toDouble() * 100)}%", style = MiuixTheme.textStyles.footnote1)
            }
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            chart.series.forEachIndexed { index, series ->
                Row(Modifier.clickable { hidden = if (index in hidden) hidden - index else if (hidden.size < chart.series.size - 1) hidden + index else hidden }
                    .semantics { stateDescription = if (index in hidden) "已隐藏" else "已显示" }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(if (index in hidden) foreground else palette[index], RoundedCornerShape(2.dp)))
                    Text(series.name + if (index in hidden) "（已隐藏）" else "", style = MiuixTheme.textStyles.footnote1, color = foreground)
                }
            }
        }
    }
}
