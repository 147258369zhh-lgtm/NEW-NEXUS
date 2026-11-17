package io.github.mangi.eta.fork.a2a

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64

private const val LAN_BIND_ADDRESS = "0.0.0.0"

internal object A2ATransportMode {
    const val DIRECT = "direct"
    const val TAILSCALE = "tailscale"
    val values = setOf(DIRECT, TAILSCALE)
}

internal data class A2AInboundSnapshot(
    val enabled: Boolean,
    val port: Int,
    val lanAddress: String?,
    val advertisedEndpointUrl: String?,
    val token: String,
    val lastError: String?,
    val transportMode: String = A2ATransportMode.DIRECT,
    val lastProbeHealthy: Boolean? = null,
    val lastProbeAt: Long? = null,
    val lastListenAddress: String? = null,
    val restartCount: Int = 0,
    val tailscaleAddress: String? = null,
) {
    val agentCardUrl: String?
        get() = endpointUrl?.let(A2AInboundConfigStore::agentCardUrlForEndpoint)

    val endpointUrl: String?
        get() = advertisedEndpointUrl ?: when (transportMode) {
            A2ATransportMode.TAILSCALE ->
                (tailscaleAddress ?: lanAddress)?.let { "http://$it:$port/a2a" }
            else -> lanAddress?.let { "http://$it:$port/a2a" }
        }

    val bindAddress: String
        get() = LAN_BIND_ADDRESS
}

