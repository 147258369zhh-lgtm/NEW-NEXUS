package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.cloud.CloudToolAvailability
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal data class RunHostBinding(val id: HostId, val tools: List<ToolDescriptor>, val schemas: JSONArray,
    val executor: CapabilityToolProvider, val available: (String) -> Boolean = { true },
    val maxConcurrentCalls: Int = 2, val displayName: String = id.value) {
    init { require(maxConcurrentCalls in 1..8) }
}
internal data class RunLocalHostBinding(val adapter: HostAdapter, val name: String, val accepts: (String, JSONObject) -> Boolean,
    val metadata: () -> JSONObject = { JSONObject() })

/** Uses existing executors, while one registered owner supplies discovery, execution and close. */
internal class NexusRunHosts(private val native: CapabilityDirectory, bindings: List<RunHostBinding>, private val localHosts: List<RunLocalHostBinding> = emptyList(),
    private val captureResult: (AgentModelClient.ToolCall, AgentModelClient.ToolResult) -> AgentModelClient.ToolResult = { _, result -> result },
    private val beforeDispatch: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult? = { null }) :
    CapabilityDirectory, CapabilityToolProvider {
    private data class Entry(val binding: RunHostBinding, val descriptor: ToolDescriptor, val schema: JSONObject)
    private class Monitor(maximum: Int) {
        val slots = Semaphore(maximum, true)
        val active = AtomicInteger()
        val waiting = AtomicInteger()
        @Volatile var state = HostStatus.READY
        @Volatile var lastErrorCode: String? = null
    }
    private val closed = AtomicBoolean(false)
    private val adapters = HostAdapterRegistry()
    private val monitors = java.util.concurrent.ConcurrentHashMap<HostId, Monitor>()
    private val bindings = bindings.toList()
    private val entries = linkedMapOf<String, Entry>()

    init {
        adapters.register(object : HostAdapter {
            override val id = HostId("native-android")
            override fun status() = if (closed.get()) HostStatus.STOPPED else HostStatus.READY
            override fun listTools() = native.list().mapNotNull { native.describe(it.id) }
        })
        localHosts.forEach { binding -> adapters.register(object : HostAdapter {
            override val id = binding.adapter.id
            override fun status() = if (closed.get()) HostStatus.STOPPED else binding.adapter.status()
            override fun listTools() = binding.adapter.listTools()
        }) }
        for (binding in bindings) {
            val schemaById = buildMap {
                for (index in 0 until binding.schemas.length()) {
                    val function = binding.schemas.getJSONObject(index).getJSONObject("function")
                    check(put(function.getString("name"), function.getJSONObject("parameters")) == null) { "HOST_SCHEMA_DUPLICATE" }
                }
            }
            require(schemaById.keys == binding.tools.map { it.id }.toSet()) { "HOST_SCHEMA_MISMATCH" }
            for (tool in binding.tools) {
                check(native.describe(tool.id) == null && tool.id !in entries) { "HOST_TOOL_ID_CONFLICT" }
                entries[tool.id] = Entry(binding, tool, checkNotNull(schemaById[tool.id]))
            }
            monitors[binding.id] = Monitor(binding.maxConcurrentCalls)
            adapters.register(object : HostAdapter {
                override val id = binding.id
                override fun status() = if (closed.get()) HostStatus.STOPPED else monitors[id]?.state ?: HostStatus.STOPPED
                override fun listTools() = binding.tools
            })
        }
    }

    fun descriptor(id: String): ToolDescriptor? = entries[id]?.descriptor ?: native.describe(id)
    fun descriptor(id: String, arguments: JSONObject): ToolDescriptor? = entries[id]?.descriptor
        ?: localHosts.firstOrNull { it.accepts(id, arguments) }?.adapter?.listTools()?.firstOrNull { it.id == id } ?: native.describe(id)
    fun schema(id: String): JSONObject? = entries[id]?.schema?.let { JSONObject(it.toString()) }
    fun hostId(id: String): HostId? = entries[id]?.binding?.id
    fun hostId(id: String, arguments: JSONObject): HostId = entries[id]?.binding?.id
        ?: localHosts.firstOrNull { it.accepts(id, arguments) }?.adapter?.id ?: HostId("native-android")
    fun isAvailable(id: String): Boolean = !closed.get() && (entries[id]?.let {
        runCatching { it.binding.available(id) }.getOrDefault(false)
    } ?: if (CloudToolAvailability.isCloudTool(id)) {
        // The native cloud directory checks current settings and dedicated credentials. The same
        // predicate gates discovery, activation, child runs and the execution preflight.
        native.list(id).firstOrNull { it.id == id }?.available == true
    } else true)
    fun isAvailable(id: String, arguments: JSONObject): Boolean = isAvailable(id) &&
        (localHosts.firstOrNull { it.accepts(id, arguments) }?.adapter?.status()?.let { it == HostStatus.READY } ?: true)
    override fun contains(toolName: String) = toolName in entries
    override fun describe(toolId: String) = descriptor(toolId)

    override fun list(query: String): List<CapabilitySummary> {
        val needle = query.trim()
        return if (closed.get()) emptyList() else native.list(query) + entries.values.filter { entry ->
            needle.isEmpty() || listOf(entry.descriptor.id, entry.descriptor.namespace, entry.descriptor.modelDescription)
                .any { it.contains(needle, ignoreCase = true) }
        }.map { CapabilitySummary(it.descriptor.id, it.descriptor.namespace, it.descriptor.shortDescription,
            it.descriptor.effectClass, isAvailable(it.descriptor.id), false) }
    }

    override fun activate(toolId: String, expiresAt: Long): CapabilityActivation {
        check(!closed.get() && isAvailable(toolId)) { "HOST_TOOL_UNAVAILABLE" }
        val entry = entries[toolId] ?: return native.activate(toolId, expiresAt)
        require(expiresAt > System.currentTimeMillis())
        return CapabilityActivation(toolId, entry.descriptor.inputSchemaRef, expiresAt)
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val entry = entries[toolCall.name] ?: return failure("HOST_TOOL_UNKNOWN")
        if (!isAvailable(toolCall.name)) return failure("HOST_TOOL_UNAVAILABLE")
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }.getOrNull() ?: return failure("INVALID_TOOL_ARGUMENT")
        try { io.github.mangi.eta.agent.control.EtaControlSchema.validate(entry.schema, arguments) }
        catch (_: io.github.mangi.eta.agent.control.EtaControlException) { return failure("INVALID_TOOL_ARGUMENT") }
        val monitor = checkNotNull(monitors[entry.binding.id])
        if (!acquireSlot(entry, monitor)) return failure(if (isAvailable(toolCall.name)) "HOST_BUSY" else "HOST_TOOL_UNAVAILABLE")
        monitor.active.incrementAndGet()
        try {
            if (!isAvailable(toolCall.name)) return failure("HOST_TOOL_UNAVAILABLE")
            io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller?.throwIfCancelled()
            beforeDispatch(toolCall)?.let { return it }
            val result = try { entry.binding.executor.execute(toolCall) }
            catch (cancelled: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { throw cancelled }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (pause: io.github.mangi.eta.agent.runtime.AgentRunPauseException) { throw pause }
            catch (_: Exception) { failure("HOST_CALL_FAILED") }
            val payload = runCatching { JSONObject(result.content) }.getOrNull()
            val successful = payload?.optBoolean("ok", true) != false
            monitor.state = if (successful) HostStatus.READY else HostStatus.DEGRADED
            monitor.lastErrorCode = if (successful) null else payload?.optString("code")
                ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "HOST_CALL_FAILED"
            return captureResult(toolCall, result)
        } finally {
            monitor.active.decrementAndGet()
            monitor.slots.release()
        }
    }

    /** Wait only before dispatch; an uncertain external result is never replayed here. */
    private fun acquireSlot(entry: Entry, monitor: Monitor): Boolean {
        val controller = io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller
        controller?.throwIfCancelled()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(entry.descriptor.timeoutMs.coerceIn(1L, 15_000L))
        monitor.waiting.incrementAndGet()
        try {
            while (isAvailable(entry.descriptor.id)) {
                controller?.throwIfCancelled()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                if (monitor.slots.tryAcquire(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)) return true
            }
            return false
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            controller?.throwIfStopped()
            throw kotlinx.coroutines.CancellationException("Host admission interrupted").also { it.initCause(interrupted) }
        } finally {
            monitor.waiting.decrementAndGet()
        }
    }

    fun snapshot(): List<JSONObject> = adapters.list().map { adapter ->
        val binding = bindings.firstOrNull { it.id == adapter.id }
        val monitor = monitors[adapter.id]
        val local = localHosts.firstOrNull { it.adapter.id == adapter.id }
        JSONObject().put("hostId", adapter.id.value).put("state", adapter.status().name)
            .put("name", binding?.displayName ?: local?.name ?: "Android")
            .put("active_calls", monitor?.active?.get() ?: 0)
            .put("waiting_calls", monitor?.waiting?.get() ?: 0)
            .put("max_concurrent_calls", binding?.maxConcurrentCalls ?: 0)
            .put("last_error_code", monitor?.lastErrorCode ?: JSONObject.NULL)
            .put("tools", JSONArray(adapter.listTools().map { it.id }))
            .apply { local?.let { put("metadata", it.metadata()) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        bindings.map { it.executor }.distinct().forEach { runCatching { it.close() } }
    }

    private fun failure(code: String) = AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", code)
        .put("message", when (code) {
            "HOST_BUSY" -> "等待该连接的并发空位超时，本次未执行"
            "HOST_CALL_FAILED" -> "外部调用未取得成功回执，请核对状态，不要自动重复提交"
            else -> "外部工具不可用或参数无效，本次未执行"
        }).toString(), sensitive = true)
}

internal object RunHostIdentity {
    fun of(namespace: String, id: String): HostId = HostId("$namespace-" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) })
}
