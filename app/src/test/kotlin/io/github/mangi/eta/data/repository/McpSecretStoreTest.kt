package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class McpSecretStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE)
    private var key: SecretKey? = null
    private val keyRequests = mutableListOf<Boolean>()
    private val store get() = McpSecretStore(context) { create ->
        keyRequests += create
        key ?: if (create) newKey().also { key = it } else error("test key unavailable")
    }

    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun missingCredentialDoesNotAccessOrGenerateKey() {
        assertNull(store.bearerToken("server"))
        assertEquals(McpCredentialStatus.MISSING, store.credentialStatus("server"))
        assertTrue(keyRequests.isEmpty())
    }

    @Test fun encryptedRoundTripKeepsPlaintextOutOfPreferences() {
        store.setBearerToken("server", " secret-token ")
        assertEquals("secret-token", store.bearerToken("server"))
        assertEquals(McpCredentialStatus.AVAILABLE, store.credentialStatus("server"))
        assertFalse(prefs.getString("bearer_server", null)!!.contains("secret-token"))
        assertEquals(listOf(true, false, false), keyRequests)
    }

    @Test fun missingKeyIsUnavailableAndReadingNeverCreatesReplacement() {
        store.setBearerToken("server", "secret-token")
        key = null
        keyRequests.clear()
        val original = store.encryptedSnapshot("server")
        val failure = assertThrows(McpCredentialsUnavailable::class.java) { store.bearerToken("server") }
        assertFalse(failure.message.orEmpty().contains("secret-token"))
        assertNull(failure.cause)
        assertEquals(McpCredentialStatus.UNAVAILABLE, store.credentialStatus("server"))
        assertEquals(listOf(false, false), keyRequests)
        assertEquals(original, store.encryptedSnapshot("server"))
        assertNull(key)
    }

    @Test fun wrongDeviceKeyAndTamperedPayloadCannotBecomeAnonymousCredential() {
        store.setBearerToken("server", "secret-token")
        key = newKey()
        assertEquals(McpCredentialStatus.UNAVAILABLE, store.credentialStatus("server"))
        assertThrows(McpCredentialsUnavailable::class.java) { store.bearerToken("server") }
        prefs.edit().putString("bearer_server", "not-valid-ciphertext").commit()
        assertEquals(McpCredentialStatus.UNAVAILABLE, store.credentialStatus("server"))
    }

    @Test fun explicitlyReplacingUnavailableTokenGeneratesNewKeyAndRecovers() {
        store.setBearerToken("server", "old-token")
        key = null
        store.setBearerToken("server", "replacement-token")
        assertEquals("replacement-token", store.bearerToken("server"))
        assertEquals(McpCredentialStatus.AVAILABLE, store.credentialStatus("server"))
    }

    @Test fun encryptedRollbackPreservesUnreadableValueWithoutKeyAccess() {
        prefs.edit().putString("bearer_server", "original-broken-ciphertext").commit()
        val snapshot = store.encryptedSnapshot("server")
        store.clear("server")
        store.restoreEncryptedSnapshot("server", snapshot)
        assertEquals("original-broken-ciphertext", prefs.getString("bearer_server", null))
        assertTrue(keyRequests.isEmpty())
        store.restoreEncryptedSnapshot("server", null)
        assertFalse(prefs.contains("bearer_server"))
    }

    @Test fun explicitClearOfUnreadableCredentialDoesNotRequireKey() {
        prefs.edit().putString("bearer_server", "broken").commit()
        store.setBearerToken("server", " ")
        assertEquals(McpCredentialStatus.MISSING, store.credentialStatus("server"))
        assertTrue(keyRequests.isEmpty())
    }
}
