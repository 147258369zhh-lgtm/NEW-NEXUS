package io.github.mangi.eta.agent.mcp

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.RunInputRequest
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.zhh_lgtm.nexus.kernel.api.UserInputPart
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Only this in-process scope can attach protocol recovery ownership to a native question. */
internal object McpElicitationScope {
    data class Binding(val session: McpContinuationSession, val inputRequestId: String, val page: Int,
        val currentRunId: String)
    private val local = ThreadLocal<Binding?>()
    val current: Binding? get() = local.get()
    fun <T> withInput(session: McpContinuationSession, requestId: String, action: () -> T): T {
        val before = local.get()
        val run = RuntimeInvocationScope.controller?.executionIdentity?.runId?.value ?: error("MCP_INPUT_RUN_CONTEXT_REQUIRED")
        local.set(Binding(session, requestId, 0, run))
        return try { action() }
        catch (stopped: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { runCatching { session.unrecoverable("MCP_CONTINUATION_CANCELLED") }; throw stopped }
        catch (paused: io.github.mangi.eta.agent.runtime.AgentRunPauseException) { runCatching { session.unrecoverable("MCP_CONTINUATION_PAUSED") }; throw paused }
        catch (cancelled: kotlinx.coroutines.CancellationException) { runCatching { session.unrecoverable("MCP_CONTINUATION_CANCELLED") }; throw cancelled }
        finally { if (before == null) local.remove() else local.set(before) }
    }
    fun <T> withPage(page: Int, action: () -> T): T {
        val before = local.get() ?: return action()
        local.set(before.copy(page = page))
        return try { action() } finally { local.set(before) }
    }
    fun attach(body: JSONObject) {
        val binding = current ?: return
        val invocation = RuntimeInvocationScope.toolInvocation ?: return
        val identity = RuntimeInvocationScope.controller?.executionIdentity ?: return
        check(identity.runId.value == binding.currentRunId && invocation.callId == binding.session.callId &&
            invocation.toolName == binding.session.toolName) { "MCP_INPUT_OWNER_MISMATCH" }
        body.put("mcp_continuation_id", binding.session.id).put("mcp_input_request_id", binding.inputRequestId)
            .put("mcp_input_page", binding.page).put("mcp_origin_run_id", binding.session.originRunId)
    }
    fun committedQuestion(body: JSONObject, reference: String) {
        current?.let { binding -> binding.session.rememberQuestion(body, reference) }
    }
}

/** One record belongs to one original tool call; no credentials or reusable transport session ids are saved. */
internal class McpRequestContinuationStore(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "mcp-continuations")
    fun create(body: JSONObject): McpContinuationSession = synchronized(lock) {
        check(root.isDirectory || root.mkdirs())
        // Protocol state expires after 30 minutes and is never transported by backup.
        val obsolete = System.currentTimeMillis() - 60 * 60_000L
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.lastModified() < obsolete }?.forEach { AtomicFile(it).delete() }
        require((root.listFiles()?.count { it.name.endsWith(".json") } ?: 0) < 128) { "MCP_CONTINUATION_STORAGE_LIMIT" }
        val id = "mcp-cont-${UUID.randomUUID()}"
        body.put("id", id).put("created_at", System.currentTimeMillis()).put("updated_at", System.currentTimeMillis())
        body.put("expires_at", System.currentTimeMillis() + 30 * 60_000L)
        write(id, body)
        McpContinuationSession(this, id)
    }
    fun read(id: String): JSONObject = synchronized(lock) {
        val file = target(id)
        require(file.isFile && file.length() <= MAX_BYTES) { "MCP_CONTINUATION_UNAVAILABLE" }
        val bytes = AtomicFile(file).openRead().use { it.readNBytes(MAX_BYTES.toInt() + 1) }
        require(bytes.size <= MAX_BYTES)
        JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.getString("id") == id) }
    }
    fun update(id: String, change: (JSONObject) -> Unit): JSONObject = synchronized(lock) {
        val body = read(id); change(body); body.put("updated_at", System.currentTimeMillis()); write(id, body); body
    }
    fun snapshots(): List<JSONObject> = synchronized(lock) {
        var bytes = 0L
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.take(32)?.mapNotNull { file ->
            bytes += file.length()
            if (bytes > 24L * 1024 * 1024) null else runCatching { read(file.name.removeSuffix(".json")) }.getOrNull()
        }.orEmpty()
    }
    private fun write(id: String, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "MCP_CONTINUATION_TOO_LARGE" }
        val file = AtomicFile(target(id))
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    private fun target(id: String): File {
        require(id.matches(Regex("mcp-cont-[a-f0-9-]{36}"))) { "MCP_CONTINUATION_ID_INVALID" }
        check(root.isDirectory || root.mkdirs())
        return File(root, "$id.json")
    }
    companion object {
        private val lock = Any()
        private const val MAX_BYTES = 24L * 1024 * 1024
        fun fingerprint(server: McpServerSetting, token: String?): String = digest(JSONObject()
            .put("id", server.id).put("url", server.url).put("transport", server.transportType)
            .put("protocol", server.protocolMode).put("authorization", server.authorizationType)
            .put("stdio_command", server.stdioCommand).put("stdio_arguments", JSONArray(server.stdioArguments))
            .put("launch", server.launchCommand).put("working_directory", server.workingDirectory)
            .put("credential_digest", digest(token.orEmpty())).toString())
        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/** Passed only by the trusted MCP executor, never constructed from server or model JSON. */
internal class McpRecoveryContext(val context: Context, val tool: McpRunTool, val call: AgentModelClient.ToolCall,
    private val existing: McpContinuationSession? = null) {
    fun begin(method: String, base: JSONObject, result: JSONObject): McpContinuationSession? {
        if (result.optString("resultType") != "input_required") return existing
        val opaque = result.opt("requestState") as? String
        if (opaque.isNullOrEmpty()) { existing?.unrecoverable("MCP_OPAQUE_STATE_REQUIRED"); return null }
        require(opaque.length <= 1024 * 1024)
        if (existing != null) { existing.checkpoint(result); return existing }
        val controller = RuntimeInvocationScope.controller ?: return null
        val invocation = RuntimeInvocationScope.toolInvocation ?: return null
        val owner = controller.executionIdentity ?: return null
        if (invocation.callId != call.id || invocation.toolName != call.name || invocation.messagesJson.isBlank()) return null
        require(method in setOf("tools/call", "resources/read", "prompts/get"))
        val body = JSONObject().put("protocol", McpProtocolMode.LATEST).put("origin_run_id", owner.runId.value)
            .put("tool_call_id", call.id).put("model_tool_name", call.name).put("model_arguments", call.argumentsJson)
            .put("server_id", tool.server.id).put("server_fingerprint", McpRequestContinuationStore.fingerprint(tool.server, tool.bearerToken))
            .put("tool_definition", definitionJson(tool.definition)).put("resource_reader", tool.resourceReader)
            .put("method", method).put("base_params", JSONObject(base.toString()))
            .put("request_state", opaque).put("server_result", JSONObject(result.toString()))
            .put("input_responses", JSONObject()).put("form_progress", JSONObject()).put("state", "waiting")
        return McpRequestContinuationStore(context).create(body)
    }
    companion object {
        fun definitionJson(definition: McpToolDefinition) = JSONObject().put("name", definition.name).put("title", definition.title)
            .put("description", definition.description).put("schema", definition.inputSchemaJson)
            .put("readonly", definition.readOnlyHint ?: JSONObject.NULL).put("destructive", definition.destructiveHint ?: JSONObject.NULL)
            .put("idempotent", definition.idempotentHint ?: JSONObject.NULL).put("openworld", definition.openWorldHint ?: JSONObject.NULL)
        fun definition(body: JSONObject) = McpToolDefinition(body.getString("name"), body.optString("title"), body.optString("description"), body.getString("schema"),
            body.opt("readonly") as? Boolean, body.opt("destructive") as? Boolean, body.opt("idempotent") as? Boolean, body.opt("openworld") as? Boolean)
    }
}

internal class McpContinuationSession(private val store: McpRequestContinuationStore, val id: String) {
    val originRunId: String get() = store.read(id).getString("origin_run_id")
    val callId: String get() = store.read(id).getString("tool_call_id")
    val toolName: String get() = store.read(id).getString("model_tool_name")
    fun snapshot(): JSONObject = store.read(id)
    fun checkpoint(result: JSONObject) {
        val opaque = result.opt("requestState") as? String
        require(!opaque.isNullOrEmpty()) { "MCP_OPAQUE_STATE_REQUIRED" }
        store.update(id) { body ->
            val previous = body.optJSONObject("server_result")?.toString()
            if (previous != result.toString()) {
                body.put("input_responses", JSONObject()).put("form_progress", JSONObject()).remove("last_input_body")
                body.remove("last_input_ref")
            }
            body.put("server_result", JSONObject(result.toString())).put("request_state", opaque).put("state", "waiting")
        }
    }
    fun response(requestId: String): JSONObject? = store.read(id).getJSONObject("input_responses").optJSONObject(requestId)
    fun saveResponse(requestId: String, response: JSONObject) = store.update(id) { body ->
        body.getJSONObject("input_responses").put(requestId, JSONObject(response.toString()))
    }
    fun progress(requestId: String): JSONObject = store.read(id).getJSONObject("form_progress").optJSONObject(requestId) ?: JSONObject()
    fun saveProgress(requestId: String, values: JSONObject, completedPage: Int) = store.update(id) { body ->
        body.getJSONObject("form_progress").put(requestId, JSONObject().put("values", JSONObject(values.toString())).put("completed_page", completedPage))
        body.getJSONObject("input_responses").remove(requestId)
    }
    fun rememberQuestion(body: JSONObject, reference: String) = store.update(id) {
        it.put("last_input_body", JSONObject(body.toString())).put("last_input_ref", reference).remove("recovery_claim")
    }
    fun ready(params: JSONObject) = store.update(id) { it.put("retry_params", JSONObject(params.toString())).put("state", "ready") }
    fun dispatched() = store.update(id) { body ->
        check(body.optString("state") == "ready") { "MCP_CONTINUATION_ALREADY_DISPATCHED" }
        body.put("state", "dispatched")
    }
    fun received(result: JSONObject) {
        if (result.optString("resultType") == "complete") store.update(id) { it.put("final_result", JSONObject(result.toString())).put("state", "completed") }
        else if (result.optString("resultType") == "input_required" && !result.optString("requestState").isNullOrEmpty()) checkpoint(result)
        else unrecoverable("MCP_CONTINUATION_RESULT_UNSUPPORTED")
    }
    fun unrecoverable(reason: String) = store.update(id) { it.put("state", "unrecoverable").put("failure", reason) }
    fun claimRecovery(runId: String) = store.update(id) { body ->
        require(body.optString("state") in setOf("waiting", "ready", "completed")) { "MCP_CONTINUATION_NOT_SAFE_TO_RETRY" }
        require(body.optString("recovery_claim").isEmpty()) { "MCP_CONTINUATION_ALREADY_CLAIMED" }
        body.put("recovery_claim", runId)
    }
}

internal object McpContinuationRecovery {
    private fun bound(context: Context, id: String, origin: String, callId: String): Pair<JSONObject, McpRunTool> {
        val body = McpRequestContinuationStore(context).read(id)
        require(body.getString("protocol") == McpProtocolMode.LATEST && body.getString("origin_run_id") == origin &&
            body.getString("tool_call_id") == callId) { "MCP_CONTINUATION_OWNER_MISMATCH" }
        require(System.currentTimeMillis() <= body.getLong("expires_at")) { "MCP_CONTINUATION_EXPIRED" }
        require(body.optString("state") in setOf("waiting", "ready", "completed")) { "MCP_CONTINUATION_NOT_SAFE_TO_RETRY" }
        require(body.optString("request_state").isNotEmpty()) { "MCP_OPAQUE_STATE_REQUIRED" }
        val connection = runBlocking(Dispatchers.IO + RuntimeInvocationScope.coroutineContext()) { McpServerRepository.connectionSnapshot(body.getString("server_id")) }
            ?: error("MCP_SERVER_UNAVAILABLE")
        require(connection.server.enabled && connection.server.protocolMode != McpProtocolMode.LEGACY &&
            McpRequestContinuationStore.fingerprint(connection.server, connection.token) == body.getString("server_fingerprint")) { "MCP_CONTINUATION_CONNECTION_CHANGED" }
        val definition = McpRecoveryContext.definition(body.getJSONObject("tool_definition"))
        val resourceReader = body.optBoolean("resource_reader")
        require(resourceReader || connection.server.activeTools.any { it == definition }) { "MCP_CONTINUATION_TOOL_CHANGED" }
        return body to McpRunTool(body.getString("model_tool_name"), connection.server, definition, connection.token, resourceReader)
    }
    fun isRecoverable(context: Context, continuationId: String, originRunId: String, toolCallId: String): Boolean = runCatching {
        val (body, _) = bound(context, continuationId, originRunId, toolCallId)
        body.optString("recovery_claim").isEmpty()
    }.getOrDefault(false)
    fun recoveryCall(context: Context, id: String, originRunId: String, toolCallId: String): AgentModelClient.ToolCall {
        val (body, _) = bound(context, id, originRunId, toolCallId)
        return AgentModelClient.ToolCall(toolCallId, body.getString("model_tool_name"), body.getString("model_arguments"))
    }
    fun additionalPending(context: Context): List<RunInputRequest> = McpRequestContinuationStore(context).snapshots().mapNotNull { snapshot ->
        runCatching {
            if (snapshot.optString("state") !in setOf("waiting", "ready") || snapshot.optString("recovery_claim").isNotEmpty()) return@runCatching null
            val body = snapshot.optJSONObject("last_input_body") ?: return@runCatching null
            val reference = snapshot.optString("last_input_ref").takeIf(String::isNotBlank) ?: return@runCatching null
            val id = body.getString("request_id")
            if (body.optLong("deadline_at") <= System.currentTimeMillis()) return@runCatching null
            if (!isRecoverable(context, snapshot.getString("id"), snapshot.getString("origin_run_id"), snapshot.getString("tool_call_id"))) return@runCatching null
            // The committed native question remains the authoritative UI/model-context snapshot.
            val committed = WorkflowRevisionStore(context).readCommitted("input", reference)
            require(committed.toString() == body.toString())
            val sourceName = body.optString("recovery_surface").ifBlank { body.optString("source") }
            val source = RunSource.entries.firstOrNull { it.name == sourceName } ?: return@runCatching null
            val options = body.optJSONArray("options") ?: JSONArray()
            RunInputRequest(body.getString("run_id"), source,
                UserInputPart(id, id, body.getString("question"), (0 until options.length()).map(options::getString), body.optBoolean("allow_free_text", true),
                    io.github.mangi.eta.agent.runtime.RunInputForms.parse(body), body.getLong("deadline_at")),
                conversationId = body.optString("conversation_id").takeIf(String::isNotBlank),
                targetId = body.optString("target_id").takeIf(String::isNotBlank), actorId = body.optString("actor_id").takeIf(String::isNotBlank), recovered = true, payloadRef = reference)
        }.getOrNull()
    }
    fun resume(context: Context, continuationId: String, originRunId: String, toolCallId: String,
        inputRequestId: String, inputPage: Int, answerResult: JSONObject): AgentModelClient.ToolResult {
        val controller = RuntimeInvocationScope.controller ?: error("MCP_INPUT_RUN_CONTEXT_REQUIRED")
        val invocation = RuntimeInvocationScope.toolInvocation ?: error("MCP_INPUT_TOOL_CONTEXT_REQUIRED")
        require(invocation.callId == toolCallId)
        val (body, tool) = bound(context, continuationId, originRunId, toolCallId)
        require(invocation.toolName == tool.modelName)
        val session = McpContinuationSession(McpRequestContinuationStore(context), continuationId)
        session.claimRecovery(controller.executionIdentity?.runId?.value ?: error("MCP_INPUT_RUN_ID_REQUIRED"))
        val cached = body.optJSONObject("final_result")
        val raw = if (body.optString("state") == "completed" && cached != null) cached else {
            val input = body.getJSONObject("server_result").optJSONObject("inputRequests")?.optJSONObject(inputRequestId)
                ?: error("MCP_INPUT_REQUEST_MISSING")
            require(input.optString("method") == "elicitation/create")
            val bridge = McpElicitationBridge(context, tool.server)
            bridge.acceptRecovered(session, inputRequestId, inputPage, input.getJSONObject("params"), answerResult)
            val recovery = McpRecoveryContext(context, tool, AgentModelClient.ToolCall(toolCallId, tool.modelName, body.getString("model_arguments")), session)
            val client = McpClientFactory.create(tool.server, tool.bearerToken, bridge::handle, recovery)
            val cancellation = controller.register(client::close)
            try {
                controller.throwIfStopped()
                McpInputRoundTrips.complete(session.snapshot().getJSONObject("server_result"), body.getJSONObject("base_params"), bridge::handle,
                    recovery = recovery, method = body.getString("method")) { retry ->
                    // This is the only wire send: it includes server state and collected responses.
                    require(retry.optString("requestState").isNotEmpty())
                    client.resumeRequest(body.getString("method"), retry)
                }
            } finally { cancellation.close(); client.close() }
        }
        require(raw.optString("resultType") == "complete") { "MCP_CONTINUATION_NOT_COMPLETE" }
        val final = if (tool.resourceReader) JSONObject().put("resultType", "complete").put("content", JSONArray().apply {
            val contents = raw.optJSONArray("contents") ?: error("MCP_RESOURCE_CONTENTS_MISSING")
            for (index in 0 until contents.length()) put(JSONObject().put("type", "resource").put("resource", contents.getJSONObject(index)))
        }) else raw
        return McpToolExecutor(McpRunSnapshot(listOf(tool))).adaptResult(tool, final, McpProtocolMode.LATEST)
    }
}
