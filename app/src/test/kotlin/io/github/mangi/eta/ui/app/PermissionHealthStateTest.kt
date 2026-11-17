package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionHealthStateTest {
    @Test
    fun notificationPolicyActionOnlyAppearsWhileAccessIsMissing() {
        val missing = notificationPolicyPermissionHealthItem(granted = false)
        assertEquals(PermissionStatusUi.Missing, missing.status)
        assertEquals("设置", missing.primaryActionLabel)

        val granted = notificationPolicyPermissionHealthItem(granted = true)
        assertEquals(PermissionStatusUi.Available, granted.status)
        assertEquals(null, granted.primaryActionLabel)
    }

    @Test
    fun optionalRootIsNotCountedAsAFaultButDisconnectedAccessibilityIs() {
        val root = PermissionHealthItemUi("root", "Root", "", PermissionStatusUi.Disabled, null, requiredForCore = false)
        val accessibility = PermissionHealthItemUi("accessibility", "Accessibility", "", PermissionStatusUi.Warning, "Settings")
        assertEquals(0, PermissionHealthUiState(listOf(root)).issueCount)
        assertEquals(1, PermissionHealthUiState(listOf(root, accessibility)).issueCount)
        assertEquals(0, PermissionHealthUiState(listOf(root, accessibility.copy(status = PermissionStatusUi.Available))).issueCount)
    }
}
