package io.github.mangi.eta.agent.workflow

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.GoalTransition
import io.github.zhh_lgtm.nexus.application.api.WorkflowActor
import io.github.zhh_lgtm.nexus.application.api.WorkflowMode
import io.github.zhh_lgtm.nexus.application.api.WorkflowSnapshot
import io.github.zhh_lgtm.nexus.application.api.WorkflowStateEngine
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.GoalId
import io.github.zhh_lgtm.nexus.kernel.api.GoalSpec
import io.github.zhh_lgtm.nexus.kernel.api.GoalState
import io.github.zhh_lgtm.nexus.kernel.api.NotepadRevision
import io.github.zhh_lgtm.nexus.kernel.api.PlanDocument
import io.github.zhh_lgtm.nexus.kernel.api.PlanId
import io.github.zhh_lgtm.nexus.kernel.api.PlanStep
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.zhh_lgtm.nexus.kernel.api.SessionId
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Durable Goal, Plan and conversation Notepad state for one conversation/project scope. */
internal class GoalPlanNotepadStore private constructor(private val appContext: Context) {
    @Synchronized
    fun snapshot(scopeId: String): WorkflowSnapshot = decode(scopeId, read(scopeId))

    /** Persist the user's workflow mode without creating a goal or plan implicitly. */
    @Synchronized
    fun setMode(scopeId: String, mode: WorkflowMode): WorkflowSnapshot {
        val current = snapshot(scopeId)
        if (current.mode == mode) return current
        val updated = current.copy(mode = mode, updatedAt = System.currentTimeMillis())
        write(scopeId, updated)
        return updated
    }

    @Synchronized
    fun goalManage(scopeId: String, arguments: JSONObject): JSONObject = runCatching {
        val action = arguments.optString("action", "status")
        val current = snapshot(scopeId)
        when (action) {
            "status" -> stateJson(current).put("ok", true)
            "create" -> {
                val existingGoal = current.goal
                if (existingGoal != null && existingGoal.state !in terminalGoalStates) {
                    error("GOAL_ALREADY_ACTIVE")
                }
                val now = System.currentTimeMillis()
                val goal = GoalSpec(
                    goalId = GoalId("goal-${UUID.randomUUID()}"),
                    sessionId = SessionId(scopeId),
                    objective = arguments.optString("objective").trim().also {
                        require(it.isNotBlank()) { "objective 不能为空" }
                    },
                    constraints = arguments.optJSONArray("constraints").stringList(),
                    completionCriteria = arguments.optJSONArray("completion_criteria").stringList(),
                    state = GoalState.DRAFT,
                    budget = RunBudget(
                        softRounds = arguments.optInt("soft_rounds", 10).takeIf { it > 0 },
                        hardRounds = arguments.optInt("hard_rounds", 20).takeIf { it > 0 },
                        softDurationMs = arguments.optLong("soft_duration_ms", 15 * 60_000L).takeIf { it > 0 },
                        hardDurationMs = arguments.optLong("hard_duration_ms", 60 * 60_000L).takeIf { it > 0 },
                        softToolCalls = arguments.optInt("soft_tool_calls", 100).takeIf { it > 0 },
                        hardToolCalls = arguments.optInt("hard_tool_calls", 300).takeIf { it > 0 },
                    ),
                )
                write(scopeId, current.copy(mode = WorkflowMode.GOAL, goal = goal, updatedAt = now))
                stateJson(snapshot(scopeId)).put("ok", true)
            }
            "transition" -> {
                val goal = current.goal ?: error("GOAL_NOT_FOUND")
                require(goal.goalId.value == arguments.optString("goal_id")) { "goal_id 不匹配" }
                val transition = parseTransition(arguments.optString("transition"))
                if (transition == GoalTransition.Complete) {
                    require(arguments.optBoolean("confirmed")) { "目标完成需要用户确认" }
                }
                val next = WorkflowStateEngine.transition(goal, transition)
                write(scopeId, current.copy(goal = next, updatedAt = System.currentTimeMillis()))
                stateJson(snapshot(scopeId)).put("ok", true)
            }
            else -> error("UNKNOWN_GOAL_ACTION")
        }
    }.getOrElse { failure -> failureJson(failure) }

    @Synchronized
    fun planManage(scopeId: String, arguments: JSONObject): JSONObject = runCatching {
        val action = arguments.optString("action", "status")
        val current = snapshot(scopeId)
        when (action) {
            "status" -> stateJson(current).put("ok", true)
            "replace" -> {
                val goalId = arguments.optString("goal_id").takeIf(String::isNotBlank)?.let(::GoalId)
                val planId = arguments.optString("plan_id").takeIf(String::isNotBlank)?.let(::PlanId)
                    ?: PlanId("plan-${UUID.randomUUID()}")
                require(arguments.has("expected_revision")) { "expected_revision 不能为空" }
                val expected = arguments.getLong("expected_revision")
                require((current.plan?.revision ?: 0L) == expected) { "PLAN_REVISION_CONFLICT" }
                val steps = parsePlanSteps(arguments.getJSONArray("steps"))
                val plan = PlanDocument(
                    planId = planId,
                    goalId = goalId ?: current.goal?.goalId,
                    revision = (current.plan?.revision ?: 0L) + 1,
                    steps = steps,
                )
                val goal = current.goal?.copy(planId = plan.planId)
                write(scopeId, current.copy(mode = WorkflowMode.PLAN, goal = goal, plan = plan, updatedAt = System.currentTimeMillis()))
                stateJson(snapshot(scopeId)).put("ok", true)
            }
            else -> error("UNKNOWN_PLAN_ACTION")
        }
    }.getOrElse { failure -> failureJson(failure) }

