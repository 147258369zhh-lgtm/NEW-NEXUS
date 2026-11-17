package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

internal object RuntimePreferenceControlProvider : EtaControlProvider {
    override val providerId = "runtime_preferences"
    private val titles = mapOf(
        Prefs.Keys.AGENT_TERMINAL_TOOLS to "终端与文件工具", Prefs.Keys.AGENT_BROWSER_TOOLS to "网页工具",
        Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to "设备工具", Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to "敏感设备信息",
        Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to "敏感设备操作", Prefs.Keys.AGENT_THINKING_ENABLED to "默认思考",
        Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED to "自动压缩上下文", Prefs.Keys.AGENT_SUBAGENT_ENABLED to "子 Agent 工具",
        Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED to "虚拟屏工具",
    )
    fun id(key: String): String {
        require(key in titles) { "RUNTIME_SETTING_UNSUPPORTED" }
        return "settings.runtime.$key"
    }

    override fun capabilities(): List<EtaControlRegistration> = titles.map { (key, title) ->
        val ordinary = key in setOf(Prefs.Keys.AGENT_THINKING_ENABLED, Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED)
        EtaControlRegistration(
            info = EtaControlCapabilityInfo(id(key), title, "运行设置", "修改$title；开关不授予 Android 系统权限，也不代替高风险操作确认",
                if (ordinary) EtaControlDanger.WRITE else EtaControlDanger.CONFIRM,
                JSONObject().put("type", "object").put("properties", JSONObject().put("enabled", JSONObject().put("type", "boolean")))
                    .put("required", JSONArray().put("enabled")).put("additionalProperties", false), true),
            read = { context -> current(context, key) },
            rollback = { context, before -> write(context, key, before.getBoolean("enabled")) },
            execute = { context, parameters -> write(context, key, parameters.getBoolean("enabled")) },
        )
    }

    private fun current(context: Context, key: String): JSONObject {
        Prefs.initLocal(context)
        return JSONObject().put("enabled", requireNotNull(Prefs.localAgentPreferences()).getBoolean(key, Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key)))
    }

    private fun write(context: Context, key: String, enabled: Boolean): JSONObject {
        Prefs.initLocal(context)
        check(requireNotNull(Prefs.localAgentPreferences()).edit().putBoolean(key, enabled).commit()) { "RUNTIME_SETTING_SAVE_FAILED" }
        Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
        return current(context, key)
    }
}
