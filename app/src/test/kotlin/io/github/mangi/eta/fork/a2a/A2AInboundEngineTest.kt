package io.github.mangi.eta.fork.a2a

import java.net.ServerSocket
import java.net.Socket
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class A2AInboundEngineTest {
    @Test
    fun `agent card can advertise the transport endpoint while binding locally`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val advertisedEndpoint = "http://100.88.20.10:43121/a2a"
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { "test-token" },
            tasks = LegacyA2AInboundMessageHandler { prompt, _ -> "reply: $prompt" },
            bindAddress = "127.0.0.1",
            advertisedEndpointProvider = { advertisedEndpoint },
        )
        engine.start()
        try {
            val client = A2AHttpClient(a2aPeerPolicyForManualEndpoint("http://127.0.0.1:$port/a2a"))
            val card = client.discoverAgentCard("http://127.0.0.1:$port/.well-known/agent-card.json")
            assertEquals(advertisedEndpoint, card.supportedInterfaces.single().url)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `agent card discovery and authenticated send message work end to end`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "test-token"
        val receivedHistory = mutableListOf<Int>()
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { token },
            complete = { prompt, history ->
                receivedHistory += history.size
                "reply: $prompt"
            },
        )
        engine.start()
        try {
            val policy = a2aPeerPolicyForManualEndpoint("http://127.0.0.1:$port/a2a")
            val client = A2AHttpClient(policy)
            val card = client.discoverAgentCard("http://127.0.0.1:$port/.well-known/agent-card.json")
            assertEquals("Eta on this device", card.name)
            assertTrue(card.supportedInterfaces.any { it.protocolVersion == A2A_PROTOCOL_VERSION })

            val first = client.sendMessage(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = A2ASendMessageRequest(
                    message = A2AMessage(
                        messageId = "message-1",
                        role = A2ARole.USER,
                        contextId = "context-1",
                        parts = listOf(A2APart(text = "hello")),
                    ),
                ),
                bearerToken = token,
            )
            assertEquals("reply: hello", first.message?.parts?.single()?.text)

            val second = client.sendMessage(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = A2ASendMessageRequest(
                    message = A2AMessage(
                        messageId = "message-2",
                        role = A2ARole.USER,
                        contextId = "context-1",
                        parts = listOf(A2APart(text = "continue")),
                    ),
                ),
                bearerToken = token,
            )
            assertEquals("reply: continue", second.message?.parts?.single()?.text)
            assertEquals(listOf(0, 2), receivedHistory)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `declared unsupported protocol version is rejected before execution`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "version-test-token"
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { token },
            complete = { _, _ -> error("must not execute") },
        )
        engine.start()
        try {
            val connection = URI("http://127.0.0.1:$port/a2a").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", A2A_JSONRPC_CONTENT_TYPE)
            connection.setRequestProperty("A2A-Version", "0.3.0")
            connection.outputStream.use { output ->
                output.write(
                    """{"jsonrpc":"2.0","id":"version-test","method":"SendMessage","params":{}}"""
                        .toByteArray(),
                )
            }
            assertEquals(400, connection.responseCode)
            val body = connection.errorStream.bufferedReader().use { it.readText() }
            assertTrue(body.contains("Unsupported A2A version"))
            connection.disconnect()
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `streaming message uses server sent events`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "stream-token"
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { token },
            complete = { prompt, _ -> "stream: $prompt" },
        )
        engine.start()
        try {
            val client = A2AHttpClient(a2aPeerPolicyForManualEndpoint("http://127.0.0.1:$port/a2a"))
            val event = client.sendStreamingMessage(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = A2ASendMessageRequest(
                    message = A2AMessage(
                        messageId = "stream-message",
                        role = A2ARole.USER,
                        parts = listOf(A2APart(text = "hello")),
                    ),
                ),
                bearerToken = token,
            ).first()
            assertEquals("stream: hello", event.message?.parts?.single()?.text)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `client disconnect does not stop following requests`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "disconnect-token"
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { token },
            complete = { prompt, _ -> "reply: $prompt" },
        )
        engine.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("POST /a2a HTTP/1.1\r\nHost: localhost\r\n".toByteArray())
            }
            val client = A2AHttpClient(a2aPeerPolicyForManualEndpoint("http://127.0.0.1:$port/a2a"))
            val response = client.sendMessage(
                endpointUrl = "http://127.0.0.1:$port/a2a",
                request = A2ASendMessageRequest(
                    message = A2AMessage(
                        messageId = "after-disconnect",
                        role = A2ARole.USER,
                        parts = listOf(A2APart(text = "still alive")),
                    ),
                ),
                bearerToken = token,
            )
            assertEquals("reply: still alive", response.message?.parts?.single()?.text)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `standard message send alias remains compatible`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val token = "alias-token"
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { token },
            complete = { prompt, _ -> "alias: $prompt" },
        )
        engine.start()
        try {
            val connection = URI("http://127.0.0.1:$port/a2a").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", A2A_JSONRPC_CONTENT_TYPE)
            val body = """{"jsonrpc":"2.0","id":"alias-1","method":"message/send","params":{"message":{"messageId":"alias-message","role":"ROLE_USER","parts":[{"text":"hello"}]}}}"""
            connection.outputStream.use { it.write(body.toByteArray()) }
            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertTrue(response.contains("alias: hello"))
            connection.disconnect()
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `agent card derives endpoint from request host`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val engine = A2AInboundEngine(
            port = port,
            bearerTokenProvider = { "host-token" },
            complete = { _, _ -> "unused" },
        )
        engine.start()
        try {
            Socket("127.0.0.1", port).use { socket ->
                val request = "GET /.well-known/agent-card.json HTTP/1.1\r\n" +
                    "Host: 100.104.248.80:43121\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                val response = socket.getInputStream().bufferedReader().readText()
                assertTrue(response.contains("http://100.104.248.80:43121/a2a"))
                assertTrue(!response.contains("192.168.1.100"))
            }
        } finally {
            engine.stop()
        }
    }
}
