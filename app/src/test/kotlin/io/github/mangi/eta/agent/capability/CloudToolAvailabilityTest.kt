package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.cloud.CloudToolAvailability
import io.github.mangi.eta.agent.model.AgentCloudToolCatalog
import io.github.mangi.eta.data.model.CloudToolsCredentials
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.model.CloudToolsSettings
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudToolAvailabilityTest {
    @Test
    fun disabledOrUnconfiguredCloudToolsCannotReturnThroughDiscoveryOrRetainActivation() {
        // Empty routes model an older settings file; enabled capabilities still use route defaults.
        val settings = CloudToolsSettings(enabled = false, routes = emptyMap())
        var config = CloudToolsRuntimeConfig(settings, CloudToolsCredentials(deepSeekApiKey = "fixture-key"))
        val directory = InMemoryCapabilityDirectory(AgentCloudToolCatalog.names.map {
            requireNotNull(NexusToolDescriptors.describe(it))
        })
        val currentDirectory = object : CapabilityDirectory by directory {
            override fun list(query: String) = directory.list(query).map { summary ->
                summary.copy(available = CloudToolAvailability.isAvailable(summary.id, config))
            }
        }
        NexusRunHosts(currentDirectory, emptyList()).use { hosts ->
            val catalog = NexusLazyToolCatalog(hosts, toolAllowed = hosts::isAvailable, discoveryAllowed = { true })
            fun searchCount() = catalog.execute("tool_search", JSONObject().put("query", "cloud"))!!
                .getJSONArray("tools").length()
            fun activate(name: String) = catalog.execute("tool_activate", JSONObject().put("tool_id", name))!!

            assertEquals(0, searchCount())
            AgentCloudToolCatalog.names.forEach { name ->
                assertFalse(hosts.isAvailable(name))
                assertEquals("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", activate(name).getString("code"))
            }
            config = CloudToolsRuntimeConfig(settings.copy(enabled = true), CloudToolsCredentials())
            assertEquals(0, searchCount())
            assertEquals("cloud_credentials_missing", CloudToolAvailability.unavailableCode(
                AgentCloudToolCatalog.DOCUMENT, "translate", config))

            config = CloudToolsRuntimeConfig(settings.copy(enabled = true), CloudToolsCredentials(deepSeekApiKey = "fixture-key"))
            assertEquals(null, CloudToolAvailability.unavailableCode(AgentCloudToolCatalog.DOCUMENT, "ocr", config))
            assertEquals("unknown_cloud_operation", CloudToolAvailability.unavailableCode(
                AgentCloudToolCatalog.DOCUMENT, "image_generate", config))
            assertEquals(2, searchCount())
            AgentCloudToolCatalog.names.forEach { name -> assertTrue(activate(name).getBoolean("ok")) }
            assertEquals(AgentCloudToolCatalog.names, catalog.activeToolIds())

            config = CloudToolsRuntimeConfig(settings, CloudToolsCredentials(deepSeekApiKey = "fixture-key"))
            assertEquals(0, searchCount())
            assertTrue(catalog.activeToolIds().isEmpty())
            assertEquals(0, catalog.localSchemas(JSONArray().also(AgentCloudToolCatalog::appendTo)).length())
            AgentCloudToolCatalog.names.forEach { name ->
                assertFalse(hosts.isAvailable(name, JSONObject().put("operation", "ocr")))
                assertFalse(activate(name).getBoolean("ok"))
            }
        }
    }
}
