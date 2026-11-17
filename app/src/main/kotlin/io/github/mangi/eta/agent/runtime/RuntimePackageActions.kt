package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentRunBudgetGuard
import io.github.mangi.eta.agent.packages.CapabilityPackage
import io.github.mangi.eta.agent.packages.PackageRunSnapshot
import io.github.mangi.eta.agent.packages.PackageTool
import org.json.JSONObject
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass

/** A direct package action uses the same Runtime/Gate/receipts without paying for an LLM turn. */
internal object RuntimePackageActions {
    const val SOURCE = "plugin_action"
    fun request(record: CapabilityPackage, tool: PackageTool, arguments: JSONObject, subject: ConversationSubject? = null,
        deliveryRunId: String? = null): AgentRuntimeWire.RunRequest {
        require(record.enabled && tool in record.tools)
        io.github.mangi.eta.agent.control.EtaControlSchema.validate(JSONObject(tool.inputSchema), arguments)
        val name = PackageRunSnapshot.toolName(record.id, tool)
        require(deliveryRunId == null || deliveryRunId.matches(Regex("package-event-[a-f0-9-]{36}")))
        val id = deliveryRunId ?: "package-action-${java.util.UUID.randomUUID()}"
        require(subject == null || subject.kind != ConversationSubject.Kind.NEW_CHAT)
        val payload = JSONObject().put("tool", name).put("arguments", arguments)
        subject?.let { payload.put("subject_kind", it.kind.value).put("subject_id", it.id) }
        val automatic = tool.callbackKey?.substringBefore('/') in io.github.mangi.eta.agent.packages.PackageExtensionKinds.automatic
        val readOnly = automatic || tool.callbackKey?.substringBefore('/') in io.github.mangi.eta.agent.packages.PackageExtensionKinds.ui && arguments.optString("actionId").isBlank()
        return AgentRuntimeWire.RunRequest(id, tool.description.ifBlank { record.title },
            AgentModelClient.ModelConfig(baseUrl = "https://local.nexus.invalid", apiKey = "", model = "package-runtime", systemPrompt = "",
                terminalTools = true, browserTools = true, deviceDirectTools = true, deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true, subagentTools = !readOnly && "subagent" in record.allowedTools,
                supportsTools = true, autoCompactionEnabled = false),
            images = emptyList(), operation = AgentRuntimeWire.OP_PACKAGE_ACTION,
            handoff = AgentRuntimeWire.EntryHandoff(id, SOURCE, payload.toString()),
            modelSessionId = subject?.key ?: "package:${record.id}", toolPolicy = AgentToolPolicy(allowedTools =
                ((if (readOnly) record.allowedTools.filter { it != "request_user_input" && io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(it) ==
                    io.github.zhh_lgtm.nexus.kernel.api.EffectClass.READ } else record.allowedTools) + name).sorted()))
    }
    /** Read-only plugin rendering can finish while its original client remains bound, including an OS widget worker. */
    fun isReadOnlyPresentation(context: android.content.Context, request: AgentRuntimeWire.RunRequest): Boolean = runCatching {
        if (request.operation != AgentRuntimeWire.OP_PACKAGE_ACTION || request.handoff?.source != SOURCE) return@runCatching false
        val payload = JSONObject(request.handoff.payload)
        if (payload.getJSONObject("arguments").optString("actionId").isNotBlank()) return@runCatching false
        val name = payload.getString("tool")
        val candidate = io.github.mangi.eta.agent.packages.CapabilityPackageStore(context).list().filter { it.enabled }
            .flatMap { record -> record.tools.filter { PackageRunSnapshot.toolName(record.id, it) == name } }
            .singleOrNull() ?: return@runCatching false
        candidate.callbackKey?.substringBefore('/') in io.github.mangi.eta.agent.packages.PackageExtensionKinds.ui + io.github.mangi.eta.agent.packages.PackageExtensionKinds.automatic &&
            request.toolPolicy.allowedTools?.all { it == name || it != "request_user_input" &&
                io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(it) == EffectClass.READ } == true
    }.getOrDefault(false)
    fun subject(handoff: AgentRuntimeWire.EntryHandoff?): ConversationSubject? {
        if (handoff?.source != SOURCE) return null
        val payload = JSONObject(handoff.payload)
        if (!payload.has("subject_id")) return null
        val id = payload.getString("subject_id").also { require(it.isNotBlank() && it.length <= 512) }
        return when (payload.getString("subject_kind")) { "chat" -> ConversationSubject.chat(id); "meeting" -> ConversationSubject.meeting(id); else -> error("PACKAGE_ACTION_SUBJECT_INVALID") }
    }

    fun execute(request: AgentRuntimeWire.RunRequest, executor: AgentModelClient.ToolExecutor, guard: AgentRunBudgetGuard,
        controller: AgentRunController, event: (AgentEvent) -> Unit): AgentModelClient.ModelResponse.Text {
        require(request.operation == AgentRuntimeWire.OP_PACKAGE_ACTION && request.handoff?.source == SOURCE)
        val payload = JSONObject(request.handoff.payload)
        val name = payload.getString("tool")
        require(name.matches(Regex("pkg_[a-f0-9]{36}")) && request.toolPolicy.isAllowed(name)) { "PACKAGE_ACTION_NOT_REGISTERED" }
        val args = payload.getJSONObject("arguments").toString()
        val call = AgentModelClient.ToolCall("package-direct", name, args, round = 1)
        controller.throwIfCancelled()
        guard.beforeToolCall()
        event(AgentEvent.RoundStarted(1, messageCount = 0))
        event(AgentEvent.ToolStarted(1, call.id, name, argsPreview = "{}"))
        val result = io.github.mangi.eta.agent.packages.PackageUiOutput.withSink({ event(it) }) { executor.execute(call) }
        val json = runCatching { JSONObject(result.content) }.getOrNull()
        val successful = json?.optBoolean("ok") == true
        event(AgentEvent.ToolFinished(1, call.id, name, """{"ok":$successful}""", result.images.size, result.images.sumOf { it.bytes }, successful))
        // Preserve the settled tool receipt even when stopping the enclosing Run.
        controller.throwIfCancelled()
        if (json == null) throw AgentModelFailure("PACKAGE_ACTION_INVALID_RESULT", false, "扩展包返回了无法识别的结果")
        if (!successful) throw AgentModelFailure(json.optString("code", "PACKAGE_ACTION_FAILED"), false, json.optString("message", "扩展包操作未完成"))
        return AgentModelClient.ModelResponse.Text(result.content)
    }
}
