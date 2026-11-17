package io.github.mangi.eta.agent.packages

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunBudgetGuard
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.NexusExecutionStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageEventRecoveryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val tool = PackageTool("event", "Event fixture", "main.js",
        """{"type":"object","properties":{"eventName":{"type":"string"},"eventPayload":{"type":"object"}},"required":["eventName","eventPayload"]}""",
        "AppLifecycleHook/foreground/function")
    private fun record() = CapabilityPackage("fixture.events", "Events", "", "1", "plugin", "a".repeat(64), listOf(tool), true,
        allowedTools = setOf("read_file", "write_file"), mainEntry = "main.js", enabledExtensions = setOf("AppLifecycleHook"),
        registrations = JSONArray().put(JSONObject().put("kind", "AppLifecycleHook").put("id", "foreground").put("event", "application_on_foreground")
            .put("function", JSONObject().put("nexus_callback", tool.callbackKey))).toString())
    private fun delivery(record: CapabilityPackage = record(), text: String = "private body") = PackageEventDispatcher.select(listOf(record),
        "AppLifecycleHook", "application_on_foreground", JSONObject().put("text", text), ConversationSubject.meeting("meeting-original")).single()

    @Test fun committedNotificationsSurviveReopeningAndUseTheOriginalIdentityOnce() = runBlocking {
        val value = delivery()
        PackageEventJournal(context).use { assertTrue(it.enqueue(listOf(value))) }
        val received = Collections.synchronizedList(mutableListOf<PackageEventDelivery>())
        val dispatcher = PackageEventDispatcher(PackageEventJournal(context), { listOf(record()) }, { received += it }, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            withTimeout(4000) { dispatcher.awaitIdle() }
            assertEquals(value.runId, received.single().runId)
            assertEquals(value.subject, received.single().subject)
            assertEquals("private body", received.single().arguments.getJSONObject("eventPayload").getString("text"))
            dispatcher.refresh(); withTimeout(4000) { dispatcher.awaitIdle() }
            assertEquals(1, received.size)
            PackageEventJournal(context).use { journal ->
                assertTrue(journal.unsettled().isEmpty())
                assertEquals(PackageEventStatus.SUCCEEDED, journal.history(value.record.id).single().status)
                journal.readableDatabase.rawQuery("SELECT arguments_json,subject_id FROM deliveries", null).use { row ->
                    assertTrue(row.moveToFirst()); assertTrue(row.isNull(0)); assertTrue(row.isNull(1))
                }
            }
        } finally { dispatcher.shutdown() }
    }

    @Test fun aClaimedUnknownCallbackIsNeverReplayedWhileNewPendingWorkStillRuns() = runBlocking {
        val old = delivery(); val next = delivery(text = "next")
        PackageEventJournal(context).use { journal -> assertTrue(journal.enqueue(listOf(old, next))); assertTrue(journal.claim(old.runId)) }
        val received = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = PackageEventDispatcher(PackageEventJournal(context), { listOf(record()) }, { received += it.runId }, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            withTimeout(4000) { dispatcher.awaitIdle() }; dispatcher.refresh(); withTimeout(4000) { dispatcher.awaitIdle() }
            assertEquals(listOf(next.runId), received.toList())
            PackageEventJournal(context).use { journal ->
                assertEquals(PackageEventStatus.UNKNOWN, journal.history(old.record.id).single { it.runId == old.runId }.status)
                assertFalse(journal.archiveUnknown("other.package", old.runId))
                assertTrue(journal.archiveUnknown(old.record.id, old.runId)); assertTrue(journal.unsettled().isEmpty())
                assertEquals(PackageEventStatus.UNKNOWN, journal.history(old.record.id).single { it.runId == old.runId }.status)
            }
        } finally { dispatcher.shutdown() }
    }

    @Test fun changedVersionsOrRevokedExtensionsCannotReceiveQueuedOldEvents() = runBlocking {
        val value = delivery()
        PackageEventJournal(context).use { assertTrue(it.enqueue(listOf(value))) }
        var calls = 0
        val dispatcher = PackageEventDispatcher(PackageEventJournal(context), { listOf(record().copy(digest = "b".repeat(64))) }, { calls++ },
            CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            withTimeout(4000) { dispatcher.awaitIdle() }
            assertEquals(0, calls)
            PackageEventJournal(context).use { assertEquals(PackageEventStatus.REVOKED, it.history(value.record.id).single().status) }
            assertTrue(PackageEventDispatcher.select(listOf(record().copy(enabledExtensions = emptySet())), "AppLifecycleHook",
                "application_on_foreground", JSONObject(), null).isEmpty())
        } finally { dispatcher.shutdown() }
    }

    @Test fun originalRuntimeReceiptRecoversSuccessEvenWhenTheSurroundingRunWasCancelled() = runBlocking {
        val value = delivery()
        PackageEventJournal(context).use { assertTrue(it.enqueue(listOf(value))); assertTrue(it.claim(value.runId)) }
        val request = RuntimePackageActions.request(value.record, value.tool, value.arguments, value.subject, value.runId)
        val mapped = NexusRunMapper.fromLegacy(request).request
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController()
            val recorder = NexusExecutionRecorder(store, mapped).apply { bindController(controller); start() }
            assertThrows(AgentRunCancelledException::class.java) {
                RuntimePackageActions.execute(request, AgentModelClient.ToolExecutor {
                    controller.cancel(); AgentModelClient.ToolResult("{\"ok\":true,\"value\":true}")
                }, AgentRunBudgetGuard(mapped.budget), controller, recorder::record)
            }
            recorder.finish(AgentRuntimeWire.RunResult(value.runId, false, "cancelled"), true)
        }
        val journal = PackageEventJournal(context)
        val outcome = PackageApplicationEvents.reconcile(context, journal.unsettled().single())
        assertEquals(PackageEventStatus.SUCCEEDED, outcome)
        journal.finish(value.runId, outcome!!); journal.close()
    }

    @Test fun aFullInboxRejectsTheEntireNewBatchAndArchivingUnknownReleasesPayloadCapacity() {
        val values = (1..128).map { delivery() }
        PackageEventJournal(context).use { journal ->
            assertTrue(journal.enqueue(values)); assertFalse(journal.enqueue(listOf(delivery())))
            assertEquals(128, journal.unsettled().size)
            assertTrue(journal.claim(values.first().runId)); journal.finish(values.first().runId, PackageEventStatus.UNKNOWN)
            assertTrue(journal.archiveUnknown(values.first().record.id, values.first().runId))
            assertTrue(journal.enqueue(listOf(delivery())))
            assertEquals(128, journal.unsettled().size)
        }
    }
    @Test fun revocationClearsPrivatePendingDataAndNoSubscribersCreateNoBackgroundWork() = runBlocking {
        val value = delivery()
        PackageEventJournal(context).use { journal ->
            assertTrue(journal.enqueue(listOf(value))); journal.revoke(value.record.id)
            assertTrue(journal.unsettled().isEmpty())
            assertEquals(PackageEventStatus.REVOKED, journal.history(value.record.id).single().status)
        }
        val dispatcher = PackageEventDispatcher(PackageEventJournal(context), { emptyList() }, { error("Unexpected callback") },
            CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            assertFalse(dispatcher.submit("AppLifecycleHook", "application_on_foreground", JSONObject()))
            withTimeout(4000) { dispatcher.awaitIdle() }
        } finally { dispatcher.shutdown() }
    }
}
