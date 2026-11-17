package io.github.mangi.eta.ui.screens.artwork

import android.content.Context
import io.github.mangi.eta.data.repository.NexusArtifactAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.UUID

internal data class NexaWebArtworkBundle(val id: String, val title: String, val entry: String, val root: File) {
    val source: File get() = File(root, entry)
    val thumbnail: File get() = File(root, "preview.png")
}

/** Generated pages never receive a real file:// URL or the logged-in browser's storage. */
internal object NexaWebArtworkStore {
    private const val MAX_SOURCE = 2 * 1024 * 1024
    private const val MAX_RESOURCE = 32L * 1024 * 1024
    private const val MAX_BUNDLE = 96L * 1024 * 1024
    private val idPattern = Regex("[a-f0-9]{64}")
    private val publicationLock = Any()
    private val references = listOf(
        Regex("(?:src|href|poster)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("url\\(\\s*[\"']?([^\\s)\"']+)", RegexOption.IGNORE_CASE),
        Regex("@import\\s+[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("(?:from|import|fetch\\()\\s*[\"']([^\"']+)[\"']"),
        Regex("(?:import|fetch|new\\s+URL)\\s*\\(\\s*[\"']([^\"']+)[\"']"),
    )
    private val assetExtensions = setOf("html", "htm", "svg", "css", "js", "mjs", "json", "txt", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "woff", "woff2", "ttf", "otf", "mp3", "wav", "ogg", "opus", "mp4", "webm", "m4a")

    fun accepts(language: String?, source: String): Boolean {
        val name = language?.trim()?.lowercase()?.substringBefore(' ')
        if (name in setOf("html", "htm", "xhtml", "svg")) return true
        val start = source.trimStart().take(256).lowercase()
        return start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<svg")
    }

    /** Resolve only inside the bundle. Query/fragment do not form part of a local file name. */
    fun resourcePath(from: String, reference: String): String? = runCatching {
        val input = reference.trim()
        if (input.isEmpty() || input.startsWith('#') || input.startsWith('/') || '\\' in input) return null
        val uri = URI(input.replace(" ", "%20"))
        if (uri.isAbsolute || uri.rawAuthority != null) return null
        val resolved = URI("https://nexa-artwork.invalid/$from").resolve(uri).normalize()
        val path = resolved.path?.removePrefix("/") ?: return null
        if (path.isBlank() || path.split('/').any { it == ".." || it == "." || it.isBlank() } || '\\' in path || ':' in path || path.any(Char::isISOControl)) return null
        path
    }.getOrNull()

    suspend fun prepareSource(context: Context, source: String, language: String?, title: String = "网页作品"): NexaWebArtworkBundle = withContext(Dispatchers.IO) {
        require(accepts(language, source)) { "此代码不是可预览的HTML或SVG作品" }
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE) { "作品源码过大，请保存为文件后查看" }
        val svg = language?.equals("svg", true) == true || source.trimStart().startsWith("<svg", true)
        val entry = if (svg) "index.svg" else "index.html"
        create(context, digest("$entry\n$source"), title, entry, source)
    }

    suspend fun prepareArtifact(context: Context, artifactId: String): NexaWebArtworkBundle = withContext(Dispatchers.IO) {
        val access = NexusArtifactAccess(context)
        val record = access.record(artifactId) ?: error("作品记录不存在")
        val source = access.readText(artifactId, MAX_SOURCE)
        val svg = record.mediaType?.substringBefore(';') == "image/svg+xml" || record.label.endsWith(".svg", true)
        val directory = staging(context)
        val entry = if (svg) "index.svg" else "index.html"
        File(directory, entry).writeText(source, Charsets.UTF_8)
        // Dependencies are snapshotted; only same-run verified resources are readable through Access.
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(entry to source)
        val visited = hashSetOf<String>()
        var total = source.toByteArray(Charsets.UTF_8).size.toLong()
        try {
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (from, text) = pending.removeFirst()
            val srcsets = Regex("srcset\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(text)
                .flatMap { it.groupValues[1].split(',').asSequence().map { item -> item.trim().substringBefore(' ') } }
            val refs = references.asSequence().flatMap { it.findAll(text).map { match -> match.groupValues[1] } } + srcsets
            for (reference in refs) {
                val path = resourcePath(from, reference) ?: continue
                if (path.substringAfterLast('.', "").lowercase() !in assetExtensions || path in visited) continue
                check(visited.size < 192) { "作品依赖文件过多，请精简后重试" }
                visited.add(path)
                val file = access.localResource(artifactId, path) ?: continue
                val destination = File(directory, path).canonicalFile
                if (!destination.toPath().startsWith(directory.canonicalFile.toPath()) || path in setOf(entry, "bundle.json", "preview.png")) continue
                destination.parentFile?.mkdirs()
                var size = 0L
                file.inputStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= MAX_RESOURCE && total + size <= MAX_BUNDLE) { "作品依赖资源过大，请拆分后查看" }
                        output.write(buffer, 0, count)
                    }
                } }
                total += size
                if (file.extension.lowercase() in setOf("css", "js", "mjs", "html", "htm") && size <= MAX_SOURCE) {
                    pending.add(path to destination.readText(Charsets.UTF_8))
                }
            }
        }
        // The version includes actual resource bytes. Deleted/changed assets cannot leak from
        // a prior preview, and readers only ever see a completely published snapshot.
        val fingerprint = MessageDigest.getInstance("SHA-256").apply {
            update(artifactId.toByteArray(Charsets.UTF_8))
            directory.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
                update("${file.relativeTo(directory).invariantSeparatorsPath}\u0000${file.length()}\u0000".toByteArray(Charsets.UTF_8))
                file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; update(buffer, 0, count) } }
            }
        }.digest().joinToString("") { "%02x".format(it) }
        val bundle = publish(context, directory, fingerprint, record.label, entry)
        File(root(context), "artifact-${digest(artifactId)}.json").writeText(JSONObject().put("bundle", bundle.id).toString())
        bundle
        } finally { if (directory.exists()) directory.deleteRecursively() }
    }

    fun thumbnail(context: Context, artifactId: String): File? = runCatching {
        val id = JSONObject(File(root(context), "artifact-${digest(artifactId)}.json").readText()).getString("bundle")
        load(context, id)?.thumbnail?.takeIf { it.isFile }
    }.getOrNull()

    fun load(context: Context, id: String): NexaWebArtworkBundle? = runCatching {
        require(idPattern.matches(id))
        val directory = File(root(context), id)
        val metadata = JSONObject(File(directory, "bundle.json").readText())
        val entry = metadata.getString("entry")
        require(entry in setOf("index.html", "index.svg"))
        NexaWebArtworkBundle(id, metadata.getString("title"), entry, directory).takeIf { it.source.isFile }
    }.getOrNull()

    suspend fun export(bundle: NexaWebArtworkBundle, output: OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(output).use { zip ->
            bundle.root.walkTopDown().filter { it.isFile && it.relativeTo(bundle.root).invariantSeparatorsPath !in setOf("bundle.json", "preview.png") && it.extension.lowercase() in assetExtensions }.forEach { file ->
                currentCoroutineContext().ensureActive()
                check(file.canonicalFile.toPath().startsWith(bundle.root.canonicalFile.toPath()))
                zip.putNextEntry(ZipEntry(file.relativeTo(bundle.root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
    }

    private fun root(context: Context) = File(context.applicationContext.filesDir, "nexa-web-artworks").apply { check(isDirectory || mkdirs()) }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun staging(context: Context) = File(root(context), ".pending-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private fun create(context: Context, id: String, title: String, entry: String, source: String): NexaWebArtworkBundle {
        val temporary = staging(context)
        try { File(temporary, entry).writeText(source, Charsets.UTF_8); return publish(context, temporary, id, title, entry) }
        finally { if (temporary.exists()) temporary.deleteRecursively() }
    }
    private fun publish(context: Context, temporary: File, id: String, title: String, entry: String): NexaWebArtworkBundle = synchronized(publicationLock) {
        val directory = File(root(context), id)
        File(temporary, "bundle.json").writeText(JSONObject().put("entry", entry).put("title", title.take(160)).toString())
        if (!directory.isDirectory) check(temporary.renameTo(directory)) { "作品快照保存失败" }
        NexaWebArtworkBundle(id, title.take(160), entry, directory)
    }
}
