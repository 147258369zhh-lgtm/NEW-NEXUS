package io.github.mangi.eta.ui.screens.conversations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationBranchesScreenStateTest {
    @Test fun emptyStateOnlyAppearsAfterSuccessfulLoad() {
        assertFalse(shouldShowConversationBranchEmptyState(loading = true, failed = false, rowsEmpty = true))
        assertFalse(shouldShowConversationBranchEmptyState(loading = false, failed = true, rowsEmpty = true))
        assertFalse(shouldShowConversationBranchEmptyState(loading = false, failed = false, rowsEmpty = false))
        assertTrue(shouldShowConversationBranchEmptyState(loading = false, failed = false, rowsEmpty = true))
    }
}
