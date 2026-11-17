package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.model.OnlineImageGenerationConfig
import io.github.mangi.eta.data.model.*
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous OpenAI Images-compatible generation, separate from chat completion endpoints. */
internal object OnlineImageGeneration {
    private const val MIB = 1024L * 1024L

    internal fun candidates(providers: List<ProviderSetting>): List<ProviderModel> = providers.flatMap { provider ->
        val sourceType = ProviderSourceRegistry.resolve(provider)
        if (!provider.isEnabled || provider.authMode != ProviderAuthModes.API_KEY || provider.apiKey.isBlank() ||
            sourceType !in setOf(ProviderSourceTypes.OPENAI, ProviderSourceTypes.BAILIAN, ProviderSourceTypes.GEMINI, ProviderSourceTypes.CUSTOM) ||
            provider !is OpenAiCompatibleProviderSetting && provider !is CustomProviderSetting && provider !is GeminiProviderSetting ||
            provider.customHeaders.isNotEmpty() || provider.customBody.isNotEmpty()) emptyList()
        else provider.models.filter { model -> model.isEnabled && model.customHeaders.isEmpty() && model.customBody.isEmpty() &&
            ((sourceType == ProviderSourceTypes.OPENAI || sourceType == ProviderSourceTypes.CUSTOM) ||
                sourceType == ProviderSourceTypes.BAILIAN && (model.modelId == "wan2.6-t2i" || model.modelId.startsWith("qwen-image")) ||
                sourceType == ProviderSourceTypes.GEMINI && provider is GeminiProviderSetting && model.modelId in setOf("gemini-2.5-flash-image", "gemini-3-pro-image")) &&
            model.outputModalities.any { it.equals(Model.IMAGE_MODALITY, ignoreCase = true) } }
            .map { ProviderModel(provider, it) }
    }

    internal fun select(providers: List<ProviderSetting>, args: JSONObject): ProviderModel? {
        val matches = matching(providers, args)
        require(matches.size <= 1) { "存在多个图片模型，请指定供应商和模型" }
        return matches.singleOrNull()
    }

    internal fun matching(providers: List<ProviderSetting>, args: JSONObject): List<ProviderModel> {
        val modelId = args.optString("model").trim()
        val providerId = args.optString("provider_id").trim()
        return candidates(providers).filter { candidateValid(it) }.filter { (provider, model) ->
            (providerId.isEmpty() || provider.id == providerId) &&
                (modelId.isEmpty() || model.id == modelId || model.modelId == modelId)
        }
    }

