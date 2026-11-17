package io.github.mangi.eta.fork.a2a

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable

internal object A2AToolTier {
    const val READ_ONLY = "read_only"
    const val RESTRICTED = "restricted"
    const val HIGH_RISK = "high_risk"
    const val CUSTOM = "custom"

    val configurable = setOf(READ_ONLY, RESTRICTED, CUSTOM)
}

@Serializable
internal data class A2AToolAccess(
    val peerId: String,
    val tier: String,
    val allowedTools: Set<String>,
) {
    companion object {
        val READ_ONLY_TOOLS = setOf(
            "device_status",
            "network_info",
            "get_current_context",
            "read_file",
            "list_directory",
            "stat_file",
            "recent_notifications",
            "recent_app_activity",
        )
        val RESTRICTED_TOOLS = READ_ONLY_TOOLS + setOf(
            "write_file",
            "edit_file",
            "terminal",
            "launch_app",
            "open_uri",
            "set_clipboard",
        )
        val HIGH_RISK_TOOLS = setOf(
            "automation_manage",
            "subagent",
            "terminal_daemon",
            "skills_install_from_github",
        )

        /** Named local tools only: no wildcards, remote supplied schemas, or privilege controls. */
        val CONFIGURABLE_TOOLS = (RESTRICTED_TOOLS + setOf(
            "glob_files", "grep_files", "read_image", "make_directory", "copy_file", "move_file",
            "inspect_app", "get_clipboard", "observe_screen", "wait", "wait_for_text", "wait_for_package",
            "web_search", "fetch_url", "browser_use", "tap", "tap_element", "long_press", "swipe", "scroll",
            "type_text", "press_key", "get_device_environment", "get_current_location",
            "search_notification_history", "app_usage_summary", "memory_get", "memory_search", "memory_write",
            "conversation_history", "skills_list", "skills_read", "skills_read_resource",
            "schedule_list", "schedule_create", "schedule_pause", "schedule_delete", "set_alarm", "set_timer",
            "voice_synthesize", "download_media", "cloud_document", "cloud_media", "connector_call",
            "goal_manage", "plan_manage", "notepad_manage", "project_workspace_status",
        )).filterTo(sortedSetOf()) {
            it !in HIGH_RISK_TOOLS &&
                io.github.mangi.eta.agent.capability.NexusToolEffectClassifier.classify(it) !=
                io.github.zhh_lgtm.nexus.kernel.api.EffectClass.HIGH_RISK
        }

        fun custom(peerId: String, tools: Set<String>): A2AToolAccess {
            require(tools.all { it in CONFIGURABLE_TOOLS }) { "Unsupported A2A tool grant" }
            return A2AToolAccess(peerId, A2AToolTier.CUSTOM, tools.toSortedSet())
        }

        fun readOnly(peerId: String): A2AToolAccess = A2AToolAccess(
            peerId = peerId,
            tier = A2AToolTier.READ_ONLY,
            allowedTools = READ_ONLY_TOOLS,
        )

        fun restricted(peerId: String): A2AToolAccess = A2AToolAccess(
            peerId = peerId,
            tier = A2AToolTier.RESTRICTED,
            allowedTools = (RESTRICTED_TOOLS - HIGH_RISK_TOOLS),
        )
    }
}

internal class A2AAccessPolicyStore(context: Context) {
    @Suppress("DEPRECATION")
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        // The A2A service owns a separate process. Multi-process mode makes the service
        // observe tier changes written by the settings UI instead of retaining stale values.
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
    )

    fun observePeers(): Flow<Map<String, String>> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { peerTiers() }.flowOn(Dispatchers.IO)

    fun peerTiers(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith(KEY_PREFIX) }
        .mapNotNull { (key, value) ->
            val peerId = key.removePrefix(KEY_PREFIX)
            val tier = value as? String
            peerId.takeIf(String::isNotBlank)?.let { it to (tier?.takeIf(A2AToolTier.configurable::contains) ?: A2AToolTier.READ_ONLY) }
        }.toMap()

    fun tier(peerId: String): String = peerTiers()[peerId] ?: A2AToolTier.READ_ONLY

    fun customTools(peerId: String): Set<String> {
        val raw = preferences.getStringSet(CUSTOM_PREFIX + safePeerId(peerId), null)
            ?: return accessForLegacyTier(peerId).allowedTools.intersect(A2AToolAccess.CONFIGURABLE_TOOLS)
        return raw.intersect(A2AToolAccess.CONFIGURABLE_TOOLS)
    }

    fun setCustomTools(peerId: String, tools: Set<String>) {
        A2AToolAccess.custom(peerId, tools)
        check(preferences.edit().putStringSet(CUSTOM_PREFIX + safePeerId(peerId), tools.toSet())
            .putString(KEY_PREFIX + safePeerId(peerId), A2AToolTier.CUSTOM).commit()) {
            "A2A authorization policy was not saved"
        }
    }

    fun setTier(peerId: String, tier: String) {
        require(tier in A2AToolTier.configurable) { "Unsupported A2A tool tier" }
        check(preferences.edit().putString(KEY_PREFIX + safePeerId(peerId), tier).commit()) {
            "A2A authorization policy was not saved"
        }
    }

    fun observePeer(peerId: String) {
        val key = KEY_PREFIX + safePeerId(peerId)
        if (!preferences.contains(key)) {
            check(preferences.edit().putString(key, A2AToolTier.READ_ONLY).commit()) {
                "A2A authorization policy was not initialized"
            }
        }
    }

    fun accessFor(peerId: String): A2AToolAccess = if (tier(peerId) == A2AToolTier.CUSTOM) {
        A2AToolAccess.custom(peerId, customTools(peerId))
    } else accessForLegacyTier(peerId)

    private fun accessForLegacyTier(peerId: String): A2AToolAccess = when (tier(peerId)) {
        A2AToolTier.RESTRICTED -> A2AToolAccess.restricted(peerId)
        else -> A2AToolAccess.readOnly(peerId)
    }

    companion object {
        private const val PREFERENCES_NAME = "eta_a2a_access_policy"
        private const val KEY_PREFIX = "peer::"
        private const val CUSTOM_PREFIX = "tools::"

        fun tokenPeerId(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(token.toByteArray(Charsets.UTF_8))
                .take(6)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "token-$digest"
        }

        private fun safePeerId(peerId: String): String =
            peerId.trim().take(120).ifBlank { "unknown" }
    }
}
