package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LsposedRequirement
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.ToolSystemAccess
import io.github.zhh_lgtm.nexus.application.api.CapabilityActivation
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.CapabilitySummary
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.json.JSONObject

/** Lazy model-facing capability directory backed by the existing tool schemas. */
internal class NexusCapabilityDirectory private constructor(context: Context) : CapabilityDirectory {
    private val appContext = context.applicationContext
    private val schemas = linkedMapOf<String, JSONObject>()
    private val descriptors = linkedMapOf<String, ToolDescriptor>()
    private val active = linkedMapOf<String, CapabilityActivation>()

    init {
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
        )
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
                replayPolicy = NexusToolEffectClassifier.replayPolicy(effect),
                timeoutMs = 60_000,
                hostKind = HostKind.NATIVE_ANDROID,
            )
        }
    }

    override fun list(query: String): List<CapabilitySummary> {
        val needle = query.trim()
        val capabilities = AgentToolCapabilities.capture(appContext)
        return synchronized(active) {
            descriptors.values.asSequence()
                .filter { descriptor ->
                    needle.isEmpty() || listOf(descriptor.id, descriptor.namespace, descriptor.shortDescription)
                        .any { it.contains(needle, ignoreCase = true) }
                }
                .map { descriptor ->
                    CapabilitySummary(
                        id = descriptor.id,
                        namespace = descriptor.namespace,
                        description = descriptor.shortDescription,
                        effectClass = descriptor.effectClass,
                        available = capabilities.unavailableCode(descriptor.id) == null,
                        active = descriptor.id in active,
                    )
                }
                .sortedWith(compareBy(CapabilitySummary::namespace, CapabilitySummary::id))
                .toList()
        }
    }

    override fun describe(toolId: String): ToolDescriptor? = descriptors[toolId]

    override fun activate(toolId: String, expiresAt: Long): CapabilityActivation {
        val descriptor = descriptors[toolId] ?: error("Unknown capability: $toolId")
        require(expiresAt > 0) { "Activation expiry must be positive" }
        val unavailable = AgentToolCapabilities.capture(appContext).unavailableCode(descriptor.id)
        require(unavailable == null) { unavailable.orEmpty() }
        return CapabilityActivation(descriptor.id, descriptor.inputSchemaRef, expiresAt)
            .also { synchronized(active) { active[toolId] = it } }
    }

    internal fun schema(toolId: String): JSONObject? = schemas[toolId]?.let { JSONObject(it.toString()) }

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

    companion object {
        @Volatile
        private var instance: NexusCapabilityDirectory? = null

        fun get(context: Context): NexusCapabilityDirectory = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.appContext === app } ?: NexusCapabilityDirectory(app).also { instance = it }
        }
    }
}

internal object NexusToolEffectClassifier {
    private val readOnly = setOf(
        "get_current_context", "device_status", "inspect_app", "get_clipboard", "memory_get",
        "character_memory_get", "skills_list", "skills_read", "skills_read_resource",
        "skills_list_curated", "schedule_list", "learning_history", "observe_screen",
        "wait", "wait_for_text", "wait_for_package", "web_search", "fetch_url",
        "browser_use", "read_file", "list_directory", "stat_file", "glob_files", "grep_files",
        "read_image", "recent_notifications", "search_notification_history", "recent_app_activity",
        "app_usage_summary", "get_current_location", "get_device_environment", "get_setting",
        "memory_search", "conversation_history",
    )
    private val gui = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe", "scroll",
        "scroll_element", "type_text", "input_text", "replace_text", "clear_text", "paste_text",
        "press_key", "open_system_panel", "present_to_user", "launch_app", "open_uri",
    )
    private val reversibleWrite = setOf(
        "write_file", "edit_file", "set_clipboard", "memory_write", "character_memory_write",
        "schedule_create", "schedule_pause", "schedule_delete", "set_alarm", "set_timer",
        "goal_manage", "plan_manage", "notepad_manage",
    )
    private val idempotentWrite = setOf("set_volume", "set_device_state")
    private val external = setOf("connector_call", "voice_synthesize")
    private val highRisk = setOf(
        "terminal", "terminal_job", "terminal_daemon", "eta_control", "automation_manage",
        "skills_install_from_github", "skills_inspect_github", "app_state_control", "set_setting",
        "audio_record", "character_memory_generate",
    )

    fun classify(toolId: String): EffectClass = when (toolId) {
        in readOnly -> EffectClass.READ
        in gui -> EffectClass.GUI_ACTION
        in reversibleWrite -> EffectClass.REVERSIBLE_WRITE
        in idempotentWrite -> EffectClass.IDEMPOTENT_WRITE
        in external -> EffectClass.EXTERNAL_SIDE_EFFECT
        in highRisk -> EffectClass.HIGH_RISK
        else -> if (toolId.startsWith("search_") || toolId.startsWith("list_")) {
            EffectClass.READ
        } else {
            EffectClass.HIGH_RISK
        }
    }

    fun replayPolicy(effect: EffectClass): ReplayPolicy = when (effect) {
        EffectClass.READ -> ReplayPolicy.AUTOMATIC
        EffectClass.REVERSIBLE_WRITE, EffectClass.IDEMPOTENT_WRITE -> ReplayPolicy.VERIFY_THEN_RESUME
        EffectClass.DESTRUCTIVE -> ReplayPolicy.NEVER
        EffectClass.NON_IDEMPOTENT_WRITE,
        EffectClass.EXTERNAL_SIDE_EFFECT,
        EffectClass.GUI_ACTION,
        EffectClass.HIGH_RISK,
        -> ReplayPolicy.REQUIRE_USER
    }
}
