package io.github.mangi.eta.ui.screens.projects

import io.github.mangi.eta.fork.projects.ProjectEntity

internal sealed interface ProjectsLoadState {
    data object Loading : ProjectsLoadState
    data class Loaded(val projects: List<ProjectEntity>) : ProjectsLoadState
    data object Failed : ProjectsLoadState
}

internal fun shouldShowProjectsEmptyState(state: ProjectsLoadState, showArchived: Boolean): Boolean =
    (state as? ProjectsLoadState.Loaded)?.projects
        ?.none { showArchived || it.archivedAt == null } == true

internal enum class ProjectDetailsDataLoadState { Loading, Loaded, Failed }

internal fun hasProjectDetailsSnapshot(state: ProjectDetailsDataLoadState): Boolean =
    state == ProjectDetailsDataLoadState.Loaded
