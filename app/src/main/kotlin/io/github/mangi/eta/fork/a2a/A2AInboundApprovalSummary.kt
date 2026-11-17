package io.github.mangi.eta.fork.a2a

import io.github.mangi.eta.agent.capability.A2AToolCallIdentity
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Bounded, local-only resume ledger for one owner-approval pause.
 *
 * It never contains transcript text, tool results or argument values: only the exact pending
 * call identity (id, tool, effect, canonical argument digest) and the digests of calls that had
 * already settled. A missing, truncated, oversized or unverifiable summary forbids the approval.
 */
@Serializable
internal data class A2AInboundApprovalSummary(
    /** Local approval slot of the pause; a later pause uses a new slot and new summary. */
    val slot: String,
    val callId: String,
    val tool: String,
    val effect: String,
    val argumentsDigest: String,
    /** tool|effect|argumentsDigest of calls that already settled in the paused turn. */
    val settledCalls: List<String> = emptyList(),
) {
    /** True when a settled call had a non-read effect; such a turn is never auto-resumed. */
    val effectfulSettled: Boolean
        get() = settledCalls.any { !isReadKey(it) }

    /** Stable one-shot grant identity. */
    val grantKey: String get() = A2AToolCallIdentity.settledKey(tool, effect, argumentsDigest)

    companion object {
        const val MAX_CHARS = 8_192
        const val MAX_SETTLED_CALLS = 32
        private val toolPattern = Regex("[A-Za-z0-9._:-]{1,128}")
        private val digestPattern = Regex("[0-9a-f]{64}")

        /**
         * Builds the summary from a pause that explicitly marked an approval-pending call.
         * Returns null when the runtime did not prove that the call was not executed.
         */
        fun fromPause(slot: String, pause: AgentRuntimeWire.RunPause?): A2AInboundApprovalSummary? {
            if (slice(slot).isEmpty() || pause == null) return null
            // The pause must say the call never ran. A run stopped by an unknown in-flight effect
            // has no such marker and can only be verified manually.
            if (pause.pendingExecuted || pause.pendingCallId.isBlank()) return null
            val tool = pause.pendingTool.trim()
            val effect = pause.pendingEffect.trim()
            val argumentsDigest = pause.pendingArgumentsDigest.trim().lowercase()
            if (!toolPattern.matches(tool)) return null
            if (EffectClass.entries.none { it.name == effect }) return null
            if (!digestPattern.matches(argumentsDigest)) return null
            val settled = pause.settledCalls.map(String::trim).filter(String::isNotEmpty).distinct()
            if (settled.size > MAX_SETTLED_CALLS) return null
            if (settled.any { !isSettledKey(it) }) return null
            val summary = A2AInboundApprovalSummary(
                slot = slice(slot),
                callId = slice(pause.pendingCallId).ifEmpty { return null },
                tool = tool,
                effect = effect,
                argumentsDigest = argumentsDigest,
                settledCalls = settled,
            )
            // A turn that already produced a non-read effect cannot be resumed automatically:
            // its exact result is not in this local ledger, so the owner must verify it manually.
            if (summary.effectfulSettled || pause.settledEffectful) return null
            return summary.takeIf { it.encoded().json.length <= MAX_CHARS }
        }

        /** Returns null for missing, damaged, oversized or foreign-slot summaries. */
        fun decode(json: String?, digest: String?, chars: Int, expectedSlot: String): A2AInboundApprovalSummary? =
            decode(json, digest, chars, setOf(expectedSlot))

        fun decode(
            json: String?,
            digest: String?,
            chars: Int,
            expectedSlots: Set<String>,
        ): A2AInboundApprovalSummary? {
            if (json.isNullOrBlank() || digest.isNullOrBlank()) return null
            if (chars <= 0 || json.length != chars || json.length > MAX_CHARS) return null
            if (sha256(json) != digest) return null
            val summary = runCatching { A2A_JSON.decodeFromString<A2AInboundApprovalSummary>(json) }.getOrNull()
                ?: return null
            val again = summary.encoded()
            if (again.json != json || again.digest != digest) return null
            val slots = expectedSlots.map(::slice).filter(String::isNotEmpty).toSet()
            if (slots.isEmpty() || summary.slot !in slots) return null
            if (!toolPattern.matches(summary.tool)) return null
            if (EffectClass.entries.none { it.name == summary.effect }) return null
            if (!digestPattern.matches(summary.argumentsDigest.lowercase())) return null
            if (summary.settledCalls.size > MAX_SETTLED_CALLS) return null
            if (summary.settledCalls.any { !isSettledKey(it) }) return null
            return summary
        }

        fun isSettledKey(value: String): Boolean {
            val parts = value.split('|')
            if (parts.size != 3) return false
            if (!toolPattern.matches(parts[0])) return false
            if (EffectClass.entries.none { it.name == parts[1] }) return false
            return digestPattern.matches(parts[2].lowercase())
        }

        private fun isReadKey(value: String): Boolean =
            value.split('|').getOrNull(1) == EffectClass.READ.name

        private fun slice(value: String): String = value.trim().take(128)

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun encoded(): Stored {
        val json = A2A_JSON.encodeToString(this)
        return Stored(json = json, digest = sha256(json), chars = json.length)
    }

    data class Stored(val json: String, val digest: String, val chars: Int)
}
