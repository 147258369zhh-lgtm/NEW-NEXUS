package io.github.mangi.eta.agent.capability

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class ApiConnectorStoreTest {
    private lateinit var store: ApiConnectorStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "capability-center/api-connectors.json").delete()
        store = ApiConnectorStore(context)
    }

    @Test
    fun persistsConnectorMetadataWithoutCredentials() {
        val saved = store.upsert(connector())
        assertEquals(1, saved.size)
        val loaded = store.load().single()
        assertEquals("Projects", loaded.name)
        assertEquals("https://api.example.com/v1", loaded.baseUrl)
        assertEquals(1, loaded.actions.size)
        assertFalse(loaded.actions.single().enabled)
    }

    @Test
    fun rejectsInsecureUrlsAndUnsafeActions() {
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(connector().copy(baseUrl = "http://api.example.com/v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(connector().copy(baseUrl = "https://user:pass@api.example.com/v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(
                connector().copy(
                    actions = listOf(
                        connector().actions.single().copy(path = "/projects/{id}", pathParameters = emptyList()),
                    ),
                ),
            )
        }
    }

    @Test
    fun togglesAndDeletesConnector() {
        val draft = store.upsert(connector()).single()
        assertFalse(store.upsert(draft.copy(enabled = false)).single().enabled)
        assertTrue(store.delete(draft.id).isEmpty())
    }

    @Test
    fun persistsOAuthConfigurationWithoutSecrets() {
        val oauth = connector().copy(
            authType = ApiConnectorAuthType.OAUTH2,
            oauth = ApiOAuth2Configuration(
                authorizationEndpoint = "https://auth.example.com/authorize",
                tokenEndpoint = "https://auth.example.com/token",
                clientId = "public-client",
                scopes = listOf("read", "write", "read"),
                usesClientSecret = true,
            ),
        )
        store.upsert(oauth)
        val loaded = store.load().single()
        assertEquals(ApiConnectorAuthType.OAUTH2, loaded.authType)
        assertEquals(listOf("read", "write"), loaded.oauth?.scopes)
        assertTrue(loaded.oauth?.usesClientSecret == true)
        val raw = File(
            RuntimeEnvironment.getApplication().filesDir,
            "capability-center/api-connectors.json",
        ).readText()
        assertFalse(raw.contains("client-secret-value"))
    }

    @Test
    fun rejectsUnsafeOAuthEndpointsAndMismatchedAuthentication() {
        val oauth = ApiOAuth2Configuration(
            authorizationEndpoint = "https://auth.example.com/authorize",
            tokenEndpoint = "https://auth.example.com/token",
            clientId = "public-client",
        )
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(
                connector().copy(
                    authType = ApiConnectorAuthType.OAUTH2,
                    oauth = oauth.copy(authorizationEndpoint = "http://auth.example.com/authorize"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(
                connector().copy(
                    authType = ApiConnectorAuthType.OAUTH2,
                    oauth = oauth.copy(tokenEndpoint = "https://auth.example.com/token?account=one"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.validate(connector().copy(authType = ApiConnectorAuthType.OAUTH2, oauth = null))
        }
    }

    private fun connector() = ApiConnectorDefinition(
        id = "api-test",
        name = "Projects",
        baseUrl = "https://api.example.com/v1",
        authType = ApiConnectorAuthType.BEARER,
        actions = listOf(
            ApiConnectorAction(
                name = "get_project",
                description = "Read a project",
                method = ApiHttpMethod.GET,
                path = "/projects/{projectId}",
                inputSchemaJson = """{"type":"object","properties":{"projectId":{"type":"string"}},"required":["projectId"]}""",
                pathParameters = listOf("projectId"),
            ),
        ),
    )
}