/** Local settings and encrypted token for Eta's inbound A2A endpoint. */
internal class A2AInboundConfigStore(
    context: Context,
    private val secrets: A2ASecretStore = A2ASecretStore(context.applicationContext),
) {
    private val appContext = context.applicationContext
    @Suppress("DEPRECATION")
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        // The settings screen and sticky endpoint run in different processes. Every caller
        // reads a fresh on-disk snapshot before using enable/token/transport state.
        Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS,
    )

    fun observe(): Flow<A2AInboundSnapshot> = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().map { snapshot() }.flowOn(Dispatchers.IO)

    fun snapshot(): A2AInboundSnapshot {
        val token = synchronized(TOKEN_LOCK) { loadToken() ?: rotateToken() }
        return A2AInboundSnapshot(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            port = preferences.getInt(KEY_PORT, DEFAULT_PORT)
                .takeIf { it in MIN_PORT..MAX_PORT } ?: DEFAULT_PORT,
            lanAddress = currentLanAddress(),
            advertisedEndpointUrl = preferences.getString(KEY_ADVERTISED_ENDPOINT_URL, null)
                ?.takeIf(::isValidAdvertisedEndpoint),
            transportMode = preferences.getString(KEY_TRANSPORT_MODE, A2ATransportMode.DIRECT)
                ?.takeIf { it in A2ATransportMode.values } ?: A2ATransportMode.DIRECT,
            token = token,
            tailscaleAddress = currentTailscaleAddress(),
            lastError = preferences.getString(KEY_LAST_ERROR, null),
            lastProbeHealthy = preferences.all[KEY_LAST_PROBE_HEALTHY] as? Boolean,
            lastProbeAt = (preferences.all[KEY_LAST_PROBE_AT] as? Long)?.takeIf { it > 0L },
            lastListenAddress = preferences.getString(KEY_LAST_LISTEN_ADDRESS, null),
            restartCount = preferences.getInt(KEY_RESTART_COUNT, 0),
        )
    }

    fun setEnabled(enabled: Boolean) {
        ensureToken()
        commit {
            putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_PORT, DEFAULT_PORT)
                .remove(KEY_LAST_ERROR)
        }
    }

    fun setLastError(message: String?) {
        commit {
            if (message.isNullOrBlank()) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, message.take(500))
        }
    }

    fun setProbeState(healthy: Boolean, address: String?) {
        commit {
            putBoolean(KEY_LAST_PROBE_HEALTHY, healthy)
                .putLong(KEY_LAST_PROBE_AT, System.currentTimeMillis())
                .apply {
                    if (address.isNullOrBlank()) remove(KEY_LAST_LISTEN_ADDRESS)
                    else putString(KEY_LAST_LISTEN_ADDRESS, address)
                }
        }
    }

    fun recordRestart() {
        commit {
            putInt(KEY_RESTART_COUNT, preferences.getInt(KEY_RESTART_COUNT, 0) + 1)
        }
    }

    fun rotateToken(): String = synchronized(TOKEN_LOCK) {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        secrets.save(TOKEN_ALIAS, token)
        commit { remove(KEY_LAST_ERROR) }
        token
    }

    fun loadToken(): String? = runCatching { secrets.load(TOKEN_ALIAS) }
        .getOrNull()
        ?.takeIf(String::isNotBlank)

    /** Set by a verified transport adapter, never by an unauthenticated network request. */
    fun setAdvertisedEndpoint(url: String?) {
        require(url == null || isValidAdvertisedEndpoint(url)) { "Invalid advertised A2A endpoint" }
        commit {
            if (url == null) remove(KEY_ADVERTISED_ENDPOINT_URL) else putString(KEY_ADVERTISED_ENDPOINT_URL, url)
        }
    }

    fun setTransportMode(mode: String) {
        require(mode in A2ATransportMode.values) { "Unsupported A2A transport mode" }
        commit { putString(KEY_TRANSPORT_MODE, mode) }
        if (mode == A2ATransportMode.TAILSCALE) {
            val advertised = preferences.getString(KEY_ADVERTISED_ENDPOINT_URL, null)
            if (advertised != null && isNonTailscalePrivateEndpoint(advertised)) setAdvertisedEndpoint(null)
        }
    }

    /** Commit before a cross-process service start/restart observes the setting. */
    private fun commit(edit: android.content.SharedPreferences.Editor.() -> Unit) {
        check(preferences.edit().apply(edit).commit()) { "A2A inbound settings were not saved" }
    }

    private fun ensureToken() {
        synchronized(TOKEN_LOCK) {
            if (loadToken() == null) rotateToken()
        }
    }

    companion object {
        private val TOKEN_LOCK = Any()
        const val DEFAULT_PORT = 43121
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        private const val PREFERENCES_NAME = "eta_a2a_inbound"
        private const val TOKEN_ALIAS = "a2a-inbound-v1"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PORT = "port"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_ADVERTISED_ENDPOINT_URL = "advertised_endpoint_url"
        private const val KEY_TRANSPORT_MODE = "transport_mode"
        private const val KEY_LAST_PROBE_HEALTHY = "last_probe_healthy"
        private const val KEY_LAST_PROBE_AT = "last_probe_at"
        private const val KEY_LAST_LISTEN_ADDRESS = "last_listen_address"
        private const val KEY_RESTART_COUNT = "restart_count"

        internal fun isValidAdvertisedEndpoint(value: String): Boolean = runCatching {
            val uri = java.net.URI(value)
            uri.scheme in setOf("http", "https") &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawQuery == null &&
                uri.fragment == null &&
                uri.path == "/a2a"
        }.getOrDefault(false)

        internal fun agentCardUrlForEndpoint(endpointUrl: String): String? = runCatching {
            val uri = java.net.URI(endpointUrl)
            require(isValidAdvertisedEndpoint(endpointUrl))
            java.net.URI(
                uri.scheme,
                null,
                uri.host,
                uri.port,
                "/.well-known/agent-card.json",
                null,
                null,
            ).toString()
        }.getOrNull()

        internal fun currentLanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address -> !address.isLoopbackAddress && address.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        internal fun currentTailscaleAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    val bytes = address.address
                    bytes.size == 4 && bytes[0].toInt() and 0xff == 100 &&
                        (bytes[1].toInt() and 0xff) in 64..127
                }
                ?.hostAddress
        }.getOrNull()

        private fun isNonTailscalePrivateEndpoint(value: String): Boolean = runCatching {
            val host = java.net.URI(value).host ?: return@runCatching false
            val address = java.net.InetAddress.getByName(host)
            address.isSiteLocalAddress && !host.startsWith("100.")
        }.getOrDefault(false)
    }
}
