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
@Config(sdk = [36], shadows = [io.github.mangi.eta.agent.cloud.CloudAtomicFileRenameShadow::class])
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
        assertTrue(capabilities.any { it.id == "settings.model_roles" })
        assertTrue(capabilities.any { it.id == "settings.current_model" })
        assertTrue(capabilities.any { it.id == "state.agent_permission" })
        assertTrue(capabilities.any { it.id == "settings.agent_permission" })
        assertTrue(capabilities.any { it.id == "settings.learning" })
        assertTrue(capabilities.any { it.id == "learning.candidate.promote" })
        assertTrue(capabilities.any { it.id == "learning.candidate.review" })
        assertTrue(capabilities.any { it.id == "learning.rule.rollback" })
        assertTrue(capabilities.any { it.id == "backup.run_now" })
        assertTrue(capabilities.any { it.id == "state.models" })
        assertTrue(capabilities.any { it.id == "state.local_models" })
        assertTrue(capabilities.any { it.id == "state.mcp_servers" })
        assertTrue(capabilities.any { it.id == "state.api_connectors" })
        assertTrue(capabilities.any { it.id == "settings.api_connector.enabled" })
        assertTrue(capabilities.any { it.id == "settings.api_connector.action" })
        assertTrue(capabilities.any { it.id == "settings.mcp_server" })
        assertTrue(capabilities.any { it.id == "settings.mcp_tool" })
        assertTrue(capabilities.any { it.id == "settings.mcp_server.refresh" })
        assertTrue(capabilities.any { it.id == "mcp_server.delete" })
        assertTrue(capabilities.any { it.id == "state.skills" })
        assertTrue(capabilities.any { it.id == "settings.skill.enabled" })
        assertTrue(capabilities.any { it.id == "skill.read_document" })
        assertTrue(capabilities.any { it.id == "settings.skill.document" })
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
    fun apiConnectorStateReadsStoredActionsWithoutSensitiveConfiguration() = runBlocking {
        val store = io.github.mangi.eta.agent.capability.ApiConnectorStore(context)
        val original = store.load()
        try {
            store.save(listOf(io.github.mangi.eta.agent.capability.ApiConnectorDefinition(
                id = "api-control-test", name = "Reports", baseUrl = "https://secret.example.com",
                description = "private description", authType = "bearer", enabled = false,
                actions = listOf(io.github.mangi.eta.agent.capability.ApiConnectorAction(
                    name = "get_report", description = "private action description", method = "GET",
                    path = "/private", inputSchemaJson = "{\"type\":\"object\"}", enabled = true,
                    destructive = false,
                )),
            )))
            val result = EtaAppControlRegistry.get(context, "state.api_connectors")
            val connector = result.getJSONArray("connectors").getJSONObject(0)
            assertEquals("api-control-test", connector.getString("id"))
            assertEquals(false, connector.getBoolean("enabled"))
            assertTrue(connector.getJSONArray("actions").getJSONObject(0).getBoolean("enabled"))
            assertTrue(!result.toString().contains("secret.example.com"))
            assertTrue(!result.toString().contains("private"))
            assertTrue(!result.toString().contains("bearer"))
        } finally {
            store.save(original)
        }
    }

    @Test
    fun skillEditsUseRevisionAndKeepDocumentOutOfAudit() = runBlocking {
        io.github.mangi.eta.agent.skill.SkillRuntime.resetForTests()
        val skillId = "control-edit-test"
        val directory = java.io.File(io.github.mangi.eta.agent.skill.SkillRuntime.skillsRoot(context), skillId)
        directory.mkdirs()
        val manifest = java.io.File(directory, "SKILL.md")
        manifest.writeText("---\nname: $skillId\ndescription: edit fixture\n---\n\nOriginal\n")
        val document = EtaAppControlRegistry.execute(context, "skill.read_document", JSONObject().put("skillId", skillId))
        val revision = document.getString("revision")
        val parameters = JSONObject().put("skillId", skillId).put("name", "Renamed")
            .put("body", "private document text").put("revision", revision)
        val result = EtaAppControlRegistry.execute(context, "settings.skill.document", parameters)
        assertEquals("Renamed", result.getString("name"))
        assertTrue(manifest.readText().contains("private document text"))
        assertTrue(runCatching { EtaAppControlRegistry.execute(context, "settings.skill.document", parameters) }.isFailure)
        val audit = EtaAppControlAuditStore(context).list(10)
        assertTrue(audit.none { it.toString().contains("private document text") })
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
