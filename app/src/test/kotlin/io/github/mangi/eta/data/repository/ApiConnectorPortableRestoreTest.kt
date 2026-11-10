package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.capability.ApiConnectorAction
import io.github.mangi.eta.agent.capability.ApiConnectorAuthType
import io.github.mangi.eta.agent.capability.ApiConnectorDefinition
import io.github.mangi.eta.agent.capability.ApiConnectorStore
import io.github.mangi.eta.agent.capability.ApiHttpMethod
import io.github.mangi.eta.agent.capability.apiConnectorSecretKey
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ApiConnectorPortableRestoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var key: SecretKey? = null
    private val storage get() = McpSecretStore(context) { create ->
        key ?: if (create) KeyGenerator.getInstance("AES").run {
            init(128)
            generateKey()
        }.also { key = it } else error("test key unavailable")
    }

    @Before
    fun setUp() {
        File(context.filesDir, "capability-center/api-connectors.json").delete()
        File(context.noBackupFilesDir, "eta-portable-secrets.json").delete()
        context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun captureAndRestoreKeepRestApiCredentialsPortable() {
        val connector = ApiConnectorDefinition(
            id = "api-portable-restore",
            name = "Portable API",
            baseUrl = "https://api.example.com/v1",
            authType = ApiConnectorAuthType.BEARER,
            actions = listOf(
                ApiConnectorAction(
                    name = "read",
                    description = "Read",
                    method = ApiHttpMethod.GET,
                    path = "/read",
                    inputSchemaJson = "{\"type\":\"object\"}",
                ),
            ),
        )
        ApiConnectorStore(context).upsert(connector)
        storage.setBearerToken(
            apiConnectorSecretKey(connector.id),
            "portable-api-token",
        )
        val snapshot = ApiConnectorCredentialTransfer.capture(context, storage)
        assertEquals(0, snapshot.unreadable)
        assertEquals("portable-api-token", snapshot.credentials.getValue(connector.id))

        context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE)
            .edit().clear().commit()
        key = null
        ApiConnectorCredentialTransfer.restore(context, snapshot.credentials, storage)

        assertEquals(
            "portable-api-token",
            storage.bearerToken(apiConnectorSecretKey(connector.id)),
        )
    }
}
