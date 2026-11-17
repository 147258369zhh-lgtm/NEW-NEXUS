package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.model.PackageAiAgentProvider
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONObject

internal class PackageAiCatalog(private val context: Context, private val invoker: PackageAiInvoker = PackageAiInvoker(context)) {
    fun test(provider: ProviderSetting, controller: AgentRunController): String {
        val declaration = PackageAiRegistry(context).resolve(provider.id)
        if (declaration.callback("testConnection") == null) return "连接成功 · ${list(provider, controller).size} 个模型"
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, Model("probe", "probe", "probe", toolCall = false))
        val result = invoker.invoke(declaration, "testConnection", config, PackageAiAgentProvider.payload(declaration, config), controller).value
        check(result.getBoolean("success")) { "插件模型连接测试失败" }
        return result.optString("message").takeIf(String::isNotBlank)?.take(300) ?: "连接成功"
    }
    fun list(provider: ProviderSetting, controller: AgentRunController): List<Model> {
        val declaration = PackageAiRegistry(context).resolve(provider.id)
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, Model("catalog", "catalog", "catalog", toolCall = false))
        val value = invoker.invoke(declaration, "listModels", config, PackageAiAgentProvider.payload(declaration, config), controller).value
        val models = value.getJSONArray("models")
        require(models.length() <= 500) { "插件模型目录超过 500 项" }
        val names = hashSetOf<String>()
        return (0 until models.length()).map { index -> models.getJSONObject(index).let {
            val id = it.getString("id").trim()
            require(id.length in 1..256 && id.none(Char::isISOControl) && names.add(id.lowercase())) { "插件模型编号无效或重复" }
            val contextWindow = (it.opt("contextWindow") as? Number)?.toInt()?.takeIf { size -> size in 1..100_000_000 }
            Model("package-model-" + java.security.MessageDigest.getInstance("SHA-256").digest("${provider.id}:$id".toByteArray())
                .joinToString("") { b -> "%02x".format(b.toInt() and 255) }, id,
                it.optString("name").ifBlank { id }.take(256), source = ModelSource.REMOTE, contextWindow = contextWindow,
                contextWindowOverride = contextWindow, toolCall = it.optBoolean("supportsTools", false), reasoning = it.optBoolean("supportsReasoning", false))
        } }
    }
}
