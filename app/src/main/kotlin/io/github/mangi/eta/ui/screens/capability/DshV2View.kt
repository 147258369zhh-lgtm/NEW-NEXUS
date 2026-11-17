package io.github.mangi.eta.ui.screens.capability

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.dsh.DshProtocol
import io.github.mangi.eta.agent.dsh.DshViewDocument
import io.github.mangi.eta.agent.dsh.DshViewNode
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.markdown.CodeHighlightPalette
import io.github.mangi.eta.ui.markdown.highlightCode
import kotlinx.coroutines.Deferred
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun DshPluginButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
    val color = dshReadable(if (enabled) LocalDshAccent.current else Color(0xFF697382), background)
    Box(modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp))
        .border(1.dp, color, RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp),
        contentAlignment = Alignment.Center) { Text(label, style = MiuixTheme.textStyles.body2, color = color) }
}

@Composable
internal fun DshActionButtons(items: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
        if (items.size == 2 && maxWidth >= minimum * 2 + 12.dp) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (label, action) -> DshPluginButton(label, enabled, action, Modifier.weight(1f).fillMaxHeight()) }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { (label, action) -> DshPluginButton(label, enabled, action, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
internal fun DshV2Row(node: DshViewNode, data: Any?, pending: String?, onAction: (String, JSONObject) -> Deferred<Boolean>, image: DshPanelImages) {
    val style = DshViewDocument.resolvedObject(node.source.opt("style"), data)
    val rawWeights = style.optJSONArray("columns")
    val weights = rawWeights?.takeIf { it.length() in 1..8 }?.let { rows -> (0 until rows.length()).map { rows.optInt(it, 0) } }
        ?.takeIf { values -> values.all { it in 1..12 } }
    val gap = when (style.optString("gap")) { "S" -> 8.dp; "L" -> 16.dp; else -> 12.dp }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimum = 136.dp * density.fontScale.coerceIn(1f, 2f)
        var columns = minOf(node.children.size.coerceAtLeast(1), weights?.size ?: 3)
        fun widths(count: Int): List<androidx.compose.ui.unit.Dp> {
            val proportions = weights?.take(count) ?: List(count) { 1 }
            val available = (maxWidth - gap * (count - 1)).coerceAtLeast(0.dp)
            return proportions.map { available * it / proportions.sum() }
        }
        while (columns > 1 && widths(columns).any { it < minimum }) columns--
        val tracks = widths(columns)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
            val rows = node.children.chunked(columns)
            rows.forEachIndexed { rowIndex, row ->
                var height by remember(node.path, rowIndex) { mutableIntStateOf(0) }
                Row(Modifier.fillMaxWidth().onSizeChanged { height = it.height }, verticalAlignment = when (style.optString("align")) {
                    "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top
                }) {
                    row.forEachIndexed { index, child ->
                        if (index > 0) Box(Modifier.width(gap).height(with(density) { height.toDp() }), contentAlignment = Alignment.Center) {
                            if (style.optBoolean("dividers")) VerticalDivider()
                        }
                        Box(Modifier.width(tracks[index])) { RenderDshNode(child, data, pending, onAction, image) }
                    }
                }
                if (style.optBoolean("dividers") && rowIndex < rows.lastIndex) HorizontalDivider()
            }
        }
    }
}

