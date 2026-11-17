package io.github.mangi.eta.agent.skill

/** Offsets count UTF-16 code units, like String.length; a page never splits a surrogate pair. */
internal data class SkillTextPage(val text: String, val offset: Int, val nextOffset: Int?, val totalChars: Int) {
    val truncated: Boolean get() = nextOffset != null

    companion object {
        fun read(text: String, offset: Int, maxChars: Int): SkillTextPage {
            require(offset in 0..text.length && maxChars >= 2) { "offset 超出文本范围或页大小无效" }
            require(offset == 0 || offset == text.length ||
                !(text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate())) { "offset 位于 Unicode 字符中间，请使用 next_offset" }
            var end = minOf(text.length.toLong(), offset.toLong() + maxChars).toInt()
            if (end > offset && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end -= 1
            return SkillTextPage(text.substring(offset, end), offset, end.takeIf { it < text.length }, text.length)
        }
    }
}
