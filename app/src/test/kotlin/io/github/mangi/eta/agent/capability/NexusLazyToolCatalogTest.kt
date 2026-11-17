package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.model.AgentModelClient
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
    @Test fun aCommittedRunExtensionAllowsFreshActivationAfterTheOriginalDeadline() {
        var time = 10L
        var deadline = 20L
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(listOf(descriptor("read_file", EffectClass.READ))),
            toolAllowed = { true }, discoveryAllowed = { true }, validUntil = deadline, now = { time },
            validUntilProvider = { deadline })
        time = 21L
        assertFalse(catalog.enabled())
        deadline = 40L
        assertTrue(catalog.enabled())
        assertTrue(catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!.getBoolean("ok"))
    }
    @Test fun fullCatalogCanReplaceOneActiveSchemaWithoutExpandingPermissions() {
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory((0..17).map { descriptor("tool_$it", EffectClass.READ) }),
            toolAllowed = { it != "tool_17" }, discoveryAllowed = { true })
        repeat(16) { assertTrue(catalog.execute("tool_activate", JSONObject().put("tool_id", "tool_$it"))!!.getBoolean("ok")) }
        val full = catalog.execute("tool_activate", JSONObject().put("tool_id", "tool_16"))!!
        assertEquals("TOOL_ACTIVATION_LIMIT", full.getString("code"))
        assertEquals(16, full.getJSONArray("active_tool_ids").length())
        val denied = catalog.execute("tool_activate", JSONObject().put("tool_id", "tool_17").put("replace_tool_id", "tool_0"))!!
        assertFalse(denied.getBoolean("ok"))
        assertTrue("tool_0" in catalog.activeToolIds())
        assertTrue(catalog.execute("tool_activate", JSONObject().put("tool_id", "tool_16").put("replace_tool_id", "tool_0"))!!.getBoolean("ok"))
        assertEquals(16, catalog.activeToolIds().size)
        assertFalse("tool_0" in catalog.activeToolIds())
        assertTrue("tool_16" in catalog.activeToolIds())
    }

    @Test fun historyRestoresOnlyMatchedSuccessfulToolsUnderCurrentAuthorization() {
        val tools = listOf("read_file", "write_file", "delete_file", "list_files", "move_file")
            .map { descriptor(it, EffectClass.READ) }
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(tools),
            toolAllowed = { it != "write_file" }, discoveryAllowed = { true },
            authorize = { if (it.id == "move_file") CapabilityAuthorization.Denied("REVOKED", "revoked")
                else CapabilityAuthorization.Allowed })
        fun pair(id: String, tool: String, args: String, result: String) = listOf(
            AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = JSONArray().put(JSONObject()
                .put("id", id).put("type", "function").put("function", JSONObject()
                    .put("name", tool).put("arguments", args))).toString()),
            AgentModelClient.ConversationMessage("tool", result, toolCallId = id),
        )
        val history = pair("1", "tool_activate", """{"tool_id":"read_file"}""",
            """{"ok":true,"active":true,"tool_id":"read_file"}""") +
            pair("2", "write_file", "{}", """{"ok":true}""") +
            pair("3", "delete_file", "{}", """{"ok":false}""") +
            pair("4", "tool_activate", """{"tool_id":"delete_file"}""",
                """{"ok":true,"active":true,"tool_id":"list_files"}""") +
            pair("5", "move_file", "{}", """{"ok":true}""") +
            AgentModelClient.ConversationMessage("tool", """{"ok":true}""", toolCallId = "unmatched")
        catalog.restoreUsedTools(history)
        assertEquals(setOf("read_file"), catalog.activeToolIds())
        val described = catalog.execute("tool_describe", JSONObject().put("tool_id", "read_file"))!!
        assertEquals("object", described.getJSONObject("parameters").getString("type"))
    }

    @Test fun expiredAndRevokedActivationsDisappearBeforeTheNextSchemaRequest() {
        var time = 10L
        var permitted = true
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(listOf(readFile)),
            toolAllowed = { permitted }, discoveryAllowed = { true }, validUntil = 20, now = { time })
        val activated = catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!
        assertEquals(20L, activated.getLong("expires_at"))
        permitted = false
        assertTrue(catalog.activeToolIds().isEmpty())
        permitted = true
        assertTrue(catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!.getBoolean("ok"))
        time = 20
        assertTrue(catalog.activeToolIds().isEmpty())
        assertEquals(0, catalog.localSchemas(JSONArray()).length())
        assertEquals("GRANT_EXPIRED", catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!.getString("code"))
    }

    @Test fun capabilityRevocationAlsoRemovesAnAlreadyActivatedSchema() {
        var allowed = true
        val catalog = NexusLazyToolCatalog(InMemoryCapabilityDirectory(listOf(readFile)), toolAllowed = { true },
            discoveryAllowed = { true }, authorize = {
                if (allowed) CapabilityAuthorization.Allowed else CapabilityAuthorization.Denied("REVOKED", "revoked")
            })
        assertTrue(catalog.execute("tool_activate", JSONObject().put("tool_id", "read_file"))!!.getBoolean("ok"))
        allowed = false
        assertTrue(catalog.activeToolIds().isEmpty())
    }

    @Test fun browserControlIsNotClassifiedAsReadOnly() {
        assertEquals(EffectClass.GUI_ACTION, NexusToolEffectClassifier.classify("browser_use"))
    }

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
