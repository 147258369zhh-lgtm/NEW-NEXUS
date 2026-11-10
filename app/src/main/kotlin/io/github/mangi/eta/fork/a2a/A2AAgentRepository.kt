package io.github.mangi.eta.fork.a2a

import android.content.Context
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.util.UUID

/** Persists remote agents and tasks while keeping credentials and artifacts quarantined by default. */
internal class A2AAgentRepository(
    context: Context,
    private val client: A2AHttpClient,
    private val dao: A2ADao = A2ADatabase.get(context).dao(),
    private val secretStore: A2ASecretStore = A2ASecretStore(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeAgents() = dao.observeAgents()
    fun observeTasks() = dao.observeTasks()
    fun observeEvents(localTaskId: String) = dao.observeEvents(localTaskId)
    fun observeArtifacts(localTaskId: String) = dao.observeArtifacts(localTaskId)

    suspend fun discoverAndAddAgent(
        displayName: String,
        cardUrl: String,
        bearerToken: String? = null,
    ): A2AAgentEntity {
        val card = client.discoverAgentCard(cardUrl, bearerToken)
        val selectedInterface = card.supportedInterfaces.firstOrNull {
            it.protocolVersion == A2A_PROTOCOL_VERSION && it.protocolBinding.equals("JSONRPC", ignoreCase = true)
        } ?: throw A2AProtocolException("Agent Card has no JSON-RPC interface for A2A 1.0")
        val interfaceUrl = selectedInterface.url
        val now = clock()
        val id = stableId("agent", cardUrl)
        val secretAlias = bearerToken?.takeIf(String::isNotBlank)?.let { token ->
            val alias = "agent-${stableId("token", id).take(24)}"
            secretStore.save(alias, token)
        }
        val existing = dao.agent(id)
        val entity = A2AAgentEntity(
            id = id,
            displayName = displayName.trim().ifBlank { card.name },
            cardUrl = cardUrl,
            endpointUrl = interfaceUrl,
            tenant = selectedInterface.tenant,
            protocolVersion = A2A_PROTOCOL_VERSION,
            authSecretAlias = secretAlias ?: existing?.authSecretAlias,
            trustState = "MANUAL_ALLOWLIST",
            cardJson = A2A_JSON.encodeToString(card),
            enabled = true,
            discoveredAt = now,
            updatedAt = now,
        )
        dao.upsertAgent(entity)
        return entity
    }

    suspend fun refreshAgentCard(agentId: String): A2AAgentEntity {
        val current = dao.agent(agentId) ?: throw A2AProtocolException("Unknown A2A agent")
        val token = secretStore.load(current.authSecretAlias)
        val card = client.discoverAgentCard(current.cardUrl, token)
        val selectedInterface = card.supportedInterfaces.firstOrNull {
            it.protocolVersion == A2A_PROTOCOL_VERSION && it.protocolBinding.equals("JSONRPC", ignoreCase = true)
        } ?: throw A2AProtocolException("Agent Card has no JSON-RPC interface for A2A 1.0")
        val updated = current.copy(
            displayName = card.name,
            endpointUrl = selectedInterface.url,
            tenant = selectedInterface.tenant,
            cardJson = A2A_JSON.encodeToString(card),
            discoveredAt = clock(),
            updatedAt = clock(),
        )
        dao.upsertAgent(updated)
        return updated
    }

    suspend fun removeAgent(agentId: String) {
        val current = dao.agent(agentId) ?: return
        dao.deleteAgent(agentId)
        secretStore.delete(current.authSecretAlias)
    }

    suspend fun setAgentEnabled(agentId: String, enabled: Boolean) {
        if (dao.agent(agentId) == null) throw A2AProtocolException("Unknown A2A agent")
        dao.setAgentEnabled(agentId, enabled, clock())
    }

    suspend fun sendMessage(
        agentId: String,
        message: A2AMessage,
        clientTaskId: String? = null,
        returnImmediately: Boolean = false,
    ): A2ASendMessageResponse {
        require(message.role == A2ARole.USER) { "Messages sent to a remote agent must use ROLE_USER" }
        val agent = enabledAgent(agentId)
        val response = client.sendMessage(
            endpointUrl = agent.endpointUrl,
            request = A2ASendMessageRequest(
                message = message,
                tenant = agent.tenant,
                configuration = if (returnImmediately) {
                    A2ASendMessageConfiguration(returnImmediately = true)
                } else {
                    null
                },
            ),
            bearerToken = secretStore.load(agent.authSecretAlias),
        )
        response.task?.let { task -> persistTask(agent.id, task, clientTaskId) }
        response.message?.let { remoteMessage ->
            val now = clock()
            val localId = clientTaskId ?: stableId("direct", "${agent.id}:${message.messageId}")
            val existing = dao.task(localId)
            val task = A2ATaskEntity(
                localId = localId,
                agentId = agent.id,
                remoteTaskId = existing?.remoteTaskId,
                remoteContextId = remoteMessage.contextId ?: existing?.remoteContextId,
                state = A2ATaskState.COMPLETED.name,
                cancelRequestedAt = existing?.cancelRequestedAt,
                cancelUnconfirmed = false,
                lastEventKey = existing?.lastEventKey,
                errorCode = null,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            dao.upsertTask(task)
            recordEvent(
                localTaskId = localId,
                kind = "message",
                eventKey = "message:${remoteMessage.messageId}",
                payload = A2AStreamResponse(message = remoteMessage),
            )
        }
        return response
    }

    suspend fun getTask(agentId: String, localTaskId: String): A2ATask {
        val agent = enabledAgent(agentId)
        val local = dao.task(localTaskId) ?: throw A2AProtocolException("Unknown local A2A task")
        val remoteId = local.remoteTaskId ?: throw A2AProtocolException("Task is not backed by a remote task")
        val task = client.getTask(
            endpointUrl = agent.endpointUrl,
            request = A2AGetTaskRequest(id = remoteId, tenant = agent.tenant),
            bearerToken = secretStore.load(agent.authSecretAlias),
        )
        persistTask(agent.id, task, localTaskId)
        return task
    }

    suspend fun cancelTask(agentId: String, localTaskId: String): A2ATask {
        val agent = enabledAgent(agentId)
        val local = dao.task(localTaskId) ?: throw A2AProtocolException("Unknown local A2A task")
        val remoteId = local.remoteTaskId ?: throw A2AProtocolException("Task is not backed by a remote task")
        dao.markCancelRequested(localTaskId, clock())
        return try {
            val task = client.cancelTask(
                endpointUrl = agent.endpointUrl,
                request = A2ACancelTaskRequest(id = remoteId),
                bearerToken = secretStore.load(agent.authSecretAlias),
            )
            persistTask(agent.id, task, localTaskId)
            task
        } catch (error: Exception) {
            dao.markCancelUnconfirmed(
                localId = localTaskId,
                errorCode = "CANCEL_UNCONFIRMED",
                message = error.message ?: error.javaClass.simpleName,
                updatedAt = clock(),
            )
            throw error
        }
    }

    suspend fun applyStreamEvent(agentId: String, localTaskId: String, event: A2AStreamResponse) {
        val agent = enabledAgent(agentId)
        when {
            event.task != null -> persistTask(agent.id, event.task, localTaskId)
            event.message != null -> recordEvent(
                localTaskId,
                kind = "message",
                eventKey = "message:${event.message.messageId}",
                payload = event,
            )
            event.statusUpdate != null -> {
                val update = event.statusUpdate
                val key = "status:${update.taskId}:${stableId("status", event.toString())}"
                dao.updateTaskState(
                    localId = localTaskId,
                    state = update.status.state.name,
                    eventKey = key,
                    errorCode = if (update.status.state == A2ATaskState.FAILED) "REMOTE_FAILED" else null,
                    errorMessage = update.status.message?.parts?.joinToString("\n") { part -> part.text.orEmpty() }
                        ?.takeIf(String::isNotBlank),
                    updatedAt = clock(),
                )
                recordEvent(localTaskId, "status", key, event)
            }
            event.artifactUpdate != null -> {
                val update = event.artifactUpdate
                val key = "artifact:${update.taskId}:${update.artifact.artifactId}:${stableId("chunk", event.toString())}"
                recordEvent(localTaskId, "artifact", key, event)
                persistArtifact(localTaskId, update.artifact)
            }
        }
    }

    suspend fun setArtifactQuarantineState(
        localTaskId: String,
        artifactId: String,
        state: A2AArtifactQuarantineState,
    ) = dao.setArtifactQuarantineState(localTaskId, artifactId, state.name)

    private suspend fun persistTask(agentId: String, task: A2ATask, clientTaskId: String?): String {
        val existing = clientTaskId?.let { dao.task(it) }
            ?: dao.taskByRemoteId(agentId, task.id)
        val now = clock()
        val localId = existing?.localId ?: clientTaskId ?: idFactory()
        val eventKey = "task:${task.id}:${stableId("task", A2A_JSON.encodeToString(task))}"
        dao.upsertTask(
            A2ATaskEntity(
                localId = localId,
                agentId = agentId,
                remoteTaskId = task.id,
                remoteContextId = task.contextId,
                state = task.status.state.name,
                cancelRequestedAt = existing?.cancelRequestedAt,
                cancelUnconfirmed = if (task.status.state == A2ATaskState.CANCELED) false else existing?.cancelUnconfirmed ?: false,
                lastEventKey = eventKey,
                errorCode = if (task.status.state == A2ATaskState.FAILED) "REMOTE_FAILED" else null,
                errorMessage = task.status.message?.parts?.joinToString("\n") { it.text.orEmpty() }
                    ?.takeIf(String::isNotBlank),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        recordEvent(localId, "task", eventKey, A2AStreamResponse(task = task))
        task.artifacts.forEach { artifact -> persistArtifact(localId, artifact) }
        return localId
    }

    private suspend fun persistArtifact(localTaskId: String, artifact: A2AArtifact) {
        val raw = A2A_JSON.encodeToString(artifact)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_ARTIFACT_BYTES) {
            recordEvent(
                localTaskId = localTaskId,
                kind = "artifact_rejected",
                eventKey = "artifact-rejected:${artifact.artifactId}:${stableId("artifact", raw)}",
                payload = A2AStreamResponse(
                    statusUpdate = A2ATaskStatusUpdateEvent(
                        taskId = "unknown",
                        contextId = "unknown",
                        status = A2ATaskStatus(A2ATaskState.FAILED),
                    ),
                ),
            )
            return
        }
        dao.upsertArtifact(
            A2AArtifactEntity(
                localTaskId = localTaskId,
                artifactId = artifact.artifactId,
                name = artifact.name,
                mediaTypeSummary = artifact.parts.mapNotNull(A2APart::mediaType).distinct().joinToString(",")
                    .ifBlank { null },
                rawJson = raw,
                quarantineState = A2AArtifactQuarantineState.PENDING.name,
                receivedAt = clock(),
            ),
        )
    }

    private suspend fun recordEvent(
        localTaskId: String,
        kind: String,
        eventKey: String,
        payload: A2AStreamResponse,
    ) {
        val raw = A2A_JSON.encodeToString(payload)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_EVENT_BYTES) return
        dao.insertEvent(
            A2AEventEntity(
                localTaskId = localTaskId,
                eventKey = eventKey,
                kind = kind,
                payloadJson = raw,
                receivedAt = clock(),
            ),
        )
    }

    private suspend fun enabledAgent(agentId: String): A2AAgentEntity {
        val agent = dao.agent(agentId) ?: throw A2AProtocolException("Unknown A2A agent")
        if (!agent.enabled) throw A2AProtocolException("A2A agent is disabled")
        return agent
    }

    private fun stableId(namespace: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$namespace\u0000$value".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_EVENT_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024
    }
}

internal enum class A2AArtifactQuarantineState {
    PENDING,
    APPROVED,
    REJECTED,
}
