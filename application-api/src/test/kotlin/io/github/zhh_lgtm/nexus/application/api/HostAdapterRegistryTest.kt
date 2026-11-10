package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.HostId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HostAdapterRegistryTest {
    @Test fun registryDeduplicatesAndFiltersReadyHosts() {
        val registry = HostAdapterRegistry()
        val adapter = object : HostAdapter {
            override val id = HostId("mcp:test")
            override fun status() = HostStatus.READY
            override fun listTools() = emptyList<io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor>()
        }
        registry.register(adapter)
        assertEquals(1, registry.ready().size)
        assertThrows(IllegalArgumentException::class.java) { registry.register(adapter) }
    }
}
