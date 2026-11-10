package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.GoalSpec
import io.github.zhh_lgtm.nexus.kernel.api.GoalState
import io.github.zhh_lgtm.nexus.kernel.api.NotepadRevision
import io.github.zhh_lgtm.nexus.kernel.api.PlanDocument

enum class WorkflowMode {
    CHAT,
    PLAN,
    GOAL,
}

enum class WorkflowActor {
    USER,
    AGENT,
    BACKGROUND,
}

data class WorkflowSnapshot(
    val scopeId: String,
    val mode: WorkflowMode = WorkflowMode.CHAT,
    val goal: GoalSpec? = null,
    val plan: PlanDocument? = null,
    val notepadRevision: NotepadRevision? = null,
    val updatedAt: Long = 0,
)

sealed interface GoalTransition {
    data object StartPlanning : GoalTransition
    data object StartRunning : GoalTransition
    data object StartVerification : GoalTransition
    data object PauseForBudget : GoalTransition
    data object WaitForUser : GoalTransition
    data object Resume : GoalTransition
    data object Complete : GoalTransition
    data object Fail : GoalTransition
    data object Cancel : GoalTransition
}

object WorkflowStateEngine {
    fun transition(goal: GoalSpec, transition: GoalTransition): GoalSpec {
        val next = when (transition) {
            GoalTransition.StartPlanning -> requireState(goal, GoalState.WAITING_PLAN_APPROVAL, GoalState.DRAFT)
            GoalTransition.StartRunning ->
                requireState(goal, GoalState.RUNNING, GoalState.WAITING_PLAN_APPROVAL, GoalState.BUDGET_PAUSED, GoalState.WAITING_USER)
            GoalTransition.StartVerification -> requireState(goal, GoalState.VERIFYING, GoalState.RUNNING)
            GoalTransition.PauseForBudget -> requireState(goal, GoalState.BUDGET_PAUSED, GoalState.RUNNING)
            GoalTransition.WaitForUser ->
                requireState(goal, GoalState.WAITING_USER, GoalState.RUNNING, GoalState.VERIFYING, GoalState.BUDGET_PAUSED)
            GoalTransition.Resume ->
                requireState(goal, GoalState.RUNNING, GoalState.WAITING_USER, GoalState.BUDGET_PAUSED)
            GoalTransition.Complete -> requireState(goal, GoalState.COMPLETED, GoalState.VERIFYING, GoalState.WAITING_USER)
            GoalTransition.Fail -> requireState(goal, GoalState.FAILED, GoalState.RUNNING, GoalState.VERIFYING, GoalState.WAITING_USER)
            GoalTransition.Cancel -> {
                if (goal.state in terminal) goal.state else GoalState.CANCELLED
            }
        }
        return goal.copy(state = next)
    }

    fun appendNotepadRevision(
        current: NotepadRevision?,
        content: String,
        author: WorkflowActor,
        now: Long,
    ): NotepadRevision {
        require(content.length <= MAX_NOTEPAD_CHARS) { "Notepad content is too large" }
        return NotepadRevision(
            revision = (current?.revision ?: 0) + 1,
            content = content,
            author = author.name.lowercase(),
            createdAt = now,
        )
    }

    private fun requireState(goal: GoalSpec, next: GoalState, vararg expected: GoalState): GoalState {
        require(goal.state in expected) {
            "Invalid goal transition: ${goal.state} -> $next"
        }
        return next
    }

    private val terminal = setOf(GoalState.COMPLETED, GoalState.FAILED, GoalState.CANCELLED)
    const val MAX_NOTEPAD_CHARS = 1_000_000
}
