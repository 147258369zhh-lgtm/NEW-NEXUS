package io.github.mangi.eta.agent.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRecoveryLeaseTest {
    @Test
    fun onlyTheExactTokenWithinTheLeaseWindowIsAccepted() {
        val lease = AccessibilityRecoveryLease("issued-token", 1_000L)
        assertTrue(lease.validAt("issued-token", 1_000L))
        assertTrue(lease.validAt("issued-token", 25_999L))
        assertFalse(lease.validAt("another-token", 1_000L))
        assertFalse(lease.validAt("issued-token", 999L))
        assertFalse(lease.validAt("issued-token", 26_000L))
    }
}
