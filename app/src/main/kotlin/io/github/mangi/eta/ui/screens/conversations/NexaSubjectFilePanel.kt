package io.github.mangi.eta.ui.screens.conversations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.NexusArtifactMedia
import io.github.mangi.eta.data.repository.SubjectInsightFile
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class NexaSubjectFileCategory(val label: String) {
    IMAGE("图片"), VIDEO("视频"), AUDIO("音频"), DOCUMENT("文档"), CODE("代码网页"), OTHER("其他");

    companion object {
        fun of(file: SubjectInsightFile): NexaSubjectFileCategory {
            if (file.directory) return OTHER
            val record = file.artifact
            val mime = NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path)
            val extension = record.label.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime in setOf("text/html", "application/xhtml+xml", "application/json", "application/javascript", "text/javascript", "text/css", "application/xml", "text/xml") ||
                    extension in setOf("kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "html", "htm", "css", "json", "xml", "yaml", "yml", "toml", "sh", "bash", "c", "h", "cpp", "rs", "go", "sql", "swift", "vue", "dart", "ini", "conf") -> CODE
                mime.startsWith("text/") || mime == "application/pdf" || mime.contains("officedocument") || mime.contains("msword") || mime.contains("ms-excel") || mime.contains("ms-powerpoint") ||
                    extension in setOf("pdf", "txt", "md", "rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "odt", "ods", "odp", "epub") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

@Composable
internal fun NexaSubjectFilePanel(
    ownerKey: String,
    inputs: List<SubjectInsightFile>,
    outputs: List<SubjectInsightFile>,
    busy: Boolean,
    loading: Boolean,
    importEnabled: Boolean,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
    onPreview: (SubjectInsightFile) -> Unit,
    onExport: (SubjectInsightFile) -> Unit,
    onShare: (SubjectInsightFile) -> Unit,
) {
    var source by rememberSaveable(ownerKey) { mutableIntStateOf(0) }
    val categories = NexaSubjectFileCategory.entries
    val sourceFiles = if (source == 0) inputs else outputs
    val grouped = remember(sourceFiles) { sourceFiles.groupBy(NexaSubjectFileCategory::of) }
    val pager = rememberPagerState { categories.size }
    val categoryList = rememberLazyListState()
    val notch = with(LocalDensity.current) { 3.dp.toPx() }
    val ticketShape = remember(notch) { GenericShape { size, _ ->
        val middle = size.height / 2
        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, middle - notch)
        quadraticBezierTo(size.width - notch * 2, middle, size.width, middle + notch)
        lineTo(size.width, size.height); lineTo(0f, size.height); lineTo(0f, middle + notch)
        quadraticBezierTo(notch * 2, middle, 0f, middle - notch); close()
    } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { categoryList.animateScrollToItem(pager.currentPage) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("项目文件 · ${inputs.size} 个导入 · ${outputs.size} 个输出", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
            NexaCompactAction("刷新", onRefresh, enabled = !busy && !loading, icon = Icons.Rounded.Refresh)
        }
        NexaSegmentedTabs(listOf("导入文件 · ${inputs.size}", "输出文件 · ${outputs.size}"), source, { source = it }, Modifier.padding(horizontal = 12.dp))
        if (source == 0) Card(Modifier.padding(horizontal = 12.dp).etaGlassSurface(accent = Color(0xFF1689FF), cornerRadius = 18.dp)) {
            Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                NexaCompactAction("", onImport, modifier = Modifier.size(44.dp).semantics {
                    contentDescription = if (busy) "正在处理文件" else "导入文件，可选择多个文件"
                },
                    enabled = importEnabled && !busy, icon = Icons.Rounded.FileUpload)
            }
        }
        // Category entries are compact tickets; the pager owns horizontal drag and settles to a category.
        LazyRow(Modifier.fillMaxWidth(), state = categoryList, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categories.size) { index ->
                val category = categories[index]
                NexaCompactAction("${category.label} ${grouped[category].orEmpty().size}",
                    { scope.launch { pager.animateScrollToPage(index) } }, modifier = Modifier.clip(ticketShape), selected = pager.currentPage == index)
            }
        }
        if (loading) Text("正在读取当前会话文件…", Modifier.padding(horizontal = 24.dp), style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        // A bounded list avoids an unbounded vertical child inside the detail screen's LazyColumn.
        HorizontalPager(pager, Modifier.fillMaxWidth().height(460.dp), key = { "${ownerKey}_${source}_$it" }) { page ->
            val category = categories[page]
            val files = grouped[category].orEmpty()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (files.isEmpty()) item {
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        ListEmptyState(title = if (loading) "正在读取文件" else "暂无${category.label}${if (source == 0) "输入" else "输出"}",
                            summary = if (source == 0) "导入文件、输入栏附件及关联项目的共享输入会显示在这里" else "这里只显示当前会话已验证的生成文件",
                            icon = Icons.Rounded.Description)
                    }
                }
                items(files, key = { "${source}_${it.artifact.id}" }) { file ->
                    NexaSubjectFileCard(file, category, busy, { onPreview(file) }, { onExport(file) }, { onShare(file) })
                }
            }
        }
    }
}

@Composable
private fun NexaSubjectFileCard(file: SubjectInsightFile, category: NexaSubjectFileCategory, busy: Boolean,
    onPreview: () -> Unit, onExport: () -> Unit, onShare: () -> Unit) {
    val accent = if (file.input) Color(0xFF1689FF) else Color(0xFF08D58A)
    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).etaGlassSurface(accent = accent, cornerRadius = 18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NexaSubjectFileMedia(file, category, Modifier.size(76.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(file.artifact.label, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (file.directory) file.artifact.path else subjectFileSize(file.artifact.sizeBytes), style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.source, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.availability, style = MiuixTheme.textStyles.footnote2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (!file.readable && !file.directory) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (!file.directory) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                NexaCompactAction("预览", onPreview, enabled = !busy && file.readable)
                NexaCompactAction("导出", onExport, enabled = !busy && file.readable)
                NexaCompactAction("分享", onShare, enabled = !busy && file.readable)
            }
        }
    }
}

private fun subjectFileSize(bytes: Long?): String = when {
    bytes == null -> "大小未记录"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
