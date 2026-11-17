package io.github.mangi.eta.agent.dsh

import android.app.Application
import io.github.mangi.eta.agent.connector.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraft
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import io.github.mangi.eta.data.repository.EtaPortableSecrets
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshCredentialAndDraftTest {
    @Test fun connectionIdentityChangesClearOldSecretsAndCookieRefreshKeepsTheSameEpoch() {
        val app = RuntimeEnvironment.getApplication()
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val store = ConnectorConfigStore(app, A2ASecretStore(app) { key })
        val first = store.save(SavedConnectorConfig("", "Fixture", "rest", "https://example.com"), "private-key")
        assertEquals(1L, first.credentialRevision)
        store.updateToken(first.id, "refreshed-cookie", identityChanged = false, expectedRevision = 1)
        assertEquals(1L, store.load().single().credentialRevision)
        assertEquals("refreshed-cookie", store.tokenOrThrow(first))
        assertTrue(EtaPortableSecrets(connectorCredentials = store.exportCredentials()).hasCredentials())
        val moved = store.save(first.copy(endpoint = "https://other.example.com"))
        assertNull(store.tokenOrThrow(moved)); assertEquals(2L, moved.credentialRevision)
        assertThrows(IllegalArgumentException::class.java) { store.updateToken(moved.id, "stale-cookie", false, expectedRevision = 1) }
        val prefs = app.getSharedPreferences("eta_a2a_secrets", 0).all.toString()
        assertFalse(prefs.contains("private-key")); assertFalse(prefs.contains("refreshed-cookie"))
    }

    @Test fun readingAnUnrecoverableSecretNeverCreatesAReplacementKey() {
        val app = RuntimeEnvironment.getApplication()
        val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        A2ASecretStore(app) { key }.save("fixture", "original")
        var creating = false
        val broken = A2ASecretStore(app) { create -> creating = create; error("key unavailable") }
        assertTrue(runCatching { broken.load("fixture") }.isFailure)
        assertFalse(creating)
        assertEquals("original", A2ASecretStore(app) { key }.load("fixture"))
    }

    @Test fun insertionUpdatesTheActualMountedEditorSelectionAndPersistsWithoutASendOrStaleOverwrite() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = ConversationDraftRepository(app.getSharedPreferences("dsh-drafts", 0), scope)
            val subject = ConversationSubject.chat("chat")
            val lease = store.open(subject)
            var editor = ConversationDraft("Hello old world", 6, 9)
            store.registerActive(lease, replace = { editor = it }) { editor }.use {
                assertTrue(store.insertExternal(subject, "new"))
                assertEquals("Hello new world", editor.text); assertEquals(9, editor.selectionStart)
                editor = ConversationDraft(editor.text + "!")
                store.save(lease, editor).await()
            }
            val restored = ConversationDraftRepository(app.getSharedPreferences("dsh-drafts", 0), scope)
            assertEquals("Hello new world!", restored.load(restored.open(subject))!!.text)
            assertFalse(store.insertExternal(subject, "blocked") { false })
            assertEquals("Hello new world!", store.load(store.open(subject))!!.text)
            val meeting = ConversationSubject.meeting("chat")
            assertTrue(store.insertExternal(meeting, "separate"))
            assertEquals("separate", store.load(store.open(meeting))!!.text)
            assertEquals("Hello new world!", store.load(store.open(subject))!!.text)
        } finally { scope.cancel() }
    }
}
