package io.github.mangi.eta.agent.control

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaAppControlRegistryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun capabilityIdsAreStableUniqueAndSearchable() {
        val capabilities = EtaAppControlRegistry.list()
        assertEquals(capabilities.size, capabilities.map { it.id }.toSet().size)
        assertTrue(capabilities.any { it.id == "navigation.models" })
        assertTrue(capabilities.any { it.id == "navigation.backup" })
        assertTrue(capabilities.any { it.id == "settings.memory.enabled" })
        assertTrue(capabilities.any { it.id == "settings.model_fallback" })
        assertTrue(capabilities.any { it.id == "settings.current_model" })
        assertTrue(capabilities.any { it.id == "settings.learning" })
        assertTrue(capabilities.any { it.id == "learning.candidate.promote" })
        assertTrue(capabilities.any { it.id == "learning.candidate.review" })
        assertTrue(capabilities.any { it.id == "learning.rule.rollback" })
        assertTrue(capabilities.any { it.id == "backup.run_now" })
        assertTrue(capabilities.any { it.id == "state.models" })
        assertTrue(capabilities.any { it.id == "state.local_models" })
        assertTrue(capabilities.any { it.id == "state.mcp_servers" })
        assertTrue(capabilities.any { it.id == "settings.mcp_server" })
        assertTrue(capabilities.any { it.id == "settings.mcp_tool" })
        assertTrue(capabilities.any { it.id == "mcp_server.delete" })
        assertTrue(capabilities.any { it.id == "state.skills" })
        assertTrue(capabilities.any { it.id == "settings.skill.enabled" })
        assertTrue(capabilities.any { it.id == "skill.delete" })
        assertTrue(capabilities.any { it.id == "state.prompt_templates" })
        assertTrue(capabilities.any { it.id == "state.subagents" })
        assertTrue(capabilities.any { it.id == "subagent.create" })
        assertTrue(capabilities.any { it.id == "subagent.delete" })
        assertTrue(capabilities.any { it.id == "state.projects" })
        assertTrue(capabilities.any { it.id == "state.learning" })
        assertTrue(capabilities.any { it.id == "state.memory_candidates" })
        assertTrue(capabilities.any { it.id == "memory.candidate.review" })
        assertTrue(capabilities.any { it.id == "state.backup" })
        assertTrue(capabilities.any { it.id == "state.shared_folders" })
        assertTrue(capabilities.any { it.id == "state.runtime" })
        assertTrue(capabilities.any { it.id == "state.statistics.today" })
        assertTrue(EtaAppControlRegistry.search("会议").any { it.id == "navigation.prompt_injection" })
    }

    @Test
    fun navigationPublishesTypedRouteAndReturnsExecutionResult() = runBlocking {
        val result = EtaAppControlRegistry.execute(
            context = context,
            id = "navigation.capabilities",
            parameters = JSONObject(),
        )
        assertEquals("capabilities", result.getString("routeKey"))
        val event = withTimeout(2_000) { EtaAppControlEvents.events.first() }
        assertTrue(event is EtaAppControlEvent.Navigate)
        assertEquals("capabilities", (event as EtaAppControlEvent.Navigate).routeKey)
    }

    @Test
    fun destructiveCapabilityWaitsForVisibleConfirmationAndHonorsCancel() = runBlocking {
        val execution = async(Dispatchers.Default) {
            runCatching {
                EtaAppControlRegistry.execute(
                    context = context,
                    id = "settings.memory.clear",
                    parameters = JSONObject(),
                )
            }
        }
        val confirmation = withTimeout(2_000) {
            EtaAppControlConfirmation.pending.filterNotNull().first()
        }
        assertEquals("settings.memory.clear", confirmation.capabilityId)
        assertTrue(EtaAppControlConfirmation.cancel(confirmation.id))
        val error = execution.await().exceptionOrNull()
        assertTrue(error is EtaControlException)
        error as EtaControlException
        assertEquals("USER_CANCELLED", error.code)
        assertNotNull(error.message)
    }

    @Test
    fun destructiveCapabilityExecutesAfterVisibleApproval() = runBlocking {
        val execution = async(Dispatchers.Default) {
            runCatching {
                EtaAppControlRegistry.execute(
                    context = context,
                    id = "settings.memory.clear",
                    parameters = JSONObject(),
                )
            }
        }
        val confirmation = withTimeout(2_000) {
            EtaAppControlConfirmation.pending.filterNotNull().first()
        }
        assertTrue(EtaAppControlConfirmation.approve(confirmation.id))
        val result = withTimeout(5_000) { execution.await().getOrThrow() }
        assertTrue(result.getBoolean("cleared"))
    }

    @Test
    fun expandedStateReadersReturnRealRepositoryData() = runBlocking {
        listOf(
            "state.models",
            "state.model_fallback",
            "state.local_models",
            "state.mcp_servers",
            "state.prompt_templates",
            "state.subagents",
            "state.projects",
            "state.learning",
            "state.backup",
            "state.shared_folders",
        ).forEach { id ->
            val result = withTimeout(10_000) {
                EtaAppControlRegistry.get(context, id)
            }
            assertEquals(id, result.getString("id"))
            assertTrue("$id did not return ok", result.getBoolean("ok"))
        }
    }
}
