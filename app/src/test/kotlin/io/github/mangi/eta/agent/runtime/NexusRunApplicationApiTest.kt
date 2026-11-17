package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunBudgets
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusRunApplicationApiTest {
    @Test fun activeBackupAndPendingRestoreBlockAdmissionWithoutCreatingARunAndReleaseOnFailure() = kotlinx.coroutines.runBlocking {
        val context = RuntimeEnvironment.getApplication()
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            api.configureBackupGuard(context)
            val denied = io.github.mangi.eta.data.repository.EtaBackupOperations.exclusive(backup = true) {
                api.admit(request("during-backup")) { error("Must not create Runtime") }
            } as RuntimeRunRegistry.Admission.Rejected
            assertEquals("BACKUP_IN_PROGRESS", denied.code)
            assertNull(store.find(RunId("during-backup")))
            val failure = runCatching {
                io.github.mangi.eta.data.repository.EtaBackupOperations.exclusive(backup = true) { error("interrupted export") }
            }
            assertTrue(failure.isFailure)
            assertFalse(io.github.mangi.eta.data.repository.EtaBackupOperations.dataOperationActive)
            val state = java.io.File(context.dataDir, "eta_restore_control/state")
            state.parentFile!!.mkdirs(); state.writeText("pending")
            try {
                val pending = api.admit(request("pending-restore")) { error("Must not create Runtime") } as RuntimeRunRegistry.Admission.Rejected
                assertEquals("DATA_RESTORE_PENDING", pending.code)
                assertNull(store.find(RunId("pending-restore")))
            } finally { state.delete() }
        }
    }
    private fun request(id: String = "app-run") = AgentRuntimeWire.RunRequest(id, "private prompt",
        AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "private-key", model = "fixture", systemPrompt = ""),
        images = emptyList(), handoff = AgentRuntimeWire.EntryHandoff("handoff", "agent_ui", "conversation-1"))

    @Test fun actualAdmissionCommitsCanonicalStateAndPublishesOnlyCommittedEvents() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val registry = RuntimeRunRegistry()
            val api = NexusRunApplicationApi(store, registry) {}
            val legacy = request()
            val canonical = NexusRunMapper.fromLegacy(legacy).request
            val events = mutableListOf<ExecutionEvent>()
            val accepted = api.admit(legacy, listener = RunEventListener { event ->
                assertTrue(store.find(event.runId)!!.lastSequence >= event.sequence)
                events += event
            }) { recorder ->
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, eventRecorder = recorder::record,
                    terminalRecorder = { recorder.finish(it, cancelled = false) }))
            } as RuntimeRunRegistry.Admission.Accepted
            assertEquals(RunState.ADMITTED, store.find(canonical.runId)?.state)
            accepted.run.session.emit(AgentEvent.RunStarted(0,0,0,false))
            accepted.run.session.complete(AgentRuntimeWire.RunResult(legacy.runId,true,"private answer"))
            assertEquals(listOf(RunEventType.RUN_ADMITTED,RunEventType.RUN_STARTED,RunEventType.RUN_SUCCEEDED),events.map { it.type })
            assertEquals(RunState.SUCCEEDED,store.find(canonical.runId)?.state)
            assertFalse(store.timeline(canonical.runId).toString().contains("private"))
        }
    }

    @Test fun activeDuplicateDoesNotCreateASecondRuntimeAndCompletedRunCannotReexecute() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val registry = RuntimeRunRegistry()
            val api = NexusRunApplicationApi(store,registry) {}
            val legacy=request()
            var creations=0
            fun create(recorder: NexusExecutionRecorder): RuntimeRunRegistry.Run {
                creations++
                return RuntimeRunRegistry.Run(legacy,AgentRuntimeSession(legacy.runId,eventRecorder=recorder::record,
                    terminalRecorder={recorder.finish(it,cancelled=false)}))
            }
            val first=api.admit(legacy,create=::create) as RuntimeRunRegistry.Admission.Accepted
            assertTrue(api.admit(legacy,create=::create) is RuntimeRunRegistry.Admission.Duplicate)
            assertEquals(1,creations)
            first.run.session.complete(AgentRuntimeWire.RunResult(legacy.runId,false,"","fixture failure"))
            registry.finish(legacy.runId,first.run.session)
            val restarted=NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val denied=restarted.admit(legacy,create=::create) as RuntimeRunRegistry.Admission.Rejected
            assertEquals("RUN_ALREADY_TERMINAL",denied.code)
            assertEquals(1,creations)
        }
    }

    @Test fun metadataWithoutAuthenticatedInputCannotLaunchAnAgent() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val api=NexusRunApplicationApi(store,RuntimeRunRegistry()) {}
            val canonical=NexusRunMapper.fromLegacy(request()).request
            val denied=api.submit(canonical,RunEventListener {}) as RunAdmission.Rejected
            assertEquals("RUN_INPUT_REQUIRED",denied.code)
            assertNull(store.find(canonical.runId))
        }
    }

    @Test fun unfinishedDurableRunRequiresRecoveryInsteadOfReplayingAfterProcessDeath() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val legacy=request()
            val first=NexusRunApplicationApi(store,RuntimeRunRegistry()) {}
            first.admit(legacy) { RuntimeRunRegistry.Run(legacy,AgentRuntimeSession(legacy.runId)) }
            val restarted=NexusRunApplicationApi(store,RuntimeRunRegistry()) {}
            val denied=restarted.admit(legacy) { error("Must not execute an uncertain previous run") } as RuntimeRunRegistry.Admission.Rejected
            assertEquals("RUN_RECOVERY_REQUIRED",denied.code)
            assertEquals(2,store.timeline(NexusRunMapper.fromLegacy(legacy).request.runId).size)
        }
    }

    @Test fun planModeParentHandsPlanModeToItsDurableChildWhileOtherParentsKeepTaskChildren() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            fun admit(legacy: AgentRuntimeWire.RunRequest): RunId {
                lateinit var recorder: NexusExecutionRecorder
                api.admit(legacy) { created ->
                    recorder = created
                    RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId))
                }
                val canonical = NexusRunMapper.fromLegacy(legacy).request
                recorder.start()
                return canonical.runId
            }

            val planLegacy = request("plan-parent").copy(handoff = AgentRuntimeWire.EntryHandoff(
                "handoff", "agent_ui",
                AgentUiHandoffPayload("plan-conversation", workflowMode = "PLAN").toJson(),
            ))
            val planParent = NexusRunMapper.fromLegacy(planLegacy).request
            assertEquals(RunMode.PLAN, planParent.mode)
            val planChild = api.openChild(admit(planLegacy), "plan-child", "plan-session",
                AgentRunBudgets.forSubagent(8), AgentRunController())
            assertEquals(RunMode.PLAN, planChild.request.mode)
            assertEquals(RunSource.SUBAGENT, planChild.request.source)
            assertEquals(planParent.runId, planChild.request.parentRunId)

            val chatLegacy = request("chat-parent")
            assertEquals(RunMode.CHAT, NexusRunMapper.fromLegacy(chatLegacy).request.mode)
            val chatChild = api.openChild(admit(chatLegacy), "chat-child", "chat-session",
                AgentRunBudgets.forSubagent(8), AgentRunController())
            assertEquals(RunMode.TASK, chatChild.request.mode)
        }
    }

    @Test fun canonicalCancellationTargetsTheExactLiveRuntime() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val registry=RuntimeRunRegistry()
            val cancelled=mutableListOf<String>()
            val api=NexusRunApplicationApi(store,registry,cancelled::add)
            val legacy=request("run-中文")
            api.admit(legacy) { RuntimeRunRegistry.Run(legacy,AgentRuntimeSession(legacy.runId)) }
            api.cancel(NexusRunMapper.fromLegacy(legacy).request.runId,"user request")
            api.cancel(RunId("unrelated"),"user request")
            assertEquals(listOf(legacy.runId),cancelled)
        }
    }

    @Test fun twoMeetingRunsCannotWriteTheSameCanonicalParticipantSessionConcurrently() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val registry=RuntimeRunRegistry()
            val api=NexusRunApplicationApi(store,registry) {}
            val one=request("meeting-1").copy(
                modelSessionId="meeting:room:reviewer",
                handoff=AgentRuntimeWire.EntryHandoff("meeting-1","meeting","room"))
            val two=one.copy(runId="meeting-2")
            api.admit(one) { recorder -> RuntimeRunRegistry.Run(one,AgentRuntimeSession(one.runId,eventRecorder=recorder::record)) }
            val denied=api.admit(two) { error("Concurrent session must not start") } as RuntimeRunRegistry.Admission.Rejected
            assertEquals("CONVERSATION_BUSY",denied.code)
            assertNull(store.find(NexusRunMapper.fromLegacy(two).request.runId))
        }
    }
}
