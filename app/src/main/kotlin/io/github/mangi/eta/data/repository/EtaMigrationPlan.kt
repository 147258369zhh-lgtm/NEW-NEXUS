package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ETA_DATABASE_VERSION

/** Explicit migration inventory used during backup/restore and Android 16/17 upgrades. */
internal object EtaMigrationPlan {
    data class Entry(val name: String, val currentVersion: Int, val backupSection: String, val databaseName: String)

    val entries = listOf(
        Entry("core", ETA_DATABASE_VERSION, "core", "eta.db"),
        Entry("conversation_extensions", 5, "conversation_extensions", "eta_fork.db"),
        Entry("meeting", 5, "meeting", "eta_meeting.db"),
        Entry("learning", 3, "learning", "eta_learning.db"),
        Entry("tasks", 2, "tasks", "eta_task.db"),
        Entry("a2a", 2, "a2a", "eta_a2a.db"),
        Entry("projects", 2, "projects", "eta_projects.db"),
        Entry("mcp_skills", ETA_DATABASE_VERSION, "mcp_skills", "eta.db"),
        Entry("run_metrics", 3, "runtime", "eta_run_metrics.db"),
    )

    fun sectionForDatabase(name: String): String = entries.firstOrNull { name == it.name || name == it.databaseName }?.backupSection
        ?: "runtime"
}
