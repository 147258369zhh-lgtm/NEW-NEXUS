package io.github.mangi.eta.fork.a2a

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository

/** Idempotent bootstrap for the isolated :a2a process and sticky-service restarts. */
internal object A2ARuntimeBootstrap {
    private val lock = Any()
    @Volatile private var completed = false

    fun ensure(context: Context) {
        if (completed && ProviderRepository.isInitialized()) return
        synchronized(lock) {
            if (completed && ProviderRepository.isInitialized()) return
            val appContext = context.applicationContext
            SettingsDataStore.init(appContext)
            AgentRunMetricsStore.init(appContext)
            ProviderRepository.initIfNeeded(appContext)
            AgentMemoryRepository.init(appContext)
            McpServerRepository.init(appContext)
            LocalModelRepository.init(appContext)
            completed = true
        }
    }
}
