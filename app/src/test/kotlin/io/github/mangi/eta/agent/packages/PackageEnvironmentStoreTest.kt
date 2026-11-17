package io.github.mangi.eta.agent.packages

import android.app.Application
import io.github.mangi.eta.data.repository.McpSecretStore
import io.github.mangi.eta.data.repository.EtaPortableSecrets
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageEnvironmentStoreTest {
    @Test fun packageVariablesAreEncryptedIsolatedAndPortableAndDeletedWithoutReadingSecretsInTheUi() {
        val context = RuntimeEnvironment.getApplication()
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val secrets = McpSecretStore(context) { key }
        val first = PackageEnvironmentStore(context, secrets)
        first.put("tavily", "TAVILY_API_KEY", "fixture-private-key")
        assertEquals(listOf("TAVILY_API_KEY"), first.names("tavily"))
        assertEquals("fixture-private-key", first.value("tavily", "TAVILY_API_KEY"))
        assertNull(first.value("crossref", "TAVILY_API_KEY"))
        val raw = context.getSharedPreferences("eta_mcp_secrets", 0).all.toString()
        assertFalse(raw.contains("fixture-private-key")); assertFalse(raw.contains("TAVILY_API_KEY"))
        val portable = first.portable("tavily")!!
        first.restore("restored", portable)
        assertEquals("fixture-private-key", first.value("restored", "TAVILY_API_KEY"))
        assertTrue(EtaPortableSecrets(packageEnvironments = mapOf("tavily" to portable)).hasCredentials())
        first.clear("tavily")
        assertTrue(first.names("tavily").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { first.restore("bad", JSONObject().put("../outside", "no").toString()) }
    }
}
