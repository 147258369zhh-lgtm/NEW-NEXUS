package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.workflow.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.*
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
@Config(sdk = [36], application = Application::class)
class RunBudgetIncreaseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun invoke(controller: AgentRunController, args: JSONObject) = RuntimeInvocationScope.withController(controller) {
        WorkflowToolExecutor.execute(context, "increase-session", "goal_manage", args)
    }
    private fun create(controller: AgentRunController) = invoke(controller, JSONObject().put("action", "create")
        .put("objective", "fixture objective").put("soft_rounds", 2).put("hard_rounds", 2)
        .put("soft_tool_calls", 6).put("hard_tool_calls", 6).put("cost_limit_micros", 100))
        .getJSONObject("goal").getString("goal_id")
    private fun args(id: String) = JSONObject().put("action", "increase_budget").put("goal_id", id)
        .put("soft_rounds", 4).put("hard_rounds", 4).put("cost_limit_micros", 200).put("confirmed", true)
    private fun parent(api: NexusRunApplicationApi, controller: AgentRunController): NexusExecutionRecorder {
        lateinit var parent: NexusExecutionRecorder
        val wire = AgentRuntimeWire.RunRequest("increase-parent", "fixture", AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid", apiKey = "", model = "fixture", systemPrompt = ""), images = emptyList())
        api.admit(wire) { recorder ->
            parent = recorder; recorder.bindController(controller)
            RuntimeRunRegistry.Run(wire, AgentRuntimeSession(wire.runId, controller))
        }
        parent.start()
        return parent
    }

    @Test fun realLocalApprovalUpdatesGoalActiveFamilyAndRequestLedgerWithoutResettingUsage() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val controller = AgentRunController(); val parent = parent(api, controller)
            val id = create(controller)
            val childController = AgentRunController()
            val child = api.openChild(parent.request.runId, "child", "child", RunBudget(hardRounds = 1, hardToolCalls = 3), childController)
            child.start()
            val guard = AgentRunBudgetGuard(store.find(parent.request.runId)!!.request.budget)
            val childGuard = AgentRunBudgetGuard(child.request.budget)
            val bindings = listOf(guard.watch(controller), childGuard.watch(childController))
            try {
                guard.checkRound(2); guard.reserveToolCalls(3)
                repeat(2) { index ->
                    val request = ModelBudgetRequest("before-$index", parent.request.runId, "fixture", "fixture", "CHAT",
                        "USD", "price", "1", "1", "0", "0", 20)
                    store.reserveModelRequest(request, 5)
                    store.settleModelRequest(parent.request.runId, ModelBudgetSettlement(request.requestId, 20, true, true), 6)
                }
                val execution = async(Dispatchers.Default) { invoke(controller, args(id)) }
                try {
                    val pending = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertTrue(pending.capabilityId.startsWith("workflow.goal.budget:"))
                    assertEquals(RunState.WAITING_AUTH, store.find(parent.request.runId)!!.state)
                    assertEquals(2, guard.effectiveBudget().hardRounds)
                    EtaAppControlConfirmation.approve(pending.id)
                    val result = withTimeout(3_000) { execution.await() }
                    assertTrue(result.toString(), result.optBoolean("ok"))
                } finally { execution.cancelAndJoin() }
                assertEquals(4, guard.effectiveBudget().hardRounds)
                assertEquals(2L, guard.roundsUsed()); assertEquals(3, guard.toolCallsUsed())
                guard.checkRound(3)
                assertEquals(1, childGuard.effectiveBudget().hardRounds)
                assertEquals(3, childGuard.effectiveBudget().hardToolCalls)
                assertEquals(200L, store.modelBudgetTotals(child.request.runId).costLimitMicros)
                assertEquals(2L, store.modelBudgetTotals(child.request.runId).requests)
                assertEquals(40L, store.modelBudgetTotals(child.request.runId).knownCostMicros)
                store.reserveModelRequest(ModelBudgetRequest("after", parent.request.runId, "fixture", "fixture", "CHAT",
                    "USD", "price", "1", "1", "0", "0", 80), 7)
                store.settleModelRequest(parent.request.runId, ModelBudgetSettlement("after", 30, true, true), 8)
                assertEquals(70L, store.modelBudgetTotals(parent.request.runId).knownCostMicros)
                val later = api.openChild(parent.request.runId, "later", "later", RunBudget(hardRounds = 10), AgentRunController())
                assertEquals(4, later.request.budget.hardRounds)
                later.finish(AgentRuntimeWire.RunResult("later", false, ""), false)
                assertEquals(4, GoalPlanNotepadStore.get(context).executionSnapshot("increase-session").goal!!.budget.hardRounds)
                assertEquals(1, store.timeline(parent.request.runId).count { it.event.type == RunEventType.BUDGET_INCREASED })
                assertEquals(1, store.timeline(child.request.runId).count { it.event.type == RunEventType.BUDGET_INCREASED })
            } finally {
                bindings.forEach { it.close() }; controller.cancel(); childController.cancel()
                child.finish(AgentRuntimeWire.RunResult("child", false, ""), true)
                parent.finish(AgentRuntimeWire.RunResult("parent", false, ""), true)
            }
        }
    }

    @Test fun forgedFlagsAndAnOldApprovalCannotBypassLocalConfirmationOrSnapshotChecking() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val parent = parent(NexusRunApplicationApi(store, RuntimeRunRegistry()) {}, controller)
            val id = create(controller)
            val fact = ExecutionFact("forged", RunEventType.BUDGET_INCREASED, 10,
                budgetLimit = store.find(parent.request.runId)!!.request.budget.copy(hardRounds = 100), goalId = GoalId(id))
            assertThrows(IllegalArgumentException::class.java) { store.append(parent.request.runId, fact) }
            assertThrows(IllegalStateException::class.java) { store.increaseBudget(parent.request.runId,
                store.find(parent.request.runId)!!.request.budget, fact, parent.request.runId, "workflow.goal.budget:forged") }
            val execution = async(Dispatchers.Default) { invoke(controller, args(id)) }
            try {
                val pending = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                GoalPlanNotepadStore.get(context).setMode("increase-session", WorkflowMode.PLAN)
                EtaAppControlConfirmation.approve(pending.id)
                assertEquals("WORKFLOW_CHANGED_SINCE_APPROVAL", withTimeout(3_000) { execution.await() }.getString("code"))
                assertEquals(2, store.find(parent.request.runId)!!.request.budget.hardRounds)
                assertEquals(2, GoalPlanNotepadStore.get(context).executionSnapshot("increase-session").goal!!.budget.hardRounds)
            } finally { controller.cancel(); execution.cancelAndJoin(); parent.finish(AgentRuntimeWire.RunResult("parent", false, ""), true) }
        }
    }

    @Test fun rejectionDoesNotModifyTheGoalOrTheActiveBudget() = runBlocking {
        NexusExecutionStore(context).use { store ->
            val controller = AgentRunController(); val parent = parent(NexusRunApplicationApi(store, RuntimeRunRegistry()) {}, controller)
            val id = create(controller)
            val execution = async(Dispatchers.Default) { invoke(controller, args(id)) }
            try {
                val pending = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                EtaAppControlConfirmation.cancel(pending.id)
                assertEquals("LOCAL_APPROVAL_REJECTED", withTimeout(3_000) { execution.await() }.getString("code"))
                assertEquals(2, store.find(parent.request.runId)!!.request.budget.hardRounds)
                assertFalse(store.timeline(parent.request.runId).any { it.event.type == RunEventType.BUDGET_INCREASED })
            } finally { controller.cancel(); execution.cancelAndJoin(); parent.finish(AgentRuntimeWire.RunResult("parent", false, ""), true) }
        }
    }
}
