package io.github.mangi.eta.data.model

internal data class ProviderAccountSummary(
    val email: String?,
    val displayName: String?,
    val needsReauthentication: Boolean,
    val planEnabled: Boolean,
)

internal data class ProviderCredentialBinding(
    val providerId: String,
    val authMode: String,
    val generation: Long,
)

internal class ProviderLoginPrompt(
    val authorizationUrl: String,
    val userCode: String? = null,
) {
    override fun toString(): String = "ProviderLoginPrompt(redacted)"
}
