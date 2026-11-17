package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.capability.McpPortableCodec
import io.github.mangi.eta.agent.capability.McpPortableConfigException
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpTransportType
import org.junit.Assert.*
import org.junit.Test

class McpExecutionBindingTest {
    private val server = McpServerSetting("test", "test", "", transportType = McpTransportType.STDIO, stdioCommand = "node")
    @Test fun legacyPortableConnectionRemainsAndroid() {
        val result = McpPortableCodec.decode("""{"schema":"${McpPortableCodec.SCHEMA}","connections":[{"name":"old","transportType":"stdio","stdioCommand":"node"}]}""")
        assertEquals("android", result.connections.single().executionTarget)
        assertEquals("chroot", result.connections.single().linuxBackend)
    }
    @Test fun linuxBindingRoundTripsAndChangesConnectionIdentity() {
        val linux = server.copy(executionTarget = "debian", linuxBackend = "proot", workingDirectory = "/workspace/project")
        assertFalse(server.sameConnectionAs(linux))
        val portable = McpPortableCodec.decode(McpPortableCodec.encode(listOf(linux))).connections.single()
        assertEquals("debian", portable.executionTarget)
        assertEquals("proot", portable.linuxBackend)
        assertEquals("/workspace/project", portable.workingDirectory)
    }
    @Test fun invalidBindingsNeverFallBackToAndroid() {
        assertThrows(IllegalArgumentException::class.java) { validateMcpExecutionBinding(server.copy(executionTarget = "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { validateMcpExecutionBinding(server.copy(executionTarget = "debian", linuxBackend = "auto")) }
        assertThrows(IllegalArgumentException::class.java) { validateMcpExecutionBinding(server.copy(executionTarget = "alpine", workingDirectory = "relative")) }
        assertThrows(McpPortableConfigException::class.java) {
            McpPortableCodec.decode("""{"schema":"${McpPortableCodec.SCHEMA}","connections":[{"name":"bad","transportType":"stdio","stdioCommand":"node","executionTarget":"unknown"}]}""")
        }
    }
    @Test fun guestDirectoryIsQuotedAndFailureStopsCommand() {
        val command = mcpGuestCommand("/workspace/a' b", "exec node server.js")
        assertTrue(command.startsWith("cd '/workspace/a'\\'' b'"))
        assertTrue(command.contains("exit 72"))
        assertTrue(command.endsWith("exec node server.js"))
    }
}
