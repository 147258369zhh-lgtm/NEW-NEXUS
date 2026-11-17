package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.application.api.CapabilityActivation
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
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
    validUntil: Long = System.currentTimeMillis() + 60L * 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val validUntilProvider: (() -> Long)? = null,
) {
    private val initialValidUntil = validUntil
    private val validUntil: Long get() = validUntilProvider?.invoke() ?: initialValidUntil
    private val active = linkedMapOf<String, CapabilityActivation>()
    private val additional = linkedMapOf<String, JSONObject>().also { tools ->
        for (index in 0 until additionalSchemas.length()) {
            val schema = additionalSchemas.optJSONObject(index) ?: continue
            val function = schema.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isNotBlank()) tools[name] = JSONObject(schema.toString())
        }
    }

    fun enabled(): Boolean = now() < validUntil && discoveryAllowed()

    /** Signed providers need a stable catalogue, with the same grant/capability checks. */
    fun authorizedSchemas(schemas: JSONArray): JSONArray = JSONArray().also { allowed ->
        if (now() >= validUntil) return@also
        for (index in 0 until schemas.length()) {
            val schema = schemas.optJSONObject(index) ?: continue
            val id = schema.optJSONObject("function")?.optString("name") ?: continue
            if (toolAllowed(id) && descriptor(id)?.let { authorize(it) == CapabilityAuthorization.Allowed } == true) {
                allowed.put(JSONObject(schema.toString()))
            }
        }
    }

    /** Recover schemas, never tool execution or old grants. All current checks still apply. */
    fun restoreUsedTools(history: List<AgentModelClient.ConversationMessage>) {
        if (!enabled()) return
        val calls = linkedMapOf<String, AgentModelClient.ToolCall>()
        val recentlyUsed = linkedSetOf<String>()
        history.forEach { message ->
            if (message.role == "assistant") {
                runCatching { AgentConversationCodec.parseToolCalls(AgentConversationCodec.toJsonObject(message)) }
                    .getOrDefault(emptyList()).forEach { calls[it.id] = it }
            } else if (message.role == "tool") {
                val call = calls.remove(message.toolCallId) ?: return@forEach
                val result = runCatching { JSONObject(message.content) }.getOrNull() ?: return@forEach
                if (!result.optBoolean("ok")) return@forEach
                val id = if (call.name == NexusToolDiscoveryCatalog.ACTIVATE) {
                    val requested = runCatching { JSONObject(call.argumentsJson).optString("tool_id") }.getOrDefault("")
                    val replaced = result.optString("replaced_tool_id")
                    if (replaced.isNotBlank() && result.optBoolean("active") && result.optString("tool_id") == requested) {
                        recentlyUsed.remove(replaced)
                    }
                    result.optString("tool_id").takeIf { it == requested && result.optBoolean("active") }
                } else call.name.takeUnless { it in NexusToolDiscoveryCatalog.NAMES }
                if (!id.isNullOrBlank()) { recentlyUsed.remove(id); recentlyUsed.add(id) }
            }
        }
        recentlyUsed.toList().takeLast(MAX_ACTIVE_TOOLS).forEach { id ->
            try {
                activate(JSONObject().put("tool_id", id))
            } catch (cancelled: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) {
                throw cancelled
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                // A connection or capability can disappear while restoring its schema.
            } catch (_: IllegalArgumentException) {
                // Stale history cannot make an unavailable capability executable.
            }
        }
    }

    fun activeToolIds(): Set<String> {
        val snapshot = synchronized(active) { active.toMap() }
        val usable = snapshot.filter { (id, activation) ->
            now() < minOf(validUntil, activation.expiresAt) && toolAllowed(id) &&
                descriptor(id)?.let { authorize(it) == CapabilityAuthorization.Allowed } == true
        }.keys
        synchronized(active) {
            snapshot.forEach { (id, activation) -> if (id !in usable && active[id] == activation) active.remove(id) }
        }
        return usable
    }

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
        if (now() >= validUntil) return error("GRANT_EXPIRED", "本次任务授权已过期")
        if (!enabled()) return error("TOOL_DISCOVERY_DISABLED", "本次运行未开放能力发现")
        val schema = NexusToolDiscoveryCatalog.schemas().let { schemas ->
            (0 until schemas.length()).map { schemas.getJSONObject(it).getJSONObject("function") }
                .single { it.getString("name") == toolName }.getJSONObject("parameters")
        }
        try { io.github.mangi.eta.agent.control.EtaControlSchema.validate(schema, arguments) }
        catch (invalid: io.github.mangi.eta.agent.control.EtaControlException) { return error("INVALID_TOOL_ARGUMENT", invalid.message) }
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
        val activeIds = activeToolIds()
        // Take one current snapshot: a multiword query must not repeatedly probe every host,
        // resolve credentials or re-authorize the entire active set for every search result.
        val local = directory.list()
        val external = additional.values.asSequence()
            .mapNotNull(::externalDescriptor)
            .map { descriptor ->
                io.github.zhh_lgtm.nexus.application.api.CapabilitySummary(descriptor.id,
                    descriptor.namespace, descriptor.shortDescription, descriptor.effectClass, true, false)
            }.toList()
        val candidates = (local + external).distinctBy { it.id }.filter { toolAllowed(it.id) }
        val results = CapabilitySearch.rank(query, candidates).take(limit).map { summary ->
            JSONObject().put("tool_id", summary.id).put("description", summary.description)
                .put("effect", summary.effectClass.name).put("available", summary.available)
                .put("active", summary.id in activeIds)
        }
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
            .put("parameters", inputSchema(toolId) ?: JSONObject())
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
        if (now() >= validUntil) return error("GRANT_EXPIRED", "本次任务授权已过期")
        activeToolIds()
        val replacement = arguments.optString("replace_tool_id").trim().takeIf(String::isNotBlank)
        val activation = synchronized(active) {
            if (replacement != null && replacement != toolId && replacement !in active) {
                return error("TOOL_REPLACEMENT_NOT_ACTIVE", "要替换的工具当前没有激活")
            }
            if (toolId !in active && active.size >= MAX_ACTIVE_TOOLS && (replacement == null || replacement == toolId)) {
                return error("TOOL_ACTIVATION_LIMIT", "最多激活 $MAX_ACTIVE_TOOLS 个工具；传 replace_tool_id 替换暂不需要的工具")
                    .put("active_tool_ids", JSONArray(active.keys.toList()))
            }
            // Validate/activate the new schema before dropping the previous one. Failed replacement
            // must not lose a working tool. Neither operation changes execution permissions.
            val value = active[toolId] ?: if (localSummary != null) directory.activate(toolId, validUntil)
                else CapabilityActivation(toolId, descriptor.inputSchemaRef, validUntil)
            if (replacement != toolId) replacement?.let(active::remove)
            active[toolId] = value
            value
        }
        return JSONObject()
            .put("ok", true)
            .put("tool_id", activation.toolId)
            .put("effect", descriptor.effectClass.name)
            .put("active", true)
            .put("replaced_tool_id", replacement?.takeIf { it != toolId } ?: JSONObject.NULL)
            .put("expires_at", activation.expiresAt)
            .put("parameters", inputSchema(toolId) ?: JSONObject())
            .put("message", "工具已激活；下一轮模型请求将包含完整参数 Schema")
    }

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private fun descriptor(toolId: String): ToolDescriptor? =
        directory.describe(toolId) ?: additional[toolId]?.let(::externalDescriptor)

    private fun inputSchema(toolId: String): JSONObject? =
        additional[toolId]?.optJSONObject("function")?.optJSONObject("parameters")?.let { JSONObject(it.toString()) }
            ?: NexusToolDescriptors.schemas[toolId]?.let { JSONObject(it.toString()) }

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
            description = "Activate one discovered tool for this run. Its full JSON schema becomes available on the next model round. Activation never grants permissions. At the active schema limit, replace an unused active tool with replace_tool_id.",
            properties = JSONObject().put("tool_id", string("Exact available tool id"))
                .put("replace_tool_id", string("Optional currently active tool id to remove from the next model request; does not cancel work or revoke execution permissions")),
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
