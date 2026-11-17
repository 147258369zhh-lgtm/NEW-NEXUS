package io.github.mangi.eta.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import io.github.mangi.eta.ui.markdown.ImageLoadState
import io.github.mangi.eta.ui.markdown.MarkdownImageLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun NexusImagePreview(
    uri: String,
    label: String,
    show: Boolean,
    onDismiss: () -> Unit,
    artifactId: String? = null,
) {
    if (!show) return
    val context = LocalContext.current
    val access = remember(context) { NexusArtifactAccess(context) }
    val scope = rememberCoroutineScope()
    var saving by remember(artifactId) { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { target ->
        if (target != null) scope.launch {
            saving = true
            try {
                if (artifactId != null) access.exportTo(artifactId, target)
                else MarkdownImageLoader.exportOriginal(context, uri, target)
                toast(context, "图片已保存")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(context, "图片保存失败")
            } finally {
                saving = false
            }
        }
    }
    val state by produceState<ImageLoadState>(ImageLoadState.Loading, uri) {
        value = MarkdownImageLoader.load(context, uri)
    }
    val regionInfo by produceState<NexaRegionImageLoader.Info?>(null, uri) { value = NexaRegionImageLoader.info(context, uri) }
    var requestedScale by remember(uri) { mutableStateOf<Float?>(null) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val colors = MiuixTheme.colorScheme
    WindowDialog(show = true, title = label, cornerRadius = DialogDefaults.CornerRadius, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))) {
            Row(Modifier.fillMaxWidth().padding(8.dp).etaGlassSurface(cornerRadius = 20.dp)
                .background(colors.surface.copy(alpha = .96f), RoundedCornerShape(20.dp)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (artifactId != null) {
                        ImageFileAction(Icons.Rounded.ContentCopy, "复制图片") {
                            scope.launch {
                                runCatching { access.shareUri(artifactId) }.onSuccess { (shared, _) ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, label, shared))
                                    toast(context, "图片已复制")
                                }.onFailure { toast(context, "无法复制图片") }
                            }
                        }
                        ImageFileAction(Icons.Rounded.Download, if (saving) "保存中" else "保存图片", enabled = !saving) {
                            saveLauncher.launch(safeMediaName(label, "png"))
                        }
                        ImageFileAction(Icons.Rounded.Share, "分享图片") {
                            scope.launch {
                                runCatching { access.launch(artifactId, share = true) }
                                    .onFailure { toast(context, "无法分享图片") }
                            }
                        }
                    } else {
                        ImageFileAction(Icons.Rounded.Download, if (saving) "保存中" else "保存图片", enabled = !saving) {
                            saveLauncher.launch(safeMediaName(label, extensionFor(context, uri)))
                        }
                    }
                }
                PreviewAction(Icons.Rounded.Close, "关闭预览", onClick = onDismiss)
            }
            when (val current = state) {
                ImageLoadState.Loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("正在加载图片", color = Color.White, style = MiuixTheme.textStyles.body2)
                }
                is ImageLoadState.Failed -> Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(current.reason, color = Color(0xFFFF6B6B), style = MiuixTheme.textStyles.footnote1)
                }
                is ImageLoadState.Ready -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val density = LocalDensity.current
                    val controlsHeight = 48.dp
                    val viewportWidth = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                    val viewportHeight = with(density) { (maxHeight - controlsHeight).coerceAtLeast(1.dp).toPx() }.coerceAtLeast(1f)
                    val fitScale = minOf(viewportWidth / current.bitmap.width, viewportHeight / current.bitmap.height)
                    // ImageDecoder may apply orientation; raw regions must never cover a differently oriented preview.
                    val original = regionInfo?.takeIf {
                        val originalRatio = it.width.toDouble() / it.height
                        val previewRatio = current.bitmap.width.toDouble() / current.bitmap.height
                        kotlin.math.abs(originalRatio / previewRatio - 1.0) < .01
                    }
                    // Keep Compose's scroll layout bounded even for a 100,000 px source.
                    val nativeSizeAvailable = original != null && maxOf(original.width, original.height) <= 32_000
                    val nativeScale = if (nativeSizeAvailable) original!!.width.toFloat() / current.bitmap.width else 1f
                    val minScale = minOf(fitScale, 1f)
                    val maxScale = minOf(32_000f / maxOf(current.bitmap.width, current.bitmap.height), maxOf(fitScale, nativeScale, 4f))
                    val scale = requestedScale?.coerceIn(minScale, maxScale) ?: fitScale
                    val displayWidth = current.bitmap.width * scale
                    val displayHeight = current.bitmap.height * scale
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().height(controlsHeight).background(colors.surface.copy(alpha = .96f))
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(text = "适屏", modifier = Modifier.height(44.dp),
                                colors = if (requestedScale == null) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                                onClick = { requestedScale = null; scope.launch { horizontal.scrollTo(0); vertical.scrollTo(0) } })
                            TextButton(text = if (nativeSizeAvailable) "原尺寸" else "预览尺寸", modifier = Modifier.height(44.dp),
                                colors = if (requestedScale == nativeScale) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                                onClick = { requestedScale = nativeScale; scope.launch { horizontal.scrollTo(0); vertical.scrollTo(0) } })
                            PreviewAction(Icons.Rounded.ZoomOut, "缩小图片", enabled = scale > minScale + .001f) {
                                requestedScale = (scale / 1.5f).coerceIn(minScale, maxScale)
                            }
                            Text("${(scale * 100).toInt()}%", style = MiuixTheme.textStyles.footnote2, color = colors.onSurfaceVariantSummary)
                            PreviewAction(Icons.Rounded.ZoomIn, "放大图片", enabled = scale < maxScale - .001f) {
                                requestedScale = (scale * 1.5f).coerceIn(minScale, maxScale)
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                            // Only the drawing size changes: the bounded sampled bitmap stays the base at every zoom.
                            Box(Modifier.fillMaxSize().horizontalScroll(horizontal).verticalScroll(vertical)
                                .sizeIn(minWidth = with(density) { viewportWidth.toDp() }, minHeight = with(density) { viewportHeight.toDp() }),
                                contentAlignment = Alignment.Center) {
                                Image(current.bitmap.asImageBitmap(), label,
                                    Modifier.size(with(density) { displayWidth.toDp() }, with(density) { displayHeight.toDp() }),
                                    contentScale = ContentScale.FillBounds)
                            }
                            if (original != null && scale > 1f && (original.width > current.bitmap.width || original.height > current.bitmap.height)) {
                                val visibleWidth = minOf(viewportWidth, displayWidth)
                                val visibleHeight = minOf(viewportHeight, displayHeight)
                                val left = (horizontal.value / displayWidth * original.width).toInt().coerceIn(0, original.width - 1)
                                val top = (vertical.value / displayHeight * original.height).toInt().coerceIn(0, original.height - 1)
                                val right = kotlin.math.ceil((horizontal.value + visibleWidth) / displayWidth * original.width).toInt().coerceIn(left + 1, original.width)
                                val bottom = kotlin.math.ceil((vertical.value + visibleHeight) / displayHeight * original.height).toInt().coerceIn(top + 1, original.height)
                                val rect = android.graphics.Rect(left, top, right, bottom)
                                val detail by produceState<android.graphics.Bitmap?>(null, uri, rect, visibleWidth.toInt(), visibleHeight.toInt()) {
                                    value = null
                                    delay(120)
                                    value = NexaRegionImageLoader.region(context, uri, rect, visibleWidth.toInt(), visibleHeight.toInt())
                                }
                                detail?.let { bitmap -> Image(bitmap.asImageBitmap(), null,
                                    Modifier.offset(with(density) { ((viewportWidth - displayWidth).coerceAtLeast(0f) / 2).toDp() },
                                        with(density) { ((viewportHeight - displayHeight).coerceAtLeast(0f) / 2).toDp() })
                                        .size(with(density) { visibleWidth.toDp() }, with(density) { visibleHeight.toDp() }),
                                    contentScale = ContentScale.FillBounds) }
                            }
                            // Rails are drawn only for an overflowing axis; scrolling remains inside the preview.
                            Canvas(Modifier.fillMaxSize()) {
                                val thickness = 3.dp.toPx()
                                if (horizontal.maxValue > 0) {
                                    val track = size.width
                                    val thumb = (track * track / (track + horizontal.maxValue)).coerceAtLeast(20.dp.toPx()).coerceAtMost(track)
                                    val x = (track - thumb) * horizontal.value / horizontal.maxValue
                                    drawRoundRect(Color.White.copy(alpha = .65f), Offset(x, size.height - thickness), Size(thumb, thickness))
                                }
                                if (vertical.maxValue > 0) {
                                    val track = size.height
                                    val thumb = (track * track / (track + vertical.maxValue)).coerceAtLeast(20.dp.toPx()).coerceAtMost(track)
                                    val y = (track - thumb) * vertical.value / vertical.maxValue
                                    drawRoundRect(Color.White.copy(alpha = .65f), Offset(size.width - thickness, y), Size(thickness, thumb))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageFileAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    NexaCompactAction(text = "", icon = icon, enabled = enabled, onClick = onClick,
        modifier = Modifier.size(44.dp).semantics { contentDescription = description })
}

@Composable
private fun PreviewAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Icon(icon, description, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun safeMediaName(label: String, fallbackExtension: String): String {
    val name = label.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(160)
    if (name.isBlank()) return "NEXA-media.$fallbackExtension"
    return if (name.substringAfterLast('.', "").isBlank()) "$name.$fallbackExtension" else name
}

private fun extensionFor(context: Context, uri: String): String = when (android.net.Uri.parse(uri).scheme?.lowercase()) {
    "data" -> uri.substringBefore(',').substringAfter("data:image/", "img").substringBefore(';').takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase() ?: "img"
    "content" -> context.contentResolver.getType(android.net.Uri.parse(uri))?.substringAfter('/', "img")
        ?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase() ?: "img"
    else -> uri.substringBefore('?').substringBefore('#').substringAfterLast('.', "img").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }?.lowercase() ?: "img"
}
