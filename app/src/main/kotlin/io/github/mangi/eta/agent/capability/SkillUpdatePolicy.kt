package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillProvenance

/**
 * Decides whether a market entry represents a safe upgrade over an installed Skill.
 *
 * Commit SHAs and archive digests are authoritative. A version string is only
 * allowed to suppress an update when both sides are comparable versions; it is
 * never treated as proof that an arbitrary string is newer.
 */
object SkillUpdatePolicy {
    private val commitSha = Regex("[a-fA-F0-9]{40}")
    private val archiveSha256 = Regex("[a-fA-F0-9]{64}")

    fun hasAvailableUpdate(item: SkillMarketItem, installedSkills: List<SkillIndexEntry>): Boolean =
        installedSkills
            .asSequence()
            .firstOrNull { it.installed && it.id == item.skillId }
            ?.let { hasAvailableUpdate(item, it) }
            ?: false

    fun hasAvailableUpdate(item: SkillMarketItem, installed: SkillIndexEntry): Boolean {
        if (!installed.installed) return false
        val provenance = installed.provenance ?: return false
        if (!isSamePackage(item, provenance)) return false

        val marketCommit = item.ref?.trim()
            ?.takeIf(commitSha::matches)
            ?.lowercase()
        val installedCommit = provenance.ref?.trim()
            ?.takeIf(commitSha::matches)
            ?.lowercase()
        val commitChanged = marketCommit != null && installedCommit != null && marketCommit != installedCommit
        if (marketCommit != null && marketCommit == installedCommit) return false

        val marketDigest = item.archiveSha256?.trim()
            ?.takeIf(archiveSha256::matches)
            ?.lowercase()
        val installedDigest = provenance.archiveSha256?.trim()
            ?.takeIf(archiveSha256::matches)
            ?.lowercase()
        val archiveChanged = marketDigest != null && installedDigest != null && marketDigest != installedDigest
        if (marketDigest != null && marketDigest == installedDigest) return false

        val marketVersion = SemanticVersion.parse(item.version)
        val installedVersion = SemanticVersion.parse(provenance.version)
        if (marketVersion != null && installedVersion != null) {
            val comparison = marketVersion.compareTo(installedVersion)
            if (comparison > 0) return true
            if (comparison < 0) return false
        }

        return commitChanged || archiveChanged
    }

    private fun isSamePackage(item: SkillMarketItem, provenance: SkillProvenance): Boolean {
        if (item.sourceId == provenance.sourceId) return true
        val repository = item.repository?.trim()?.trimEnd('/')?.lowercase()
        val installedRepository = provenance.repository?.trim()?.trimEnd('/')?.lowercase()
        if (repository.isNullOrBlank() || repository != installedRepository) return false
        val path = item.path?.trim()?.trim('/')
        val installedPath = provenance.path?.trim()?.trim('/')
        return path == installedPath
    }

    private data class SemanticVersion(
        val core: List<Int>,
        val preRelease: List<String>?,
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int {
            val width = maxOf(core.size, other.core.size)
            for (index in 0 until width) {
                val comparison = core.getOrElse(index) { 0 }
                    .compareTo(other.core.getOrElse(index) { 0 })
                if (comparison != 0) return comparison
            }
            if (preRelease == null && other.preRelease == null) return 0
            if (preRelease == null) return 1
            if (other.preRelease == null) return -1
            val length = minOf(preRelease.size, other.preRelease.size)
            for (index in 0 until length) {
                val left = preRelease[index]
                val right = other.preRelease[index]
                val leftNumber = left.toIntOrNull()
                val rightNumber = right.toIntOrNull()
                val comparison = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (comparison != 0) return comparison
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        companion object {
            private val pattern = Regex(
                "^[vV]?(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?" +
                    "(?:-([0-9A-Za-z.-]{1,64}))?(?:\\+[0-9A-Za-z.-]{1,64})?$",
            )

            fun parse(value: String?): SemanticVersion? {
                val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
                val match = pattern.matchEntire(trimmed) ?: return null
                val groups = match.groupValues.drop(1).take(4)
                val core = groups.mapNotNull { group -> group.takeIf(String::isNotEmpty)?.toIntOrNull() }
                if (core.isEmpty()) return null
                val preRelease = match.groupValues[5]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                    ?.takeIf { parts -> parts.all { it.isNotEmpty() } }
                return SemanticVersion(core, preRelease)
            }
        }
    }
}
