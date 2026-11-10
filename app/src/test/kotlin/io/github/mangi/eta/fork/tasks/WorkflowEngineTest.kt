package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkflowEngineTest {
    private val ready = AutomationState(true, true, true, 80)
    private fun step(id: String, kind: WorkflowStepKind = WorkflowStepKind.AGENT) = WorkflowStep(id, kind, id, prompt = "整理输入内容")

    @Test fun outputFlowsToNextStepAndNotification() = runBlocking {
        val inputs = mutableListOf<String>(); val notifications = mutableListOf<String>()
        val engine = WorkflowEngine({ ready }, { s, input, _ -> inputs += input; AgentTaskExecutionResult.Succeeded(s.id, "result:${s.id}") },
            { _, text -> notifications += text }, { _, _ -> })
        val result = engine.run(AutomationSpec(listOf(step("one"), step("two"), step("notify", WorkflowStepKind.NOTIFY).copy(prompt = ""))), "目标", "run")
        assertTrue(result is AgentTaskExecutionResult.Succeeded)
        assertEquals("result:one", JSONObject(inputs[1]).getString("previous_step_result"))
        assertEquals(listOf("result:two"), notifications)
    }

    @Test fun falseBranchSkipsConfiguredStepsAndRepeatsAreBounded() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = WorkflowEngine({ ready.copy(charging = false) }, { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) }, { _, _ -> }, { _, _ -> })
        val branch = step("condition", WorkflowStepKind.CONDITION).copy(conditions = AutomationCondition(charging = true), skipOnFalse = 1)
        engine.run(AutomationSpec(listOf(branch, step("skipped"), step("last").copy(repeats = 3))), "", "run")
        assertEquals(listOf("last", "last", "last"), calls)
    }

    @Test fun unknownFailureIsNeverRetriedButSafeFailureIsRetried() = runBlocking {
        for (safe in listOf(false, true)) {
            var calls = 0
            val engine = WorkflowEngine({ ready }, { _, _, _ -> calls++; AgentTaskExecutionResult.Failed("ERROR", "failure", safe) }, { _, _ -> }, { _, _ -> })
            val result = engine.run(AutomationSpec(listOf(step("one").copy(retries = 2))), "", "run")
            assertTrue(result is AgentTaskExecutionResult.Failed)
            assertEquals(if (safe) 3 else 1, calls)
        }
    }

    @Test fun continuedFailureReplacesEarlierSuccessForNextStep() = runBlocking {
        var lastInput = ""
        val engine = WorkflowEngine({ ready }, { s, data, _ ->
            when (s.id) {
                "one" -> AgentTaskExecutionResult.Succeeded("one", "old success")
                "two" -> AgentTaskExecutionResult.Failed("FAILED_WRITE", "没有保存")
                else -> { lastInput = data; AgentTaskExecutionResult.Succeeded("last") }
            }
        }, { _, _ -> }, { _, _ -> })
        engine.run(AutomationSpec(listOf(step("one"), step("two").copy(failure = WorkflowFailure.CONTINUE), step("last"))), "", "run")
        val previous = JSONObject(JSONObject(lastInput).getString("previous_step_result"))
        assertFalse(previous.getBoolean("ok")); assertEquals("FAILED_WRITE", previous.getString("code"))
    }

    @Test fun stepTimeoutStopsNextStepAndDoesNotReplay() = runBlocking {
        var calls = 0
        val engine = WorkflowEngine({ ready }, { _, _, _ -> calls++; delay(11000); AgentTaskExecutionResult.Succeeded("never") }, { _, _ -> }, { _, _ -> })
        val result = engine.run(AutomationSpec(listOf(step("one").copy(timeoutSeconds = 10, retries = 2), step("two"))), "", "run")
        assertEquals("STEP_TIMEOUT", (result as AgentTaskExecutionResult.Failed).code); assertEquals(1, calls)
    }

    @Test fun cancellationPropagatesWithoutStartingNextEffect() = runBlocking {
        var calls = 0
        val engine = WorkflowEngine({ ready }, { _, _, _ -> calls++; throw CancellationException("user canceled") }, { _, _ -> }, { _, _ -> })
        try { engine.run(AutomationSpec(listOf(step("one"), step("two"))), "", "run"); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, calls) }
    }

    @Test fun invalidBudgetsAndDuplicateIdsAreRejected() {
        for (spec in listOf(AutomationSpec(List(21) { step("$it") }), AutomationSpec(listOf(step("same"), step("same"))),
            AutomationSpec(listOf(step("one").copy(repeats = 6))), AutomationSpec(timeoutSeconds = 541))) {
            assertTrue(runCatching { spec.validate() }.isFailure)
        }
    }

    @Test fun conditionsRoundTripAndEmptyAnyConditionIsTrue() {
        val spec = AutomationSpec(listOf(step("skill", WorkflowStepKind.SKILL).copy(skillId = "example")),
            AutomationCondition(charging = false, network = true, minimumBattery = 40), allowedTools = listOf("read_file"), disallowedTools = listOf("terminal*"), projectId = "project-1")
        assertEquals(spec, AutomationSpec.parse(spec.json()))
        assertTrue(AutomationCondition(any = true).matches(ready))
        assertFalse(spec.condition.matches(ready))
        assertTrue(spec.condition.copy(any = true).matches(ready))
    }

    @Test fun automationToolSchemaPlacesAllArgumentsInsideProperties() {
        val function = AgentAutomationTools.schema().getJSONObject("function")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")
        for (field in listOf("action", "id", "title", "prompt", "workflow", "trigger", "schedule", "confirmed")) assertTrue(field, properties.has(field))
    }
}
