package io.github.mangi.eta.data.repository

import io.github.zhh_lgtm.nexus.application.api.MigrationPurpose
import io.github.zhh_lgtm.nexus.application.api.NexusMigrationValidator
import io.github.zhh_lgtm.nexus.kernel.api.*

/** The existing archive remains the transport; its verified files implement the migration contract. */
internal object NexusBackupMigration {
    fun validate(manifest: EtaFullBackupManifest): NexusMigrationManifest? {
        if (manifest.schemaVersion < 4 && manifest.migrationSchemaVersion == null) return null
        val source = manifest.sourceApplicationId ?: throw EtaBackupException("迁移包缺少来源应用")
        val version = manifest.appVersion.takeIf(String::isNotBlank) ?: throw EtaBackupException("迁移包缺少来源版本")
        val contract = try {
            NexusMigrationManifest(schemaVersion = manifest.migrationSchemaVersion ?: error("MIGRATION_SCHEMA_MISSING"),
                sourceApplicationId = source, sourceVersionName = version, sourceDatabaseVersions = manifest.databaseVersions,
                createdAt = manifest.exportedAt, encrypted = manifest.encrypted,
                entries = manifest.entries.map { entry -> MigrationArtifactEntry(entry.path, entry.sha256, entry.size,
                    MigrationCategory.valueOf(entry.category ?: error("MIGRATION_CATEGORY_MISSING"))) })
        } catch (failure: IllegalArgumentException) { throw EtaBackupException("迁移清单元数据无效", failure)
        } catch (failure: IllegalStateException) { throw EtaBackupException("迁移清单缺少必要元数据", failure) }
        val purpose = if (source == NexusProduct.APPLICATION_ID) MigrationPurpose.RESTORE_NEXUS else MigrationPurpose.IMPORT_ETA
        val validation = NexusMigrationValidator.validate(contract, purpose)
        if (!validation.valid) throw EtaBackupException("迁移校验未通过：${validation.issues.joinToString(", ")}")
        return contract
    }

    fun category(path: String, section: String, encrypted: Boolean): MigrationCategory = when {
        path == "data/no_backup/eta-portable-secrets.json" -> if (encrypted) MigrationCategory.CREDENTIALS else MigrationCategory.SETTINGS
        path.startsWith("data/files/local_models/") -> MigrationCategory.MODELS
        path.startsWith("data/files/terminal") -> MigrationCategory.LINUX_HOST
        path.contains("/dsh/") -> MigrationCategory.DSH_HOST
        path.contains("/workflow-state/") || path.contains("/independent-notepad/") || path.contains("/eta_app_control/") -> MigrationCategory.SETTINGS
        path.contains("/agent-memory") || path.contains("/memories/") -> MigrationCategory.MEMORY
        path.contains("/skills/") || path.contains("/skill-") -> MigrationCategory.SKILLS
        path.contains("/capability-center/") -> MigrationCategory.CONNECTORS
        else -> when (section) {
            "core", "conversation_extensions" -> MigrationCategory.CONVERSATIONS
            "meeting", "meeting_artifacts" -> MigrationCategory.MEETINGS
            "projects" -> MigrationCategory.PROJECTS
            "learning" -> MigrationCategory.LEARNING
            "tasks" -> MigrationCategory.TASKS
            "a2a", "mcp_skills" -> MigrationCategory.CONNECTORS
            "settings", "secrets", "runtime", "notifications" -> MigrationCategory.SETTINGS
            else -> MigrationCategory.WORKSPACE_FILES
        }
    }
}
