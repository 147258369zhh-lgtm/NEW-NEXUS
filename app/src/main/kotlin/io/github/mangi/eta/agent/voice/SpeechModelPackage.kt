package io.github.mangi.eta.agent.voice

import java.io.File
import java.io.InputStream
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import org.json.JSONObject
import io.github.mangi.eta.agent.model.mnn.MnnModelPackage

internal data class SpeechModelSpec(val root: File, val architecture: String, val kind: LocalSpeechKind,
    val files: Map<String, String>, val language: String = "", val voices: List<String> = emptyList()) {
    fun path(key: String): String = files[key].orEmpty()
}

/** Uses full upstream model bundles; a lone ONNX graph cannot replace its tokenizer/lexicon. */
internal object SpeechModelPackage {
    private val required = mapOf(
        "zipformer-streaming" to listOf("encoder", "decoder", "joiner", "tokens"),
        "paraformer-streaming" to listOf("encoder", "decoder", "tokens"),
        "whisper" to listOf("encoder", "decoder", "tokens"),
        "sensevoice" to listOf("model", "tokens"),
        "paraformer" to listOf("model", "tokens"),
        "vits" to listOf("model", "tokens", "lexicon"),
        "kokoro" to listOf("model", "voices", "tokens", "lexicon"),
        "matcha" to listOf("model", "vocoder", "tokens", "lexicon"),
        "keyword-spotter" to listOf("encoder", "decoder", "joiner", "tokens"),
        "speaker-embedding" to listOf("model"),
    )
    private val synthesis = setOf("vits", "kokoro", "matcha")
    fun isArchive(name: String): Boolean = listOf(".zip", ".tar.bz2", ".tar.gz", ".tar.xz", ".tgz").any { name.lowercase().endsWith(it) }

    fun inspect(directory: File): SpeechModelSpec? {
        if (!directory.isDirectory) return null
        val manifest = directory.resolve("nexus-speech.json")
        if (manifest.isFile) {
            require(manifest.length() <= 64 * 1024) { "语音模型清单过大" }
            val json = JSONObject(manifest.readText())
            require(json.getInt("version") == 1) { "语音模型清单版本不支持" }
            val architecture = json.getString("architecture")
            require(architecture in required) { "不支持的语音模型架构：$architecture" }
            val files = json.getJSONObject("files")
            val resolved = files.keys().asSequence().associateWith { key ->
                if (key in setOf("lexicon", "rule_fsts", "rule_fars")) files.getString(key).split(',').joinToString(",") { safePath(directory, it) }
                else safePath(directory, files.getString(key))
            }
            required.getValue(architecture).forEach { require(resolved[it]?.split(',')?.all { path -> File(path).isFile } == true) { "语音模型缺少 $it" } }
            listOf("data_dir", "dict_dir").forEach { key ->
                resolved[key]?.let { require(File(it).isDirectory) { "语音模型目录无效：$key" } }
            }
            val voices = json.optJSONArray("voice_names")?.let { array -> (0 until minOf(array.length(), 1024)).map { array.getString(it).take(80) } }.orEmpty()
            return SpeechModelSpec(directory, architecture, if (architecture in synthesis) LocalSpeechKind.TTS else LocalSpeechKind.ASR,
                resolved, json.optString("language").take(32), voices)
        }
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        fun pick(predicate: (String) -> Boolean) = files.filter { predicate(it.name.lowercase()) }
            .sortedWith(compareBy<File> { !it.name.contains("int8") }.thenBy { it.name }).firstOrNull()?.absolutePath
        val tokens = pick { it.endsWith("tokens.txt") } ?: return null
        val name = directory.name.lowercase()
        val encoder = pick { it.contains("encoder") && !it.contains("decoder") && it.endsWith(".onnx") }
        val decoder = pick { it.contains("decoder") && it.endsWith(".onnx") }
        val joiner = pick { it.startsWith("joiner") && it.endsWith(".onnx") }
        val model = pick { it.endsWith(".onnx") && !it.contains("vocoder") && !it.startsWith("encoder") && !it.startsWith("decoder") && !it.startsWith("joiner") }
        val lexicons = files.filter { it.name.startsWith("lexicon") && it.extension == "txt" }.sortedBy { it.name }
        val architecture = when {
            encoder != null && decoder != null && joiner != null && "kws" in name -> "keyword-spotter"
            encoder != null && decoder != null && joiner != null -> "zipformer-streaming".takeIf { "streaming" in name }
            encoder != null && decoder != null && "whisper" in name -> "whisper"
            encoder != null && decoder != null && "paraformer" in name && "streaming" in name -> "paraformer-streaming"
            model != null && "sense" in name -> "sensevoice"
            model != null && "paraformer" in name -> "paraformer"
            model != null && lexicons.isNotEmpty() && "kokoro" in name -> "kokoro"
            model != null && lexicons.isNotEmpty() && ("vits" in name || "piper" in name) -> "vits"
            else -> null
        } ?: return null
        val resolved = mapOf("model" to model, "encoder" to encoder, "decoder" to decoder, "joiner" to joiner,
            "tokens" to tokens, "voices" to pick { it.startsWith("voices") && it.endsWith(".bin") },
            "data_dir" to directory.resolve("espeak-ng-data").takeIf(File::isDirectory)?.absolutePath,
            "dict_dir" to directory.resolve("dict").takeIf(File::isDirectory)?.absolutePath,
            "lexicon" to lexicons.joinToString(",") { it.absolutePath },
            "rule_fsts" to listOf("date.fst", "number.fst", "phone.fst").mapNotNull { name -> files.firstOrNull { it.name == name } }.joinToString(",") { it.absolutePath },
            "rule_fars" to files.filter { it.extension == "far" }.sortedBy { it.name }.joinToString(",") { it.absolutePath })
            .filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
        required.getValue(architecture).forEach { if (resolved[it].isNullOrBlank()) return null }
        val speakerFile = directory.resolve("speakers.txt")
        val speakerNames = if (speakerFile.isFile && speakerFile.length() <= 256 * 1024) speakerFile.readLines().take(1024).mapIndexedNotNull { index, line ->
            val parts = line.trim().split(Regex("\\s+"))
            val explicit = parts.lastOrNull()?.toIntOrNull()?.takeIf { it in 0..1023 }
            if (explicit != null) explicit to parts.dropLast(1).joinToString(" ").take(80)
            else line.trim().takeIf(String::isNotBlank)?.let { index to it.take(80) }
        }.toMap() else emptyMap()
        val names = (0..(speakerNames.keys.maxOrNull() ?: -1)).map { speakerNames[it] ?: "音色 ${it + 1}" }
        return SpeechModelSpec(directory, architecture, if (architecture in synthesis) LocalSpeechKind.TTS else LocalSpeechKind.ASR, resolved, voices = names)
    }