@Composable
internal fun DshV2Node(node: DshViewNode, data: Any?, pending: String?, onAction: (String, JSONObject) -> Deferred<Boolean>, image: DshPanelImages) {
    val context = requireNotNull(LocalDshView.current)
    fun value(key: String) = DshViewDocument.value(node.source.opt(key), data)
    fun text(key: String, maximum: Int = 200) = DshProtocol.text(value(key), maximum)
    @Composable fun children(nodes: List<DshViewNode> = node.children) { nodes.forEach { key(it.path) { RenderDshNode(it, data, pending, onAction, image) } } }
    when (val type = node.source.optString("type")) {
        in DshViewDocument.inputs -> DshInputNode(node, data)
        "form" -> {
            if (text("label").isNotBlank()) DshStyledText(node, data, text("label"))
            children()
            DshPluginButton(text("submitLabel").ifBlank { "提交" }, context.connected && node.path !in context.actions.forms,
                { context.actions.submit(node, data) }, Modifier.fillMaxWidth())
            context.state.failure?.let { Text(it, style = MiuixTheme.textStyles.body2, color = dshReadable(MiuixTheme.colorScheme.error, LocalDshBackground.current ?: MiuixTheme.colorScheme.surface)) }
        }
        "grid" -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimum = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
            val columns = minOf(node.source.optInt("columns", 2).coerceIn(1, 4), ((maxWidth + 12.dp) / (minimum + 12.dp)).toInt().coerceAtLeast(1))
            val width = ((maxWidth - 12.dp * (columns - 1)) / columns).coerceAtLeast(0.dp)
            FlowRow(maxItemsInEachRow = columns, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                node.children.forEach { child -> Box(Modifier.width(width)) { children(listOf(child)) } }
            }
        }
        "tabs", "carousel" -> {
            var selected by remember(node.path, node.groups.map { it.first }) { mutableIntStateOf(0) }
            if (node.groups.isNotEmpty()) {
                selected = selected.coerceIn(0, node.groups.lastIndex)
                if (type == "tabs") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    node.groups.forEachIndexed { index, group -> EtaChoiceChip(group.first.ifBlank { "${index + 1}" }, selected = selected == index,
                        modifier = Modifier.heightIn(min = 48.dp), onClick = { selected = index }) }
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = selected > 0, onClick = { selected-- }) { Icon(Icons.Rounded.ChevronLeft, "上一页") }
                    Text(node.groups[selected].first.ifBlank { "${selected + 1}/${node.groups.size}" }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
                    IconButton(enabled = selected < node.groups.lastIndex, onClick = { selected++ }) { Icon(Icons.Rounded.ChevronRight, "下一页") }
                }
                children(node.groups[selected].second)
            }
        }
        "collapsible", "accordion" -> {
            val groups = if (type == "collapsible") listOf(text("label") to node.children) else node.groups
            groups.forEachIndexed { index, group -> key(node.path, index) {
                var expanded by remember { mutableStateOf(node.source.optBoolean("expanded") && index == 0) }
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text(group.first.ifBlank { "详情" }, style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "收起" else "展开")
                }
                if (expanded) children(group.second)
            } }
        }
        "timeline" -> node.groups.forEach { (label, nodes) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(10.dp).padding(top = 2.dp), tint = dshReadable(LocalDshAccent.current, LocalDshBackground.current ?: MiuixTheme.colorScheme.surface, 3f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { if (label.isNotBlank()) Text(label, style = MiuixTheme.textStyles.body2); children(nodes) }
            }
        }
        "badge", "tag" -> Text(text("text"), style = MiuixTheme.textStyles.footnote1,
            modifier = Modifier.border(1.dp, dshReadable(LocalDshAccent.current, LocalDshBackground.current ?: MiuixTheme.colorScheme.surface, 3f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        "avatar" -> Box(Modifier.size(48.dp)) { DshImage(text("src", 90000), text("alt"), 1f, image) }
        "codeBlock" -> {
            val raw = DshProtocol.text(value("code"), 8000)
            val background = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
            val accent = LocalDshAccent.current
            val code = remember(raw, text("language"), background, accent) { highlightCode(raw, text("language"), CodeHighlightPalette(
                keyword = dshReadable(accent, background), string = dshReadable(Color(0xFF258051), background), number = dshReadable(Color(0xFFCA6328), background),
                comment = dshReadable(Color(0xFF697382), background), function = dshReadable(Color(0xFF167F84), background), type = dshReadable(Color(0xFF8554BB), background))) }
            SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) }
        }
        "divider" -> HorizontalDivider()
        "spacer" -> Spacer(Modifier.height(when (text("size")) { "S" -> 8.dp; "L" -> 24.dp; else -> 16.dp }))
    }
}

