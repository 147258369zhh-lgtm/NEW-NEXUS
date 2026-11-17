package io.github.mangi.eta.ui.components

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun NexaFileCard(source: NexaAttachmentSource, modifier: Modifier = Modifier, allowPreview: Boolean = true, compact: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember(source.uri) { mutableStateOf(false) }
    var busy by remember(source.uri) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(source.mime)) { destination ->
        if (destination != null) scope.launch {
            busy = true
            try { source.export(context, destination); Toast.makeText(context, "文件已保存", Toast.LENGTH_SHORT).show() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, "保存失败，原文件未删除", Toast.LENGTH_SHORT).show() }
            finally { busy = false }
        }
    }
    Column(modifier.fillMaxWidth().padding(vertical = if (compact) 2.dp else 8.dp)) {
        if (!compact) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.InsertDriveFile, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(source.label, style = MiuixTheme.textStyles.footnote1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(source.mime + (source.byteCount?.let { " · ${fileSize(it)}" } ?: ""), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (allowPreview) NexaCompactAction(text = "预览", onClick = { preview = true })
            NexaCompactAction(text = "打开", enabled = !busy, onClick = { scope.launch {
                busy = true
                try { source.launch(context) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, "没有可打开此文件的应用，请选择保存", Toast.LENGTH_LONG).show() }
                finally { busy = false }
            } })
            NexaCompactAction(text = if (busy) "处理中" else "保存", enabled = !busy, onClick = { save.launch(safeAttachmentName(source.label)) })
        }
    }
    if (preview) NexaBoundedFilePreview(source, { preview = false })
}

private fun fileSize(bytes: Long) = when { bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / 1048576.0); bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0); else -> "$bytes B" }

private sealed interface FilePreview {
    data class Text(val text: String, val more: Boolean) : FilePreview
    data class Csv(val rows: List<List<String>>, val limited: Boolean) : FilePreview
    data class Json(val root: JsonElement) : FilePreview
    data class Archive(val entries: List<String>, val limited: Boolean) : FilePreview
    data class Bytes(val text: String) : FilePreview
    data class Failure(val text: String) : FilePreview
}

@Composable
internal fun NexaBoundedFilePreview(source: NexaAttachmentSource, dismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember(source.uri) { mutableIntStateOf(0) }
    val preview by produceState<FilePreview?>(null, source.uri, page) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { readPreview(context, source, page) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { FilePreview.Failure(failure.message ?: "文件预览不可用，仍可保存或外部打开") }
        }
    }
    WindowDialog(show = true, title = source.label, onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            when (val content = preview) {
                null -> Text("正在读取有界预览", Modifier.padding(12.dp))
                is FilePreview.Csv -> {
                    Text("CSV 只读预览 · ${content.rows.size} 行${if (content.limited) "，达到预览限额" else ""}", style = MiuixTheme.textStyles.footnote2)
                    val columns = content.rows.maxOfOrNull { it.size } ?: 1
                    Box(Modifier.fillMaxWidth().height(420.dp).horizontalScroll(rememberScrollState())) {
                        LazyColumn(Modifier.width((columns.coerceAtMost(128) * 148).dp)) {
                            items(content.rows.size) { row -> Row {
                                repeat(columns.coerceAtMost(128)) { column -> SelectionContainer {
                                    Text(content.rows[row].getOrNull(column).orEmpty(), Modifier.width(148.dp).background(if (row % 2 == 0) MiuixTheme.colorScheme.surfaceContainer else MiuixTheme.colorScheme.surface).padding(8.dp),
                                        style = MiuixTheme.textStyles.footnote1, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                } }
                            } }
                        }
                    }
                }
                is FilePreview.Json -> LazyColumn(Modifier.fillMaxWidth().height(440.dp)) { item { JsonBranch("根", content.root, 0) } }
                is FilePreview.Archive -> {
                    Text("ZIP 只读目录；不会解压或执行${if (content.limited) " · 目录已截断" else ""}", style = MiuixTheme.textStyles.footnote2)
                    LazyColumn(Modifier.fillMaxWidth().height(420.dp)) { items(content.entries) { entry -> SelectionContainer { Text(entry, Modifier.padding(6.dp), style = MiuixTheme.textStyles.footnote1) } } }
                }
                is FilePreview.Text -> {
                    SelectionContainer { Text(content.text, Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(8.dp), style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)) }
                    Row {
                        NexaCompactAction(text = "上一页", enabled = page > 0, onClick = { page-- })
                        Text("第 ${page + 1} 页", Modifier.align(Alignment.CenterVertically), style = MiuixTheme.textStyles.footnote2)
                        NexaCompactAction(text = "下一页", enabled = content.more && page < 127, onClick = { page++ })
                    }
                }
                is FilePreview.Bytes -> SelectionContainer { Text(content.text, Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp), style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace)) }
                is FilePreview.Failure -> Text(content.text, Modifier.padding(12.dp), style = MiuixTheme.textStyles.footnote1)
            }
            NexaCompactAction(text = "关闭", onClick = dismiss)
        }
    }
}

