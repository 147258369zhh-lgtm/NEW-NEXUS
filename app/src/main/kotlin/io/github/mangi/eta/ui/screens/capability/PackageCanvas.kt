package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalPackageImages = staticCompositionLocalOf<DshPanelImages?> { null }

@Composable
internal fun packageColor(value: Any?, fallback: Color = MiuixTheme.colorScheme.onSurface): Color {
    val scheme = MiuixTheme.colorScheme
    val token = if (value is JSONObject) value.optString("__colorToken") else value?.toString().orEmpty()
    val color = when (token) {
        "primary" -> scheme.primary; "error" -> scheme.error; "surface", "background" -> scheme.surface
        "surfaceContainer", "surfaceVariant" -> scheme.surfaceContainer
        "outline", "outlineVariant", "onSurfaceVariant" -> scheme.onSurfaceVariantSummary
        "onSurface" -> scheme.onSurface
        else -> if (token.matches(Regex("#[A-Fa-f0-9]{6}([A-Fa-f0-9]{2})?")))
            runCatching { Color(android.graphics.Color.parseColor(token)) }.getOrDefault(fallback) else fallback
    }
    val alpha = (value as? JSONObject)?.optDouble("alpha", 1.0)?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.toFloat() ?: 1f
    return color.copy(alpha = alpha)
}

