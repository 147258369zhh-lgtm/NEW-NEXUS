package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.MigrationCategory
import io.github.zhh_lgtm.nexus.kernel.api.NexusMigrationManifest
import io.github.zhh_lgtm.nexus.kernel.api.NexusProduct

data class MigrationValidation(
    val valid: Boolean,
    val issues: List<String>,
    val totalBytes: Long,
)

object NexusMigrationValidator {
    fun validate(manifest: NexusMigrationManifest): MigrationValidation {
        val issues = buildList {
            if (manifest.schemaVersion != 1) add("UNSUPPORTED_MIGRATION_SCHEMA")
            if (manifest.sourceApplicationId == NexusProduct.APPLICATION_ID) add("SOURCE_AND_TARGET_IDENTICAL")
            if (manifest.entries.isEmpty()) add("MIGRATION_EMPTY")
            if (manifest.entries.any { it.category == MigrationCategory.CREDENTIALS } && !manifest.encrypted) {
                add("CREDENTIALS_REQUIRE_ENCRYPTION")
            }
            if (manifest.entries.map { it.path }.toSet().size != manifest.entries.size) add("DUPLICATE_MIGRATION_PATH")
            if (manifest.entries.any { it.bytes < 0 || it.bytes > MAX_ENTRY_BYTES }) add("MIGRATION_ENTRY_TOO_LARGE")
        }.toMutableList()
        val totalBytes = manifest.entries.sumOf { it.bytes }
        if (totalBytes > MAX_ARCHIVE_BYTES) issues += "MIGRATION_ARCHIVE_TOO_LARGE"
        return MigrationValidation(issues.isEmpty(), issues, totalBytes)
    }

    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
}
