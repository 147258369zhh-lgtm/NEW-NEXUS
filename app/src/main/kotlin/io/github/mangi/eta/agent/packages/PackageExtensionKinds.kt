package io.github.mangi.eta.agent.packages

import org.json.JSONArray
import org.json.JSONObject

internal object PackageExtensionKinds {
    val labels = linkedMapOf(
        "ChatInputHook" to "会话提交处理", "PromptInputHook" to "提示词输入处理",
        "PromptHistoryHook" to "历史上下文处理", "SystemPromptComposeHook" to "系统提示词组合",
        "ToolPromptComposeHook" to "工具提示词组合", "PromptFinalizeHook" to "提示词最终处理",
        "ToolLifecycleHook" to "工具拦截与回执",
        "MessageProcessingPlugin" to "消息处理", "ChatRuntimeHook" to "会话运行状态",
        "AppLifecycleHook" to "应用事件", "ChatViewHook" to "会话页面事件", "ChatMessageHook" to "消息保存事件",
        "InputMenuTogglePlugin" to "输入菜单开关",
        "AiProvider" to "模型提供商",
    )
    val automatic = setOf("AppLifecycleHook", "ChatViewHook", "ChatMessageHook", "InputMenuTogglePlugin")
    val ui = setOf("ToolboxUiModule", "UiRoute")
    fun declared(record: CapabilityPackage): Set<String> {
        val array = JSONArray(record.registrations)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("kind")?.takeIf(labels::containsKey) }.toSet()
    }
    fun callback(row: JSONObject): String? = row.optJSONObject("function")?.optString("nexus_callback")?.takeIf {
        it == "${row.optString("kind")}/${row.optString("id")}/function"
    }
}
