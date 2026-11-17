package io.github.mangi.eta.agent.packages

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ProviderSettingsService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class PackageAiDeclaration(val record: CapabilityPackage, val row: JSONObject) {
    val id get() = row.getString("id")
    val providerId get() = "package-ai-" + MessageDigest.getInstance("SHA-256").digest("${record.id}\u0000$id".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val title get() = row.optString("displayName").ifBlank { record.title }
    fun callback(method: String): String? = row.optJSONObject(method)?.optJSONObject("function")?.optString("nexus_callback")?.takeIf {
        it == "AiProvider/$id/$method/function"
    }
}

/** Only package identity/version routing is stored here; model and connection settings stay in the original provider repository. */
internal class PackageAiRegistry(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.filesDir, "capability-packages/provider-bindings.json"))
    fun declarations(): List<PackageAiDeclaration> = CapabilityPackageStore(context).list().filter { it.enabled && "AiProvider" in it.enabledExtensions }
        .flatMap { record -> val rows = JSONArray(record.registrations); (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.takeIf { it.optString("kind") == "AiProvider" }?.let { PackageAiDeclaration(record, it) }
        } }
    fun resolve(providerId: String): PackageAiDeclaration = synchronized(LOCK) {
        val binding = read().optJSONObject(providerId) ?: error("插件模型尚未绑定，请重新接入")
        declarations().singleOrNull { it.providerId == providerId && it.record.id == binding.getString("packageId") &&
            it.id == binding.getString("registrationId") && it.record.digest == binding.getString("digest") }
            ?: error("插件模型已停用、卸载或更新，请重新确认接入")
    }
    fun available(providerId: String): Boolean = runCatching { resolve(providerId); true }.getOrDefault(false)
    fun isConnected(value: PackageAiDeclaration): Boolean = runCatching {
        val current = resolve(value.providerId)
        current.record.digest == value.record.digest && current.id == value.id &&
            kotlinx.coroutines.runBlocking { ProviderRepository.providerById(value.providerId) } != null
    }.getOrDefault(false)
    suspend fun connect(value: PackageAiDeclaration): ProviderSetting {
        val current = declarations().single { it.providerId == value.providerId && it.record.digest == value.record.digest }
        require(current.callback("listModels") != null && current.callback("sendMessage") != null) { "插件模型声明缺少必要回调" }
        synchronized(LOCK) {
            val data = read().put(value.providerId, JSONObject().put("packageId", current.record.id).put("registrationId", current.id).put("digest", current.record.digest))
            require(data.length() <= 128)
            file.baseFile.parentFile!!.mkdirs()
            val output = file.startWrite()
            try { output.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
        }
        return ProviderRepository.providerById(value.providerId) ?: ProviderSettingsService(context).save(CustomProviderSetting(value.providerId,
            current.title, "https://plugin.nexus.invalid", models = emptyList()))
    }

    /**
     * Deletes every provider that can belong to this package, including stale bindings whose
     * registration disappeared after an update. Credentials and model selection are removed by
     * ProviderRepository as part of the same operation.
     */
    suspend fun removePackage(packageId: String) {
        val record = CapabilityPackageStore(context).list().singleOrNull { it.id == packageId }
        val (declared, bound) = synchronized(LOCK) {
            val providerIds = linkedSetOf<String>()
            if (record != null) {
                val rows = JSONArray(record.registrations)
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index)?.takeIf { it.optString("kind") == "AiProvider" } ?: continue
                    providerIds += PackageAiDeclaration(record, row).providerId
                }
            }
            val data = read()
            val boundIds = data.keys().asSequence().filter { key -> data.optJSONObject(key)?.optString("packageId") == packageId }.toSet()
            providerIds += boundIds
            providerIds to boundIds
        }

        for (providerId in declared) {
            require(isPackageProvider(providerId)) { "插件模型编号无效" }
            ProviderRepository.deleteProvider(providerId)
        }

        if (bound.isNotEmpty()) synchronized(LOCK) {
            val data = read()
            for (providerId in bound) {
                if (data.optJSONObject(providerId)?.optString("packageId") == packageId) data.remove(providerId)
            }
            write(data)
        }
    }

    private fun write(data: JSONObject) {
        require(data.length() <= 128)
        file.baseFile.parentFile!!.mkdirs()
        val output = file.startWrite()
        try {
            output.write(data.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        return file.openRead().use { input -> val bytes = input.readNBytes(256 * 1024 + 1); require(bytes.size <= 256 * 1024); JSONObject(bytes.toString(Charsets.UTF_8)) }
    }
    companion object {
        private val LOCK = Any()
        fun isPackageProvider(id: String) = id.matches(Regex("package-ai-[a-f0-9]{64}"))
        fun validate(record: CapabilityPackage, row: JSONObject) {
            val value = PackageAiDeclaration(record, row)
            require(value.callback("listModels") != null && value.callback("sendMessage") != null) { "插件模型回调声明无效" }
            listOf("testConnection", "calculateInputTokens").forEach { method ->
                require(!row.has(method) || value.callback(method) != null) { "插件模型回调声明无效" }
            }
        }
    }
}
