package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.cloud.CloudToolAvailability
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.repository.CloudToolsSettingsRepository
import io.github.zhh_lgtm.nexus.application.api.CapabilityActivation
import io.github.zhh_lgtm.nexus.application.api.CapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.CapabilitySummary
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Lazy model-facing capability directory backed by the existing tool schemas. */
internal class NexusCapabilityDirectory private constructor(context: Context) : CapabilityDirectory {
    private val appContext = context.applicationContext
    /** 目录与恢复判定共用 [NexusToolDescriptors] 的同一份 descriptor，策略不会在两处漂移。 */
    private val schemas: Map<String, JSONObject> get() = NexusToolDescriptors.schemas
    private val descriptors: Map<String, ToolDescriptor> get() = NexusToolDescriptors.byId
    private val active = linkedMapOf<String, CapabilityActivation>()

    override fun list(query: String): List<CapabilitySummary> {
        val needle = query.trim()
        val capabilities = AgentToolCapabilities.capture(appContext)
        // Resolve secrets at most once per listing, and only if a cloud tool matches the query.
        val cloudConfig by lazy { currentCloudConfig() }
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
                        available = capabilities.unavailableCode(descriptor.id) == null &&
                            (!CloudToolAvailability.isCloudTool(descriptor.id) ||
                                cloudConfig?.let { CloudToolAvailability.isAvailable(descriptor.id, it) } == true),
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
        if (CloudToolAvailability.isCloudTool(toolId)) {
            require(currentCloudConfig()?.let { CloudToolAvailability.isAvailable(toolId, it) } == true) {
                "CLOUD_TOOL_UNAVAILABLE"
            }
        }
        return CapabilityActivation(descriptor.id, descriptor.inputSchemaRef, expiresAt)
            .also { synchronized(active) { active[toolId] = it } }
    }

    internal fun schema(toolId: String): JSONObject? = schemas[toolId]?.let { JSONObject(it.toString()) }

    private fun currentCloudConfig(): CloudToolsRuntimeConfig? = try {
        runBlocking(Dispatchers.IO) { CloudToolsSettingsRepository.resolveRuntimeConfig(appContext) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
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
    /** Small, locally owned allowlist. Unknown shell syntax and external annotations stay denied. */
    fun isProvenReadOnlyCall(toolId: String, arguments: JSONObject): Boolean = when (toolId) {
        "terminal_job" -> arguments.optString("action") in setOf("list", "read", "wait")
        "terminal_daemon" -> arguments.optString("action") in setOf("list", "logs")
        "terminal" -> arguments.optString("action", "exec") == "exec" &&
            arguments.optString("session_id").isBlank() &&
            arguments.optString("command").trim() in READ_ONLY_FIXED_COMMANDS
        else -> false
    }
    private val READ_ONLY_FIXED_COMMANDS = setOf(
        "/system/bin/pwd", "/system/bin/id", "/system/bin/id -u", "/system/bin/uname", "/system/bin/uname -a",
        "/usr/bin/pwd", "/usr/bin/id", "/usr/bin/id -u", "/usr/bin/uname", "/usr/bin/uname -a",
    )
    private val readOnly = setOf(
        "get_current_context", "device_status", "network_info", "inspect_app", "get_clipboard", "memory_get",
        "character_memory_get", "skills_list", "skills_read", "skills_read_resource",
        "skills_list_curated", "schedule_list", "learning_history", "observe_screen",
        "wait", "wait_for_text", "wait_for_package", "web_search", "fetch_url",
        "read_file", "list_directory", "stat_file", "glob_files", "grep_files",
        "read_image", "recent_notifications", "search_notification_history", "recent_app_activity",
        "app_usage_summary", "get_current_location", "get_device_environment", "get_setting",
        "memory_search", "conversation_history", "request_user_input",
        "project_workspace_status",
    )
    private val gui = setOf(
        "tap", "tap_area", "tap_element", "long_press", "long_press_element", "swipe", "scroll",
        "scroll_element", "type_text", "input_text", "replace_text", "clear_text", "paste_text",
        "press_key", "open_system_panel", "present_to_user", "launch_app", "open_uri", "browser_use",
    )
    private val reversibleWrite = setOf(
        "write_file", "edit_file", "deliver_file", "set_clipboard", "memory_write", "character_memory_write",
        "make_directory", "copy_file", "move_file", "skills_prepare_runtime",
        "schedule_create", "schedule_pause", "schedule_delete", "set_alarm", "set_timer",
        "goal_manage", "plan_manage", "notepad_manage",
    )
    private val idempotentWrite = setOf("set_volume", "set_device_state")
    private val external = setOf("connector_call", "voice_synthesize", "download_media", "cloud_document", "cloud_media")
    private val highRisk = setOf(
        "terminal", "terminal_job", "terminal_daemon", "eta_control", "automation_manage",
        "skills_install_from_github", "skills_inspect_github", "app_state_control", "set_setting",
        "audio_record", "character_memory_generate",
        "delete_file",
        "project_workspace_publish",
    )

    fun classify(toolId: String): EffectClass = when (toolId) {
        in readOnly -> EffectClass.READ
        in gui -> EffectClass.GUI_ACTION
        in reversibleWrite -> EffectClass.REVERSIBLE_WRITE
        in idempotentWrite -> EffectClass.IDEMPOTENT_WRITE
        in external -> EffectClass.EXTERNAL_SIDE_EFFECT
        in highRisk -> EffectClass.HIGH_RISK
        // An external tool's name is not a trustworthy declaration of its side effects.
        else -> EffectClass.HIGH_RISK
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