    @Synchronized
    fun notepadManage(scopeId: String, arguments: JSONObject): JSONObject = runCatching {
        val action = arguments.optString("action", "read")
        val current = snapshot(scopeId)
        when (action) {
            "read" -> notepadJson(current.notepadRevision).put("ok", true)
            "replace", "append" -> {
                val expected = arguments.optLong("expected_revision", -1L)
                require(expected >= 0) { "expected_revision 不能为空" }
                require((current.notepadRevision?.revision ?: 0L) == expected) { "NOTEPAD_REVISION_CONFLICT" }
                val value = arguments.optString("content", "")
                val content = if (action == "append") {
                    listOfNotNull(current.notepadRevision?.content?.takeIf(String::isNotBlank), value.takeIf(String::isNotBlank))
                        .joinToString("\n\n")
                } else {
                    value
                }
                val revision = WorkflowStateEngine.appendNotepadRevision(
                    current.notepadRevision,
                    content,
                    WorkflowActor.AGENT,
                    System.currentTimeMillis(),
                )
                write(scopeId, current.copy(notepadRevision = revision, updatedAt = revision.createdAt))
                notepadJson(revision).put("ok", true)
            }
            else -> error("UNKNOWN_NOTEPAD_ACTION")
        }
    }.getOrElse { failure -> failureJson(failure) }

    private fun read(scopeId: String): JSONObject {
        val file = file(scopeId)
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrDefault(JSONObject())
    }

