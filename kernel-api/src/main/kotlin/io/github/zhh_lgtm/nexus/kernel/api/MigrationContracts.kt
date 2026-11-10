package io.github.zhh_lgtm.nexus.kernel.api

enum class MigrationCategory {
    SETTINGS,
    CONVERSATIONS,
    MEETINGS,
    PROJECTS,
    MEMORY,
    LEARNING,
    TASKS,
    SKILLS,
    CONNECTORS,
    CREDENTIALS,
    WORKSPACE_FILES,
    MODELS,
    LINUX_HOST,
    DSH_HOST,
}

data class MigrationArtifactEntry(
    val path: String,
    val sha256: String,
    val bytes: Long,
    val category: MigrationCategory,
) {
    init {
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains("..")) { "Unsafe migration path" }
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid SHA-256" }
        require(bytes >= 0) { "Invalid artifact size" }
    }
}

data class NexusMigrationManifest(
    val schemaVersion: Int = NexusProduct.DATA_SCHEMA_VERSION,
    val sourceApplicationId: String = "fuck.andes",
    val sourceVersionName: String,
    val sourceDatabaseVersions: Map<String, Int>,
    val createdAt: Long,
    val encrypted: Boolean,
    val entries: List<MigrationArtifactEntry>,
) {
    init {
        require(schemaVersion > 0) { "Invalid migration schema" }
        require(sourceVersionName.isNotBlank()) { "Source version is required" }
        require(createdAt >= 0) { "Invalid creation time" }
        require(entries.map(MigrationArtifactEntry::path).toSet().size == entries.size) { "Duplicate migration path" }
    }
}
