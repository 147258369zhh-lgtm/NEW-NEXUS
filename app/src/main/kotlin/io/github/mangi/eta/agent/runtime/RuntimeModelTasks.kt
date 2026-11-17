package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import org.json.JSONObject

internal enum class RuntimeModelTaskKind(val source: String, val purpose: ProviderRequestPurpose, val timeoutMs: Long) {
    MEMORY("memory_extraction", ProviderRequestPurpose.MEMORY_EXTRACTION, 90_000),
    SKILL("skill_evolution", ProviderRequestPurpose.MEMORY_EXTRACTION, 90_000),
    TRANSCRIPT("transcript_refine", ProviderRequestPurpose.TRANSCRIPT_REFINE, 8_000),
    NOTEPAD("notepad_organize", ProviderRequestPurpose.MEMORY_EXTRACTION, 90_000),
}

/** Transport adapter for the existing Runtime; maintenance never owns a second Agent loop. */
internal object RuntimeModelTasks {
    fun kind(request: AgentRuntimeWire.RunRequest): RuntimeModelTaskKind {
        require(request.operation == AgentRuntimeWire.OP_MODEL_TASK) { "MODEL_TASK_OPERATION_REQUIRED" }
        return RuntimeModelTaskKind.entries.firstOrNull { it.source == request.handoff?.source }
            ?: error("MODEL_TASK_SOURCE_INVALID")
    }

    fun request(kind: RuntimeModelTaskKind, config: AgentModelClient.ModelConfig, instruction: String, input: String,
        ownerId: String, projectId: String? = null, images: List<AgentModelClient.ModelImage> = emptyList()): AgentRuntimeWire.RunRequest {
        require(instruction.isNotBlank() && instruction.length <= 16_000 && input.length in 1..64_000)
        require(ownerId.isNotBlank() && ownerId.length <= 512)
        require(images.isEmpty() || kind == RuntimeModelTaskKind.NOTEPAD && config.supportsImageInput && images.size <= 4) { "记事本图片输入不可用" }
        val id = "model-task-${UUID.randomUUID()}"
        return AgentRuntimeWire.RunRequest(id, input, restrictedConfig(config, instruction),
            images = images, operation = AgentRuntimeWire.OP_MODEL_TASK,
            handoff = AgentRuntimeWire.EntryHandoff(id, kind.source, JSONObject().put("owner_id", ownerId).put("project_id", projectId).toString()),
            modelSessionId = "${kind.source}:$ownerId", toolPolicy = AgentToolPolicy(allowedTools = emptyList()))
    }

    fun restrictedConfig(config: AgentModelClient.ModelConfig, instruction: String) =
        config.copy(systemPrompt = instruction, supportsTools = false,
            terminalTools = false, browserTools = false, deviceDirectTools = false, deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false, subagentTools = false, hostedWebSearchEnabled = false,
            autoCompactionEnabled = false, supportsSearchSuggestions = false, extraBodyJson = "", customBody = emptyList())

    fun run(context: Context, kind: RuntimeModelTaskKind, config: AgentModelClient.ModelConfig, instruction: String,
        input: String, ownerId: String, controller: AgentRunController, projectId: String? = null): String =
        run(context, request(kind, config, instruction, input, ownerId, projectId), controller).let { result ->
            requireSuccess(result)
            result.content
        }

    fun run(context: Context, request: AgentRuntimeWire.RunRequest, controller: AgentRunController): AgentRuntimeWire.RunResult {
        kind(request)
        controller.throwIfCancelled()
        val client = AgentRuntimeClient(context.applicationContext, AndroidAgentLogger)
        val result = client.run(request, onEvent = {}, controller = controller)
        runCatching { client.ackResult(result.runId.ifBlank { request.runId }) }
        controller.throwIfCancelled()
        return result
    }

    fun requireSuccess(result: AgentRuntimeWire.RunResult) {
        if (!result.ok) throw io.github.mangi.eta.agent.model.AgentModelFailure(result.failureCode ?: "MODEL_TASK_FAILED", false,
            result.error ?: "整理任务未完成")
    }
}
