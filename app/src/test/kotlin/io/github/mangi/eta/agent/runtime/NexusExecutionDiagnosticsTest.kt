package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=Application::class)
class NexusExecutionDiagnosticsTest {
    @Test fun fourWayComparisonRequiresLedgerMetricsCheckpointAndUiItems() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val context = RuntimeEnvironment.getApplication()
            val legacyRunId = "shadow four way"
            val canonicalRunId = NexusRunMapper.canonicalRunId(legacyRunId).value
            val request = RunRequest(RunId(canonicalRunId), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request)
            recorder.start()
            recorder.prepareInput(emptyList(), "fixture", 0)
            recorder.record(AgentEvent.RoundStarted(1, 1))
            recorder.record(AgentEvent.ProviderRequestStarted(1))
            recorder.record(AgentEvent.ToolStarted(1, "call-1", "read_file", "{}"))
            recorder.record(AgentEvent.ToolFinished(1, "call-1", "read_file", "ok", 0, 0, success = true))
            val transcript = listOf(AgentModelClient.ConversationMessage(role = "assistant", content = "answer"))
            val snapshot = AgentContextSnapshot(operationId = "operation", messages = transcript)
            recorder.saveCheckpoint(transcript, snapshot)
            recorder.finish(AgentRuntimeWire.RunResult(canonicalRunId, true, "answer", transcript = transcript), false)

