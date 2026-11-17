package io.github.mangi.eta.ui.screens.projects

import io.github.mangi.eta.fork.projects.ProjectEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectsLoadStateTest {
    @Test
    fun emptyStateRequiresLoadedProjectsAndRespectsArchiveFilter() {
        assertFalse(shouldShowProjectsEmptyState(ProjectsLoadState.Loading, showArchived = false))
        assertFalse(shouldShowProjectsEmptyState(ProjectsLoadState.Failed, showArchived = false))
        assertTrue(shouldShowProjectsEmptyState(ProjectsLoadState.Loaded(emptyList()), showArchived = false))
        assertTrue(shouldShowProjectsEmptyState(ProjectsLoadState.Loaded(listOf(project(archived = true))), showArchived = false))
        assertFalse(shouldShowProjectsEmptyState(ProjectsLoadState.Loaded(listOf(project(archived = true))), showArchived = true))
        assertFalse(hasProjectDetailsSnapshot(ProjectDetailsDataLoadState.Loading))
        assertFalse(hasProjectDetailsSnapshot(ProjectDetailsDataLoadState.Failed))
        assertTrue(hasProjectDetailsSnapshot(ProjectDetailsDataLoadState.Loaded))
    }

    private fun project(archived: Boolean) = ProjectEntity(
        id = "project",
        name = "Project",
        providerId = "test",
        rootIdentityKey = "root",
        rootDisplayName = "Root",
        source = "test",
        createdAt = 1L,
        updatedAt = 1L,
        archivedAt = 2L.takeIf { archived },
    )
}
