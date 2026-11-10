package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusLazyToolCatalogTest {
    private val readFile = descriptor("read_file", EffectClass.READ)
    private val terminal = descriptor("terminal", EffectClass.HIGH_RISK)

    @Test
    fun searchDescribeAndActivationFollowTheRunPolicy() {
        val directory = InMemoryCapabilityDirectory(listOf(readFile, terminal))
        val catalog = NexusLazyToolCatalog(
            directory = directory,
            toolAllowed = { it == "read_file" },
            discoveryAllowed = { true },
        )
        val discovery = catalog.appendDiscoverySchemas(JSONArray())
        assertEquals(NexusToolDiscoveryCatalog.NAMES.size, discovery.length())

        val search = catalog.execute("tool_search", JSONObject().put("query", "file"))!!
        assertEquals("read_file", search.getJSONArray("tools").getJSONObject(0).getString("tool_id"))

        val blocked = catalog.execute("tool_activate", JSONObject().put("tool_id", "terminal"))!!
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", blocked.getString("code"))

        val activated = catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!
        assertTrue(activated.getBoolean("ok"))
        assertEquals(setOf("read_file"), catalog.activeToolIds())
    }

    @Test
    fun disabledDiscoveryDoesNotLeakControlTools() {
        val catalog = NexusLazyToolCatalog(
            directory = InMemoryCapabilityDirectory(listOf(readFile)),
            toolAllowed = { true },
            discoveryAllowed = { false },
        )
        assertEquals(0, catalog.appendDiscoverySchemas(JSONArray()).length())
        assertEquals("TOOL_DISCOVERY_DISABLED", catalog.execute("tool_search", JSONObject())!!.getString("code"))
    }

    @Test
    fun activationCannotBypassCapabilityAuthorization() {
        val catalog = NexusLazyToolCatalog(
            directory = InMemoryCapabilityDirectory(listOf(readFile)),
            toolAllowed = { true },
            discoveryAllowed = { true },
            authorize = {
                CapabilityAuthorization.Denied("LOCAL_CONFIRMATION_REQUIRED", "local approval required")
            },
        )
        val result = catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!
        assertFalse(result.getBoolean("ok"))
        assertEquals("LOCAL_CONFIRMATION_REQUIRED", result.getString("code"))
        assertTrue(catalog.activeToolIds().isEmpty())
    }

    @Test
    fun externalSchemasBecomeVisibleOnlyAfterActivation() {
        val external = JSONArray().put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "memory_search")
                .put("description", "Search reviewed memory")
                .put("parameters", JSONObject().put("type", "object"))))
        val catalog = NexusLazyToolCatalog(
            directory = InMemoryCapabilityDirectory(emptyList()),
            toolAllowed = { true },
            discoveryAllowed = { true },
            additionalSchemas = external,
        )
        assertEquals(0, catalog.localSchemas(JSONArray()).length())
        val result = catalog.execute("tool_activate", JSONObject().put("tool_id", "memory_search"))!!
        assertTrue(result.getBoolean("ok"))
        val schemas = catalog.localSchemas(JSONArray())
        assertEquals(1, schemas.length())
        assertEquals("memory_search", schemas.getJSONObject(0).getJSONObject("function").getString("name"))
    }

    private fun descriptor(id: String, effect: EffectClass) = ToolDescriptor(
        id = id,
        namespace = id.substringBefore('_'),
        shortDescription = "$id description",
        modelDescription = "$id full description",
        inputSchemaRef = "nexus-tool://$id/input",
        effectClass = effect,
        replayPolicy = if (effect == EffectClass.READ) ReplayPolicy.AUTOMATIC else ReplayPolicy.REQUIRE_USER,
        timeoutMs = 30_000,
        hostKind = HostKind.NATIVE_ANDROID,
    )
}
