package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.data.model.CloudCapabilityRoute
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.repository.CloudToolsSettingsRepository
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

/** Direct cloud document APIs. All local file access and output publication are caller-owned ports. */
internal object CloudDocumentTools {
    private val operations = setOf("ocr", "file_upload", "file_status", "file_list", "file_delete",
        "file_question", "vision", "baidu_ocr", "document_parse", "document_status", "table_recognize",
        "embedding", "rerank", "translate", "moderate")

    suspend fun execute(context: CloudToolContext, args: JSONObject): JSONObject =
        executeConfigured(context, args, CloudToolsSettingsRepository.resolveRuntimeConfig(context.androidContext))

    /** Injectable transport permits protocol verification without real cloud credentials or billing. */
    internal suspend fun executeConfigured(
        context: CloudToolContext,
        args: JSONObject,
        config: CloudToolsRuntimeConfig,
        transport: CloudApiTransport = CloudApiTransport(),
    ): JSONObject = context.cancellableCloudIo {
        try {
            val operation = args.optString("operation")
            require(operation in operations) { "不支持的文档云能力" }
            val route = config.route(operation)
            val provider = chooseProvider(config, route)
            val call: suspend () -> JSONObject = {
                context.controller.throwIfCancelled()
                val result = when (operation) {
                    "ocr" -> if (provider == "baidu") baiduOcr(context, args, config, route, transport)
                        else try { deepSeekOcr(context, args, config, route, transport) }
                        catch (failure: CloudApiException) {
                            // Only definite pre-execution rejection permits a different billed API.
                            // A disconnect, timeout, 5xx or malformed success never triggers failover.
                            if (route.backup != "baidu" || !config.credentials.configured("baidu") ||
                                failure.outcomeUnknown || failure.httpStatus !in setOf(401, 402, 403, 404, 429)) throw failure
                            baiduOcr(context, args, config, route, transport).put("provider", "baidu").put("used_backup", true)
                        }
                    "file_upload" -> upload(context, args, config, route, transport)
                    "file_status", "file_list", "file_delete" -> files(context, args, config, route, transport)
                    "file_question", "vision" -> deepSeekQuestion(context, args, config, route, transport)
                    "baidu_ocr" -> baiduOcr(context, args, config, route, transport)
                    "document_parse" -> parseDocument(context, args, config, route, transport)
                    "document_status" -> documentStatus(context, args, config, route, transport)
                    "table_recognize" -> table(context, args, config, route, transport)
                    "embedding" -> embeddings(context, args, config, route, transport)
                    "rerank" -> rerank(context, args, config, route, transport)
                    "translate" -> translate(context, args, config, route, transport)
                    "moderate" -> moderate(context, args, config, route, transport)
                    else -> error("unreachable")
                }
                if (!result.has("provider")) result.put("provider", provider)
                result.put("operation", operation)
            }
            if (operation in setOf("file_status", "file_list", "document_status")) call()
            else {
                val requestId = args.optString("request_id")
                val fingerprintArgs = JSONObject(args.toString()).put("provider", provider)
                    .put("provider_endpoint", endpointIdentity(config, provider))
                    .put("account_fingerprint", accountFingerprint(config, provider))
                    .put("route_backup", route.backup).put("backup_account_fingerprint",
                        if (route.backup.isBlank()) "" else accountFingerprint(config, route.backup))
                    .put("configured_models", JSONObject().put("deepseek", config.settings.deepSeek.model)
                        .put("embedding", config.settings.bailian.embeddingModel)
                        .put("rerank", config.settings.bailian.rerankModel)
                        .put("translate", config.settings.bailian.translationModel))
                CloudRequestLedger(context.androidContext, context.scopeKey).execute(requestId, operation,
                    CloudRequestLedger.fingerprint(fingerprintArgs), call)
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: CloudApiException) { failure.toJson() }
        catch (_: IllegalArgumentException) { JSONObject().put("ok", false).put("error", "invalid_cloud_arguments") }
        // Controller cancellations/aborts must retain their runtime type and escape to the owner.
    }

    private fun chooseProvider(config: CloudToolsRuntimeConfig, route: CloudCapabilityRoute): String {
        if (config.credentials.configured(route.primary)) return route.primary
        if (route.backup.isNotBlank() && config.credentials.configured(route.backup)) return route.backup
        throw CloudApiException("cloud_credentials_missing")
    }

    private fun endpointIdentity(config: CloudToolsRuntimeConfig, provider: String) = when (provider) {
        "deepseek" -> config.deepSeekBaseUrl()
        "bailian" -> config.bailianBaseUrl()
        "baidu" -> config.settings.baidu.baseUrl.trimEnd('/')
        "tencent" -> config.settings.tencent.baseUrl.trimEnd('/')
        else -> throw CloudApiException("unknown_provider")
    }

    private suspend fun deepSeekOcr(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val file = input(ctx, args)
        val purpose = purpose(args, allowUserData = false)
        val result = http.multipart(ctx.controller, ds(config, "/v1/tools/ocr"), mapOf("purpose" to purpose), file,
            bearer(config.credentials.deepSeekApiKey), minOf(route.timeoutSeconds, 180), fileLimit(route, 100))
        val pages = result.optJSONArray("pages") ?: throw CloudApiException("invalid_ocr_response", outcomeUnknown = true)
        val incomplete = JSONArray()
        val text = buildString {
            for (index in 0 until pages.length()) {
                val page = pages.getJSONObject(index)
                if (page.optString("finish_reason") != "stop") incomplete.put(page.optInt("index", index))
                append("<!-- page ").append(page.optInt("index", index) + 1).append(" -->\n")
                append(page.optString("text")).append("\n\n")
            }
        }
        val output = saveText(ctx, args, text, "ocr.md", "text/markdown")
        val raw = saveJson(ctx, args, result, "ocr-pages.json")
        val summary = JSONObject().put("id", result.optString("id")).put("purpose", purpose)
            .put("page_count", pages.length()).put("usage", result.optJSONObject("usage"))
        return success(summary).put("text", text.take(MAX_PREVIEW)).put("output", output).put("structured_output", raw)
            .put("complete", incomplete.length() == 0).put("incomplete_pages", incomplete)
            .put("billing_notice", "客户端取消不等于供应商取消；成功页仍可能计费")
    }

    private fun upload(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val purpose = purpose(args, allowUserData = true)
        val file = input(ctx, args)
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (purpose == "user_data" && ext in setOf("pdf", "doc", "docx", "ppt", "pptx")) {
            throw IllegalArgumentException("文档须使用 text_recognition 或 rich_layout_recognition")
        }
        val fields = linkedMapOf("purpose" to purpose)
        // A finite lifetime avoids leaving an unbounded account-wide copy by default.
        val expires = args.optLong("expires_seconds", 7L * 24 * 3600)
        require(expires in 3600..30L * 24 * 3600) { "云文档有效期须为1小时至30天" }
        fields["expires_after[anchor]"] = "created_at"
        fields["expires_after[seconds]"] = expires.toString()
        val cap = if (purpose == "user_data" && !file.mimeType.startsWith("image/")) 32 else if (purpose == "user_data") 64 else 100
        val result = http.multipart(ctx.controller, ds(config, "/files"), fields, file,
            bearer(config.credentials.deepSeekApiKey), route.timeoutSeconds, fileLimit(route, cap))
        val id = result.optString("id")
        requireId(id)
        resources(ctx, config, "deepseek").record("files", id, result)
        return success(result).put("file_id", id).put("cloud_only", true)
            .put("message", "云端 file_id 用于问答引用，原件及解析结果不支持下载；本地原件仍由现有文件链路保留")
    }

    private fun files(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val operation = args.getString("operation")
        val headers = bearer(config.credentials.deepSeekApiKey)
        val store = resources(ctx, config, "deepseek")
        if (operation == "file_list") {
            val known = store.list("files")
            // Query the real service and return only IDs belonging to this local owner. This also
            // refreshes expirations after a process restart without exposing other conversations.
            val ownedIds = (0 until known.length()).map { known.getJSONObject(it).getString("id") }.toSet()
            val after = args.optString("after")
            val limit = args.optInt("limit", 20).also { require(it in 1..1000) }
            val order = args.optString("order", "desc").also { require(it in setOf("asc", "desc")) }
            val query = ds(config, "/files").toHttpUrl().newBuilder().addQueryParameter("limit", "1000")
                .addQueryParameter("order", order).build().toString()
            val remote = http.json(ctx.controller, "GET", query, headers = headers, timeoutSeconds = route.timeoutSeconds)
            val rows = remote.optJSONArray("data") ?: throw CloudApiException("invalid_files_response")
            val entries = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("id") in ownedIds }
                .filter { args.optString("purpose").isBlank() || it.optString("purpose") == args.optString("purpose") }
                .sortedBy { it.optLong("created_at") }.let { if (order == "desc") it.reversed() else it }
            val cursor = if (after.isBlank()) 0 else entries.indexOfFirst { it.optString("id") == after }.also {
                if (it < 0) throw CloudApiException("cloud_resource_not_owned")
            } + 1
            val page = entries.drop(cursor).take(limit)
            return success(JSONObject().put("data", JSONArray(page)).put("has_more", cursor + page.size < entries.size))
                .put("scope", "current_owner").put("account_page_incomplete", remote.optBoolean("has_more"))
                .put("message", "仅列出当前对话/项目登记的云文档；供应商账号全部文件需在控制台核对")
        }
        val id = args.optString("file_id").also(::requireId)
        store.requireOwned("files", id)
        val url = ds(config, "/files/$id")
        val result = http.json(ctx.controller, if (operation == "file_delete") "DELETE" else "GET", url,
            headers = headers, timeoutSeconds = route.timeoutSeconds)
        if (operation == "file_delete" && result.optBoolean("deleted")) store.remove("files", id)
        return success(result)
    }

