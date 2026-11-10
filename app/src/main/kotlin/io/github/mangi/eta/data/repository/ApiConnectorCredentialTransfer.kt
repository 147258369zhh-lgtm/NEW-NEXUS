package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.capability.ApiConnectorAuthType
import io.github.mangi.eta.agent.capability.ApiConnectorStore
import io.github.mangi.eta.agent.capability.apiConnectorSecretKey

internal data class ApiConnectorCredentialSnapshot(
    val credentials: Map<String, String> = emptyMap(),
    val unreadable: Int = 0,
)

/** Keeps REST credentials portable across devices without copying Keystore ciphertext as usable data. */
internal object ApiConnectorCredentialTransfer {
    fun capture(
        context: Context,
        storage: McpSecretStore = McpSecretStore(context.applicationContext),
    ): ApiConnectorCredentialSnapshot {
        val credentials = linkedMapOf<String, String>()
        var unreadable = 0
        ApiConnectorStore(context.applicationContext).load()
            .filter { it.authType != ApiConnectorAuthType.NONE }
            .forEach { connector ->
                try {
                    storage.bearerToken(apiConnectorSecretKey(connector.id))?.let { token ->
                        credentials[connector.id] = token
                    }
                } catch (_: McpCredentialsUnavailable) {
                    unreadable++
                }
            }
        return ApiConnectorCredentialSnapshot(credentials, unreadable)
    }

    fun restore(
        context: Context,
        credentials: Map<String, String>,
        storage: McpSecretStore = McpSecretStore(context.applicationContext),
    ) {
        if (credentials.isEmpty()) return
        val existing = ApiConnectorStore(context.applicationContext).load()
            .filter { it.authType != ApiConnectorAuthType.NONE }
            .mapTo(hashSetOf()) { it.id }
        credentials.forEach { (connectorId, credential) ->
            if (credential.isNotBlank() && connectorId in existing) {
                storage.setBearerToken(apiConnectorSecretKey(connectorId), credential)
            }
        }
    }
}
