package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.*
import org.json.JSONArray
import org.json.JSONObject

internal class PackageAiAgentProvider(context: Context, private val invoker: PackageAiInvoker = PackageAiInvoker(context)) : AgentProviderClient {
    private val registry = PackageAiRegistry(context)
    override val id = "package_ai_provider"
    override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
    override fun estimateInputTokens(request: ProviderRequest, runController: AgentRunController): Int? {
        val declaration = registry.resolve(request.effectiveConfig.providerId)
        if (declaration.callback("calculateInputTokens") == null) return null
        val value = invoker.invoke(declaration, "calculateInputTokens", request.effectiveConfig,
            payload(declaration, request.effectiveConfig).put("chatHistory", turns(request.messages)).put("availableTools", request.effectiveTools), runController).value
        val tokens = value.get("tokens") as? Number ?: error("插件模型的 Token 估算必须为数字")
        require(tokens.toDouble().isFinite() && tokens.toDouble() == tokens.toLong().toDouble() && tokens.toLong() in 0..Int.MAX_VALUE) { "插件模型 Token 估算无效" }
        return tokens.toInt()
    }

    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
        val config = request.effectiveConfig
        val declaration = registry.resolve(config.providerId)
        val payload = payload(declaration, config)
            .put("chatHistory", turns(request.messages)).put("availableTools", JSONArray((0 until request.effectiveTools.length()).map {
                request.effectiveTools.getJSONObject(it).getJSONObject("function") }))
            .put("enableThinking", config.effectiveReasoningEffort.enablesReasoning).put("stream", true).put("enableRetry", false)
            .put("preserveThinkInHistory", true).put("modelParameters", JSONArray(config.customBody.map { JSONObject().put("key", it.key).put("value", it.value.toString()) }))
        var emitted = false
        onEvent(ProviderEvent.RequestStarted)
        val result = try { invoker.invoke(declaration, "sendMessage", config, payload, runController, request,
            onUsage = { onEvent(ProviderEvent.Usage(it)) }) { text ->
            if (!emitted) { onEvent(ProviderEvent.ResponseHeaders(200)); onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0)); emitted = true }
            onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, text))
        } } catch (cancelled: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) { throw cancelled }
        catch (failure: Exception) { throw AgentModelFailure("PACKAGE_PROVIDER_FAILED", false, "插件模型请求未完成，请核对配置与运行记录，未自动重试", failure) }
        val text = result.value.opt("text") as? String ?: error("插件模型缺少 text")
        require(text.length <= 256 * 1024) { "插件模型输出过大" }
        if (!emitted) {
            onEvent(ProviderEvent.ResponseHeaders(200)); onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
            if (text.isNotEmpty()) onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, text))
        }
        onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = text, replaceContent = result.streamedText != text))
        val assistant = JSONObject().put("role", "assistant").put("content", text)
        val calls = result.value.optJSONArray("toolCalls")
        if (calls != null && calls.length() > 0) {
            require(calls.length() <= 32 && request.effectiveTools.length() > 0) { "插件模型未获工具调用授权" }
            val names = (0 until request.effectiveTools.length()).map { request.effectiveTools.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
            val ids = hashSetOf<String>()
            val canonical = JSONArray()
            for (index in 0 until calls.length()) {
                val call = calls.getJSONObject(index); val name = call.getString("name"); val callId = call.getString("id")
                require(name in names && callId.length in 1..256 && ids.add(callId)) { "插件模型工具调用无效" }
                val arguments = when (val value = call.get("arguments")) { is JSONObject -> value.toString(); is String -> JSONObject(value).toString(); else -> error("插件工具参数必须为对象") }
                canonical.put(JSONObject().put("id", callId).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", arguments)))
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, index + 1, callId, name))
                onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TOOL_CALL, index + 1, callId, name, arguments))
            }
            assistant.put("tool_calls", canonical).put("finish_reason", "tool_calls")
        } else {
            require(text.isNotBlank()) { "插件模型未返回有效内容" }
            assistant.put("finish_reason", result.value.optString("finishReason").takeIf { it in setOf("length", "max_tokens", "content_filter") } ?: "stop")
        }
        onEvent(ProviderEvent.Completed(assistant.getString("finish_reason")))
        return ProviderResponse(assistant)
    }

    companion object {
        fun payload(declaration: PackageAiDeclaration, config: AgentModelClient.ModelConfig): JSONObject = JSONObject()
            .put("providerId", declaration.id).put("providerDisplayName", declaration.title).put("providerDescription", declaration.row.optString("description"))
            .put("config", JSONObject().put("id", config.providerId).put("name", config.providerName).put("apiProviderType", "plugin")
                .put("apiProviderTypeId", declaration.id).put("apiKey", config.apiKey).put("apiEndpoint", config.baseUrl).put("modelName", config.model)
                .put("enableToolCall", config.supportsTools).put("enableDirectImageProcessing", false).put("enableDirectAudioProcessing", false)
                .put("enableDirectVideoProcessing", false).put("enableGoogleSearch", false).put("enableClaude1hPromptCache", false)
                .put("requestLimitPerMinute", 0).put("maxConcurrentRequests", 1).put("locale", "zh-CN")
                .put("customHeaders", JSONObject().apply { config.customHeaders.forEach { put(it.name, it.value) } })
                .put("customParameters", JSONArray(config.customBody.map { JSONObject().put("key", it.key).put("value", it.value.toString()) })))

        private fun turns(messages: JSONArray): JSONArray = JSONArray((0 until messages.length()).map { index ->
            val message = messages.getJSONObject(index)
            val content = when (val value = message.opt("content")) {
                null, JSONObject.NULL -> ""
                is String -> value
                else -> throw AgentModelFailure("PACKAGE_PROVIDER_TEXT_ONLY", false, "此插件模型尚未声明可核验的多模态传输")
            }
            JSONObject().put("kind", if (message.optString("role") == "tool") "TOOL_RESULT" else message.optString("role").uppercase())
                .put("content", content).put("toolName", message.optString("name"))
                .put("metadata", JSONObject().put("toolCallId", message.optString("tool_call_id"))).apply {
                    message.optJSONArray("tool_calls")?.let { put("toolCalls", it) }
                }
        })
    }
}
