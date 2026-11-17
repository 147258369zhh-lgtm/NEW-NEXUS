package io.github.mangi.eta.ui.screens.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLoadStateTest {
    @Test
    fun emptyStateIsNotShownForFailedDirectoryRead() {
        assertFalse(workspaceEmptyStateVisible(entriesEmpty = true, listingFailed = true))
        assertFalse(workspaceEmptyStateVisible(entriesEmpty = false, listingFailed = false))
        assertTrue(workspaceEmptyStateVisible(entriesEmpty = true, listingFailed = false))
    }
}
