package io.github.mangi.eta.fork.a2a

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Enforces the user's manual endpoint allowlist and protects the client from SSRF/DNS rebinding. */
internal data class A2APeerPolicy(
    val allowedHosts: Set<String>,
    val allowHttpForDebug: Boolean = false,
    val allowPrivateNetwork: Boolean = false,
) {
    init {
        require(allowedHosts.all(::isValidAllowlistEntry)) {
            "A2A allowlists may only contain exact host names or *.suffix entries"
        }
    }

    fun validateUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw A2AProtocolException("Invalid A2A URL")
        if (!allowHttpForDebug && url.scheme != "https") {
            throw A2AProtocolException("A2A endpoints must use HTTPS")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw A2AProtocolException("Credentials must not be embedded in A2A URLs")
        }
        requireAllowedHost(url.host)
        return url
    }

    fun requireAllowedHost(host: String) {
        val normalized = host.trimEnd('.').lowercase()
        val allowed = allowedHosts.any { entry ->
            when {
                entry.startsWith("*.") -> normalized != entry.removePrefix("*.") &&
                    normalized.endsWith(entry.removePrefix("*"))
                else -> normalized == entry
            }
        }
        if (!allowed) throw A2AProtocolException("A2A host is not on the manual allowlist")
    }

    fun secureDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            requireAllowedHost(hostname)
            val addresses = delegate.lookup(hostname)
            if (!allowPrivateNetwork && addresses.any(::isForbiddenAddress)) {
                throw A2AProtocolException("A2A host resolved to a private, loopback, link-local or metadata address")
            }
            return addresses
        }
    }

    private fun isValidAllowlistEntry(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank() || normalized.contains('/') || normalized.contains(':')) return false
        if (normalized.startsWith("*.")) {
            val suffix = normalized.removePrefix("*.")
            return suffix.isNotEmpty() && suffix.split('.').all(::isValidLabel)
        }
        return normalized.split('.').all(::isValidLabel)
    }

    private fun isValidLabel(label: String): Boolean =
        label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
}

internal fun isForbiddenAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return true
    val bytes = address.address
    if (bytes.size == 4) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        return first == 0 || first == 10 || first == 127 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 0) ||
            (first == 192 && second == 168) ||
            (first == 198 && second in 18..19) ||
            first >= 224
    }
    if (bytes.size == 16) {
        val first = bytes[0].toInt() and 0xff
        // ::/128, ::1/128, fc00::/7, fe80::/10 and ff00::/8.
        return (first == 0xfc || (first and 0xfe) == 0xfc) ||
            (first == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80) ||
            (first == 0xff) || address.isLoopbackAddress
    }
    return true
}

/** A manual peer may use private HTTP only when the user supplies that exact local host. */
internal fun a2aPeerPolicyForManualEndpoint(rawUrl: String): A2APeerPolicy {
    val url = rawUrl.toHttpUrlOrNull() ?: throw A2AProtocolException("Invalid A2A URL")
    val localHttp = url.scheme == "http" && isLocalEndpointHost(url.host)
    if (url.scheme != "https" && !localHttp) {
        throw A2AProtocolException("Only HTTPS or an exact private-network HTTP endpoint may be used")
    }
    return A2APeerPolicy(
        allowedHosts = setOf(url.host),
        allowHttpForDebug = localHttp,
        allowPrivateNetwork = localHttp,
    )
}

internal fun isLocalEndpointHost(host: String): Boolean {
    val normalized = host.trim().trimEnd('.').lowercase()
    if (normalized == "localhost" || normalized.endsWith(".local")) return true
    return runCatching { InetAddress.getAllByName(normalized).toList() }
        .getOrNull()
        ?.takeIf(List<InetAddress>::isNotEmpty)
        ?.all(::isPrivateLanAddress) == true
}

private fun isPrivateLanAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isSiteLocalAddress) return true
    val bytes = address.address
    return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
}
