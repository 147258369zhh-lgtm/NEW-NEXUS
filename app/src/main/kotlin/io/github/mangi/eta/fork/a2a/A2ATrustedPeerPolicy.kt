package io.github.mangi.eta.fork.a2a

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Identity supplied by the tsnet adapter after it has authenticated a tunnel peer.
 * Reachability inside a Tailnet is not sufficient: the node id and exact endpoint host must both
 * match a user-approved pairing record.
 */
internal data class A2APairedTunnelPeer(
    val nodeId: String,
    val endpointHost: String,
    val bearerSecretAlias: String?,
    val enabled: Boolean = true,
) {
    init {
        require(nodeId.isNotBlank()) { "A2A peer node id must not be blank" }
        require(endpointHost.isNotBlank()) { "A2A peer endpoint host must not be blank" }
        bearerSecretAlias?.let { alias -> A2ASecretStore.requireValidAlias(alias) }
    }
}

/**
 * Builds the normal SSRF policy from a paired node. A 100.64.0.0/10 address is never trusted merely
 * because it is in the CGNAT range; it must exactly match the host persisted for this node.
 */
internal object A2ATrustedPeerPolicy {
    fun forEndpoint(
        rawUrl: String,
        peer: A2APairedTunnelPeer,
        authenticatedNodeId: String,
    ): A2APeerPolicy {
        if (!peer.enabled) throw A2AProtocolException("A2A tunnel peer is disabled")
        if (authenticatedNodeId != peer.nodeId) {
            throw A2AProtocolException("A2A tunnel peer identity does not match the pairing record")
        }
        val url = rawUrl.toHttpUrlOrNull() ?: throw A2AProtocolException("Invalid A2A URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw A2AProtocolException("Credentials must not be embedded in A2A URLs")
        }
        if (!url.host.equals(peer.endpointHost.trimEnd('.'), ignoreCase = true)) {
            throw A2AProtocolException("A2A tunnel endpoint does not match the paired peer host")
        }
        val localTunnelHttp = url.scheme == "http" && peer.endpointHost.isTailnetLoopbackForward()
        if (url.scheme != "https" && !localTunnelHttp) {
            throw A2AProtocolException("A2A tunnel endpoints require HTTPS unless explicitly paired on tailnet HTTP")
        }
        return A2APeerPolicy(
            allowedHosts = setOf(url.host),
            allowHttpForDebug = localTunnelHttp,
            allowPrivateNetwork = true,
        )
    }

    private fun String.isTailnetLoopbackForward(): Boolean {
        val normalized = trim().trimEnd('.').lowercase()
        if (normalized == "localhost") return true
        val bytes = runCatching { java.net.InetAddress.getByName(normalized).address }.getOrNull()
            ?: return false
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return first == 127 || (first == 100 && second in 64..127)
        }
        return bytes.size == 16 && bytes.all { it == 0.toByte() || it == 1.toByte() }
    }
}
