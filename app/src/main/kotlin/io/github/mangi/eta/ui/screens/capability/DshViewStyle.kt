package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.dsh.DshProtocol
import io.github.mangi.eta.agent.dsh.DshViewDocument
import io.github.mangi.eta.agent.dsh.DshViewNode
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalDshBackground = staticCompositionLocalOf<Color?> { null }
internal val LocalDshAccent = staticCompositionLocalOf { Color(0xFF3D6FD6) }
private val colors = mapOf("red" to 0xFFD04545, "blue" to 0xFF3D6FD6, "orange" to 0xFFCA6328, "indigo" to 0xFF5B5CC7,
    "amber" to 0xFFAA7900, "violet" to 0xFF8554BB, "green" to 0xFF258051, "pink" to 0xFFB94F83, "teal" to 0xFF167F84, "gray" to 0xFF697382)
internal fun dshColor(raw: String, accent: Color, fallback: Color): Color = when (raw) {
    "accent" -> accent
    else -> colors[raw]?.let(::Color) ?: raw.takeIf { Regex("#[A-Fa-f0-9]{6}").matches(it) }?.drop(1)?.toLong(16)?.let { Color(it or 0xFF000000) } ?: fallback
}
internal fun dshReadable(requested: Color, background: Color, ratio: Float = 4.5f): Color {
    fun quantized(color: Color) = Color((color.red * 255).toInt().coerceIn(0, 255), (color.green * 255).toInt().coerceIn(0, 255), (color.blue * 255).toInt().coerceIn(0, 255))
    fun contrast(foreground: Color): Float {
        val a = foreground.luminance(); val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
    val original = quantized(requested)
    if (contrast(original) >= ratio) return original
    var best = if (contrast(Color.Black) > contrast(Color.White)) Color.Black else Color.White
    var movement = 1f
    for (endpoint in listOf(Color.Black, Color.White)) {
        if (contrast(endpoint) < ratio) continue
        var low = 0f; var high = 1f
        repeat(16) { val middle = (low + high) / 2; if (contrast(quantized(lerp(original, endpoint, middle))) >= ratio) high = middle else low = middle }
        val found = quantized(lerp(original, endpoint, high))
        if (high < movement && contrast(found) >= ratio) { best = found; movement = high }
    }
    return best
}

@Composable
internal fun DshNodeSurface(node: DshViewNode, data: Any?, content: @Composable () -> Unit) {
    val context = LocalDshView.current
    if (context?.document?.v2 != true) { content(); return }
    val base = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
    val style = DshViewDocument.resolvedObject(node.source.opt("style"), data)
    val root = context.document.root.source
    val accentRaw = DshProtocol.text(DshViewDocument.value(root.opt("accent"), data))
    val accent = if (accentRaw == "app") MiuixTheme.colorScheme.primary else dshColor(accentRaw, LocalDshAccent.current, LocalDshAccent.current)
    val container = node.source.optString("type") in setOf("stack", "row", "grid", "form", "collapsible", "tabs", "accordion", "carousel", "timeline")
    val card = if (container) style.optString("card", "plain") else "plain"
    val rawBackground = if (container) DshProtocol.text(DshViewDocument.value(style.opt("background"), data)) else ""
    val background = when {
        rawBackground.isNotBlank() -> lerp(base, dshColor(rawBackground, accent, accent), if (base.luminance() < .2f) .16f else .1f).copy(alpha = 1f)
        card == "tinted" -> lerp(base, accent, if (base.luminance() < .2f) .16f else .1f).copy(alpha = 1f)
        card == "surface" -> MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 1f)
        else -> base.copy(alpha = 1f)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = 136.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 2f)
        val requested = if (!container) 0.dp else when (style.optString("padding")) { "S" -> 8.dp; "L" -> 16.dp; "M" -> 12.dp; else -> if (card == "plain") 0.dp else 12.dp }
        val padding = minOf(requested, ((maxWidth - minimum) / 2).coerceAtLeast(0.dp))
        val border = if (card == "outlined") Modifier.border(1.dp, dshReadable(accent, background, 3f), RoundedCornerShape(8.dp)) else Modifier
        CompositionLocalProvider(LocalDshBackground provides background, LocalDshAccent provides accent,
            LocalContentColor provides dshReadable(MiuixTheme.colorScheme.onSurface, background)) {
          val colors = MiuixTheme.colorScheme
          MiuixTheme(colors = colors.copy(surface = background, surfaceContainer = background,
              onSurface = dshReadable(colors.onSurface, background), onSurfaceVariantSummary = dshReadable(colors.onSurfaceVariantSummary, background),
              primary = dshReadable(accent, background), error = dshReadable(colors.error, background)), textStyles = MiuixTheme.textStyles) {
            Column(Modifier.fillMaxWidth().background(background, RoundedCornerShape(8.dp)).then(border).padding(padding)) { content() }
          }
        }
    }
}

