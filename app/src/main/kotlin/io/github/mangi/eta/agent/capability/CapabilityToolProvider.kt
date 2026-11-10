package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.agent.model.AgentModelClient

/** A bounded capability provider that can be routed by the Agent tool executor. */
internal interface CapabilityToolProvider : AutoCloseable {
    fun contains(toolName: String): Boolean

    fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult
}
