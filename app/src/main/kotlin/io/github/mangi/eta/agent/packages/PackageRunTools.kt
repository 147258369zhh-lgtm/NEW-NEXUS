package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.capability.RunHostBinding
import io.github.mangi.eta.agent.capability.RunHostIdentity
import io.github.mangi.eta.agent.capability.CapabilityToolProvider
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal class PackageRunSnapshot(private val packages: List<CapabilityPackage>, private val uiAction: Boolean = false) {
    fun appendModelTools(destination: JSONArray) { packages.forEach { record -> record.tools.forEach { destination.put(schema(record, it)) } } }
    fun bindings(context: Context, runner: PackageHookRunner? = null): List<RunHostBinding> {
        val store = CapabilityPackageStore(context)
        return packages.map { record ->
            RunHostBinding(RunHostIdentity.of("package", record.id), record.tools.map { tool ->
                val ui = tool.callbackKey?.substringBefore('/') in PackageExtensionKinds.ui
                val read = store.isTrustedTime(record, tool) || tool.callbackKey?.substringBefore('/') in PackageExtensionKinds.automatic || ui && !uiAction
                ToolDescriptor(toolName(record.id, tool), "package", "${record.title}: ${tool.description}".take(180),
                    tool.description.ifBlank { "${record.title} / ${tool.name}" }, "package://${record.id}/${tool.name}/input",
                    effectClass = if (read) EffectClass.READ else EffectClass.HIGH_RISK,
                    replayPolicy = if (read && !ui) ReplayPolicy.AUTOMATIC else ReplayPolicy.REQUIRE_USER,
                    timeoutMs = record.timeoutSeconds * 1000L, hostKind = HostKind.PLUGIN)
            }, JSONArray(record.tools.map { schema(record, it) }), PackageRunExecutor(context, record, runner), available = {
                store.list().any { current -> current.id == record.id && current.enabled && current.digest == record.digest &&
                    record.tools.all { tool -> tool in current.tools &&
                        (tool.callbackKey?.substringBefore('/') !in PackageExtensionKinds.automatic || tool.callbackKey?.substringBefore('/') in current.enabledExtensions) } }
            }, maxConcurrentCalls = 1, displayName = record.title)
        }
    }
    companion object {
        val EMPTY = PackageRunSnapshot(emptyList())
        fun load(context: Context, requestedTool: String? = null, uiAction: Boolean = false): PackageRunSnapshot {
            val store = CapabilityPackageStore(context)
            store.ensureBuiltinTime()
            return PackageRunSnapshot(store.list().filter { it.enabled }.sortedByDescending { record ->
                requestedTool != null && record.tools.any { toolName(record.id, it) == requestedTool }
            }.map { record -> record.copy(tools = record.tools.filter { tool ->
                tool.callbackKey?.substringBefore('/') !in PackageExtensionKinds.automatic + PackageExtensionKinds.ui ||
                    toolName(record.id, tool) == requestedTool && (tool.callbackKey?.substringBefore('/') in PackageExtensionKinds.ui ||
                        tool.callbackKey?.substringBefore('/') in record.enabledExtensions)
            }) }.filter { it.tools.isNotEmpty() }.take(32), uiAction)
        }
        fun toolName(packageId: String, tool: PackageTool): String = "pkg_" + RunHostIdentity.of("tool", "$packageId:${tool.entry}:${tool.name}").value.removePrefix("tool-").take(36)
        private fun schema(record: CapabilityPackage, tool: PackageTool) = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", toolName(record.id, tool)).put("description", "Package ${record.title}: ${tool.description}. Runs in an isolated process; native tools remain subject to the current Agent and package permissions.")
            .put("parameters", JSONObject(tool.inputSchema)))
    }
}

