package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
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
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class McpCredentialRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("eta_mcp_secrets", Context.MODE_PRIVATE)
    private val server = McpServerSetting("credential-test", "Test", "https://example.com/mcp", authorizationType = McpAuthorizationType.BEARER)

    @Before fun reset() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        prefs.edit().clear().commit()
        resetRepositoryContext()
        McpServerRepository.init(context)
    }

    @After fun close() { EtaDatabase.closeForTests(); resetRepositoryContext() }

    private fun resetRepositoryContext() {
        // Application 会由 Robolectric 每项重建，生产单例也必须随夹具一并重置。
        McpServerRepository::class.java.getDeclaredField("applicationContext").apply {
            isAccessible = true
            set(null, null)
        }
    }

    private suspend fun seed() {
        EtaDatabase.get(context).mcpServerDao().insert(server.toEntity())
        prefs.edit().putString("bearer_${server.id}", "broken-ciphertext").commit()
    }

    @Test fun unrelatedSettingChangePreservesUnreadableCredential() = runBlocking(Dispatchers.IO) {
        seed()
        McpServerRepository.update(server.copy(enabled = false))
        assertFalse(McpServerRepository.serverById(server.id)!!.enabled)
        assertEquals("broken-ciphertext", prefs.getString("bearer_${server.id}", null))
    }

    @Test fun userCanRemoveAuthenticationDespiteInvalidOldCiphertext() = runBlocking(Dispatchers.IO) {
        seed()
        McpServerRepository.update(server.copy(authorizationType = McpAuthorizationType.NONE), bearerToken = "")
        assertEquals(McpAuthorizationType.NONE, McpServerRepository.serverById(server.id)!!.authorizationType)
        assertFalse(prefs.contains("bearer_${server.id}"))
    }

    @Test fun userCanDeleteServerDespiteInvalidOldCiphertext() = runBlocking(Dispatchers.IO) {
        seed()
        McpServerRepository.delete(server.id)
        assertNull(McpServerRepository.serverById(server.id))
        assertFalse(prefs.contains("bearer_${server.id}"))
    }

    @Test fun failedSettingsWriteRestoresOriginalUnreadableCiphertext() = runBlocking(Dispatchers.IO) {
        seed()
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mcp_update BEFORE UPDATE ON mcp_servers BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        val failure = runCatching {
            McpServerRepository.update(server.copy(authorizationType = McpAuthorizationType.NONE), bearerToken = "")
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(McpAuthorizationType.BEARER, McpServerRepository.serverById(server.id)!!.authorizationType)
        assertEquals("broken-ciphertext", prefs.getString("bearer_${server.id}", null))
    }

    @Test fun failedDeleteRestoresOriginalUnreadableCiphertext() = runBlocking(Dispatchers.IO) {
        seed()
        EtaDatabase.get(context).openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mcp_delete BEFORE DELETE ON mcp_servers BEGIN SELECT RAISE(ABORT, 'test'); END",
        )
        assertNotNull(runCatching { McpServerRepository.delete(server.id) }.exceptionOrNull())
        assertNotNull(McpServerRepository.serverById(server.id))
        assertEquals("broken-ciphertext", prefs.getString("bearer_${server.id}", null))
    }

    @Test fun missingBearerFailsBeforeRequestAndAnonymousServerIgnoresOrphanedSecret() {
        assertThrows(McpCredentialsUnavailable::class.java) { McpServerRepository.bearerToken(server) }
        prefs.edit().putString("bearer_${server.id}", "broken-ciphertext").commit()
        assertNull(McpServerRepository.bearerToken(server.copy(authorizationType = McpAuthorizationType.NONE)))
    }
}
