package io.github.mangi.eta

import io.github.mangi.eta.agent.voice.SpeechOssUpload
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.accessibility.RootAccessibilityRecovery
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.fork.data.LegacyInstallMigrationCoordinator
import io.github.mangi.eta.fork.conversations.ConversationArchiveScheduler
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.tasks.AgentTaskWorkScheduler
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.EtaFullBackupArchive
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.agent.model.LiteRtLmRuntimeManager
import io.github.mangi.eta.agent.model.LlamaCppRuntimeManager
import io.github.mangi.eta.agent.model.MnnRuntimeManager
import io.github.mangi.eta.agent.model.LocalModelStartupCoordinator
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener, androidx.work.Configuration.Provider {

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        val fullRuntime = AppProcessPolicy.shouldInitializeFullRuntime(
            Application.getProcessName(),
            packageName,
        )
        // Full archive restores replace Room/DataStore files and therefore must happen before any
        // database handle or preference store is opened. A failed apply rolls back synchronously.
        if (fullRuntime) {
            runCatching {
                if (EtaFullBackupArchive.applyPendingRestore(this@EtaApp)) {
                    runCatching {
                        val settings = io.github.mangi.eta.data.repository.AutoBackupStore.read(this@EtaApp)
                        io.github.mangi.eta.data.repository.AutoBackupStore.save(this@EtaApp, settings.copy(enabled = false, tree = ""))
                        // The backup password is wrapped by this installation's Keystore and cannot migrate.
                        io.github.mangi.eta.data.repository.AutoBackupPassword.save(this@EtaApp, "")
                        io.github.mangi.eta.data.repository.AutoBackupStore.status(this@EtaApp, "恢复后请重新选择备份目录")
                    }.onFailure { error ->
                        AndroidAgentLogger.warn(
                            "Automatic backup settings could not be reset after restore: type=${error.safeLogType()}",
                        )
                    }
                }
            }
                .onFailure { error ->
                    AndroidAgentLogger.error(
                        "Nexus full backup startup restore failed: type=${error.safeLogType()}",
                    )
                }
        }
        Prefs.initLocal(this)
        io.github.mangi.eta.agent.model.AgentHttpClient.initialize(this)
        // Secondary processes (:voice, :recognition) also serve speech requests and need the
        // local audio runtimes even though they must not open the main app's databases.
        LocalSpeechRuntime.initialize(this)
        if (!fullRuntime) return
        // This must finish before any Room or DataStore consumer opens the new stores.
        runBlocking(Dispatchers.IO) {
            LegacyInstallMigrationCoordinator.migrateIfNeeded(this@EtaApp)
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        RootAccessibilityRecovery.initialize(this)
        applicationScope.launch { TerminalRuntime.backgroundJobs(this@EtaApp).resume() }
        SettingsDataStore.init(this)
        AgentRunMetricsStore.init(this)
        applicationScope.launch {
            runCatching {
                io.github.mangi.eta.data.db.EtaDatabase.get(this@EtaApp)
                    .conversationDao()
                    .dedupeIdenticalContextCheckpoints()
            }.onSuccess { count ->
                if (count > 0) AndroidAgentLogger.info("Conversation checkpoint duplicate journals removed: count=$count")
            }.onFailure { error ->
                AndroidAgentLogger.warn("Conversation checkpoint dedupe unavailable: type=${error.safeLogType()}")
            }
        }
        applicationScope.launch {
            try { io.github.mangi.eta.data.repository.ConversationBranchSetupCoordinator.recoverPending(this@EtaApp) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { AndroidAgentLogger.warn("Branch settings recovery unavailable: type=${failure.safeLogType()}") }
        }
        applicationScope.launch {
            try {
                ConversationArchiveScheduler.sync(
                    context = this@EtaApp,
                    settings = ConversationExtensionRepository.archiveSettings(this@EtaApp),
                )
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn(
                    "Nexus conversation archive scheduler unavailable: type=${error.safeLogType()}"
                )
            }
        }
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        AgentMemoryRepository.init(this)
        runCatching { io.github.mangi.eta.data.repository.AutoBackupScheduler.sync(this) }
            .onFailure { AndroidAgentLogger.warn("Backup scheduler unavailable: type=${it.safeLogType()}") }
        runCatching { io.github.mangi.eta.agent.memory.MemoryMaintenanceScheduler.sync(this) }
            .onFailure { AndroidAgentLogger.warn("Memory scheduler unavailable: type=${it.safeLogType()}") }
        runCatching { io.github.mangi.eta.agent.capability.SkillUpdateScheduler.sync(this) }
            .onFailure { AndroidAgentLogger.warn("Skill update scheduler unavailable: type=${it.safeLogType()}") }
        ProviderRepository.init(this)
        io.github.mangi.eta.data.repository.LocalModelRepository.init(this)
        applicationScope.launch {
            runCatching { io.github.mangi.eta.data.repository.LocalModelRepository.refresh() }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Nexus local model maintenance unavailable: type=${error.safeLogType()}",
                    )
                }
        }
        LiteRtLmRuntimeManager.initialize(this)
        LlamaCppRuntimeManager.initialize(this)
        MnnRuntimeManager.initialize(this)
        applicationScope.launch {
            runCatching { LocalModelStartupCoordinator.resumeSelectedModel(this@EtaApp) }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Nexus selected local model could not resume: type=${error.safeLogType()}",
                    )
                }
        }
        McpServerRepository.init(this)
        runCatching {
            runBlocking(Dispatchers.IO) {
                EtaFullBackupArchive.restorePortableSecretsAfterStartup(this@EtaApp)
            }
        }.onFailure { error ->
            AndroidAgentLogger.error(
                "Nexus portable backup credential restore failed: type=${error.safeLogType()}",
            )
        }
        runCatching { AgentTaskWorkScheduler.ensureScheduled(this) }
            .onFailure { error ->
                AndroidAgentLogger.warn(
                    "Nexus task scheduler unavailable: type=${error.safeLogType()}",
                )
            }
        runCatching {
            io.github.mangi.eta.agent.skill.SkillEvolution.enqueue(this)
            io.github.mangi.eta.fork.tasks.AutomationTriggers.signal(this, io.github.mangi.eta.fork.tasks.AutomationTrigger.APP_START, java.util.UUID.randomUUID().toString())
            io.github.mangi.eta.fork.tasks.AutomationMonitorService.restoreIfEnabled(this)
        }.onFailure { AndroidAgentLogger.warn("Core automation startup unavailable: type=${it.safeLogType()}") }
        applicationScope.launch {
            runCatching { AgentTaskWorkScheduler.refreshNextDeadline(this@EtaApp) }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Nexus task deadline refresh unavailable: type=${error.safeLogType()}",
                    )
                }
        }
        applicationScope.launch {
            runCatching { MeetingRepository.recoverInterruptedRuns(this@EtaApp) }
                .onFailure { error ->
                    AndroidAgentLogger.warn(
                        "Meeting interrupted-run recovery failed: type=${error.safeLogType()}",
                    )
                }
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            try {
                SpeechOssUpload(this@EtaApp).retryPending(this@EtaApp)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                AndroidAgentLogger.warn("Nexus speech cleanup unavailable: type=${error.javaClass.simpleName}")
            }
        }
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            BrowserSessionRegistry.releaseIdlePages()
            val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            LiteRtLmRuntimeManager.requestMemoryRelief(critical)
            LlamaCppRuntimeManager.requestMemoryRelief(critical)
            MnnRuntimeManager.requestMemoryRelief(critical)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        BrowserSessionRegistry.releaseIdlePages()
        LiteRtLmRuntimeManager.requestMemoryRelief(critical = true)
        LlamaCppRuntimeManager.requestMemoryRelief(critical = true)
        MnnRuntimeManager.requestMemoryRelief(critical = true)
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
