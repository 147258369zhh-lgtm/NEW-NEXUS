package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.control.EtaAppControlAuditStore
import io.github.mangi.eta.agent.control.SettingsControlOrigin
import io.github.mangi.eta.agent.control.SettingsControlSnapshots
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface PackageHookRunner {
    fun invoke(source: File, timeoutMs: Long, memoryBytes: Long, host: (String, JSONObject) -> JSONObject): JSONObject
}

/** Hooks reuse the existing isolated engine and current Run; no callback is evaluated in the UI. */
internal class PackageHookInvoker(context: Context, private val controller: AgentRunController,
    private val native: AgentModelClient.ToolExecutor,
    private val readAllowed: (String, JSONObject) -> Boolean,
    private val runner: PackageHookRunner? = null,
) : AutoCloseable {
    private val context = context.applicationContext
    private val store = CapabilityPackageStore(context)
    private val clients = CopyOnWriteArraySet<PackageSandboxClient>()
    private val closed = AtomicBoolean(false)
    fun invoke(record: CapabilityPackage, row: JSONObject, eventName: String, eventPayload: JSONObject, timeoutMs: Long): Any? {
        controller.throwIfCancelled(); check(!closed.get()) { "PACKAGE_HOOK_CLOSED" }
        val kind = row.getString("kind")
        val output = PackageMessageOutput.sink.takeIf { kind == "MessageProcessingPlugin" && !eventPayload.optBoolean("probeOnly") }
        var outputChars = 0L
        val current = store.list().singleOrNull { it.id == record.id && it.enabled && it.digest == record.digest && kind in it.enabledExtensions }
            ?: error("PACKAGE_HOOK_REVOKED")
        val callback = requireNotNull(PackageExtensionKinds.callback(row)) { "PACKAGE_HOOK_CALLBACK_INVALID" }
        val event = JSONObject().put("event", family(kind)).put("eventName", eventName).put("pluginId", row.getString("id"))
            .put("toolPkgId", record.id).put("eventPayload", eventPayload)
        require(event.toString().toByteArray().size <= 512 * 1024) { "PACKAGE_HOOK_CONTEXT_LIMIT" }
        val temporary = File.createTempFile("package-hook-", ".json", context.cacheDir)
        val client = if (runner == null) PackageSandboxClient(context).also(clients::add) else null
        val audit = EtaAppControlAuditStore(context)
        val metadata = JSONObject().put("package_id", record.id).put("digest", record.digest).put("hook", row.getString("id")).put("kind", kind).put("event", eventName)
            .put("input_hash", SettingsControlSnapshots.hash(eventPayload))
        val origin = SettingsControlOrigin(SettingsActorKind.LOCAL_AGENT, record.id, controller.executionIdentity?.runId?.value, "package_hook")
        val started = System.nanoTime()
        audit.append("package_hook_started", "packages.hook", metadata, false, durationMs = 0, origin = origin)
        return try {
            val source = store.source(current).put("entry", requireNotNull(record.mainEntry)).put("main_entry", record.mainEntry)
                .put("package_id", record.id).put("api_version", record.apiVersion).put("function", "__nexus_callback__")
                .put("stream_mode", output != null)
                .put("arguments", JSONObject().put("key", callback).put("event", event))
            val bytes = source.toString().toByteArray(); require(bytes.size <= 8 * 1024 * 1024) { "PACKAGE_SOURCE_LIMIT" }; temporary.writeBytes(bytes)
            val callHost: (String, JSONObject) -> JSONObject = { name, args ->
                controller.throwIfCancelled()
                val live = store.list().singleOrNull { it.id == record.id && it.enabled && it.digest == record.digest && kind in it.enabledExtensions }
                if (live == null) JSONObject().put("ok", false).put("code", "PACKAGE_HOOK_REVOKED")
                else if (name == "package:message-output") {
                    check(output != null) { "PACKAGE_MESSAGE_OUTPUT_NOT_EXPECTED" }
                    val text = args.getJSONObject("value").get("chunk") as? String ?: error("PACKAGE_MESSAGE_CHUNK_REQUIRED")
                    require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 && outputChars + text.length <= 256_000) { "PACKAGE_MESSAGE_OUTPUT_LIMIT" }
                    outputChars += text.length; output(text)
                    JSONObject().put("ok", true)
                }
                else if (name == "package:state") PackageStateStore(context).invoke(record.id, args,
                    writable = !(kind == "MessageProcessingPlugin" && eventPayload.optBoolean("probeOnly")))
                else if (name == "package:environment") JSONObject().put("ok", true).put("value", PackageEnvironmentStore(context).value(record.id, args.getString("name")) ?: "")
                else {
                    fun invokeNative(tool: String, parameters: JSONObject): JSONObject {
                        if (tool !in live.allowedTools) return JSONObject().put("ok", false).put("code", "PACKAGE_HOOK_TOOL_NOT_GRANTED")
                        if (!readAllowed(tool, parameters)) {
                            // Probes, prompt transforms and observers never perform hidden writes.
                            if (kind != "MessageProcessingPlugin" || eventPayload.optBoolean("probeOnly"))
                                return JSONObject().put("ok", false).put("code", "PACKAGE_HOOK_TOOL_NOT_GRANTED")
                            val approved = runBlocking(RuntimeInvocationScope.coroutineContext()) {
                                EtaAppControlConfirmation.awaitApproval(EtaControlCapabilityInfo(
                                    "packages.automatic_write", "插件请求执行操作", "插件", "${record.title} 请求调用 $tool",
                                    EtaControlDanger.CONFIRM, JSONObject(), false),
                                    JSONObject().put("package", record.title).put("tool", tool).put("arguments", parameters), controller = controller)
                            }
                            if (!approved) return JSONObject().put("ok", false).put("code", "PACKAGE_HOOK_WRITE_REJECTED")
                            controller.throwIfCancelled()
                            if (store.list().none { it.id == record.id && it.enabled && it.digest == record.digest && kind in it.enabledExtensions && tool in it.allowedTools })
                                return JSONObject().put("ok", false).put("code", "PACKAGE_HOOK_REVOKED")
                        }
                        val result = inside { native.execute(AgentModelClient.ToolCall("package-hook-${java.util.UUID.randomUUID()}", tool, parameters.toString())) }
                        return runCatching { JSONObject(result.content) }.getOrElse { JSONObject().put("ok", true).put("text", result.content) }
                    }
                    if (name.startsWith("operit:")) OperitToolAdapter.invoke(name.removePrefix("operit:"), args, ::invokeNative) else invokeNative(name, args)
                }
            }
            val response = RuntimeInvocationScope.withController(controller) {
                val maximum = if (kind == "MessageProcessingPlugin" && !eventPayload.optBoolean("probeOnly")) current.timeoutSeconds * 1000L else 10_000L
                if (runner != null) runner.invoke(temporary, timeoutMs.coerceIn(1000, maximum), current.memoryMb * 1024L * 1024, callHost)
                else requireNotNull(client).invoke(temporary, timeoutMs.coerceIn(1000, maximum), current.memoryMb * 1024L * 1024, callHost)
            }
            controller.throwIfCancelled()
            check(store.list().any { it.id == record.id && it.enabled && it.digest == record.digest && kind in it.enabledExtensions }) { "PACKAGE_HOOK_REVOKED" }
            check(response.optBoolean("ok")) { response.optString("message", "PACKAGE_HOOK_FAILED").take(300) }
            audit.append("package_hook_finished", "packages.hook", metadata, true, durationMs = (System.nanoTime() - started) / 1_000_000, origin = origin)
            response.opt("value")?.takeUnless { it == JSONObject.NULL }
        } catch (failure: Exception) {
            audit.append("package_hook_finished", "packages.hook", metadata, false, errorCode = failure.javaClass.simpleName,
                durationMs = (System.nanoTime() - started) / 1_000_000, origin = origin)
            throw failure
        } finally { client?.close(); if (client != null) clients.remove(client); temporary.delete() }
    }
    override fun close() { closed.set(true); clients.forEach { it.close() }; clients.clear() }
    companion object {
        private val nesting = ThreadLocal<Int>()
        val insideHook get() = (nesting.get() ?: 0) > 0
        private fun <T> inside(action: () -> T): T { val old = nesting.get() ?: 0; nesting.set(old + 1); return try { action() } finally { if (old == 0) nesting.remove() else nesting.set(old) } }
        private fun family(kind: String) = "toolpkg_" + kind.removeSuffix("Hook").removeSuffix("Plugin")
            .replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
}
