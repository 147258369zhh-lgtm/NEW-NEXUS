package io.github.mangi.eta.agent.packages

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONObject

/** Native calls made inside an isolated hook use the original Run's durable tool boundaries. */
internal object PackageHookToolReceipts {
    fun execute(call: AgentModelClient.ToolCall, native: AgentModelClient.ToolExecutor, event: (AgentEvent) -> Unit): AgentModelClient.ToolResult {
        val trace = AgentTraceFormatter()
        event(AgentEvent.ToolStarted(call.round, call.id, call.name, argsPreview = "插件调用"))
        val result = native.execute(call)
        val artifact = runCatching { JSONObject(result.content) }.getOrNull()?.takeIf {
            it.optBoolean("ok") && it.optString("artifact_id").matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))
        }
        event(AgentEvent.ToolFinished(call.round, call.id, call.name, trace.summarizeResult(call.name, result), result.images.size,
            result.images.sumOf { it.bytes }, trace.isSuccessResult(result), result.command,
            artifactId = artifact?.getString("artifact_id"), artifactName = artifact?.optString("path")?.substringAfterLast('/')?.take(255)))
        return result
    }
}
