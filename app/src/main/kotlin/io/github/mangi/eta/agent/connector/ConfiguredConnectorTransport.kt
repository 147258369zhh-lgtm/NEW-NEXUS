package io.github.mangi.eta.agent.connector

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One invocation owns one configured identity; revocation closes the live transport too. */
internal class ConfiguredConnectorTransport(
    private val config: SavedConnectorConfig,
    private val store: ConnectorConfigStore,
    private val transport: ConnectorTransport,
) : ConnectorTransport {
    override val descriptor get() = transport.descriptor
    override val events get() = transport.events
    override suspend fun probe(): ConnectorHealth = guarded { transport.probe() }
    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = guarded { transport.invoke(request) }
    private fun current(): Boolean = runCatching { store.load().singleOrNull { it.id == config.id } == config && config.enabled }.getOrDefault(false)
    private suspend fun <T> guarded(block: suspend () -> T): T = coroutineScope {
        check(current()) { "连接已停用、移除或配置已变化" }
        val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
        val watcher = launch {
            ConnectorConfigStore.changes.collect {
                if (!current()) { revoked.set(true); transport.close() }
            }
        }
        try {
            val result = block()
            check(!revoked.get() && current()) { "连接在调用期间发生变化，请核对外部结果后再操作" }
            result
        } finally { watcher.cancelAndJoin() }
    }
    override fun close() = transport.close()
}
