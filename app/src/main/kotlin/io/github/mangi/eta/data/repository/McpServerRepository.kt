package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object McpServerRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val secretStore = secrets()
        updateSecret(secretStore, added, bearerToken)
        try {
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.clear(added.id) }
            throw failure
        }
        return added
    }

    /** Imports non-secret configuration. Missing credentials are reported when the server is used. */
    suspend fun addUnconfigured(server: McpServerSetting): McpServerSetting {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        dao().insert(added.toEntity())
        return added
    }

    suspend fun update(server: McpServerSetting, bearerToken: String? = null) {
        requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val changesSecret = bearerToken != null || server.authorizationType == McpAuthorizationType.NONE
        val previousSecret = if (changesSecret) secretStore.encryptedSnapshot(server.id) else null
        val updated = if (bearerToken != null) {
            server.copy(lastProtocolVersion = null, toolsExpireAt = null)
        } else {
            server
        }
        if (changesSecret) updateSecret(secretStore, updated, bearerToken.orEmpty())
        try {
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            if (changesSecret) runCatching {
                secretStore.restoreEncryptedSnapshot(server.id, previousSecret)
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val names = server.enabledToolNames.toMutableSet()
        if (enabled) names += toolName else names -= toolName
        update(server.copy(enabledToolNames = names))
    }

    suspend fun delete(serverId: String) {
        requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousSecret = secretStore.encryptedSnapshot(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            runCatching { secretStore.restoreEncryptedSnapshot(serverId, previousSecret) }
            throw failure
        }
    }

    fun bearerToken(server: McpServerSetting): String? {
        if (server.authorizationType == McpAuthorizationType.NONE) return null
        return secrets().bearerToken(server.id)
            ?: throw McpCredentialsUnavailable(context().getString(R.string.mcp_credentials_missing))
    }

    fun credentialStatus(serverId: String): McpCredentialStatus = secrets().credentialStatus(serverId)

    internal fun appContext(): Context = context()

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (server.authorizationType == McpAuthorizationType.BEARER) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.clear(server.id)
        }
    }

    private fun dao() = EtaDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}
