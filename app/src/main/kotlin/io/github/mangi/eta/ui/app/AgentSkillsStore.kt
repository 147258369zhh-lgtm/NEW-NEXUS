package io.github.mangi.eta.ui.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillPrerequisiteStatus
import io.github.mangi.eta.agent.skill.SkillQualityIssue
import io.github.mangi.eta.agent.skill.SkillQualityPolicy
import io.github.mangi.eta.agent.skill.SkillReadinessInspector
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillVersionRestoreOutcome
import io.github.mangi.eta.agent.skill.SkillVersionService
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.SkillVersionUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Skills 管理页状态：列表、开关、删除与本地 ZIP 安装的二次确认流程。 */
internal class AgentSkillsStore(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val usage = runCatching {
                io.github.mangi.eta.agent.skill.SkillUsageStore(appContext).readAll()
            }.getOrDefault(emptyMap())
            val readinessEnvironment = runCatching {
                SkillReadinessInspector.capture(appContext)
            }.getOrNull()
            val versions = runCatching { SkillVersionService(appContext).listAll() }
                .getOrDefault(emptyList())
                .groupBy { it.skillId }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                val quality = SkillQualityPolicy.evaluate(entry, usage[entry.id])
                val qualityLabel = when (quality.issue) {
                    SkillQualityIssue.NONE -> null
                    SkillQualityIssue.STALE -> "长期未使用"
                    SkillQualityIssue.HIGH_FAILURE_RATE -> "失败率偏高"
                }
                val qualityDetail = when (quality.issue) {
                    SkillQualityIssue.NONE -> null
                    SkillQualityIssue.STALE -> if (quality.daysSinceUse == null) {
                        "已超过 90 天没有使用记录"
                    } else if (quality.useCount == 0) {
                        "安装后 ${quality.daysSinceUse} 天尚未使用"
                    } else {
                        "最近使用距今 ${quality.daysSinceUse} 天"
                    }
                    SkillQualityIssue.HIGH_FAILURE_RATE -> {
                        "最近 ${quality.useCount} 次调用中失败 ${quality.failureCount} 次，" +
                            "失败率 ${(quality.failureRate * 100).roundToInt()}%"
                    }
                }
                val prerequisites = readinessEnvironment?.let { environment ->
                    SkillReadinessInspector.inspect(entry.dependencies, environment)
                }.orEmpty()
                val blocked = prerequisites.filter { it.status == SkillPrerequisiteStatus.BLOCKED }
                val unverified = prerequisites.filter { it.status == SkillPrerequisiteStatus.UNVERIFIED }
                val prerequisiteLabel = when {
                    blocked.isNotEmpty() -> blocked.size.toString() + " 项依赖未满足"
                    unverified.isNotEmpty() -> unverified.size.toString() + " 项依赖待确认"
                    prerequisites.isNotEmpty() -> "依赖已就绪"
                    else -> null
                }
                val prerequisiteDetail = prerequisites
                    .take(4)
                    .joinToString("；") { report -> report.requirement + " · " + report.detail }
                    .ifBlank { null }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                    sourceName = entry.provenance?.sourceName?.lineSequence()?.firstOrNull()?.trim()
                        ?.take(80)
                        ?.takeIf(String::isNotBlank),
                    version = entry.provenance?.version?.lineSequence()?.firstOrNull()?.trim()
                        ?.take(40)
                        ?.takeIf(String::isNotBlank),
                    installedAt = entry.provenance?.installedAt?.takeIf { it > 0L },
                    useCount = usage[entry.id]?.useCount ?: 0,
                    lastUsedAt = usage[entry.id]?.lastUsedAt?.takeIf { it > 0L },
                    reviewRecommended = quality.issue != SkillQualityIssue.NONE,
                    qualityLabel = qualityLabel,
                    qualityDetail = qualityDetail,
                    dependencies = entry.dependencies,
                    prerequisiteLabel = prerequisiteLabel,
                    prerequisiteDetail = prerequisiteDetail,
                    setupRequired = blocked.isNotEmpty(),
                    versions = versions[entry.id].orEmpty().map { backup ->
                        SkillVersionUi(
                            id = backup.id,
                            version = backup.version,
                            sourceName = backup.sourceName.takeIf(String::isNotBlank),
                            createdAt = backup.createdAt,
                            sizeBytes = backup.sizeBytes,
                        )
                    },
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_eta_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.VERSION_BACKUP_FAILED ->
                appContext.getString(R.string.state_skill_version_backup_failed_before_update)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun restoreSkillVersion(skillId: String, backupId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { SkillVersionService(appContext).restore(backupId) }
                .getOrElse { SkillVersionRestoreOutcome.Failure(it.message ?: "版本恢复失败") }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = when (outcome) {
                        is SkillVersionRestoreOutcome.Success -> newSkillNotice(
                            title = "版本已恢复",
                            message = outcome.backup.displayName + " 已恢复" +
                                (outcome.backup.version?.let { "到 v$it" } ?: "到上一个版本"),
                            isError = false,
                        )
                        is SkillVersionRestoreOutcome.Failure -> newSkillNotice(
                            title = if (outcome.recoveryRequired) "版本恢复需要修复" else "版本恢复失败",
                            message = outcome.message,
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)
}