@Composable
private fun DshInputNode(node: DshViewNode, data: Any?) {
    val context = requireNotNull(LocalDshView.current)
    val state = context.state
    val value = state.value(node)
    val enabled = context.connected && state.editable(node)
    val label = DshProtocol.text(DshViewDocument.value(node.source.opt("label"), data), 200)
    val type = node.source.getString("type")
    val control = node.copy(source = DshViewDocument.resolvedObject(node.source, data))
    fun edit(value: Any?) { if (enabled) { state.edit(node, value); context.actions.changed(node) } }
    val background = LocalDshBackground.current ?: MiuixTheme.colorScheme.surface
    val color = dshReadable(MiuixTheme.colorScheme.onSurface, background)
    val fieldBackground = lerp(background, color, .05f).copy(alpha = 1f)
    val fieldColors = TextFieldDefaults.textFieldColors().copy(backgroundColor = fieldBackground,
        labelColor = dshReadable(Color(0xFF697382), fieldBackground), borderColor = dshReadable(LocalDshAccent.current, fieldBackground, 3f))
    var choosing by remember(node.path) { mutableStateOf(false) }
    val options = remember(node.source.toString(), data?.toString()) { runCatching { DshInputContract.options(node, data) }.getOrDefault(emptyMap()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (type) {
            "textField", "number" -> TextField(value = if (value == JSONObject.NULL) "" else value?.toString().orEmpty(),
                onValueChange = { changed -> edit(DshInputContract.truncate(changed, if (type == "textField") DshInputContract.textLimit(control) else 64)) },
                label = label, enabled = enabled, singleLine = !control.source.optBoolean("multiline"), maxLines = control.source.optInt("maxLines", 4).coerceIn(1, 8),
                colors = fieldColors, cornerRadius = 8.dp, textStyle = MiuixTheme.textStyles.body2.copy(color = dshReadable(color, fieldBackground)),
                keyboardOptions = KeyboardOptions(keyboardType = if (type == "number") KeyboardType.Decimal else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
            "toggle" -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2, color = color)
                Switch(checked = value == true, onCheckedChange = { edit(it) }, enabled = enabled)
            }
            "stepper" -> {
                Text(label, style = MiuixTheme.textStyles.body2, color = color)
                val limits = runCatching { DshInputContract.limits(control) }.getOrNull()
                val step = runCatching { DshInputContract.number(control, "step", 1.0) }.getOrNull()?.takeIf { it >= 1e-9 }
                val number = value?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: limits?.first ?: 0.0
                if (limits == null || step == null) Text("数值范围无效", color = MiuixTheme.colorScheme.error)
                else {
                    val previous = (number - step).coerceAtLeast(limits.first); val next = (number + step).coerceAtMost(limits.second)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(enabled = enabled && previous < number, modifier = Modifier.size(48.dp), onClick = { edit(previous) }) { Icon(Icons.Rounded.Remove, "减少 $label") }
                        Text(value?.toString().orEmpty(), color = color, style = MiuixTheme.textStyles.body2, modifier = Modifier.defaultMinSize(minHeight = 48.dp).padding(12.dp))
                        IconButton(enabled = enabled && next > number, modifier = Modifier.size(48.dp), onClick = { edit(next) }) { Icon(Icons.Rounded.Add, "增加 $label") }
                    }
                }
            }
            "slider" -> {
                Text(label, style = MiuixTheme.textStyles.body2, color = color)
                val spec = runCatching { val bounds = DshInputContract.limits(control); val step = DshInputContract.number(control, "step", 1.0)
                    val count = (bounds.second - bounds.first) / step
                    require(step >= 1e-9 && count.isFinite() && count in 1.0..1000.0 && kotlin.math.abs(count - kotlin.math.round(count)) <= 1e-7)
                    Triple(bounds.first, step, count.roundToInt()) }.getOrNull()
                if (spec == null) Text("滑块范围无效", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                else {
                    val (low, step, count) = spec
                    fun index(raw: Any?) = (((raw?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: low) - low) / step).toFloat().coerceIn(0f, count.toFloat())
                    if (control.source.optBoolean("range")) {
                        val selected = value as? JSONArray
                        val start = index(selected?.opt(0)); val end = index(selected?.opt(1)).coerceAtLeast(start)
                        Text("${selected?.opt(0) ?: low} - ${selected?.opt(1) ?: low}", style = MiuixTheme.textStyles.body2, color = color)
                        RangeSlider(value = start..end, onValueChange = { edit(JSONArray().put(low + it.start.roundToInt() * step).put(low + it.endInclusive.roundToInt() * step)) },
                            enabled = enabled, valueRange = 0f..count.toFloat(), steps = (count - 1).coerceAtLeast(0), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    } else {
                        Text(value?.toString().orEmpty(), style = MiuixTheme.textStyles.body2, color = color)
                        Slider(value = index(value), onValueChange = { edit(low + it.roundToInt() * step) }, enabled = enabled,
                            valueRange = 0f..count.toFloat(), steps = (count - 1).coerceAtLeast(0), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    }
                }
            }
            "chips", "segmented", "rating" -> {
                Text(label, style = MiuixTheme.textStyles.body2, color = color)
                val items = if (type == "rating") (1..control.source.optInt("max", 5).coerceIn(1, 10)).associate { it.toString() to it.toString() } else options
                val selected = if (value is JSONArray) (0 until value.length()).map { value.optString(it) }.toSet() else setOf(value?.toString())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { (key, title) ->
                        if (type == "rating") IconButton(enabled = enabled, modifier = Modifier.size(48.dp), onClick = { edit(key.toInt()) }) {
                            Icon(if ((value?.toString()?.toDoubleOrNull() ?: 0.0) >= key.toInt()) Icons.Rounded.Star else Icons.Rounded.StarOutline, "$label $key", tint = dshReadable(LocalDshAccent.current, background, 3f))
                        } else EtaChoiceChip(title, selected = key in selected, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            if (type == "chips" && control.source.optBoolean("multi")) edit(JSONArray((if (key in selected) selected - key else selected + key).filterNotNull())) else edit(key)
                        })
                    }
                }
            }
            "select", "date", "time" -> {
                Text(label, style = MiuixTheme.textStyles.body2, color = color)
                DshPluginButton((if (type == "select") options[value as? String] else value?.toString()?.takeUnless { it == "null" }).orEmpty().ifBlank { "选择" }, enabled, { choosing = true }, Modifier.fillMaxWidth())
            }
        }
        state.errors[node.source.optString("bind")]?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = dshReadable(MiuixTheme.colorScheme.error, background)) }
    }
    if (choosing && type == "select") WindowDialog(show = true, title = "插件选项", onDismissRequest = { choosing = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, title) -> DshPluginButton(title, enabled, { edit(key); choosing = false }, Modifier.fillMaxWidth()) }
            MiuixDialogActions(confirmText = "关闭", onConfirm = { choosing = false }, onCancel = { choosing = false })
        }
    }
    val androidContext = LocalContext.current
    DisposableEffect(choosing, enabled, node.path) {
        val dialog = if (choosing && enabled && type == "date") {
            val date = runCatching { LocalDate.parse(value?.toString()) }.getOrDefault(LocalDate.now()).let { if (it.year in 1900..2100) it else LocalDate.now() }
            DatePickerDialog(androidContext, { _, year, month, day -> edit(LocalDate.of(year, month + 1, day).toString()); choosing = false }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                setTitle("插件日期")
                val low = runCatching { LocalDate.parse(control.source.optString("minDate")) }.getOrDefault(LocalDate.of(1900, 1, 1)).coerceAtLeast(LocalDate.of(1900, 1, 1))
                val high = runCatching { LocalDate.parse(control.source.optString("maxDate")) }.getOrDefault(LocalDate.of(2100, 12, 31)).coerceAtMost(LocalDate.of(2100, 12, 31)).coerceAtLeast(low)
                datePicker.minDate = java.util.GregorianCalendar(low.year, low.monthValue - 1, low.dayOfMonth).timeInMillis
                datePicker.maxDate = java.util.GregorianCalendar(high.year, high.monthValue - 1, high.dayOfMonth).timeInMillis
            }
        } else if (choosing && enabled && type == "time") {
            val time = runCatching { LocalTime.parse(value?.toString()) }.getOrDefault(LocalTime.now())
            TimePickerDialog(androidContext, { _, hour, minute -> edit(java.lang.String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)); choosing = false }, time.hour, time.minute, true).apply { setTitle("插件时间") }
        } else null
        dialog?.setOnDismissListener { choosing = false }; dialog?.show()
        onDispose { dialog?.setOnDismissListener(null); dialog?.dismiss() }
    }
}
