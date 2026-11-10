package io.github.mangi.eta.fork.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPolicyTest {
    @Test
    fun explicitDoNotRememberBlocksPersistence() {
        val result = LearningPolicy.evaluate(
            kind = LearningKind.PREFERENCE,
            title = "用户偏好",
            content = "不要记住这个偏好，也不要保存任何相关内容",
            evidence = "fixture",
            activeRules = emptyList(),
        )
        assertEquals(LearningPolicyResult.Blocked, result)
    }

    @Test
    fun secretsAndPersonalIdentifiersAreRedactedBeforeCandidateCreation() {
        val result = LearningPolicy.evaluate(
            kind = LearningKind.FAILURE_FIX,
            title = "API Key sk-1234567890abcdefghijkl",
            content = "遇到错误时不要重试。api_key=super-secret-value，联系 person@example.com 或 13800138000。",
            evidence = "Bearer abcdefghijklmnopqrstuvwxyz",
            activeRules = emptyList(),
        ) as LearningPolicyResult.Accepted
        val combined = result.title + '\n' + result.content + '\n' + result.evidence
        assertTrue(result.redacted)
        assertTrue(!combined.contains("super-secret-value"))
        assertTrue(!combined.contains("person@example.com"))
        assertTrue(!combined.contains("13800138000"))
        assertTrue(!combined.contains("abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun similarButDifferentRuleRequiresConflictConfirmation() {
        val existing = LearningRuleEntity(
            id = "rule",
            kind = LearningKind.PROCEDURE.wireValue,
            title = "Android 构建验证流程",
            content = "Android 构建失败时先运行 Gradle 单测，再检查 KSP 与 Room 生成的错误。",
            currentVersion = 1,
            status = LearningRuleStatus.ACTIVE.wireValue,
            createdAt = 1,
            updatedAt = 1,
        )
        val result = LearningPolicy.evaluate(
            kind = LearningKind.PROCEDURE,
            title = "Android 构建验证流程",
            content = "Android 构建失败时先运行 Gradle 单测，再检查 Room 的 KSP 错误。",
            evidence = "新的失败证据",
            activeRules = listOf(existing),
        ) as LearningPolicyResult.Accepted
        assertEquals(existing, result.conflict)
    }
}
