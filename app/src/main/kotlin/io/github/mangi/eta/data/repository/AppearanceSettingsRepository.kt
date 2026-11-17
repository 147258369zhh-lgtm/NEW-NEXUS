package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceSettings
import kotlinx.coroutines.flow.Flow
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.mangi.eta.data.model.MessageTypographySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

object AppearanceSettingsRepository {
    private val typographyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val typographyWriteLock = Any()
    private var typographyWrite: Deferred<Unit>? = null

    /** Finishes committed UI edits even when the settings page is immediately dismissed. */
    fun saveMessageTypography(value: MessageTypographySettings): Deferred<Unit> =
        saveAppearanceChange { it.copy(messageTypography = value.normalized()) }

    fun saveAppearanceChange(transform: (AppearanceSettings) -> AppearanceSettings): Deferred<Unit> = synchronized(typographyWriteLock) {
        val previous = typographyWrite
        typographyScope.async {
            previous?.join()
            control().updateAppearance { transform(it).normalized() }
            startupSnapshot = null
        }.also { typographyWrite = it }
    }

    @Volatile
    private var startupSnapshot: AppearanceSettings? = null
    internal fun invalidateStartupSnapshot() { startupSnapshot = null }
    private val startupMutex = Mutex()

    fun settingsFlow(): Flow<AppearanceSettings> = SettingsDataStore.appearanceSettingsFlow()

    fun startupSnapshot(): AppearanceSettings? = startupSnapshot

    suspend fun settings(): AppearanceSettings {
        startupSnapshot?.let { return it }
        return startupMutex.withLock {
            startupSnapshot ?: SettingsDataStore.settings().appearance.also { startupSnapshot = it }
        }
    }

    suspend fun update(settings: AppearanceSettings) {
        control().updateAppearance { settings }
        startupSnapshot = null
    }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        control().updateAppearance(transform)
        startupSnapshot = null
    }

    private fun control() = NexusSettingsControlPlane.get(SettingsDataStore.applicationContext())
}
