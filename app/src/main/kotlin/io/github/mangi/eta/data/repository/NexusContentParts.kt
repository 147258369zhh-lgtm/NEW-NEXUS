package io.github.mangi.eta.data.repository

import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject

/** Portable structured bodies stored with the existing conversation message, not a second history. */
internal object NexusContentParts {
    fun text(messageId: String, content: String, markdown: Boolean = true): List<ContentPart> =
        if (content.isBlank()) emptyList() else listOf(if (markdown) MarkdownPart("$messageId:body", content) else TextPart("$messageId:body", content))

    fun encode(parts: List<ContentPart>, fallback: String = "", pending: Boolean = false): String = if (parts.isEmpty() && !pending) "[]" else JSONObject().put("format", "nexus-content-parts")
        .put("version", 1).apply { if (pending) put("delivery_pending", true) }.put("parts", JSONArray().also { result -> parts.forEach { part ->
            val row = JSONObject().put("id", part.partId)
            when (part) {
                is TextPart -> row.put("type", "text").putText(part.text, fallback)
                is MarkdownPart -> row.put("type", "markdown").putText(part.markdown, fallback)
                is CodePart -> row.put("type", "code").put("code", part.code).put("language", part.language)
                is MathPart -> row.put("type", "math").put("latex", part.latex)
                is ImagePart -> row.put("type", "image").put("artifact", part.artifactId.value).put("mime", part.mimeType)
                is AudioPart -> row.put("type", "audio").put("artifact", part.artifactId.value).put("mime", part.mimeType).put("duration", part.durationMs)
                is VideoPart -> row.put("type", "video").put("artifact", part.artifactId.value).put("mime", part.mimeType)
                    .put("duration", part.durationMs).put("width", part.width).put("height", part.height)
                is FilePart -> row.put("type", "file").put("artifact", part.artifactId.value).put("name", part.displayName)
                is ToolTracePart -> row.put("type", "tool").put("item", part.itemId.value)
                is PlanCardPart -> row.put("type", "plan").put("plan", part.planId.value).put("item", part.executionItemId?.value)
                is UserInputPart -> row.put("type", "input").put("request", part.requestId).put("question", part.question)
                    .put("options", JSONArray(part.options)).put("free_text", part.allowFreeText)
                    .put("questions", io.github.mangi.eta.agent.runtime.RunInputForms.encode(part.questions))
                    .put("deadline_at", part.deadlineAtMillis)
                is ApprovalPart -> row.put("type", "approval").put("approval", part.approvalId)
            }
            result.put(row)
        } }).toString()

    fun decode(raw: String, messageId: String, fallback: String, markdown: Boolean = true): List<ContentPart> {
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            check(root.getString("format") == "nexus-content-parts" && root.getInt("version") == 1)
            val parts = root.getJSONArray("parts")
            if (parts.length() == 0 && root.optBoolean("delivery_pending")) return emptyList()
            require(parts.length() <= 1024)
            val ids = hashSetOf<String>()
            buildList {
                for (i in 0 until parts.length()) {
                    val row = parts.getJSONObject(i)
                    val id = row.getString("id")
                    check(id.isNotBlank() && ids.add(id))
                    val content = if (row.optBoolean("message_content")) fallback else row.optString("text")
                    val part: ContentPart? = when (row.getString("type")) {
                        "text" -> TextPart(id, content)
                        "markdown" -> MarkdownPart(id, content)
                        "code" -> CodePart(id, row.optString("language").takeIf(String::isNotBlank), row.getString("code"))
                        "math" -> MathPart(id, row.getString("latex"))
                        "image" -> ImagePart(id, ArtifactId(row.getString("artifact")), row.getString("mime"))
                        "audio" -> AudioPart(id, ArtifactId(row.getString("artifact")), row.getString("mime"),
                            if (row.isNull("duration")) null else row.getLong("duration"))
                        "video" -> VideoPart(id, ArtifactId(row.getString("artifact")), row.getString("mime"),
                            if (row.isNull("duration")) null else row.getLong("duration"),
                            if (row.isNull("width")) null else row.optInt("width").takeIf { it > 0 },
                            if (row.isNull("height")) null else row.optInt("height").takeIf { it > 0 })
                        "file" -> FilePart(id, ArtifactId(row.getString("artifact")), row.getString("name"))
                        "tool" -> ToolTracePart(id, ItemId(row.getString("item")))
                        "plan" -> PlanCardPart(id, PlanId(row.getString("plan")), row.optString("item").takeIf(String::isNotBlank)?.let(::ItemId))
                        "input" -> row.getJSONArray("options").let { options -> UserInputPart(id, row.getString("request"),
                            row.getString("question"), (0 until options.length()).map(options::getString), row.getBoolean("free_text"),
                            io.github.mangi.eta.agent.runtime.RunInputForms.parse(row),
                            row.optLong("deadline_at", 0).takeIf { it > 0L }) }
                        "approval" -> ApprovalPart(id, row.getString("approval"))
                        else -> null
                    }
                    part?.let(::add)
                }
            }.ifEmpty { text(messageId, fallback, markdown) }
        }.getOrElse { text(messageId, fallback, markdown) }
    }

    private fun JSONObject.putText(text: String, fallback: String): JSONObject =
        if (text == fallback) put("message_content", true) else put("text", text)
}
