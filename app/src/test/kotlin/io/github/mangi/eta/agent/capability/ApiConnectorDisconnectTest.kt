package io.github.mangi.eta.agent.capability

import android.app.Application
import io.github.mangi.eta.data.repository.McpSecretStore
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class ApiConnectorDisconnectTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: ApiConnectorStore
    private lateinit var secrets: McpSecretStore
    private lateinit var service: ApiConnectorService

    @Before fun setup() {
        context.getSharedPreferences("eta_mcp_secrets", 0).edit().clear().commit()
        java.io.File(context.filesDir, "capability-center/api-connectors.json").delete()
        store = ApiConnectorStore(context)
        secrets = McpSecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
        service = ApiConnectorService(context, connectorStore = store, secretStore = secrets)
    }

    @Test fun tokenDisconnectKeepsMetadataAndRevokesOnlyTheCredential() {
        val connector = bearerConnector()
        store.upsert(connector)
        secrets.setBearerToken(apiConnectorSecretKey(connector.id), "private-token")
        assertTrue(service.credentialAvailable(connector))

        val remaining = service.disconnect(connector.id).single()

        assertFalse(service.credentialAvailable(remaining))
        assertNull(secrets.bearerToken(apiConnectorSecretKey(connector.id)))
        assertTrue(store.load().single().actions.single().enabled)
    }

    @Test fun oauthDisconnectClearsTokensAndClientSecretButKeepsConnectorConfiguration() {
        val connector = bearerConnector().copy(
            authType = ApiConnectorAuthType.OAUTH2,
            oauth = ApiOAuth2Configuration(
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "client-id",
                usesClientSecret = true,
            ),
        )
        store.upsert(connector)
        secrets.setBearerToken(apiConnectorSecretKey(connector.id), JSONObject()
            .put("accessToken", "access-token")
            .put("refreshToken", "refresh-token")
            .put("clientSecret", "client-secret")
            .put("tokenType", "Bearer")
            .toString())
        assertTrue(service.credentialAvailable(connector))

        service.disconnect(connector.id)

        assertFalse(service.credentialAvailable(connector))
        assertNull(secrets.bearerToken(apiConnectorSecretKey(connector.id)))
        assertTrue(store.load().single().enabled)
    }

    private fun bearerConnector() = ApiConnectorDefinition(
        id = "api-disconnect-test",
        name = "Disconnect test",
        baseUrl = "https://api.example.com",
        authType = ApiConnectorAuthType.BEARER,
        actions = listOf(ApiConnectorAction(
            name = "read_items",
            description = "Read items",
            method = ApiHttpMethod.GET,
            path = "/items",
            inputSchemaJson = """{"type":"object"}""",
            enabled = true,
        )),
    )
}
