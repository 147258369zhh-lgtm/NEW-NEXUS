package io.github.mangi.eta.fork.a2a

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress

class A2AUrlPolicyTest {
    @Test
    fun `manual private http endpoint is allowed only for its exact host`() {
        val policy = a2aPeerPolicyForManualEndpoint("http://192.168.1.20:43121/a2a")
        assertEquals("http://192.168.1.20:43121/a2a", policy.validateUrl("http://192.168.1.20:43121/a2a").toString())
        assertThrows(A2AProtocolException::class.java) {
            policy.validateUrl("http://192.168.1.21:43121/a2a")
        }
        assertThrows(A2AProtocolException::class.java) {
            a2aPeerPolicyForManualEndpoint("http://agents.example.com/a2a")
        }
        assertThrows(A2AProtocolException::class.java) {
            a2aPeerPolicyForManualEndpoint("http://169.254.169.254/latest/meta-data")
        }
    }

    @Test
    fun `requires https and exact or wildcard allowlist match`() {
        val policy = A2APeerPolicy(setOf("agents.example.com", "*.trusted.example"))
        assertEquals(
            "https://agents.example.com/rpc",
            policy.validateUrl("https://agents.example.com/rpc").toString(),
        )
        assertEquals(
            "api.trusted.example",
            policy.validateUrl("https://api.trusted.example/rpc").host,
        )
        assertThrows(A2AProtocolException::class.java) {
            policy.validateUrl("http://agents.example.com/rpc")
        }
        assertThrows(A2AProtocolException::class.java) {
            policy.validateUrl("https://evil.example/rpc")
        }
        assertThrows(A2AProtocolException::class.java) {
            policy.validateUrl("https://user:pass@agents.example.com/rpc")
        }
    }

    @Test
    fun `dns validation rejects private and metadata addresses`() {
        val policy = A2APeerPolicy(setOf("agents.example.com"))
        val dns = policy.secureDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("169.254.169.254"))
        })
        assertThrows(A2AProtocolException::class.java) { dns.lookup("agents.example.com") }
    }

    @Test
    fun `dns validation accepts public address and never resolves off-list host`() {
        val policy = A2APeerPolicy(setOf("agents.example.com"))
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("8.8.8.8"))
        }
        val dns = policy.secureDns(delegate)
        assertEquals("8.8.8.8", dns.lookup("agents.example.com").single().hostAddress)
        assertThrows(A2AProtocolException::class.java) { dns.lookup("evil.example") }
    }
}
