package io.github.mangi.eta.agent.connector

import android.content.Context
import io.github.mangi.eta.agent.control.ConnectorSettingsControlProvider
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** UI operations use the existing configuration owner and the same durable settings audit. */
internal class ConnectorSettingsService(context: Context, private val store: ConnectorConfigStore = ConnectorConfigStore(context)) {
    private val context = context.applicationContext

    suspend fun save(value: SavedConnectorConfig, token: String? = null): SavedConnectorConfig {
        val draft = value.copy(id = value.id.ifBlank { UUID.randomUUID().toString() })
        val previous = withContext(Dispatchers.IO) { store.load().singleOrNull { it.id == draft.id } }
        var saved: SavedConnectorConfig? = null
        val parameters = ConnectorSettingsControlProvider.parameters(draft).put("privateChange", previous != null &&
            (!token.isNullOrEmpty() || previous.endpoint != draft.endpoint || previous.protocol != draft.protocol || previous.socketPath != draft.socketPath))
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi("settings.connector.configure", parameters, "connection_settings") {
            store.save(draft, token).also { saved = it }
            ConnectorSettingsControlProvider.snapshot(store, draft.id, parameters.optBoolean("privateChange"))
        }
        return requireNotNull(saved)
    }

    suspend fun remove(value: SavedConnectorConfig) {
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi("settings.connector.configure",
            JSONObject().put("connectionId", value.id).put("expectedRevision", value.configurationRevision).put("privateChange", true),
            "connection_settings") {
            val current = store.load().singleOrNull { it.id == value.id } ?: error("连接已移除")
            check(current.configurationRevision == value.configurationRevision) { "连接配置已变化，请重新打开" }
            store.remove(value.id)
            ConnectorSettingsControlProvider.snapshot(store, value.id, privateChange = true)
        }
    }
}
