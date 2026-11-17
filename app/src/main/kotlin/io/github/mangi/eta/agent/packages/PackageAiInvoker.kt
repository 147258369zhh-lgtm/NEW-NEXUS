package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.control.EtaAppControlAuditStore
import io.github.mangi.eta.agent.control.SettingsControlOrigin
import io.github.mangi.eta.agent.control.SettingsControlSnapshots
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class PackageAiResult(val value: JSONObject, val usage: AgentTokenUsage?, val streamedText: String)

internal class PackageAiInvoker(context: Context, private val runner: PackageHookRunner? = null,
    private val httpClient: okhttp3.OkHttpClient = io.github.mangi.eta.agent.model.AgentHttpClient.modelClient,
) {
    private val context = context.applicationContext
    fun invoke(declaration: PackageAiDeclaration, method: String, config: AgentModelClient.ModelConfig, payload: JSONObject,
        controller: AgentRunController, request: ProviderRequest? = null, onUsage: (AgentTokenUsage) -> Unit = {}, onDelta: (String) -> Unit = {}): PackageAiResult {
        val registry = PackageAiRegistry(context)
        val callback = requireNotNull(declaration.callback(method)) { "插件模型缺少 $method 回调" }
        fun current(): Boolean = runCatching {
            registry.resolve(config.providerId).let { it.record.digest == declaration.record.digest && it.row.toString() == declaration.row.toString() } &&
                runBlocking { io.github.mangi.eta.data.repository.ProviderRepository.providerById(config.providerId) }?.let {
                    it.isEnabled && it.baseUrl.trim() == config.baseUrl.trim() && it.apiKey.trim() == config.apiKey &&
                        it.authMode == io.github.mangi.eta.data.model.ProviderAuthModes.API_KEY
                } == true
        }.getOrDefault(false)
        check(current()) { "插件模型已变化，请重新接入" }
        controller.throwIfStopped()
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "PACKAGE_PROVIDER_INPUT_LIMIT" }
        val temporary = File.createTempFile("package-provider-", ".json", context.cacheDir)
        val client = if (runner == null) PackageSandboxClient(context) else null
        val http = PackageProviderHttp(config, controller, request, started = {}, client = httpClient, onUsage = onUsage)
        val streamed = StringBuilder()
        val revoked = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val watcher = scope.launch { combine(CapabilityPackageStore.changes,
            io.github.mangi.eta.data.repository.ProviderRepository.providersFlow()) { a, b -> a to b }.collect {
            if (!current()) { revoked.set(true); http.close(); client?.close() }
        } }
        val audit = EtaAppControlAuditStore(context)
        val metadata = JSONObject().put("package_id", declaration.record.id).put("digest", declaration.record.digest).put("method", method)
            .put("provider_id", config.providerId).put("input_hash", SettingsControlSnapshots.hash(payload))
        val origin = SettingsControlOrigin(SettingsActorKind.LOCAL_AGENT, declaration.record.id, controller.executionIdentity?.runId?.value, "package_model")
        val started = System.nanoTime()
        try {
            audit.append("package_model_started", "packages.provider", metadata, false, durationMs = 0, origin = origin)
            val data = CapabilityPackageStore(context).source(declaration.record).put("entry", declaration.record.mainEntry)
                .put("main_entry", declaration.record.mainEntry).put("api_version", declaration.record.apiVersion).put("package_id", declaration.record.id)
                .put("provider_mode", true).put("function", "__nexus_callback__").put("arguments", JSONObject().put("key", callback).put("event", JSONObject()
                    .put("event", eventName(method)).put("eventName", eventName(method)).put("pluginId", declaration.id).put("toolPkgId", declaration.record.id)
                    .put("eventPayload", payload)))
            val bytes = data.toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 8 * 1024 * 1024) { "PACKAGE_PROVIDER_SOURCE_LIMIT" }; temporary.writeBytes(bytes)
            val host: (String, JSONObject) -> JSONObject = { name, args ->
                controller.throwIfStopped(); check(!revoked.get() && current()) { "插件模型已停用或变化" }
                when (name) {
                    "package:environment" -> JSONObject().put("ok", true).put("value", PackageEnvironmentStore(context).value(declaration.record.id, args.getString("name")) ?: "")
                    "package:state" -> PackageStateStore(context).invoke(declaration.record.id, args, writable = false)
                    "operit:http_request" -> { check(method != "calculateInputTokens"); http.all(args) }
                    "package:provider-http-start" -> { check(method != "calculateInputTokens"); http.start(args) }
                    "package:provider-http-next" -> { check(method != "calculateInputTokens"); http.next() }
                    "package:provider-output" -> {
                        check(method == "sendMessage") { "PACKAGE_PROVIDER_OUTPUT_NOT_EXPECTED" }
                        val text = args.optJSONObject("value")?.opt("chunk") as? String
                        if (text != null) { require(streamed.length + text.length <= 256 * 1024); streamed.append(text); onDelta(text) }
                        JSONObject().put("ok", true)
                    }
                    else -> JSONObject().put("ok", false).put("code", "PACKAGE_PROVIDER_HOST_DENIED")
                }
            }
            val response = RuntimeInvocationScope.withController(controller) {
                if (runner != null) runner.invoke(temporary, declaration.record.timeoutSeconds * 1000L, declaration.record.memoryMb * 1024L * 1024, host)
                else requireNotNull(client).invoke(temporary, declaration.record.timeoutSeconds * 1000L, declaration.record.memoryMb * 1024L * 1024, host)
            }
            controller.throwIfStopped(); check(!revoked.get() && current()) { "插件模型已变化，未采用迟到结果" }
            check(response.optBoolean("ok")) { "插件模型回调未完成，请检查插件设置与运行记录" }
            val value = response.optJSONObject("value") ?: error("插件模型返回格式无效")
            audit.append("package_model_finished", "packages.provider", metadata, true, durationMs = (System.nanoTime() - started) / 1_000_000, origin = origin)
            return PackageAiResult(value, http.usage, streamed.toString())
        } catch (failure: Exception) {
            audit.append("package_model_finished", "packages.provider", metadata, false, errorCode = failure.javaClass.simpleName,
                durationMs = (System.nanoTime() - started) / 1_000_000, origin = origin)
            throw failure
        } finally { scope.cancel(); watcher.cancel(); client?.close(); http.close(); temporary.delete() }
    }
    companion object {
        fun eventName(method: String) = "toolpkg_ai_provider_" + method.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
}
