package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class PortableSecretsRestoreTest {
    @Test fun oneDamagedCategoryDoesNotBlockLaterCredentialRestores() = runBlocking {
        val restored = mutableListOf<String>()

        val failures = PortableSecretsRestore.run(listOf(
            "providers" to { restored += "providers" },
            "api_connectors" to { throw IllegalArgumentException("corrupt connector metadata") },
            "mcp" to { restored += "mcp" },
            "speech" to { restored += "speech" },
        ))

        assertEquals(listOf("providers", "mcp", "speech"), restored)
        assertEquals(listOf("api_connectors"), failures.map { it.section })
    }
}
