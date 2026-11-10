package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSkillsUiState(
    val skills: List<SkillItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val busySkillId: String? = null,
    val replacement: SkillReplacementUi? = null,
    val notice: SkillNoticeUi? = null,
)

@Immutable
data class SkillReplacementUi(
    val id: String,
    val name: String,
)

@Immutable
data class SkillNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)

@Immutable
data class SkillItemUi(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val installed: Boolean,
    val capabilities: List<String>,
    val sourceName: String? = null,
    val version: String? = null,
    val installedAt: Long? = null,
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val reviewRecommended: Boolean = false,
    val qualityLabel: String? = null,
    val qualityDetail: String? = null,
    val dependencies: List<String> = emptyList(),
    val prerequisiteLabel: String? = null,
    val prerequisiteDetail: String? = null,
    val setupRequired: Boolean = false,
    val versions: List<SkillVersionUi> = emptyList(),
)

@Immutable
data class SkillVersionUi(
    val id: String,
    val version: String? = null,
    val sourceName: String? = null,
    val createdAt: Long,
    val sizeBytes: Long,
)

internal val SkillItemUi.canDeleteUserSkill: Boolean
    get() = installed && source == "user"
