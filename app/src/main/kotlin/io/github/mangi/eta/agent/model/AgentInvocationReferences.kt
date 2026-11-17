package io.github.mangi.eta.agent.model

import android.net.Uri

/** Stable invocation references survive draft persistence and message forwarding. They never grant permission. */
internal object AgentInvocationReferences {
    data class Reference(val kind: String, val id: String?, val label: String, val start: Int,
        val end: Int, val display: String) {
        val isSkill: Boolean get() = kind == "Skill"
    }
    private val pattern = Regex("@(MCP|Skill)\\[([^\\]\\r\\n]+)](?:\\((nexa-(?:mcp|skill):[^)\\s]+)\\))?")
    fun token(kind: String, id: String, label: String): String {
        require(kind == "MCP" || kind == "Skill")
        val safeLabel = label.replace(Regex("[\\[\\]\\r\\n]"), " ").trim().take(100)
        val scheme = if (kind == "MCP") "nexa-mcp" else "nexa-skill"
        return "@$kind[$safeLabel]($scheme:${Uri.encode(id)})"
    }
    fun parse(text: CharSequence): List<Reference> = pattern.findAll(text).map { match ->
        val kind = match.groupValues[1]
        val uri = match.groupValues[3]
        val expected = if (kind == "MCP") "nexa-mcp:" else "nexa-skill:"
        val id = uri.takeIf { it.startsWith(expected) }?.removePrefix(expected)?.let(Uri::decode)
        Reference(kind, id, match.groupValues[2], match.range.first, match.range.last + 1,
            "@$kind[${match.groupValues[2]}]")
    }.toList()
}

