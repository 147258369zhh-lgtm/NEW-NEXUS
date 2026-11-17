package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.data.model.CloudCapabilityRoute
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.repository.CloudToolsSettingsRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Real cloud media calls; input access and artifact publication remain owned by the local tool host. */
internal object CloudMediaTools {
    val operations = setOf("image_generate", "image_edit", "image_status", "video_understand", "video_generate",
        "video_status", "media_tasks", "speech_transcribe", "speech_synthesize")

    suspend fun execute(context: CloudToolContext, args: JSONObject): JSONObject =
        executeConfigured(context, args, CloudToolsSettingsRepository.resolveRuntimeConfig(context.androidContext))

    internal suspend fun executeConfigured(
        context: CloudToolContext,
        args: JSONObject,
        config: CloudToolsRuntimeConfig,
        transport: CloudApiTransport = CloudApiTransport(),
        downloader: CloudMediaDownload = CloudMediaDownload(),
    ): JSONObject = context.cancellableCloudIo {
        val owner = currentCoroutineContext().job
        val binding = context.controller.register { owner.cancel() }
        try {
        context.controller.throwIfStopped()
        val operation = args.getString("operation")
        require(operation in operations) { "不支持的媒体操作" }
        if (operation == "image_generate") {
            val matches = OnlineImageGeneration.matching(config.imageProviders, args)
            if (matches.size > 1) return@cancellableCloudIo JSONObject().put("ok", false)
                .put("error", "image_model_selection_required")
                .put("message", "请选择图片模型并用同一 request_id 重试；尚未提交生成请求")
                .put("models", JSONArray(matches.map { (provider, model) ->
                    JSONObject().put("provider_id", provider.id).put("provider_name", provider.name)
                        .put("model", model.modelId).put("model_name", model.displayName)
                }))
            val selected = matches.singleOrNull()
            val legacyMatch = config.settings.onlineImage.enabled && args.optString("provider_id").isBlank() &&
                args.optString("model").let { it.isBlank() || it == config.settings.onlineImage.model }
            if (selected == null && (args.optString("provider_id").isNotBlank() ||
                    OnlineImageGeneration.candidates(config.imageProviders).isNotEmpty() ||
                    args.optString("model").isNotBlank() && !legacyMatch)) {
                return@cancellableCloudIo JSONObject().put("ok", false)
                    .put("error", "image_model_not_found")
                    .put("message", "未找到匹配的已启用图片模型，尚未提交生成请求")
            }
            if (selected != null && io.github.mangi.eta.data.provider.ProviderSourceRegistry.resolve(selected.provider) == io.github.mangi.eta.data.model.ProviderSourceTypes.BAILIAN) {
                return@cancellableCloudIo providerBailianImage(context, args, config, selected, transport, downloader)
            }
            if (selected != null && selected.provider is io.github.mangi.eta.data.model.GeminiProviderSetting) {
                return@cancellableCloudIo providerGeminiImage(context, args, config, selected, transport)
            }
            if (selected != null || legacyMatch) {
                return@cancellableCloudIo OnlineImageGeneration.execute(context, args, config, transport, downloader, selected)
            }
        }
        val route = config.route(operation)
        require(route.primary == if (operation.startsWith("speech_")) "deepseek" else "bailian") { "媒体供应商路由无效" }
        val store = CloudMediaTaskStore(context)
        val ledger = CloudRequestLedger(context.androidContext, store.scope)
        when (operation) {
            "image_generate", "image_edit" -> image(context, args, config, route, ledger, store, transport, downloader)
            "video_generate" -> video(context, args, config, route, ledger, store, transport, downloader)
            "image_status", "video_status" -> status(context, args, config, route, store, transport, downloader)
            "media_tasks" -> JSONObject().put("ok", true).put("operation", operation).put("tasks", store.list())
            "video_understand" -> understand(context, args, config, route, ledger, transport)
            "speech_transcribe" -> transcribe(context, args, config, route, ledger, transport)
            "speech_synthesize" -> synthesize(context, args, config, route, ledger, transport)
            else -> throw IllegalArgumentException("不支持的媒体操作")
        }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: CloudApiException) { failure.toJson() }
        catch (_: IllegalArgumentException) { JSONObject().put("ok", false).put("error", "invalid_cloud_arguments") }
        finally { binding.close() }
    }

