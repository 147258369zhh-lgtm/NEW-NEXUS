package io.github.mangi.eta.fork.learning

import java.security.MessageDigest
import io.github.mangi.eta.agent.memory.MemoryAutomationPolicy

internal enum class LearningKind(val wireValue: String) {
    FACT("fact"),
    PREFERENCE("preference"),
    PROCEDURE("procedure"),
    FAILURE_FIX("failure_fix"),
    ;

    companion object {
        fun fromWire(value: String): LearningKind? = entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class LearningStatus(val wireValue: String) {
    BUTLER_REVIEW("butler_review"),
    BUTLER_APPROVED("butler_approved"),
    PENDING("pending"),
    CONFLICT("conflict"),
    CONFIRMED("confirmed"),
    PROMOTED("promoted"),
    REJECTED("rejected"),
}

internal enum class LearningRuleStatus(val wireValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived"),
}

internal sealed interface LearningPolicyResult {
    data object Blocked : LearningPolicyResult
    data object Empty : LearningPolicyResult
    data class Accepted(
        val title: String,
        val content: String,
        val evidence: String,
        val confidence: Double,
        val conflict: LearningRuleEntity?,
        val redacted: Boolean,
    ) : LearningPolicyResult
}

internal object LearningPolicy {
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_CONTENT_CHARS = 4_000
    private const val MAX_EVIDENCE_CHARS = 1_200
    private const val CONFLICT_JACCARD = 0.58
    private val optOut = Regex(
        "(不要|别|无需|禁止).{0,8}(记住|记忆|保存)|do\\s+not\\s+(remember|save)|don't\\s+remember",
        RegexOption.IGNORE_CASE,
    )
    private val privateKey = Regex(
        "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val namedSecret = Regex(
        "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|passwd|authorization)\\b" +
            "\\s*[:=]\\s*([^\\s,;]+)",
    )
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val chinaId = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")
    private val phone = Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)")
    private val cardNumber = Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)")
    private val tokenLike = Regex("(?i)\\b(?:sk|ghp|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{12,}\\b")

    fun evaluate(
        kind: LearningKind,
        title: String,
        content: String,
        evidence: String,
        activeRules: List<LearningRuleEntity>,
    ): LearningPolicyResult {
        val combined = "$title\n$content\n$evidence"
        if (optOut.containsMatchIn(combined)) return LearningPolicyResult.Blocked
        val safeTitle = redact(title).value.trim().take(MAX_TITLE_CHARS)
        val safeContent = redact(content).value.trim().take(MAX_CONTENT_CHARS)
        val safeEvidence = redact(evidence).value.trim().take(MAX_EVIDENCE_CHARS)
        if (safeTitle.isBlank() || safeContent.length < 8) return LearningPolicyResult.Empty
        val redacted = safeTitle != title.trim() || safeContent != content.trim() || safeEvidence != evidence.trim()
        val conflict = activeRules.asSequence()
            .filter { it.kind == kind.wireValue && it.title.equals(safeTitle, ignoreCase = true) }
            .firstOrNull { !MemoryAutomationPolicy.equivalent(it.content, safeContent) }
            ?: activeRules.asSequence()
                .filter { it.kind == kind.wireValue }
                .filter { !MemoryAutomationPolicy.equivalent(it.content, safeContent) }
                .map { rule -> rule to similarity(rule.content + ' ' + rule.title, safeContent + ' ' + safeTitle) }
                .filter { (_, score) -> score >= CONFLICT_JACCARD }
                .maxByOrNull { (_, score) -> score }
                ?.first
        val confidence = when {
            conflict != null -> 0.48
            safeEvidence.isNotBlank() -> 0.78
            else -> 0.66
        }
        return LearningPolicyResult.Accepted(
            title = safeTitle,
            content = safeContent,
            evidence = safeEvidence,
            confidence = confidence,
            conflict = conflict,
            redacted = redacted,
        )
    }

    fun fingerprint(kind: LearningKind, title: String, content: String): String =
        sha256("v2\n" + kind.wireValue + '\n' + MemoryAutomationPolicy.dedupKey(title) + '\n' + MemoryAutomationPolicy.dedupKey(content))

    fun evidenceHash(excerpt: String): String = sha256(excerpt)

    fun sanitizeForStorage(text: String, maxChars: Int): String =
        redact(text).value.trim().take(maxChars)

    private fun redact(source: String): Redaction {
        var output = source
        var changed = false
        fun replace(regex: Regex, replacement: String) {
            val result = regex.replace(output, replacement)
            if (result != output) changed = true
            output = result
        }
        replace(privateKey, "[已脱敏：私钥]")
        replace(bearer, "Bearer [已脱敏]")
        replace(tokenLike, "[已脱敏：令牌]")
        val named = namedSecret.replace(output) { match ->
            changed = true
            "${match.groupValues[1]} = [已脱敏]"
        }
        output = named
        replace(email, "[已脱敏：邮箱]")
        replace(chinaId, "[已脱敏：证件号]")
        replace(phone, "[已脱敏：电话]")
        replace(cardNumber, "[已脱敏：卡号]")
        return Redaction(output, changed)
    }

    private fun similarity(left: String, right: String): Double {
        val leftTokens = learningTokens(left)
        val rightTokens = learningTokens(right)
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val intersection = leftTokens.intersect(rightTokens).size.toDouble()
        val union = leftTokens.union(rightTokens).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    private fun learningTokens(text: String): Set<String> {
        val normalized = text.normalizedForLearning()
        val ascii = Regex("[a-z0-9_]{2,}").findAll(normalized).map { it.value }.toMutableSet()
        val cjk = normalized.filter { it in '\u4e00'..'\u9fff' }
        if (cjk.length >= 2) {
            for (index in 0 until cjk.length - 1) ascii += cjk.substring(index, index + 2)
        }
        return ascii
    }

    private fun String.normalizedForLearning(): String =
        lowercase().replace(Regex("[\\p{P}\\p{Z}\\s]+"), " ").trim()

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class Redaction(val value: String, val changed: Boolean)
}
