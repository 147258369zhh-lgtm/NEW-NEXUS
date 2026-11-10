package io.github.mangi.eta.fork.tasks

import org.json.JSONArray
import org.json.JSONObject

internal data class AutomationCondition(val charging: Boolean? = null, val network: Boolean? = null,
    val unlocked: Boolean? = null, val minimumBattery: Int = 0, val any: Boolean = false) {
    fun matches(state: AutomationState): Boolean {
        val checks = buildList {
            charging?.let { add(it == state.charging) }; network?.let { add(it == state.network) }
            unlocked?.let { add(it == state.unlocked) }; if (minimumBattery > 0) add(state.battery >= minimumBattery)
        }
        return checks.isEmpty() || if (any) checks.any { it } else checks.all { it }
    }
    fun json() = JSONObject().put("charging", charging ?: JSONObject.NULL).put("network", network ?: JSONObject.NULL)
        .put("unlocked", unlocked ?: JSONObject.NULL).put("minimumBattery", minimumBattery).put("any", any)
    companion object {
        fun parse(j: JSONObject) = AutomationCondition(
            if (j.isNull("charging")) null else j.getBoolean("charging"),
            if (j.isNull("network")) null else j.getBoolean("network"),
            if (j.isNull("unlocked")) null else j.getBoolean("unlocked"), j.optInt("minimumBattery").also { require(it in 0..100) }, j.optBoolean("any"))
    }
}
internal data class AutomationState(
    val charging: Boolean,
    val network: Boolean,
    val unlocked: Boolean,
    val battery: Int,
    val headset: Boolean = false,
    val airplaneMode: Boolean = false,
)

internal enum class AutomationTrigger(val label: String) {
    MANUAL("手动 / 语音指令"),
    CHARGING("充电 · 连接电源"),
    CHARGING_STOPPED("充电 · 断开电源"),
    NETWORK("连接 · 网络条件满足"),
    NETWORK_CONNECTED("连接 · 网络恢复"),
    NETWORK_DISCONNECTED("连接 · 网络断开"),
    WIFI_CONNECTED("连接 · 接入 Wi‑Fi"),
    WIFI_DISCONNECTED("连接 · 断开 Wi‑Fi"),
    MOBILE_CONNECTED("连接 · 接入移动网络"),
    MOBILE_DISCONNECTED("连接 · 断开移动网络"),
    BLUETOOTH_CONNECTED("蓝牙 · 已连接"),
    BLUETOOTH_DISCONNECTED("蓝牙 · 已断开"),
    HEADSET_CONNECTED("音频 · 耳机已连接"),
    HEADSET_DISCONNECTED("音频 · 耳机已断开"),
    AIRPLANE_MODE_ON("连接 · 飞行模式开启"),
    AIRPLANE_MODE_OFF("连接 · 飞行模式关闭"),
    UNLOCKED("设备 · 解锁"),
    SCREEN_ON("设备 · 亮屏"),
    SCREEN_OFF("设备 · 熄屏"),
    BATTERY_ABOVE("电量 · 达到设定值"),
    BATTERY_BELOW("电量 · 低于设定值"),
    LOCATION_ENTER("位置 · 进入区域"),
    LOCATION_EXIT("位置 · 离开区域"),
    APP_START("Nexus 启动"),
    APP_OPENED("应用 · 打开指定应用"),
    BOOT("设备启动"),
    FILE_CREATED("文件 · 目录新文件"),
    FILE_CHANGED("文件 · 文件更新"),
    TASK_SUCCEEDED("任务 · 执行成功"),
    TASK_FAILED("任务 · 执行失败"),
    MEMORY_UPDATED("记忆 · 内容更新"),
    STATE("条件 · 满足设备条件")
}

internal data class LocationAutomationConfig(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
) {
    init {
        require(latitude in -90.0..90.0) { "纬度应在 -90 到 90 之间" }
        require(longitude in -180.0..180.0) { "经度应在 -180 到 180 之间" }
        require(radiusMeters in 50..50_000) { "区域半径应在 50–50000 米之间" }
    }

    fun encode(): String = "$latitude,$longitude,$radiusMeters"

    companion object {
        fun parse(raw: String): LocationAutomationConfig {
            val values = raw.split(',').map(String::trim)
            require(values.size == 3) { "位置区域格式无效" }
            return LocationAutomationConfig(
                latitude = values[0].toDoubleOrNull() ?: error("纬度格式无效"),
                longitude = values[1].toDoubleOrNull() ?: error("经度格式无效"),
                radiusMeters = values[2].toIntOrNull() ?: error("区域半径格式无效"),
            )
        }
    }
}

internal object AutomationTriggerConfig {
    fun batteryThreshold(raw: String): Int =
        (raw.toIntOrNull() ?: error("电量触发阈值无效")).also { require(it in 1..100) { "电量阈值应在 1–100 之间" } }

