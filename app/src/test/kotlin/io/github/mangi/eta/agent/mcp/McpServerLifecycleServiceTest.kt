package io.github.mangi.eta.agent.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class McpServerLifecycleServiceTest {
    @Test fun deletingAServerStopsItsProcessBeforeRemovingTheRecord() = runBlocking {
        val calls = mutableListOf<String>()
        val service = McpServerLifecycleService(
            stopProcess = { calls += "stop:$it"; true },
            deleteRecord = { calls += "delete:$it" },
        )

        service.delete("fixture-server")

        assertEquals(listOf("stop:fixture-server", "delete:fixture-server"), calls)
    }

    @Test(expected = IllegalStateException::class)
    fun survivingProcessPreventsRecordDeletion() = runBlocking {
        val calls = mutableListOf<String>()
        val service = McpServerLifecycleService(
            stopProcess = { calls += "stop:$it"; false },
            deleteRecord = { calls += "delete:$it" },
        )
        service.delete("fixture-server")
        assertEquals(listOf("stop:fixture-server"), calls)
    }
}
