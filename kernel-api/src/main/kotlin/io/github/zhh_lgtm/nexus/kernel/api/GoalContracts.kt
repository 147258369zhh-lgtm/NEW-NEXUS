package io.github.zhh_lgtm.nexus.kernel.api

enum class GoalState {
    DRAFT,
    WAITING_PLAN_APPROVAL,
    RUNNING,
    VERIFYING,
    WAITING_USER,
    BUDGET_PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class GoalSpec(
    val goalId: GoalId,
    val sessionId: SessionId,
    val projectId: String? = null,
    val objective: String,
    val constraints: List<String> = emptyList(),
    val completionCriteria: List<String> = emptyList(),
    val planId: PlanId? = null,
    val state: GoalState = GoalState.DRAFT,
    val budget: RunBudget = RunBudget(),
) {
    init {
        require(objective.isNotBlank()) { "Goal objective must not be blank" }
    }
}

data class PlanStep(
    val id: String,
    val title: String,
    val description: String,
    val dependsOn: Set<String> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val effectClass: EffectClass = EffectClass.READ,
    val estimatedCost: String? = null,
    val verification: String,
    val reversible: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Plan step id must not be blank" }
        require(title.isNotBlank()) { "Plan step title must not be blank" }
        require(verification.isNotBlank()) { "Plan verification must not be blank" }
        require(reversible || effectClass == EffectClass.READ || effectClass == EffectClass.HIGH_RISK) {
            "Irreversible plan steps must be read-only or high-risk"
        }
    }
}

data class PlanDocument(
    val planId: PlanId,
    val goalId: GoalId?,
    val revision: Long,
    val steps: List<PlanStep>,
) {
    init {
        require(revision >= 0) { "revision must be non-negative" }
        require(steps.map(PlanStep::id).toSet().size == steps.size) { "Plan step ids must be unique" }
        val ids = steps.map(PlanStep::id).toSet()
        require(steps.all { step -> step.dependsOn.all(ids::contains) }) { "Plan dependencies must reference existing steps" }
    }
}

data class NotepadRevision(
    val revision: Long,
    val content: String,
    val author: String,
    val createdAt: Long,
) {
    init {
        require(revision > 0) { "Notepad revision must be positive" }
        require(author.isNotBlank()) { "Notepad author must not be blank" }
        require(createdAt >= 0) { "Notepad creation time must be non-negative" }
    }
}