    fun validate(type: AutomationTrigger, source: String) {
        when (type) {
            AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW -> batteryThreshold(source)
            AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT -> LocationAutomationConfig.parse(source)
            AutomationTrigger.APP_OPENED -> require(source.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) {
                "请选择要监听的应用程序"
            }
            else -> Unit
        }
    }
}

internal enum class WorkflowStepKind(val label: String) { AGENT("Agent 执行"), SKILL("调用 Skill"), WAIT("等待"), CONDITION("条件分支"), NOTIFY("发送通知") }
internal enum class WorkflowFailure(val label: String) { STOP("停止流程"), CONTINUE("继续下一步") }
internal data class WorkflowStep(val id: String, val kind: WorkflowStepKind, val title: String,
    val prompt: String = "", val target: String = "default", val skillId: String = "", val waitSeconds: Int = 0,
    val conditions: AutomationCondition = AutomationCondition(), val skipOnFalse: Int = 1,
    val repeats: Int = 1, val retries: Int = 0, val timeoutSeconds: Int = 120, val failure: WorkflowFailure = WorkflowFailure.STOP) {
    fun json() = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("prompt", prompt)
        .put("target", target).put("skillId", skillId).put("waitSeconds", waitSeconds).put("conditions", conditions.json())
        .put("skipOnFalse", skipOnFalse).put("repeats", repeats).put("retries", retries).put("timeoutSeconds", timeoutSeconds).put("failure", failure.name)
    companion object {
        fun parse(j: JSONObject) = WorkflowStep(j.getString("id"), WorkflowStepKind.valueOf(j.getString("kind")), j.getString("title"),
            j.optString("prompt"), j.optString("target", "default"), j.optString("skillId"), j.optInt("waitSeconds"),
            AutomationCondition.parse(j.optJSONObject("conditions") ?: JSONObject()), j.optInt("skipOnFalse", 1),
            j.optInt("repeats", 1), j.optInt("retries"), j.optInt("timeoutSeconds", 120), WorkflowFailure.valueOf(j.optString("failure", "STOP")))
    }
}

internal data class AutomationSpec(val steps: List<WorkflowStep> = emptyList(), val condition: AutomationCondition = AutomationCondition(),
    val timeoutSeconds: Int = 540, val allowedTools: List<String>? = null, val disallowedTools: List<String> = emptyList(), val projectId: String? = null,
    val maxEventRunsPerDay: Int = 20) {
    fun validate() {
        require(condition.minimumBattery in 0..100 && allowedTools.orEmpty().size <= 256 && disallowedTools.size <= 256)
        require(maxEventRunsPerDay in 1..200)
        require(steps.size <= 20 && steps.sumOf { it.repeats } <= 40) { "最多 20 步、累计 40 次执行" }
        require(steps.map { it.id }.distinct().size == steps.size) { "步骤 ID 重复" }
        require(timeoutSeconds in 10..540) { "流程总超时为 10–540 秒" }
        steps.forEach {
            require(it.id.isNotBlank() && it.title.length in 1..100)
            require(it.repeats in 1..5 && it.retries in 0..2 && it.timeoutSeconds in 10..300)
            require(it.waitSeconds in 0..300 && it.skipOnFalse in 0..20)
            require(it.prompt.length <= 12000 && it.skillId.length <= 64)
            require(it.conditions.minimumBattery in 0..100 && it.target.length in 1..120)
            if (it.kind == WorkflowStepKind.SKILL) require(it.skillId.isNotBlank()) { "请选择 Skill" }
            if (it.kind in setOf(WorkflowStepKind.AGENT, WorkflowStepKind.SKILL)) require(it.prompt.isNotBlank()) { "步骤缺少任务内容" }
        }
    }
    fun json() = JSONObject().put("automationVersion", 1).put("steps", JSONArray(steps.map { it.json() }))
        .put("condition", condition.json()).put("timeoutSeconds", timeoutSeconds)
        .put("disallowedTools", JSONArray(disallowedTools)).put("projectId", projectId ?: JSONObject.NULL)
        .put("maxEventRunsPerDay", maxEventRunsPerDay)
        .put("allowedTools", allowedTools?.let(::JSONArray) ?: JSONObject.NULL).toString()
    companion object {
        fun parse(raw: String): AutomationSpec {
            val j = JSONObject(raw)
            if (!j.has("automationVersion")) return AutomationSpec()
            require(j.getInt("automationVersion") == 1) { "不支持的流程版本" }
            val array = j.getJSONArray("steps")
            val allow = j.optJSONArray("allowedTools")
            val deny = j.optJSONArray("disallowedTools")
            return AutomationSpec((0 until array.length()).map { WorkflowStep.parse(array.getJSONObject(it)) },
                AutomationCondition.parse(j.getJSONObject("condition")), j.getInt("timeoutSeconds"),
                allow?.let { (0 until it.length()).map(it::getString) }, deny?.let { (0 until it.length()).map(it::getString) }.orEmpty(),
                j.optString("projectId").takeIf { it.isNotBlank() && it != "null" }, j.optInt("maxEventRunsPerDay", 20)).also { it.validate() }
        }
    }
}
