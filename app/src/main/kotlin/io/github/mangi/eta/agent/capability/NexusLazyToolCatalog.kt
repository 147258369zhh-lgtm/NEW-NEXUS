package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.application.api.CapabilityActivation
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-run lazy tool catalogue. It exposes discovery operations and only the local schemas
 * explicitly activated during the run. Execution and visibility share the same policy predicate.
 */
internal class NexusLazyToolCatalog(
    private val directory: CapabilityDirectory,
    private val toolAllowed: (String) -> Boolean,
    private val discoveryAllowed: () -> Boolean,
    private val authorize: (ToolDescriptor) -> CapabilityAuthorization = { CapabilityAuthorization.Allowed },
    additionalSchemas: JSONArray = JSONArray(),
) {
    private val active = linkedMapOf<String, CapabilityActivation>()
    private val additional = linkedMapOf<String, JSONObject>().also { tools ->
        for (index in 0 until additionalSchemas.length()) {
            val schema = additionalSchemas.optJSONObject(index) ?: continue
            val function = schema.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isNotBlank()) tools[name] = JSONObject(schema.toString())
        }
    }

    fun enabled(): Boolean = discoveryAllowed()

    fun activeToolIds(): Set<String> = synchronized(active) { active.keys.toSet() }

    fun localSchemas(fullCatalog: JSONArray): JSONArray {
        val ids = activeToolIds()
        return JSONArray().also { selected ->
            for (index in 0 until fullCatalog.length()) {
                val schema = fullCatalog.optJSONObject(index) ?: continue
                val name = schema.optJSONObject("function")?.optString("name").orEmpty()
                if (name in ids && toolAllowed(name)) selected.put(JSONObject(schema.toString()))
            }
            activeToolIds().forEach { id ->
                if (toolAllowed(id)) additional[id]?.let { schema -> selected.put(JSONObject(schema.toString())) }
            }
        }
    }

    fun appendDiscoverySchemas(schemas: JSONArray): JSONArray {
        if (!enabled()) return JSONArray(schemas.toString())
        val existing = buildSet {
            for (index in 0 until schemas.length()) {
                schemas.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                    ?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return JSONArray(schemas.toString()).also { result ->
            val discovery = NexusToolDiscoveryCatalog.schemas()
            for (index in 0 until discovery.length()) {
                val schema = discovery.getJSONObject(index)
                val name = schema.getJSONObject("function").getString("name")
                if (name !in existing) result.put(schema)
            }
        }
    }

    /** Returns null for ordinary tools, or the complete result for a discovery operation. */
    fun execute(toolName: String, arguments: JSONObject): JSONObject? {
        if (toolName !in NexusToolDiscoveryCatalog.NAMES) return null
        if (!enabled()) return error("TOOL_DISCOVERY_DISABLED", "本次运行未开放能力发现")
        return when (toolName) {
            NexusToolDiscoveryCatalog.SEARCH -> search(arguments)
            NexusToolDiscoveryCatalog.DESCRIBE -> describe(arguments)
            NexusToolDiscoveryCatalog.ACTIVATE -> activate(arguments)
            else -> null
        }
    }

    private fun search(arguments: JSONObject): JSONObject {
        val query = arguments.optString("query").trim()
        val limit = arguments.optInt("limit", DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        val local = directory.list(query)
            .asSequence()
            .filter { toolAllowed(it.id) }
            .map { summary ->
                JSONObject()
                    .put("tool_id", summary.id)
                    .put("description", summary.description)
                    .put("effect", summary.effectClass.name)
                    .put("available", summary.available)
                    .put("active", summary.id in activeToolIds())
            }
        val external = additional.values.asSequence()
            .mapNotNull(::externalDescriptor)
            .filter { descriptor -> toolAllowed(descriptor.id) }
            .filter { descriptor ->
                query.isBlank() || listOf(descriptor.id, descriptor.namespace, descriptor.shortDescription)
                    .any { it.contains(query, ignoreCase = true) }
            }
            .map { descriptor ->
                JSONObject()
                    .put("tool_id", descriptor.id)
                    .put("description", descriptor.shortDescription)
                    .put("effect", descriptor.effectClass.name)
                    .put("available", true)
                    .put("active", descriptor.id in activeToolIds())
            }
        val results = (local + external).distinctBy { it.getString("tool_id") }.take(limit).toList()
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("tools", JSONArray(results))
            .put("hint", if (results.isEmpty()) "没有匹配工具；尝试更短的关键词或 tool_describe" else "选择工具后先 tool_activate，再调用领域工具")
    }

    private fun describe(arguments: JSONObject): JSONObject {
        val toolId = arguments.optString("tool_id").trim()
        require(toolId.isNotBlank()) { "tool_id 不能为空" }
        if (!toolAllowed(toolId)) return error("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", "工具不在本次运行权限范围内")
        val descriptor = descriptor(toolId) ?: return error("UNKNOWN_TOOL", "未知工具：$toolId")
        val summary = directory.list(toolId).firstOrNull { it.id == toolId }
        return JSONObject()
            .put("ok", true)
            .put("tool_id", descriptor.id)
            .put("description", descriptor.modelDescription)
            .put("effect", descriptor.effectClass.name)
            .put("replay_policy", descriptor.replayPolicy.name)
            .put("required_capabilities", JSONArray(descriptor.requiredCapabilities.toList()))
            .put("available", summary?.available == true)
            .put("active", descriptor.id in activeToolIds())
    }

    private fun activate(arguments: JSONObject): JSONObject {
        val toolId = arguments.optString("tool_id").trim()
        require(toolId.isNotBlank()) { "tool_id 不能为空" }
        if (!toolAllowed(toolId)) return error("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", "工具不在本次运行权限范围内")
        val localSummary = directory.list(toolId).firstOrNull { it.id == toolId }
        val descriptor = descriptor(toolId) ?: return error("UNKNOWN_TOOL", "未知工具：$toolId")
        if (localSummary != null && !localSummary.available) {
            return error("CAPABILITY_UNAVAILABLE", "工具当前不可用：$toolId")
        }
        when (val decision = authorize(descriptor)) {
            CapabilityAuthorization.Allowed -> Unit
            is CapabilityAuthorization.Denied -> return error(decision.code, decision.reason)
        }
        val activation = synchronized(active) {
            if (toolId !in active && active.size >= MAX_ACTIVE_TOOLS) {
                return error("TOOL_ACTIVATION_LIMIT", "本次运行最多同时激活 $MAX_ACTIVE_TOOLS 个领域工具")
            }
            active.getOrPut(toolId) {
                if (localSummary != null) directory.activate(toolId, Long.MAX_VALUE)
                else CapabilityActivation(toolId, descriptor.inputSchemaRef, Long.MAX_VALUE)
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("tool_id", activation.toolId)
            .put("effect", descriptor.effectClass.name)
            .put("active", true)
            .put("message", "工具已激活；下一轮模型请求将包含完整参数 Schema")
    }

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private fun descriptor(toolId: String): ToolDescriptor? =
        directory.describe(toolId) ?: additional[toolId]?.let(::externalDescriptor)

    private fun externalDescriptor(schema: JSONObject): ToolDescriptor? {
        val function = schema.optJSONObject("function") ?: return null
        val id = function.optString("name").takeIf(String::isNotBlank) ?: return null
        val description = function.optString("description", id)
        val effect = NexusToolEffectClassifier.classify(id)
        return ToolDescriptor(
            id = id,
            namespace = id.substringBefore('_', "external"),
            shortDescription = description.take(180),
            modelDescription = description,
            inputSchemaRef = "nexus-tool://$id/input",
            effectClass = effect,
            replayPolicy = NexusToolEffectClassifier.replayPolicy(effect),
            timeoutMs = 60_000,
            hostKind = if (id.startsWith("mcp_")) HostKind.MCP else HostKind.NATIVE_ANDROID,
        )
    }

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 8
        const val MAX_SEARCH_LIMIT = 20
        const val MAX_ACTIVE_TOOLS = 16
    }
}

internal object NexusToolDiscoveryCatalog {
    const val SEARCH = "tool_search"
    const val DESCRIBE = "tool_describe"
    const val ACTIVATE = "tool_activate"
    val NAMES = setOf(SEARCH, DESCRIBE, ACTIVATE)

    fun schemas(): JSONArray = JSONArray()
        .put(function(
            name = SEARCH,
            description = "Search Nexus capabilities without loading their full schemas. Use short task keywords. This does not execute or activate a tool.",
            properties = JSONObject()
                .put("query", string("Task keywords, for example: files, screen, calendar, terminal", minLength = 1))
                .put("limit", integer("Maximum results", 1, 20, default = 8)),
            required = arrayOf("query"),
        ))
        .put(function(
            name = DESCRIBE,
            description = "Read capability metadata, required device capabilities, effect class and replay policy. This does not execute the tool.",
            properties = JSONObject().put("tool_id", string("Exact tool id returned by tool_search")),
            required = arrayOf("tool_id"),
        ))
        .put(function(
            name = ACTIVATE,
            description = "Activate one discovered tool for this run. Its full JSON schema becomes available on the next model round. Activation never grants permissions.",
            properties = JSONObject().put("tool_id", string("Exact available tool id")),
            required = arrayOf("tool_id"),
        ))

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String>,
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required.toList()))
                .put("additionalProperties", false)))

    private fun string(description: String, minLength: Int = 1): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)
        .put("minLength", minLength)

    private fun integer(description: String, minimum: Int, maximum: Int, default: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)
        .put("minimum", minimum)
        .put("maximum", maximum)
        .put("default", default)
}
