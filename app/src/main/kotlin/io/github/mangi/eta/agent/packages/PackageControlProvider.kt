package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.control.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import org.json.JSONArray
import org.json.JSONObject

/** UI and Agent settings share the existing audited control plane. */
internal object PackageControlProvider : EtaControlProvider {
    override val providerId = "capability-packages"
    override fun capabilities(): List<EtaControlRegistration> = listOf(
        EtaControlRegistration(EtaControlCapabilityInfo("state.packages", "扩展包", "能力", "读取插件与沙盒包", EtaControlDanger.SAFE,
            JSONObject().put("type", "object"), true), read = { context ->
                JSONObject().put("packages", JSONArray(CapabilityPackageStore(context).list().map { record ->
                    CapabilityPackageStore.encode(record).put("configured_environment_names", JSONArray(PackageEnvironmentStore(context).names(record.id)))
                }))
            }, execute = { _, _ -> JSONObject() }),
        EtaControlRegistration(EtaControlCapabilityInfo("settings.packages.configure", "扩展包设置", "能力", "修改启用、明确工具授权与运行配额", EtaControlDanger.WRITE,
            schema(JSONObject().put("enabled", JSONObject().put("type", "boolean"))
                .put("allowed_tools", JSONObject().put("type", "array").put("maxItems", 128).put("uniqueItems", true).put("items", JSONObject().put("type", "string")))
                .put("enabled_extensions", JSONObject().put("type", "array").put("maxItems", 32).put("uniqueItems", true).put("items", JSONObject().put("type", "string")))
                .put("memory_mb", JSONObject().put("type", "integer").put("minimum", 8).put("maximum", 256))
                .put("timeout_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 120))), false),
            snapshot = { context, params -> configuration(CapabilityPackageStore(context).list().single { it.id == params.getString("package_id") }) },
            rollback = { context, before -> applyConfiguration(context, before) }, execute = ::applyConfiguration),
        EtaControlRegistration(EtaControlCapabilityInfo("packages.delete", "卸载扩展包", "能力", "停用并删除此扩展包，保留用户项目文件", EtaControlDanger.CONFIRM, schema(), false),
            execute = { context, params -> CapabilityPackageStore(context).delete(params.getString("package_id"), params.getString("digest")); JSONObject() }),
        EtaControlRegistration(EtaControlCapabilityInfo("packages.rollback", "回退扩展包", "能力", "回退上一版本，并重新确认启用", EtaControlDanger.CONFIRM, schema(), false),
            execute = { context, params -> CapabilityPackageStore(context).rollback(params.getString("package_id"), params.getString("digest")); JSONObject() }),
    )

    private fun schema(extra: JSONObject = JSONObject()): JSONObject {
        val properties = JSONObject().put("package_id", JSONObject().put("type", "string").put("minLength", 1))
            .put("digest", JSONObject().put("type", "string").put("minLength", 64).put("maxLength", 64))
        extra.keys().forEach { properties.put(it, extra.get(it)) }
        return JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(listOf("package_id", "digest"))).put("additionalProperties", false)
    }

    private fun configuration(record: CapabilityPackage) = JSONObject().put("package_id", record.id).put("digest", record.digest)
        .put("enabled", record.enabled).put("allowed_tools", JSONArray(record.allowedTools.sorted())).put("memory_mb", record.memoryMb)
        .put("timeout_seconds", record.timeoutSeconds)
        .put("enabled_extensions", JSONArray(record.enabledExtensions.sorted()))

    private fun applyConfiguration(context: Context, params: JSONObject): JSONObject {
        val tools = params.optJSONArray("allowed_tools")
        CapabilityPackageStore(context).configure(params.getString("package_id"),
            enabled = if (params.has("enabled")) params.getBoolean("enabled") else null,
            allowedTools = tools?.let { (0 until it.length()).map { i -> it.getString(i) }.toSet() },
            memoryMb = if (params.has("memory_mb")) params.getInt("memory_mb") else null,
            timeoutSeconds = if (params.has("timeout_seconds")) params.getInt("timeout_seconds") else null,
            expectedDigest = params.getString("digest"), enabledExtensions = params.optJSONArray("enabled_extensions")?.let { values ->
                (0 until values.length()).map { values.getString(it) }.toSet()
            })
        return JSONObject()
    }
}

/** Called after the local package review dialog; code installation remains outside the UI. */
internal class PackageApplicationService(context: Context) {
    private val context = context.applicationContext
    private val store = CapabilityPackageStore(this.context)
    fun installReviewed(preview: PackageImportPreview) = audited("packages.install", preview.content.id) { store.install(preview) }
    suspend fun deleteReviewed(record: CapabilityPackage) = auditedSuspend("packages.delete", record.id) { store.delete(record.id, record.digest) }
    fun rollbackReviewed(record: CapabilityPackage) = audited("packages.rollback", record.id) { store.rollback(record.id, record.digest) }
    fun environmentNames(record: CapabilityPackage) = PackageEnvironmentStore(context).names(record.id)
    fun notificationHistory(record: CapabilityPackage): List<PackageEventReceipt> = PackageEventJournal(context).use { it.history(record.id) }
    fun archiveNotificationReviewed(record: CapabilityPackage, runId: String) = audited("packages.archive_notification", record.id) {
        PackageEventJournal(context).use { journal -> check(journal.archiveUnknown(record.id, runId)) { "通知状态已变化，请重新读取" } }
    }
    fun saveEnvironmentReviewed(record: CapabilityPackage, name: String, value: String?) = audited("packages.environment", record.id) {
        require(store.list().any { it.id == record.id && it.digest == record.digest }) { "扩展包版本已变化" }
        PackageEnvironmentStore(context).put(record.id, name, value)
    }
    fun inspectReviewed(record: CapabilityPackage): JSONObject = audited("packages.inspect", record.id) {
        require(record.mainEntry != null) { "此包没有主注册入口" }
        val file = java.io.File.createTempFile("package-registration-", ".json", context.cacheDir)
        try {
            val payload = store.source(record, requireEnabled = false).put("entry", record.mainEntry)
                .put("main_entry", record.mainEntry).put("package_id", record.id).put("api_version", record.apiVersion)
                .put("function", "__nexus_registration__").put("arguments", JSONObject()).toString()
            require(payload.toByteArray().size <= 8 * 1024 * 1024) { "PACKAGE_SOURCE_LIMIT" }
            file.writeText(payload)
            PackageSandboxClient(context).use { client ->
                val result = client.invoke(file, 10000, record.memoryMb * 1024L * 1024) { _, _ ->
                    JSONObject().put("ok", false).put("code", "PACKAGE_REGISTRATION_SIDE_EFFECT_DENIED")
                }
                require(result.optBoolean("ok")) { result.optString("message", "主注册未完成") }
                store.saveRegistration(record, result.getJSONObject("value").getJSONArray("registrations"))
                result
            }
        } finally { file.delete() }
    }
    fun runActionReviewed(record: CapabilityPackage, tool: PackageTool, arguments: JSONObject,
        controller: io.github.mangi.eta.agent.runtime.AgentRunController, subject: io.github.mangi.eta.data.model.ConversationSubject? = null,
        deliveryRunId: String? = null, onEvent: (io.github.mangi.eta.agent.runtime.AgentEvent) -> Unit = {}): io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunResult {
        val current = store.list().single { it.id == record.id && it.enabled && it.digest == record.digest }
        val request = io.github.mangi.eta.agent.runtime.RuntimePackageActions.request(current, tool, arguments, subject, deliveryRunId)
        val client = io.github.mangi.eta.agent.runtime.AgentRuntimeClient(context, io.github.mangi.eta.core.AndroidAgentLogger)
        val result = client.run(request, controller = controller, onEvent = onEvent)
        runCatching { client.ackResult(result.runId.ifBlank { request.runId }) }
        return result
    }
    private fun <T> audited(capability: String, id: String, action: () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        val audit = EtaAppControlAuditStore(context)
        val before = store.list().firstOrNull { it.id == id }?.let { CapabilityPackageStore.encode(it) }
        audit.append("package_change_started", capability, JSONObject().put("package_id", id), false, durationMs = 0,
            before = before, origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
        try {
            val result = action()
            audit.append("execute", capability, JSONObject().put("package_id", id), true,
                durationMs = android.os.SystemClock.elapsedRealtime() - started, before = before,
                after = store.list().firstOrNull { it.id == id }?.let { CapabilityPackageStore.encode(it) },
                origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
            return result
        } catch (failure: Exception) {
            audit.append("execute", capability, JSONObject().put("package_id", id), false, errorCode = failure.javaClass.simpleName,
                durationMs = android.os.SystemClock.elapsedRealtime() - started, origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
            throw failure
        }
    }

    private suspend fun <T> auditedSuspend(capability: String, id: String, action: suspend () -> T): T {
        val started = android.os.SystemClock.elapsedRealtime()
        val audit = EtaAppControlAuditStore(context)
        val before = store.list().firstOrNull { it.id == id }?.let { CapabilityPackageStore.encode(it) }
        audit.append("package_change_started", capability, JSONObject().put("package_id", id), false, durationMs = 0,
            before = before, origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
        try {
            val result = action()
            audit.append("execute", capability, JSONObject().put("package_id", id), true,
                durationMs = android.os.SystemClock.elapsedRealtime() - started, before = before,
                after = store.list().firstOrNull { it.id == id }?.let { CapabilityPackageStore.encode(it) },
                origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
            return result
        } catch (failure: Exception) {
            audit.append("execute", capability, JSONObject().put("package_id", id), false, errorCode = failure.javaClass.simpleName,
                durationMs = android.os.SystemClock.elapsedRealtime() - started, origin = SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "package_center"))
            throw failure
        }
    }
}
