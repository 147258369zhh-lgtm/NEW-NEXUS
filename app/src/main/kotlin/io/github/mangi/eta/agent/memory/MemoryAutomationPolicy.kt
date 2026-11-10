package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.data.repository.AgentMemoryScopeType
import io.github.mangi.eta.fork.learning.LearningPolicy
import java.text.Normalizer
import java.util.Locale

internal object MemoryAutomationPolicy {
    private val optOut = Regex("(不要|别|禁止|不许).{0,10}(记住|记忆|保存|学习)|do\\s+not\\s+(remember|save|learn)|don't\\s+(remember|save|learn)", RegexOption.IGNORE_CASE)
    private val secrets = Regex("验证码|口令|密码|私钥|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|Bearer\\s|BEGIN.*PRIVATE KEY|\\bsk-[A-Za-z0-9]", RegexOption.IGNORE_CASE)
    fun allowed(text: String): Boolean = !optOut.containsMatchIn(text)
    fun safe(text: String, max: Int): String = LearningPolicy.sanitizeForStorage(text, max)
    fun sensitive(text: String): Boolean = secrets.containsMatchIn(text)
    // Similar wording can still change a number, a negation, or a path. Only formatting is deduplicated automatically.
    fun dedupKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+"), " ")
    fun equivalent(left: String, right: String): Boolean = dedupKey(left) == dedupKey(right)
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\p{P}\\p{Z}\\s]+"), "").trim()
    fun similarity(a: String, b: String): Double {
        val left = normalize(a)
        val right = normalize(b)
        if (left == right) return 1.0
        fun grams(s: String) = if (s.length < 2) setOf(s) else s.windowed(2).toSet()
        val x = grams(left); val y = grams(right)
        return x.intersect(y).size.toDouble() / x.union(y).size.coerceAtLeast(1)
    }
    fun parseScope(key: String): AgentMemoryScope {
        if (key == "global") return AgentMemoryScope.GLOBAL
        val parts = key.split(':', limit = 2)
        require(parts.size == 2) { "记忆作用域无效" }
        return requireNotNull(AgentMemoryScope.fromPath(parts[0], parts[1])) { "记忆作用域无效" }
    }
    fun ttl(scope: AgentMemoryScope, days: Int, now: Long): Long? =
        if (scope.type in setOf(AgentMemoryScopeType.TASK, AgentMemoryScopeType.CONVERSATION, AgentMemoryScopeType.MEETING))
            now + days.coerceIn(1, 365) * 86_400_000L else null
}
