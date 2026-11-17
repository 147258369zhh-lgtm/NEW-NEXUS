package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.fork.a2a.A2ADatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Configuration counts only; never opens a remote connection or resolves a credential. */
internal object CapabilityConnectionSummary {
    suspend fun otherEnabled(context: Context): Int = withContext(Dispatchers.IO) {
        ConnectorConfigStore(context).load().count { it.enabled } +
            A2ADatabase.get(context).dao().observeAgents().first().count { it.enabled }
    }
}
