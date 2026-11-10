package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private const val MAX_COMPATIBILITY_RETRIES = 2
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.effectiveConfig
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "当前 Provider 未配置为 Chat Completions API"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also {
                ProviderRequestHeaders.mergeInto(
                    it, config.baseUrl, config.customHeaders, request.sessionId, config.authMode, config.model,
                )
            }
            .build()

        val requestJson = buildRequestJson(config, request.messages, request.effectiveTools).apply {
            if (!request.purpose.allowsTools) {
                remove("tools")
                remove("tool_choice")
            }
        }
        val existingToolCallIds = ChatToolCallHistory.referencedIds(request.messages) +
            requestJson.optJSONArray("messages")?.let(ChatToolCallHistory::referencedIds).orEmpty()
        runController.throwIfCancelled()
        // A number of OpenAI-compatible gateways reject optional fields that are valid in
        // the public API (most commonly reasoning controls, stream_options, or an empty tool
        // declaration). A rejected HTTP request has no model side effect, so retry once with a
        // conservative payload before surfacing the 400. This keeps normal chat working when a
        // provider's advertised compatibility is narrower than its endpoint name.
        var candidate = requestJson
        var compatibilityAttempt = 0
        var transportFallback = false
        while (true) {
            val httpRequest = Request.Builder()
                .url(url)
                .headers(headers)
                .post(candidate.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            var responseOutputStarted = false
            val outcome = runCatching {
                AuthorizedProviderHttp.execute(
                    client = AgentHttpClient.modelClient(request.purpose),
                    providerId = config.providerId,
                    authMode = config.authMode,
                    request = httpRequest,
                    controller = runController,
                ) { response ->
                    onEvent(ProviderEvent.ResponseHeaders(response.code))
                    if (!response.isSuccessful) {
                        val body = response.peekBody(16_384).string()
                        if (response.code == 400 && compatibilityAttempt < MAX_COMPATIBILITY_RETRIES) {
                            throw OpenAiCompatibilityRetry(body)
                        }
                        throw AgentModelFailure.http(
                            response.code,
                            body,
                            includeParameterDetails = response.code == 400,
                        )
                    }
                    val eventSink: (ProviderEvent) -> Unit = { event ->
                        if (event is ProviderEvent.BlockStart ||
                            event is ProviderEvent.HostedToolStarted ||
                            event is ProviderEvent.SearchSuggestions
                        ) {
                            responseOutputStarted = true
                        }
                        onEvent(event)
                    }
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val assistantMessage = if (
                        contentType.contains("application/json") ||
                        !candidate.optBoolean("stream", true)
                    ) {
                        readNonStreamingAssistantMessage(
                            body = response.body.string(),
                            existingToolCallIds = existingToolCallIds,
                            onEvent = eventSink,
                        )
                    } else {
                        readStreamingAssistantMessage(
                            response.body.byteStream(), runController, existingToolCallIds, eventSink,
                        )
                    }
                    onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
                    ProviderResponse(assistantMessage)
                }
            }
            val retry = outcome.exceptionOrNull() as? OpenAiCompatibilityRetry
            if (retry != null) {
                val repaired = when (compatibilityAttempt) {
                    0 -> if (shouldRetryWithConservativePayload(retry.body, candidate, config)) {
                        conservativePayload(candidate, retry.body, config)
                    } else {
                        null
                    }
                    1 -> payloadWithoutTools(candidate, retry.body, config)
                    else -> null
                }
                if (repaired != null) {
                    candidate = repaired
                    compatibilityAttempt += 1
                    continue
                }
                throw AgentModelFailure.http(
                    status = 400,
                    body = retry.body,
                    includeParameterDetails = true,
                )
            }
            val transportFailure = outcome.exceptionOrNull()
            if (
                transportFailure != null &&
                !transportFallback &&
                candidate.optBoolean("stream", true) &&
                !responseOutputStarted &&
                transportFailure.canRetryAsNonStreaming()
            ) {
                // A few compatible gateways accept stream=true but never close an SSE response,
                // while the same endpoint responds correctly to a normal JSON completion. Retry
                // once without streaming only before any visible output or hosted side effect.
                candidate = JSONObject(candidate.toString())
                    .put("stream", false)
                    .also {
                        it.remove("stream_options")
                        it.remove("include_usage")
                    }
                transportFallback = true
                continue
            }
            return outcome.getOrThrow()
        }
    }

    /** Marker used internally so a rejected first payload can be retried without classifying it
     * as a normal model failure or entering the model-level transient retry loop. */
    private class OpenAiCompatibilityRetry(val body: String) : RuntimeException()

    private fun shouldRetryWithConservativePayload(
        body: String,
        request: JSONObject,
        config: AgentModelClient.ModelConfig,
    ): Boolean {
        if (request.length() == 0) return false
        val lower = body.lowercase()
        // If the gateway explicitly rejects the model itself, changing optional fields cannot
        // help and would only delay the useful error shown to the user.
        val modelRejected = ("model" in lower || "model_not_found" in lower) &&
            listOf("does not exist", "not found", "unknown model", "unsupported model", "invalid model")
                .any(lower::contains)
        if (modelRejected && !canRepairDeepSeekModel(config)) return false
        return true
    }

    private fun conservativePayload(
        source: JSONObject,
        errorBody: String,
        config: AgentModelClient.ModelConfig,
    ): JSONObject {
        val payload = JSONObject(source.toString())
        val lower = errorBody.lowercase()
        val mentionsTools = listOf("tool", "function", "tool_choice").any(lower::contains)
        val mentionsReasoning = listOf("reasoning", "thinking", "enable_thinking").any(lower::contains)
        val mentionsStreamOptions = "stream_options" in lower || "include_usage" in lower

        // These fields are optional in Chat Completions and are the most frequent source of
        // incompatibility. Keep tools unless the server specifically points at them.
        if (mentionsReasoning || !mentionsTools && !mentionsStreamOptions) {
            listOf("reasoning_effort", "reasoning", "thinking", "enable_thinking", "thinking_budget")
                .forEach(payload::remove)
        }
        if (mentionsStreamOptions || !mentionsReasoning && !mentionsTools) {
            payload.remove("stream_options")
        }
        if (mentionsTools) {
            // Keep the function declarations on the compatibility retry.  Dropping the
            // entire `tools` array makes the request appear to work while silently turning
            // the Agent into a text-only chat (the exact failure mode users see).  Gateways
            // most commonly reject the optional `tool_choice` field; if a gateway truly
            // does not support tools, let the second request fail explicitly instead of
            // hiding the capability from the model.
            payload.remove("tool_choice")
        }
        if (canRepairDeepSeekModel(config) &&
            ("model" in lower || "model_not_found" in lower) &&
            listOf("does not exist", "not found", "unknown model", "unsupported model", "invalid model")
                .any(lower::contains)
        ) {
            // The built-in DeepSeek catalog contains forward-compatible aliases. The public
            // endpoint rejects those aliases with 400; map them only for that endpoint and only
            // after it explicitly reports an unknown model, leaving custom gateways untouched.
            payload.put("model", deepSeekFallbackModel(config.model))
        }
        return payload
    }

    /**
     * Some gateways advertise Chat Completions but reject function declarations wholesale. A
     * rejected request has not executed a model turn, so the safe final fallback keeps normal
     * conversation available while removing the unsupported optional capability.
     */
    private fun payloadWithoutTools(
        source: JSONObject,
        errorBody: String,
        config: AgentModelClient.ModelConfig,
    ): JSONObject? {
        val tools = source.optJSONArray("tools") ?: return null
        if (tools.length() == 0) return null
        val lower = errorBody.lowercase()
        val modelRejected = ("model" in lower || "model_not_found" in lower) &&
            listOf("does not exist", "not found", "unknown model", "unsupported model", "invalid model")
                .any(lower::contains)
        if (modelRejected && !canRepairDeepSeekModel(config)) return null
        if (AgentModelFailure.http(400, errorBody, includeParameterDetails = true).code == "CONTEXT_OVERFLOW") {
            return null
        }
        return JSONObject(source.toString()).also { payload ->
            payload.remove("tools")
            payload.remove("tool_choice")
            payload.remove("parallel_tool_calls")
        }
    }

    private fun canRepairDeepSeekModel(config: AgentModelClient.ModelConfig): Boolean {
        val source = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        return source == ProviderSourceTypes.DEEPSEEK &&
            config.baseUrl.lowercase().contains("api.deepseek.com")
    }

    private fun deepSeekFallbackModel(model: String): String =
        if (model.lowercase().contains("flash") || model.lowercase().contains("pro")) {
            "deepseek-reasoner"
        } else {
            "deepseek-chat"
        }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val sourceType = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        val usableTools = if (config.supportsTools) {
            AgentToolSchema.sanitizeFunctionCatalog(tools)
        } else {
            JSONArray()
        }
        return JSONObject()
            .put("model", effectiveModelName(config))
            .put("stream", true)
            .put("messages", OpenAiRequestMessages.forChatCompletions(messages))
            .also { request ->
                if (usableTools.length() > 0) {
                    request.put("tools", usableTools)
                    request.put("tool_choice", "auto")
                }
                if (sourceType != ProviderSourceTypes.OPENROUTER) {
                    request.put("stream_options", JSONObject().put("include_usage", true))
                }
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
                if (config.authMode == ProviderAuthModes.XAI_GROK) {
                    // 代理按请求头路由模型，正文必须与同一配置一致，并保持订阅所需的流式调用。
                    request.put("model", config.model)
                    request.put("stream", true)
                    request.put("messages", OpenAiRequestMessages.forChatCompletions(messages))
                    if (usableTools.length() > 0) {
                        request.put("tools", usableTools)
                        request.put("tool_choice", "auto")
                    } else {
                        request.remove("tools")
                        request.remove("tool_choice")
                    }
                }
            }
    }

    /**
     * DeepSeek's public OpenAI-compatible endpoint accepts the stable chat/reasoner IDs.
     * Older Eta catalogs used forward-looking aliases (for example `deepseek-flash`) which
     * are rejected by the official endpoint with HTTP 400. Keep custom gateways untouched and
     * normalize only those known aliases on the official host.
     */
    private fun effectiveModelName(config: AgentModelClient.ModelConfig): String {
        val source = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        if (source != ProviderSourceTypes.DEEPSEEK || !config.baseUrl.lowercase().contains("api.deepseek.com")) {
            return config.model
        }
        return when (config.model.trim().lowercase()) {
            "deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-reasoner-preview" ->
                "deepseek-reasoner"
            else -> config.model
        }
    }

    private fun readStreamingAssistantMessage(
        stream: java.io.InputStream?,
        runController: AgentRunController,
        existingToolCallIds: Set<String>,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null
        var nextContentIndex = 0
        var activeVisibleBlock: StreamingVisibleBlock? = null

        fun finishActiveVisibleBlock() {
            val block = activeVisibleBlock ?: return
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = block.kind,
                    index = block.contentIndex,
                    content = block.content.toString(),
                )
            )
            activeVisibleBlock = null
        }

        fun appendVisibleDelta(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            var block = activeVisibleBlock
            if (block?.kind != kind) {
                finishActiveVisibleBlock()
                block = StreamingVisibleBlock(
                    kind = kind,
                    contentIndex = nextContentIndex++,
                ).also { created ->
                    activeVisibleBlock = created
                    onEvent(ProviderEvent.BlockStart(kind, created.contentIndex))
                }
            }
            block.content.append(delta)
            onEvent(ProviderEvent.BlockDelta(kind, block.contentIndex, delta))
        }

        readProviderSse(stream, runController) { _, data ->
            sawStreamData = true
            val payload = data.trim()
            if (payload == "[DONE]") {
                sawDone = true
                return@readProviderSse false
            }
            val chunk = JSONObject(payload)
            throwStreamingErrorIfPresent(chunk)
            parseUsage(chunk)?.let { parsedUsage ->
                usage = parsedUsage
                onEvent(ProviderEvent.Usage(parsedUsage))
            }
            val choices = chunk.optJSONArray("choices")
            if (choices == null || choices.length() == 0) return@readProviderSse true
            val choice = choices.optJSONObject(0) ?: return@readProviderSse true
            val reason = choice.optString("finish_reason")
            if (reason.isNotBlank() && reason != "null") {
                finishReason = reason
            }
            if (reason == "error") {
                error("模型接口 SSE 以 error 结束")
            }
            val delta = choice.optJSONObject("delta") ?: JSONObject()
            val reasoningDelta = visibleReasoningDelta(delta)
            if (reasoningDelta.isNotEmpty()) {
                reasoningContent.append(reasoningDelta)
                appendVisibleDelta(AssistantBlockKind.THINKING, reasoningDelta)
            }
            if (delta.has("content") && !delta.isNull("content")) {
                val text = delta.optString("content")
                if (text.isNotEmpty()) {
                    content.append(text)
                    appendVisibleDelta(AssistantBlockKind.TEXT, text)
                }
            }
            val deltaToolCalls = delta.optJSONArray("tool_calls") ?: JSONArray()
            if (deltaToolCalls.length() > 0) finishActiveVisibleBlock()
            for (i in 0 until deltaToolCalls.length()) {
                val item = deltaToolCalls.optJSONObject(i) ?: continue
                val index = item.optInt("index", i)
                val call = toolCalls.getOrPut(index) {
                    StreamingToolCall(
                        index = index,
                        contentIndex = nextContentIndex++,
                    ).also { created ->
                        onEvent(
                            ProviderEvent.BlockStart(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = created.contentIndex,
                            )
                        )
                    }
                }
                if (item.has("id") && !item.isNull("id")) {
                    item.optString("id").takeIf(String::isNotBlank)?.let { call.id = it }
                }
                if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                val function = item.optJSONObject("function")
                val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                if (argsDelta.isNotEmpty()) {
                    onEvent(
                        ProviderEvent.BlockDelta(
                            kind = AssistantBlockKind.TOOL_CALL,
                            index = call.contentIndex,
                            delta = argsDelta,
                        )
                    )
                }
            }
            if (finishReason != null) finishActiveVisibleBlock()
            true
        }

        if (!sawStreamData) throw AgentModelFailure.incompleteStream("模型接口未返回 SSE data chunk")
        if (!sawDone && finishReason == null) throw AgentModelFailure.incompleteStream("模型接口 SSE 流未正常结束")

        finishActiveVisibleBlock()
        resolveToolCallIds(toolCalls.values, existingToolCallIds)
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEach { call ->
                                array.put(call.toJson())
                            }
                        }
                    )
                }
            }
    }

    private fun readNonStreamingAssistantMessage(
        body: String,
        existingToolCallIds: Set<String>,
        onEvent: (ProviderEvent) -> Unit,
    ): JSONObject {
        val root = JSONObject(body)
        root.optJSONObject("error")?.let { error ->
            throw AgentModelFailure.stream(
                error,
                "模型接口返回错误：${error.optString("message").ifBlank { "未提供错误信息" }.compactError()}",
            )
        }
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: error("模型接口未返回 choices")
        val rawMessage = choice.optJSONObject("message")
            ?: error("模型接口未返回 assistant message")
        val content = messageText(rawMessage.opt("content"))
        val reasoning = visibleReasoningDelta(rawMessage)
        var contentIndex = 0

        fun emitBlock(kind: AssistantBlockKind, value: String) {
            if (value.isEmpty()) return
            val index = contentIndex++
            onEvent(ProviderEvent.BlockStart(kind, index))
            onEvent(ProviderEvent.BlockDelta(kind, index, value))
            onEvent(ProviderEvent.BlockEnd(kind = kind, index = index, content = value))
        }

        emitBlock(AssistantBlockKind.THINKING, reasoning)
        emitBlock(AssistantBlockKind.TEXT, content)

        val calls = rawMessage.optJSONArray("tool_calls") ?: JSONArray()
        val normalizedCalls = JSONArray()
        val reservedIds = existingToolCallIds.toMutableSet()
        val idPrefix = "call_eta_${UUID.randomUUID().toString().replace("-", "")}_"
        for (index in 0 until calls.length()) {
            val raw = calls.optJSONObject(index) ?: continue
            val function = raw.optJSONObject("function") ?: continue
            val requestedId = raw.optString("id").takeIf(String::isNotBlank)
            val id = requestedId?.takeIf(reservedIds::add)
                ?: generateSequence(0) { it + 1 }
                    .map { "$idPrefix$it" }
                    .first(reservedIds::add)
            val arguments = function.optString("arguments").ifBlank { "{}" }
            normalizedCalls.put(
                JSONObject()
                    .put("id", id)
                    .put("type", raw.optString("type").ifBlank { "function" })
                    .put(
                        "function",
                        JSONObject()
                            .put("name", function.optString("name"))
                            .put("arguments", arguments),
                    ),
            )
            val blockIndex = contentIndex++
            onEvent(ProviderEvent.BlockStart(kind = AssistantBlockKind.TOOL_CALL, index = blockIndex))
            onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, blockIndex, arguments))
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = blockIndex,
                    blockId = id,
                    name = function.optString("name").ifBlank { null },
                    content = arguments,
                ),
            )
        }

        val usage = parseUsage(root)
        usage?.let { onEvent(ProviderEvent.Usage(it)) }
        return JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", choice.optString("finish_reason").ifBlank { "stop" })
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
                if (normalizedCalls.length() > 0) message.put("tool_calls", normalizedCalls)
            }
    }

    private fun messageText(raw: Any?): String = when (raw) {
        is String -> raw
        is JSONArray -> buildString {
            for (index in 0 until raw.length()) {
                val part = raw.optJSONObject(index) ?: continue
                if (part.optString("type") in setOf("text", "output_text", "input_text")) {
                    append(part.optString("text"))
                }
            }
        }
        else -> ""
    }

    private fun resolveToolCallIds(calls: Collection<StreamingToolCall>, existingIds: Set<String>) {
        if (calls.isEmpty()) return
        val reservedIds = existingIds.toMutableSet().apply {
            calls.mapNotNullTo(this) { it.id }
        }
        val assignedIds = existingIds.toMutableSet()
        val prefix = "call_eta_${UUID.randomUUID().toString().replace("-", "")}_"
        var nextId = 0
        // 先保留所有服务端 ID，再统一补齐；结束事件和历史必须使用同一个已定稿 ID。
        calls.sortedBy { it.index }.forEach { call ->
            if (call.id?.let(assignedIds::add) != true) {
                var generated: String
                do {
                    generated = "$prefix${nextId++}"
                } while (!reservedIds.add(generated))
                call.id = generated
                assignedIds += generated
            }
        }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", requireNotNull(id))
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private data class StreamingVisibleBlock(
        val kind: AssistantBlockKind,
        val contentIndex: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private fun visibleReasoningDelta(delta: JSONObject): String {
        // 同一分片的纯文本与结构化字段可重复携带相同思考，只消费一种表示。
        for (key in listOf("reasoning_content", "reasoning")) {
            (delta.opt(key) as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val details = delta.optJSONArray("reasoning_details") ?: return ""
        return buildString {
            for (index in 0 until details.length()) {
                val detail = details.optJSONObject(index) ?: continue
                val key = when (detail.optString("type")) {
                    "reasoning.text" -> "text"
                    "reasoning.summary" -> "summary"
                    else -> continue
                }
                (detail.opt(key) as? String)?.let(::append)
            }
        }
    }

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun throwStreamingErrorIfPresent(chunk: JSONObject) {
        val streamError = chunk.optJSONObject("error") ?: return
        val code = streamError.opt("code")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val errorType = streamError.optJSONObject("metadata")
            ?.optString("error_type")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val context = listOfNotNull(
            code?.let { "code=$it" },
            errorType?.let { "type=$it" },
        ).joinToString(", ")
        val message = streamError.optString("message")
            .ifBlank { "未提供错误信息" }
            .compactError()
        throw AgentModelFailure.stream(
            streamError,
            "模型接口 SSE 返回错误${context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}：$message",
        )
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? {
        val usage = chunk.optJSONObject("usage") ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            // 兼容端点各用各的字段：OpenAI 嵌套在 prompt_tokens_details，DeepSeek 为 prompt_cache_hit_tokens，
            // 部分端点直接放在顶层 cached_tokens。
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt("cache_read_input_tokens", "prompt_cache_hit_tokens", "cached_tokens"),
            billingBasis = io.github.mangi.eta.agent.runtime.AgentBillingTokens.OPENAI,
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
