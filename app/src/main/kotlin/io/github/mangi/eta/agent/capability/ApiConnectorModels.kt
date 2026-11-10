package io.github.mangi.eta.agent.capability

internal object ApiConnectorAuthType {
    const val NONE = "none"
    const val BEARER = "bearer"
    const val HEADER = "header"
    const val OAUTH2 = "oauth2"

    val values = setOf(NONE, BEARER, HEADER, OAUTH2)
}

data class ApiOAuth2Configuration(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val clientId: String,
    val scopes: List<String> = emptyList(),
    val usesClientSecret: Boolean = false,
) {
    companion object {
        const val REDIRECT_URI = "nexus://capability-oauth/callback"
    }
}

internal fun apiConnectorSecretKey(connectorId: String): String = "api_$connectorId"

internal object ApiHttpMethod {
    const val GET = "GET"
    const val POST = "POST"
    const val PUT = "PUT"
    const val PATCH = "PATCH"
    const val DELETE = "DELETE"

    val values = setOf(GET, POST, PUT, PATCH, DELETE)
}

internal data class ApiConnectorAction(
    val name: String,
    val description: String,
    val method: String,
    val path: String,
    val inputSchemaJson: String,
    val pathParameters: List<String> = emptyList(),
    val queryParameters: List<String> = emptyList(),
    val headerParameters: List<String> = emptyList(),
    val bodyParameters: List<String> = emptyList(),
    val requestBodyRequired: Boolean = false,
    val enabled: Boolean = false,
    val destructive: Boolean = true,
)

internal data class ApiConnectorDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val baseUrl: String,
    val enabled: Boolean = true,
    val authType: String = ApiConnectorAuthType.NONE,
    val authHeaderName: String = "X-API-Key",
    val oauth: ApiOAuth2Configuration? = null,
    val actions: List<ApiConnectorAction> = emptyList(),
    val sourceUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    val activeActions: List<ApiConnectorAction>
        get() = if (enabled) actions.filter(ApiConnectorAction::enabled) else emptyList()
}

internal data class ApiConnectorImportResult(
    val connector: ApiConnectorDefinition,
    val skippedOperations: Int,
)

internal class ApiConnectorException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
