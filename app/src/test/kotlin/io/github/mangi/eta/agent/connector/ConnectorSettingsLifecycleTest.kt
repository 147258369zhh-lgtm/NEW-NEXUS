package io.github.mangi.eta.agent.connector

import android.app.Application
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.control.*
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConnectorSettingsLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val secrets get() = A2ASecretStore(context) { SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES") }
    private val store get() = ConnectorConfigStore(context, secrets)
    private fun config(id: String = "", endpoint: String = "https://example.com/rpc") = SavedConnectorConfig(id, "Fixture", "jsonrpc", endpoint)
    private fun legacy(id: String) = "connector_${id.lowercase().replace(Regex("[^a-z0-9._-]"), "_")}".take(64)

    @Test fun corruptConfigurationIsNeverTreatedAsAnEmptyListOrOverwritten() {
        val prefs = context.getSharedPreferences("eta_connector_configs", 0)
        prefs.edit().putString("connections", "corrupt fixture").commit()
        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue(runCatching { store.save(config()) }.isFailure)
        assertEquals("corrupt fixture", prefs.getString("connections", null))
    }

    @Test fun formerlyCollidingIdsHaveIndependentSecretsAndStaleEditsCannotOverwrite() {
        val one = store.save(config("中文-one"), "first-private-token")
        val two = store.save(config("日文-one"), "second-private-token")
        assertEquals(legacy(one.id), legacy(two.id))
        assertEquals("first-private-token", store.tokenOrThrow(one))
        assertEquals("second-private-token", store.tokenOrThrow(two))
        val renamed = store.save(one.copy(name = "Renamed"))
        assertTrue(runCatching { store.save(one.copy(enabled = false)) }.isFailure)
        assertTrue(runCatching { store.tokenOrThrow(one) }.isFailure)
        assertEquals("first-private-token", store.tokenOrThrow(renamed))
        store.remove(one.id)
        assertEquals("second-private-token", store.tokenOrThrow(two))
    }

    @Test fun legacyCredentialMigratesWithoutDecryptingAndAmbiguousLegacyCredentialsRequireReentry() {
        val single = store.save(config("legacy-unique"))
        secrets.save(legacy(single.id), "legacy-private-token")
        assertEquals("legacy-private-token", store.tokenOrThrow(single))
        assertNull(secrets.encryptedSnapshot(legacy(single.id)))
        val one = store.save(config("甲乙"))
        val two = store.save(config("丙丁"))
        secrets.save(legacy(one.id), "ambiguous-private-token")
        assertTrue(runCatching { store.tokenOrThrow(one) }.exceptionOrNull()?.message.orEmpty().contains("归属不明"))
        store.remove(two.id)
        assertTrue(runCatching { store.tokenOrThrow(one) }.isFailure)
        val repaired = store.save(one, "new-owner-token")
        assertEquals("new-owner-token", store.tokenOrThrow(repaired))
    }

    @Test fun uiCreationRenameDisableAndUndoUseTheSameAuditWithoutStoringCredentials() = runBlocking {
        val service = ConnectorSettingsService(context, store)
        val original = service.save(config(), "private-ui-token")
        val renamed = service.save(original.copy(name = "New title", enabled = false))
        assertEquals("private-ui-token", store.tokenOrThrow(renamed))
        val plane = NexusSettingsControlPlane.get(context)
        val audit = plane.audit().last { it.optString("operation") == "execute" }
        assertTrue(audit.getBoolean("rollbackSafe"))
        assertFalse(plane.audit().toString().contains("private-ui-token"))
        val rollback = async(Dispatchers.IO) { plane.rollbackJson(audit.getString("id"), SettingsActorKind.LOCAL_USER) }
        try {
            val approval = withTimeout(3000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            assertFalse(store.load().single().enabled)
            EtaAppControlConfirmation.approve(approval.id)
            assertTrue(withTimeout(3000) { rollback.await() }.getBoolean("ok"))
            val restored = store.load().single()
            assertEquals(original.name, restored.name)
            assertTrue(restored.enabled)
            assertEquals("private-ui-token", store.tokenOrThrow(restored))
            assertTrue(runCatching { service.save(renamed.copy(name = "Stale")) }.isFailure)
        } finally { rollback.cancelAndJoin() }
    }

    @Test fun tokenReplacementAndUrlQueriesAreNeverOfferedAsRestorablePublicSnapshots() = runBlocking {
        val service = ConnectorSettingsService(context, store)
        val first = service.save(config(), "first-private-token")
        val next = service.save(first, "next-private-token")
        val plane = NexusSettingsControlPlane.get(context)
        val audit = plane.audit().last { it.optString("operation") == "execute" }
        assertFalse(audit.getBoolean("rollbackSafe"))
        assertEquals("next-private-token", store.tokenOrThrow(next))
        service.save(config(endpoint = "https://example.com/rpc?token=query-private-token"))
        assertFalse(plane.audit().toString().contains("query-private-token"))
        assertFalse(plane.audit().last { it.optString("operation") == "execute" }.getBoolean("rollbackSafe"))
    }

    @Test fun revokingAConfiguredConnectionClosesAnActualBlockedHttpResponse() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rpc") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try { exchange.responseBody.write('{'.code); exchange.responseBody.flush(); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
            finally { exchange.close() }
        }
        server.start()
        val saved = store.save(config(endpoint = "http://127.0.0.1:${server.address.port}/rpc"))
        val transport = createSavedConnector(context, saved, null, ConnectorSecurityManager())
        val task = async(Dispatchers.IO) { runCatching { transport.invoke(ConnectorInvocation("call", JSONObject().put("method", "read").put("confirmed", true))) } }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            store.save(saved.copy(enabled = false))
            assertTrue(withTimeout(2000) { task.await() }.isFailure)
            assertTrue(runCatching { transport.probe() }.isFailure)
        } finally { transport.close(); release.countDown(); task.cancelAndJoin(); server.stop(0) }
    }
}
