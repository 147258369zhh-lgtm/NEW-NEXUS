package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.runtime.AgentEvent

/** A stopped/network-failed task is not evidence that a skill's steps are defective. */
internal object SkillRunQuality {
    fun outcome(completed: Boolean, interrupted: Boolean, events: List<AgentEvent>): Boolean? = when {
        interrupted -> null
        completed -> true
        events.filterIsInstance<AgentEvent.ToolFinished>().any { it.success == false } -> false
        else -> null
    }
}