    private suspend fun providerGeminiImage(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        selected: io.github.mangi.eta.data.model.ProviderModel, transport: CloudApiTransport): JSONObject {
        val route = config.settings.routes["image_generate"]
        check(route?.enabled != false)
        val timeout = (route?.timeoutSeconds ?: 180).also { require(it in 1..600) }
        val maxBytes = minOf(16L * MIB, (route?.maxFileMiB ?: 16).also { require(it in 1..100) } * MIB)
        val target = OnlineImageGeneration.geminiEndpoint(selected)
        val body = OnlineImageGeneration.geminiRequest(args)
        val key = selected.provider.apiKey
        require(key.isNotBlank() && key.length <= 8192 && key.none { it == '\r' || it == '\n' })
        val id = requestId(args)
        val ledger = CloudRequestLedger(context.androidContext, "online-image:${context.scopeKey}")
        return ledger.execute(id, "image_generate", CloudRequestLedger.fingerprint(JSONObject().put("endpoint", target)
            .put("body", body).put("account", CloudRequestLedger.fingerprint(JSONObject().put("credential", key))))) {
            val response = transport.json(context.controller, "POST", target, body, mapOf("x-goog-api-key" to key),
                timeout, maxResponseBytes = 32L * MIB, requireJsonContentType = true)
            val candidates = response.optJSONArray("candidates") ?: throw CloudApiException("image_data_missing", outcomeUnknown = true)
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw CloudApiException("image_data_missing", outcomeUnknown = true)
            val files = JSONArray()
            var total = 0L
            for (index in 0 until parts.length()) {
                context.controller.throwIfStopped()
                val part = parts.optJSONObject(index) ?: continue
                val image = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                val bytes = OnlineImageGeneration.decodeImage(image.optString("data"), maxBytes)
                val mime = OnlineImageGeneration.verifiedImageMime(bytes)
                if (image.optString("mimeType", image.optString("mime_type")) != mime) throw CloudApiException("image_content_type_mismatch", outcomeUnknown = true)
                total += bytes.size
                if (total > 32L * MIB || files.length() >= 4) throw CloudApiException("image_result_too_large", outcomeUnknown = true)
                val receipt = context.saveOutput("generated-$id-${files.length() + 1}.${extension(mime)}", mime, bytes)
                if (!receipt.optBoolean("ok", true)) throw CloudApiException("image_result_save_failed", outcomeUnknown = true)
                files.put(receipt)
            }
            if (files.length() == 0) throw CloudApiException("image_result_empty", outcomeUnknown = true)
            JSONObject().put("ok", true).put("operation", "image_generate").put("provider", "gemini")
                .put("model", selected.model.modelId).put("pending", false).put("files", files)
        }
    }

    private suspend fun providerBailianImage(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        selected: io.github.mangi.eta.data.model.ProviderModel, transport: CloudApiTransport,
        downloader: CloudMediaDownload): JSONObject {
        require(!args.optBoolean("async", false)) { "普通供应商图片模型使用同步生成" }
        val route = config.settings.routes["image_generate"]
        check(route?.enabled != false) { "图片生成已停用" }
        val timeout = (route?.timeoutSeconds ?: 180).also { require(it in 1..600) }
        val maxBytes = minOf(16L * MIB, (route?.maxFileMiB ?: 16).also { require(it in 1..100) } * MIB)
        val base = OnlineImageGeneration.bailianBase(selected.provider)
        val request = CloudMediaProtocol.image(args, selected.model.modelId, emptyList(), false)
        val key = selected.provider.apiKey
        require(key.isNotBlank() && key.length <= 8192 && key.none { it == '\r' || it == '\n' })
        val id = requestId(args)
        val ledger = CloudRequestLedger(context.androidContext, "online-image:${context.scopeKey}")
        return ledger.execute(id, "image_generate", submissionFingerprint(request, base, key)) {
            val response = transport.json(context.controller, "POST", base + request.path, request.body,
                authorization(key), timeout, maxResponseBytes = 32L * MIB, requireJsonContentType = true)
            val urls = CloudMediaProtocol.outputUrls(response, video = false)
            if (urls.isEmpty() || urls.size > request.body.getJSONObject("parameters").getInt("n"))
                throw CloudApiException("image_result_count_invalid", outcomeUnknown = true)
            val files = JSONArray()
            var total = 0L
            urls.forEachIndexed { index, url ->
                val binary = downloader.download(context.controller, url, maxBytes, timeout)
                total += binary.bytes.size
                if (total > 32L * MIB) throw CloudApiException("image_result_too_large", outcomeUnknown = true)
                val mime = OnlineImageGeneration.verifiedImageMime(binary.bytes)
                val receipt = context.saveOutput("generated-$id-${index + 1}.${extension(mime)}", mime, binary.bytes)
                if (!receipt.optBoolean("ok", true)) throw CloudApiException("image_result_save_failed", outcomeUnknown = true)
                files.put(receipt)
            }
            JSONObject().put("ok", true).put("operation", "image_generate").put("provider", "bailian")
                .put("model", selected.model.modelId).put("pending", false).put("files", files)
        }
    }

