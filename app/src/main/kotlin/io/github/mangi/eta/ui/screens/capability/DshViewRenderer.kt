package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.dsh.DshProtocol
import io.github.mangi.eta.agent.dsh.DshViewDocument
import io.github.mangi.eta.agent.dsh.DshViewNode
import io.github.mangi.eta.ui.components.EtaChoiceChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class DshViewContext(val state: DshFormState, val actions: DshPanelActions, val document: DshViewDocument, val connected: Boolean)
internal val LocalDshView = staticCompositionLocalOf<DshViewContext?> { null }

@Composable
internal fun DshViewRenderer(view: JSONObject, data: Any?, pendingAction: String?,
    onAction: suspend (DshViewAction) -> Boolean, imageScope: String, connected: Boolean, generation: Long,
    image: suspend (String) -> ByteArray) {
    val coroutineScope = rememberCoroutineScope()
    val currentImage by rememberUpdatedState(image)
    val images = remember(imageScope, generation) { DshPanelImages(coroutineScope) { currentImage(it) } }
    DisposableEffect(images) { onDispose { images.close() } }
    val document = remember(view.toString(), data?.toString()) { runCatching { DshViewDocument.parse(view, data) }.getOrNull() }
    if (document == null) Text("插件界面有误", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    else key(imageScope) {
        var diagnosticsOpen by remember { mutableStateOf(false) }
        val state = remember { DshFormState(document, data) }
        val currentAction by rememberUpdatedState(onAction)
        val actions = remember(state) { DshPanelActions(coroutineScope, state, invoke = { currentAction(it) }) }
        DisposableEffect(actions) { onDispose { actions.close() } }
        SideEffect { actions.connection(connected, generation) }
        LaunchedEffect(document, data?.toString()) { state.update(document, data) }
        val effective = state.effectiveData()
        CompositionLocalProvider(LocalDshView provides DshViewContext(state, actions, document, connected)) {
            val body: @Composable () -> Unit = { RenderDshNode(document.root, effective, actions.pending ?: pendingAction, actions::action, images) }
            if (document.v2) {
                Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).clipToBounds().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Rounded.Extension, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text("插件内容", modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (document.diagnostics.isNotEmpty()) IconButton(onClick = { diagnosticsOpen = !diagnosticsOpen }) {
                            Icon(Icons.Rounded.Info, contentDescription = "样式诊断 ${document.diagnostics.size} 项", modifier = Modifier.size(18.dp))
                        }
                    }
                    AnimatedVisibility(diagnosticsOpen && document.diagnostics.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            document.diagnostics.forEach { item -> Column {
                                Text(item.message, style = MiuixTheme.textStyles.body2)
                                Text(item.path, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } }
                        }
                    }
                    body()
                    if (document.root.source.optString("type") != "form") state.failure?.let {
                        Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                    }
                }
            } else body()
        }
    }
}

