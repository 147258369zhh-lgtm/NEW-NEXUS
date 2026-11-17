package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import java.time.LocalDate
import org.json.JSONObject

/** Resolution input is the same frozen role card used by the actual model request. */
internal data class PromptMacroContext(
    val card: CharacterCard? = null,
    val userName: String = "用户",
    val userDescription: String = "",
) {
    fun expand(text: String, model: String, original: String): String {
        val common = text.replace(Regex("\\{\\{\\s*model\\s*\\}\\}", RegexOption.IGNORE_CASE)) { model }
        return if (card != null) CharacterMacros.expand(common, card, userName, userDescription, original)
        else common
            .replace(Regex("\\{\\{\\s*user\\s*\\}\\}|<USER>", RegexOption.IGNORE_CASE)) { userName }
            .replace(Regex("\\{\\{\\s*date\\s*\\}\\}", RegexOption.IGNORE_CASE)) { LocalDate.now().toString() }
            .replace(Regex("\\{\\{\\s*original\\s*\\}\\}", RegexOption.IGNORE_CASE)) { original }
    }
}

internal data class PromptFragmentTrace(
    val templateId: String,
    val templateName: String,
    val blockId: String,
    val title: String,
    val source: String,
    val scope: String,
    val position: PromptBlockPosition,
    val inheritance: PromptInheritance,
    val status: String,
    val content: String = "",
    val sourceCharacters: Int = content.length,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("template_id", templateId).put("template", templateName).put("block_id", blockId)
        .put("title", title).put("source", source).put("scope", scope).put("position", position.wireValue)
        .put("inheritance", inheritance.name).put("status", status).put("content", content)
        .put("source_chars", sourceCharacters)
}
