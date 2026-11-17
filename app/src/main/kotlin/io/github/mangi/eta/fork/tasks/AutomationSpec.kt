package io.github.mangi.eta.fork.tasks

import org.json.JSONArray
import org.json.JSONObject

internal data class AutomationConditionResult(val matched: Boolean, val reasons: List<String> = emptyList()) {
    /** Bounded explanation of the unmet clauses; never contains trigger payload text. */
    val reason: String? get() = reasons.takeIf { it.isNotEmpty() }?.joinToString("；")?.take(MAX_REASON_LENGTH)

    private companion object { const val MAX_REASON_LENGTH = 240 }
}

/**
 * Persisted, bounded device-condition tree. Each node combines its own finite comparisons and its
 * [groups] with all (AND) or any (OR); nesting and node counts are capped by [MAX_DEPTH]/[MAX_NODES].
 * Evaluation only reads [AutomationState] and never interprets free text as state or authorization.
 */
internal data class AutomationCondition(val charging: Boolean? = null, val network: Boolean? = null,
    val unlocked: Boolean? = null, val headset: Boolean? = null, val airplaneMode: Boolean? = null,
    val minimumBattery: Int = 0, val maximumBattery: Int = 0, val any: Boolean = false,
    val groups: List<AutomationCondition> = emptyList()) {
    fun matches(state: AutomationState): Boolean = evaluate(state).matched

    /** Explains why the condition does not hold, or null when it matches. */
    fun mismatch(state: AutomationState): String? = evaluate(state).let { if (it.matched) null else it.reason ?: "设备条件未满足" }

    fun evaluate(state: AutomationState): AutomationConditionResult = evaluate(state, 0)

    private fun evaluate(state: AutomationState, depth: Int): AutomationConditionResult {
        require(depth <= MAX_DEPTH) { "条件最多嵌套 $MAX_DEPTH 层" }
        val parts = ArrayList<Pair<Boolean, String>>(4)
        fun clause(matches: Boolean, reason: String) { parts += matches to reason }
        charging?.let { clause(it == state.charging, "充电状态应为${if (it) "充电中" else "未充电"}，当前${if (state.charging) "充电中" else "未充电"}") }
        network?.let { clause(it == state.network, "网络应${if (it) "可用" else "不可用"}，当前${if (state.network) "可用" else "不可用"}") }
        unlocked?.let { clause(it == state.unlocked, "设备应${if (it) "已解锁" else "已锁定"}，当前${if (state.unlocked) "已解锁" else "已锁定"}") }
        headset?.let { clause(it == state.headset, "耳机应${if (it) "已连接" else "未连接"}，当前${if (state.headset) "已连接" else "未连接"}") }
        airplaneMode?.let { clause(it == state.airplaneMode, "飞行模式应${if (it) "已开启" else "已关闭"}，当前${if (state.airplaneMode) "已开启" else "已关闭"}") }
        if (minimumBattery > 0) clause(state.battery >= minimumBattery, "电量应不低于 $minimumBattery%，当前 ${state.battery}%")
        if (maximumBattery > 0) clause(state.battery <= maximumBattery, "电量应不高于 $maximumBattery%，当前 ${state.battery}%")
        groups.forEachIndexed { index, child ->
            val result = child.evaluate(state, depth + 1)
            clause(result.matched, "子条件 ${index + 1}：${result.reason ?: "未满足"}")
        }
        if (parts.isEmpty()) return AutomationConditionResult(true)
        if (any) {
            if (parts.any { it.first }) return AutomationConditionResult(true)
            return AutomationConditionResult(false, listOf("任一条件均未满足：${parts.joinToString("；") { it.second }}"))
        }
        val failures = parts.filterNot { it.first }.map { it.second }
        return AutomationConditionResult(failures.isEmpty(), failures)
    }

    fun validate() {
        val queue = ArrayDeque<Pair<AutomationCondition, Int>>().apply { add(this@AutomationCondition to 0) }
        var nodes = 0
        var clauses = 0
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            require(depth <= MAX_DEPTH) { "条件最多嵌套 $MAX_DEPTH 层" }
            require(++nodes <= MAX_NODES) { "条件节点最多 $MAX_NODES 个" }
            require(node.minimumBattery in 0..100 && node.maximumBattery in 0..100) { "电量条件应在 0–100 之间" }
            require(node.minimumBattery == 0 || node.maximumBattery == 0 || node.minimumBattery <= node.maximumBattery) {
                "电量下限不能高于上限"
            }
            clauses += node.ownClauseCount() + node.groups.size
            require(clauses <= MAX_CLAUSES) { "条件最多 $MAX_CLAUSES 项" }
            node.groups.forEach { queue.add(it to depth + 1) }
        }
    }

    private fun ownClauseCount(): Int = listOfNotNull(charging, network, unlocked, headset, airplaneMode).size +
        (if (minimumBattery > 0) 1 else 0) + (if (maximumBattery > 0) 1 else 0)

    fun json(): JSONObject = JSONObject().put("charging", charging ?: JSONObject.NULL).put("network", network ?: JSONObject.NULL)
        .put("unlocked", unlocked ?: JSONObject.NULL).put("headset", headset ?: JSONObject.NULL)
        .put("airplaneMode", airplaneMode ?: JSONObject.NULL).put("minimumBattery", minimumBattery)
        .put("maximumBattery", maximumBattery).put("any", any)
        .apply { if (groups.isNotEmpty()) put("groups", JSONArray(groups.map { it.json() })) }

    companion object {
        private const val MAX_DEPTH = 3
        private const val MAX_NODES = 16
        private const val MAX_CLAUSES = 32
        private val KEYS = setOf("charging", "network", "unlocked", "headset", "airplaneMode",
            "minimumBattery", "maximumBattery", "any", "groups")

        fun parse(j: JSONObject, depth: Int = 0): AutomationCondition {
            require(depth <= MAX_DEPTH) { "条件最多嵌套 $MAX_DEPTH 层" }
            val unknown = j.keys().asSequence().filterNot(KEYS::contains).toList()
            require(unknown.isEmpty()) { "无法识别的条件字段：${unknown.take(3).joinToString()}" }
            fun optionalBoolean(key: String): Boolean? = if (!j.has(key) || j.isNull(key)) null else j.getBoolean(key)
            val groups = j.optJSONArray("groups")?.let { array ->
                require(array.length() <= MAX_NODES) { "条件节点最多 $MAX_NODES 个" }
                (0 until array.length()).map { parse(array.getJSONObject(it), depth + 1) }
            }.orEmpty()
            return AutomationCondition(
                charging = optionalBoolean("charging"),
                network = optionalBoolean("network"),
                unlocked = optionalBoolean("unlocked"),
                headset = optionalBoolean("headset"),
                airplaneMode = optionalBoolean("airplaneMode"),
                minimumBattery = j.optInt("minimumBattery").also { require(it in 0..100) { "最低电量应为 0–100" } },
                maximumBattery = j.optInt("maximumBattery").also { require(it in 0..100) { "最高电量应为 0–100" } },
                any = if (j.has("any") && !j.isNull("any")) j.getBoolean("any") else false,
                groups = groups,
            ).also { it.validate() }
        }
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
            AutomationTrigger.BLUETOOTH_CONNECTED, AutomationTrigger.BLUETOOTH_DISCONNECTED -> {
                require(source.length <= 248 && source.none(Char::isISOControl)) { "蓝牙设备地址或名称无效" }
            }
            else -> Unit
        }
    }
}

