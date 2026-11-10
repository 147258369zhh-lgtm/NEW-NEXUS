package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class EtaMigrationPlanTest {
    @Test
    fun keepsNewDatabasesInBackupSections() {
        assertEquals("projects", EtaMigrationPlan.sectionForDatabase("eta_projects.db"))
        assertEquals("tasks", EtaMigrationPlan.sectionForDatabase("tasks"))
        assertEquals("conversation_extensions", EtaMigrationPlan.sectionForDatabase("eta_fork.db"))
        assertEquals("core", EtaMigrationPlan.sectionForDatabase("eta.db"))
        assertEquals("runtime", EtaMigrationPlan.sectionForDatabase("eta_run_metrics.db"))
        assertEquals(27, EtaMigrationPlan.entries.single { it.name == "core" }.currentVersion)
        assertEquals(5, EtaMigrationPlan.entries.single { it.name == "conversation_extensions" }.currentVersion)
        assertEquals(2, EtaMigrationPlan.entries.single { it.name == "projects" }.currentVersion)
        assertEquals(5, EtaMigrationPlan.entries.single { it.name == "meeting" }.currentVersion)
        assertEquals(3, EtaMigrationPlan.entries.single { it.name == "learning" }.currentVersion)
        assertEquals(2, EtaMigrationPlan.entries.single { it.name == "tasks" }.currentVersion)
        assertEquals("runtime", EtaMigrationPlan.sectionForDatabase("unknown.db"))
    }
}
