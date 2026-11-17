package io.github.mangi.eta.agent.model

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/** Reads metadata only, never tensors. Bounds apply before allocating or seeking untrusted data. */
internal data class GgufModelMetadata(
    val architecture: String,
    val name: String,
    val tokenizer: String,
    val chatTemplate: String,
    val splitCount: Long,
    val vocabularySize: Long = 0,
) {
    val isMiniCpm: Boolean get() = (architecture + " " + name).lowercase(Locale.ROOT).contains("minicpm")

    fun validateTextRuntime() {
        require(architecture.isNotBlank()) { "GGUF 缺少模型架构信息" }
        require(splitCount <= 1) { "分片 GGUF 需要先合并为完整模型文件" }
        require(architecture != "clip") { "这是视觉投影文件，请导入完整的文本模型 GGUF" }
        if (!isMiniCpm) return
        // The shipped JNI exposes text generation only. mtmd symbols in the library do not
        // provide image/audio inputs through our frozen JNI surface.
        val identity = (name + " " + architecture).lowercase(Locale.ROOT)
        require(!Regex("minicpm[-_ ]?(?:v|o|asr)(?:[-_ .0-9]|$)").containsMatchIn(identity)) {
            "内置 MiniCPM 适配仅支持纯文本 GGUF；MiniCPM-V、MiniCPM-o 和 MiniCPM-ASR 需要专用多模态引擎"
        }
        require(architecture in setOf("minicpm", "minicpm3", "llama")) { "当前内置引擎不支持此 MiniCPM 架构：$architecture" }
        require(tokenizer.isNotBlank() && vocabularySize > 0 && chatTemplate.isNotBlank()) {
            "MiniCPM GGUF 必须内嵌 tokenizer 和 chat_template；请使用完整的官方兼容转换文件"
        }
    }

    companion object {
        private const val MAX_METADATA_BYTES = 128L * 1024 * 1024
        private const val MAX_STRING_BYTES = 16L * 1024 * 1024
        private const val MAX_ARRAY_ITEMS = 2_000_000L

        fun inspect(file: File): GgufModelMetadata = RandomAccessFile(file, "r").use { input ->
            val limit = minOf(input.length(), MAX_METADATA_BYTES)
            fun requireBytes(size: Long) { require(size >= 0 && size <= limit - input.filePointer) { "GGUF 元数据不完整或超出安全上限" } }
            fun u32(): Long { requireBytes(4); return Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL }
            fun u64(): Long { requireBytes(8); return java.lang.Long.reverseBytes(input.readLong()).also { require(it >= 0) { "GGUF 长度超出安全上限" } } }
            fun skip(size: Long) { requireBytes(size); input.seek(input.filePointer + size) }
            fun string(retain: Boolean): String {
                val size = u64()
                require(size <= MAX_STRING_BYTES) { "GGUF 字符串过长" }
                requireBytes(size)
                if (!retain) { skip(size); return "" }
                return ByteArray(size.toInt()).also(input::readFully).toString(Charsets.UTF_8)
            }
            fun scalarSize(type: Long): Long = when (type) { 0L, 1L, 7L -> 1; 2L, 3L -> 2; 4L, 5L, 6L -> 4; 10L, 11L, 12L -> 8; else -> error("GGUF 元数据类型不支持：$type") }
            fun skipValue(type: Long) {
                when (type) {
                    8L -> string(false)
                    9L -> {
                        val element = u32(); val count = u64()
                        require(count <= MAX_ARRAY_ITEMS && element != 9L) { "GGUF 元数据数组无效" }
                        if (element == 8L) repeat(count.toInt()) { string(false) }
                        else skip(Math.multiplyExact(count, scalarSize(element)))
                    }
                    else -> skip(scalarSize(type))
                }
            }
            requireBytes(4)
            require(input.readInt() == 0x47475546) { "GGUF 文件头无效" }
            require(u32() in 2L..3L) { "仅支持 GGUF v2/v3" }
            val tensors = u64(); val count = u64()
            require(tensors > 0 && count in 1L..100_000L) { "GGUF 缺少模型张量或元数据" }
            val values = mutableMapOf<String, String>()
            var splitCount = 1L
            var vocabularySize = 0L
            repeat(count.toInt()) {
                val key = string(true)
                val type = u32()
                if (key in setOf("general.architecture", "general.name", "tokenizer.ggml.model", "tokenizer.chat_template") && type == 8L) values[key] = string(true)
                else if (key == "tokenizer.ggml.tokens" && type == 9L) {
                    val element = u32(); val tokens = u64()
                    require(element == 8L && tokens in 1L..MAX_ARRAY_ITEMS) { "GGUF 词表无效" }
                    repeat(tokens.toInt()) { string(false) }
                    vocabularySize = tokens
                } else if (key == "split.count" && type in setOf(2L, 4L)) {
                    splitCount = if (type == 2L) { requireBytes(2); java.lang.Short.reverseBytes(input.readShort()).toLong() and 0xffffL } else u32()
                } else skipValue(type)
            }
            GgufModelMetadata(values["general.architecture"].orEmpty(), values["general.name"].orEmpty(),
                values["tokenizer.ggml.model"].orEmpty(), values["tokenizer.chat_template"].orEmpty(), splitCount, vocabularySize)
        }
    }
}