    private suspend fun deepSeekQuestion(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", nonblank(args, "prompt", 100000)))
        if (args.getString("operation") == "file_question") {
            val ids = args.optJSONArray("file_ids") ?: JSONArray().put(args.optString("file_id"))
            require(ids.length() in 1..20)
            for (index in 0 until ids.length()) {
                val id = ids.getString(index).also(::requireId)
                resources(ctx, config, "deepseek").requireOwned("files", id)
                content.put(JSONObject().put("type", "file").put("file_id", id))
            }
        } else if (args.optString("file_id").isNotBlank()) {
            val id = args.getString("file_id").also(::requireId)
            resources(ctx, config, "deepseek").requireOwned("files", id)
            content.put(JSONObject().put("type", "file").put("file_id", id))
        } else {
            val file = input(ctx, args, "image")
            require(file.mimeType in setOf("image/jpeg", "image/png", "image/gif", "image/webp"))
            val bytes = readBounded(ctx, file, fileLimit(route, 32))
            val detail = args.optString("detail", "auto").also { require(it in setOf("low", "high", "original", "auto")) }
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:${file.mimeType};base64,${Base64.getEncoder().encodeToString(bytes)}").put("detail", detail)))
        }
        val body = JSONObject().put("model", args.optString("model", config.settings.deepSeek.model))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("stream", false).put("max_tokens", args.optInt("max_tokens", 4096).also { require(it in 1..32768) })
        if (args.optBoolean("safety_check", true)) body.put("safety_check", JSONObject().put("level", "high"))
        val result = http.json(ctx.controller, "POST", ds(config, "/chat/completions"), body,
            bearer(config.credentials.deepSeekApiKey), route.timeoutSeconds)
        val choice = result.optJSONArray("choices")?.optJSONObject(0)
            ?: throw CloudApiException("invalid_chat_response", outcomeUnknown = true)
        val message = choice.optJSONObject("message") ?: JSONObject()
        val text = message.optString("content")
        val refused = choice.optString("finish_reason") == "content_filter" || message.optString("refusal").isNotBlank()
        return success(result).put("text", text.take(MAX_PREVIEW)).put("refused", refused)
            .put("output", saveText(ctx, args, text, "analysis.md", "text/markdown"))
    }