            val directory = File(context.filesDir, "execution-ledger").apply {
                deleteRecursively()
                mkdirs()
            }
            val file = File(directory, legacyRunId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".jsonl")
            ExecutionShadowRecorder(file, JSONObject().put("run_id", legacyRunId)
                .put("canonical_run_id", canonicalRunId)).use { shadow ->
                shadow.record(AgentEvent.RoundStarted(1, 1))
                shadow.record(AgentEvent.ProviderRequestStarted(1))
                shadow.record(AgentEvent.ToolStarted(1, "call-1", "read_file", "{}"))
                shadow.record(AgentEvent.ToolFinished(1, "call-1", "read_file", "ok", 0, 0, success = true))
                shadow.finish(AgentRuntimeWire.RunResult(legacyRunId, true, "answer", transcript = transcript), false)
                assertTrue(shadow.awaitClosed())
            }
            val metrics = AgentRunMetricsRecord(legacyRunId, legacyRunId, "owner", "chat", "chat", "session",
                "", "", "", 1, 2, 2, 1, "succeeded", 1, 0,
                AgentRunMetrics(toolCalls = 1, requestAttempts = 1))
            val summary = ExecutionShadowRecorder.inspectionSummary(
                context = context,
                metrics = listOf(metrics),
                limit = 1,
                store = store,
                checkpoints = emptyList(),
                archivedRuns = emptyList(),
            )
            val row = summary.getJSONArray("runs").getJSONObject(0)
            assertEquals("match", row.getString("metricsComparison"))
            assertEquals("valid", row.getJSONObject("checkpoint").getString("status"))
            assertEquals("match", row.getJSONObject("uiProjection").getString("status"))
            assertEquals(0, row.getJSONArray("missingSources").length())
            assertTrue(summary.getBoolean("fullComparisonComplete"))
        }
    }

    @Test fun waitingAndPausedRunsStillMatchRunningMetrics() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request = RunRequest(RunId("waiting-diagnostics"), RunSource.GOAL, RunMode.GOAL, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request)
            val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val metrics = AgentRunMetricsRecord("waiting-diagnostics", "waiting-diagnostics", "owner", "chat", "chat",
                "session", "", "", "", 1, 2, null, 1, "running", 1, 0, AgentRunMetrics())
            controller.pause()
            assertEquals("match", NexusExecutionDiagnostics.inspect(store, store.find(request.runId)!!, metrics).getString("metricsComparison"))
            controller.resume()
            val approval = controller.beginApproval("terminal")
            assertEquals("match", NexusExecutionDiagnostics.inspect(store, store.find(request.runId)!!, metrics).getString("metricsComparison"))
            approval.resolve(RunApprovalPhase.REJECTED)
            recorder.finish(AgentRuntimeWire.RunResult("waiting-diagnostics", false, "", "fixture"), false)
        }
    }

    @Test fun incompleteWorkflowWritesAreVisibleInTheActualRuntimeEvidence() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request = RunRequest(RunId("workflow-diagnostics"), RunSource.GOAL, RunMode.GOAL, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request)
            val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            controller.recordWorkflowChange(RunWorkflowChange("pending", RunEventType.WORKFLOW_UPDATE_STARTED, "notepad_manage"))
            val result = NexusExecutionDiagnostics.inspect(store, store.find(request.runId)!!, null)
            assertEquals(1, result.getInt("unresolvedWorkflowEffects"))
            assertEquals(0, result.getInt("workflowRevisions"))
            assertTrue(result.getJSONArray("issues").toString().contains("UNRESOLVED_WORKFLOW_EFFECT"))
            recorder.finish(AgentRuntimeWire.RunResult("workflow-diagnostics", false, "", "interrupted"), false)
        }
    }

    @Test fun comparisonUsesActualStoredEventsAndPrivateCheckpointWithoutLeakingContent() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request=RunRequest(RunId("diagnostic-run"),RunSource.CHAT,RunMode.CHAT,HostId("native-android"))
            val recorder=NexusExecutionRecorder(store,request)
            recorder.start();recorder.prepareInput(emptyList(),"PRIVATE_USER_TEXT",1)
            recorder.record(AgentEvent.RoundStarted(1,1))
            recorder.record(AgentEvent.ProviderRequestStarted(1))
            recorder.record(AgentEvent.ToolStarted(1,"tool","read_file","PRIVATE_ARGS"))
            recorder.record(AgentEvent.ToolFinished(1,"tool","read_file","PRIVATE_RESULT",0,0,true))
            recorder.saveCheckpoint(listOf(AgentModelClient.ConversationMessage("assistant","PRIVATE_ANSWER")),null)
            recorder.finish(AgentRuntimeWire.RunResult("diagnostic-run",true,"done"),false)
            val metrics=AgentRunMetricsRecord("diagnostic-run","diagnostic-run","owner","chat","chat","session","","","",
                1,2,2,1,"succeeded",1,0,AgentRunMetrics(toolCalls=1,requestAttempts=1))
            val report=NexusExecutionDiagnostics.report(store,listOf(metrics))
            val row=report.getJSONArray("runs").getJSONObject(0)
            assertEquals("valid",row.getString("checkpointVerification"))
            assertEquals("match",row.getString("metricsComparison"))
            assertEquals(0,row.getInt("unresolvedToolEffects"))
            assertTrue(row.getBoolean("inputImagesRequireReattach"))
            assertFalse(report.toString().contains("PRIVATE_"))
            assertFalse(report.getBoolean("recoveryEnabled"))
            assertFalse(report.getBoolean("fullComparisonComplete"))
            val missing=NexusExecutionDiagnostics.report(store,emptyList()).getJSONArray("runs").getJSONObject(0)
            assertEquals("missing",missing.getString("metricsComparison"))
            val wrong=NexusExecutionDiagnostics.report(store,listOf(metrics.copy(metrics=AgentRunMetrics(toolCalls=2))))
            assertEquals(0,wrong.getInt("matchingMetricsRuns"))
        }
    }

    @Test fun corruptSnapshotAndUnknownEffectRemainExplicitlyUnverified() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val request=RunRequest(RunId("uncertain"),RunSource.AUTOMATION,RunMode.TASK,HostId("native-android"))
            val recorder=NexusExecutionRecorder(store,request)
            recorder.start();recorder.prepareInput(emptyList(),"fixture",1)
            recorder.record(AgentEvent.ToolStarted(1,"tool","write_file","{}"))
            store.writableDatabase.execSQL("UPDATE execution_checkpoint_chunks SET body='corrupt'")
            val report=NexusExecutionDiagnostics.report(store,emptyList()).getJSONArray("runs").getJSONObject(0)
            assertEquals("corrupt",report.getString("checkpointVerification"))
            assertEquals(1,report.getInt("unresolvedToolEffects"))
            assertEquals("legacy-checkpoint",report.getString("recoveryAuthority"))
        }
    }
}