    private fun write(scopeId: String, snapshot: WorkflowSnapshot) {
        val target = file(scopeId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp-${UUID.randomUUID()}")
        try {
            temporary.writeText(encode(snapshot).toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
    }

    private fun encode(snapshot: WorkflowSnapshot): JSONObject = JSONObject()
        .put("version", 1)
        .put("scope_id", snapshot.scopeId)
        .put("mode", snapshot.mode.name)
        .put("updated_at", snapshot.updatedAt)
        .put("goal", snapshot.goal?.let(::goalJson) ?: JSONObject.NULL)
        .put("plan", snapshot.plan?.let(::planJson) ?: JSONObject.NULL)
        .put("notepad", snapshot.notepadRevision?.let(::notepadJson) ?: JSONObject.NULL)

    private fun decode(scopeId: String, root: JSONObject): WorkflowSnapshot = WorkflowSnapshot(
        scopeId = scopeId,
        mode = runCatching { WorkflowMode.valueOf(root.optString("mode")) }.getOrDefault(WorkflowMode.CHAT),
        goal = root.optJSONObject("goal")?.let(::parseGoal),
        plan = root.optJSONObject("plan")?.let(::parsePlan),
        notepadRevision = root.optJSONObject("notepad")?.let(::parseNotepad),
        updatedAt = root.optLong("updated_at"),
    )

    private fun goalJson(goal: GoalSpec): JSONObject = JSONObject()
        .put("goal_id", goal.goalId.value)
        .put("session_id", goal.sessionId.value)
        .put("project_id", goal.projectId)
        .put("objective", goal.objective)
        .put("constraints", JSONArray(goal.constraints))
        .put("completion_criteria", JSONArray(goal.completionCriteria))
        .put("plan_id", goal.planId?.value)
        .put("state", goal.state.name)
        .put("budget", JSONObject()
            .put("soft_rounds", goal.budget.softRounds)
            .put("hard_rounds", goal.budget.hardRounds)
            .put("soft_duration_ms", goal.budget.softDurationMs)
            .put("hard_duration_ms", goal.budget.hardDurationMs)
            .put("soft_tool_calls", goal.budget.softToolCalls)
            .put("hard_tool_calls", goal.budget.hardToolCalls)
            .put("cost_limit_micros", goal.budget.costLimitMicros))

    private fun parseGoal(json: JSONObject): GoalSpec = GoalSpec(
        goalId = GoalId(json.getString("goal_id")),
        sessionId = SessionId(json.getString("session_id")),
        projectId = json.optString("project_id").takeIf(String::isNotBlank),
        objective = json.optString("objective"),
        constraints = json.optJSONArray("constraints").stringList(),
        completionCriteria = json.optJSONArray("completion_criteria").stringList(),
        planId = json.optString("plan_id").takeIf(String::isNotBlank)?.let(::PlanId),
        state = GoalState.valueOf(json.optString("state", GoalState.DRAFT.name)),
        budget = json.optJSONObject("budget")?.let { budget ->
            RunBudget(
                budget.optNullableInt("soft_rounds"),
                budget.optNullableInt("hard_rounds"),
                budget.optNullableLong("soft_duration_ms"),
                budget.optNullableLong("hard_duration_ms"),
                budget.optNullableInt("soft_tool_calls"),
                budget.optNullableInt("hard_tool_calls"),
                budget.optNullableLong("cost_limit_micros"),
            )
        } ?: RunBudget(),
    )

    private fun planJson(plan: PlanDocument): JSONObject = JSONObject()
        .put("plan_id", plan.planId.value)
        .put("goal_id", plan.goalId?.value)
        .put("revision", plan.revision)
        .put("steps", JSONArray().also { array ->
            plan.steps.forEach { step ->
                array.put(JSONObject()
                    .put("id", step.id)
                    .put("title", step.title)
                    .put("description", step.description)
                    .put("depends_on", JSONArray(step.dependsOn.toList()))
                    .put("required_capabilities", JSONArray(step.requiredCapabilities.toList()))
                    .put("effect", step.effectClass.name)
                    .put("estimated_cost", step.estimatedCost)
                    .put("verification", step.verification)
                    .put("reversible", step.reversible))
            }
        })

    private fun parsePlan(json: JSONObject): PlanDocument = PlanDocument(
        planId = PlanId(json.getString("plan_id")),
        goalId = json.optString("goal_id").takeIf(String::isNotBlank)?.let(::GoalId),
        revision = json.optLong("revision"),
        steps = parsePlanSteps(json.getJSONArray("steps")),
    )

    private fun parsePlanSteps(array: JSONArray): List<PlanStep> = buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(PlanStep(
                id = item.getString("id"),
                title = item.getString("title"),
                description = item.optString("description"),
                dependsOn = item.optJSONArray("depends_on").stringList().toSet(),
                requiredCapabilities = item.optJSONArray("required_capabilities").stringList().toSet(),
                effectClass = runCatching { EffectClass.valueOf(item.optString("effect", EffectClass.READ.name)) }
                    .getOrDefault(EffectClass.HIGH_RISK),
                estimatedCost = item.optString("estimated_cost").takeIf(String::isNotBlank),
                verification = item.optString("verification"),
                reversible = item.optBoolean("reversible", false),
            ))
        }
    }

    private fun notepadJson(revision: NotepadRevision?): JSONObject = if (revision == null) JSONObject()
        .put("revision", 0).put("content", "").put("author", "").put("created_at", 0L)
    else JSONObject()
        .put("revision", revision.revision)
        .put("content", revision.content)
        .put("author", revision.author)
        .put("created_at", revision.createdAt)

    private fun parseNotepad(json: JSONObject): NotepadRevision = NotepadRevision(
        revision = json.optLong("revision"),
        content = json.optString("content"),
        author = json.optString("author"),
        createdAt = json.optLong("created_at"),
    )

    private fun stateJson(snapshot: WorkflowSnapshot): JSONObject = JSONObject()
        .put("scope_id", snapshot.scopeId)
        .put("mode", snapshot.mode.name)
        .put("goal", snapshot.goal?.let(::goalJson) ?: JSONObject.NULL)
        .put("plan", snapshot.plan?.let(::planJson) ?: JSONObject.NULL)
        .put("notepad", notepadJson(snapshot.notepadRevision))
        .put("updated_at", snapshot.updatedAt)

    private fun parseTransition(value: String): GoalTransition = when (value) {
        "start_planning" -> GoalTransition.StartPlanning
        "start_running" -> GoalTransition.StartRunning
        "start_verification" -> GoalTransition.StartVerification
        "pause_for_budget" -> GoalTransition.PauseForBudget
        "wait_for_user" -> GoalTransition.WaitForUser
        "resume" -> GoalTransition.Resume
        "complete" -> GoalTransition.Complete
        "fail" -> GoalTransition.Fail
        "cancel" -> GoalTransition.Cancel
        else -> error("UNKNOWN_GOAL_TRANSITION")
    }

    private fun failureJson(failure: Throwable): JSONObject = JSONObject()
        .put("ok", false)
        .put("code", (failure.message ?: "WORKFLOW_ERROR").substringBefore(':'))
        .put("message", failure.message ?: failure.javaClass.simpleName)

    private fun file(scopeId: String): File {
        require(scopeId.matches(Regex("[A-Za-z0-9._:-]{1,256}"))) { "Invalid workflow scope" }
        val directory = File(appContext.filesDir, "workflow-state")
        val digest = MessageDigest.getInstance("SHA-256").digest(scopeId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    private fun JSONArray?.stringList(): List<String> = buildList {
        if (this@stringList == null) return@buildList
        for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    companion object {
        private val terminalGoalStates = setOf(GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED)

        @Volatile private var instance: GoalPlanNotepadStore? = null

        fun get(context: Context): GoalPlanNotepadStore = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.appContext === app } ?: GoalPlanNotepadStore(app).also { instance = it }
        }
    }
}