    private suspend fun baiduOcr(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val file = input(ctx, args)
        val payload = baiduImage(ctx, file, route)
        if (args.has("language_type")) payload["language_type"] = args.getString("language_type").take(40)
        payload["detect_direction"] = args.optBoolean("detect_direction", true).toString()
        val result = http.form(ctx.controller, baiduUrl(ctx, config, http, "/rest/2.0/ocr/v1/accurate_basic"),
            payload, timeoutSeconds = route.timeoutSeconds)
        val words = result.optJSONArray("words_result") ?: throw CloudApiException("invalid_ocr_response", outcomeUnknown = true)
        val text = (0 until words.length()).joinToString("\n") { words.optJSONObject(it)?.optString("words").orEmpty() }
        return success(result).put("text", text.take(MAX_PREVIEW)).put("output", saveText(ctx, args, text, "ocr.txt", "text/plain"))
            .put("structured_output", saveJson(ctx, args, result, "ocr-structure.json"))
    }

    private suspend fun parseDocument(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val file = input(ctx, args)
        // Base64/form APIs require bounded buffering; multipart APIs use streaming instead.
        val bytes = readBounded(ctx, file, fileLimit(route, if (file.mimeType.startsWith("image/")) 10 else 32))
        val fields = linkedMapOf("file_data" to Base64.getEncoder().encodeToString(bytes), "file_name" to file.name)
        for (key in setOf("analysis_chart", "merge_tables", "relevel_titles", "recognize_seal", "return_span_boxes",
            "angle_adjust", "erase_seal", "erase_watermark", "unwarp")) {
            if (args.has(key)) fields[key] = args.getBoolean(key).toString()
        }
        fields.putIfAbsent("return_span_boxes", "true")
        val result = http.form(ctx.controller, baiduUrl(ctx, config, http,
            "/rest/2.0/brain/online/v2/paddle-vl-parser/task"), fields, timeoutSeconds = route.timeoutSeconds)
        val taskId = result.optJSONObject("result")?.optString("task_id").orEmpty().also(::requireId)
        resources(ctx, config, "baidu").record("tasks", taskId, JSONObject().put("id", taskId)
            .put("request_id", args.optString("request_id")).put("filename", file.name))
        return success(result).put("task_id", taskId).put("status", "pending").put("poll_after_seconds", 5)
            .put("message", "使用 document_status 和此 task_id 恢复查询，不要重复提交解析")
    }