    fun endpoint(config: OnlineImageGenerationConfig): String {
        require(config.protocol == "openai_images") { "不支持的图片接口协议" }
        require(config.model.isNotBlank() && config.model.length <= 256 && config.model.none(Char::isISOControl)) { "图片模型名称无效" }
        require(config.responseFormat in setOf("auto", "b64_json", "url")) { "不支持的图片响应格式" }
        val url = config.baseUrl.trim().toHttpUrlOrNull() ?: error("图片接口地址无效")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "图片接口须为不含凭据或查询参数的 HTTPS 地址"
        }
        val base = url.toString().trimEnd('/')
        return if (url.encodedPath.trimEnd('/').endsWith("/images/generations")) base else "$base/images/generations"
    }

    internal fun bailianBase(provider: ProviderSetting): String {
        val url = provider.baseUrl.toHttpUrlOrNull() ?: error("百炼地址无效")
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.host in setOf(
                "dashscope.aliyuncs.com", "dashscope-intl.aliyuncs.com", "dashscope-us.aliyuncs.com")) { "百炼地址无效" }
        require(url.encodedPath.trimEnd('/') in setOf("", "/compatible-mode/v1")) { "百炼地址路径无效" }
        return "https://${url.host}"
    }

    internal fun candidateValid(candidate: ProviderModel): Boolean = runCatching {
        require(candidate.provider.apiKey.length <= 8192 && candidate.provider.apiKey.none { it == '\r' || it == '\n' })
        if (ProviderSourceRegistry.resolve(candidate.provider) == ProviderSourceTypes.BAILIAN) bailianBase(candidate.provider)
        else if (ProviderSourceRegistry.resolve(candidate.provider) == ProviderSourceTypes.GEMINI) geminiEndpoint(candidate)
        else endpoint(OnlineImageGenerationConfig(enabled = true, baseUrl = candidate.provider.baseUrl, model = candidate.model.modelId))
    }.isSuccess

    internal fun geminiEndpoint(candidate: ProviderModel): String {
        val url = candidate.provider.baseUrl.trim().toHttpUrlOrNull() ?: error("Gemini 地址无效")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(candidate.model.modelId in setOf("gemini-2.5-flash-image", "gemini-3-pro-image"))
        return url.toString().trimEnd('/') + "/models/${candidate.model.modelId}:generateContent"
    }

    internal fun geminiRequest(args: JSONObject): JSONObject {
        require(!args.optBoolean("async", false) && args.optInt("n", 1) == 1 && args.optString("size").isBlank())
        return JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
            JSONObject().put("text", CloudMediaProtocol.text(args, "prompt", 16_000))))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray(listOf("TEXT", "IMAGE"))))
    }

    fun request(args: JSONObject, config: OnlineImageGenerationConfig): JSONObject {
        endpoint(config)
        require(args.optString("model").let { it.isBlank() || it == config.model }) { "请在在线图片模型设置中更换模型" }
        require(!args.optBoolean("async", false)) { "此图片接口只支持同步生成" }
        val body = JSONObject().put("model", config.model)
            .put("prompt", CloudMediaProtocol.text(args, "prompt", 16_000))
            .put("n", CloudMediaProtocol.integer(args, "n", 1, 1..4))
        if (config.responseFormat != "auto") body.put("response_format", config.responseFormat)
        args.optString("size").trim().takeIf(String::isNotEmpty)?.let { raw ->
            val size = raw.replace('*', 'x')
            val match = Regex("([1-9][0-9]{2,3})x([1-9][0-9]{2,3})").matchEntire(size)
            require(size == "auto" || match != null && match.groupValues.drop(1).all { it.toInt() in 256..4096 }) {
                "图片 size 须为 auto 或 256–4096 范围的宽x高；具体尺寸仍须由模型支持"
            }
            body.put("size", size)
        }
        return body
    }

    suspend fun execute(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        transport: CloudApiTransport, downloader: CloudMediaDownload, selected: ProviderModel? = null): JSONObject {
        val settings = selected?.let { OnlineImageGenerationConfig(enabled = true, baseUrl = it.provider.baseUrl, model = it.model.modelId) }
            ?: config.settings.onlineImage
        check(settings.enabled)
        val route = config.settings.routes["image_generate"]
        check(route?.enabled != false) { "图片生成已停用" }
        val timeout = (route?.timeoutSeconds ?: 180).also { require(it in 1..600) }
        val fileMiB = (route?.maxFileMiB ?: 16).also { require(it in 1..100) }
        val maxBytes = minOf(16L * MIB, fileMiB * MIB)
        val target = endpoint(settings)
        val normalizedArgs = if (selected != null) JSONObject(args.toString()).put("model", selected.model.modelId) else args
        val body = request(normalizedArgs, settings)
        val key = selected?.provider?.apiKey ?: config.credentials.onlineImageApiKey
        require(key.isNotBlank() && key.length <= 8192 && key.none { it == '\r' || it == '\n' }) { "请保存图片接口 API Key" }
        val requestId = args.getString("request_id")
        val fingerprint = CloudRequestLedger.fingerprint(JSONObject().put("endpoint", target).put("body", body)
            .put("account", CloudRequestLedger.fingerprint(JSONObject().put("credential", key))))
        val ledger = CloudRequestLedger(context.androidContext, "online-image:${context.scopeKey}")
        return ledger.execute(requestId, "image_generate", fingerprint) {
            val response = transport.json(context.controller, "POST", target, body,
                mapOf("Authorization" to "Bearer $key"), timeout, maxResponseBytes = 32L * MIB, requireJsonContentType = true)
            val data = response.optJSONArray("data") ?: throw CloudApiException("image_data_missing", outcomeUnknown = true)
            if (data.length() !in 1..body.getInt("n")) throw CloudApiException("image_result_count_invalid", outcomeUnknown = true)
            val files = JSONArray()
            var totalBytes = 0L
            for (index in 0 until data.length()) {
                context.controller.throwIfStopped()
                val item = data.optJSONObject(index) ?: throw CloudApiException("image_data_invalid", outcomeUnknown = true)
                val encoded = (item.opt("b64_json") as? String)?.takeIf(String::isNotBlank)
                val url = (item.opt("url") as? String)?.takeIf(String::isNotBlank)
                val bytes = if (encoded != null) decodeImage(encoded, maxBytes) else {
                    if (url == null) throw CloudApiException("image_result_empty", outcomeUnknown = true)
                    val binary = downloader.downloadPublicImage(context.controller, url, maxBytes, timeout)
                    val detected = verifiedImageMime(binary.bytes)
                    val declared = binary.mimeType.substringBefore(';').trim().lowercase()
                    if (declared != detected && declared != "application/octet-stream") {
                        throw CloudApiException("image_content_type_mismatch", outcomeUnknown = true)
                    }
                    binary.bytes
                }
                totalBytes += bytes.size
                if (totalBytes > 32L * MIB) throw CloudApiException("image_result_too_large", outcomeUnknown = true)
                val mime = verifiedImageMime(bytes)
                val extension = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
                val receipt = context.saveOutput("generated-$requestId-${index + 1}.$extension", mime, bytes)
                if (!receipt.optBoolean("ok", true)) throw CloudApiException("image_result_save_failed", outcomeUnknown = true)
                files.put(receipt)
            }
            JSONObject().put("ok", true).put("operation", "image_generate").put("provider", "online_image")
                .put("model", settings.model).put("pending", false).put("files", files)
        }
    }

    internal fun decodeImage(encoded: String, maxBytes: Long): ByteArray {
        if (encoded.length.toLong() > ((maxBytes + 2) / 3) * 4) throw CloudApiException("image_result_too_large", outcomeUnknown = true)
        val bytes = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { throw CloudApiException("image_base64_invalid", outcomeUnknown = true) }
        if (bytes.isEmpty() || bytes.size > maxBytes) throw CloudApiException("image_result_too_large", outcomeUnknown = true)
        verifiedImageMime(bytes)
        return bytes
    }

    internal fun verifiedImageMime(bytes: ByteArray): String = CloudMediaTools.detectedInputMime(bytes).also {
        if (it !in setOf("image/png", "image/jpeg", "image/webp")) throw CloudApiException("image_result_type_invalid", outcomeUnknown = true)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
            bounds.outWidth.toLong() * bounds.outHeight > 33_554_432L) {
            throw CloudApiException("image_dimensions_invalid", outcomeUnknown = true)
        }
    }
}
