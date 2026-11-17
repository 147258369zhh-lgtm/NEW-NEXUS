package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.ToolBatchSlot
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LsposedRequirement
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.ToolSystemAccess
import io.github.zhh_lgtm.nexus.application.api.ReplayPolicyEngine
import io.github.zhh_lgtm.nexus.application.api.ReplayVerdict
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.json.JSONObject

/**
 * 内置工具的唯一 descriptor 表：工具目录、执行前复核和中断恢复共用同一份分类结果，
 * 不再各自维护重放策略。构建不依赖 Context，恢复路径因此可以在没有 Activity 时判定。
 */
internal object NexusToolDescriptors {
    private val table: Table by lazy { build() }

    val schemas: Map<String, JSONObject> get() = table.schemas

    val byId: Map<String, ToolDescriptor> get() = table.descriptors

    fun describe(toolId: String): ToolDescriptor? = table.descriptors[toolId]

    /** 恢复判定入口：descriptor 未知 fail-closed，verifier 只认工具表里真实存在的只读工具。 */
    fun replayVerdict(toolId: String): ReplayVerdict {
        val descriptor = describe(toolId)
        val verifier = descriptor?.verifierId?.let(::describe)
        return ReplayPolicyEngine.decide(descriptor, verifierKnown = verifier?.effectClass == EffectClass.READ)
    }

    /**
     * 批次调度入口：并发语义只取本地 descriptor。外部 host 或插件自报的分类与并发键
     * 不参与这里，未登记的工具按串行处理。
     */
    fun batchSlot(toolId: String): ToolBatchSlot {
        val descriptor = describe(toolId) ?: return ToolBatchSlot.SERIAL
        val key = descriptor.concurrencyKey ?: return ToolBatchSlot.SERIAL
        return ToolBatchSlot(readOnly = descriptor.effectClass == EffectClass.READ, concurrencyKey = key)
    }

    private class Table(val schemas: Map<String, JSONObject>, val descriptors: Map<String, ToolDescriptor>)

    /** 写操作 → 可在同一目标上读取结果的只读工具；没有完全对应的回读工具就不声明 verifier。 */
    private val verifiers = mapOf(
        "set_clipboard" to "get_clipboard",
        "memory_write" to "memory_get",
    )

    /**
     * 只读工具的互斥资源键。同一键的调用会争用同一份快照或外部资源，批次内必须串行；
     * 不同键的只读调用可以并发。未列出的只读工具保持 null，按串行屏障处理。
     * 交互式或阻塞请求（request_user_input、会话历史等）不参与并发。
     */
    private val readConcurrencyKeys: Map<String, String> = buildMap {
        fun group(key: String, vararg tools: String) = tools.forEach { put(it, key) }
        group("device.snapshot", "get_current_context", "device_status", "inspect_app", "observe_screen",
            "get_device_environment")
        group("filesystem", "read_file", "list_directory", "stat_file", "glob_files", "grep_files",
            "read_image", "project_workspace_status")
        group("memory", "memory_get", "memory_search", "character_memory_get", "learning_history")
        group("skills", "skills_list", "skills_read", "skills_read_resource", "skills_list_curated")
        group("schedule", "schedule_list")
        group("network", "web_search", "fetch_url")
        group("notifications", "recent_notifications", "search_notification_history")
        group("app.usage", "recent_app_activity", "app_usage_summary")
        group("screen.wait", "wait", "wait_for_text", "wait_for_package")
        group("device.settings", "get_setting")
        group("device.clipboard", "get_clipboard")
        group("device.location", "get_current_location")
    }

    private fun build(): Table {
        val allCapabilities = AgentToolCapabilities(
            rootAvailable = true,
            lsposedAvailable = true,
            accessibilityAvailable = true,
            accessibilityRecoveryAvailable = true,
            notificationsAllowed = true,
            usageAllowed = true,
            locationAllowed = true,
            colorOs = true,
            calendarReadable = true,
            calendarWritable = true,
            flashlightAvailable = true,
            hotspotAvailable = true,
            nfcAvailable = true,
            nightLightAvailable = true,
        )
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = allCapabilities,
            localWebSearch = true,
            cloudTools = true,
        )
        val schemas = linkedMapOf<String, JSONObject>()
        val descriptors = linkedMapOf<String, ToolDescriptor>()
        for (index in 0 until tools.length()) {
            val function = tools.getJSONObject(index).getJSONObject("function")
            val id = function.getString("name")
            val description = function.optString("description", id)
            val effect = NexusToolEffectClassifier.classify(id)
            schemas[id] = function.getJSONObject("parameters")
            descriptors[id] = ToolDescriptor(
                id = id,
                namespace = id.substringBefore('_', "native"),
                shortDescription = description.take(180),
                modelDescription = description,
                inputSchemaRef = "nexus-tool://$id/input",
                requiredCapabilities = requiredCapabilities(id),
                effectClass = effect,
                replayPolicy = if (id == "request_user_input") ReplayPolicy.REQUIRE_USER
                else NexusToolEffectClassifier.replayPolicy(effect),
                timeoutMs = if (id == "request_user_input") 300_000 else 60_000,
                verifierId = verifiers[id],
                concurrencyKey = if (effect == EffectClass.READ) readConcurrencyKeys[id] else null,
                hostKind = if (id == "connector_call") HostKind.CONNECTOR else HostKind.NATIVE_ANDROID,
            )
        }
        return Table(schemas, descriptors)
    }

    private fun requiredCapabilities(toolId: String): Set<String> = buildSet {
        val requirement = AgentToolRequirements.find(toolId) ?: return@buildSet
        if (requirement.rootRequirement == RootRequirement.REQUIRED) add("device.root")
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED) add("device.lsposed")
        if (requirement.accessibility) add("device.accessibility")
        if (requirement.colorOs) add("device.coloros")
        when (requirement.systemAccess) {
            ToolSystemAccess.NOTIFICATIONS -> add("device.notifications")
            ToolSystemAccess.USAGE -> add("device.usage")
            ToolSystemAccess.LOCATION -> add("device.location")
            ToolSystemAccess.CALENDAR_READ -> add("calendar.read")
            ToolSystemAccess.CALENDAR_WRITE -> add("calendar.write")
            ToolSystemAccess.NONE -> Unit
        }
    }
}