    private suspend fun image(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, ledger: CloudRequestLedger, store: CloudMediaTaskStore,
        transport: CloudApiTransport, downloader: CloudMediaDownload): JSONObject {
        val edit = args.getString("operation") == "image_edit"
        val model = args.optString("model").ifBlank { if (edit) config.settings.bailian.imageEditModel else config.settings.bailian.imageModel }
        val base = apiBase(config.bailianBaseUrl())
        val headers = authorization(config.credentials.bailianApiKey)
        val images = if (edit) imageReferences(args).map { input ->
            encodedInput(context, input, minOf(10L * MIB, route.maxFileMiB * MIB), IMAGE_TYPES).dataUri
        } else emptyList()
        val request = CloudMediaProtocol.image(args, model, images, edit)
        val waitSeconds = CloudMediaProtocol.integer(args, "wait_seconds", 0, 0..180)
        val fingerprint = submissionFingerprint(request, base, config.credentials.bailianApiKey)
        val submitted = ledger.execute(requestId(args), args.getString("operation"), fingerprint) {
            val response = transport.json(context.controller, "POST", base + request.path, request.body,
                headers + if (request.asynchronous) mapOf("X-DashScope-Async" to "enable") else emptyMap(),
                route.timeoutSeconds)
            val id = if (request.asynchronous) providerTaskId(response) else "local-${UUID.randomUUID()}"
            val record = store.create(id, "image", base, account(config.credentials.bailianApiKey), model, response)
                .put("synchronous", !request.asynchronous)
            if (!request.asynchronous) {
                val urls = CloudMediaProtocol.outputUrls(response, video = false)
                if (urls.isEmpty()) throw CloudApiException("image_result_empty", outcomeUnknown = true)
                record.put("task_status", "SUCCEEDED").put("result_urls", JSONArray(urls))
            }
            store.save(record)
            resultSummary(record, args.getString("operation"))
        }
        if (!submitted.optBoolean("ok")) return submitted
        return status(context, JSONObject().put("operation", "image_status").put("task_id", submitted.getString("task_id"))
            .put("wait_seconds", waitSeconds), config, route, store, transport, downloader)
            .put("operation", args.getString("operation"))
            .put("request_id", requestId(args)).put("reused_request", submitted.optBoolean("reused_request"))
    }

    private suspend fun video(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, ledger: CloudRequestLedger, store: CloudMediaTaskStore,
        transport: CloudApiTransport, downloader: CloudMediaDownload): JSONObject {
        val reference = args.optString("image_path").ifBlank { args.optString("path") }.trim().takeIf(String::isNotEmpty)
        val model = args.optString("model").ifBlank {
            if (reference == null) config.settings.bailian.videoGenerationModel else config.settings.bailian.imageToVideoModel
        }
        val image = reference?.let { encodedInput(context, it, minOf(20L * MIB, route.maxFileMiB * MIB), VIDEO_IMAGE_TYPES).dataUri }
        val request = CloudMediaProtocol.video(args, model, image)
        val waitSeconds = CloudMediaProtocol.integer(args, "wait_seconds", 0, 0..180)
        val base = apiBase(config.bailianBaseUrl())
        val headers = authorization(config.credentials.bailianApiKey) + mapOf("X-DashScope-Async" to "enable")
        val submitted = ledger.execute(requestId(args), "video_generate", submissionFingerprint(request, base, config.credentials.bailianApiKey)) {
            val response = transport.json(context.controller, "POST", base + request.path, request.body, headers, route.timeoutSeconds)
            val record = store.create(providerTaskId(response), "video", base, account(config.credentials.bailianApiKey), model, response)
            resultSummary(record, "video_generate")
        }
        if (!submitted.optBoolean("ok")) return submitted
        return status(context, JSONObject().put("operation", "video_status").put("task_id", submitted.getString("task_id"))
            .put("wait_seconds", waitSeconds), config, route, store, transport, downloader)
            .put("operation", "video_generate")
            .put("request_id", requestId(args)).put("reused_request", submitted.optBoolean("reused_request"))
    }

