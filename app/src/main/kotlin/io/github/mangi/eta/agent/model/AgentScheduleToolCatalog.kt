package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentScheduleToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(schema(
            name = "schedule_create",
            description = "创建本地持久定时任务。必须先向用户说明标题、时间和提示词，并在用户明确确认后传 confirmed=true；不会在后台静默创建。",
            required = listOf("title", "prompt", "kind", "start_at", "confirmed"),
            properties = JSONObject()
                .put("title", string("任务标题"))
                .put("prompt", string("每次运行交给 Agent 的任务提示词"))
                .put("kind", JSONObject().put("type", "string").put("enum", JSONArray().put("ONCE").put("INTERVAL").put("DAILY").put("WEEKLY")))
                .put("start_at", JSONObject().put("type", "integer").put("description", "UTC epoch milliseconds"))
                .put("interval_minutes", JSONObject().put("type", "integer").put("description", "INTERVAL 必填，最小 15 分钟"))
                .put("local_hour", JSONObject().put("type", "integer").put("description", "DAILY/WEEKLY 的本地小时 0-23"))
                .put("local_minute", JSONObject().put("type", "integer").put("description", "DAILY/WEEKLY 的本地分钟 0-59"))
                .put("weekdays_mask", JSONObject().put("type", "integer").put("description", "WEEKLY 使用 Monday bit0 到 Sunday bit6"))
                .put("time_zone_id", string("IANA 时区，默认 UTC"))
                .put("confirmed", JSONObject().put("type", "boolean").put("description", "用户明确确认后才传 true"))
                .put("target_id", string("关联会话或会议 ID，默认 current"))
                .put("missed_run_policy", JSONObject().put("type", "string").put("enum", JSONArray().put("SKIP").put("RUN_ONCE").put("ASK_USER"))),
        ))
        tools.put(schema("schedule_list", "列出 Nexus 本地定时任务及其下次运行时间。", JSONObject()))
        tools.put(schema(
            name = "schedule_pause",
            description = "暂停或恢复一个定时任务。",
            required = listOf("schedule_id", "paused"),
            properties = JSONObject()
                .put("schedule_id", string("计划 ID"))
                .put("paused", JSONObject().put("type", "boolean")),
        ))
        tools.put(schema(
            name = "schedule_delete",
            description = "删除一个定时任务及其定义。执行前必须获得用户明确确认。",
            required = listOf("definition_id", "confirmed"),
            properties = JSONObject()
                .put("definition_id", string("定义 ID"))
                .put("confirmed", JSONObject().put("type", "boolean")),
        ))
    }

    private fun schema(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject =
        AgentToolSchema.function(name = name, description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("additionalProperties", false)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required))
                })

    private fun string(description: String): JSONObject = JSONObject().put("type", "string").put("description", description)
}
