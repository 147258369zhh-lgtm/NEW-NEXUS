package io.github.mangi.eta.agent.connector

import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectorSecurityManagerTest {
    @Test
    fun remoteEndpointRequiresTls() {
        val security = ConnectorSecurityManager()
        security.validateEndpoint("https://example.com/socket")
        assertThrows(SecurityException::class.java) {
            security.validateEndpoint("http://example.com/socket")
        }
        security.validateEndpoint("http://127.0.0.1:4000/socket")
    }

    @Test
    fun destructiveCapabilityRequiresExplicitConfirmation() {
        val security = ConnectorSecurityManager()
        val descriptor = ConnectorDescriptor(
            id = "test",
            name = "test",
            kind = ConnectorKind.ANDROID_INTENT,
            transport = "intent",
            capabilities = listOf(
                ConnectorCapability("open", "open", "open", destructive = true),
            ),
        )
        assertThrows(SecurityException::class.java) {
            security.authorize(descriptor, ConnectorInvocation("open"), confirmed = false)
        }
        security.authorize(descriptor, ConnectorInvocation("open"), confirmed = true)
    }
}