@Composable
internal fun RenderDshNode(node: DshViewNode, data: Any?, pending: String?,
    onAction: (String, JSONObject) -> Deferred<Boolean>, image: DshPanelImages) {
    val source = node.source
    val scope = rememberCoroutineScope()
    fun value(key: String): Any? = DshViewDocument.value(source.opt(key), data)
    fun text(key: String, maximum: Int = 2000) = DshProtocol.text(value(key), maximum)
    fun array(key: String): JSONArray = value(key) as? JSONArray ?: JSONArray()
    fun action(raw: Any?): Pair<String, JSONObject>? = when (raw) {
        is String -> raw.takeIf(DshProtocol.actionId::matches)?.let { it to JSONObject() }
        is JSONObject -> raw.optString("id").takeIf(DshProtocol.actionId::matches)?.let {
            val args = DshProtocol.binding(raw.opt("args"), data) as? JSONObject ?: JSONObject()
            if (args.toString().toByteArray().size <= 1024) it to args else null
        }
        else -> null
    }
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    DshNodeSurface(node, data) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (LocalDshView.current?.document?.v2 == true && source.optString("type") in DshViewDocument.additions) {
            DshV2Node(node, data, pending, onAction, image)
        } else {
        when (source.optString("type")) {
            "stack" -> node.children.forEachIndexed { index, child ->
                RenderDshNode(child, data, pending, onAction, image)
                if (LocalDshView.current?.document?.v2 == true && index < node.children.lastIndex && (value("style") as? JSONObject)?.optBoolean("dividers") == true) HorizontalDivider()
            }
            "row" -> {
                if (LocalDshView.current?.document?.v2 == true) DshV2Row(node, data, pending, onAction, image) else {
                val minimum = 136.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth < minimum * 2 + 12.dp) 1 else 2
                    val width = (maxWidth - 12.dp * (columns - 1)) / columns
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = columns) {
                        node.children.forEach { child -> Box(Modifier.width(width)) { RenderDshNode(child, data, pending, onAction, image) } }
                    }
                }
                }
            }
            "text", "callout" -> SelectionContainer {
                val content = text("text")
                if (LocalDshView.current?.document?.v2 == true) DshStyledText(node, data, content) else {
                val codeBackground = MiuixTheme.colorScheme.surfaceContainer
                val formatted = remember(content, codeBackground) { dshInlineText(content, codeBackground) }
                Text(formatted, style = MiuixTheme.textStyles.body2, color = if (source.optString("tone") == "muted") muted else MiuixTheme.colorScheme.onSurface,
                    modifier = if (source.optString("type") == "callout") Modifier.fillMaxWidth().background(toneColor(text("tone")).copy(alpha = 0.10f)).padding(12.dp) else Modifier)
                }
            }
            "metric" -> {
                Text(text("label", 200), style = MiuixTheme.textStyles.body2, color = muted)
                if (LocalDshView.current?.document?.v2 == true) DshStyledText(node, data, text("value").ifBlank { "暂无数据" }, metric = true)
                else Text(text("value").ifBlank { "暂无数据" }, style = MiuixTheme.textStyles.headline2, fontFamily = FontFamily.Monospace, color = toneColor(text("tone")))
                if (text("caption").isNotBlank()) Text(text("caption"), style = MiuixTheme.textStyles.footnote1, color = muted)
            }
            "keyValue" -> {
                val entries = when (val items = value("items")) {
                    is JSONObject -> items.keys().asSequence().take(24).map { it to DshProtocol.text(DshProtocol.binding(items.opt(it), data)) }.toList()
                    is JSONArray -> (0 until minOf(24, items.length())).mapNotNull { i -> when (val row = items.opt(i)) {
                        is JSONArray -> DshProtocol.text(DshProtocol.binding(row.opt(0), data), 200) to DshProtocol.text(DshProtocol.binding(row.opt(1), data))
                        is JSONObject -> DshProtocol.text(DshProtocol.binding(row.opt("label"), data), 200) to DshProtocol.text(DshProtocol.binding(row.opt("value"), data))
                        else -> null
                    } }
                    else -> emptyList()
                }
                if (entries.isEmpty()) Text("暂无数据", color = muted)
                entries.forEach { (label, entry) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MiuixTheme.textStyles.body2, color = muted, modifier = Modifier.weight(1f))
                    Text(entry, style = MiuixTheme.textStyles.body2, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                } }
            }
            "progress" -> {
                val number = DshProtocol.text(value("value")).toDoubleOrNull()?.takeIf(Double::isFinite) ?: 0.0
                val maximum = DshProtocol.text(value("max")).toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 100.0
                val fraction = (number / maximum).coerceIn(0.0, 1.0).toFloat()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text("label"), style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
                    Text(text("caption").ifBlank { "${(fraction * 100).toInt()}%" }, style = MiuixTheme.textStyles.body2, color = muted)
                }
                val color = toneColor(text("tone"))
                Canvas(Modifier.fillMaxWidth().height(6.dp)) { drawRoundRect(color.copy(alpha = .12f)); drawRoundRect(color, size = Size(size.width * fraction, size.height)) }
            }
            "table" -> {
                val columns = array("columns"); val rows = array("rows")
                if (columns.length() == 0 || rows.length() == 0) Text("暂无数据", color = muted)
                else Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row {
                        for (index in 0 until minOf(columns.length(), 8)) {
                            val label = columns.opt(index).let { if (it is JSONObject) DshProtocol.text(it.opt("label")) else DshProtocol.text(it) }
                            Text(label, fontWeight = FontWeight.Medium, style = MiuixTheme.textStyles.body2, modifier = Modifier.width(132.dp).padding(8.dp))
                        }
                    }
                    for (index in 0 until minOf(rows.length(), 50)) Row {
                        val cells = rows.optJSONArray(index) ?: JSONArray()
                        for (column in 0 until minOf(columns.length(), 8)) Text(DshProtocol.text(DshProtocol.binding(cells.opt(column), data), 200),
                            style = MiuixTheme.textStyles.body2, modifier = Modifier.width(132.dp).padding(8.dp))
                    }
                }
            }
            "list", "steps" -> {
                val items = array("items"); val maximum = if (source.optString("type") == "steps") 12 else 50
                if (items.length() == 0) Text("暂无数据", color = muted)
                for (index in 0 until minOf(items.length(), maximum)) {
                    val item = items.optJSONObject(index) ?: continue
                    val title = DshProtocol.text(DshProtocol.binding(item.opt(if (source.optString("type") == "steps") "label" else "title"), data), 200)
                    val detail = DshProtocol.text(DshProtocol.binding(item.opt(if (source.optString("type") == "steps") "detail" else "subtitle"), data))
                    val callback = action(item.opt("action"))
                    if (callback == null) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(title, style = MiuixTheme.textStyles.body2)
                                if (detail.isNotBlank()) Text(detail, style = MiuixTheme.textStyles.footnote1, color = muted)
                            }
                            Text(DshProtocol.text(DshProtocol.binding(item.opt(if (source.optString("type") == "steps") "status" else "trailing"), data)),
                                style = MiuixTheme.textStyles.body2, color = toneColor(item.optString("tone")))
                        }
                    } else TextButton(text = title, enabled = LocalDshView.current?.connected == true, onClick = { onAction(callback.first, callback.second) }, modifier = Modifier.fillMaxWidth())
                }
            }
            "actions" -> {
                val items = array("items")
                if (LocalDshView.current?.document?.v2 == true) DshActionButtons((0 until minOf(items.length(), 4)).mapNotNull { index ->
                    val item = items.optJSONObject(index) ?: return@mapNotNull null
                    val callback = action(DshProtocol.binding(item.opt("action"), data)) ?: return@mapNotNull null
                    DshProtocol.text(DshProtocol.binding(item.opt("label"), data), 200) to { onAction(callback.first, callback.second); Unit }
                }, LocalDshView.current?.connected == true) else {
                for (index in 0 until minOf(items.length(), 4)) {
                    val item = items.optJSONObject(index) ?: continue
                    val callback = action(DshProtocol.binding(item.opt("action"), data)) ?: continue
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(text = DshProtocol.text(DshProtocol.binding(item.opt("label"), data), 200), enabled = LocalDshView.current?.connected == true,
                            onClick = { onAction(callback.first, callback.second) }, modifier = Modifier.weight(1f))
                        if (pending == callback.first) InfiniteProgressIndicator()
                    }
                }
                }
            }
            "confirm" -> {
                val id = text("id", 64)
                if (DshProtocol.actionId.matches(id) && LocalDshView.current?.document?.v2 == true) DshActionButtons(listOf(
                    text("cancelLabel", 40).ifBlank { "取消" } to { onAction(id, JSONObject().put("confirmed", false)); Unit },
                    text("confirmLabel", 40).ifBlank { "确认" } to { onAction(id, JSONObject().put("confirmed", true)); Unit }), LocalDshView.current?.connected == true)
                else if (DshProtocol.actionId.matches(id)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(text = text("cancelLabel", 40).ifBlank { "取消" }, enabled = pending == null,
                        onClick = { onAction(id, JSONObject().put("confirmed", false)) }, modifier = Modifier.weight(1f))
                    TextButton(text = text("confirmLabel", 40).ifBlank { "确认" }, enabled = pending == null,
                        onClick = { onAction(id, JSONObject().put("confirmed", true)) }, modifier = Modifier.weight(1f))
                }
            }
            "options" -> {
                val id = text("id", 64); val options = array("options")
                val rawSelected = value("selected")
                val initial = DshProtocol.optionValues(rawSelected)
                var selected by remember(source.toString(), initial.keys) { mutableStateOf(initial) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (index in 0 until minOf(options.length(), 8)) {
                        val option = options.optJSONObject(index) ?: continue
                        val label = DshProtocol.text(DshProtocol.binding(option.opt("label"), data), 200)
                        val value = DshProtocol.binding(option.opt("value"), data) ?: label
                        val optionKey = DshProtocol.optionKey(value)
                        EtaChoiceChip(label, selected = optionKey in selected, enabled = pending == null && DshProtocol.actionId.matches(id), onClick = {
                            val old = selected
                            selected = if (source.optBoolean("multi")) { if (optionKey in selected) selected - optionKey else selected + (optionKey to value) } else mapOf(optionKey to value)
                            val args = if (source.optBoolean("multi")) JSONObject().put("values", JSONArray(selected.values.toList())) else JSONObject().put("value", value)
                            val result = onAction(id, args)
                            scope.launch { try { if (!result.await()) selected = old } catch (cancelled: CancellationException) { selected = old; throw cancelled } }
                        })
                    }
                }
            }
            "compare" -> {
                val items = array("items")
                for (index in 0 until minOf(items.length(), 4)) {
                    val item = items.optJSONObject(index) ?: continue
                    Text(DshProtocol.text(DshProtocol.binding(item.opt("title"), data), 200), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                    val nested = JSONObject().put("type", "keyValue").put("items", item.optJSONArray("rows") ?: JSONArray())
                    RenderDshNode(DshViewNode(nested, "${node.path}.compare.$index", emptyList(), emptyList(), null), data, pending, onAction, image)
                }
            }
            "chart" -> DshChartView(source, data)
            "image" -> DshImage(text("src"), text("alt", 200), DshProtocol.text(value("aspect")).toFloatOrNull(), image)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Extension, contentDescription = null, tint = muted)
                Text("此组件尚未支持", style = MiuixTheme.textStyles.body2, color = muted)
            }
        }
        }
        if (node.truncated) Text("部分内容未显示", style = MiuixTheme.textStyles.footnote1, color = muted)
      }
    }
}

@Composable
private fun toneColor(tone: String): Color {
  val requested = when (tone) {
    "good", "success", "positive" -> Color(0xFF209665)
    "bad", "error", "negative", "danger" -> MiuixTheme.colorScheme.error
    "warn", "warning", "caution" -> Color(0xFFBA870F)
    "muted", "secondary" -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    else -> MiuixTheme.colorScheme.primary
  }
  return if (LocalDshView.current?.document?.v2 == true) dshReadable(requested, LocalDshBackground.current ?: MiuixTheme.colorScheme.surface) else requested
}

@Composable
internal fun DshImage(src: String, alt: String, requestedAspect: Float?, load: DshPanelImages) {
    var bitmap by remember(src, load) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(src, load) { bitmap = load.image(src) }
    val aspect = requestedAspect?.takeIf { it.isFinite() }?.coerceIn(.25f, 4f) ?: 1.5f
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 320.dp).aspectRatio(aspect), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text(alt.ifBlank { "图片无法显示" }, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        else Image(bitmap!!, alt, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}
