package io.github.mangi.eta.fork.tasks

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkflowEngineTest {
    private val ready = AutomationState(true, true, true, 80)
    private fun step(id: String, kind: WorkflowStepKind = WorkflowStepKind.AGENT) = WorkflowStep(id, kind, id, prompt = "整理输入内容")

    @Test fun settledAndWaitingEventsRetainTheirOriginalStepTitle() = runBlocking {
        val events = mutableListOf<Pair<String, JSONObject>>()
        var now = 1_000L
        var checkpoint = WorkflowCheckpoint()
        val engine = WorkflowEngine({ ready }, { _, _, _ -> AgentTaskExecutionResult.Succeeded("done") },
            { _, _ -> }, { type, payload -> events += type to JSONObject(payload.toString()) },
            clock = { now }, persistCheckpoint = { checkpoint = it })
        val spec = AutomationSpec(listOf(step("work").copy(title = "原始名称"),
            step("wait", WorkflowStepKind.WAIT).copy(title = "等待名称", waitSeconds = 1)))
        assertTrue(engine.run(spec, "", "run") is AgentTaskExecutionResult.Deferred)
        now = 2_001L
        assertTrue(engine.run(spec, "", "run", checkpoint) is AgentTaskExecutionResult.Succeeded)
        val titles = events.filter { it.first in setOf("STEP_STARTED", "STEP_WAIT_SCHEDULED", "STEP_FINISHED") }
            .map { it.second.getString("title") }
        assertEquals(listOf("原始名称", "原始名称", "等待名称", "等待名称"), titles)
    }

    @Test fun acknowledgementsFollowCommittedCheckpointAndStorageFailureRetainsResult() = runBlocking {
        var committed = WorkflowCheckpoint()
        val acknowledgements = mutableListOf<String>()
        val engine = WorkflowEngine({ ready }, { s, _, _ -> AgentTaskExecutionResult.Succeeded(s.id, "complete", s.id) },
            { _, _ -> }, { _, _ -> }, persistCheckpoint = { committed = it }, acknowledge = {
                assertFalse(committed.stepStarted)
                assertTrue(committed.nextStepIndex > 0)
                acknowledgements += (it as AgentTaskExecutionResult.Succeeded).resultRef
            })
        assertTrue(engine.run(AutomationSpec(listOf(step("one"))), "", "success") is AgentTaskExecutionResult.Succeeded)
        assertEquals(listOf("one"), acknowledgements)
        val failing = WorkflowEngine({ ready }, { _, _, _ -> AgentTaskExecutionResult.Succeeded("unsaved", "complete", "unsaved") },
            { _, _ -> }, { _, _ -> }, persistCheckpoint = {
                if (it.nextStepIndex > 0) error("fixture checkpoint write failure")
            }, acknowledge = { fail("Uncommitted result must stay pending") })
        try { failing.run(AutomationSpec(listOf(step("one"))), "", "failure"); fail("Storage failure swallowed") }
        catch (failure: IllegalStateException) { assertEquals("fixture checkpoint write failure", failure.message) }
    }

    @Test fun outerTimeoutPropagatesWithoutSettlingOrAcknowledgingStep() = runBlocking {
        var finished = false
        var stopped = false
        val engine = WorkflowEngine({ ready }, { _, _, _ -> try { awaitCancellation() } finally { stopped = true } },
            { _, _ -> }, { type, _ -> if (type == "STEP_FINISHED") finished = true },
            acknowledge = { fail("Canceled step must not be acknowledged") })
        try { withTimeout(100) { engine.run(AutomationSpec(listOf(step("one"))), "", "canceled") }; fail("Outer timeout swallowed") }
        catch (_: TimeoutCancellationException) { }
        assertTrue(stopped)
        assertFalse(finished)
    }

    @Test fun workflowDeadlineWinsOverLongerStepDeadline() = runBlocking {
        val engine = WorkflowEngine({ ready }, { _, _, _ -> awaitCancellation() }, { _, _ -> }, { _, _ -> }, clock = { 9_900 })
        val result = engine.run(AutomationSpec(listOf(step("one")), timeoutSeconds = 10), "", "deadline",
            WorkflowCheckpoint(workflowStartedAt = 1))
        assertEquals("WORKFLOW_TIMEOUT", (result as AgentTaskExecutionResult.Failed).code)
    }

    @Test fun outputFlowsToNextStepAndNotification() = runBlocking {
        val inputs = mutableListOf<String>(); val notifications = mutableListOf<String>()
        val engine = WorkflowEngine({ ready }, { s, input, _ -> inputs += input; AgentTaskExecutionResult.Succeeded(s.id, when (s.id) { "echo" -> "{{zzz}}"; else -> "result:${s.id}" }) },
            { _, text -> notifications += text }, { _, _ -> })
        val result = engine.run(AutomationSpec(listOf(step("one"), step("two"), step("echo").copy(prompt = "使用 {{previous}}"), step("notify", WorkflowStepKind.NOTIFY).copy(prompt = ""))), "目标", "run")
        assertTrue(result is AgentTaskExecutionResult.Succeeded)
        assertEquals("result:one", JSONObject(inputs[1]).getString("previous_step_result"))
        assertEquals("result:two", JSONObject(inputs[2]).getString("previous_step_result"))
        assertEquals(listOf("{{zzz}}"), notifications)
    }

    @Test fun falseBranchSkipsConfiguredStepsAndRepeatsAreBounded() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = WorkflowEngine({ ready.copy(charging = false) }, { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) }, { _, _ -> }, { _, _ -> })
        val branch = step("condition", WorkflowStepKind.CONDITION).copy(conditions = AutomationCondition(charging = true), skipOnFalse = 1)
        engine.run(AutomationSpec(listOf(branch, step("skipped"), step("last").copy(repeats = 3))), "", "run")
        assertEquals(listOf("last", "last", "last"), calls)
    }

    @Test fun repeatsDoNotReplayPreviousStepAfterAdvancing() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = WorkflowEngine(
            { ready },
            { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) },
            { _, _ -> },
            { _, _ -> },
        )
        val result = engine.run(
            AutomationSpec(listOf(step("one").copy(repeats = 2), step("two").copy(repeats = 2), step("three"))),
            "",
            "run",
        )
        assertTrue(result is AgentTaskExecutionResult.Succeeded)
        assertEquals(listOf("one", "one", "two", "two", "three"), calls)
    }

    @Test fun loopModesStopBeforeCapAndResumeUntilWithoutRecheckingSettledIteration() = runBlocking {
        var calls = mutableListOf<String>()
        var state = ready.copy(charging = false)
        var until = false
        suspend fun runSpec(spec: AutomationSpec): AgentTaskExecutionResult =
            WorkflowEngine(
                { state },
                { s, _, _ -> calls += s.id; if (until) state = state.copy(charging = true); AgentTaskExecutionResult.Succeeded(s.id) },
                { _, _ -> },
                { _, _ -> },
            ).run(spec, "", "run")
        val whileSpec = AutomationSpec(listOf(step("while").copy(
            repeats = 5,
            loopMode = WorkflowLoopMode.WHILE,
            conditions = AutomationCondition(charging = true),
        )))
        assertTrue(runSpec(whileSpec) is AgentTaskExecutionResult.Succeeded)
        assertEquals(emptyList<String>(), calls)

        calls = mutableListOf()
        until = true
        val untilSpec = AutomationSpec(listOf(step("until").copy(
            repeats = 5,
            loopMode = WorkflowLoopMode.UNTIL,
            conditions = AutomationCondition(charging = true),
        ), step("after")))
        assertTrue(runSpec(untilSpec) is AgentTaskExecutionResult.Succeeded)
        assertEquals(listOf("until", "after"), calls)
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

    @Test fun resumesAtCommittedCursorWithoutReplayingPreviousStep() = runBlocking {
        val calls = mutableListOf<String>()
        val engine = WorkflowEngine(
            { ready },
            { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id, "result:" + s.id) },
            { _, _ -> },
            { _, _ -> },
            clock = { 10_000 },
        )
        val checkpoint = WorkflowCheckpoint(
            nextStepIndex = 1,
            previous = "confirmed output",
            variables = mapOf("one" to "confirmed output"),
            workflowStartedAt = 10_000,
        )
        val result = engine.run(
            AutomationSpec(listOf(step("one"), step("two"))),
            "input",
            "run",
            checkpoint,
        )

        assertTrue(result is AgentTaskExecutionResult.Succeeded)
        assertEquals(listOf("two"), calls)
    }

    @Test fun startedCursorIsRejectedBeforeAnyEffect() = runBlocking {
        var calls = 0
        val engine = WorkflowEngine(
            { ready },
            { _, _, _ -> calls++; AgentTaskExecutionResult.Succeeded("never") },
            { _, _ -> },
            { _, _ -> },
        )
        val result = engine.run(
            AutomationSpec(listOf(step("one"), step("two"))),
            "",
            "run",
            WorkflowCheckpoint(stepStarted = true, workflowStartedAt = 1_000),
        )
        assertEquals("WORKFLOW_STEP_UNCONFIRMED", (result as AgentTaskExecutionResult.Failed).code)
        assertEquals(0, calls)
    }

    @Test fun durableWaitReturnsBeforeDeadlineAndResumesFromPersistedDeadline() = runBlocking {
        var now = 1_000L
        var persisted = WorkflowCheckpoint()
        val fingerprint = "definition-fingerprint"
        val calls = mutableListOf<String>()
        val waitingSpec = AutomationSpec(
            listOf(step("wait", WorkflowStepKind.WAIT).copy(waitSeconds = 30, timeoutSeconds = 10), step("after")),
            timeoutSeconds = 60,
        )
        val first = WorkflowEngine(
            { ready },
            { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) },
            { _, _ -> },
            { _, _ -> },
            persistCheckpoint = { persisted = it },
            clock = { now },
            ).run(waitingSpec, "", "run", WorkflowCheckpoint(definitionFingerprint = fingerprint))

        assertTrue(first is AgentTaskExecutionResult.Deferred)
        assertEquals(31_000L, (first as AgentTaskExecutionResult.Deferred).wakeAtEpochMillis)
        assertEquals(31_000L, persisted.waitUntil)
        assertEquals(fingerprint, persisted.definitionFingerprint)
        assertEquals(emptyList<String>(), calls)

        now = 31_000L
        val second = WorkflowEngine(
            { ready },
            { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) },
            { _, _ -> },
            { _, _ -> },
            persistCheckpoint = { persisted = it },
            clock = { now },
        ).run(waitingSpec, "", "run", persisted)

        assertTrue(second is AgentTaskExecutionResult.Succeeded)
        assertEquals(listOf("after"), calls)
        assertFalse(persisted.stepStarted)
        assertEquals(2, persisted.nextStepIndex)
        assertEquals(fingerprint, persisted.definitionFingerprint)
    }

    @Test fun invalidBudgetsAndDuplicateIdsAreRejected() {
        for (spec in listOf(AutomationSpec(List(21) { step("$it") }), AutomationSpec(listOf(step("same"), step("same"))),
            AutomationSpec(listOf(step("one").copy(repeats = 6))), AutomationSpec(timeoutSeconds = 541),
            AutomationSpec(listOf(step("branch", WorkflowStepKind.CONDITION).copy(loopMode = WorkflowLoopMode.WHILE))))) {
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

    @Test fun compoundConditionsPersistAndEvaluateAndOrWithReasons() {
        val compound = AutomationCondition(any = true, groups = listOf(
            AutomationCondition(charging = true, minimumBattery = 80),
            AutomationCondition(network = true, unlocked = true),
        ))
        val spec = AutomationSpec(listOf(step("one")), condition = compound)
        assertEquals(spec, AutomationSpec.parse(spec.json()))
        val idle = AutomationState(false, false, false, 95)
        assertFalse(compound.matches(idle))
        assertTrue(compound.mismatch(idle)!!.contains("充电状态"))
        assertTrue(compound.matches(AutomationState(false, true, true, 20)))
        assertTrue(compound.matches(AutomationState(true, false, false, 90)))
        val either = AutomationCondition(any = true, groups = listOf(
            AutomationCondition(charging = true), AutomationCondition(headset = true)))
        assertTrue(either.matches(AutomationState(false, false, false, 20, headset = true)))
        assertFalse(either.matches(AutomationState(false, false, false, 20)))
        assertTrue(either.mismatch(AutomationState(false, false, false, 20))!!.contains("任一条件均未满足"))
        assertFalse(AutomationCondition(minimumBattery = 50, maximumBattery = 80).matches(AutomationState(true, true, true, 90)))
    }

    @Test fun unrecognizedOrUnboundedConditionsFailClosed() {
        assertTrue(runCatching { AutomationCondition.parse(JSONObject().put("chargin", true)) }.isFailure)
        assertTrue(runCatching { AutomationCondition.parse(JSONObject().put("minimumBattery", 120)) }.isFailure)
        assertTrue(runCatching { AutomationCondition(minimumBattery = 80, maximumBattery = 40).validate() }.isFailure)
        var deep = AutomationCondition(charging = true)
        repeat(4) { deep = AutomationCondition(groups = listOf(deep)) }
        assertTrue(runCatching { deep.validate() }.isFailure)
        assertTrue(runCatching { AutomationSpec(condition = AutomationCondition(groups = List(17) { AutomationCondition() })).validate() }.isFailure)
    }

    @Test fun conditionStepUsesCompoundConditionAndJournalsSkipReason() = runBlocking {
        val calls = mutableListOf<String>()
        val events = mutableListOf<JSONObject>()
        val engine = WorkflowEngine({ ready.copy(charging = false, network = false) },
            { s, _, _ -> calls += s.id; AgentTaskExecutionResult.Succeeded(s.id) }, { _, _ -> }, { _, payload -> events += payload })
        val branch = step("branch", WorkflowStepKind.CONDITION).copy(conditions = AutomationCondition(groups = listOf(
            AutomationCondition(charging = true), AutomationCondition(network = true))), skipOnFalse = 1)
        engine.run(AutomationSpec(listOf(branch, step("skipped"), step("last"))), "", "run")
        assertEquals(listOf("last"), calls)
        val event = events.single { it.getString("step") == "branch" }
        assertFalse(event.getBoolean("matched"))
        assertTrue(event.getString("reason").isNotBlank())
    }

    @Test fun automationToolSchemaPlacesAllArgumentsInsideProperties() {
        val function = AgentAutomationTools.schema().getJSONObject("function")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")
        for (field in listOf("action", "id", "title", "prompt", "workflow", "trigger", "schedule", "confirmed")) assertTrue(field, properties.has(field))
    }
}
