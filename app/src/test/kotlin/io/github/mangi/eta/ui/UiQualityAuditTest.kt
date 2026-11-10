package io.github.mangi.eta.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiQualityAuditTest {
    @Test
    fun enforcesReadableTitlesAndTouchTargets() {
        assertTrue(UiQualityAudit.validPrimaryTitle("项目设置"))
        assertFalse(UiQualityAudit.validPrimaryTitle(" "))
        assertTrue(UiQualityAudit.validTouchTarget(48, 48))
        assertFalse(UiQualityAudit.validTouchTarget(40, 48))
    }
}
