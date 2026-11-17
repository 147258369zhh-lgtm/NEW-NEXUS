package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCornerRadiusTest {
    @Test fun fallsBackWhenDisplayDoesNotProvideRadius() {
        assertEquals(12f, safeScreenCornerRadius(12f) { null }, 0f)
        assertEquals(12f, safeScreenCornerRadius(12f) { 0 }, 0f)
        assertEquals(12f, safeScreenCornerRadius(12f) { throw IllegalStateException() }, 0f)
        assertEquals(24f, safeScreenCornerRadius(12f) { 24 }, 0f)
    }
}
