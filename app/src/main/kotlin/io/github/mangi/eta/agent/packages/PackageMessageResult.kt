package io.github.mangi.eta.agent.packages

import io.github.mangi.eta.agent.model.AgentExtensionCompletion
import org.json.JSONObject

internal object PackageMessageResult {
    fun matched(value: Any?): Boolean = when (value) {
        null, false -> false
        true -> true
        is String -> value.isNotBlank()
        is JSONObject -> if (value.has("matched")) value.getBoolean("matched") else value.has("text") || value.has("content") || value.has("chunks")
        else -> error("PACKAGE_MESSAGE_RESULT_INVALID")
    }

    fun completion(value: Any?): AgentExtensionCompletion {
        check(matched(value)) { "PACKAGE_MESSAGE_MATCH_CHANGED" }
        val chunks = when (value) {
            is String -> listOf(value)
            is JSONObject -> value.optJSONArray("chunks")?.let { array ->
                require(array.length() <= 1024) { "PACKAGE_MESSAGE_CHUNK_LIMIT" }
                (0 until array.length()).map(array::getString)
            } ?: listOf((value.opt("text") as? String) ?: (value.opt("content") as? String) ?: error("PACKAGE_MESSAGE_TEXT_REQUIRED"))
            else -> error("PACKAGE_MESSAGE_TEXT_REQUIRED")
        }
        require(chunks.sumOf { it.length.toLong() } in 1..256_000 && chunks.any(String::isNotBlank)) { "PACKAGE_MESSAGE_OUTPUT_LIMIT" }
        return AgentExtensionCompletion(chunks)
    }
}
