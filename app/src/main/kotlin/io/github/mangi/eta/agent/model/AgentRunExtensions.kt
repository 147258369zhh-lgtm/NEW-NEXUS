package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.runtime.AgentEvent

internal data class AgentExtensionCompletion(val chunks: List<String>) {
    val text: String get() = chunks.joinToString("")
}

/** Application-owned extension hooks; provider adapters and the loop remain protocol owners. */
internal interface AgentRunExtensions : AutoCloseable {
    fun input(prompt: String, attachmentCount: Int = 0): String = prompt
    fun prepare(messages: JSONArray, systemCount: Int, tools: JSONArray): JSONArray = tools
    fun tools(tools: JSONArray): JSONArray = tools
    fun process(messages: JSONArray, systemCount: Int, maxTokens: Int?): AgentExtensionCompletion? = null
    fun processStreaming(messages: JSONArray, systemCount: Int, maxTokens: Int?, onDelta: (String) -> Unit): AgentExtensionCompletion? =
        process(messages, systemCount, maxTokens)
    fun event(event: AgentEvent) = Unit
    fun finished(success: Boolean) = Unit
    fun intercept(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult? = null
    fun completed(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) = Unit
    fun permissionChecked(call: AgentModelClient.ToolCall, allowed: Boolean) = Unit
    fun toolStarting(call: AgentModelClient.ToolCall) = Unit
    fun toolFailed(call: AgentModelClient.ToolCall) = Unit
    fun executeTool(call: AgentModelClient.ToolCall, execute: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        val denied = intercept(call)
        if (denied != null) return denied
        val result = try { execute() } catch (failure: Throwable) {
            try { toolFailed(call) } catch (_: Exception) { }
            throw failure
        }
        try { completed(call, result) } catch (failure: Exception) {
            io.github.mangi.eta.core.AndroidAgentLogger.warn("Package result hook failed after tool settled: ${failure.javaClass.simpleName}")
        }
        return result
    }
    override fun close() = Unit
    companion object { val NONE = object : AgentRunExtensions {} }
}
