package io.github.mangi.eta.fork.meeting

internal data class PlanStepResult(val content: String, val error: String? = null) {
    val succeeded get() = error == null && content.isNotBlank()
}

internal object MeetingPlanScheduler {
    fun validDependencies(pending: Map<String, List<String>>): Boolean {
        if (pending.values.any { dependencies -> dependencies.distinct().size != dependencies.size || dependencies.any { it !in pending } }) return false
        val done = mutableSetOf<String>()
        while (done.size < pending.size) {
            val ready = pending.filter { (name, dependencies) -> name !in done && dependencies.all { it in done } }.keys
            if (ready.isEmpty()) return false
            done.addAll(ready)
        }
        return true
    }
    fun ready(pending: Map<String, List<String>>, completed: Map<String, PlanStepResult>): List<String> =
        pending.filterValues { dependencies -> dependencies.all(completed::containsKey) }.keys.toList()

    fun inputs(dependencies: List<String>, completed: Map<String, PlanStepResult>): Map<String, String>? {
        val selected = dependencies.associateWith { completed[it] ?: return null }
        return if (selected.values.any { !it.succeeded }) null else selected.mapValues { it.value.content }
    }

    fun outcome(results: Collection<PlanStepResult>, hostSucceeded: Boolean): MeetingTurnOutcome = when {
        results.none { it.succeeded } -> MeetingTurnOutcome.HANDLED
        hostSucceeded && results.all { it.succeeded } -> MeetingTurnOutcome.SUCCEEDED
        else -> MeetingTurnOutcome.PARTIAL
    }
}
