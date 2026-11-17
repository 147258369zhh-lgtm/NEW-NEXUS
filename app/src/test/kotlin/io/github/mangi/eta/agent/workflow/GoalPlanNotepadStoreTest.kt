package io.github.mangi.eta.agent.workflow

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.NexusExecutionRecorder
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.zhh_lgtm.nexus.kernel.api.SessionId
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GoalPlanNotepadStoreTest {
    @Test fun zeroOrNegativeBudgetsCannotRemoveExecutionLimits() {
        val store = GoalPlanNotepadStore.get(context)
        for (name in listOf("hard_rounds", "soft_rounds", "hard_tool_calls", "hard_duration_ms")) {
            assertTrue(!store.goalManage(scope, JSONObject().put("action", "create").put("objective", "fixture").put(name, 0)).getBoolean("ok"))
            assertTrue(store.executionSnapshot(scope).goal == null)
        }
        val created = store.goalManage(scope, JSONObject().put("action", "create").put("objective", "fixture").put("hard_rounds", 2))
        assertTrue(created.toString(), created.getBoolean("ok"))
        assertEquals(2, store.executionSnapshot(scope).goal?.budget?.softRounds)
    }

    @Test fun crossGoalPlansAndCyclesCannotReplaceTheCurrentRevision() {
        val store = GoalPlanNotepadStore.get(context)
        val step = JSONObject().put("id", "a").put("title", "fixture").put("verification", "inspect").put("reversible", true)
        val crossGoal = store.planManage(scope, JSONObject().put("action", "replace").put("expected_revision", 0)
            .put("goal_id", "unrelated-goal").put("steps", JSONArray().put(step)))
        assertTrue(!crossGoal.getBoolean("ok"))
        val cycle = store.planManage(scope, JSONObject().put("action", "replace").put("expected_revision", 0)
            .put("steps", JSONArray().put(step.put("depends_on", JSONArray().put("a")))))
        assertTrue(!cycle.getBoolean("ok"))
        assertTrue(store.executionSnapshot(scope).plan == null)
    }

    private lateinit var context: Context
    private val scope = "conversation-workflow-test"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "workflow-state").deleteRecursively()
    }

    @After fun tearDown() {
        File(context.filesDir, "workflow-state").deleteRecursively()
    }

    @Test fun goalPlanAndNotepadPersistWithOptimisticRevisions() {
        val store = GoalPlanNotepadStore.get(context)
        val created = store.goalManage(scope, JSONObject()
            .put("action", "create")
            .put("objective", "Ship workflow support"))
        assertTrue(created.getBoolean("ok"))
        val goalId = created.getJSONObject("goal").getString("goal_id")
        assertTrue(store.goalManage(scope, JSONObject()
            .put("action", "transition")
            .put("goal_id", goalId)
            .put("transition", "start_planning")).getBoolean("ok"))

        val plan = store.planManage(scope, JSONObject()
            .put("action", "replace")
            .put("expected_revision", 0)
            .put("steps", JSONArray().put(JSONObject()
                .put("id", "step-1")
                .put("title", "Implement")
                .put("verification", "Compile and test")
                .put("reversible", true))))
        assertTrue(plan.toString(), plan.getBoolean("ok"))
        assertEquals(1, plan.getJSONObject("plan").getLong("revision"))

        assertTrue(store.notepadManage(scope, JSONObject()
            .put("action", "append")
            .put("expected_revision", 0)
            .put("content", "first note")).getBoolean("ok"))
        assertTrue(store.notepadManage(scope, JSONObject()
            .put("action", "append")
            .put("expected_revision", 1)
            .put("content", "second note")).getBoolean("ok"))

        val restored = GoalPlanNotepadStore.get(context).snapshot(scope)
        assertEquals(1L, restored.plan?.revision)
        assertEquals(2L, restored.notepadRevision?.revision)
        assertEquals("first note\n\nsecond note", restored.notepadRevision?.content)
    }

    @Test fun completionRecordRequiresEvidenceAndKeepsNoncriticalOmissions() {
        val store = GoalPlanNotepadStore.get(context)
        val created = store.goalManage(scope, JSONObject().put("action", "create").put("objective", "Publish report"))
        val goalId = created.getJSONObject("goal").getString("goal_id")
        val runningGoal = store.executionSnapshot(scope).goal!!.copy(state = io.github.zhh_lgtm.nexus.kernel.api.GoalState.VERIFYING)
        val blocked = store.goalManage(scope, JSONObject().put("action", "transition")
            .put("goal_id", goalId).put("transition", "complete").put("state_hash", "fixture"), null)
        assertEquals("GOAL_COMPLETION_NOTE_REQUIRED", blocked.getString("code"))
        assertTrue(runningGoal.completionNote == null)
        val note = io.github.zhh_lgtm.nexus.kernel.api.GoalCompletionNote(
            coreCriteriaVerified = true,
            missingNoncriticalItems = listOf("optional appendix"),
            impact = "Core report is usable",
            evidence = listOf("validated report file"),
        )
        assertEquals(listOf("optional appendix"), note.missingNoncriticalItems)
        assertTrue(note.coreCriteriaVerified)
    }
}
