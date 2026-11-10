package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime-enforced tool boundary. A null allowlist is unrestricted; an empty allowlist
 * denies every tool. Entries may use a trailing `*` prefix match, and the denylist always wins.
 */
internal data class AgentToolPolicy(
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
) {
    private val allowed = allowedTools?.map(String::trim)?.toSet()
    private val denied = disallowedTools.map(String::trim).toSet()

    fun intersect(other: AgentToolPolicy): AgentToolPolicy {
        val next = when {
            allowedTools == null -> other.allowedTools
            other.allowedTools == null -> allowedTools
            else -> allowedTools.flatMap { left -> other.allowedTools.mapNotNull { right ->
                when {
                    left == "*" -> right
                    right == "*" -> left
                    left == right -> left
                    left.endsWith("*") && right.endsWith("*") -> when {
                        left.dropLast(1).startsWith(right.dropLast(1)) -> left
                        right.dropLast(1).startsWith(left.dropLast(1)) -> right
                        else -> null
                    }
                    left.endsWith("*") && matches(left, right) -> right
                    right.endsWith("*") && matches(right, left) -> left
                    else -> null
                }
            } }.distinct()
        }
        return AgentToolPolicy(next, (disallowedTools + other.disallowedTools).distinct())
    }

    /**
     * Combines capabilities contributed by independently active Skills. Parent and global
     * boundaries must still be intersected afterwards; denylists remain effective.
     */
    fun union(other: AgentToolPolicy): AgentToolPolicy {
        val next = when {
            allowedTools == null || other.allowedTools == null -> null
            else -> (allowedTools + other.allowedTools)
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
        }
        return AgentToolPolicy(next, (disallowedTools + other.disallowedTools).distinct())
    }

    fun isAllowed(toolName: String): Boolean {
        val name = toolName.trim()
        if (name.isEmpty() || denied.any { matches(it, name) }) return false
        val allow = allowed ?: return true
        return "*" in allow || name in allow || allow.any { pattern ->
            pattern.endsWith("*") && pattern.length > 1 && name.startsWith(pattern.dropLast(1))
        }
    }

    /** Discovery is a control plane for allowed domain tools, not a separate permission. */
    fun allowsToolDiscovery(controlTools: Set<String>): Boolean {
        if (allowed?.isEmpty() == true) return false
        return controlTools.none { name -> denied.any { matches(it, name) } }
    }

    fun filterSchemas(schemas: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until schemas.length()) {
            val schema = schemas.optJSONObject(index) ?: continue
            val name = schema.optJSONObject("function")?.optString("name").orEmpty()
            if (isAllowed(name)) filtered.put(JSONObject(schema.toString()))
        }
    }

    companion object {
        private fun matches(pattern: String, name: String): Boolean = pattern == "*" || pattern == name ||
            pattern.endsWith("*") && pattern.length > 1 && name.startsWith(pattern.dropLast(1))
        val UNRESTRICTED = AgentToolPolicy()
    }
}
