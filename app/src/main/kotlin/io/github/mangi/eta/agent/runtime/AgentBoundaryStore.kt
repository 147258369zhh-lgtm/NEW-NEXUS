package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.config.Prefs
import org.json.JSONObject

/** Missing configuration preserves existing defaults; corrupt configuration never expands access. */
internal object AgentBoundaryStore {
    private const val KEY = "agent_tool_boundary_v1"
    private const val PROMPT = "agent_behavior_prompt_v1"
    private const val HISTORY = "agent_behavior_history_v1"
    fun behaviorHistory(): List<Pair<Long, String>> = runCatching {
        val values = org.json.JSONArray(Prefs.localAgentPreferences()?.getString(HISTORY, "[]") ?: "[]")
        (0 until values.length()).map { values.getJSONObject(it).let { j -> j.getLong("time") to j.getString("text") } }.take(5)
    }.getOrDefault(emptyList())
    fun load(): AgentToolPolicy {
        val raw = Prefs.localAgentPreferences()?.getString(KEY, null) ?: return AgentToolPolicy.UNRESTRICTED
        return runCatching {
            val j = JSONObject(raw); require(j.getInt("version") == 1)
            val denied = j.getJSONArray("denied")
            val allowed = j.optJSONArray("allowed")
            AgentToolPolicy(allowedTools = allowed?.let { (0 until it.length()).map(it::getString) }, disallowedTools = (0 until denied.length()).map(denied::getString))
        }.getOrElse { AgentToolPolicy(allowedTools = emptyList()) }
    }
    fun save(policy: AgentToolPolicy): Boolean = Prefs.localAgentPreferences()?.edit()?.putString(KEY,
        JSONObject().put("version", 1).put("allowed", policy.allowedTools?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
            .put("denied", org.json.JSONArray(policy.disallowedTools)).toString())?.commit() == true
    fun behavior(): String = Prefs.localAgentPreferences()?.getString(PROMPT, "").orEmpty().take(4000)
    fun saveBehavior(text: String): Boolean {
        require(text.length <= 4000)
        val old = behavior()
        val history = if (old == text) behaviorHistory() else (listOf(System.currentTimeMillis() to old) + behaviorHistory()).take(5)
        return Prefs.localAgentPreferences()?.edit()?.putString(PROMPT, text)
            ?.putString(HISTORY, org.json.JSONArray(history.map { (time, content) -> JSONObject().put("time", time).put("text", content) }).toString())?.commit() == true
    }
    val groups = linkedMapOf(
        "屏幕操作" to listOf("tap", "tap_*", "long_press*", "swipe", "scroll*", "type_text", "input_text", "replace_text", "clear_text", "paste_text", "press_key", "launch_app", "open_uri", "present_to_user", "open_system_panel", "set_clipboard"),
        "终端与 Root" to listOf("terminal*", "root_*", "shell*"),
        "浏览器与网页" to listOf("browser*", "web_*", "fetch_url"),
        "写入与删除文件" to listOf("write_file", "edit_file", "delete_file", "move_file", "copy_file", "mkdir*"),
        "设备设置与操作" to io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filter { it.write }.map { it.name },
        "个人资料与敏感读取" to io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.filter { !it.write && it.personal }.map { it.name },
        "写入记忆与学习" to listOf("memory_write", "learning_*"),
        "安装与修改 Skill" to listOf("skills_install*", "skills_manage"),
        "MCP 外部工具" to listOf("mcp__*", "mcp_*"),
        "REST API 连接" to listOf("api_*"),
        "子 Agent" to listOf("subagent*"),
        "自动任务管理" to listOf("schedule_*", "task_*", "tasks_*", "automation_*"),
    )
}
