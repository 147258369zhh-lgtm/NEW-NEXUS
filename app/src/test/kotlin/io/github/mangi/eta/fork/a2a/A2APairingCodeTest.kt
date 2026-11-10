package io.github.mangi.eta.fork.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class A2APairingCodeTest {
    @Test
    fun roundTripsHttpsAndLocalPairingData() {
        val https = A2APairingCode.decode(
            A2APairingCode.encode(
                displayName = "  My Computer  ",
                agentCardUrl = "https://agent.example.com/.well-known/agent-card.json",
                bearerToken = "  secret-token  ",
            ),
        )
        assertEquals("My Computer", https.displayName)
        assertEquals("https://agent.example.com/.well-known/agent-card.json", https.agentCardUrl)
        assertEquals("secret-token", https.bearerToken)

        val local = A2APairingCode.decode(
            A2APairingCode.encode(
                displayName = "Phone",
                agentCardUrl = "http://192.168.1.20:43121/.well-known/agent-card.json",
                bearerToken = null,
            ),
        )
        assertEquals("Phone", local.displayName)
        assertNull(local.bearerToken)
    }

    @Test
    fun rejectsForeignSchemesEmbeddedCredentialsAndUnknownVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            A2APairingCode.encode("Agent", "ftp://example.com/card", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2APairingCode.encode("Agent", "https://user:pass@example.com/card", null)
        }
        val foreign = A2APairingCode.encode("Agent", "https://example.com/card", null)
            .replace("eta-a2a://pair?payload=", "https://foreign.example/?payload=")
        assertThrows(IllegalArgumentException::class.java) { A2APairingCode.decode(foreign) }

        val futurePayload = A2APairingData(
            version = 99,
            displayName = "Agent",
            agentCardUrl = "https://example.com/card",
        )
        val future = "eta-a2a://pair?payload=" +
            payloadBase64(A2A_JSON.encodeToString(futurePayload))
        assertThrows(A2AProtocolException::class.java) { A2APairingCode.decode(future) }
    }

    @Test
    fun rejectsMalformedAndOversizedContent() {
        assertThrows(IllegalArgumentException::class.java) { A2APairingCode.decode("") }
        assertThrows(A2AProtocolException::class.java) {
            A2APairingCode.decode("eta-a2a://pair?payload=not-valid-base64!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2APairingCode.encode("x".repeat(81), "https://example.com/card", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2APairingCode.encode("Agent", "https://example.com/card", "x".repeat(4_097))
        }
    }

    private fun payloadBase64(value: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
}
