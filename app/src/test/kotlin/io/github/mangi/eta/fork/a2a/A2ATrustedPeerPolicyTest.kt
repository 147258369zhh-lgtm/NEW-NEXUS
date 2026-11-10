package io.github.mangi.eta.fork.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class A2ATrustedPeerPolicyTest {
    private val peer = A2APairedTunnelPeer(
        nodeId = "node-phone",
        endpointHost = "100.88.20.10",
        bearerSecretAlias = "a2a-phone-token",
    )

    @Test
    fun `tailnet host is allowed only with the exact paired node identity`() {
        val policy = A2ATrustedPeerPolicy.forEndpoint(
            rawUrl = "http://100.88.20.10:43121/a2a",
            peer = peer,
            authenticatedNodeId = "node-phone",
        )
        assertEquals("100.88.20.10", policy.allowedHosts.single())
        assertThrows(A2AProtocolException::class.java) {
            A2ATrustedPeerPolicy.forEndpoint(
                rawUrl = "http://100.88.20.11:43121/a2a",
                peer = peer,
                authenticatedNodeId = "node-phone",
            )
        }
    }

    @Test
    fun `tailnet reachability cannot substitute for paired node identity`() {
        assertThrows(A2AProtocolException::class.java) {
            A2ATrustedPeerPolicy.forEndpoint(
                rawUrl = "http://100.88.20.10:43121/a2a",
                peer = peer,
                authenticatedNodeId = "node-attacker",
            )
        }
    }

    @Test
    fun `arbitrary tailnet host cannot reuse another pairing`() {
        assertThrows(A2AProtocolException::class.java) {
            A2ATrustedPeerPolicy.forEndpoint(
                rawUrl = "http://100.101.7.9:43121/a2a",
                peer = peer,
                authenticatedNodeId = "node-phone",
            )
        }
    }
}