@Composable
internal fun DshStyledText(node: DshViewNode, data: Any?, text: String, metric: Boolean = false) {
    val style = DshViewDocument.resolvedObject(node.source.opt("style"), data)
    val background = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
    val accent = LocalDshAccent.current
    val foreground = dshReadable(dshColor(DshProtocol.text(DshViewDocument.value(style.opt("color"), data)), accent, MiuixTheme.colorScheme.onSurface), background)
    fun weight(raw: String) = when (raw) { "medium" -> FontWeight.Medium; "semibold" -> FontWeight.SemiBold; "bold" -> FontWeight.Bold; else -> FontWeight.Normal }
    val kind = style.optString("size", if (metric) "title" else "body")
    val size = when (kind) { "display" -> 28; "title" -> 24; "subtitle" -> 20; "caption" -> 13; "mono" -> 14; else -> 16 }
    val spans = DshViewDocument.value(node.source.opt("spans"), data) as? JSONArray
    val formatted = remember(text, spans?.toString(), data?.toString(), foreground, background, accent) {
        if (spans == null || spans.length() == 0) dshInlineText(text, lerp(background, foreground, .08f))
        else AnnotatedString.Builder().apply {
            var remaining = 2000
            for (index in 0 until minOf(spans.length(), 32)) {
                val span = DshViewDocument.resolvedObject(spans.optJSONObject(index) ?: continue, data)
                val content = DshProtocol.text(DshViewDocument.value(span.opt("text"), data)).take(remaining)
                remaining -= content.length
                val highlight = DshProtocol.text(DshViewDocument.value(span.opt("background"), data))
                val fill = if (highlight.isBlank()) background else lerp(background, dshColor(highlight, accent, accent), .16f).copy(alpha = 1f)
                withStyle(SpanStyle(color = dshReadable(dshColor(DshProtocol.text(DshViewDocument.value(span.opt("color"), data)), accent, foreground), fill),
                    fontWeight = weight(span.optString("weight")), fontFamily = if (span.optBoolean("mono")) FontFamily.Monospace else null,
                    background = if (highlight.isBlank()) Color.Unspecified else fill)) { append(content) }
            }
        }.toAnnotatedString().takeIf { it.text.isNotEmpty() } ?: dshInlineText(text, background)
    }
    val decorations = buildList { if (style.optBoolean("underline")) add(TextDecoration.Underline); if (style.optBoolean("strike")) add(TextDecoration.LineThrough) }
    Text(formatted, style = MiuixTheme.textStyles.body2.merge(TextStyle(fontSize = size.sp, fontWeight = weight(style.optString("weight", if (metric) "semibold" else "regular")),
        fontFamily = if (kind == "mono") FontFamily.Monospace else null, fontStyle = if (style.optBoolean("italic")) FontStyle.Italic else FontStyle.Normal,
        textDecoration = TextDecoration.combine(decorations), textAlign = when (style.optString("align")) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start })),
        color = foreground, maxLines = if (style.has("maxLines")) style.optInt("maxLines", 20).coerceIn(1, 20) else Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}
