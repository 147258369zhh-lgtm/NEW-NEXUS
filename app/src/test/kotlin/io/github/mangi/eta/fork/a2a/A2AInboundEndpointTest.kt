package io.github.mangi.eta.fork.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2AInboundEndpointTest {
    @Test
    fun `advertised transport endpoint derives card url and wildcard binding`() {
        val snapshot = A2AInboundSnapshot(
            enabled = true,
            port = 43121,
            lanAddress = "192.168.1.20",
            advertisedEndpointUrl = "http://100.88.20.10:43121/a2a",
            token = "token",
            lastError = null,
        )

        assertEquals("http://100.88.20.10:43121/a2a", snapshot.endpointUrl)
        assertEquals("http://100.88.20.10:43121/.well-known/agent-card.json", snapshot.agentCardUrl)
        assertEquals("0.0.0.0", snapshot.bindAddress)
    }

    @Test
    fun `lan mode binds the lan interface and rejects unsafe advertised urls`() {
        val snapshot = A2AInboundSnapshot(
            enabled = true,
            port = 43121,
            lanAddress = "192.168.1.20",
            advertisedEndpointUrl = null,
            token = "token",
            lastError = null,
        )

        assertEquals("0.0.0.0", snapshot.bindAddress)
        assertEquals("http://192.168.1.20:43121/a2a", snapshot.endpointUrl)
        assertTrue(A2AInboundConfigStore.isValidAdvertisedEndpoint("https://agent.example/a2a"))
        assertFalse(A2AInboundConfigStore.isValidAdvertisedEndpoint("https://user:pass@agent.example/a2a"))
        assertFalse(A2AInboundConfigStore.isValidAdvertisedEndpoint("https://agent.example/not-a2a"))
        assertFalse(A2AInboundConfigStore.isValidAdvertisedEndpoint("file:///tmp/a2a"))
    }
}