    private suspend fun status(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, store: CloudMediaTaskStore, transport: CloudApiTransport, downloader: CloudMediaDownload): JSONObject {
        val taskId = args.getString("task_id")
        val expectedKind = if (args.getString("operation") == "video_status") "video" else "image"
        val waitSeconds = CloudMediaProtocol.integer(args, "wait_seconds", 0, 0..180)
        val deadline = System.nanoTime() + waitSeconds * 1_000_000_000L
        return try { store.locked(taskId) {
            val record = store.load(taskId)
            require(record.getString("kind") == expectedKind) { "任务类型与查询操作不匹配" }
            if (record.optString("account") != account(config.credentials.bailianApiKey)) throw CloudApiException("media_task_account_changed")
            var queried = false
            while (true) {
                context.controller.throwIfStopped()
                record.optJSONArray("files")?.takeIf { it.length() > 0 && it.length() == record.optJSONArray("result_urls")?.length() }?.let { files ->
                    return@locked filesResult(record, files, args.getString("operation"))
                }
                if (record.optLong("expires_at") <= System.currentTimeMillis()) {
                    return@locked resultSummary(record, args.getString("operation")).put("ok", false)
                        .put("error", "media_task_expired").put("retry_safe", false)
                }
                val endpoint = apiBase(record.getString("endpoint"))
                // A region/workspace change must never send the current key to an old result's host.
                if (endpoint != apiBase(config.bailianBaseUrl())) throw CloudApiException("media_task_endpoint_changed")
                if (!record.optBoolean("synchronous") && (!queried || record.optString("task_status") in setOf("PENDING", "RUNNING"))) {
                    val response = transport.json(context.controller, "GET", "$endpoint/api/v1/tasks/$taskId",
                        headers = authorization(config.credentials.bailianApiKey), timeoutSeconds = route.timeoutSeconds)
                    val output = response.optJSONObject("output") ?: throw CloudApiException("missing_task_output")
                    val status = output.optString("task_status")
                    if (status !in TASK_STATES) throw CloudApiException("invalid_task_status")
                    record.put("task_status", status).put("queried_at", System.currentTimeMillis())
                    if (status == "SUCCEEDED") {
                        val urls = CloudMediaProtocol.outputUrls(response, expectedKind == "video")
                        if (urls.isEmpty()) throw CloudApiException("media_result_empty")
                        record.put("result_urls", JSONArray(urls))
                    }
                    store.save(record)
                    queried = true
                }
                when (record.getString("task_status")) {
                    "SUCCEEDED" -> {
                        val urls = record.optJSONArray("result_urls") ?: throw CloudApiException("media_result_missing")
                        val files = record.optJSONArray("files") ?: JSONArray()
                        // Persist each receipt separately so a later failed download resumes only missing results.
                        for (index in files.length() until urls.length()) {
                            context.controller.throwIfStopped()
                            val binary = downloader.download(context.controller, urls.getString(index),
                                minOf(if (expectedKind == "video") 100L * MIB else 32L * MIB, route.maxFileMiB * MIB),
                                route.timeoutSeconds)
                            val mime = verifiedMediaMime(binary.bytes, expectedKind)
                            val suffix = extension(mime)
                            val receipt = context.saveOutput("${expectedKind}-${taskId.take(48)}-${index + 1}.$suffix", mime, binary.bytes)
                            if (!receipt.optBoolean("ok", true)) throw CloudApiException("media_result_save_failed")
                            files.put(receipt)
                            record.put("files", files)
                            store.save(record)
                        }
                        return@locked filesResult(record, files, args.getString("operation"))
                    }
                    "FAILED", "CANCELED", "UNKNOWN" -> return@locked resultSummary(record, args.getString("operation"))
                        .put("ok", false).put("error", "media_task_${record.getString("task_status").lowercase(Locale.ROOT)}")
                        .put("retry_safe", false)
                }
                if (waitSeconds == 0 || System.nanoTime() >= deadline) {
                    return@locked resultSummary(record, args.getString("operation")).put("pending", true)
                        .put("poll_after_seconds", 10).put("retry_safe", false)
                }
                delay(minOf(10_000L, ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)))
            }
            @Suppress("UNREACHABLE_CODE") JSONObject()
        } } catch (failure: CloudApiException) {
            failure.toJson().put("task_id", taskId).put("operation", args.getString("operation"))
                .put("retry_safe", false).put("query_retry_safe", true)
        }
    }

    private suspend fun understand(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, ledger: CloudRequestLedger, transport: CloudApiTransport): JSONObject {
        val file = encodedInput(context, args.optString("video_path").ifBlank { args.getString("path") },
            minOf(30L * MIB, route.maxFileMiB * MIB), VIDEO_TYPES)
        val model = args.optString("model").ifBlank { config.settings.bailian.videoUnderstandingModel }
        val request = CloudMediaProtocol.videoUnderstanding(args, model, file.dataUri)
        val endpoint = apiBase(config.bailianBaseUrl())
        val headers = authorization(config.credentials.bailianApiKey)
        return ledger.execute(requestId(args), "video_understand", submissionFingerprint(request, endpoint, config.credentials.bailianApiKey)) {
            val response = transport.json(context.controller, "POST", endpoint + request.path, request.body,
                headers, route.timeoutSeconds)
            val choices = response.optJSONArray("choices") ?: throw CloudApiException("video_understanding_response_invalid", outcomeUnknown = true)
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw CloudApiException("video_understanding_response_invalid", outcomeUnknown = true)
            val content = message.opt("content")
            val text = when (content) {
                is String -> content
                is JSONArray -> (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text") }.joinToString("\n")
                else -> ""
            }
            if (text.isBlank()) throw CloudApiException("video_understanding_empty", outcomeUnknown = true)
            val result = JSONObject().put("ok", true).put("operation", "video_understand").put("provider", "bailian")
                .put("model", model).put("text", text).put("finish_reason", choices.optJSONObject(0)?.optString("finish_reason"))
            response.optJSONObject("usage")?.let { result.put("usage", it) }
            if (args.optBoolean("save", true)) result.put("files", JSONArray().put(context.saveOutput("video-understanding.md", "text/markdown", text.toByteArray(Charsets.UTF_8))))
            result
        }
    }

    private suspend fun transcribe(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, ledger: CloudRequestLedger, transport: CloudApiTransport): JSONObject {
        val reference = args.optString("audio_path").ifBlank { args.getString("path") }
        val source = context.resolveInput(reference)
        val bytes = readBounded(context, source, minOf(8L * MIB, route.maxFileMiB * MIB))
        val audio = validateAsr(bytes)
        val endpoint = apiBase(config.deepSeekBaseUrl(), deepSeek = true)
        val headers = authorization(config.credentials.deepSeekApiKey)
        val fingerprint = CloudRequestLedger.fingerprint(JSONObject().put("endpoint", endpoint)
            .put("account", account(config.credentials.deepSeekApiKey)).put("sha256", sha256(bytes)).put("save", args.optBoolean("save", true)))
        return ledger.execute(requestId(args), "speech_transcribe", fingerprint) {
            val response = transport.multipart(context.controller, "$endpoint/v1/tools/asr", emptyMap(),
                CloudInputFile(if (audio == "audio/wav") "speech.wav" else "speech.ogg", audio, bytes.size.toLong()) { ByteArrayInputStream(bytes) },
                headers, route.timeoutSeconds, maxFileBytes = 8L * MIB)
            val text = response.optString("text")
            if (!response.has("text")) throw CloudApiException("speech_transcription_invalid", outcomeUnknown = true)
            val result = JSONObject().put("ok", true).put("operation", "speech_transcribe").put("provider", "deepseek")
                .put("text", text).put("duration_ms", response.optLong("duration_ms", -1))
            if (args.optBoolean("save", true) && text.isNotEmpty()) result.put("files", JSONArray().put(context.saveOutput("transcript.txt", "text/plain", text.toByteArray(Charsets.UTF_8))))
            result
        }
    }

    private suspend fun synthesize(context: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, ledger: CloudRequestLedger, transport: CloudApiTransport): JSONObject {
        val body = CloudMediaProtocol.speech(args)
        val endpoint = apiBase(config.deepSeekBaseUrl(), deepSeek = true)
        val headers = authorization(config.credentials.deepSeekApiKey)
        val fingerprint = CloudRequestLedger.fingerprint(JSONObject().put("endpoint", endpoint)
            .put("account", account(config.credentials.deepSeekApiKey)).put("body", body))
        return ledger.execute(requestId(args), "speech_synthesize", fingerprint) {
            val response = transport.bytes(context.controller, "POST", "$endpoint/v1/tools/tts", body,
                headers, route.timeoutSeconds,
                maxResponseBytes = minOf(60L * MIB, route.maxFileMiB * MIB))
            if (!ascii(response.bytes, 0, "RIFF") || !ascii(response.bytes, 8, "WAVE")) throw CloudApiException("speech_audio_invalid", outcomeUnknown = true)
            val requestedName = args.optString("output_name").substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_").trim('.', ' ').take(100).removeSuffix(".wav").ifBlank { "deepseek-speech" }
            val receipt = context.saveOutput("$requestedName.wav", "audio/wav", response.bytes)
            receipt.put("ok", true).put("operation", "speech_synthesize").put("provider", "deepseek")
                .put("voice", body.getString("voice_id")).put("sample_rate_hz", 24_000)
        }
    }

    private data class EncodedInput(val dataUri: String)

    private fun encodedInput(context: CloudToolContext, reference: String, maximum: Long, allowed: Set<String>): EncodedInput {
        require(reference.isNotBlank()) { "需提供已授权的文件引用" }
        val source = context.resolveInput(reference)
        val bytes = readBounded(context, source, maximum)
        val mime = detectedInputMime(bytes)
        require(mime in allowed) { "文件内容不属于此云能力支持的媒体格式" }
        return EncodedInput("data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))
    }

    private fun readBounded(context: CloudToolContext, source: CloudInputFile, maximum: Long): ByteArray {
        if (source.sizeBytes?.let { it > maximum } == true) throw CloudApiException("file_too_large")
        return source.open().use { input ->
            val binding = context.controller.register { runCatching { input.close() } }
            try {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    context.controller.throwIfStopped()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (bytes.size().toLong() + read > maximum) throw CloudApiException("file_too_large")
                    bytes.write(buffer, 0, read)
                }
                if (bytes.size() == 0) throw CloudApiException("empty_input_file")
                bytes.toByteArray()
            } finally { binding.close() }
        }
    }

    internal fun validateAsr(bytes: ByteArray): String {
        if (ascii(bytes, 0, "OggS")) {
            require(bytes.copyOfRange(0, minOf(bytes.size, 128)).toString(Charsets.ISO_8859_1).contains("OpusHead")) { "Ogg 音频必须使用 Opus 编码" }
            var lastGranule = -1L
            var cursor = 0
            while (cursor + 27 <= bytes.size) {
                require(ascii(bytes, cursor, "OggS")) { "Ogg 音频结构无效" }
                val count = bytes[cursor + 26].toInt() and 255
                require(cursor + 27 + count <= bytes.size) { "Ogg 音频不完整" }
                val dataSize = (0 until count).sumOf { bytes[cursor + 27 + it].toInt() and 255 }
                val end = cursor + 27 + count + dataSize
                require(end <= bytes.size) { "Ogg 音频不完整" }
                val granule = ByteBuffer.wrap(bytes, cursor + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (granule >= 0) lastGranule = maxOf(lastGranule, granule)
                cursor = end
            }
            require(cursor == bytes.size && lastGranule in 0..120L * 48_000) { "音频时长超过 120 秒或 Ogg 时长无效" }
            return "audio/ogg"
        }
        require(ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WAVE")) { "DeepSeek 转写只支持 16 位 PCM WAV 或 Ogg Opus" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12; var rate = 0L; var dataSize = 0L; var foundFormat = false
        while (cursor + 8 <= bytes.size) {
            val size = buffer.getInt(cursor + 4).toLong() and 0xffffffffL
            require(size <= bytes.size.toLong() - cursor - 8) { "WAV 音频不完整" }
            if (ascii(bytes, cursor, "fmt ")) {
                require(size >= 16) { "WAV 格式无效" }
                val format = buffer.getShort(cursor + 8).toInt() and 65535
                val channels = buffer.getShort(cursor + 10).toInt() and 65535
                val sampleRate = buffer.getInt(cursor + 12)
                val bits = buffer.getShort(cursor + 22).toInt() and 65535
                require(format == 1 && channels in 1..2 && bits == 16 && sampleRate in setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000)) { "WAV 须为支持采样率的 16 位 PCM 单声道或双声道" }
                rate = sampleRate.toLong() * channels * 2; foundFormat = true
            }
            if (ascii(bytes, cursor, "data")) dataSize += size
            cursor += 8 + size.toInt() + (size.toInt() and 1)
        }
        require(foundFormat && dataSize > 0 && dataSize <= rate * 120) { "音频为空或时长超过 120 秒" }
        return "audio/wav"
    }

    private fun imageReferences(args: JSONObject): List<String> {
        val array = args.optJSONArray("image_paths") ?: args.optJSONArray("paths")
        val result = if (array != null) (0 until array.length()).map(array::getString)
            else listOf(args.optString("image_path").ifBlank { args.optString("path") })
        require(result.size in 1..4 && result.all(String::isNotBlank)) { "需提供 1–4 个已授权图片引用" }
        return result
    }

    private fun submissionFingerprint(request: CloudMediaProtocol.Request, endpoint: String, key: String): String =
        CloudRequestLedger.fingerprint(JSONObject().put("endpoint", endpoint).put("account", account(key)).put("path", request.path).put("body", request.body))

    private fun account(key: String): String = sha256(key.toByteArray(Charsets.UTF_8))

    private fun requestId(args: JSONObject): String = args.optString("request_id").also {
        require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{7,127}"))) { "收费请求须提供 8–128 位 request_id；恢复同一请求请复用原 ID" }
    }

    private fun providerTaskId(response: JSONObject): String = response.optJSONObject("output")?.optString("task_id")?.takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
    } ?: throw CloudApiException("task_id_missing", outcomeUnknown = true)

    private fun authorization(key: String): Map<String, String> {
        require(key.isNotBlank()) { "请先配置此云能力的独立 API Key" }
        return mapOf("Authorization" to "Bearer $key")
    }

    internal fun apiBase(raw: String, deepSeek: Boolean = false): String {
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("云服务 HTTPS 地址无效")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "云服务地址须为不含凭据或查询参数的 HTTPS 地址" }
        var base = url.toString().trimEnd('/')
        if (deepSeek) base = base.removeSuffix("/v1")
        return base
    }

    private fun resultSummary(record: JSONObject, operation: String): JSONObject = CloudMediaTaskStore.summary(record)
        .put("ok", true).put("operation", operation).put("provider", "bailian")

    private fun filesResult(record: JSONObject, files: JSONArray, operation: String): JSONObject {
        val result = resultSummary(record, operation).put("files", files).put("pending", false)
        if (files.length() == 1) files.getJSONObject(0).keys().forEach { key ->
            if (key in setOf("path", "content_uri", "mime_type", "bytes", "size_bytes", "label", "artifact_id")) result.put(key, files.getJSONObject(0).get(key))
        }
        return result
    }

    private fun verifiedMediaMime(bytes: ByteArray, kind: String): String = detectedInputMime(bytes).also {
        if (it !in if (kind == "video") setOf("video/mp4") else IMAGE_TYPES) throw CloudApiException("media_result_type_invalid")
    }

    internal fun detectedInputMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP") -> "image/webp"
        ascii(bytes, 0, "GIF87a") || ascii(bytes, 0, "GIF89a") -> "image/gif"
        ascii(bytes, 0, "BM") -> "image/bmp"
        ascii(bytes, 0, "II*\u0000") || ascii(bytes, 0, "MM\u0000*") -> "image/tiff"
        ascii(bytes, 4, "ftyp") -> when {
            ascii(bytes, 8, "qt  ") -> "video/quicktime"
            listOf("isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash", "msdh", "msix").any { ascii(bytes, 8, it) } -> "video/mp4"
            else -> "application/octet-stream"
        }
        bytes.size >= 4 && bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte() -> "video/webm"
        else -> "application/octet-stream"
    }

    private fun ascii(bytes: ByteArray, offset: Int, value: String): Boolean = bytes.size >= offset + value.length &&
        value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }

    private fun extension(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/bmp" -> "bmp"
        "image/tiff" -> "tiff"; "video/mp4" -> "mp4"; else -> "png"
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private const val MIB = 1024L * 1024
    private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/bmp", "image/tiff", "image/gif")
    private val VIDEO_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/bmp")
    private val VIDEO_TYPES = setOf("video/mp4", "video/webm", "video/quicktime")
    private val TASK_STATES = setOf("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED", "UNKNOWN")
}