internal enum class WorkflowStepKind(val label: String) { AGENT("Agent 执行"), SKILL("调用 Skill"), WAIT("等待"), CONDITION("条件分支"), NOTIFY("发送通知") }
internal enum class WorkflowLoopMode(val label: String) { COUNT("固定次数"), WHILE("条件满足时重复"), UNTIL("执行到条件满足") }
internal enum class WorkflowFailure(val label: String) { STOP("停止流程"), CONTINUE("继续下一步") }
internal data class WorkflowStep(val id: String, val kind: WorkflowStepKind, val title: String,
    val prompt: String = "", val target: String = "default", val skillId: String = "", val waitSeconds: Int = 0,
    val conditions: AutomationCondition = AutomationCondition(), val skipOnFalse: Int = 1,
    val repeats: Int = 1, val retries: Int = 0, val timeoutSeconds: Int = 120, val failure: WorkflowFailure = WorkflowFailure.STOP,
    val loopMode: WorkflowLoopMode = WorkflowLoopMode.COUNT) {
    fun json() = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("prompt", prompt)
        .put("target", target).put("skillId", skillId).put("waitSeconds", waitSeconds).put("conditions", conditions.json())
        .put("skipOnFalse", skipOnFalse).put("repeats", repeats).put("retries", retries).put("timeoutSeconds", timeoutSeconds).put("failure", failure.name).put("loopMode", loopMode.name)
    companion object {
        fun parse(j: JSONObject) = WorkflowStep(j.getString("id"), WorkflowStepKind.valueOf(j.getString("kind")), j.getString("title"),
            j.optString("prompt"), j.optString("target", "default"), j.optString("skillId"), j.optInt("waitSeconds"),
            AutomationCondition.parse(j.optJSONObject("conditions") ?: JSONObject()), j.optInt("skipOnFalse", 1),
            j.optInt("repeats", 1), j.optInt("retries"), j.optInt("timeoutSeconds", 120), WorkflowFailure.valueOf(j.optString("failure", "STOP")),
            WorkflowLoopMode.valueOf(j.optString("loopMode", "COUNT")))
    }
}

internal data class AutomationSpec(val steps: List<WorkflowStep> = emptyList(), val condition: AutomationCondition = AutomationCondition(),
    val timeoutSeconds: Int = 540, val allowedTools: List<String>? = null, val disallowedTools: List<String> = emptyList(), val projectId: String? = null,
    val maxEventRunsPerDay: Int = 20) {
    fun validate() {
        condition.validate()
        require(allowedTools.orEmpty().size <= 256 && disallowedTools.size <= 256)
        require(maxEventRunsPerDay in 1..200)
        require(steps.size <= 20 && steps.sumOf { it.repeats } <= 40) { "最多 20 步、累计 40 次执行" }
        require(steps.map { it.id }.distinct().size == steps.size) { "步骤 ID 重复" }
        require(timeoutSeconds in 10..540) { "流程总超时为 10–540 秒" }
        steps.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")) && it.id !in setOf("task", "previous", "definition") && it.title.length in 1..100) { "步骤 ID 无效或与任务变量冲突" }
            require(it.repeats in 1..5 && it.retries in 0..2 && it.timeoutSeconds in 10..300)
            require(it.waitSeconds in 0..300 && it.skipOnFalse in 0..20)
            require(it.prompt.length <= 12000 && it.skillId.length <= 64)
            it.conditions.validate()
            if (it.kind == WorkflowStepKind.CONDITION) require(it.loopMode == WorkflowLoopMode.COUNT) { "条件分支不支持循环模式" }
            require(it.kind != WorkflowStepKind.CONDITION || it.loopMode == WorkflowLoopMode.COUNT) {
                "条件分支步骤不能配置循环模式"
            }
            require(it.target.length in 1..120)
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
