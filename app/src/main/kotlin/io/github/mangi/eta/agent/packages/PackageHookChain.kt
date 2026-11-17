package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal class PackageHookChain(
    records: List<CapabilityPackage>,
    private val invoke: (CapabilityPackage, JSONObject, String, JSONObject, Long) -> Any?,
    private val controller: AgentRunController,
    private val chatId: String?,
    private val modelName: String,
    private val closeInvoker: () -> Unit = {},
    private val now: () -> Long = System::nanoTime,
) : AgentRunExtensions {
    private data class Hook(val record: CapabilityPackage, val row: JSONObject)
    private val hooks = records.filter { it.enabled && it.mainEntry != null }.flatMap { record ->
        val rows = JSONArray(record.registrations)
        (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.takeIf {
            it.optString("kind") in record.enabledExtensions && PackageExtensionKinds.callback(it) != null
        }?.let { Hook(record, it) } }
    }.take(64)
    private var rawInput = ""
    private var processedInput = ""
    private var promptDeadline: Long? = null
    private var toolNames: Set<String>? = null
    private val lock = Any()
    private var runtimeState: String? = null

    private fun dispatch(kind: String, event: String, payload: () -> JSONObject, apply: (Any?) -> Unit) {
        if (PackageHookInvoker.insideHook) return
        val selected = hooks.filter { it.row.optString("kind") == kind }
        if (selected.isEmpty()) return
        val deadline = promptDeadline ?: (now() + TimeUnit.SECONDS.toNanos(10))
        for (hook in selected) {
            controller.throwIfCancelled()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - now())
            if (remaining < 1000) throw AgentModelFailure("PACKAGE_HOOK_TIMEOUT", false, "扩展处理超时，请停用相关扩展后重试")
            val value = invoke(hook.record, hook.row, event, payload(), minOf(remaining, hook.record.timeoutSeconds * 1000L))
            if (now() > deadline) throw AgentModelFailure("PACKAGE_HOOK_TIMEOUT", false, "扩展处理超过本次时限，结果未采用")
            controller.throwIfCancelled()
            apply(value)
        }
    }
    private fun base() = JSONObject().put("chatId", chatId ?: JSONObject.NULL).put("modelName", modelName).put("useEnglish", false)
        .put("rawInput", rawInput).put("processedInput", processedInput).put("metadata", JSONObject().put("nexusRunId", controller.executionIdentity?.runId?.value ?: JSONObject.NULL))

    override fun input(prompt: String, attachmentCount: Int): String = synchronized(lock) {
        rawInput = prompt; processedInput = prompt
        promptDeadline = now() + TimeUnit.SECONDS.toNanos(10)
        dispatch("ChatInputHook", "submit_requested", { base().put("text", processedInput).put("selectionStart", processedInput.length).put("selectionEnd", processedInput.length)
            .put("hasAttachments", attachmentCount > 0).put("attachmentCount", attachmentCount).put("source", "runtime").put("submitSource", "user") }) { value ->
            val result = value as? JSONObject
            val action = result?.optString("action").orEmpty()
            if (action in setOf("block", "consume")) throw AgentModelFailure("PACKAGE_INPUT_BLOCKED", false, result?.optString("message")?.takeIf(String::isNotBlank) ?: "扩展已阻止提交")
            val text = if (value is String) value else result?.opt("text") as? String
            if (text != null) { require(text.length in 1..64_000) { "PACKAGE_HOOK_INPUT_LIMIT" }; processedInput = text }
        }
        dispatch("PromptInputHook", "before_process", { base().put("stage", "before_process") }) { value ->
            val text = if (value is String) value else (value as? JSONObject)?.opt("processedInput") as? String
            if (text != null) { require(text.length in 1..64_000); processedInput = text }
        }
        processedInput
    }

    override fun prepare(messages: JSONArray, systemCount: Int, tools: JSONArray): JSONArray = synchronized(lock) {
        if (SignedToolContext.hasPendingResponse(messages)) { promptDeadline = null; return@synchronized tools }
        val original = JSONArray(messages.toString())
        val precedingNames = toolNames
        var currentTools = JSONArray(tools.toString())
        fun payload(stage: String) = base().put("stage", stage).put("systemPrompt", messages.optJSONObject(0)?.optString("content").orEmpty())
            .put("toolPrompt", "").put("chatHistory", turns(messages)).put("preparedHistory", turns(messages))
            .put("availableTools", JSONArray((0 until currentTools.length()).map { currentTools.getJSONObject(it).getJSONObject("function") }))
        fun mutate(value: Any?, defaultKind: String) {
            val result = when (value) { is String -> JSONObject().put(if (defaultKind == "SystemPromptComposeHook") "systemPrompt" else "processedInput", value)
                is JSONArray -> JSONObject().put("preparedHistory", value); is JSONObject -> value; else -> return }
            result.optJSONArray("preparedHistory")?.let { replaceHistory(messages, it, systemCount) }
            result.optJSONArray("chatHistory")?.takeIf { !result.has("preparedHistory") }?.let { replaceHistory(messages, it, systemCount) }
            (result.opt("systemPrompt") as? String)?.let { text ->
                require(text.length in 1..128_000 && systemCount > 0) { "PACKAGE_HOOK_SYSTEM_PROMPT_LIMIT" }
                messages.getJSONObject(0).put("content", text)
            }
            (result.opt("toolPrompt") as? String)?.takeIf(String::isNotBlank)?.let { text ->
                require(text.length <= 32_000 && systemCount > 0)
                val first = messages.getJSONObject(0); first.put("content", first.optString("content") + "\n\n" + text)
            }
            (result.opt("processedInput") as? String)?.let { text ->
                require(text.length in 1..64_000)
                val index = (messages.length() - 1 downTo systemCount).firstOrNull { messages.optJSONObject(it)?.optString("role") == "user" }
                if (index != null) {
                    val message = messages.getJSONObject(index)
                    require(message.opt("content") is String) { "PACKAGE_HOOK_MULTIMODAL_INPUT_IMMUTABLE" }
                    message.put("content", text); processedInput = text
                }
            }
            result.optJSONArray("availableTools")?.let { selected ->
                val originals = (0 until currentTools.length()).associate { index -> currentTools.getJSONObject(index).getJSONObject("function").getString("name") to currentTools.getJSONObject(index) }
                val names = (0 until selected.length()).map { index -> val tool = selected.getJSONObject(index); tool.optString("name").ifBlank { tool.optJSONObject("function")?.optString("name").orEmpty() } }
                require(names.distinct().size == names.size && names.all(originals::containsKey)) { "PACKAGE_HOOK_CANNOT_GRANT_TOOLS" }
                currentTools = JSONArray(names.map(originals::getValue)); toolNames = names.toSet()
            }
        }
        if (promptDeadline == null) promptDeadline = now() + TimeUnit.SECONDS.toNanos(10)
        try {
            for ((kind, stage) in listOf("PromptHistoryHook" to "before_prepare_history", "SystemPromptComposeHook" to "after_compose_system_prompt",
                "ToolPromptComposeHook" to "after_compose_tool_prompt", "PromptFinalizeHook" to "before_send")) {
                dispatch(kind, stage, { payload(stage) }) { mutate(it, kind) }
            }
            currentTools
        } catch (failure: Exception) {
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until original.length()) messages.put(original.getJSONObject(index))
            toolNames = precedingNames
            throw failure
        } finally { promptDeadline = null }
    }
    override fun tools(tools: JSONArray): JSONArray = synchronized(lock) {
        val allowed = toolNames ?: return@synchronized tools
        JSONArray((0 until tools.length()).map(tools::getJSONObject).filter { it.getJSONObject("function").getString("name") in allowed })
    }
    override fun process(messages: JSONArray, systemCount: Int, maxTokens: Int?): AgentExtensionCompletion? =
        processStreaming(messages, systemCount, maxTokens) {}
    override fun processStreaming(messages: JSONArray, systemCount: Int, maxTokens: Int?, onDelta: (String) -> Unit): AgentExtensionCompletion? {
        if (PackageHookInvoker.insideHook) return null
        return synchronized(lock) {
            val candidates = hooks.filter { it.row.optString("kind") == "MessageProcessingPlugin" }
            if (candidates.isEmpty()) return@synchronized null
            val history = JSONArray((systemCount until messages.length() - 1).map { index -> messages.getJSONObject(index) })
            val input = messages.optJSONObject(messages.length() - 1)?.let(::content).orEmpty()
            val executionId = "message-${java.util.UUID.randomUUID()}"
            fun payload(probe: Boolean) = base().put("messageContent", input).put("chatHistory", turns(history))
                .put("workspacePath", JSONObject.NULL).put("maxTokens", maxTokens ?: JSONObject.NULL).put("tokenUsageThreshold", 0.85)
                .put("probeOnly", probe).put("executionId", executionId)
            val probeDeadline = now() + TimeUnit.SECONDS.toNanos(10)
            for (hook in candidates) {
                controller.throwIfCancelled()
                val remaining = TimeUnit.NANOSECONDS.toMillis(probeDeadline - now())
                check(remaining >= 1000) { "PACKAGE_MESSAGE_PROBE_TIMEOUT" }
                val probe = invoke(hook.record, hook.row, "", payload(true), minOf(remaining, hook.record.timeoutSeconds * 1000L))
                controller.throwIfCancelled()
                check(now() <= probeDeadline) { "PACKAGE_MESSAGE_PROBE_TIMEOUT" }
                if (!PackageMessageResult.matched(probe)) continue
                // A matched processor owns this submission. Its failure must not repeat work through an LLM.
                observeState("receiving")
                val streamed = StringBuilder()
                val result = try { PackageMessageOutput.withSink({ chunk ->
                    require(streamed.length.toLong() + chunk.length <= 256_000) { "PACKAGE_MESSAGE_OUTPUT_LIMIT" }
                    streamed.append(chunk); onDelta(chunk)
                }) { invoke(hook.record, hook.row, "", payload(false), hook.record.timeoutSeconds * 1000L) } }
                catch (cancelled: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { throw cancelled }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    throw AgentModelFailure("PACKAGE_MESSAGE_PROCESSING_FAILED", false, "消息处理扩展未完成，请核对插件结果后再操作", failure)
                }
                controller.throwIfCancelled()
                val final = if (result is JSONObject && PackageMessageResult.matched(result) && streamed.isNotEmpty() &&
                    listOf("text", "content", "chunks").none(result::has)) JSONObject(result.toString()).put("text", streamed.toString()) else result
                return@synchronized PackageMessageResult.completion(final)
            }
            null
        }
    }

    override fun event(event: AgentEvent) {
        if (PackageHookInvoker.insideHook) return
        val state = when (event) {
            is AgentEvent.RunStarted, is AgentEvent.RoundStarted -> "processing"
            is AgentEvent.ProviderRequestStarted -> "connecting"
            is AgentEvent.ProviderResponseStarted, is AgentEvent.AssistantBlockStart -> "receiving"
            is AgentEvent.ToolStarted, is AgentEvent.HostedToolStarted -> "executing_tool"
            is AgentEvent.ToolFinished, is AgentEvent.HostedToolFinished -> "processing_tool_result"
            else -> return
        }
        observeState(state)
    }
    override fun finished(success: Boolean) = observeState(if (success) "completed" else "error")
    private fun observeState(state: String) {
        if (PackageHookInvoker.insideHook) return
        synchronized(lock) {
            if (state == runtimeState) return
            val before = runtimeState ?: "idle"
            runtimeState = state
            try {
                dispatch("ChatRuntimeHook", "state_changed", { base().put("runtime", "main").put("previousState", before).put("state", state) }) { _ -> }
            } catch (failure: Exception) {
                io.github.mangi.eta.core.AndroidAgentLogger.warn("Package runtime observer failed: ${failure.javaClass.simpleName}")
            }
        }
    }
    override fun intercept(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult? {
      if (PackageHookInvoker.insideHook) return null
      return synchronized(lock) {
        var blocked: String? = null
        dispatch("ToolLifecycleHook", "tool_call_requested", { toolPayload(call) }) { _ -> }
        dispatch("ToolLifecycleHook", "tool_call_intercept", { toolPayload(call) }) { value ->
            (value as? JSONObject)?.takeIf { it.optString("action").equals("block", true) }?.let { result ->
                if (blocked == null) blocked = (result.opt("reason") as? String)?.trim()?.takeIf(String::isNotBlank)?.take(500) ?: "扩展已阻止工具执行"
            }
        }
        blocked?.let {
            toolNotice(call, "tool_execution_finished", JSONObject().put("success", false).put("blocked", true))
            AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", "PACKAGE_TOOL_BLOCKED").put("message", it).toString())
        }
      }
    }
    override fun permissionChecked(call: AgentModelClient.ToolCall, allowed: Boolean) =
        toolNotice(call, "tool_permission_checked", JSONObject().put("allowed", allowed))
    override fun toolStarting(call: AgentModelClient.ToolCall) = toolNotice(call, "tool_execution_started")
    override fun toolFailed(call: AgentModelClient.ToolCall) {
        toolNotice(call, "tool_execution_error", JSONObject().put("success", false).put("resultUnconfirmed", true))
        toolNotice(call, "tool_execution_finished", JSONObject().put("success", false).put("resultUnconfirmed", true))
    }
    private fun toolNotice(call: AgentModelClient.ToolCall, name: String, extra: JSONObject = JSONObject()) {
        if (PackageHookInvoker.insideHook) return
        synchronized(lock) {
            try {
                dispatch("ToolLifecycleHook", name, { toolPayload(call).apply { extra.keys().forEach { put(it, extra.get(it)) } } }) { _ -> }
            } catch (failure: Exception) { io.github.mangi.eta.core.AndroidAgentLogger.warn("Package tool observer failed: ${failure.javaClass.simpleName}") }
        }
    }
    override fun completed(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
      if (PackageHookInvoker.insideHook) return
      synchronized(lock) {
        if (hooks.none { it.row.optString("kind") == "ToolLifecycleHook" }) return@synchronized
        val parsed = runCatching { JSONObject(result.content) }.getOrNull()
        try {
            dispatch("ToolLifecycleHook", "tool_execution_result", { toolPayload(call).put("success", parsed?.optBoolean("ok", true) ?: true)
                .put("resultText", if (result.sensitive) "[sensitive result omitted]" else result.content.take(32_000))
                .put("resultJson", if (result.sensitive) JSONObject.NULL else parsed ?: JSONObject.NULL) }) { _ -> }
            if (parsed?.optBoolean("ok", true) == false) toolNotice(call, "tool_execution_error", JSONObject().put("success", false))
        } finally { toolNotice(call, "tool_execution_finished", JSONObject().put("success", parsed?.optBoolean("ok", true) ?: true)) }
      }
    }
    private fun toolPayload(call: AgentModelClient.ToolCall) = base().put("toolName", call.name).put("parameters", JSONObject(call.argumentsJson.ifBlank { "{}" })).put("toolCallId", call.id)
    override fun close() = closeInvoker()

    companion object {
        fun load(context: Context, controller: AgentRunController, chatId: String?, modelName: String, native: AgentModelClient.ToolExecutor,
            readAllowed: (String, JSONObject) -> Boolean): AgentRunExtensions {
            val records = CapabilityPackageStore(context).list().filter { it.enabled && it.enabledExtensions.isNotEmpty() }
            if (records.isEmpty()) return AgentRunExtensions.NONE
            val invoker = PackageHookInvoker(context, controller, native, readAllowed)
            return PackageHookChain(records, invoker::invoke, controller, chatId, modelName, invoker::close)
        }
        private fun content(message: JSONObject): String = when (val value = message.opt("content")) { is String -> value; else -> value?.toString().orEmpty() }
        private fun turns(messages: JSONArray) = JSONArray((0 until messages.length()).map { index ->
            val source = messages.getJSONObject(index)
            JSONObject().put("kind", source.optString("role").uppercase()).put("content", content(source)).put("toolName", source.optString("name"))
                .put("metadata", JSONObject().put("nexusOriginalIndex", index).put("nexusMessageId", source.optString("_eta_message_id")))
        })
        internal fun replaceHistory(messages: JSONArray, turns: JSONArray, systemCount: Int) {
            require(turns.length() <= 1024)
            val originals = (0 until messages.length()).map(messages::getJSONObject)
            val output = mutableListOf<JSONObject>(); val kept = mutableSetOf<Int>()
            for (index in 0 until turns.length()) {
                val turn = turns.getJSONObject(index); val role = turn.getString("kind").lowercase(); val text = turn.getString("content")
                require(role in setOf("system", "user", "assistant", "tool"))
                val originalIndex = turn.optJSONObject("metadata")?.takeIf { it.has("nexusOriginalIndex") }?.getInt("nexusOriginalIndex")
                    ?: originals.indices.firstOrNull { it !in kept && originals[it].optString("role") == role && content(originals[it]) == text }
                if (originalIndex != null) {
                    require(originalIndex in originals.indices && kept.add(originalIndex))
                    val original = originals[originalIndex]; require(original.optString("role") == role)
                    val protected = originalIndex < systemCount || original.has("tool_calls") || role == "tool" || original.opt("content") !is String || original.keys().asSequence().any { it.contains("signature", true) || it.contains("thinking", true) }
                    require(!protected || text == content(original)) { "PACKAGE_HOOK_PROTECTED_MESSAGE_CHANGED" }
                    output += JSONObject(original.toString()).apply { if (!protected) put("content", text) }
                } else { require(role in setOf("user", "assistant") && text.length <= 64_000); output += JSONObject().put("role", role).put("content", text) }
            }
            require(output.size >= systemCount && (0 until systemCount).all { index -> output[index].toString() == originals[index].toString() }) { "PACKAGE_HOOK_SYSTEM_PREFIX_CHANGED" }
            val calls = output.flatMap { message -> val array = message.optJSONArray("tool_calls") ?: JSONArray(); (0 until array.length()).map { array.getJSONObject(it).getString("id") } }
            val results = output.filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") }
            require(calls.toSet() == results.toSet() && calls.size == results.size && calls.distinct().size == calls.size) { "PACKAGE_HOOK_TOOL_CHAIN_INCOMPLETE" }
            val last = originals.lastOrNull()
            if (last?.optString("role") == "user") require(output.lastOrNull()?.optString("_eta_message_id") == last.optString("_eta_message_id") && output.last().optString("role") == "user") { "PACKAGE_HOOK_CURRENT_INPUT_REMOVED" }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            output.forEach(messages::put)
        }
    }
}
