package io.github.mangi.eta.ui.screens.artwork

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.screens.browser.AgentBrowserActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection

/** Runs in :artwork, with a distinct WebView profile and no bridge to Agent/browser credentials. */
class NexaWebArtworkActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var link by mutableStateOf<String?>(null)
    private var pageError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!profileSet) {
            WebView.setDataDirectorySuffix("nexa_artwork")
            profileSet = true
        }
        enableEdgeToEdge()
        val appearance = runCatching { Json.decodeFromString(AppearanceSettings.serializer(), intent.getStringExtra("appearance") ?: "{}") }
            .getOrDefault(AppearanceSettings())
        lifecycleScope.launch {
            val bundle = withContext(Dispatchers.IO) { NexaWebArtworkStore.load(this@NexaWebArtworkActivity, intent.getStringExtra("bundle").orEmpty()) }
            if (bundle == null) { Toast.makeText(this@NexaWebArtworkActivity, "作品不可读取，请从消息重新打开", Toast.LENGTH_SHORT).show(); finish(); return@launch }
            setContent { AgentAppTheme(appearance, applyInterfaceScale = true) { Artwork(bundle) } }
        }
    }

    @Composable
    private fun Artwork(bundle: NexaWebArtworkBundle) {
        var showSource by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val source by produceState("", bundle.id) {
            value = withContext(Dispatchers.IO) { bundle.source.readText(Charsets.UTF_8).let { if (it.length > 96_000) it.take(96_000) + "\n\n… 完整源码可通过保存作品查看" else it } }
        }
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
            if (destination != null) lifecycleScope.launch {
                busy = true
                try { withContext(Dispatchers.IO) { contentResolver.openOutputStream(destination, "wt")?.let { NexaWebArtworkStore.export(bundle, it) } ?: error("无法写入所选位置") }; toast("作品已保存，包含本地依赖资源") }
                catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; toast("作品保存失败") }
                finally { busy = false }
            }
        }
        BackHandler { if (showSource) showSource = false else finish() }
        SideEffect { webView?.apply { visibility = if (showSource) View.INVISIBLE else View.VISIBLE; if (showSource) pauseTimers() else resumeTimers() } }
        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (showSource) showSource = false else finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text(bundle.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body1)
                NexaCompactAction(text = if (showSource) "预览" else "源码", onClick = { showSource = !showSource })
                NexaCompactAction(text = "", icon = Icons.Rounded.Download, modifier = Modifier.semantics { contentDescription = "保存完整作品" }, enabled = !busy, onClick = { save.launch("NEXA-作品-${bundle.id.take(10)}.zip") })
                NexaCompactAction(text = "", icon = Icons.Rounded.Share, modifier = Modifier.semantics { contentDescription = "分享作品" }, enabled = !busy, onClick = {
                    lifecycleScope.launch {
                        busy = true
                        try {
                            val file = withContext(Dispatchers.IO) {
                                val directory = File(cacheDir, "workspace-share").apply { check(isDirectory || mkdirs()) }
                                File(directory, "NEXA-作品-${bundle.id.take(10)}.zip").also { NexaWebArtworkStore.export(bundle, it.outputStream()) }
                            }
                            val uri = FileProvider.getUriForFile(this@NexaWebArtworkActivity, "$packageName.files", file)
                            val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = android.content.ClipData.newRawUri(bundle.title, uri) }
                            startActivity(Intent.createChooser(intent, "分享作品"))
                        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; toast("作品分享失败") }
                        finally { busy = false }
                    }
                })
            }
            pageError?.let { message -> Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                NexaCompactAction(text = "重试", onClick = { recreate() })
            } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { createWebView(bundle) }, modifier = Modifier.fillMaxSize())
                if (showSource) SelectionContainer { Text(source, Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)) }
            }
        }
        link?.let { url -> WindowDialog(show = true, title = "打开网页", summary = url, onDismissRequest = { link = null }) {
            MiuixDialogActions(confirmText = "内置浏览器打开", onCancel = { link = null }, onConfirm = {
                link = null
                startActivity(AgentBrowserActivity.createIntent(this@NexaWebArtworkActivity, "artwork-links", url))
            })
        } }
    }

    private fun createWebView(bundle: NexaWebArtworkBundle): WebView {
        val host = "${bundle.id}.nexa-artwork.invalid"
        return WebView(this).apply {
            webView = this
            isLongClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnLongClickListener { false }
            resumeTimers()
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                safeBrowsingEnabled = true
            }
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
                override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean { callback?.onReceiveValue(null); return true }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == host) return false
                    if (request.isForMainFrame && request.hasGesture() && uri.scheme in setOf("https", "http")) link = uri.toString()
                    return true
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == host) {
                        return runCatching {
                        val path = uri.path?.removePrefix("/").orEmpty()
                        if (path.isBlank() || path.any(Char::isISOControl) || '\\' in path || path.split('/').any { it == ".." || it == "." } || path in setOf("bundle.json", "preview.png")) return denied()
                        val file = File(bundle.root, path).canonicalFile
                        if (!file.toPath().startsWith(bundle.root.canonicalFile.toPath()) || !file.isFile) return denied()
                        val mime = when (file.extension.lowercase()) { "html", "htm" -> "text/html"; "svg" -> "image/svg+xml"; "css" -> "text/css"; "js", "mjs" -> "application/javascript"; "json" -> "application/json"; else -> URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream" }
                        WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                            "Content-Security-Policy" to "default-src 'self' https: data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval' https: blob:; style-src 'self' 'unsafe-inline' https:; connect-src https:; frame-src 'none'; object-src 'none'; base-uri 'self'; form-action 'none'",
                            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=()",
                            "X-Content-Type-Options" to "nosniff",
                        ), file.inputStream())
                        }.getOrElse { denied() }
                    }
                    return if (uri.scheme in setOf("https", "data", "blob")) null else denied()
                }
                override fun onPageFinished(view: WebView, url: String) { view.postDelayed({ capture(view, bundle) }, 900) }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) { if (request.isForMainFrame) pageError = "作品暂时无法显示，可查看源码或重试" }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy(); webView = null; pageError = "网页渲染已停止，作品文件仍然保留"
                    return true
                }
            }
            loadUrl("https://$host/${bundle.entry}")
        }
    }

    private fun capture(view: WebView, bundle: NexaWebArtworkBundle) {
        if (isFinishing || isDestroyed || !view.isAttachedToWindow || view.visibility != View.VISIBLE || view.width <= 0 || view.height <= 0) return
        val position = IntArray(2).also(view::getLocationInWindow)
        val height = minOf(view.height, view.width * 3 / 4)
        val width = minOf(view.width, 720)
        val bitmap = Bitmap.createBitmap(width, (height.toFloat() * width / view.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, Rect(position[0], position[1], position[0] + view.width, position[1] + height), bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    val job = lifecycleScope.launch(Dispatchers.IO) {
                        val temporary = File(bundle.root, "preview-${java.util.UUID.randomUUID()}.tmp")
                        try {
                            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            java.nio.file.Files.move(temporary.toPath(), bundle.thumbnail.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        } catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure }
                        finally { temporary.delete() }
                    }
                    job.invokeOnCompletion { bitmap.recycle() }
                }
                else bitmap.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) { bitmap.recycle() }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onResume() { super.onResume(); webView?.onResume(); webView?.resumeTimers() }
    override fun onPause() { webView?.onPause(); webView?.pauseTimers(); super.onPause() }
    override fun onDestroy() { webView?.stopLoading(); webView?.destroy(); webView = null; super.onDestroy() }

    companion object {
        private var profileSet = false
        suspend fun openSource(context: Context, source: String, language: String?) = open(context, NexaWebArtworkStore.prepareSource(context, source, language))
        suspend fun openArtifact(context: Context, artifactId: String) = open(context, NexaWebArtworkStore.prepareArtifact(context, artifactId))
        private suspend fun open(context: Context, bundle: NexaWebArtworkBundle) {
            val appearance = AppearanceSettingsRepository.settings()
            withContext(Dispatchers.Main) { context.startActivity(Intent(context, NexaWebArtworkActivity::class.java)
                .putExtra("bundle", bundle.id).putExtra("appearance", Json.encodeToString(AppearanceSettings.serializer(), appearance))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
