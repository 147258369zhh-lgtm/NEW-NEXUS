package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ConversationSubject
import org.json.JSONArray
import org.json.JSONObject

internal data class PackageInputToggle(val packageId: String, val digest: String, val tool: PackageTool, val id: String, val title: String,
    val checked: Boolean, val icon: String?)

internal class PackageInputToggles(context: Context,
    private val execute: ((CapabilityPackage, PackageTool, JSONObject, AgentRunController, ConversationSubject) -> String)? = null,
) {
    private val context = context.applicationContext
    private val store = CapabilityPackageStore(context)
    fun available(): Boolean = store.list().any { it.enabled && "InputMenuTogglePlugin" in it.enabledExtensions }
    fun load(subject: ConversationSubject, controller: AgentRunController): List<PackageInputToggle> =
        store.list().filter { it.enabled && "InputMenuTogglePlugin" in it.enabledExtensions }.flatMap { record ->
            record.tools.filter { it.callbackKey?.startsWith("InputMenuTogglePlugin/") == true }.map { record to it }
        }.take(8).flatMap { (record, tool) -> parse(record, tool, invoke(record, tool, subject, controller, "create")) }.also {
            require(it.map { value -> value.packageId to value.id }.distinct().size == it.size) { "输入开关编号重复" }
        }

    fun toggle(value: PackageInputToggle, subject: ConversationSubject, controller: AgentRunController): List<PackageInputToggle> {
        val record = store.list().singleOrNull { it.id == value.packageId && it.digest == value.digest && it.enabled &&
            "InputMenuTogglePlugin" in it.enabledExtensions && value.tool in it.tools } ?: error("输入开关扩展已变化")
        return parse(record, value.tool, invoke(record, value.tool, subject, controller, "toggle", value.id))
    }
    private fun invoke(record: CapabilityPackage, tool: PackageTool, subject: ConversationSubject, controller: AgentRunController, action: String, id: String? = null): Any? {
        val args = JSONObject().put("eventName", action).put("eventPayload", JSONObject().put("action", action)
            .put("toggleId", id).put("chatId", subject.id).put("runtime", "main"))
        val raw = if (execute != null) execute.invoke(record, tool, args, controller, subject) else {
            val result = PackageApplicationService(context).runActionReviewed(record, tool, args, controller, subject)
            check(result.ok) { "输入开关读取或保存未完成" }
            result.content
        }
        val envelope = JSONObject(raw)
        check(envelope.optBoolean("ok")) { "输入开关执行失败" }
        return envelope.opt("value")?.takeUnless { it == JSONObject.NULL }
    }
    internal fun parse(record: CapabilityPackage, tool: PackageTool, result: Any?): List<PackageInputToggle> {
        val array = when (result) {
            null -> return emptyList()
            is JSONArray -> result
            is JSONObject -> result.getJSONArray("toggles")
            else -> error("输入开关格式无效")
        }
        require(array.length() <= 16) { "单扩展最多 16 个输入开关" }
        return (0 until array.length()).map { index -> array.getJSONObject(index).let { row ->
            PackageInputToggle(record.id, record.digest, tool,
                row.getString("id").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}"))) },
                row.getString("title").also { require(it.length in 1..80 && it.none(Char::isISOControl)) },
                row.optBoolean("isChecked"), row.optString("icon").takeIf(String::isNotBlank)?.take(80))
        } }.also { require(it.map { row -> row.id }.distinct().size == it.size) { "输入开关编号重复" } }
    }
}
