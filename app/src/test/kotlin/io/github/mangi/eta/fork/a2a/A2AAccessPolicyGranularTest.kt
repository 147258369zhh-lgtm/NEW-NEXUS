package io.github.mangi.eta.fork.a2a

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class A2AAccessPolicyGranularTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun granularGrantPersistsWithoutWideningLegacyOrRotatedCredentials() {
        val store = A2AAccessPolicyStore(context)
        val first = A2AAccessPolicyStore.tokenPeerId("granular-fixture")
        val second = A2AAccessPolicyStore.tokenPeerId("rotated-fixture")
        store.setTier("legacy-peer", A2AToolTier.RESTRICTED)
        store.setCustomTools(first, setOf("read_file", "cloud_media"))
        val recreated = A2AAccessPolicyStore(context)
        assertEquals(setOf("read_file", "cloud_media"), recreated.accessFor(first).allowedTools)
        assertEquals(A2AToolAccess.readOnly(second), recreated.accessFor(second))
        assertEquals(A2AToolTier.RESTRICTED, recreated.tier("legacy-peer"))
        recreated.setCustomTools(first, emptySet())
        assertTrue(store.accessFor(first).allowedTools.isEmpty())
    }

    @Test fun runtimeRechecksRevocationAndNeverAddsToolsToAcceptedTask() = kotlinx.coroutines.runBlocking {
        val peer = A2AAccessPolicyStore.tokenPeerId("runtime-fixture")
        val original = A2AToolAccess.custom(peer, setOf("read_file", "write_file"))
        val store = A2AAccessPolicyStore(context)
        store.setCustomTools(peer, original.allowedTools)
        val dao = A2ADatabase.get(context).dao()
        val id = "granular-task-${java.util.UUID.randomUUID()}"
        dao.upsertInboundTask(A2AInboundTaskEntity(taskId = id, requestMessageId = "message-$id", contextId = id,
            prompt = "fixture", state = A2ATaskState.WORKING.name, statusMessage = null, answer = null,
            createdAt = 1, updatedAt = 1, accessJson = A2A_JSON.encodeToString(original)))
        assertTrue(A2AAccessRuntimeGuard.isAllowed(context, id, "write_file"))
        store.setCustomTools(peer, setOf("read_file", "cloud_media"))
        assertFalse(A2AAccessRuntimeGuard.isAllowed(context, id, "write_file"))
        assertFalse(A2AAccessRuntimeGuard.isAllowed(context, id, "cloud_media"))
        assertTrue(A2AAccessRuntimeGuard.isAllowed(context, id, "read_file"))
        assertFalse(A2AAccessRuntimeGuard.isAllowed(context, "missing-task", "read_file"))
    }

    @Test fun callerCannotGrantWildcardOrPrivilegeControlThroughEditorStore() {
        val store = A2AAccessPolicyStore(context)
        listOf("*", "eta_control", "automation_manage", "subagent").forEach { denied ->
            try { store.setCustomTools("fixture", setOf(denied)); fail("Accepted $denied") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
