package io.github.mangi.eta.agent.voice

import android.icu.text.Transliterator
import java.io.File
import java.text.Normalizer

/** Converts user phrases into the downloaded KWS vocabulary, rather than shipping fixed words. */
internal object WakeKeywordCompiler {
    fun compile(words: List<String>, tokenFile: File, transliterate: (String) -> String = {
        Transliterator.getInstance("Han-Latin").transliterate(it)
    }): String {
        require(tokenFile.isFile && tokenFile.length() <= 2 * 1024 * 1024) { "唤醒词表缺失或过大" }
        val tokens = tokenFile.useLines { lines -> lines.map { it.substringBeforeLast(' ').trim() }.filter {
            it.isNotBlank() && !it.startsWith('<')
        }.toSet() }
        require(words.isNotEmpty() && words.size <= 8)
        return words.map { word ->
            require(word.length in 2..64 && !word.contains('\n') && !word.contains('@')) { "唤醒词需为 2 至 64 个文字或字母" }
            val romanized = Normalizer.normalize(transliterate(word), Normalizer.Form.NFC)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val sequence = romanized.split(' ').filter(String::isNotBlank).flatMap { syllable ->
                tokenize(syllable, tokens) ?: tokenize(syllable.uppercase(java.util.Locale.ROOT), tokens)
                ?: tokenize(syllable.lowercase(java.util.Locale.ROOT), tokens)
                ?: error("模型词表无法编码“$word”，请选择匹配语言的唤醒模型")
            }
            require(sequence.isNotEmpty()) { "唤醒词不可为空" }
            sequence.joinToString(" ") + " @$word"
        }.joinToString("\n")
    }

    private fun tokenize(text: String, vocabulary: Set<String>): List<String>? {
        val result = arrayOfNulls<List<String>>(text.length + 1)
        result[text.length] = emptyList()
        for (index in text.length - 1 downTo 0) {
            for (end in text.length downTo index + 1) {
                val token = text.substring(index, end)
                val rest = result[end]
                if (token in vocabulary && rest != null) { result[index] = listOf(token) + rest; break }
            }
        }
        return result[0]
    }
}