    private suspend fun documentStatus(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val id = args.optString("task_id").also(::requireId)
        val store = resources(ctx, config, "baidu")
        return taskLocks.getOrPut(ctx.scopeKey + "\n" + id) { Mutex() }.withLock {
        val record = store.get("tasks", id)
        record.optJSONObject("completed_result")?.let { return@withLock JSONObject(it.toString()).put("recovered", true) }
        val lastPoll = record.optLong("last_polled_at")
        record.optJSONObject("last_result")?.let {
            if (System.currentTimeMillis() - lastPoll < 5000) return@withLock JSONObject(it.toString()).put("cached", true)
        }
        val result = http.form(ctx.controller, baiduUrl(ctx, config, http,
            "/rest/2.0/brain/online/v2/paddle-vl-parser/task/query"), mapOf("task_id" to id), timeoutSeconds = route.timeoutSeconds)
        val task = result.optJSONObject("result") ?: throw CloudApiException("invalid_document_task_response")
        val status = task.optString("status")
        val response = success(result).put("task_id", id).put("status", status).put("poll_after_seconds", 5)
        if (status == "failed") {
            response.put("ok", false).put("error", "document_task_failed")
            store.record("tasks", id, record.put("completed_result", response))
            return@withLock response
        }
        if (status != "success") {
            store.record("tasks", id, record.put("last_result", response).put("last_polled_at", System.currentTimeMillis()))
            return@withLock response
        }
        // Download only official BOS result hosts. Never send OAuth tokens to result URLs.
        val outputs = JSONArray()
        val saved = record.optJSONObject("saved_outputs") ?: JSONObject()
        for ((field, name, mime) in listOf(Triple("markdown_url", "parsed-document.md", "text/markdown"),
            Triple("parse_result_url", "parsed-document.json", "application/json"))) {
            saved.optJSONObject(field)?.let { outputs.put(it) }
            if (saved.has(field)) continue
            val rawUrl = task.optString(field)
            if (rawUrl.isBlank()) continue
            val url = rawUrl.toHttpUrl()
            require(url.isHttps && url.username.isBlank() && url.password.isBlank() && url.port == 443 &&
                (url.host.endsWith(".bcebos.com") || url.host.endsWith(".baidubce.com")))
            // JSON is a legitimate document artifact, unlike binary synthesis error responses.
            val bytes = if (field == "parse_result_url") http.json(ctx.controller, "GET", url.toString(),
                timeoutSeconds = route.timeoutSeconds).toString().toByteArray(Charsets.UTF_8)
                else http.bytes(ctx.controller, "GET", url.toString(), timeoutSeconds = route.timeoutSeconds,
                    maxResponseBytes = 16 * MIB).bytes
            val receipt = ctx.saveOutput(outputName(args, name), mime, bytes)
            if (!receipt.optBoolean("ok", true)) throw CloudApiException("document_output_save_failed")
            outputs.put(receipt)
            saved.put(field, receipt)
            store.record("tasks", id, record.put("saved_outputs", saved))
        }
        response.put("outputs", outputs)
        store.record("tasks", id, record.put("completed_result", response))
        response
        }
    }

