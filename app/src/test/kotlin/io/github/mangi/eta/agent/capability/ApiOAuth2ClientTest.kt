package io.github.mangi.eta.agent.capability

import android.app.Application
import android.content.Context
import android.net.Uri
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.data.repository.McpSecretStore
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.After
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
@Config(sdk = [36], application = Application::class)
class ApiOAuth2ClientTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var server: HttpServer
    private val tokenRequests = CopyOnWriteArrayList<Map<String, String>>()
    private var beforeReply: (Map<String, String>) -> Unit = { }
    private var key: SecretKey? = null
    private val secrets get() = McpSecretStore(context) { create ->
        key ?: if (create) KeyGenerator.getInstance("AES").run {
            init(128)
            generateKey()
        }.also { key = it } else error("test key unavailable")
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("eta_capability_oauth", Context.MODE_PRIVATE).edit().clear().commit()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { instance ->
            instance.createContext("/token") { exchange ->
                val fields = exchange.requestBody.bufferedReader().use { it.readText() }
                    .split('&')
                    .filter(String::isNotBlank)
                    .associate { pair ->
                        val parts = pair.split('=', limit = 2)
                        URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                            URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
                    }
                tokenRequests += fields
                beforeReply(fields)
                val rejected = fields["code"] == "leak-code"
                val response = (if (rejected) {
                    """{"error":"invalid_grant","error_description":"client-secret-leak"}"""
                } else if (fields["grant_type"] == "refresh_token") {
                    """{"access_token":"fresh-token","token_type":"Bearer","expires_in":3600}"""
                } else {
                    """{"access_token":"initial-token","token_type":"Bearer","expires_in":1,"refresh_token":"refresh-token"}"""
                }).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (rejected) 400 else 200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            instance.start()
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
        key = null
    }

    @Test
    fun exchangesPkceCodeRefreshesTokenAndRejectsWrongState() {
        val connector = connector()
        val client = ApiOAuth2Client(
            context = context,
            secretStore = secrets,
            connectorProvider = { if (it == connector.id) connector else null },
        )
        val authorization = client.beginAuthorization(connector.id)
        val authorizationUri = Uri.parse(authorization.authorizationUrl)
        assertEquals("S256", authorizationUri.getQueryParameter("code_challenge_method"))
        assertEquals(43, authorizationUri.getQueryParameter("code_challenge")?.length)
        val state = requireNotNull(authorizationUri.getQueryParameter("state"))
        assertEquals(ApiOAuth2Configuration.REDIRECT_URI, authorizationUri.getQueryParameter("redirect_uri"))
        assertTrue(client.completeAuthorization(Uri.parse("eta://capability-oauth/callback?state=$state&code=wrong-app")) is ApiOAuthCallbackResult.Failure)

        val rejected = client.completeAuthorization(
            Uri.parse("${ApiOAuth2Configuration.REDIRECT_URI}?state=wrong&code=one"),
        )
        assertTrue(rejected is ApiOAuthCallbackResult.Failure)
        assertTrue(tokenRequests.isEmpty())

        val completed = client.completeAuthorization(
            Uri.parse("${ApiOAuth2Configuration.REDIRECT_URI}?state=$state&code=authorization-code"),
        )
        assertTrue(completed is ApiOAuthCallbackResult.Success)
        assertEquals("authorization_code", tokenRequests.single()["grant_type"])
        assertEquals("fresh-token", client.accessToken(connector))
        assertEquals("refresh_token", tokenRequests.last()["grant_type"])
    }

    @Test
    fun expiredAccessTokenWithoutRefreshTokenRequiresAuthorization() {
        val connector = connector()
        secrets.setBearerToken(
            apiConnectorSecretKey(connector.id),
            JSONObject()
                .put("accessToken", "expired-token")
                .put("expiresAt", System.currentTimeMillis() - 1_000L)
                .toString(),
        )
        val client = ApiOAuth2Client(
            context = context,
            secretStore = secrets,
            connectorProvider = { connector },
        )
        val failure = assertThrows(ApiConnectorException::class.java) {
            client.accessToken(connector)
        }
        assertEquals("OAUTH_REFRESH_MISSING", failure.code)
        assertTrue(tokenRequests.isEmpty())
    }

    @Test
    fun providerErrorTextCannotLeakClientSecretsOrTokens() {
        val connector = connector()
        val client = ApiOAuth2Client(
            context = context,
            secretStore = secrets,
            connectorProvider = { connector },
        )
        val state = requireNotNull(
            Uri.parse(client.beginAuthorization(connector.id).authorizationUrl)
                .getQueryParameter("state"),
        )
        val result = client.completeAuthorization(
            Uri.parse("${ApiOAuth2Configuration.REDIRECT_URI}?state=$state&code=leak-code"),
        ) as ApiOAuthCallbackResult.Failure

        assertTrue(result.message.contains("失效"))
        assertFalse(result.message.contains("client-secret-leak"))
        assertFalse(result.message.contains("leak-code"))
    }

    @Test fun disconnectWhileRefreshingDoesNotRestoreRevokedCredentials() {
        val connector = connector()
        secrets.setBearerToken(apiConnectorSecretKey(connector.id), JSONObject()
            .put("accessToken", "expired").put("expiresAt", 1L).put("refreshToken", "refresh").toString())
        ApiOAuth2Client(context, secretStore = secrets, connectorProvider = { connector }).use { client ->
            beforeReply = { client.clear(connector.id) }
            val failure = assertThrows(ApiConnectorException::class.java) { client.accessToken(connector) }
            assertEquals("OAUTH_AUTHORIZATION_CHANGED", failure.code)
            assertFalse(client.credentialAvailable(connector.id))
            assertEquals(null, secrets.bearerToken(apiConnectorSecretKey(connector.id)))
        }
    }

    @Test fun olderCallbackCannotOverwriteOrClearNewAuthorization() {
        val connector = connector()
        ApiOAuth2Client(context, secretStore = secrets, connectorProvider = { connector }).use { client ->
            val oldState = Uri.parse(client.beginAuthorization(connector.id).authorizationUrl).getQueryParameter("state")
            var newState: String? = null
            beforeReply = { newState = Uri.parse(client.beginAuthorization(connector.id).authorizationUrl).getQueryParameter("state") }
            assertTrue(client.completeAuthorization(Uri.parse("${ApiOAuth2Configuration.REDIRECT_URI}?state=$oldState&code=old")) is ApiOAuthCallbackResult.Failure)
            assertFalse(client.credentialAvailable(connector.id))
            beforeReply = { }
            assertTrue(client.completeAuthorization(Uri.parse("${ApiOAuth2Configuration.REDIRECT_URI}?state=$newState&code=new")) is ApiOAuthCallbackResult.Success)
            assertEquals(2, tokenRequests.size)
        }
    }

    @Test fun concurrentClientsShareOneTokenRefreshAndNullRefreshIsMissing() {
        val connector = connector()
        secrets.setBearerToken(apiConnectorSecretKey(connector.id), JSONObject()
            .put("accessToken", "expired").put("expiresAt", 1L).put("refreshToken", "refresh").toString())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val first = ApiOAuth2Client(context, secretStore = secrets, connectorProvider = { connector })
        val second = ApiOAuth2Client(context, secretStore = secrets, connectorProvider = { connector })
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val futures = listOf(first, second).map { client -> pool.submit<String> { start.await(); client.accessToken(connector) } }
            start.countDown()
            futures.forEach { assertEquals("fresh-token", it.get(5, java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1, tokenRequests.size)
            secrets.setBearerToken(apiConnectorSecretKey(connector.id), JSONObject()
                .put("accessToken", "expired").put("expiresAt", 1L).put("refreshToken", JSONObject.NULL).toString())
            assertEquals("OAUTH_REFRESH_MISSING", assertThrows(ApiConnectorException::class.java) { first.accessToken(connector) }.code)
            assertEquals(1, tokenRequests.size)
        } finally { first.close(); second.close(); pool.shutdownNow() }
    }

    private fun connector() = ApiConnectorDefinition(
        id = "api-oauth-test",
        name = "OAuth test",
        baseUrl = "https://api.example.com",
        authType = ApiConnectorAuthType.OAUTH2,
        oauth = ApiOAuth2Configuration(
            authorizationEndpoint = "https://auth.example.com/authorize",
            tokenEndpoint = "http://127.0.0.1:${server.address.port}/token",
            clientId = "eta-test-client",
            scopes = listOf("read", "write"),
        ),
    )

}