    fun findRoot(root: File): SpeechModelSpec? {
        val candidates = root.walkTopDown().maxDepth(4).filter(File::isDirectory).mapNotNull(::inspect).take(2).toList()
        require(candidates.size <= 1) { "目录包含多个语音模型，请分别导入" }
        return candidates.singleOrNull()
    }

    fun extract(input: InputStream, name: String, target: File, maxBytes: Long, checkActive: () -> Unit = {}): File {
        require(maxBytes > 0 && !target.exists()) { "语音模型暂存目录不可用" }
        check(target.mkdirs())
        val buffered = input.buffered()
        val archive: ArchiveInputStream<*> = if (name.lowercase().endsWith(".zip")) ZipArchiveInputStream(buffered)
            else TarArchiveInputStream(CompressorStreamFactory().createCompressorInputStream(buffered))
        var total = 0L; var entries = 0
        val seen = mutableSetOf<String>()
        archive.use {
            while (true) {
                checkActive()
                val entry = archive.nextEntry ?: break
                require(++entries <= 10_000 && archive.canReadEntryData(entry)) { "语音模型压缩包项目过多或不受支持" }
                if (entry is TarArchiveEntry) require(!entry.isSymbolicLink && !entry.isLink && (entry.isDirectory || entry.isFile)) { "语音模型不允许链接或设备文件" }
                if (entry is org.apache.commons.compress.archivers.zip.ZipArchiveEntry) require(!entry.isUnixSymlink) { "语音模型不允许软链接" }
                val relative = entry.name.removePrefix("./").trimEnd('/')
                if (relative.isEmpty() && entry.isDirectory) continue
                val output = File(safePath(target, relative))
                require(seen.add(relative)) { "语音模型压缩包包含重复路径" }
                if (entry.isDirectory) { check(output.mkdirs() || output.isDirectory); continue }
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                output.outputStream().buffered().use { sink ->
                    val bytes = ByteArray(256 * 1024)
                    while (true) {
                        checkActive()
                        val count = archive.read(bytes); if (count < 0) break
                        require(count.toLong() <= maxBytes - total) { "语音模型解压后超过可用空间" }
                        sink.write(bytes, 0, count); total += count
                    }
                }
            }
        }
        return findRoot(target)?.root ?: MnnModelPackage.findModelRoot(target)
            ?: error("模型包不完整：语音包需要完整权重与词表，MNN 需要 config.json 和 llm.mnn")
    }

    fun directoryHash(root: File) = MnnModelPackage.sha256(root)
    fun directorySize(root: File) = MnnModelPackage.directorySize(root)

    private fun safePath(root: File, relative: String): String {
        require(relative.isNotBlank() && !relative.contains('\\') && !relative.contains(':') && !relative.startsWith('/') &&
            relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "语音模型包含不安全路径" }
        val target = root.resolve(relative).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) { "语音模型路径越界" }
        return target.absolutePath
    }
}
