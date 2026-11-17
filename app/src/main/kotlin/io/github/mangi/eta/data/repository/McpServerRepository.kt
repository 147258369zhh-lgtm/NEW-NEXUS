package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class McpConnectionSnapshot(val server: McpServerSetting, val token: String?, val credentialRevision: String?)

internal object McpServerRepository {
    private val mutationMutex = Mutex()
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow()
        .distinctUntilChanged()
        .map { entities -> entities.map { it.toDomain() } }
        // Tool schemas may contain large JSON documents. Room moves its query off main, but
        // downstream mapping otherwise inherits the UI collector's dispatcher.
        .flowOn(Dispatchers.Default)

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    suspend fun connectionSnapshot(id: String): McpConnectionSnapshot? = mutationMutex.withLock {
        val server = serverById(id) ?: return@withLock null
        McpConnectionSnapshot(server, bearerToken(server), secrets().encryptedSnapshot(server.id))
    }

    suspend fun credentialRevision(id: String): String? = mutationMutex.withLock {
        secrets().encryptedSnapshot(id)
    }

    suspend fun add(server: McpServerSetting, bearerToken: String): McpServerSetting = mutationMutex.withLock {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        val secretStore = secrets()
        val previousSecret = secretStore.encryptedSnapshot(added.id)
        try {
            updateSecret(secretStore, added, bearerToken)
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.restoreEncryptedSnapshot(added.id, previousSecret) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        added
    }

    /** Imports non-secret configuration. Missing credentials are reported when the server is used. */
    suspend fun addUnconfigured(server: McpServerSetting): McpServerSetting = mutationMutex.withLock {
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
        )
        dao().insert(added.toEntity())
        added
    }

    suspend fun update(
        expected: McpServerSetting,
        server: McpServerSetting,
        bearerToken: String? = null,
        expectedCredentialRevision: String? = null,
    ) = mutationMutex.withLock {
        val secretStore = secrets()
        var previousSecret: String? = null
        var secretWriteAttempted = false
        try {
            EtaDatabase.get(context()).withTransaction {
            val current = requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
            require(current == expected && server.id == expected.id) {
                "MCP 工具或连接状态已变化，请重新操作"
            }
            if (bearerToken != null) require(secretStore.encryptedSnapshot(server.id) == expectedCredentialRevision) {
                "MCP 凭据已变化，请重新打开后操作"
            }
            val changesSecret = bearerToken != null ||
                current.authorizationType != server.authorizationType && server.authorizationType == McpAuthorizationType.NONE
            val updated = if (bearerToken != null) {
                server.copy(lastProtocolVersion = null, toolsExpireAt = null)
            } else {
                server
            }
            if (changesSecret) {
                previousSecret = secretStore.encryptedSnapshot(server.id)
                secretWriteAttempted = true
                updateSecret(secretStore, updated, bearerToken.orEmpty())
            }
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
            }
        } catch (failure: Throwable) {
            if (secretWriteAttempted) runCatching { secretStore.restoreEncryptedSnapshot(server.id, previousSecret) }
                .exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) =
        mutationMutex.withLock { EtaDatabase.get(context()).withTransaction {
            val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
            if (enabled) require(toolName in server.uniqueToolNames) { "工具不存在或名称重复，请修正服务后刷新" }
            val names = server.enabledToolNames.toMutableSet()
            if (enabled) names += toolName else names -= toolName
            check(dao().update(server.copy(enabledToolNames = names).toEntity()) == 1)
        } }

    /** A late network response may update its cache, never restore stale user configuration. */
    suspend fun saveDiscovery(expected: McpServerSetting, discovered: McpServerSetting, token: String?): McpServerSetting? =
        mutationMutex.withLock { EtaDatabase.get(context()).withTransaction {
            val current = serverById(expected.id) ?: return@withTransaction null
            if (!current.sameConnectionAs(expected) || bearerToken(current) != token) return@withTransaction null
            val available = discovered.uniqueToolNames.intersect(current.uniqueToolNames)
            val merged = current.copy(
                tools = discovered.tools, enabledToolNames = current.enabledToolNames.intersect(available),
                lastProtocolVersion = discovered.lastProtocolVersion, toolsExpireAt = discovered.toolsExpireAt,
                lastRefreshedAt = discovered.lastRefreshedAt, lastCheckedAt = discovered.lastCheckedAt,
                lastHealthyAt = discovered.lastHealthyAt, lastError = discovered.lastError,
            )
            check(dao().update(merged.toEntity()) == 1)
            merged
        } }

    suspend fun recordDiscoveryFailure(expected: McpServerSetting, token: String?, message: String) {
        mutationMutex.withLock { EtaDatabase.get(context()).withTransaction {
            val current = serverById(expected.id) ?: return@withTransaction
            if (!current.sameConnectionAs(expected) || bearerToken(current) != token) return@withTransaction
            dao().update(current.copy(lastCheckedAt = System.currentTimeMillis(), lastError = message.take(500)).toEntity())
        } }
    }

    suspend fun delete(serverId: String) = mutationMutex.withLock {
        val secretStore = secrets()
        var previousSecret: String? = null
        var secretWriteAttempted = false
        try {
            EtaDatabase.get(context()).withTransaction {
                requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
                previousSecret = secretStore.encryptedSnapshot(serverId)
                secretWriteAttempted = true
                secretStore.clear(serverId)
                require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
            }
        } catch (failure: Throwable) {
            if (secretWriteAttempted) runCatching { secretStore.restoreEncryptedSnapshot(serverId, previousSecret) }
                .exceptionOrNull()?.let(failure::addSuppressed)
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
