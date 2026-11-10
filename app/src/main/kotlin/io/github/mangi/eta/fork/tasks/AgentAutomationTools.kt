package io.github.mangi.eta.fork.tasks

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal object AgentAutomationTools {
    fun execute(context: Context, args: JSONObject, writesAllowed: Boolean): String = runBlocking {
        val db = AgentTaskDatabase.get(context); val dao = db.agentTaskDao()
        val action = args.getString("action")
        if (action == "list") return@runBlocking JSONObject().put("ok", true).put("tasks", JSONArray(dao.definitionsNow().map {
            JSONObject().put("id", it.id).put("title", it.title).put("enabled", it.enabled).put("workflow", JSONObject(it.policyJson))
        })).toString()
        require(writesAllowed) { "后台任务不能再创建、修改或手动触发自动任务；请使用已配置的任务链" }
        require(args.optBoolean("confirmed")) { "需先取得用户对任务内容与触发条件的明确同意" }
        val id = args.optString("id")
        when (action) {
            "save" -> {
                val existing = if (id.isBlank()) null else checkNotNull(dao.definition(id)) { "任务不存在" }
                val spec = AutomationSpec.parse(args.getJSONObject("workflow").put("automationVersion", 1).toString())
                val event = args.optJSONObject("trigger")
                val trigger = event?.let { j ->
                    val type = AutomationTrigger.valueOf(j.getString("type"))
                    val source = j.optString("source")
                    if (type in setOf(AutomationTrigger.FILE_CHANGED, AutomationTrigger.FILE_CREATED)) require(context.contentResolver.persistedUriPermissions.any { it.uri.toString() == source && it.isReadPermission }) { "请先在自动任务页授权该监控目录" }
                    AutomationTriggerConfig.validate(type, source)
                    if (type == AutomationTrigger.APP_OPENED) {
                        require(AutomationTriggers.hasUsageAccess(context)) { "请先授予使用情况访问权限" }
                        require(
                            runCatching {
                                context.packageManager.getApplicationInfo(
                                    source,
                                    android.content.pm.PackageManager.ApplicationInfoFlags.of(0L),
                                )
                            }.isSuccess,
                        ) { "目标应用未安装" }
                    }
                    AutomationTriggerEntity("", "", type.name, source, j.optString("extension"), j.optInt("cooldownSeconds", 900))
                }
                val schedule = args.optJSONObject("schedule")?.let { j -> AgentScheduleRule(AgentScheduleKind.valueOf(j.getString("kind")),
                    j.getLong("startAt"), intervalMinutes = j.optLong("intervalMinutes").takeIf { it > 0 }, localHour = j.optInt("hour"), localMinute = j.optInt("minute"),
                    weekdaysMask = j.optInt("weekdaysMask", 127), timeZoneId = j.optString("timeZoneId", java.time.ZoneId.systemDefault().id), monthDay = j.optInt("monthDay", 1)) }
                require(trigger == null || schedule == null) { "请选择一种触发方式" }
                if (schedule?.kind == AgentScheduleKind.INTERVAL) require(schedule.intervalMinutes!! >= 15)
                val saved = AutomationRepository(db).save(existing, args.getString("title"), args.getString("prompt"), spec, trigger, schedule)
                AgentTaskWorkScheduler.refreshNextDeadline(context); AgentTaskWorkScheduler.checkNow(context)
                JSONObject().put("ok", true).put("id", saved).toString()
            }
            "run" -> { require(dao.activeCount(id) == 0) { "该任务已有运行" }; val run = AgentTaskRepository(dao).runNow(id); require(run != null) { "任务不存在或已暂停" }; AgentTaskWorkScheduler.checkNow(context); JSONObject().put("ok", true).put("runId", run).toString() }
            "pause", "resume" -> { AutomationRepository(db).setEnabled(id, action == "resume"); AgentTaskWorkScheduler.refreshNextDeadline(context); JSONObject().put("ok", true).toString() }
            "delete" -> { require(dao.activeCount(id) == 0) { "请先结束运行" }; dao.deleteDefinition(id); AgentTaskWorkScheduler.refreshNextDeadline(context); JSONObject().put("ok", true).toString() }
            else -> error("不支持的自动任务操作")
        }
    }
    fun schema(): JSONObject = io.github.mangi.eta.agent.model.AgentToolSchema.function("automation_manage",
        "管理真实自动任务。action=list/save/run/pause/resume/delete；除 list 外先向用户明确说明任务和触发条件，用户同意后 confirmed=true。" +
            "save 的 workflow 是对象 {steps:[],condition:{},timeoutSeconds:540,allowedTools:null}；steps 项为 {id,kind:AGENT/SKILL/WAIT/CONDITION/NOTIFY,title,prompt,target,skillId,waitSeconds,conditions:{},skipOnFalse:1,repeats:1,retries:0,timeoutSeconds:120,failure:STOP/CONTINUE}。" +
            "condition/conditions 可有 charging/network/unlocked 布尔值、minimumBattery:0..100、any:false。" +
            "trigger 对象 {type:CHARGING/CHARGING_STOPPED/NETWORK_CONNECTED/NETWORK_DISCONNECTED/WIFI_CONNECTED/WIFI_DISCONNECTED/MOBILE_CONNECTED/MOBILE_DISCONNECTED/BLUETOOTH_CONNECTED/BLUETOOTH_DISCONNECTED/HEADSET_CONNECTED/HEADSET_DISCONNECTED/AIRPLANE_MODE_ON/AIRPLANE_MODE_OFF/UNLOCKED/SCREEN_ON/SCREEN_OFF/BATTERY_ABOVE/BATTERY_BELOW/LOCATION_ENTER/LOCATION_EXIT/APP_START/APP_OPENED/BOOT/FILE_CREATED/FILE_CHANGED/TASK_SUCCEEDED/TASK_FAILED/MEMORY_UPDATED/STATE,source,extension,cooldownSeconds:900}。" +
            "电量触发的 source 为 1..100 阈值；位置触发的 source 为 latitude,longitude,radiusMeters；文件触发 source 必须为用户已授权的目录 URI；任务链触发 source 为来源任务 ID。" +
            "APP_OPENED 的 source 为应用包名，并需要用户授予使用情况访问权限。" +
            "或 schedule 对象 {kind:ONCE/INTERVAL/DAILY/WEEKLY/MONTHLY,startAt:epoch毫秒,intervalMinutes,hour,minute,weekdaysMask,timeZoneId,monthDay}。二者皆空为手动。文件 source 必须为用户已授权的目录 URI。" +
            "工作流最多 20 步、累计 40 次、总时限 540 秒。后台任务不能递归使用此工具写入或运行其他任务。",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("list", "save", "run", "pause", "resume", "delete"))))
            .put("id", JSONObject().put("type", "string")).put("title", JSONObject().put("type", "string")).put("prompt", JSONObject().put("type", "string"))
            .put("workflow", JSONObject().put("type", "object")).put("trigger", JSONObject().put("type", "object")).put("schedule", JSONObject().put("type", "object"))
            .put("confirmed", JSONObject().put("type", "boolean"))).put("required", JSONArray().put("action")))
}
