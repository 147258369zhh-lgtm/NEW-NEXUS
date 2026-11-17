package io.github.mangi.eta.agent.model.mnn

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Validation and packaging helpers for MNN-LLM model directories.
 *
 * An MNN chat model is not a single executable file. The minimum portable layout contains
 * `config.json`, `llm.mnn` and the tokenizer/weight files referenced by that config. Import only
 * succeeds after the whole directory has been inspected and copied.
 */
internal object MnnModelPackage {
    const val CONFIG_FILE = "config.json"
    const val GRAPH_FILE = "llm.mnn"
    const val VISUAL_FILE = "visual.mnn"

    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_SEARCH_DEPTH = 4

    fun isModelDirectory(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, CONFIG_FILE).isFile &&
            File(directory, GRAPH_FILE).isFile

    fun hasVision(directory: File): Boolean = File(directory, VISUAL_FILE).isFile

    /** These are the filenames consumed by the bundled MNN LLM loader, including its defaults. */
    fun validateForRuntime(directory: File) {
        require(isModelDirectory(directory)) { "MNN 模型目录缺少 config.json 或 llm.mnn" }
        val root = directory.canonicalFile
        val configFile = root.resolve(CONFIG_FILE)
        require(configFile.length() in 2..(1024 * 1024)) { "MNN config.json 无效或过大" }
        val config = JSONObject(configFile.readText())
        fun requireFile(key: String, fallback: String): File {
            val relative = config.optString(key, fallback)
            require(relative.isNotBlank() && !File(relative).isAbsolute && !relative.contains(':') && !relative.contains('\\') &&
                relative.split('/').none { it == ".." || it == "." || it.isBlank() }) { "MNN $key 必须引用包内文件" }
            val file = root.resolve(relative).canonicalFile
            require(file.toPath().startsWith(root.toPath()) && file.isFile && file.length() > 0) { "MNN 模型缺少 $relative" }
            return file
        }
        requireFile("llm_model", GRAPH_FILE)
        requireFile("llm_weight", "llm.mnn.weight")
        requireFile("tokenizer_file", "tokenizer.txt")
        val modelConfig = requireFile("llm_config", "llm_config.json")
        require(modelConfig.length() <= 1024 * 1024) { "MNN llm_config.json 过大" }
        val model = JSONObject(modelConfig.readText())
        if (directory.name.contains("minicpm", ignoreCase = true)) {
            val template = model.optJSONObject("jinja")?.optString("chat_template").orEmpty()
            require(template.isNotBlank()) { "MiniCPM MNN 包缺少 Jinja 聊天模板，请使用完整的官方转换包" }
            require(!model.optBoolean("is_visual", false)) { "此 MiniCPM MNN 包需要专用多模态适配，不能作为纯文本模型启动" }
        }
    }

    fun directorySize(directory: File): Long {
        require(directory.isDirectory) { "MNN 模型目录不存在" }
        return directory.walkTopDown()
            .filter(File::isFile)
            .sumOf(File::length)
    }

    /**
     * Hashes relative paths as well as bytes. Renaming one weight file therefore changes the model
     * identity even when every byte is preserved.
     */
    fun sha256(directory: File): String {
        require(directory.isDirectory) { "MNN 模型目录不存在" }
        val digest = MessageDigest.getInstance("SHA-256")
        val root = directory.canonicalFile
        root.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8)
                digest.update(path)
                digest.update(0)
                FileInputStream(file).buffered().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.update(0)
            }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
    }

    /**
     * Extracts a model archive into [destination] and returns the directory that actually contains
     * the MNN pair. The method rejects traversal, malformed paths and archives which exceed the
     * same 8 GiB safety ceiling used by single-file local models.
     */
    fun extractZip(
        input: InputStream,
        destination: File,
        maxBytes: Long,
    ): File {
        require(maxBytes > 0) { "解压空间上限无效" }
        destination.deleteRecursively()
        destination.mkdirs()
        val canonicalRoot = destination.canonicalFile
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ZIP_ENTRIES) { "MNN 压缩包文件数过多" }
                val normalized = entry.name.replace('\\', '/').removePrefix("./")
                require(normalized.isNotBlank()) { "MNN 压缩包包含空路径" }
                require(!normalized.startsWith('/')) { "MNN 压缩包包含绝对路径" }
                val segments = normalized.split('/')
                require(segments.none { it == ".." || it.isBlank() }) { "MNN 压缩包包含不安全路径" }
                val target = canonicalRoot.resolve(normalized).canonicalFile
                require(target.toPath().startsWith(canonicalRoot.toPath())) { "MNN 压缩包路径越界" }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= maxBytes) { "MNN 模型解压后超过 8 GiB 安全上限" }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        return findModelRoot(destination)
            ?: throw IllegalArgumentException(
                "MNN 压缩包不完整：需要 config.json、llm.mnn 及权重/tokenizer 文件",
            )
    }

    fun findModelRoot(root: File): File? {
        if (!root.isDirectory) return null
        if (isModelDirectory(root)) return root
        val candidates = root.walkTopDown()
            .maxDepth(MAX_SEARCH_DEPTH)
            .filter(File::isDirectory)
            .filter(::isModelDirectory)
            .take(2).toList()
        require(candidates.size <= 1) { "目录包含多个 MNN 模型，请分别导入" }
        return candidates.singleOrNull()
    }

    /** Copies a validated directory while enforcing a caller-provided byte ceiling. */
    fun copyDirectory(source: File, destination: File, maxBytes: Long): Long {
        require(isModelDirectory(source)) { "MNN 模型目录不完整" }
        destination.deleteRecursively()
        destination.mkdirs()
        val canonicalSource = source.canonicalFile
        val canonicalDestination = destination.canonicalFile
        var total = 0L
        canonicalSource.walkTopDown().forEach { file ->
            val relative = file.relativeTo(canonicalSource)
            val target = canonicalDestination.resolve(relative).canonicalFile
            require(target.toPath().startsWith(canonicalDestination.toPath())) { "MNN 模型目录包含越界路径" }
            if (file.isDirectory) {
                target.mkdirs()
            } else if (file.isFile) {
                target.parentFile?.mkdirs()
                FileInputStream(file).use { input ->
                    FileOutputStream(target).use { output -> copyLimited(input, output, maxBytes - total) }
                }
                total += file.length()
            }
        }
        require(isModelDirectory(destination)) { "MNN 模型目录复制后不完整" }
        return total
    }

    private fun copyLimited(input: InputStream, output: OutputStream, remaining: Long) {
        val buffer = ByteArray(256 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= remaining) { "MNN 模型超过 8 GiB 安全上限" }
            output.write(buffer, 0, read)
        }
    }

    private fun File.pathDepth(): Int = invariantSeparatorsPath.count { it == '/' }
}
