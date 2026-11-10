package io.github.mangi.eta.agent.mcp

import org.json.JSONArray

/** Renders remote content as inert text, with one UTF-8 budget for separators and payload. */
internal object McpContentPreview {
    fun fromResources(contents: JSONArray, maxBytes: Int): McpHttpClient.ContentPreview {
        val preview = Builder(maxBytes)
        for (index in 0 until contents.length()) {
            val item = contents.optJSONObject(index)
            val text = item?.opt("text") as? String
            if (text != null) preview.append(text) else preview.omit()
        }
        return preview.build()
    }

    fun fromPrompt(messages: JSONArray, maxBytes: Int): McpHttpClient.ContentPreview {
        val preview = Builder(maxBytes)
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index)
            val content = item?.optJSONObject("content")
            if (content?.optString("type") == "text" && content.opt("text") is String) {
                preview.append("[${item.optString("role").take(32)}]\n${content.getString("text")}")
            } else preview.omit()
        }
        return preview.build()
    }

    private class Builder(val maxBytes: Int) {
        private val text = StringBuilder()
        private var bytes = 0
        private var truncated = false
        private var omitted = 0

        init { require(maxBytes >= 0) }

        fun append(value: String) {
            if (truncated) return
            val separator = if (text.isEmpty()) "" else "\n\n"
            val candidate = separator + value
            var index = 0
            while (index < candidate.length) {
                val point = candidate.codePointAt(index)
                val size = when {
                    point <= 0x7f -> 1
                    point <= 0x7ff -> 2
                    point <= 0xffff -> 3
                    else -> 4
                }
                if (bytes + size > maxBytes) {
                    truncated = true
                    break
                }
                text.appendCodePoint(point)
                bytes += size
                index += Character.charCount(point)
            }
        }

        fun omit() { omitted++ }

        fun build() = McpHttpClient.ContentPreview(text.toString(), truncated, omitted)
    }
}
