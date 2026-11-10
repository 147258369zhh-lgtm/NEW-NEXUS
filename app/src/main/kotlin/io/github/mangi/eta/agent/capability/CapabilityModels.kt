package io.github.mangi.eta.agent.capability

object SkillSourceType {
    const val GITHUB_REPOSITORY = "github_repository"
    const val HTTP_CATALOG = "http_catalog"
}

object SkillSourceTrust {
    const val OFFICIAL = "official"
    const val CURATED = "curated"
    const val COMMUNITY = "community"
    const val CUSTOM = "custom"
}

data class SkillSourceDefinition(
    val id: String,
    val name: String,
    val type: String,
    val location: String,
    val ref: String? = null,
    val path: String? = null,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val trust: String = SkillSourceTrust.CUSTOM,
    val builtIn: Boolean = false,
)

data class SkillMarketItem(
    val sourceId: String,
    val sourceName: String,
    val sourceType: String,
    val trust: String,
    val skillId: String,
    val displayName: String,
    val description: String,
    val version: String? = null,
    val publisher: String? = null,
    val repository: String? = null,
    val ref: String? = null,
    val path: String? = null,
    val archiveUrl: String? = null,
    val archiveSha256: String? = null,
    val tags: List<String> = emptyList(),
    val usage: String? = null,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val releaseNotes: String? = null,
) {
    val key: String = "$sourceId::$skillId::${ref.orEmpty()}::${path.orEmpty()}"

    val integrityVerified: Boolean
        get() = archiveSha256 != null ||
            (sourceType == SkillSourceType.GITHUB_REPOSITORY && Regex("[a-f0-9]{40}").matches(ref.orEmpty()))
}

data class SkillMarketDetails(
    val item: SkillMarketItem,
    val usage: String? = null,
    val dependencies: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val releaseNotes: String? = null,
    val readmeExcerpt: String? = null,
    val detailWarning: String? = null,
)

sealed interface SkillMarketInstallOutcome {
    data class Success(
        val skills: List<io.github.mangi.eta.agent.skill.InstalledSkill>,
        val setupRequiredSkillIds: Set<String> = emptySet(),
        val prerequisiteWarnings: List<String> = emptyList(),
    ) : SkillMarketInstallOutcome

    data class Conflict(
        val skills: List<io.github.mangi.eta.agent.skill.SkillInstallConflict>,
    ) : SkillMarketInstallOutcome

    data class ConfirmationRequired(
        val reason: String,
    ) : SkillMarketInstallOutcome

    data class Failure(
        val message: String,
        val recoveryRequired: Boolean = false,
    ) : SkillMarketInstallOutcome
}

data class SkillSourceIssue(
    val sourceId: String,
    val sourceName: String,
    val code: String,
    val message: String,
)

data class SkillSourceFetchStatus(
    val sourceId: String,
    val sourceName: String,
    val succeeded: Boolean,
    val itemCount: Int,
    val message: String? = null,
)

data class SkillMarketSnapshot(
    val items: List<SkillMarketItem> = emptyList(),
    val statuses: List<SkillSourceFetchStatus> = emptyList(),
    val issues: List<SkillSourceIssue> = emptyList(),
    val searchedAt: Long = System.currentTimeMillis(),
)

class SkillSourceValidationException(message: String) : IllegalArgumentException(message)

class SkillCatalogException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
