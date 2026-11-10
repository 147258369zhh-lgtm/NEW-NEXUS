package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.terminal.DebianEnvironmentState
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillHealthPolicyTest {
    @Test
    fun dependencyParserAcceptsCommonListFormsAndDeduplicates() {
        val dependencies = SkillDependencyParser.parse(
            frontmatter = mapOf(
                "dependencies" to "[python3, node]",
                "tools" to "- read_file\n- write_file",
            ),
            metadata = mapOf(
                "requires" to "root; Python3",
            ),
        )

        assertEquals(setOf("python3", "node", "read_file", "write_file", "root"), dependencies.toSet())
    }

    @Test
    fun staleAndUnreliableSkillsAreReviewCandidatesWithoutAutoDisabling() {
        val now = TimeUnit.DAYS.toMillis(200)
        val installed = now - TimeUnit.DAYS.toMillis(120)
        val entry = skill(installedAt = installed)

        val stale = SkillQualityPolicy.evaluate(entry, usage = null, now = now)
        assertEquals(SkillQualityIssue.STALE, stale.issue)
        assertTrue(entry.enabled)

        val unreliable = SkillQualityPolicy.evaluate(
            entry,
            SkillUsageRecord(
                skillId = entry.id,
                useCount = 10,
                successCount = 3,
                failureCount = 7,
                lastUsedAt = now - TimeUnit.DAYS.toMillis(1),
            ),
            now,
        )
        assertEquals(SkillQualityIssue.HIGH_FAILURE_RATE, unreliable.issue)
        assertEquals(0.7f, unreliable.failureRate, 0.001f)
    }

    @Test
    fun readinessDistinguishesMissingReadyAndUnverifiedRequirements() {
        val environment = SkillReadinessEnvironment(
            rootAvailable = false,
            accessibilityAvailable = true,
            accessibilityRecoveryAvailable = true,
            linuxState = DebianEnvironmentState.READY,
            supportedTools = setOf("read_file", "write_file", "terminal"),
            availableExecutables = setOf("python3"),
        )

        val reports = SkillReadinessInspector.inspect(
            listOf("root", "accessibility", "python3", "custom-package"),
            environment,
        ).associateBy(SkillPrerequisiteReport::requirement)

        assertEquals(SkillPrerequisiteStatus.BLOCKED, reports.getValue("root").status)
        assertEquals(SkillPrerequisiteStatus.READY, reports.getValue("accessibility").status)
        assertEquals(SkillPrerequisiteStatus.READY, reports.getValue("python3").status)
        assertEquals(SkillPrerequisiteStatus.UNVERIFIED, reports.getValue("custom-package").status)
    }

    private fun skill(installedAt: Long): SkillIndexEntry = SkillIndexEntry(
        id = "test-skill",
        name = "test-skill",
        description = "test",
        rootPath = "/tmp/test-skill",
        skillFilePath = "/tmp/test-skill/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
        provenance = SkillProvenance(
            skillId = "test-skill",
            sourceId = "test",
            sourceName = "Test",
            sourceType = "manual",
            installedAt = installedAt,
        ),
    )
}
