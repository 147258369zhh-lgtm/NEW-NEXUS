package io.github.mangi.eta.fork.a2a

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.HostTransport

internal interface A2ATransport : HostTransport {
    override val id: String get() = "a2a"
}

private class SocketA2ATransport(
    private val engine: A2AInboundEngine,
    private val addressProvider: () -> String?,
) : A2ATransport {
    override fun start() = engine.start()
    override fun stop() = engine.stop()
    override fun currentAddress(): String? = addressProvider()?.takeIf(::isValidEndpoint)
}

internal class LanDirectA2ATransport(
    context: Context,
    snapshot: A2AInboundSnapshot,
    tokenProvider: () -> String,
    externalEndpointProvider: () -> String?,
    accessStore: A2AAccessPolicyStore,
) : A2ATransport {
    private val delegate = SocketA2ATransport(
        engine = A2AInboundEngine(
            context = context,
            port = snapshot.port,
            bearerTokenProvider = tokenProvider,
            bindAddress = A2ABindAddress,
            advertisedEndpointProvider = externalEndpointProvider,
            accessProvider = accessStore::accessFor,
            peerObserver = accessStore::observePeer,
        ),
        addressProvider = {
            externalEndpointProvider()
                ?: A2AInboundConfigStore.currentLanAddress()?.let { "http://$it:${snapshot.port}/a2a" }
        },
    )
    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun currentAddress(): String? = delegate.currentAddress()
}

internal class TailscaleA2ATransport(
    context: Context,
    snapshot: A2AInboundSnapshot,
    tokenProvider: () -> String,
    externalEndpointProvider: () -> String?,
    accessStore: A2AAccessPolicyStore,
) : A2ATransport {
    private val delegate = SocketA2ATransport(
        engine = A2AInboundEngine(
            context = context,
            port = snapshot.port,
            bearerTokenProvider = tokenProvider,
            bindAddress = A2ABindAddress,
            advertisedEndpointProvider = externalEndpointProvider,
            accessProvider = accessStore::accessFor,
            peerObserver = accessStore::observePeer,
        ),
        addressProvider = {
            externalEndpointProvider()
                ?: A2AInboundConfigStore.currentTailscaleAddress()?.let { "http://$it:${snapshot.port}/a2a" }
        },
    )
    override fun start() = delegate.start()
    override fun stop() = delegate.stop()
    override fun currentAddress(): String? = delegate.currentAddress()
}

internal object A2ATransportFactory {
    fun create(
        context: Context,
        snapshot: A2AInboundSnapshot,
        tokenProvider: () -> String,
        externalEndpointProvider: () -> String?,
        accessStore: A2AAccessPolicyStore,
    ): A2ATransport = when (snapshot.transportMode) {
        A2ATransportMode.TAILSCALE -> TailscaleA2ATransport(context, snapshot, tokenProvider, externalEndpointProvider, accessStore)
        else -> LanDirectA2ATransport(context, snapshot, tokenProvider, externalEndpointProvider, accessStore)
    }
}

private const val A2ABindAddress = "0.0.0.0"

private fun isValidEndpoint(value: String): Boolean = runCatching {
    val uri = java.net.URI(value)
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.path == "/a2a"
}.getOrDefault(false)
