package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpPortableCodecTest {
    @Test
    fun exportsOnlyPortableConnectionMetadata() {
        val json = McpPortableCodec.encode(
            listOf(
                McpServerSetting(
                    id = "private-local-id",
                    name = "Home MCP",
                    url = "https://mcp.example.com/rpc",
                    enabled = false,
                    protocolMode = McpProtocolMode.LATEST,
                    authorizationType = McpAuthorizationType.BEARER,
                    enabledToolNames = setOf("lookup", "search"),
                ),
            ),
            exportedAt = 42L,
        )

        val decoded = McpPortableCodec.decode(json)
        assertEquals(42L, decoded.exportedAt)
        val connection = decoded.connections.single()
        assertEquals("Home MCP", connection.name)
        assertEquals(McpProtocolMode.LATEST, connection.protocolMode)
        assertEquals(McpAuthorizationType.BEARER, connection.authorizationType)
        assertEquals(setOf("lookup", "search"), connection.enabledToolNames)
        assertTrue(!json.contains("private-local-id"))
        assertTrue(!json.contains("token"))
    }

    @Test
    fun rejectsCredentialBearingUrlsAndUnknownSchemas() {
        for (json in listOf(
            """{"schema":"other","connections":[]}""",
            """{"schema":"${McpPortableCodec.SCHEMA}","credentialsIncluded":true,"connections":[]}""",
            """{"schema":"${McpPortableCodec.SCHEMA}","connections":[{"name":"x","url":"https://user:secret@example.com/mcp"}]}""",
            """{"schema":"${McpPortableCodec.SCHEMA}","connections":[{"name":"x","url":"https://example.com/mcp?token=secret"}]}""",
        )) {
            assertThrows(McpPortableConfigException::class.java) { McpPortableCodec.decode(json) }
        }
    }

    @Test
    fun importSkipsDuplicateUrlsAndKeepsBearerConnectionsUnconfigured() = runBlocking {
        val existing = McpServerSetting(
            id = "existing",
            name = "Existing",
            url = "https://mcp.example.com/existing",
        )
        val added = mutableListOf<McpServerSetting>()
        val service = McpPortableConfigService(
            listServers = { listOf(existing) },
            addServer = { server -> server.also(added::add) },
            deleteServer = {},
        )

        val result = service.importJson(
            config(
                connection("Existing", existing.url),
                connection("Team", "https://mcp.example.com/team", "bearer"),
            ),
        )

        assertEquals(1, result.imported)
        assertEquals(1, result.skippedDuplicates)
        assertEquals(1, result.credentialRequired)
        val connection = added.single()
        assertEquals(McpAuthorizationType.BEARER, connection.authorizationType)
        assertEquals(emptySet<String>(), connection.enabledToolNames)
    }

    @Test
    fun importRollsBackEveryInsertedConnectionAfterMidwayFailure() = runBlocking {
        val deleted = mutableListOf<String>()
        var inserts = 0
        val service = McpPortableConfigService(
            listServers = { emptyList() },
            addServer = { server ->
                inserts += 1
                if (inserts == 2) error("database unavailable")
                server.copy(id = "added-$inserts")
            },
            deleteServer = { deleted += it },
        )

        val failure = runCatching {
            service.importJson(
                config(
                    connection("One", "https://mcp.example.com/one"),
                    connection("Two", "https://mcp.example.com/two"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is McpPortableConfigException)
        assertEquals(listOf("added-1"), deleted)
    }

    @Test
    fun cancellationStillRollsBackAlreadyInsertedConnections() = runBlocking {
        val deleted = mutableListOf<String>()
        var inserts = 0
        val service = McpPortableConfigService(
            listServers = { emptyList() },
            addServer = { server ->
                inserts += 1
                if (inserts == 2) throw CancellationException("cancelled")
                server.copy(id = "added-${UUID.randomUUID()}")
            },
            deleteServer = { deleted += it },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                service.importJson(
                    config(
                        connection("One", "https://mcp.example.com/one"),
                        connection("Two", "https://mcp.example.com/two"),
                    ),
                )
            }
        }
        assertEquals(1, deleted.size)
        assertTrue(deleted.single().startsWith("added-"))
    }

    private fun config(vararg connections: String): String =
        """{"schema":"${McpPortableCodec.SCHEMA}","credentialsIncluded":false,"connections":[${connections.joinToString(",")}]}"""

    private fun connection(name: String, url: String, authorization: String = "none"): String =
        """{"name":"$name","url":"$url","authorizationType":"$authorization"}"""
}
