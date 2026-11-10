package io.github.mangi.eta.fork.a2a

import org.junit.Assert.assertThrows
import org.junit.Test

class A2APeerPolicyTest {
    @Test
    fun manualEndpointsStayAllowlisted() {
        val policy = a2aPeerPolicyForManualEndpoint("http://192.168.1.20:8080")
        policy.validateUrl("http://192.168.1.20:8080/v1")
        assertThrows(A2AProtocolException::class.java) { policy.validateUrl("https://example.com/v1") }
    }
}
