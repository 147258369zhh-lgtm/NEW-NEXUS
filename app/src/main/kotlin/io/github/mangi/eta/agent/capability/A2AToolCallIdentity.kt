package io.github.mangi.eta.agent.capability

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Stable, non-secret identity of a tool call: canonical arguments are hashed, never stored.
 * The same helper is used when an approval pause is created, when a one-shot approval is
 * consumed and when already-settled calls are blocked, so all three compare the same bytes.
 */
internal object A2AToolCallIdentity {
    const val DIGEST_CHARS = 64
    private const val MAX_ARGUMENTS_CHARS = 32_000

    fun canonicalArgumentsDigest(argumentsJson: String): String {
        val text = argumentsJson.ifBlank { "{}" }
        val value = runCatching { JSONTokener(text).nextValue() }.getOrNull() ?: return ""
        return digestIfBounded(value)
    }

    fun canonicalArgumentsDigest(arguments: JSONObject): String = digestIfBounded(arguments)

    /** Bound the canonical representation, so formatting and escaping cannot split identities. */
    private fun digestIfBounded(value: Any?): String {
        val canonical = canonical(value)
        return if (canonical.length > MAX_ARGUMENTS_CHARS) "" else sha256(canonical)
    }

    /** Tool, effect and argument digest joined into one comparable, non-secret string. */
    fun settledKey(tool: String, effect: String, argumentsDigest: String): String =
        if (tool.isBlank() || effect.isBlank() || argumentsDigest.length != DIGEST_CHARS) ""
        else "$tool|$effect|$argumentsDigest"

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            quote(key) + ":" + canonical(if (value.has(key)) value.opt(key) else null)
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]", separator = ",") {
            canonical(value.opt(it))
        }
        is Number, is Boolean -> value.toString()
        is String -> quote(value)
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = JSONObject.quote(value)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