@Composable
internal fun PackageCanvas(props: JSONObject, enabled: Boolean, action: (String?, Any?) -> Unit, modifier: Modifier) {
    val commands = props.optJSONArray("commands") ?: JSONArray()
    val colors = (0 until commands.length()).map { packageColor(commands.getJSONObject(it).opt("color")) }
    val measurer = rememberTextMeasurer()
    val textStyle = MiuixTheme.textStyles.body2
    val transform = props.optJSONObject("transform") ?: JSONObject()
    fun finite(value: Double, fallback: Double) = value.takeIf(Double::isFinite) ?: fallback
    val initialScale = finite(transform.optDouble("scale", 1.0), 1.0).coerceIn(.2, 5.0).toFloat()
    var zoom by remember(transform.toString()) { mutableFloatStateOf(initialScale) }
    var pan by remember(transform.toString()) { mutableStateOf(Offset(finite(transform.optDouble("offsetX", 0.0), 0.0).coerceIn(-10_000.0, 10_000.0).toFloat(),
        finite(transform.optDouble("offsetY", 0.0), 0.0).coerceIn(-10_000.0, 10_000.0).toFloat())) }
    var measured by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var reportedSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var gestureVersion by remember { mutableLongStateOf(0) }
    val callback = props.optJSONObject("onTransform")?.optString("__actionId")
    LaunchedEffect(gestureVersion) {
        if (gestureVersion > 0 && enabled && callback != null) {
            kotlinx.coroutines.delay(180)
            action(callback, JSONObject().put("zoom", zoom / initialScale).put("panX", pan.x - transform.optDouble("offsetX", 0.0))
                .put("panY", pan.y - transform.optDouble("offsetY", 0.0)).put("rotation", 0))
        }
    }
    LaunchedEffect(measured, enabled) {
        val id = props.optJSONObject("onSizeChanged")?.optString("__actionId")
        if (enabled && measured.width > 0 && measured != reportedSize && id != null) {
            reportedSize = measured
            action(id, JSONObject().put("width", measured.width).put("height", measured.height))
        }
    }
    Canvas(modifier.fillMaxWidth().height(props.optInt("height", 240).coerceIn(80, 480).dp).clipToBounds().onSizeChanged { measured = it }
        .pointerInput(enabled, callback) { if (enabled && callback != null) detectTransformGestures { _, translation, scale, _ ->
            zoom = (zoom * scale).coerceIn(.2f, 5f)
            pan = Offset((pan.x + translation.x).coerceIn(-10_000f, 10_000f), (pan.y + translation.y).coerceIn(-10_000f, 10_000f)); gestureVersion++
        } }) {
        fun number(raw: Any?, axis: Float, defaultUnit: String = "px", fallback: Float = 0f): Float {
            val objectValue = raw as? JSONObject
            val value = (if (objectValue != null) objectValue.optDouble("value", fallback.toDouble()) else (raw as? Number)?.toDouble())
                ?.takeIf(Double::isFinite)?.coerceIn(-100_000.0, 100_000.0)?.toFloat() ?: fallback
            return when (objectValue?.optString("unit") ?: defaultUnit) { "dp" -> value * density; "fraction" -> value.coerceIn(-10f, 10f) * axis; else -> value }
        }
        withTransform({ translate(pan.x, pan.y); scale(zoom, zoom, Offset(number(transform.opt("pivotX"), size.width, fallback = size.width / 2),
            number(transform.opt("pivotY"), size.height, fallback = size.height / 2))) }) {
            for (i in 0 until commands.length()) {
                val item = commands.getJSONObject(i); val unit = item.optString("unit", "px")
                fun x(key: String, fallback: Float = 0f) = number(item.opt(key), size.width, unit, fallback)
                fun y(key: String, fallback: Float = 0f) = number(item.opt(key), size.height, unit, fallback)
                val color = colors[i].copy(alpha = colors[i].alpha * finite(item.optDouble("alpha", 1.0), 1.0).coerceIn(0.0, 1.0).toFloat())
                val stroke = number(item.opt("strokeWidth"), minOf(size.width, size.height), unit, 1f).coerceIn(.5f, 64.dp.toPx())
                val style = if (item.optString("style") == "stroke" || item.has("filled") && !item.optBoolean("filled")) Stroke(stroke) else Fill
                when (item.getString("type")) {
                    "line" -> drawLine(color, Offset(x("x1"), y("y1")), Offset(x("x2"), y("y2")), stroke)
                    "rect" -> drawRect(color, Offset(x("x"), y("y")), Size(x("width").coerceAtLeast(0f), y("height").coerceAtLeast(0f)), style = style)
                    "roundRect", "drawRoundRect" -> {
                        val radius = x(if (item.has("cornerRadius")) "cornerRadius" else "radius", 8.dp.toPx()).coerceAtLeast(0f)
                        drawRoundRect(color, Offset(x("x"), y("y")), Size(x("width").coerceAtLeast(0f), y("height").coerceAtLeast(0f)), CornerRadius(radius), style = style)
                    }
                    "circle" -> drawCircle(color, x("radius").coerceAtLeast(0f), Offset(x("cx"), y("cy")), style = style)
                    "text", "drawText" -> {
                        val fontSize = x("fontSize", 14.dp.toPx()).coerceIn(8.dp.toPx(), 48.dp.toPx()) / density
                        val text = measurer.measure(AnnotatedString(item.optString("text").take(2000)),
                            textStyle.merge(TextStyle(color = color, fontSize = fontSize.sp)), maxLines = item.optInt("maxLines", 3).coerceIn(1, 12))
                        drawText(text, topLeft = Offset(x("x"), y("y")))
                    }
                    "drawPath" -> {
                        val path = Path(); val operations = item.optJSONArray("path") ?: JSONArray()
                        for (index in 0 until operations.length()) {
                            val op = operations.getJSONObject(index)
                            fun px(key: String) = number(op.opt(key), size.width, unit)
                            fun py(key: String) = number(op.opt(key), size.height, unit)
                            when (op.getString("type")) {
                                "moveTo" -> path.moveTo(px("x"), py("y")); "lineTo" -> path.lineTo(px("x"), py("y"))
                                "cubicTo" -> path.cubicTo(px("x1"), py("y1"), px("x2"), py("y2"), px("x3"), py("y3"))
                                "quadTo" -> path.quadraticTo(px("x1"), py("y1"), px("x2"), py("y2")); "close" -> path.close()
                            }
                        }
                        drawPath(path, color, style = style)
                    }
                }
            }
        }
    }
}
