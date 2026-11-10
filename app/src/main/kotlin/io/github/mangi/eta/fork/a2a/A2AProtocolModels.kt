package io.github.mangi.eta.fork.a2a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire models for the JSON-RPC binding of A2A 1.0. Patch versions are not negotiated. */
internal const val A2A_PROTOCOL_VERSION = "1.0"
internal const val A2A_JSON_CONTENT_TYPE = "application/json"
internal const val A2A_JSONRPC_CONTENT_TYPE = "application/json"

@Serializable
internal data class A2AAgentCard(
    val name: String,
    val description: String,
    val supportedInterfaces: List<A2AAgentInterface>,
    val provider: A2AAgentProvider? = null,
    val version: String,
    val documentationUrl: String? = null,
    val capabilities: A2AAgentCapabilities = A2AAgentCapabilities(),
    val securitySchemes: JsonObject? = null,
    val securityRequirements: List<JsonObject>? = null,
    val defaultInputModes: List<String>,
    val defaultOutputModes: List<String>,
    val skills: List<A2AAgentSkill>,
    val signatures: List<JsonObject>? = null,
    val iconUrl: String? = null,
)

@Serializable
internal data class A2AAgentInterface(
    val url: String,
    val protocolBinding: String,
    val tenant: String? = null,
    val protocolVersion: String,
)

@Serializable
internal data class A2AAgentProvider(
    val url: String,
    val organization: String,
)

@Serializable
internal data class A2AAgentCapabilities(
    val streaming: Boolean = false,
    val pushNotifications: Boolean = false,
    val extensions: List<JsonObject> = emptyList(),
    val extendedAgentCard: Boolean = false,
)

@Serializable
internal data class A2AAgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val inputModes: List<String> = emptyList(),
    val outputModes: List<String> = emptyList(),
    val securityRequirements: List<JsonObject>? = null,
)

@Serializable
internal data class A2AMessage(
    val messageId: String,
    val role: A2ARole,
    val parts: List<A2APart>,
    val contextId: String? = null,
    val taskId: String? = null,
    val metadata: JsonObject? = null,
    val extensions: List<String> = emptyList(),
    val referenceTaskIds: List<String> = emptyList(),
) {
    init {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(parts.isNotEmpty()) { "A2A messages must contain at least one part" }
    }
}

@Serializable
internal data class A2APart(
    val text: String? = null,
    /** Base64 encoded bytes, as required by the JSON binding. */
    val raw: String? = null,
    val url: String? = null,
    val data: kotlinx.serialization.json.JsonElement? = null,
    val metadata: JsonObject? = null,
    val filename: String? = null,
    val mediaType: String? = null,
) {
    init {
        require(listOf(text, raw, url, data).count { it != null } == 1) {
            "An A2A part must contain exactly one of text, raw, url or data"
        }
    }
}

@Serializable
internal enum class A2ARole {
    @SerialName("ROLE_UNSPECIFIED") UNSPECIFIED,
    @SerialName("ROLE_USER") USER,
    @SerialName("ROLE_AGENT") AGENT,
}

@Serializable
internal enum class A2ATaskState {
    @SerialName("TASK_STATE_UNSPECIFIED") UNSPECIFIED,
    @SerialName("TASK_STATE_SUBMITTED") SUBMITTED,
    @SerialName("TASK_STATE_WORKING") WORKING,
    @SerialName("TASK_STATE_COMPLETED") COMPLETED,
    @SerialName("TASK_STATE_FAILED") FAILED,
    @SerialName("TASK_STATE_CANCELED") CANCELED,
    @SerialName("TASK_STATE_INPUT_REQUIRED") INPUT_REQUIRED,
    @SerialName("TASK_STATE_REJECTED") REJECTED,
    @SerialName("TASK_STATE_AUTH_REQUIRED") AUTH_REQUIRED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED

    val isInterrupted: Boolean
        get() = this == INPUT_REQUIRED || this == AUTH_REQUIRED
}

@Serializable
internal data class A2ATaskStatus(
    val state: A2ATaskState,
    val message: A2AMessage? = null,
    val timestamp: String? = null,
)

@Serializable
internal data class A2AArtifact(
    val artifactId: String,
    val name: String? = null,
    val description: String? = null,
    val parts: List<A2APart>,
    val metadata: JsonObject? = null,
    val extensions: List<String> = emptyList(),
) {
    init {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        require(parts.isNotEmpty()) { "An A2A artifact must contain at least one part" }
    }
}

@Serializable
internal data class A2ATask(
    val id: String,
    val status: A2ATaskStatus,
    val contextId: String? = null,
    val artifacts: List<A2AArtifact> = emptyList(),
    val history: List<A2AMessage> = emptyList(),
    val metadata: JsonObject? = null,
) {
    init {
        require(id.isNotBlank()) { "task id must not be blank" }
    }
}

@Serializable
internal data class A2ASendMessageConfiguration(
    val acceptedOutputModes: List<String> = listOf("text/plain", "application/json"),
    val historyLength: Int? = null,
    val returnImmediately: Boolean = false,
)

@Serializable
internal data class A2ASendMessageRequest(
    val message: A2AMessage,
    val configuration: A2ASendMessageConfiguration? = null,
    val tenant: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
internal data class A2ASendMessageResponse(
    val task: A2ATask? = null,
    val message: A2AMessage? = null,
) {
    init {
        require((task == null) xor (message == null)) {
            "SendMessage response must contain exactly one of task or message"
        }
    }
}

@Serializable
internal data class A2ATaskStatusUpdateEvent(
    val taskId: String,
    val contextId: String,
    val status: A2ATaskStatus,
    val metadata: JsonObject? = null,
)

@Serializable
internal data class A2ATaskArtifactUpdateEvent(
    val taskId: String,
    val contextId: String,
    val artifact: A2AArtifact,
    val append: Boolean = false,
    val lastChunk: Boolean = false,
    val metadata: JsonObject? = null,
)

@Serializable
internal data class A2AStreamResponse(
    val task: A2ATask? = null,
    val message: A2AMessage? = null,
    val statusUpdate: A2ATaskStatusUpdateEvent? = null,
    val artifactUpdate: A2ATaskArtifactUpdateEvent? = null,
) {
    init {
        require(listOf(task, message, statusUpdate, artifactUpdate).count { it != null } == 1) {
            "A2A stream response must contain exactly one of task, message, statusUpdate or artifactUpdate"
        }
    }
}

@Serializable
internal data class A2AGetTaskRequest(
    val id: String,
    val historyLength: Int? = null,
    val tenant: String? = null,
)

@Serializable
internal data class A2ACancelTaskRequest(
    val id: String,
    val metadata: JsonObject? = null,
)

@Serializable
internal data class A2AJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement,
)

@Serializable
internal data class A2AJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val result: JsonObject? = null,
    val error: A2AJsonRpcError? = null,
) {
    init {
        require((result == null) xor (error == null)) {
            "JSON-RPC response must contain exactly one of result or error"
        }
    }
}

@Serializable
internal data class A2AJsonRpcError(
    val code: Int,
    val message: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
)

internal class A2AProtocolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