    private suspend fun table(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val fields = baiduImage(ctx, input(ctx, args), route)
        fields["cell_contents"] = "true"
        fields["return_excel"] = args.optBoolean("return_excel", true).toString()
        if (args.has("page")) {
            val page = args.getInt("page").also { require(it in 1..1000) }.toString()
            if (fields.containsKey("pdf_file")) fields["pdf_file_num"] = page
            if (fields.containsKey("ofd_file")) fields["ofd_file_num"] = page
        }
        val result = http.form(ctx.controller, baiduUrl(ctx, config, http, "/rest/2.0/ocr/v1/table"), fields,
            timeoutSeconds = route.timeoutSeconds)
        val outputs = JSONArray()
        val tables = result.optJSONArray("tables_result")
        for (index in 0 until (tables?.length() ?: 0)) {
            val item = tables!!.optJSONObject(index) ?: continue
            val encoded = item.optString("excel_file")
            if (encoded.isNotBlank()) {
                if (encoded.length > 24 * MIB) throw CloudApiException("table_output_too_large", outcomeUnknown = true)
                val excel = try { Base64.getDecoder().decode(encoded) }
                    catch (_: IllegalArgumentException) { throw CloudApiException("invalid_table_excel", outcomeUnknown = true) }
                outputs.put(ctx.saveOutput("table-${index + 1}.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", excel))
                item.remove("excel_file")
            }
        }
        // Some API revisions return a single excel_file at the top level.
        result.optString("excel_file").takeIf(String::isNotBlank)?.let { encoded ->
            if (encoded.length > 24 * MIB) throw CloudApiException("table_output_too_large", outcomeUnknown = true)
            outputs.put(ctx.saveOutput("table.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Base64.getDecoder().decode(encoded)))
            result.remove("excel_file")
        }
        return success(result).put("outputs", outputs).put("structured_output", saveJson(ctx, args, result, "table-cells.json"))
    }

    private suspend fun embeddings(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val texts = strings(args.optJSONArray("texts"), 20, 500000)
        val body = JSONObject().put("model", args.optString("model", config.settings.bailian.embeddingModel))
            .put("input", JSONArray(texts)).put("encoding_format", "float")
        if (args.has("dimensions")) body.put("dimensions", args.getInt("dimensions").also { require(it in 32..4096) })
        val result = http.json(ctx.controller, "POST", "${config.bailianBaseUrl()}/compatible-mode/v1/embeddings", body,
            bearer(config.credentials.bailianApiKey), route.timeoutSeconds)
        val data = result.optJSONArray("data") ?: throw CloudApiException("invalid_embedding_response", outcomeUnknown = true)
        require(data.length() == texts.size)
        var dimension = -1
        val seen = mutableSetOf<Int>()
        for (index in 0 until data.length()) {
            val row = data.getJSONObject(index)
            require(row.getInt("index") in texts.indices && seen.add(row.getInt("index")))
            val vector = row.getJSONArray("embedding")
            require(vector.length() in 1..4096 && (dimension == -1 || dimension == vector.length()))
            dimension = vector.length()
            for (position in 0 until dimension) require(vector.getDouble(position).isFinite())
        }
        // Preserve index, model and dimensions so the knowledge index can reject mixed generations.
        result.put("nexa_index_metadata", JSONObject().put("model", body.getString("model"))
            .put("dimensions", dimension).put("text_sha256", JSONArray(texts.map(::sha256))))
        return success(JSONObject().put("model", body.getString("model")).put("usage", result.optJSONObject("usage")))
            .put("dimensions", dimension).put("count", texts.size)
            .put("output", saveJson(ctx, args, result, "embeddings.json"))
    }

    private suspend fun rerank(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val docs = strings(args.optJSONArray("documents"), 500, 120000)
        val query = nonblank(args, "query", 16000)
        require(query.length + docs.sumOf { it.length } <= 480000)
        val parameters = JSONObject().put("return_documents", true)
            .put("top_n", args.optInt("top_n", minOf(docs.size, 10)).also { require(it in 1..docs.size) })
        val body = JSONObject().put("model", args.optString("model", config.settings.bailian.rerankModel))
            .put("input", JSONObject().put("query", query).put("documents", JSONArray(docs))).put("parameters", parameters)
        val result = http.json(ctx.controller, "POST", "${config.bailianBaseUrl()}/api/v1/services/rerank/text-rerank/text-rerank",
            body, bearer(config.credentials.bailianApiKey), route.timeoutSeconds)
        val rows = result.optJSONObject("output")?.optJSONArray("results")
            ?: throw CloudApiException("invalid_rerank_response", outcomeUnknown = true)
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            require(row.getInt("index") in docs.indices && row.getDouble("relevance_score").isFinite())
        }
        val ranked = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            ranked.put(JSONObject().put("index", row.getInt("index")).put("relevance_score", row.getDouble("relevance_score")))
        }
        return success(JSONObject().put("usage", result.optJSONObject("usage"))).put("ranked", ranked)
            .put("output", saveJson(ctx, args, result, "rerank.json"))
    }

