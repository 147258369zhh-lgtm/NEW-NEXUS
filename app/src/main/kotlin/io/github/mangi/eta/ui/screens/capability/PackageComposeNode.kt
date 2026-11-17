package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.components.EtaChoiceChip
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PackageComposeNode(node: JSONObject, enabled: Boolean, action: (String?, Any?) -> Unit, close: () -> Unit,
    modifier: Modifier = Modifier, commandsEnabled: Boolean = enabled, editing: (String, Boolean) -> Unit = { _, _ -> }, revision: Long = 0) {
    val props = node.getJSONObject("props")
    val children = node.optJSONArray("children") ?: JSONArray()
    val slots = node.optJSONObject("slots") ?: JSONObject()
    val type = node.getString("type")
    val active = enabled && commandsEnabled && props.optBoolean("enabled", true)
    val operations = props.optJSONObject("modifier")?.optJSONArray("__modifierOps") ?: JSONArray()
    fun callback(key: String) = props.optJSONObject(key)?.optString("__actionId")?.takeIf(String::isNotBlank) ?: if (key == "onClick")
        (0 until operations.length()).mapNotNull { operations.optJSONObject(it)?.takeIf { op -> op.optString("name") == "clickable" }
            ?.optJSONArray("args")?.optJSONObject(0)?.optString("__actionId")?.takeIf(String::isNotBlank) }.lastOrNull() else null
    fun click(key: String, payload: Any? = null) { callback(key)?.let { action(it, payload) } }
    @Composable fun renderChildren(rows: JSONArray = children) {
        for (index in 0 until rows.length()) key(index) { PackageComposeNode(rows.getJSONObject(index), enabled, action, close, commandsEnabled = commandsEnabled, editing = editing, revision = revision) }
    }
    @Composable fun slot(key: String) { slots.optJSONArray(key)?.let { renderChildren(it) } }
    val space = props.optDouble("spacing", 8.0).takeIf(Double::isFinite)?.coerceIn(0.0, 32.0)?.toInt() ?: 8
    val padding = props.opt("padding").let { value -> when (value) {
        is Number -> value.toInt().coerceIn(0, 32)
        is JSONObject -> value.optInt("horizontal", 0).coerceIn(0, 32)
        else -> 0
    } }
    var decorated = modifier
    for (index in 0 until operations.length()) {
        val op = operations.optJSONObject(index) ?: continue; val args = op.optJSONArray("args") ?: JSONArray()
        fun dimension(at: Int, fallback: Int = 0) = args.optDouble(at, fallback.toDouble()).takeIf(Double::isFinite)?.coerceIn(0.0, 480.0)?.toInt() ?: fallback
        decorated = when (op.optString("name")) {
            "padding" -> {
                val inset = args.optJSONObject(0)
                if (inset != null) decorated.padding(horizontal = inset.optInt("horizontal", 0).coerceIn(0, 32).dp, vertical = inset.optInt("vertical", 0).coerceIn(0, 32).dp)
                else decorated.padding(dimension(0).coerceAtMost(32).dp)
            }
            "height" -> decorated.height(dimension(0, 40).dp)
            "heightIn" -> decorated.heightIn(min = dimension(0).dp, max = dimension(1, 480).coerceAtLeast(dimension(0)).dp)
            "width" -> decorated.widthIn(max = dimension(0, 320).dp)
            "size" -> decorated.sizeIn(maxWidth = dimension(0, 24).dp, maxHeight = dimension(1, dimension(0, 24)).dp)
            else -> decorated
        }
    }
    if (type !in setOf("Button", "TextButton", "OutlinedButton", "FilledTonalButton", "IconButton", "FilledIconButton") && callback("onClick") != null)
        decorated = decorated.clickable(enabled = active) { click("onClick") }
    val layout = decorated.fillMaxWidth().padding(horizontal = padding.dp)
    when (type) {
        "Column", "LazyColumn", "MaterialTheme" -> Column(layout, verticalArrangement = Arrangement.spacedBy(space.dp)) { renderChildren() }
        "Row", "LazyRow" -> BoxWithConstraints(layout) {
            val columns = if (maxWidth < 300.dp) 1 else 2
            val width = (maxWidth - space.dp * (columns - 1)) / columns
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.dp), verticalArrangement = Arrangement.spacedBy(space.dp), maxItemsInEachRow = columns) {
                for (index in 0 until children.length()) Box(Modifier.width(width)) { PackageComposeNode(children.getJSONObject(index), enabled, action, close, commandsEnabled = commandsEnabled, editing = editing, revision = revision) }
            }
        }
        "Box" -> Box(layout, contentAlignment = Alignment.CenterStart) { Column(verticalArrangement = Arrangement.spacedBy(space.dp)) { renderChildren() } }
        "Scaffold" -> Column(layout, verticalArrangement = Arrangement.spacedBy(space.dp)) { slot("topBar"); slot("content"); renderChildren(); slot("bottomBar") }
        "TopAppBar" -> Row(layout, verticalAlignment = Alignment.CenterVertically) { slot("navigationIcon"); slot("title"); renderChildren(); slot("actions") }
        "Surface", "Card", "ElevatedCard", "OutlinedCard" -> Column(layout.background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(space.dp)) { renderChildren() }
        "Text" -> {
            val style = when (props.optString("style")) {
                "titleLarge", "titleMedium", "titleSmall", "headlineSmall", "headlineMedium" -> MiuixTheme.textStyles.body1
                "bodySmall", "labelSmall", "labelMedium" -> MiuixTheme.textStyles.footnote1
                else -> MiuixTheme.textStyles.body2
            }
            val color = packageColor(props.opt("color"))
            SelectionContainer { Text(props.optString("text"), decorated, style = style, color = color,
                fontWeight = when (props.optString("fontWeight")) { "bold" -> FontWeight.Bold; "medium", "semibold" -> FontWeight.Medium; else -> null }) }
        }
        "Markdown" -> StaticMarkdown(props.optString("text"), tone = MarkdownTone.Answer, modifier = layout)
        "SelectionContainer" -> SelectionContainer { Column(layout) { slot("content"); renderChildren() } }
        "Image" -> {
            val source = listOf("src", "path", "uri", "url", "fileUri").firstNotNullOfOrNull { props.optString(it).takeIf(String::isNotBlank) }
            val images = LocalPackageImages.current
            if (source != null && images != null) DshImage(if (source.startsWith("data:") || source.startsWith("asset:")) source else "asset:$source",
                props.optString("contentDescription"), props.optDouble("aspectRatio").toFloat(), images)
            else Icon(Icons.Rounded.Image, props.optString("contentDescription").takeIf(String::isNotBlank), Modifier.size(28.dp))
        }
        "Canvas" -> PackageCanvas(props, active, action, decorated)
        "Slider" -> {
            val range = props.optJSONArray("valueRange")
            val low = range?.optDouble(0)?.takeIf { it.isFinite() && it in -1_000_000.0..1_000_000.0 } ?: 0.0
            val high = range?.optDouble(1)?.takeIf { it.isFinite() && it > low && it - low <= 1_000_000 } ?: low + 1.0
            val source = props.optDouble("value", low).takeIf(Double::isFinite)?.coerceIn(low, high)?.toFloat() ?: low.toFloat()
            var draft by remember(source) { mutableFloatStateOf(source) }
            var changed by remember(source) { mutableStateOf(false) }
            LaunchedEffect(draft, active) { if (changed && active) { delay(250); changed = false; click("onValueChange", draft) } }
            Column(layout) {
                props.optString("label").takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.body2) }
                Slider(value = draft, onValueChange = { draft = it; changed = true }, enabled = active && callback("onValueChange") != null,
                    valueRange = low.toFloat()..high.toFloat(), steps = props.optInt("steps", 0).coerceIn(0, 200), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
        "RadioButton" -> Row(layout, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(enabled = active, onClick = { click("onClick") }) {
                Icon(if (props.optBoolean("selected")) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    props.optString("contentDescription").ifBlank { if (props.optBoolean("selected")) "已选择" else "选择" })
            }
            props.optString("text").takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.body2) }
        }
        "TabRow", "ScrollableTabRow" -> FlowRow(modifier = layout, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            renderChildren(); slot("tabs")
        }
        "Tab" -> EtaChoiceChip(props.optString("text").ifBlank { children.optJSONObject(0)?.optJSONObject("props")?.optString("text").orEmpty() }.ifBlank { "选项" },
            selected = props.optBoolean("selected"), enabled = active, onClick = { click("onClick") })
        "DropdownMenu" -> AnimatedVisibility(props.optBoolean("expanded")) {
            Column(layout.background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(enabled = active, onClick = { click("onDismissRequest") }) { Icon(Icons.Rounded.Close, "关闭选项") }
                }
                slot("content"); renderChildren()
            }
        }
        "DropdownMenuItem" -> TextButton(props.optString("text").ifBlank { slots.optJSONArray("text")?.optJSONObject(0)?.optJSONObject("props")?.optString("text").orEmpty() }
            .ifBlank { children.optJSONObject(0)?.optJSONObject("props")?.optString("text").orEmpty() }.ifBlank { "选项" }, enabled = active,
            onClick = { click("onClick") }, modifier = layout, minHeight = 40.dp, textStyle = MiuixTheme.textStyles.body2)
        "Switch" -> Row(layout, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Switch(checked = props.optBoolean("checked"), enabled = active, onCheckedChange = { click("onCheckedChange", it) })
        }
        "Checkbox" -> Row(layout, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state = if (props.optBoolean("checked")) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                enabled = active, onClick = { click("onCheckedChange", !props.optBoolean("checked")) })
            props.optString("text").takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.body2) }
        }
        "TextField", "OutlinedTextField", "BasicTextField" -> {
            val source = props.optString("value")
            var draft by remember { mutableStateOf(source) }
            var dirty by remember { mutableStateOf(false) }
            var submitted by remember { mutableStateOf<String?>(null) }
            var submittedAt by remember { mutableLongStateOf(-1) }
            val fieldId = callback("onValueChange").orEmpty()
            DisposableEffect(fieldId) { onDispose { editing(fieldId, false) } }
            LaunchedEffect(source, revision) {
                if (!dirty || draft == source || revision > submittedAt && submitted == draft) {
                    draft = source; dirty = false; submitted = null; editing(fieldId, false)
                }
            }
            LaunchedEffect(draft, enabled) {
                if (dirty && enabled && draft != source && submitted != draft && fieldId.isNotBlank()) {
                    delay(350); submitted = draft; submittedAt = revision; click("onValueChange", draft)
                }
            }
            TextField(value = draft, onValueChange = { draft = it.take(16_000); dirty = draft != source; editing(fieldId, dirty) },
                label = props.optString("label"), singleLine = props.optBoolean("singleLine"), enabled = props.optBoolean("enabled", true), modifier = layout)
        }
        "Button", "TextButton", "OutlinedButton", "FilledTonalButton" -> TextButton(
            text = props.optString("text").ifBlank { children.optJSONObject(0)?.optJSONObject("props")?.optString("text").orEmpty() }.ifBlank { "执行" },
            enabled = active && callback("onClick") != null, onClick = { click("onClick") }, modifier = layout,
            textStyle = MiuixTheme.textStyles.body2, minHeight = 42.dp)
        "Icon", "IconButton", "FilledIconButton" -> {
            val name = props.optString("name").ifBlank { props.optString("icon") }
            val icon = when (name.lowercase()) {
                "save" -> Icons.Rounded.Save; "close" -> Icons.Rounded.Close; "settings" -> Icons.Rounded.Settings; "refresh" -> Icons.Rounded.Refresh
                "add" -> Icons.Rounded.Add; "delete" -> Icons.Rounded.Delete; "check" -> Icons.Rounded.Check; "edit" -> Icons.Rounded.Edit
                else -> Icons.Rounded.Extension
            }
            if (type == "Icon") Icon(icon, props.optString("contentDescription").takeIf(String::isNotBlank), Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
            else IconButton(enabled = active && callback("onClick") != null, onClick = { click("onClick") }) { Icon(icon, props.optString("contentDescription").ifBlank { name }) }
        }
        "Spacer" -> Spacer(Modifier.height(props.optInt("height", 8).coerceIn(0, 120).dp).width(props.optInt("width", 0).coerceIn(0, 120).dp))
        "Divider", "HorizontalDivider", "VerticalDivider" -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        "CircularProgressIndicator", "LinearProgressIndicator" -> {
            val number = props.optDouble("progress", props.optDouble("value", Double.NaN))
            if (!number.isFinite()) InfiniteProgressIndicator(modifier = Modifier.size(24.dp))
            else {
                val target = number.toFloat().coerceIn(0f, 1f)
                val progress by animateFloatAsState(target, label = "package-progress")
                val color = MiuixTheme.colorScheme.primary; val track = MiuixTheme.colorScheme.surfaceContainer
                Canvas((if (type == "LinearProgressIndicator") layout.height(6.dp) else modifier.size(28.dp))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) }) {
                    if (type == "LinearProgressIndicator") { drawRoundRect(track); drawRoundRect(color, size = Size(size.width * progress, size.height)) }
                    else { drawCircle(track, style = Stroke(3.dp.toPx())); drawArc(color, -90f, progress * 360f, false, style = Stroke(3.dp.toPx())) }
                }
            }
        }
        "AlertDialog" -> Column(layout, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            props.optString("title").takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.body1) }
            slot("title"); slot("text"); slot("content"); renderChildren()
            props.optString("text").takeIf(String::isNotBlank)?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                props.optString("dismissText").takeIf(String::isNotBlank)?.let { TextButton(it, enabled = enabled, onClick = {
                    if (callback("onDismiss") != null) click("onDismiss") else close()
                }, modifier = Modifier.weight(1f)) }
                props.optString("confirmText").takeIf(String::isNotBlank)?.let { TextButton(it, enabled = active, onClick = { click("onConfirm") }, modifier = Modifier.weight(1f)) }
            }
            slot("confirmButton"); slot("dismissButton")
        }
    }
}
