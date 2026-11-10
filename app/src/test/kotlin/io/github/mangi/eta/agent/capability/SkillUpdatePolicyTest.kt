package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillProvenance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillUpdatePolicyTest {
    @Test
    fun detectsNewPinnedCommitAndArchive() {
        val installed = skill(
            commit = "a".repeat(40),
            digest = "b".repeat(64),
            version = "1.4.0",
        )

        assertTrue(
            SkillUpdatePolicy.hasAvailableUpdate(
                market(commit = "c".repeat(40), digest = "d".repeat(64), version = "1.4.0"),
                installed,
            ),
        )
        assertTrue(
            SkillUpdatePolicy.hasAvailableUpdate(
                market(commit = null, digest = "d".repeat(64), version = "1.5.0"),
                skill(commit = null, digest = "b".repeat(64), version = "1.4.0"),
            ),
        )
    }

    @Test
    fun doesNotOfferDowngradeOrDuplicateContent() {
        val installed = skill(version = "1.10.0")

        assertFalse(SkillUpdatePolicy.hasAvailableUpdate(market(version = "1.9.9"), installed))
        assertFalse(SkillUpdatePolicy.hasAvailableUpdate(market(version = "v1.10.0"), installed))
        assertFalse(SkillUpdatePolicy.hasAvailableUpdate(market(version = "latest"), installed))
    }

    @Test
    fun comparesPrereleaseVersionsWithoutGuessingArbitraryStrings() {
        val beta = skill(version = "2.0.0-beta.2")

        assertTrue(SkillUpdatePolicy.hasAvailableUpdate(market(version = "2.0.0"), beta))
        assertTrue(SkillUpdatePolicy.hasAvailableUpdate(market(version = "2.0.0-beta.10"), beta))
        assertFalse(SkillUpdatePolicy.hasAvailableUpdate(market(version = "2.0.0-alpha.9"), beta))
    }

    @Test
    fun ignoresEntriesFromAnotherPackageEvenWhenVersionLooksNewer() {
        val installed = skill(version = "1.0.0")
        val other = market(version = "9.0.0").copy(
            sourceId = "another-source",
            repository = "someone/else",
            path = "other",
        )

        assertFalse(SkillUpdatePolicy.hasAvailableUpdate(other, installed))
    }

    private fun skill(commit: String? = null, digest: String? = null, version: String?): SkillIndexEntry =
        SkillIndexEntry(
            id = "sample-skill",
            name = "Sample",
            description = "Sample",
            rootPath = "/skills/sample-skill",
            skillFilePath = "/skills/sample-skill/SKILL.md",
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            provenance = SkillProvenance(
                skillId = "sample-skill",
                sourceId = "source",
                sourceName = "Source",
                sourceType = SkillSourceType.GITHUB_REPOSITORY,
                repository = "owner/repo",
                ref = commit,
                path = "skills/sample",
                archiveSha256 = digest,
                version = version,
                installedAt = 100L,
            ),
        )

    private fun market(commit: String? = null, digest: String? = null, version: String?): SkillMarketItem =
        SkillMarketItem(
            sourceId = "source",
            sourceName = "Source",
            sourceType = SkillSourceType.GITHUB_REPOSITORY,
            trust = SkillSourceTrust.CUSTOM,
            skillId = "sample-skill",
            displayName = "Sample",
            description = "Sample",
            version = version,
            repository = "owner/repo",
            ref = commit,
            path = "skills/sample",
            archiveSha256 = digest,
        )
}
