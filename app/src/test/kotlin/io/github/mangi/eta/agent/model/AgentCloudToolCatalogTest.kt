package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.capability.NexusToolDescriptors
import io.github.mangi.eta.agent.cloud.CloudMediaTools
import io.github.mangi.eta.agent.subagent.SubagentPermissionCatalog
import io.github.mangi.eta.agent.subagent.SubagentPermissionCategory
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.data.model.CloudCapabilities
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCloudToolCatalogTest {
    @Test
    fun cloudSchemasCoverEveryRuntimeOperationAndSurviveProviderValidation() {
        val tools = JSONArray().also(AgentCloudToolCatalog::appendTo)
        val sanitized = AgentToolSchema.sanitizeFunctionCatalog(tools)
        assertEquals(AgentCloudToolCatalog.names, sanitized.functions().map { it.getString("name") }.toSet())
        val operations = tools.functions().flatMap { function ->
            function.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("operation").getJSONArray("enum").strings()
        }
        assertEquals(CloudCapabilities.definitions.map { it.operation }.toSet(), operations.toSet())
        assertEquals(operations.size, operations.toSet().size)
        val media = tools.function(AgentCloudToolCatalog.MEDIA).getJSONObject("parameters")
        assertEquals(CloudMediaTools.operations, media.getJSONObject("properties")
            .getJSONObject("operation").getJSONArray("enum").strings().toSet())
        tools.functions().forEach { function ->
            val parameters = function.getJSONObject("parameters")
            assertFalse(parameters.getBoolean("additionalProperties"))
            val request = parameters.getJSONObject("properties").getJSONObject("request_id")
            assertEquals(8, request.getInt("minLength"))
            assertEquals(128, request.getInt("maxLength"))
            val pattern = Regex(request.getString("pattern"))
            assertTrue(pattern.matches("request-0001"))
            assertFalse(pattern.matches("short"))
            assertFalse(pattern.matches("request/0001"))
        }
        val document = tools.function(AgentCloudToolCatalog.DOCUMENT).getJSONObject("parameters").getJSONObject("properties")
        assertEquals("array", document.getJSONObject("documents").getString("type"))
        assertEquals("array", document.getJSONObject("texts").getString("type"))
        val term = document.getJSONObject("terms").getJSONObject("items")
        assertFalse(term.getBoolean("additionalProperties"))
        assertEquals(setOf("source", "target"), term.getJSONArray("required").strings().toSet())
    }

    @Test
    fun disabledRunHidesCloudToolsWhileFullDirectoryAndRootlessRegistrationRetainThem() {
        val disabled = AgentToolCatalog.build(terminalTools = false, browserTools = false, cloudTools = false)
        assertTrue(disabled.functions().none { it.getString("name") in AgentCloudToolCatalog.names })
        val rootless = AgentToolCatalog.build(terminalTools = false, browserTools = false,
            capabilities = AgentToolCapabilities(rootAvailable = false), cloudTools = true)
        assertTrue(rootless.functions().map { it.getString("name") }.containsAll(AgentCloudToolCatalog.names))
        AgentCloudToolCatalog.names.forEach { name ->
            assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement(name))
            val descriptor = requireNotNull(NexusToolDescriptors.describe(name))
            assertEquals(EffectClass.EXTERNAL_SIDE_EFFECT, descriptor.effectClass)
            assertEquals(ReplayPolicy.REQUIRE_USER, descriptor.replayPolicy)
            assertFalse(NexusToolDescriptors.batchSlot(name).readOnly)
        }
    }

    @Test
    fun cloudPermissionsRequireExplicitWriteCapabilityForReadOnlySubagents() {
        val category = requireNotNull(SubagentPermissionCatalog.category("cloud-tools"))
        assertEquals(SubagentPermissionCategory.Risk.WRITE, category.risk)
        assertEquals(AgentCloudToolCatalog.names, category.tools)
        assertTrue(SubagentPermissionCatalog.allTools.containsAll(AgentCloudToolCatalog.names))
        val allowed = SubagentProfile.EXPLORE.withRuntimeToolPolicy(category.tools.toList(), emptySet())
        category.tools.forEach { name ->
            assertFalse(SubagentProfile.EXPLORE.isToolAllowed(name))
            assertTrue(allowed.isToolAllowed(name))
        }
    }

    @Test
    fun cloudTracesAndSensitivePolicyNeverEchoDocumentsOrPrivateReferences() {
        val formatter = AgentTraceFormatter()
        AgentCloudToolCatalog.names.forEach { name ->
            assertTrue(AgentSensitiveToolPolicy.isSensitive(name))
            val operation = if (name == AgentCloudToolCatalog.DOCUMENT) "translate" else "image_generate"
            val arguments = JSONObject().put("operation", operation).put("prompt", "private-marker")
                .put("text", "private-marker").put("file", "content://private-marker").toString()
            val call = AgentModelClient.ToolCall("cloud", name, arguments)
            assertFalse(formatter.summarizeArguments(call).contains("private-marker"))
            assertEquals(null, formatter.displayCommand(call))
            val pending = AgentModelClient.ToolResult("""{"ok":true,"pending":true,"text":"private-marker","task_id":"private-marker"}""")
            assertTrue(formatter.summarizeResult(name, pending).contains("处理中"))
            assertFalse(formatter.summarizeResult(name, pending).contains("private-marker"))
            val unknown = AgentModelClient.ToolResult("""{"ok":false,"outcome_unknown":true,"message":"private-marker"}""")
            assertTrue(formatter.isUnconfirmedResult(unknown))
            assertTrue(formatter.summarizeResult(name, unknown).contains("未确认"))
            assertFalse(formatter.summarizeResult(name, unknown).contains("private-marker"))
        }
    }

    private fun JSONArray.functions(): List<JSONObject> = (0 until length()).map {
        getJSONObject(it).getJSONObject("function")
    }

    private fun JSONArray.function(name: String): JSONObject = functions().single { it.getString("name") == name }

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}
