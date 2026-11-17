package io.github.zhh_lgtm.nexus.kernel.api

enum class GoalState {
    DRAFT,
    WAITING_PLAN_APPROVAL,
    RUNNING,
    PAUSED,
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
    val completionNote: GoalCompletionNote? = null,
    val planId: PlanId? = null,
    val revision: Long = 0,
    val state: GoalState = GoalState.DRAFT,
    val budget: RunBudget = RunBudget(),
) {
    init {
        require(objective.isNotBlank()) { "Goal objective must not be blank" }
        require(revision >= 0) { "Goal revision must be non-negative" }
    }
}

data class GoalCompletionNote(
    val coreCriteriaVerified: Boolean,
    val missingNoncriticalItems: List<String> = emptyList(),
    val impact: String = "",
    val evidence: List<String> = emptyList(),
) {
    init {
        require(coreCriteriaVerified) { "Goal completion requires verified core criteria" }
        require(missingNoncriticalItems.size <= 100) { "Too many missing goal items" }
        require(missingNoncriticalItems.all { it.isNotBlank() && it.length <= 1_000 }) {
            "Goal completion items must be bounded and non-blank"
        }
        require(impact.length <= 4_000 && evidence.size <= 100 && evidence.all { it.isNotBlank() && it.length <= 2_000 }) {
            "Goal completion evidence is invalid"
        }
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
        val remaining = steps.associate { it.id to it.dependsOn.size }.toMutableMap()
        val dependents = mutableMapOf<String, MutableList<String>>()
        steps.forEach { step -> step.dependsOn.forEach { dependency -> dependents.getOrPut(dependency) { mutableListOf() }.add(step.id) } }
        val ready = ArrayDeque(remaining.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            dependents[id].orEmpty().forEach { dependent ->
                val count = remaining.getValue(dependent) - 1
                remaining[dependent] = count
                if (count == 0) ready.addLast(dependent)
            }
        }
        require(visited == steps.size) { "Plan dependencies must not contain cycles" }
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