    private suspend fun translate(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val options = JSONObject().put("source_lang", args.optString("source_lang", "auto").take(80))
            .put("target_lang", nonblank(args, "target_lang", 80))
        args.optJSONArray("terms")?.let { terms ->
            require(terms.length() <= 100)
            for (index in 0 until terms.length()) {
                val term = terms.getJSONObject(index)
                nonblank(term, "source", 200); nonblank(term, "target", 200)
            }
            options.put("terms", terms)
        }
        if (args.has("domains")) options.put("domains", nonblank(args, "domains", 4096))
        val body = JSONObject().put("model", args.optString("model", config.settings.bailian.translationModel))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", nonblank(args, "text", 100000))))
            .put("translation_options", options).put("stream", false)
        val result = http.json(ctx.controller, "POST", "${config.bailianBaseUrl()}/compatible-mode/v1/chat/completions", body,
            bearer(config.credentials.bailianApiKey), route.timeoutSeconds)
        val text = result.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw CloudApiException("invalid_translation_response", outcomeUnknown = true)
        return success(result).put("text", text.take(MAX_PREVIEW)).put("output", saveText(ctx, args, text, "translation.txt", "text/plain"))
    }

    private fun moderate(ctx: CloudToolContext, args: JSONObject, config: CloudToolsRuntimeConfig,
        route: CloudCapabilityRoute, http: CloudApiTransport): JSONObject {
        val text = nonblank(args, "text", 20000)
        require(text.codePointCount(0, text.length) <= 10000)
        val body = JSONObject().put("Content", Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8)))
        val bizType = args.optString("biz_type", config.settings.tencent.bizType)
        if (bizType.isNotBlank()) body.put("BizType", bizType.take(128))
        val endpoint = config.settings.tencent.baseUrl.trim().trimEnd('/').toHttpUrl()
        require(endpoint.isHttps && endpoint.encodedPath == "/" && endpoint.query == null && endpoint.username.isBlank() && endpoint.password.isBlank())
        val timestamp = System.currentTimeMillis() / 1000
        val headers = tencentHeaders(endpoint.host, body.toString(), config.credentials.tencentSecretId,
            config.credentials.tencentSecretKey, config.settings.tencent.region, timestamp)
        val result = http.json(ctx.controller, "POST", endpoint.toString(), body, headers, route.timeoutSeconds)
        val assessment = result.optJSONObject("Response") ?: throw CloudApiException("invalid_moderation_response", outcomeUnknown = true)
        return success(result).put("suggestion", assessment.optString("Suggestion"))
            .put("label", assessment.optString("Label")).put("score", assessment.optInt("Score"))
    }

    private fun baiduImage(ctx: CloudToolContext, file: CloudInputFile, route: CloudCapabilityRoute): LinkedHashMap<String, String> {
        val bytes = readBounded(ctx, file, fileLimit(route, 5))
        val type = when {
            file.mimeType == "application/pdf" || file.name.endsWith(".pdf", true) -> "pdf_file"
            file.name.endsWith(".ofd", true) -> "ofd_file"
            file.mimeType.startsWith("image/") -> "image"
            else -> throw IllegalArgumentException("此识别接口需要图片、PDF或OFD")
        }
        return linkedMapOf(type to Base64.getEncoder().encodeToString(bytes))
    }

    private fun baiduUrl(ctx: CloudToolContext, config: CloudToolsRuntimeConfig, http: CloudApiTransport, path: String): String {
        val token = baiduToken(ctx, config, http)
        return "${config.settings.baidu.baseUrl.trimEnd('/')}$path".toHttpUrl().newBuilder()
            .addQueryParameter("access_token", token).build().toString()
    }

    @Synchronized
    private fun baiduToken(ctx: CloudToolContext, config: CloudToolsRuntimeConfig, http: CloudApiTransport): String {
        val key = sha256(config.credentials.baiduApiKey + "\n" + config.credentials.baiduSecretKey + "\n" + config.settings.baidu.baseUrl)
        baiduTokens[key]?.let { (token, expires) -> if (expires > System.currentTimeMillis() + 60000) return token }
        val result = http.form(ctx.controller, "${config.settings.baidu.baseUrl.trimEnd('/')}/oauth/2.0/token",
            mapOf("grant_type" to "client_credentials", "client_id" to config.credentials.baiduApiKey,
                "client_secret" to config.credentials.baiduSecretKey), timeoutSeconds = 30)
        val token = result.optString("access_token").takeIf(String::isNotBlank) ?: throw CloudApiException("baidu_authentication_failed")
        if (baiduTokens.size >= 8) baiduTokens.clear()
        baiduTokens[key] = token to (System.currentTimeMillis() + result.optLong("expires_in", 3600).coerceIn(60, 2592000) * 1000)
        return token
    }

    private fun input(ctx: CloudToolContext, args: JSONObject, key: String = "file"): CloudInputFile {
        val reference = args.optString(key).ifBlank { args.optString("file") }
        require(reference.isNotBlank() && reference.length <= 8192)
        return ctx.resolveInput(reference)
    }

    private fun resources(ctx: CloudToolContext, config: CloudToolsRuntimeConfig, provider: String): CloudDocumentResourceStore {
        return CloudDocumentResourceStore(ctx.androidContext, ctx.scopeKey, accountFingerprint(config, provider))
    }

    private fun accountFingerprint(config: CloudToolsRuntimeConfig, provider: String): String {
        val accountKey = when (provider) {
            "deepseek" -> config.credentials.deepSeekApiKey
            "baidu" -> config.credentials.baiduApiKey
            "bailian" -> config.credentials.bailianApiKey
            "tencent" -> config.credentials.tencentSecretId
            else -> throw CloudApiException("unsupported_resource_provider")
        }
        return sha256(endpointIdentity(config, provider) + "\n" + accountKey)
    }

    internal fun readBounded(ctx: CloudToolContext, file: CloudInputFile, limit: Long): ByteArray {
        if (file.sizeBytes != null && file.sizeBytes > limit) throw CloudApiException("file_too_large")
        file.open().use { input ->
            val binding = ctx.controller.register { runCatching { input.close() } }
            try {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    ctx.controller.throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size().toLong() + count > limit) throw CloudApiException("file_too_large")
                    bytes.write(buffer, 0, count)
                }
                return bytes.toByteArray()
            } finally { binding.close() }
        }
    }

    private fun purpose(args: JSONObject, allowUserData: Boolean): String = args.optString("purpose", "text_recognition").also {
        require(it in if (allowUserData) setOf("user_data", "text_recognition", "rich_layout_recognition")
            else setOf("text_recognition", "rich_layout_recognition"))
    }

    private fun ds(config: CloudToolsRuntimeConfig, path: String): String = config.deepSeekBaseUrl().removeSuffix("/v1") + path
    private fun fileLimit(route: CloudCapabilityRoute, capMiB: Int): Long = minOf(route.maxFileMiB, capMiB) * MIB
    private fun bearer(key: String) = mapOf("Authorization" to "Bearer ${key.trim()}")
    private fun success(result: JSONObject) = JSONObject().put("ok", true).put("result", result)
    private fun requireId(id: String) { require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,255}"))) }
    private fun nonblank(args: JSONObject, key: String, maximum: Int): String = args.optString(key).also {
        require(it.isNotBlank() && it.length <= maximum)
    }
    private fun strings(array: JSONArray?, maximum: Int, maxLength: Int): List<String> {
        require(array != null && array.length() in 1..maximum)
        return (0 until array.length()).map { index -> array.getString(index).also { require(it.isNotBlank() && it.length <= maxLength) } }
    }
    private fun outputName(args: JSONObject, default: String): String {
        val prefix = args.optString("output_prefix").take(64).replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fff]"), "_")
        return if (prefix.isBlank()) default else "$prefix-$default"
    }
    private suspend fun saveText(ctx: CloudToolContext, args: JSONObject, text: String, name: String, mime: String): JSONObject =
        ctx.saveOutput(outputName(args, name), mime, text.toByteArray(Charsets.UTF_8))
    private suspend fun saveJson(ctx: CloudToolContext, args: JSONObject, json: JSONObject, name: String): JSONObject =
        ctx.saveOutput(outputName(args, name), "application/json", json.toString(2).toByteArray(Charsets.UTF_8))

    internal fun tencentHeaders(host: String, payload: String, secretId: String, secretKey: String,
        region: String, timestamp: Long): Map<String, String> {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp * 1000))
        val scope = "$date/tms/tc3_request"
        val canonical = "POST\n/\n\ncontent-type:application/json; charset=utf-8\nhost:$host\n\ncontent-type;host\n${sha256(payload)}"
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$scope\n${sha256(canonical)}"
        val dateKey = hmac(("TC3" + secretKey.trim()).toByteArray(Charsets.UTF_8), date)
        val signingKey = hmac(hmac(dateKey, "tms"), "tc3_request")
        val signature = hmac(signingKey, stringToSign).joinToString("") { "%02x".format(it) }
        return mapOf("Authorization" to "TC3-HMAC-SHA256 Credential=${secretId.trim()}/$scope, SignedHeaders=content-type;host, Signature=$signature",
            "Content-Type" to "application/json; charset=utf-8", "Host" to host,
            "X-TC-Action" to "TextModeration", "X-TC-Version" to "2020-12-29", "X-TC-Region" to region,
            "X-TC-Timestamp" to timestamp.toString())
    }
    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private val baiduTokens = mutableMapOf<String, Pair<String, Long>>()
    private val taskLocks = ConcurrentHashMap<String, Mutex>()
    private const val MIB = 1024L * 1024
    private const val MAX_PREVIEW = 30000
}
