package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.MigrationCategory
import io.github.zhh_lgtm.nexus.kernel.api.NexusMigrationManifest
import io.github.zhh_lgtm.nexus.kernel.api.NexusProduct

data class MigrationValidation(
    val valid: Boolean,
    val issues: List<String>,
    val totalBytes: Long,
)

enum class MigrationPurpose { IMPORT_ETA, RESTORE_NEXUS }

object NexusMigrationValidator {
    fun validate(manifest: NexusMigrationManifest, purpose: MigrationPurpose = MigrationPurpose.IMPORT_ETA): MigrationValidation {
        val issues = buildList {
            if (manifest.schemaVersion != 1) add("UNSUPPORTED_MIGRATION_SCHEMA")
            if (purpose == MigrationPurpose.IMPORT_ETA && manifest.sourceApplicationId == NexusProduct.APPLICATION_ID) add("SOURCE_AND_TARGET_IDENTICAL")
            if (purpose == MigrationPurpose.RESTORE_NEXUS && manifest.sourceApplicationId != NexusProduct.APPLICATION_ID) add("BACKUP_SOURCE_MISMATCH")
            if (manifest.sourceApplicationId !in setOf(NexusProduct.APPLICATION_ID, "fuck.andes", "io.github.mangi.eta")) add("UNKNOWN_SOURCE_APPLICATION")
            if (manifest.sourceDatabaseVersions.any { (name, version) -> name.isBlank() || version < 1 }) add("INVALID_SOURCE_DATABASE_VERSION")
            if (manifest.entries.isEmpty()) add("MIGRATION_EMPTY")
            if (manifest.entries.any { it.category == MigrationCategory.CREDENTIALS } && !manifest.encrypted) {
                add("CREDENTIALS_REQUIRE_ENCRYPTION")
            }
            if (manifest.entries.map { it.path }.toSet().size != manifest.entries.size) add("DUPLICATE_MIGRATION_PATH")
            if (manifest.entries.any { it.bytes < 0 || it.bytes > MAX_ENTRY_BYTES }) add("MIGRATION_ENTRY_TOO_LARGE")
        }.toMutableList()
        val totalBytes = manifest.entries.fold(0L) { total, entry ->
            if (total > Long.MAX_VALUE - entry.bytes) Long.MAX_VALUE else total + entry.bytes
        }
        if (totalBytes > MAX_ARCHIVE_BYTES) issues += "MIGRATION_ARCHIVE_TOO_LARGE"
        return MigrationValidation(issues.isEmpty(), issues, totalBytes)
    }

    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
}
