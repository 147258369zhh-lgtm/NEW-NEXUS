package io.github.mangi.eta.fork.a2a

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal object A2AToolTier {
    const val READ_ONLY = "read_only"
    const val RESTRICTED = "restricted"
    const val HIGH_RISK = "high_risk"

    val configurable = setOf(READ_ONLY, RESTRICTED)
}

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
            trySend(peerTiers())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(peerTiers())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun peerTiers(): Map<String, String> = preferences.all
        .filterKeys { it.startsWith(KEY_PREFIX) }
        .mapNotNull { (key, value) ->
            val peerId = key.removePrefix(KEY_PREFIX)
            val tier = value as? String
            peerId.takeIf(String::isNotBlank)?.let { it to (tier?.takeIf(A2AToolTier.configurable::contains) ?: A2AToolTier.READ_ONLY) }
        }.toMap()

    fun tier(peerId: String): String = peerTiers()[peerId] ?: A2AToolTier.READ_ONLY

    fun setTier(peerId: String, tier: String) {
        require(tier in A2AToolTier.configurable) { "Unsupported A2A tool tier" }
        preferences.edit().putString(KEY_PREFIX + safePeerId(peerId), tier).apply()
    }

    fun observePeer(peerId: String) {
        val key = KEY_PREFIX + safePeerId(peerId)
        if (!preferences.contains(key)) {
            preferences.edit().putString(key, A2AToolTier.READ_ONLY).apply()
        }
    }

    fun accessFor(peerId: String): A2AToolAccess = when (tier(peerId)) {
        A2AToolTier.RESTRICTED -> A2AToolAccess.restricted(peerId)
        else -> A2AToolAccess.readOnly(peerId)
    }

    companion object {
        private const val PREFERENCES_NAME = "eta_a2a_access_policy"
        private const val KEY_PREFIX = "peer::"

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
