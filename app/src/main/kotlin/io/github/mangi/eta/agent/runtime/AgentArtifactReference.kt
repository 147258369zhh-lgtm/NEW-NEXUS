package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Only a local capture receipt may create these references; raw provider paths are never trusted. */
internal data class AgentArtifactReference(val id: String, val name: String, val mediaType: String? = null) {
    companion object {
        fun encode(items: List<AgentArtifactReference>): JSONArray = JSONArray().apply {
            items.distinctBy { it.id }.take(8).forEach { item ->
                put(JSONObject().put("id", item.id).put("name", item.name.take(255)).put("media_type", item.mediaType))
            }
        }

        fun decode(raw: String?): List<AgentArtifactReference> = runCatching {
            decode(raw?.let(::JSONArray))
        }.getOrDefault(emptyList())

        fun decode(items: JSONArray?): List<AgentArtifactReference> = buildList {
            if (items == null) return@buildList
            for (index in 0 until minOf(items.length(), 8)) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) } ?: continue
                add(AgentArtifactReference(id, item.optString("name").take(255).ifBlank { "文件" },
                    item.optString("media_type").takeIf { !item.isNull("media_type") && it.isNotBlank() }?.take(128)))
            }
        }.distinctBy { it.id }
    }
}