@Composable
private fun JsonBranch(label: String, element: JsonElement, depth: Int) {
    var expanded by remember(element, label) { mutableStateOf(depth == 0) }
    var visibleChildren by remember(element, label) { mutableIntStateOf(50) }
    val children = when (element) { is JsonObject -> element.entries.map { it.key to it.value }; is JsonArray -> element.mapIndexed { index, value -> "[$index]" to value }; else -> emptyList() }
    Column(Modifier.padding(start = if (depth > 0) 12.dp else 0.dp)) {
        if (children.isNotEmpty() && depth < 24) {
            Text("${if (expanded) "▾" else "▸"} $label · ${children.size} 项", Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 6.dp), style = MiuixTheme.textStyles.footnote1)
            if (expanded) {
                children.take(visibleChildren).forEach { (childLabel, value) -> key(childLabel) { JsonBranch(childLabel, value, depth + 1) } }
                if (visibleChildren < children.size) NexaCompactAction(text = "再显示 50 项", onClick = { visibleChildren = (visibleChildren + 50).coerceAtMost(1000) }, enabled = visibleChildren < 1000)
            }
        } else SelectionContainer { Text("$label: ${element.toString().take(4000)}", Modifier.padding(vertical = 4.dp), style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)) }
    }
}

private suspend fun readPreview(context: Context, source: NexaAttachmentSource, page: Int): FilePreview {
    if (source.mime == "application/zip") return zipDirectory(context, source)
    if (source.mime == "text/csv" || source.mime == "text/tab-separated-values") {
        val bytes = source.input(context).use { it.readNBytes(2 * 1024 * 1024 + 1) }
        val truncated = bytes.size > 2 * 1024 * 1024
        val rows = parseCsv(bytes.take(2 * 1024 * 1024).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF"), if (source.mime == "text/tab-separated-values") '\t' else ',')
        return FilePreview.Csv(rows, truncated || rows.size >= 10_000)
    }
    if (source.mime == "application/json" || source.mime.endsWith("+json")) {
        val bytes = source.input(context).use { it.readNBytes(512 * 1024 + 1) }
        if (bytes.size <= 512 * 1024) {
            val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            // Limit nesting before recursive JSON parsing so hostile input cannot exhaust the stack.
            require(jsonDepthWithinBudget(text)) { "JSON 嵌套超过 64 层，可保存原文件或用文本查看" }
            return FilePreview.Json(Json.parseToJsonElement(text))
        }
    }
    val textType = source.mime.startsWith("text/") || source.mime in setOf("application/json", "application/xml", "application/javascript") || source.mime.endsWith("+json")
    source.input(context).use { input ->
        val offset = page * TEXT_PAGE
        var remaining = offset.toLong()
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() >= 0) remaining-- else break
        }
        val bytes = input.readNBytes(TEXT_PAGE + 1)
        val limit = TEXT_PAGE
        val body = bytes.take(limit).toByteArray()
        val looksText = body.none { it == 0.toByte() } && body.count { (it.toInt() and 255) < 9 } <= body.size / 50
        if (textType || looksText) return FilePreview.Text(body.toString(Charsets.UTF_8), bytes.size > limit)
        return FilePreview.Bytes("前 ${body.size.coerceAtMost(4096)} 字节 · ${source.mime}\n" + body.take(4096).chunked(16).mapIndexed { index, row ->
            "%08x  ".format(index * 16) + row.joinToString(" ") { "%02x".format(it.toInt() and 255) } + "  " + row.map { if ((it.toInt() and 255) in 32..126) it.toInt().toChar() else '.' }.joinToString("")
        }.joinToString("\n"))
    }
}

private suspend fun zipDirectory(context: Context, source: NexaAttachmentSource): FilePreview.Archive {
    // ZipFile reads central-directory metadata without inflating any entry, avoiding ZIP bombs.
    val temporary = File.createTempFile("NEXA-directory-", ".zip", context.cacheDir)
    try {
        source.input(context).use { input -> temporary.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var count = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                count += n; require(count <= 32L * 1024 * 1024) { "ZIP 目录预览上限为 32 MiB；可外部打开或保存" }
                output.write(buffer, 0, n)
            }
        } }
        return ZipFile(temporary).use { archive ->
            val entries = archive.entries(); val result = ArrayList<String>(); var total = 0L; var limited = false
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                if (result.size >= 1000) { limited = true; break }
                val entry = entries.nextElement(); total += entry.size.coerceAtLeast(0)
                if (total > 256L * 1024 * 1024) { limited = true; break }
                val name = entry.name.take(512).replace(Regex("[\\p{Cntrl}]"), "_")
                result += "$name${if (entry.isDirectory) "/" else " · ${fileSize(entry.size.coerceAtLeast(0))}"}"
            }
            FilePreview.Archive(result, limited)
        }
    } finally { temporary.delete() }
}

/** RFC 4180 quoting, embedded newlines and CRLF, with allocation and row/column limits. */
private fun parseCsv(text: String, separator: Char): List<List<String>> {
    val rows = ArrayList<List<String>>(); val row = ArrayList<String>(); val field = StringBuilder(); var quoted = false; var i = 0
    fun cell() { if (row.size < 128) row += field.toString(); field.setLength(0) }
    fun endRow() { cell(); rows += row.toList(); row.clear() }
    while (i < text.length && rows.size < 10_000) {
        val c = text[i]
        if (c == '"') {
            if (quoted && text.getOrNull(i + 1) == '"') { if (field.length < 4096) field.append('"'); i++ }
            else if (quoted || field.isEmpty()) quoted = !quoted else if (field.length < 4096) field.append(c)
        } else if (!quoted && c == separator) cell()
        else if (!quoted && (c == '\n' || c == '\r')) { if (c == '\r' && text.getOrNull(i + 1) == '\n') i++; endRow() }
        else if (field.length < 4096) field.append(c)
        i++
    }
    if ((row.isNotEmpty() || field.isNotEmpty()) && rows.size < 10_000) endRow()
    return rows
}

private fun jsonDepthWithinBudget(text: String): Boolean {
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 64) return false }; '}', ']' -> depth-- }
    }
    return true
}
private const val TEXT_PAGE = 16 * 1024
