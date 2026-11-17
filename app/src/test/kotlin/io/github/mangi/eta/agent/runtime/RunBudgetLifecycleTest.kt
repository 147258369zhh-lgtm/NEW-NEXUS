package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.workflow.WorkflowToolExecutor
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.zhh_lgtm.nexus.application.api.ExecutionStore
import io.github.zhh_lgtm.nexus.application.api.ExecutionFact
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunBudgetLifecycleTest {
    @Test fun onlyBudgetIncreaseFactsCanCarryARunBudgetWithoutAGoal() {
        val budget = RunBudget(hardRounds = 4)
        val increase = ExecutionFact("approved-renewal", RunEventType.BUDGET_INCREASED, 0, budgetLimit = budget)
        assertEquals(budget, increase.budgetLimit)
        assertNull(increase.goalId)
        for (type in listOf(RunEventType.GOAL_UPDATED, RunEventType.BUDGET_RESTRICTED)) {
            assertThrows(IllegalArgumentException::class.java) {
                ExecutionFact("missing-goal", type, 0, budgetLimit = budget)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionFact("missing-budget", RunEventType.BUDGET_INCREASED, 0, goalId = GoalId("goal"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExecutionFact("unrelated-event", RunEventType.RUN_STARTED, 0, budgetLimit = budget)
        }
    }

    @Test fun openingAnotherChildCannotEscapeAnInProgressParentBudgetRestriction() {
        NexusExecutionStore(context).use { store ->
            val committing = CountDownLatch(1); val release = CountDownLatch(1)
            val delegated = object : ExecutionStore by store {
                override fun append(runId: RunId, fact: ExecutionFact): io.github.zhh_lgtm.nexus.application.api.ExecutionCommit {
                    if (fact.type == RunEventType.BUDGET_RESTRICTED && runId == RunId("race-parent")) {
                        committing.countDown(); check(release.await(3, TimeUnit.SECONDS))
                    }
                    return store.append(runId, fact)
                }
            }
            val api = NexusRunApplicationApi(delegated, RuntimeRunRegistry()) {}
            val parentController = AgentRunController(); val childController = AgentRunController()
            lateinit var parent: NexusExecutionRecorder
            val legacy = AgentRuntimeWire.RunRequest("race-parent", "fixture", AgentModelClient.ModelConfig(
                baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), images = emptyList())
            api.admit(legacy) { recorder ->
                parent = recorder; recorder.bindController(parentController)
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, parentController))
            }
            parent.start()
            val source = api.openChild(parent.request.runId, "source", "source", RunBudget(hardToolCalls = 10), childController)
            source.start()
            childController.recordWorkflowChange(RunWorkflowChange("race-goal", RunEventType.WORKFLOW_UPDATE_STARTED, "goal_manage"))
            val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "budget-race-fixture").apply { isDaemon = true } }
            try {
                val propagation = workers.submit {
                    childController.recordWorkflowChange(RunWorkflowChange("race-goal", RunEventType.GOAL_UPDATED, "goal_manage",
                        "nexus-workflow://fixture/goal/race", RunBudget(hardToolCalls = 2), GoalId("goal-race")))
                }
                assertTrue(committing.await(2, TimeUnit.SECONDS))
                val entered = CountDownLatch(1)
                val opening = workers.submit<NexusExecutionRecorder> {
                    entered.countDown()
                    api.openChild(parent.request.runId, "late", "late", RunBudget(hardToolCalls = 10), AgentRunController())
                }
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { opening.get(100, TimeUnit.MILLISECONDS) }
                release.countDown()
                propagation.get(3, TimeUnit.SECONDS)
                val late = opening.get(3, TimeUnit.SECONDS)
                assertEquals(2, late.request.budget.hardToolCalls)
                assertEquals(GoalId("goal-race"), late.request.goalId)
                for (recorder in listOf(late, source, parent)) recorder.finish(AgentRuntimeWire.RunResult("fixture", false, "", "fixture"), false)
            } finally { release.countDown(); workers.shutdownNow() }
        }
    }

    @Test fun childGoalRestrictionPropagatesToTheParentAndLiveSiblingsWithoutResettingCounters() {
        Prefs.initLocal(context)
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val parentController = AgentRunController()
            lateinit var parent: NexusExecutionRecorder
            val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
            val legacy = AgentRuntimeWire.RunRequest("parent-budget", "fixture", config, images = emptyList())
            api.admit(legacy) { recorder ->
                parent = recorder; recorder.bindController(parentController)
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, parentController, executionRequest = recorder.request))
            }
            parent.start()
            val childController = AgentRunController(); val siblingController = AgentRunController()
            val child = api.openChild(parent.request.runId, "child-budget", "child", RunBudget(hardToolCalls = 10), childController)
            val sibling = api.openChild(parent.request.runId, "sibling-budget", "sibling", RunBudget(hardToolCalls = 10), siblingController)
            child.start(); sibling.start()
            val guards = listOf(AgentRunBudgetGuard(parent.request.budget), AgentRunBudgetGuard(child.request.budget),
                AgentRunBudgetGuard(sibling.request.budget))
            val bindings = guards.zip(listOf(parentController, childController, siblingController)).map { (guard, controller) -> guard.watch(controller) }
            try {
                guards.first().reserveToolCalls(2)
                childController.recordWorkflowChange(RunWorkflowChange("child-goal", RunEventType.WORKFLOW_UPDATE_STARTED, "goal_manage"))
                childController.recordWorkflowChange(RunWorkflowChange("child-goal", RunEventType.GOAL_UPDATED, "goal_manage",
                    "nexus-workflow://fixture/goal/child", RunBudget(hardToolCalls = 2), GoalId("goal-child")))
                for (recorder in listOf(parent, child, sibling)) {
                    assertEquals(2, store.find(recorder.request.runId)?.request?.budget?.hardToolCalls)
                    assertEquals(GoalId("goal-child"), store.find(recorder.request.runId)?.request?.goalId)
                }
                guards.forEach { assertEquals(2, it.effectiveBudget().hardToolCalls) }
                assertEquals(2, guards.first().toolCallsUsed())
                assertThrows(AgentModelFailure::class.java) { guards.first().beforeToolCall() }
                assertTrue(store.timeline(parent.request.runId).any { it.event.type == RunEventType.BUDGET_RESTRICTED })
                assertTrue(store.timeline(sibling.request.runId).any { it.event.type == RunEventType.BUDGET_RESTRICTED })
            } finally {
                bindings.forEach(AutoCloseable::close)
                for (recorder in listOf(child, sibling, parent)) recorder.finish(AgentRuntimeWire.RunResult("fixture", false, "", "fixture"), false)
            }
        }
    }

    @Test fun failedParentBudgetCommitStopsTheFamilyInsteadOfContinuingWithWiderLimits() {
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val parentController = AgentRunController(); val childController = AgentRunController()
            lateinit var parent: NexusExecutionRecorder
            val legacy = AgentRuntimeWire.RunRequest("parent-budget-fail", "fixture", AgentModelClient.ModelConfig(
                baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""), images = emptyList())
            api.admit(legacy) { recorder ->
                parent = recorder; recorder.bindController(parentController)
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, parentController))
            }
            parent.start()
            val child = api.openChild(parent.request.runId, "failing-child", "child", RunBudget(hardToolCalls = 10), childController)
            child.start()
            store.writableDatabase.execSQL("""CREATE TRIGGER reject_inheritance BEFORE INSERT ON execution_events
                WHEN NEW.type='BUDGET_RESTRICTED' BEGIN SELECT RAISE(ABORT,'fixture'); END""")
            childController.recordWorkflowChange(RunWorkflowChange("child-goal", RunEventType.WORKFLOW_UPDATE_STARTED, "goal_manage"))
            assertThrows(IllegalStateException::class.java) {
                childController.recordWorkflowChange(RunWorkflowChange("child-goal", RunEventType.GOAL_UPDATED, "goal_manage",
                    "nexus-workflow://fixture/goal/child", RunBudget(hardToolCalls = 2), GoalId("goal-child")))
            }
            assertEquals("BUDGET_INHERITANCE_UNRECORDED", parentController.abortFailure?.message)
            assertEquals("BUDGET_INHERITANCE_UNRECORDED", childController.abortFailure?.message)
            assertEquals(2, store.find(child.request.runId)?.request?.budget?.hardToolCalls)
            child.finish(AgentRuntimeWire.RunResult("fixture", false, "", "fixture"), false)
            parent.finish(AgentRuntimeWire.RunResult("fixture", false, "", "fixture"), false)
        }
    }

    @Test fun actualModelLoopStopsAtTheNewGoalLimitWithoutRepeatingItsSuccessfulTool() {
        Prefs.initLocal(context)
        val budget = RunBudget(hardRounds = 8, hardToolCalls = 8, hardDurationMs = 60_000)
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request(budget))
            recorder.bindController(controller); recorder.start(); recorder.prepareInput(emptyList(), "fixture", 0)
            val guard = AgentRunBudgetGuard(budget)
            var calls = 0
            val provider = object : AgentProviderClient {
                override val id = "controlled-budget-fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                    calls++
                    onEvent(ProviderEvent.RequestStarted)
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                        .put("tool_calls", JSONArray().put(JSONObject().put("id", "create-goal").put("type", "function")
                            .put("function", JSONObject().put("name", "goal_manage").put("arguments", JSONObject()
                                .put("action", "create").put("objective", "fixture objective").put("hard_rounds", 1)
                                .put("hard_tool_calls", 1).toString())))))
                }
            }
            val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture",
                systemPrompt = "fixture", supportsTools = true, browserTools = false, terminalTools = false,
                subagentTools = false, autoCompactionEnabled = false)
            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(config, "fixture", AgentModelClient.ToolExecutor { call ->
                    RuntimeInvocationScope.withController(controller) {
                        AgentModelClient.ToolResult(WorkflowToolExecutor.execute(context, "budget-session", call.name,
                            JSONObject(call.argumentsJson)).toString())
                    }
                }, provider = provider, runController = controller, runBudget = budget, budgetGuard = guard,
                    onEvent = recorder::record, onTranscript = { recorder.saveCheckpoint(it, null) })
            }
            assertEquals("RUN_BUDGET_EXCEEDED_ROUNDS", (failure.cause as AgentModelFailure).code)
            assertEquals(1, calls)
            assertEquals(1, failure.transcript.count { it.role == "tool" && JSONObject(it.content).optBoolean("ok") })
            assertEquals(1, store.find(request(budget).runId)?.request?.budget?.hardRounds)
            assertEquals(1, JSONObject(store.checkpoint(request(budget).runId)!!.payload)
                .getJSONObject("effective_budget").getInt("hard_rounds"))
            recorder.finish(AgentRuntimeWire.RunResult("budget-run", false, "", "limit reached"), false)
        }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private fun request(budget: RunBudget) = RunRequest(RunId("budget-run"), RunSource.GOAL, RunMode.GOAL,
        HostId("native-android"), SessionId("budget-session"), budget = budget)

    @Test fun aGoalRevisionDoesNotReapplyItsOldLimitsAfterAResourceExtension() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController()
            val recorder = NexusExecutionRecorder(store, request(RunBudget(hardToolCalls = 8)))
            recorder.bindController(controller); recorder.start()
            val guard = AgentRunBudgetGuard(RunBudget(hardToolCalls = 8),
                approveHardLimit = { RunBudgetConfirmation.await(controller, it) })
            guard.watch(controller).use {
                val created = RuntimeInvocationScope.withController(controller) {
                    WorkflowToolExecutor.execute(context, "budget-session", "goal_manage", JSONObject().put("action", "create")
                        .put("objective", "保留同一目标").put("hard_tool_calls", 2))
                }.getJSONObject("goal")
                guard.reserveToolCalls(2)
                val execution = async(Dispatchers.Default) { guard.beforeToolCall() }
                try {
                    val pending = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    EtaAppControlConfirmation.approve(pending.id)
                    withTimeout(3_000) { execution.await() }
                    val extended = guard.effectiveBudget()
                    val revised = RuntimeInvocationScope.withController(controller) {
                        WorkflowToolExecutor.execute(context, "budget-session", "goal_manage", JSONObject().put("action", "revise")
                            .put("goal_id", created.getString("goal_id")).put("expected_revision", created.getLong("revision")))
                    }
                    assertTrue(revised.toString(), revised.getBoolean("ok"))
                    assertEquals(extended, guard.effectiveBudget())
                    assertEquals(extended, store.find(request(RunBudget()).runId)!!.request.budget)
                    assertEquals(3, guard.toolCallsUsed())
                } finally { controller.cancel(); execution.cancelAndJoin() }
            }
            recorder.finish(AgentRuntimeWire.RunResult("budget-run", false, "", "fixture"), true)
        }
    }

    @Test fun repeatedExtensionsWaitForApprovalAndUseTheSameDurableRun() = runBlocking {
        val budget = RunBudget(softRounds = 1, hardRounds = 3)
        NexusExecutionStore(context).use { store ->
            val recorder = NexusExecutionRecorder(store, request(budget)); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val guard = AgentRunBudgetGuard(budget, approveSoftLimit = { RunBudgetConfirmation.await(controller, it) })
            guard.watch(controller).use {
                guard.checkRound(1)
                val execution = async(Dispatchers.Default) { guard.checkRound(2) }
                try {
                    val pending = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertTrue(pending.capabilityId.startsWith("run_budget:extend:"))
                    assertEquals(RunState.WAITING_AUTH, store.find(request(budget).runId)?.state)
                    assertFalse(execution.isCompleted)
                    assertEquals(1L, guard.roundsUsed())
                    EtaAppControlConfirmation.approve(pending.id)
                    withTimeout(3_000) { execution.await() }
                    guard.checkRound(3)
                    assertEquals(RunState.RUNNING, store.find(request(budget).runId)?.state)
                    assertEquals(1, store.timeline(request(budget).runId).count { it.event.type == RunEventType.APPROVAL_REQUIRED })
                    val next = guard.effectiveBudget().softRounds!! + 1
                    val second = async(Dispatchers.Default) { guard.checkRound(next) }
                    try {
                        val renewal = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                        assertTrue(renewal.capabilityId.startsWith("run_budget:extend:"))
                        assertNotEquals(pending.capabilityId, renewal.capabilityId)
                        EtaAppControlConfirmation.approve(renewal.id)
                        withTimeout(3_000) { second.await() }
                        assertEquals(next.toLong(), guard.roundsUsed())
                        assertEquals(2, store.timeline(request(budget).runId).count { it.event.type == RunEventType.BUDGET_INCREASED })
                        assertEquals(guard.effectiveBudget(), store.find(request(budget).runId)!!.request.budget)
                        assertEquals(request(budget).taskId, store.find(request(budget).runId)!!.request.taskId)
                    } finally { second.cancelAndJoin() }
                    val stoppedAt = guard.roundsUsed()
                    val lastBudget = guard.effectiveBudget()
                    val declined = async(Dispatchers.Default) { runCatching { guard.checkRound(lastBudget.softRounds!! + 1) } }
                    try {
                        val refusal = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                        EtaAppControlConfirmation.reject(refusal.id)
                        assertTrue(withTimeout(3_000) { declined.await() }.isFailure)
                        assertEquals(stoppedAt, guard.roundsUsed())
                        assertEquals(lastBudget, guard.effectiveBudget())
                        assertNull(EtaAppControlConfirmation.pending.value)
                    } finally { declined.cancelAndJoin() }
                } finally { controller.cancel(); execution.cancelAndJoin() }
            }
            recorder.finish(AgentRuntimeWire.RunResult("budget-run", false, "", "fixture"), true)
        }
    }

    @Test fun goalCreatedInsideTheActualToolCallImmediatelyRestrictsItsActiveGuardAndPersists() {
        val budget = RunBudget(hardRounds = 8, hardToolCalls = 8, hardDurationMs = 60_000)
        NexusExecutionStore(context).use { store ->
            val recorder = NexusExecutionRecorder(store, request(budget)); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val guard = AgentRunBudgetGuard(budget)
            guard.watch(controller).use {
                guard.checkRound(1); guard.reserveToolCalls(2)
                val result = RuntimeInvocationScope.withController(controller) {
                    WorkflowToolExecutor.execute(context, "budget-session", "goal_manage", JSONObject().put("action", "create")
                        .put("objective", "fixture goal").put("hard_rounds", 2).put("hard_tool_calls", 2))
                }
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals(2, guard.effectiveBudget().hardRounds)
                assertEquals(2, guard.effectiveBudget().hardToolCalls)
                assertEquals(2, guard.toolCallsUsed())
                val run = store.find(request(budget).runId)!!
                assertEquals(2, run.request.budget.hardRounds)
                assertEquals(budget, run.admittedRequest.budget)
                assertNotNull(run.request.goalId)
                assertEquals("RUN_BUDGET_EXCEEDED_TOOL_CALLS", assertThrows(AgentModelFailure::class.java) {
                    guard.beforeToolCall()
                }.code)
                val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
                val child = api.openChild(run.request.runId, "child", "child", RunBudget(hardRounds = 10), AgentRunController())
                assertEquals(2, child.request.budget.hardRounds)
                assertEquals(run.request.goalId, child.request.goalId)
                val update = store.timeline(run.request.runId).single { it.event.type == RunEventType.GOAL_UPDATED }
                assertEquals(2, update.fact.budgetLimit?.hardRounds)
                assertEquals(run.request.goalId, update.event.goalId)
            }
            recorder.finish(AgentRuntimeWire.RunResult("budget-run", false, "", "fixture"), false)
        }
    }

    @Test fun cancellationWhileWaitingForExtraAllowanceReleasesApprovalWithoutSpendingTheNextTool() = runBlocking {
        val budget = RunBudget(softToolCalls = 1, hardToolCalls = 4)
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val recorder = NexusExecutionRecorder(store, request(budget))
            recorder.bindController(controller); recorder.start()
            val guard = AgentRunBudgetGuard(budget, approveSoftLimit = { RunBudgetConfirmation.await(controller, it) })
            guard.watch(controller).use {
                guard.beforeToolCall()
                val execution = async(Dispatchers.Default) { runCatching { guard.beforeToolCall() } }
                try {
                    withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    controller.cancel()
                    assertTrue(withTimeout(3_000) { execution.await() }.isFailure)
                    assertEquals(1, guard.toolCallsUsed())
                    assertNull(EtaAppControlConfirmation.pending.value)
                    assertEquals("APPROVAL_CANCELLED", store.timeline(request(budget).runId)
                        .single { it.event.type == RunEventType.APPROVAL_RESOLVED }.fact.errorCode)
                } finally { execution.cancelAndJoin() }
            }
            recorder.finish(AgentRuntimeWire.RunResult("budget-run", false, "", "stopped"), true)
        }
    }
}
