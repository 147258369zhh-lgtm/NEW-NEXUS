package io.github.mangi.eta.data.model

/** Shared endpoint classification for local inference servers that may not require an API key. */
internal object ProviderEndpoint {
    fun isLocalOrPrivate(value: String): Boolean {
        val host = runCatching { java.net.URI(value.trim()).host }
            .getOrNull()
            ?.removeSurrounding("[", "]")
            ?.lowercase()
            ?: return false
        if (host == "localhost" || host == "::1" || host == "0.0.0.0" || host.startsWith("127.")) {
            return true
        }
        if (host.endsWith(".local") || host.startsWith("fe80:")) return true
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true
        return Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(host)
    }
}
