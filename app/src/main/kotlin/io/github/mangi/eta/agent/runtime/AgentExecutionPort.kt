package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * Single entry point for foreground Agent execution. Conversation and meeting callers may
 * shape requests differently, but model, tools, memory, skills and compaction execute through
 * the same cross-process runtime.
 */
internal object AgentExecutionPort {
    fun run(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult =
        AgentRuntimeClient(context.applicationContext, AndroidAgentLogger).run(request, onEvent)
}
