package io.github.mangi.eta.agent.workflow

import android.content.Context
import java.io.File
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
}
