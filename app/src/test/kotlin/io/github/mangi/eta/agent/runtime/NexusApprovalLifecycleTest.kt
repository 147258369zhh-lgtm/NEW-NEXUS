package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.capability.NexusExecutionGate
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlException
import io.github.mangi.eta.agent.control.EtaAppControlTool
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.kernel.api.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=Application::class)
class NexusApprovalLifecycleTest {
    @Test fun outerCancellationIsNotReportedAsTheApprovalTimeout() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val request = legacy()
            val canonical = NexusRunMapper.fromLegacy(request).request
            val recorder = NexusExecutionRecorder(store, canonical)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            val failure = runCatching {
                withTimeout(25) {
                    NexusExecutionGate.authorize(context, request, "terminal", JSONObject(), controller,
                        System.currentTimeMillis() + 60_000)
                }
            }.exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            val resolution = store.timeline(canonical.runId).single { it.event.type == RunEventType.APPROVAL_RESOLVED }
            assertEquals("APPROVAL_CANCELLED", resolution.fact.errorCode)
            assertEquals(RunState.CANCELLED, store.items(canonical.runId).single().state)
            assertNull(EtaAppControlConfirmation.pending.value)
            assertFalse(controller.waitingForApproval)
            recorder.finish(AgentRuntimeWire.RunResult(request.runId, false, "", "fixture stopped"), true)
        }
    }

    @Test fun failedApprovalResolutionCannotAuthorizeExecutionAndReleasesTheVisibleRequest() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val request = legacy()
            val canonical = NexusRunMapper.fromLegacy(request).request
            val recorder = NexusExecutionRecorder(store, canonical)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            store.writableDatabase.execSQL("""CREATE TRIGGER fail_resolution BEFORE INSERT ON execution_events
                WHEN NEW.type='APPROVAL_RESOLVED' BEGIN SELECT RAISE(ABORT,'fixture disk error'); END""")
            var executed = false
            val execution = async(Dispatchers.Default) { runCatching {
                val authorization = NexusExecutionGate.authorize(context, request, "terminal", JSONObject(), controller,
                    System.currentTimeMillis() + 60_000)
                if (authorization == CapabilityAuthorization.Allowed) executed = true
            } }
            try {
                val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                EtaAppControlConfirmation.approve(approval.id)
                assertTrue(withTimeout(3_000) { execution.await() }.isFailure)
                assertFalse(executed)
                assertNull(EtaAppControlConfirmation.pending.value)
                assertFalse(controller.waitingForApproval)
                assertEquals(RunState.WAITING_AUTH, store.find(canonical.runId)?.state)
                recorder.finish(AgentRuntimeWire.RunResult(request.runId, false, "", "recording failed"), false)
                assertEquals(RunState.INTERRUPTED, store.items(canonical.runId).single().state)
            } finally {
                controller.cancel()
                execution.cancelAndJoin()
            }
        }
    }

    @Test fun approvingWhileUserPausedSettlesApprovalWithoutResumingExecution() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val request = legacy()
            val canonical = NexusRunMapper.fromLegacy(request).request
            val recorder = NexusExecutionRecorder(store, canonical)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            val resolved = CompletableDeferred<Unit>()
            val observer = controller.observeApprovals {
                if (it.phase == RunApprovalPhase.APPROVED) resolved.complete(Unit)
            }
            val execution = async(Dispatchers.Default) {
                NexusExecutionGate.authorize(context, request, "terminal", JSONObject(), controller,
                    System.currentTimeMillis() + 60_000)
            }
            try {
                val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                controller.pause()
                assertEquals(RunState.WAITING_AUTH, store.find(canonical.runId)?.state)
                EtaAppControlConfirmation.approve(approval.id)
                withTimeout(3_000) { resolved.await() }
                assertEquals(RunState.PAUSED, store.find(canonical.runId)?.state)
                assertFalse(controller.waitingForApproval)
                assertFalse(execution.isCompleted)
                controller.resume()
                assertEquals(CapabilityAuthorization.Allowed, withTimeout(3_000) { execution.await() })
                assertEquals(RunState.RUNNING, store.find(canonical.runId)?.state)
                assertEquals(listOf(RunEventType.RUN_PAUSED, RunEventType.RUN_RESUMED),
                    store.timeline(canonical.runId).map { it.event.type }.filter {
                        it in setOf(RunEventType.RUN_PAUSED, RunEventType.RUN_RESUMED)
                    })
            } finally {
                controller.cancel()
                execution.cancelAndJoin()
                observer.close()
                recorder.finish(AgentRuntimeWire.RunResult(request.runId, false, "", "fixture stopped"), true)
            }
        }
    }

    @Test fun guiPauseAndResumeCommitBeforePublishingTheirStateAndDetachAtTerminal() {
        NexusExecutionStore(context).use { store ->
            val canonical = NexusRunMapper.fromLegacy(legacy()).request
            val recorder = NexusExecutionRecorder(store, canonical)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            val gui = GuiControlCoordinator()
            gui.openRun("approval-run", controller).use { run ->
                run.withAction {}
                assertTrue(gui.pauseForUser("approval-run"))
                assertEquals(RunState.PAUSED, store.find(canonical.runId)?.state)
                assertTrue(gui.resumeForUser())
                assertEquals(RunState.RUNNING, store.find(canonical.runId)?.state)
                store.writableDatabase.execSQL("""CREATE TRIGGER fail_pause BEFORE INSERT ON execution_events
                    WHEN NEW.type='RUN_PAUSED' BEGIN SELECT RAISE(ABORT,'fixture disk error'); END""")
                assertThrows(Exception::class.java) { gui.pauseForUser("approval-run") }
                assertFalse(controller.isPaused)
                assertFalse(gui.state.value.userPaused)
                assertEquals(RunState.RUNNING, store.find(canonical.runId)?.state)
                store.writableDatabase.execSQL("DROP TRIGGER fail_pause")
            }
            recorder.finish(AgentRuntimeWire.RunResult("approval-run", true, "fixture"), false)
            val terminal = store.find(canonical.runId)
            controller.pause(); controller.resume()
            assertEquals(terminal, store.find(canonical.runId))
        }
    }

    @Test fun nestedAppControlApprovalUsesTheToolInvocationOwnerAndRestoresScope()= runBlocking {
        NexusExecutionStore(context).use { store ->
            val canonical=NexusRunMapper.fromLegacy(legacy()).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            val execution=async(Dispatchers.Default) {
                assertNull(RuntimeInvocationScope.controller)
                try { RuntimeInvocationScope.withController(controller) {
                    EtaAppControlTool.execute(context,JSONObject().put("action","execute")
                        .put("id","settings.agent_permission")
                        .put("parameters",JSONObject().put("mode","full_access")))
                } } finally { assertNull(RuntimeInvocationScope.controller) }
            }
            try {
                val approval=withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertEquals("settings.agent_permission",approval.capabilityId)
                assertEquals(RunState.WAITING_AUTH,store.find(canonical.runId)?.state)
                EtaAppControlConfirmation.cancel(approval.id)
                assertFalse(JSONObject(withTimeout(3_000) { execution.await() }).getBoolean("ok"))
                assertEquals(RunState.RUNNING,store.find(canonical.runId)?.state)
                val resolved=store.timeline(canonical.runId).single { it.event.type==RunEventType.APPROVAL_RESOLVED }
                assertEquals("APPROVAL_REJECTED",resolved.fact.errorCode)
            } finally {
                execution.cancelAndJoin()
                EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
                recorder.finish(AgentRuntimeWire.RunResult("approval-run",false,"","fixture"),false)
            }
        }
    }

    private val context get()=RuntimeEnvironment.getApplication()
    private fun legacy()=AgentRuntimeWire.RunRequest("approval-run","fixture",
        AgentModelClient.ModelConfig(baseUrl="https://fixture.invalid",apiKey="MODEL_SECRET",model="fixture",systemPrompt=""),
        images=emptyList())

    @Test fun visibleApprovalSharesItsIdWithDurableWaitAndLiveSnapshot()= runBlocking {
        NexusExecutionStore(context).use { store ->
            val request=legacy();val canonical=NexusRunMapper.fromLegacy(request).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            val run=RuntimeRunRegistry.Run(request,AgentRuntimeSession(request.runId,controller))
            run.prepared(request)
            val execution=async(Dispatchers.Default) {
                NexusExecutionGate.authorize(context,request,"terminal",JSONObject().put("command","PRIVATE_COMMAND"),
                    controller,System.currentTimeMillis()+60_000)
            }
            try {
                val approval=withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                assertEquals(RunState.WAITING_AUTH,store.find(canonical.runId)?.state)
                assertEquals(AgentRuntimeWire.RunPhase.WaitingForAuthorization,run.snapshot().phase)
                val requested=store.timeline(canonical.runId).single { it.event.type==RunEventType.APPROVAL_REQUIRED }
                assertTrue(requested.fact.key.contains(approval.id))
                assertTrue(EtaAppControlConfirmation.approve(approval.id))
                assertEquals(CapabilityAuthorization.Allowed,withTimeout(3_000) { execution.await() })
                val resolved=store.timeline(canonical.runId).single { it.event.type==RunEventType.APPROVAL_RESOLVED }
                assertEquals(requested.event.itemId,resolved.event.itemId)
                assertEquals(true,resolved.fact.success)
                assertEquals(RunState.RUNNING,store.find(canonical.runId)?.state)
                assertFalse(controller.waitingForApproval)
                assertFalse(store.timeline(canonical.runId).toString().contains("PRIVATE_COMMAND"))
                assertFalse(store.timeline(canonical.runId).toString().contains("MODEL_SECRET"))
            } finally {
                execution.cancelAndJoin()
                EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
                recorder.finish(AgentRuntimeWire.RunResult(request.runId,false,"","fixture stopped"),false)
            }
        }
    }

    @Test fun timeoutClosesApprovalItemWithoutExecutingOrLeavingWaitingState()= runBlocking {
        NexusExecutionStore(context).use { store ->
            val request=legacy();val canonical=NexusRunMapper.fromLegacy(request).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            val failure=runCatching {
                NexusExecutionGate.authorize(context,request,"terminal",JSONObject(),controller,
                    System.currentTimeMillis()+60_000,approvalTimeoutMillis=25)
            }.exceptionOrNull()
            assertTrue(failure is EtaControlException)
            assertEquals("CONFIRMATION_TIMEOUT",(failure as EtaControlException).code)
            val resolved=store.timeline(canonical.runId).single { it.event.type==RunEventType.APPROVAL_RESOLVED }
            assertEquals("APPROVAL_TIMED_OUT",resolved.fact.errorCode)
            assertEquals(false,resolved.fact.success)
            assertEquals(RunState.RUNNING,store.find(canonical.runId)?.state)
            assertFalse(controller.waitingForApproval)
            assertNull(EtaAppControlConfirmation.pending.value)
            recorder.finish(AgentRuntimeWire.RunResult(request.runId,false,"","timed out"),false)
        }
    }

    @Test fun cancellationSettlesOnlyThisRunsApprovalAndRetainsCanonicalCancel()= runBlocking {
        NexusExecutionStore(context).use { store ->
            val request=legacy();val canonical=NexusRunMapper.fromLegacy(request).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            val execution=async(Dispatchers.Default) { runCatching {
                NexusExecutionGate.authorize(context,request,"terminal",JSONObject(),controller,System.currentTimeMillis()+60_000)
            } }
            try {
                withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                controller.cancel()
                val result=runCatching { withTimeout(3_000) { execution.await() } }
                assertTrue(result.isFailure || result.getOrNull()?.isFailure==true)
                assertNull(EtaAppControlConfirmation.pending.value)
                assertFalse(controller.waitingForApproval)
                val resolved=store.timeline(canonical.runId).single { it.event.type==RunEventType.APPROVAL_RESOLVED }
                assertEquals("APPROVAL_CANCELLED",resolved.fact.errorCode)
                recorder.finish(AgentRuntimeWire.RunResult(request.runId,false,"","stopped"),true)
                assertEquals(RunState.CANCELLED,store.find(canonical.runId)?.state)
            } finally { execution.cancelAndJoin() }
        }
    }

    @Test fun resolvingOneOfTwoPendingWaitsCannotResumeTheOther() {
        NexusExecutionStore(context).use { store ->
            val canonical=NexusRunMapper.fromLegacy(legacy()).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            val first=controller.beginApproval("terminal")
            val second=controller.beginApproval("app_state_control")
            first.resolve(RunApprovalPhase.APPROVED)
            assertEquals(RunState.WAITING_AUTH,store.find(canonical.runId)?.state)
            assertTrue(controller.waitingForApproval)
            second.resolve(RunApprovalPhase.REJECTED)
            first.close();second.close()
            assertEquals(RunState.RUNNING,store.find(canonical.runId)?.state)
            assertEquals(2,store.timeline(canonical.runId).count { it.event.type==RunEventType.APPROVAL_RESOLVED })
            recorder.finish(AgentRuntimeWire.RunResult("approval-run",true,"fixture"),false)
        }
    }

    @Test fun failedWaitRecordPreventsTheConfirmationUIFromOpening()= runBlocking {
        NexusExecutionStore(context).use { store ->
            val request=legacy();val canonical=NexusRunMapper.fromLegacy(request).request
            val recorder=NexusExecutionRecorder(store,canonical);val controller=AgentRunController()
            recorder.bindController(controller);recorder.start()
            store.writableDatabase.execSQL("""CREATE TRIGGER fail_approval BEFORE INSERT ON execution_events
                WHEN NEW.type='APPROVAL_REQUIRED' BEGIN SELECT RAISE(ABORT,'fixture disk error'); END""")
            assertTrue(runCatching { NexusExecutionGate.authorize(context,request,"terminal",JSONObject(),controller,
                System.currentTimeMillis()+60_000) }.isFailure)
            assertNull(EtaAppControlConfirmation.pending.value)
            assertFalse(controller.waitingForApproval)
            assertEquals(RunState.RUNNING,store.find(canonical.runId)?.state)
            recorder.finish(AgentRuntimeWire.RunResult(request.runId,false,"","fixture"),false)
        }
    }
}