private class PackageRunExecutor(context: Context, private val record: CapabilityPackage, private val runner: PackageHookRunner? = null) : CapabilityToolProvider {
    private val context = context.applicationContext
    private val store = CapabilityPackageStore(context)
    private val clients = java.util.concurrent.CopyOnWriteArraySet<PackageSandboxClient>()
    private val closed = AtomicBoolean(false)
    override fun contains(toolName: String) = record.tools.any { PackageRunSnapshot.toolName(record.id, it) == toolName }
    override fun close() { if (closed.compareAndSet(false, true)) clients.forEach { it.close() } }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val controller = RuntimeInvocationScope.controller
        val invoker = RuntimeInvocationScope.nestedToolExecutor
        val temporary = File.createTempFile("package-call-", ".json", context.cacheDir)
        val client = if (runner == null) PackageSandboxClient(context).also { clients.add(it) } else null
        return try {
            check(!closed.get()) { "PACKAGE_HOST_CLOSED" }
            controller?.throwIfCancelled()
            val tool = record.tools.single { PackageRunSnapshot.toolName(record.id, it) == toolCall.name }
            val current = store.list().single { it.id == record.id && it.enabled && it.digest == record.digest }
            check(tool in current.tools) { "PACKAGE_TOOL_REVOKED" }
            val payload = store.source(record).put("entry", tool.entry)
                .put("function", tool.name).put("arguments", JSONObject(toolCall.argumentsJson.ifBlank { "{}" })).put("api_version", record.apiVersion)
                .put("main_entry", record.mainEntry ?: JSONObject.NULL).put("package_id", record.id)
            val ui = tool.callbackKey?.substringBefore('/') in PackageExtensionKinds.ui
            val uiOutput = PackageUiOutput.sink.takeIf { ui }
            var frameCount = 0
            var frameBytes = 0L
            var renderDepth = 0
            val uiAction = ui && JSONObject(toolCall.argumentsJson).optString("actionId").isNotBlank()
            if (ui) {
                val declarations = JSONArray(current.registrations)
                val declaration = (0 until declarations.length()).mapNotNull { PackageUiRegistration.describe(declarations.getJSONObject(it)) }
                    .single { it.callback == tool.callbackKey }
                val input = payload.getJSONObject("arguments")
                check(input.getString("moduleId") == declaration.id &&
                    input.optString("screenCallback").ifBlank { null } == declaration.screenCallback) { "PACKAGE_UI_DECLARATION_CHANGED" }
                payload.put("function", "__nexus_ui__")
                payload.put("stream_mode", uiOutput != null)
            } else if (tool.callbackKey != null) {
                val parts = tool.callbackKey.split('/')
                val kind = parts.firstOrNull().orEmpty()
                val automatic = kind in PackageExtensionKinds.automatic
                if (automatic) check(kind in current.enabledExtensions) { "PACKAGE_EVENT_REVOKED" }
                val args = JSONObject(toolCall.argumentsJson)
                payload.put("function", "__nexus_callback__").put("arguments", JSONObject().put("key", tool.callbackKey)
                    .put("event", JSONObject().put("event", when (kind) {
                        "AppLifecycleHook" -> args.getString("eventName")
                        "ChatViewHook" -> "toolpkg_chat_view"
                        "ChatMessageHook" -> "toolpkg_chat_message"
                        "InputMenuTogglePlugin" -> "toolpkg_input_menu_toggle"
                        "ChatMessageMenuItem" -> "toolpkg_chat_message_menu_item"
                        else -> "toolpkg_navigation_entry"
                    })
                        .put("eventName", if (automatic) args.getString("eventName") else if (kind == "ChatMessageMenuItem") "chat_message_menu_item_click" else "navigation_entry_click")
                        .put("pluginId", parts.getOrNull(1).orEmpty()).put("toolPkgId", record.id)
                        .put("deliveryId", if (automatic) controller?.executionIdentity?.runId?.value ?: "" else "")
                        .put("eventPayload", args.optJSONObject("eventPayload") ?: JSONObject())))
            }
            val bytes = payload.toString().toByteArray()
            require(bytes.size <= 8 * 1024 * 1024) { "PACKAGE_SOURCE_LIMIT" }
            temporary.writeBytes(bytes)
            val hostCall: (String, JSONObject) -> JSONObject = { name, args ->
                controller?.throwIfCancelled()
                val live = store.list().singleOrNull { it.id == record.id && it.enabled && it.digest == record.digest }
                fun invokeNative(nativeName: String, parameters: JSONObject): JSONObject {
                    val latest = store.list().singleOrNull { it.id == record.id && it.enabled && it.digest == record.digest }
                    if (latest == null || nativeName !in latest.allowedTools || invoker == null) return JSONObject().put("ok", false).put("code", "PACKAGE_TOOL_NOT_GRANTED")
                    if (tool !in latest.tools) return JSONObject().put("ok", false).put("code", "PACKAGE_TOOL_REVOKED")
                    if (ui && (!uiAction || renderDepth > 0) && (nativeName == "request_user_input" || io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(nativeName) != EffectClass.READ))
                        return JSONObject().put("ok", false).put("code", "PACKAGE_UI_RENDER_READ_ONLY")
                    val automatic = tool.callbackKey?.substringBefore('/')?.takeIf { it in PackageExtensionKinds.automatic }
                    if (automatic != null && (automatic !in latest.enabledExtensions ||
                        nativeName == "request_user_input" ||
                        io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(nativeName) != EffectClass.READ))
                        return JSONObject().put("ok", false).put("code", "PACKAGE_EVENT_READ_ONLY")
                    val nested = invoker.execute(AgentModelClient.ToolCall("package-${java.util.UUID.randomUUID()}", nativeName, parameters.toString()))
                    return runCatching { JSONObject(nested.content) }.getOrElse { JSONObject().put("ok", true).put("text", nested.content.take(12000)) }
                }
                if (live == null) {
                    JSONObject().put("ok", false).put("code", "PACKAGE_TOOL_NOT_GRANTED")
                } else if (name == "package:ui-render") {
                    check(ui && tool in live.tools) { "PACKAGE_UI_RENDER_NOT_EXPECTED" }
                    when (args.getString("phase")) {
                        "begin" -> { require(renderDepth < 4); renderDepth++ }
                        "end" -> { require(renderDepth > 0); renderDepth-- }
                        else -> error("PACKAGE_UI_RENDER_PHASE_INVALID")
                    }
                    JSONObject().put("ok", true)
                } else if (name == "package:ui-frame") {
                    check(uiOutput != null && uiAction && tool in live.tools) { "PACKAGE_UI_OUTPUT_NOT_EXPECTED" }
                    val document = PackageUiDocument.parse(args)
                    check(document.effects.isEmpty()) { "PACKAGE_UI_PREVIEW_EFFECTS_DENIED" }
                    val encoded = args.toString(); frameBytes += encoded.toByteArray(Charsets.UTF_8).size
                    require(++frameCount <= 128 && frameBytes <= 2 * 1024 * 1024) { "PACKAGE_UI_FRAME_LIMIT" }
                    uiOutput(io.github.mangi.eta.agent.runtime.AgentEvent.PluginUiFrame(record.id, record.digest,
                        JSONObject(toolCall.argumentsJson).getString("moduleId"), frameCount, encoded))
                    JSONObject().put("ok", true)
                } else if (name == "package:state") {
                    val querying = tool.callbackKey?.startsWith("InputMenuTogglePlugin/") == true &&
                        JSONObject(toolCall.argumentsJson).optJSONObject("eventPayload")?.optString("action") == "create"
                    PackageStateStore(context).invoke(record.id, args, writable = !querying && (!ui || uiAction && renderDepth == 0))
                } else if (name == "package:ui-environment") {
                    check(uiAction && renderDepth == 0 && tool in live.tools) { "PACKAGE_UI_USER_ACTION_REQUIRED" }
                    val values = args.optJSONObject("values") ?: JSONObject().put(args.getString("name"), args.getString("value"))
                    check(values.keys().asSequence().all { it in live.environmentNames }) { "PACKAGE_ENVIRONMENT_UNDECLARED" }
                    PackageEnvironmentStore(context).putValues(record.id, values)
                    JSONObject().put("ok", true)
                } else if (name == "package:environment") {
                    JSONObject().put("ok", true).put("value", PackageEnvironmentStore(context).value(record.id, args.getString("name")) ?: "")
                } else if (name.startsWith("operit:")) {
                    OperitToolAdapter.invoke(name.removePrefix("operit:"), args, ::invokeNative)
                } else {
                    invokeNative(name, args)
                }
            }
            val result = if (runner != null) runner.invoke(temporary, current.timeoutSeconds * 1000L, current.memoryMb * 1024L * 1024, hostCall)
                else requireNotNull(client).invoke(temporary, current.timeoutSeconds * 1000L, current.memoryMb * 1024L * 1024, hostCall)
            AgentModelClient.ToolResult(result.toString(), sensitive = true)
        } catch (cancelled: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { throw cancelled }
        catch (failure: Exception) {
            val limited = failure as? PackageSandboxBusyException
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", limited?.code ?: "PACKAGE_CALL_FAILED")
                .put("message", if (limited != null) "当前资源不足，扩展包本次未执行" else "扩展包执行未取得成功回执，请检查包设置与运行记录")
                .put("error_type", failure.javaClass.simpleName).toString(), sensitive = true)
        } finally { client?.close(); if (client != null) clients.remove(client); temporary.delete() }
    }
}
