package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.*
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.app.*
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

private const val BASE = "base"
private const val TOOLS = "tools"
private const val APK_ANALYSIS = "apk_analysis"

@Composable
internal fun LinuxEnvironmentScreen(context: Context, onNavigate: (AppRoute) -> Unit, onBack: () -> Unit) {
    val appContext = context.applicationContext
    val capabilities = rememberDeviceCapabilities()
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val coroutineScope = rememberCoroutineScope()
    var screenActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { screenActive = false } }
    var selectionRetry by remember { mutableIntStateOf(0) }
    var selectionError by remember { mutableStateOf<String?>(null) }
    val selection by produceState<LinuxExecutionSelection?>(initialValue = null, key1 = appContext, key2 = selectionRetry) {
        selectionError = null
        try {
            value = withContext(Dispatchers.IO) { LinuxEnvironmentSettingsRepository.executionSelection(appContext) }
            LinuxEnvironmentSettingsRepository.selectedFlow(appContext).flowOn(Dispatchers.IO).collectLatest { distribution ->
                LinuxEnvironmentSettingsRepository.backendFlow(appContext, distribution).flowOn(Dispatchers.IO).collect { selectedBackend ->
                    value = LinuxExecutionSelection(distribution, selectedBackend)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { selectionError = context.getString(R.string.linux_toolbox_check_failed) }
    }
    val selectedInstance = selection
    if (selectedInstance == null) {
        MiuixScaffoldPage(title = stringResource(R.string.linux_toolbox_title), onBack = onBack) {
            item(key = "loading-selection") {
                LinuxToolboxCard(stringResource(R.string.linux_toolbox_title), selectionError ?: stringResource(R.string.linux_toolbox_checking), Icons.Rounded.Terminal, busy = selectionError == null) {
                    if (selectionError != null) NexaCompactAction(stringResource(R.string.linux_toolbox_refresh), { selectionRetry++ })
                }
            }
        }
        return
    }
    val selectedDistribution = selectedInstance.distribution
    val backend = selectedInstance.backend
    val requiresRoot = backend == LinuxExecutionBackend.CHROOT && !capabilities.root.isGranted
    val installer = remember(appContext, backend) { AlpineEnvironmentInstaller(appContext, backend = backend) }
    val debianInstaller = remember(appContext, backend) { DebianEnvironmentInstaller(appContext, backend = backend) }
    val apkAnalysisInstaller = remember(appContext, selectedDistribution, backend) { LinuxApkAnalysisInstaller(appContext, selectedDistribution, backend = backend) }
    val profiles = remember(selectedDistribution) { LinuxPackageProfiles.ALL.filter { it.specs.containsKey(selectedDistribution) } }
    val profileInstallers = remember(appContext, selectedDistribution, backend) {
        profiles.associate { it.id to LinuxPackageProfileInstaller(appContext, selectedDistribution, it, backend = backend) }
    }
    // No file traversal or command probe runs during composition. A single background batch refreshes this instance.
    var status by remember(installer) { mutableStateOf<AlpineEnvironmentStatus?>(null) }
    var debianStatus by remember(debianInstaller) { mutableStateOf<DebianEnvironmentStatus?>(null) }
    var profileStatuses by remember(selectedDistribution, backend) { mutableStateOf<Map<String, LinuxPackageProfileStatus>>(emptyMap()) }
    var failedChecks by remember(selectedDistribution, backend) { mutableStateOf<Set<String>>(emptySet()) }
    var apkAnalysisReady by remember(selectedDistribution, backend) { mutableStateOf(false) }
    var refreshing by remember(selectedDistribution, backend) { mutableStateOf(false) }
    var cacheRevision by remember(selectedDistribution, backend) { mutableIntStateOf(0) }
    var installedMarkers by remember(selectedDistribution, backend) { mutableStateOf<Set<String>>(emptySet()) }
    var probingProfiles by remember(selectedDistribution, backend) { mutableStateOf<Set<String>>(emptySet()) }
    var visibleProbeJob by remember { mutableStateOf<Job?>(null) }
    val currentInstance = rememberUpdatedState(LinuxExecutionSelection(selectedDistribution, backend))
    var busyTarget by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var debianProgress by remember { mutableStateOf<DebianInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var profileResults by remember(selectedDistribution, backend) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var kimiWebLaunching by remember { mutableStateOf(false) }
    var kimiWebRunning by remember(selectedDistribution, backend) { mutableStateOf(false) }
    val kimiWebLauncher = remember(appContext, selectedDistribution, backend) {
        KimiWebLauncher(context = appContext, backend = backend, daemonSupervisor = DetachedTaskSupervisor(
            logger = AndroidAgentLogger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
            linuxRootfsPathProvider = { environment -> environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it, backend).absolutePath } },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ))
    }
    val selectedBaseReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status?.state?.let { it != AlpineEnvironmentState.NOT_INSTALLED } == true
        LinuxDistribution.DEBIAN -> debianStatus?.state?.let { it != DebianEnvironmentState.NOT_INSTALLED } == true
    }
    val selectedToolsReady = when (selectedDistribution) {
        LinuxDistribution.ALPINE -> status?.state == AlpineEnvironmentState.READY
        LinuxDistribution.DEBIAN -> debianStatus?.state == DebianEnvironmentState.READY
    }
    val checked = when (selectedDistribution) { LinuxDistribution.ALPINE -> status != null; LinuxDistribution.DEBIAN -> debianStatus != null }
    val idle = busyTarget == null && !kimiWebLaunching

    suspend fun refreshStatus() {
        val instance = LinuxExecutionSelection(selectedDistribution, backend)
        refreshing = true
        try {
            val base = withContext(Dispatchers.IO) { installer.status() to debianInstaller.status() }
            currentCoroutineContext().ensureActive()
            if (currentInstance.value != instance) return
            status = base.first
            debianStatus = base.second
            val apkReady = withContext(Dispatchers.IO) { apkAnalysisInstaller.isReady() }
            val cached = withContext(Dispatchers.IO) { profileInstallers.mapValues { (_, it) -> it.cachedStatus() } }
            val markers = withContext(Dispatchers.IO) { profileInstallers.filterValues { it.hasInstallationMarker() }.keys.toSet() }
            currentCoroutineContext().ensureActive()
            if (currentInstance.value != instance) return
            apkAnalysisReady = apkReady
            profileStatuses = cached
            installedMarkers = markers
            failedChecks = emptySet()
            cacheRevision++
        } finally { refreshing = false }
    }
    LaunchedEffect(selectedDistribution, backend, capabilities.root.isGranted) {
        try { refreshStatus() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { resultMessage = context.getString(R.string.linux_toolbox_check_failed) }
    }

    fun launchInstallation(target: String, block: suspend () -> Unit) {
        if (!idle || requiresRoot || refreshing) return
        visibleProbeJob?.cancel()
        busyTarget = target
        profileResults = profileResults - target
        resultMessage = null
        val operationInstance = LinuxExecutionSelection(selectedDistribution, backend)
        val operation: suspend () -> Unit = {
            try { block(); refreshStatus() }
            catch (cancelled: CancellationException) {
                if (screenActive && currentInstance.value == operationInstance) withContext(NonCancellable) {
                    try { refreshStatus() } catch (_: Exception) { }
                    resultMessage = context.getString(R.string.linux_toolbox_operation_cancelled)
                    profileResults = profileResults + (target to resultMessage.orEmpty())
                }
                throw cancelled
            }
            catch (error: Exception) {
                if (screenActive && currentInstance.value == operationInstance) {
                    resultMessage = context.getString(R.string.linux_toolbox_operation_failed) +
                        linuxPackageDiagnosticText(error.message.orEmpty(), 400).takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
                    profileResults = profileResults + (target to resultMessage.orEmpty())
                    try { refreshStatus() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
            }
            finally { progress = null; debianProgress = null; profileProgressSummary = null; apkAnalysisProgress = null; busyTarget = null }
        }
        if (backend == LinuxExecutionBackend.PROOT) {
            requestExecutionNotifications()
            coroutineScope.launchForegroundExecution(context = appContext, onUnavailable = {
                busyTarget = null; resultMessage = context.getString(R.string.capability_background_failed)
                profileResults = profileResults + (target to resultMessage.orEmpty())
            }, block = operation)
        } else coroutineScope.launch { operation() }
    }
    fun installBase(upgrade: Boolean = false) = launchInstallation(BASE) {
        resultMessage = when (selectedDistribution) {
            LinuxDistribution.ALPINE -> {
                val callback: suspend (AlpineInstallProgress) -> Unit = { withContext(Dispatchers.Main.immediate) { progress = it } }
                (if (upgrade) installer.upgradeBase(callback) else installer.installBase(callback)).toMessage(context)
            }
            LinuxDistribution.DEBIAN -> {
                val callback: suspend (DebianInstallProgress) -> Unit = { withContext(Dispatchers.Main.immediate) { debianProgress = it } }
                (if (upgrade) debianInstaller.upgradeBase(callback) else debianInstaller.installBase(callback)).toMessage(context)
            }
        }
    }
    fun installTools() = launchInstallation(TOOLS) {
        resultMessage = when (selectedDistribution) {
            LinuxDistribution.ALPINE -> installer.installTools { withContext(Dispatchers.Main.immediate) { progress = it } }.toMessage(context)
            LinuxDistribution.DEBIAN -> debianInstaller.installTools { withContext(Dispatchers.Main.immediate) { debianProgress = it } }.toMessage(context)
        }
    }
    fun installProfile(profile: LinuxPackageProfile, force: Boolean, repair: Boolean = false) = launchInstallation(profile.id) {
        val title = context.getString(profile.toolboxTitle())
        val result = profileInstallers.getValue(profile.id).install(force = force, repair = repair) {
            withContext(Dispatchers.Main.immediate) { profileProgressSummary = it.summary(context, title) }
        }
        resultMessage = result.toMessage(context, title)
        profileResults = profileResults + (profile.id to resultMessage.orEmpty())
    }
    fun launchKimiWeb() {
        if (!idle || requiresRoot || refreshing) return
        requestExecutionNotifications()
        kimiWebLaunching = true
        resultMessage = null
        coroutineScope.launch {
            try {
                val result = kimiWebLauncher.launch(selectedDistribution.terminalEnvironment)
                if (result is KimiWebLaunchResult.Failed) resultMessage = result.message(context)
                kimiWebRunning = withContext(Dispatchers.IO) { kimiWebLauncher.status(selectedDistribution.terminalEnvironment).running }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { resultMessage = context.getString(R.string.linux_toolbox_operation_failed) }
            finally { kimiWebLaunching = false }
        }
    }
    var category by rememberSaveable { mutableIntStateOf(0) }
    var toolCategory by rememberSaveable { mutableIntStateOf(0) }
    // Only an installed capability in the visible category starts a guest probe. Uninstalled packages use the cache.
    LaunchedEffect(selectedDistribution, backend, category, toolCategory, cacheRevision, requiresRoot, busyTarget) {
        if (category != 1 || requiresRoot || busyTarget != null || !selectedToolsReady) return@LaunchedEffect
        val instance = LinuxExecutionSelection(selectedDistribution, backend)
        val probeJob = currentCoroutineContext().job
        visibleProbeJob = probeJob
        try {
            for (profile in profiles.filter { it.toolboxCategory() == toolCategory && it.id in installedMarkers }) {
                probingProfiles = setOf(profile.id)
                try {
                    val measured = withContext(Dispatchers.IO) { profileInstallers.getValue(profile.id).status() }
                    currentCoroutineContext().ensureActive()
                    if (currentInstance.value != instance) return@LaunchedEffect
                    profileStatuses = profileStatuses + (profile.id to measured)
                    failedChecks = failedChecks - profile.id
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (currentInstance.value == instance) failedChecks = failedChecks + profile.id }
            }
        } finally {
            if (currentInstance.value == instance) probingProfiles = emptySet()
            if (visibleProbeJob == probeJob) visibleProbeJob = null
        }
    }
    LaunchedEffect(selectedDistribution, backend, category, cacheRevision, busyTarget, kimiWebLaunching) {
        if (category != 2 || busyTarget != null || kimiWebLaunching) return@LaunchedEffect
        val instance = LinuxExecutionSelection(selectedDistribution, backend)
        try {
            val service = withContext(Dispatchers.IO) { kimiWebLauncher.status(selectedDistribution.terminalEnvironment) }
            currentCoroutineContext().ensureActive()
            if (currentInstance.value == instance) kimiWebRunning = service.running
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (currentInstance.value == instance) resultMessage = context.getString(R.string.linux_toolbox_check_failed) }
    }
    val overviewScroll = rememberLazyListState()
    val toolsScroll = rememberLazyListState()
    val workScroll = rememberLazyListState()
    val activeProgress = when (busyTarget) {
        BASE, TOOLS -> when (selectedDistribution) { LinuxDistribution.ALPINE -> progress?.summary(context); LinuxDistribution.DEBIAN -> debianProgress?.summary(context) }
        APK_ANALYSIS -> apkAnalysisProgress?.summary(context)
        else -> profileProgressSummary
    }
    MiuixScaffoldPage(listState = listOf(overviewScroll, toolsScroll, workScroll)[category], aboveContent = {
        NexaSegmentedTabs(listOf(stringResource(R.string.linux_toolbox_overview), stringResource(R.string.linux_toolbox_tools), stringResource(R.string.linux_toolbox_work)), category, { category = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }, title = stringResource(R.string.linux_toolbox_title), onBack = onBack) {
        item(key = "instance") {
            LinuxToolboxCard(title = listOfNotNull(selectedDistribution.displayName(), when (selectedDistribution) { LinuxDistribution.ALPINE -> status?.version; LinuxDistribution.DEBIAN -> debianStatus?.version }).joinToString(" "),
                summary = backend.displayName() + " · " + stringResource(if (backend == LinuxExecutionBackend.CHROOT) R.string.linux_toolbox_root_identity else R.string.linux_toolbox_normal_identity),
                icon = Icons.Rounded.Terminal, busy = !idle || refreshing,
                status = when {
                    requiresRoot -> stringResource(R.string.capability_linux_root_lost)
                    kimiWebLaunching -> stringResource(R.string.linux_kimi_web_starting)
                    busyTarget != null -> activeProgress ?: stringResource(R.string.linux_installing)
                    !checked -> stringResource(R.string.linux_toolbox_checking)
                    selectedToolsReady -> stringResource(R.string.linux_environment_tools_ready)
                    selectedBaseReady -> stringResource(R.string.linux_environment_base_ready)
                    else -> stringResource(R.string.linux_not_installed)
                }) {
                if (requiresRoot) NexaCompactAction(stringResource(R.string.capability_enhancements), { onNavigate(AppRoute.SystemEnhance) }, enabled = idle)
                else if (!selectedToolsReady) NexaCompactAction(stringResource(if (selectedBaseReady) R.string.linux_install_base_tools else R.string.linux_install_base), { if (selectedBaseReady) installTools() else installBase() }, enabled = idle && checked && !refreshing)
                else NexaCompactAction(stringResource(R.string.linux_toolbox_upgrade_system), { installBase(true) }, enabled = idle && !refreshing)
                NexaCompactAction(stringResource(R.string.linux_toolbox_refresh), { coroutineScope.launch {
                    try { refreshStatus() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { resultMessage = context.getString(R.string.linux_toolbox_check_failed) }
                } }, enabled = idle && !refreshing)
            }
        }
        resultMessage?.let { message -> item(key = "result") { Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) } }
        if (category == 0) {
            item(key = "quick-actions") { LinuxToolboxQuickActions(stringResource(R.string.linux_toolbox_terminal), stringResource(R.string.linux_files_entry_title), selectedBaseReady && idle && !requiresRoot,
                { onNavigate(AppRoute.Terminal) }, { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) }) }
            item(key = "configuration") {
                LinuxToolboxCard(stringResource(R.string.linux_toolbox_instance), stringResource(R.string.linux_toolbox_instance_summary), Icons.Rounded.Layers) {
                    LinuxDistribution.entries.forEach { distribution ->
                        NexaCompactAction(distribution.displayName(), { resultMessage = null; coroutineScope.launch { LinuxEnvironmentSettingsRepository.select(distribution) } }, selected = distribution == selectedDistribution, enabled = idle && !refreshing)
                    }
                }
            }
            item(key = "identity") {
                LinuxToolboxCard(stringResource(R.string.linux_toolbox_identity), stringResource(if (backend == LinuxExecutionBackend.CHROOT) R.string.linux_toolbox_root_reason else R.string.linux_toolbox_normal_reason), Icons.Rounded.Security) {
                    NexaCompactAction(stringResource(R.string.linux_toolbox_normal), { resultMessage = null; coroutineScope.launch { LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, LinuxExecutionBackend.PROOT) } }, selected = backend == LinuxExecutionBackend.PROOT, enabled = idle && !refreshing)
                    NexaCompactAction(stringResource(R.string.linux_toolbox_root), { resultMessage = null; coroutineScope.launch { LinuxEnvironmentSettingsRepository.selectBackend(selectedDistribution, LinuxExecutionBackend.CHROOT) } }, selected = backend == LinuxExecutionBackend.CHROOT, enabled = idle && !refreshing && capabilities.root.isGranted)
                }
            }
            if (!capabilities.root.isGranted) item(key = "root-permission") {
                NexusSettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    DetailListRow(stringResource(R.string.capability_enhancements), stringResource(R.string.linux_toolbox_root_permission_summary), Icons.Rounded.Security, enabled = idle, tone = EtaIconTone.RISK, onClick = { onNavigate(AppRoute.SystemEnhance) })
                }
            }
            item(key = "tool-shortcut") { NexusSettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { DetailListRow(stringResource(R.string.linux_toolbox_tools), stringResource(R.string.linux_toolbox_tools_summary), Icons.Rounded.Build, tone = EtaIconTone.TOOL, onClick = { category = 1 }) } }
            item(key = "sources") { LinuxSourceSettingsRow(appContext) }
            item(key = "limits") { LinuxResourceSettingsRow(appContext) }
        }
        if (category == 1) {
            item(key = "tool-categories") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.linux_toolbox_development, R.string.linux_toolbox_documents, R.string.linux_toolbox_data, R.string.linux_toolbox_services, R.string.linux_toolbox_network_security, R.string.linux_toolbox_utilities).forEachIndexed { index, res ->
                        NexaCompactAction(stringResource(res), { toolCategory = index }, selected = toolCategory == index)
                    }
                }
            }
            if (!selectedToolsReady) item(key = "setup-note") { Text(stringResource(R.string.settings_linux_extensions_require_setup), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            profiles.filter { it.toolboxCategory() == toolCategory }.forEach { profile -> item(key = profile.id) {
                val measured = profileStatuses[profile.id]
                val ready = measured?.ready == true
                val isBusy = busyTarget == profile.id
                val title = stringResource(profile.toolboxTitle())
                LinuxToolboxCapabilityCard(title, when (toolCategory) { 0 -> Icons.Rounded.Code; 1 -> Icons.Rounded.Description; 2 -> Icons.Rounded.Storage; 4 -> Icons.Rounded.Security; 5 -> Icons.Rounded.Build; else -> Icons.Rounded.Cloud },
                    busy = isBusy, tone = when (toolCategory) { 0 -> EtaIconTone.AUTOMATION; 1 -> EtaIconTone.MEMORY; 2 -> EtaIconTone.DATA; 4 -> EtaIconTone.RISK; 5 -> EtaIconTone.TOOL; else -> EtaIconTone.SYSTEM },
                    status = when {
                        isBusy -> profileProgressSummary ?: stringResource(R.string.linux_installing)
                        profileResults[profile.id] != null -> profileResults[profile.id]
                        profile.id in probingProfiles -> stringResource(R.string.linux_toolbox_checking)
                        !selectedToolsReady -> stringResource(R.string.linux_base_required)
                        requiresRoot -> stringResource(R.string.capability_linux_root_lost)
                        profile.id in failedChecks -> stringResource(R.string.linux_toolbox_check_failed)
                        measured == null -> stringResource(R.string.linux_toolbox_checking)
                        ready -> stringResource(R.string.linux_toolbox_version, measured?.installedVersion.toolboxVersion(context))
                        measured?.installedVersion != null -> stringResource(R.string.linux_toolbox_repair_needed, measured.installedVersion.toolboxVersion(context))
                        else -> stringResource(R.string.linux_not_installed)
                    }) {
                    val canInstall = idle && !refreshing && selectedToolsReady && !requiresRoot
                    if (ready) {
                        NexaCompactAction(stringResource(R.string.linux_toolbox_upgrade), { installProfile(profile, true) }, enabled = canInstall, horizontalPadding = 8.dp)
                        NexaCompactAction(stringResource(R.string.linux_toolbox_repair), { installProfile(profile, true, repair = true) }, enabled = canInstall, horizontalPadding = 8.dp)
                    } else NexaCompactAction(stringResource(if (measured?.installedVersion != null) R.string.linux_toolbox_repair else R.string.linux_install), { installProfile(profile, measured?.installedVersion != null, repair = measured?.installedVersion != null) }, enabled = canInstall, horizontalPadding = 8.dp)
                    if (profile.id == "kimi" && ready) NexaCompactAction(stringResource(R.string.linux_toolbox_services), { category = 2 }, horizontalPadding = 8.dp)
                }
            } }
            if (toolCategory == 0) item(key = APK_ANALYSIS) {
                LinuxToolboxCapabilityCard(stringResource(R.string.ui_apk_analysis_95ad17), Icons.Rounded.Android,
                    status = apkAnalysisProgress?.summary(context) ?: stringResource(if (apkAnalysisReady) R.string.linux_apk_tools_ready else R.string.linux_not_installed), busy = busyTarget == APK_ANALYSIS) {
                    NexaCompactAction(stringResource(if (apkAnalysisReady) R.string.linux_toolbox_upgrade else R.string.linux_install), { launchInstallation(APK_ANALYSIS) {
                        resultMessage = apkAnalysisInstaller.install(force = apkAnalysisReady) { withContext(Dispatchers.Main.immediate) { apkAnalysisProgress = it } }.toMessage(context)
                    } }, enabled = idle && !refreshing && selectedToolsReady && !requiresRoot, horizontalPadding = 8.dp)
                }
            }
        }
        if (category == 2) {
            item(key = "files") {
                NexusSettingsCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    DetailListRow(stringResource(R.string.capability_workspace), stringResource(R.string.capability_workspace_summary), Icons.Rounded.FolderOpen, tone = EtaIconTone.MEMORY, onClick = { onNavigate(AppRoute.Workspace) })
                    DetailListRow(stringResource(R.string.shared_folders_entry_title), stringResource(R.string.linux_environment_shared_folders_summary), Icons.Rounded.FolderShared, enabled = selectedBaseReady && idle, tone = EtaIconTone.TOOL, onClick = { onNavigate(AppRoute.SharedFolders) })
                    DetailListRow(stringResource(R.string.linux_files_entry_title), stringResource(R.string.linux_files_entry_summary), Icons.Rounded.Folder, enabled = selectedBaseReady && idle && !requiresRoot, tone = EtaIconTone.SYSTEM, onClick = { onNavigate(AppRoute.LinuxFiles(selectedDistribution.wireName)) })
                    DetailListRow(stringResource(R.string.linux_toolbox_terminal), stringResource(R.string.linux_toolbox_terminal_summary), Icons.Rounded.Terminal, enabled = selectedBaseReady && idle && !requiresRoot, tone = EtaIconTone.AUTOMATION, onClick = { onNavigate(AppRoute.Terminal) })
                }
            }
            item(key = "services") {
                LinuxToolboxCard(stringResource(R.string.linux_toolbox_service_title), stringResource(R.string.linux_toolbox_service_summary), Icons.Rounded.Cloud,
                    status = stringResource(if (kimiWebLaunching) R.string.linux_kimi_web_starting else if (kimiWebRunning) R.string.linux_toolbox_running else R.string.linux_toolbox_stopped), busy = kimiWebLaunching) {
                    val kimiReady = profileStatuses["kimi"]?.ready == true
                    if (kimiReady) {
                        NexaCompactAction(stringResource(if (kimiWebRunning) R.string.action_open else R.string.linux_kimi_web_launch), { launchKimiWeb() }, enabled = idle && !refreshing && !requiresRoot)
                        if (kimiWebRunning) NexaCompactAction(stringResource(R.string.action_stop), { kimiWebLaunching = true; coroutineScope.launch {
                            try { val stopped = kimiWebLauncher.stop(selectedDistribution.terminalEnvironment); kimiWebRunning = !stopped; resultMessage = context.getString(if (stopped) R.string.linux_toolbox_service_stopped else R.string.linux_toolbox_operation_failed) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { resultMessage = context.getString(R.string.linux_toolbox_operation_failed) }
                            finally { kimiWebLaunching = false }
                        } }, enabled = idle && !refreshing && !requiresRoot)
                    } else NexaCompactAction(stringResource(R.string.linux_toolbox_install_service), { category = 1; toolCategory = 3 })
                }
            }
        }
    }
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AlpineInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun DebianInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != DebianInstallStage.DOWNLOADING || totalBytes <= 0L) return stageName
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String {
    val actualTitle = profileId?.let { id -> LinuxPackageProfiles.ALL.firstOrNull { it.id == id } }
        ?.let { context.getString(it.toolboxTitle()) } ?: profileTitle
    val stageText = when (stage) {
        PackageProfileInstallStage.WAITING -> context.getString(R.string.linux_profile_stage_waiting)
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.DOWNLOADING -> if (totalBytes > 0L) {
            context.getString(
                R.string.linux_profile_stage_downloading_percent,
                actualTitle,
                (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L),
            )
        } else {
            context.getString(R.string.linux_profile_stage_downloading, actualTitle)
        }
        PackageProfileInstallStage.CHECKSUM -> context.getString(R.string.linux_profile_stage_checksum, actualTitle)
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, actualTitle)
        PackageProfileInstallStage.VERIFYING -> context.getString(R.string.linux_profile_stage_verifying, actualTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
    }
    val amount = if (stage == PackageProfileInstallStage.DOWNLOADING && downloadedBytes > 0) {
        if (totalBytes > 0) "${downloadedBytes.toReadableSize(context)} / ${totalBytes.toReadableSize(context)}"
        else downloadedBytes.toReadableSize(context)
    } else null
    val measuredStage = percent?.let { context.getString(R.string.linux_progress_percent, stageText, it) } ?: stageText
    return listOfNotNull(measuredStage, amount, detail?.takeIf { it.isNotBlank() }).joinToString("\n")
}

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.BaseInstalled -> context.getString(R.string.linux_base_install_complete, version)
    is AlpineInstallResult.ToolsInstalled -> context.getString(R.string.linux_install_complete, version)
    AlpineInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun DebianInstallResult.toMessage(context: Context): String = when (this) {
    DebianInstallResult.AlreadyReady -> context.getString(R.string.linux_debian_already_ready)
    is DebianInstallResult.BaseInstalled -> context.getString(R.string.linux_debian_base_install_complete, version)
    is DebianInstallResult.ToolsInstalled -> context.getString(R.string.linux_debian_install_complete, version)
    DebianInstallResult.BaseNotInstalled -> context.getString(R.string.linux_base_required)
    is DebianInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    DebianInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    DebianInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    DebianInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is DebianInstallResult.Failed -> message ?: context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(
    context: Context,
    profileTitle: String,
): String = when (this) {
    PackageProfileInstallResult.AlreadyReady ->
        context.getString(R.string.linux_profile_already_ready, profileTitle)
    PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is PackageProfileInstallResult.DependencyMissing -> {
        val dependencyTitle = LinuxPackageProfiles.ALL.firstOrNull { it.id == profileId }?.let { context.getString(it.toolboxTitle()) } ?: profileId
        context.getString(R.string.linux_profile_dependency_missing, dependencyTitle)
    }
    PackageProfileInstallResult.Installed ->
        context.getString(R.string.linux_profile_installed, profileTitle)
    is PackageProfileInstallResult.Failed -> buildString {
        append(context.getString(R.string.linux_profile_failure_detail,
            PackageProfileInstallProgress(stage, profileId = profileId).summary(context, profileTitle)))
        val codeText = when (exitCode) {
            -2 -> context.getString(R.string.linux_profile_failure_timeout)
            -1 -> context.getString(R.string.linux_profile_failure_start)
            null -> null
            else -> context.getString(R.string.linux_profile_failure_exit_code, exitCode)
        }
        codeText?.let { append("\n").append(it) }
        reason?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
    }
}

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun AlpineInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AlpineInstallStage.CHECKING -> R.string.linux_stage_checking
        AlpineInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AlpineInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun DebianInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        DebianInstallStage.CHECKING -> R.string.linux_stage_checking
        DebianInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        DebianInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        DebianInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        DebianInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)

private fun LinuxPackageProfile.toolboxTitle(): Int = when (id) {
    "python" -> R.string.linux_toolbox_profile_python
    "node" -> R.string.linux_toolbox_profile_node
    "c_cpp" -> R.string.linux_toolbox_profile_c_cpp
    "go" -> R.string.linux_toolbox_profile_go
    "rust" -> R.string.linux_toolbox_profile_rust
    "java" -> R.string.linux_toolbox_profile_java
    "java_build" -> R.string.linux_toolbox_profile_java_build
    "node_dev" -> R.string.linux_toolbox_profile_node_dev
    "python_dev" -> R.string.linux_toolbox_profile_python_dev
    "media" -> R.string.linux_toolbox_profile_media
    "pdf" -> R.string.linux_toolbox_profile_pdf
    "pdf_fonts" -> R.string.linux_toolbox_profile_pdf_fonts
    "ocr" -> R.string.linux_toolbox_profile_ocr
    "tex" -> R.string.linux_toolbox_profile_tex
    "data" -> R.string.linux_toolbox_profile_data
    "duckdb" -> R.string.linux_toolbox_profile_duckdb
    "scientific" -> R.string.linux_toolbox_profile_scientific
    "ssh" -> R.string.linux_toolbox_profile_ssh
    "kimi" -> R.string.linux_toolbox_profile_kimi
    "nginx" -> R.string.linux_toolbox_profile_nginx
    "caddy" -> R.string.linux_toolbox_profile_caddy
    "network_diagnostics" -> R.string.linux_toolbox_profile_network_diagnostics
    "network_scan" -> R.string.linux_toolbox_profile_network_scan
    "packet_analysis" -> R.string.linux_toolbox_profile_packet_analysis
    "web_sqlmap" -> R.string.linux_toolbox_profile_web_sqlmap
    "web_nikto" -> R.string.linux_toolbox_profile_web_nikto
    "file_forensics" -> R.string.linux_toolbox_profile_file_forensics
    "system_performance" -> R.string.linux_toolbox_profile_system_performance
    "database_clients" -> R.string.linux_toolbox_profile_database_clients
    "file_sync" -> R.string.linux_toolbox_profile_file_sync
    "vector_graphics" -> R.string.linux_toolbox_profile_vector_graphics
    else -> R.string.linux_toolbox_tools
}

private fun LinuxPackageProfile.toolboxSummary(): Int = when (id) {
    "python" -> R.string.linux_toolbox_profile_python_summary
    "node" -> R.string.linux_toolbox_profile_node_summary
    "c_cpp" -> R.string.linux_toolbox_profile_c_cpp_summary
    "go" -> R.string.linux_toolbox_profile_go_summary
    "rust" -> R.string.linux_toolbox_profile_rust_summary
    "java" -> R.string.linux_toolbox_profile_java_summary
    "java_build" -> R.string.linux_toolbox_profile_java_build_summary
    "node_dev" -> R.string.linux_toolbox_profile_node_dev_summary
    "python_dev" -> R.string.linux_toolbox_profile_python_dev_summary
    "media" -> R.string.linux_toolbox_profile_media_summary
    "pdf" -> R.string.linux_toolbox_profile_pdf_summary
    "pdf_fonts" -> R.string.linux_toolbox_profile_pdf_fonts_summary
    "ocr" -> R.string.linux_toolbox_profile_ocr_summary
    "tex" -> R.string.linux_toolbox_profile_tex_summary
    "data" -> R.string.linux_toolbox_profile_data_summary
    "duckdb" -> R.string.linux_toolbox_profile_duckdb_summary
    "scientific" -> R.string.linux_toolbox_profile_scientific_summary
    "ssh" -> R.string.linux_toolbox_profile_ssh_summary
    "kimi" -> R.string.linux_toolbox_profile_kimi_summary
    "nginx" -> R.string.linux_toolbox_profile_nginx_summary
    "caddy" -> R.string.linux_toolbox_profile_caddy_summary
    else -> R.string.linux_toolbox_tools_summary
}

private fun LinuxPackageProfile.toolboxCategory(): Int = when (id) {
    "python" -> 0
    "node" -> 0
    "c_cpp" -> 0
    "go" -> 0
    "rust" -> 0
    "java" -> 0
    "java_build" -> 0
    "node_dev" -> 0
    "python_dev" -> 0
    "media" -> 1
    "pdf" -> 1
    "pdf_fonts" -> 1
    "ocr" -> 1
    "tex" -> 1
    "data" -> 2
    "duckdb" -> 2
    "scientific" -> 2
    "ssh" -> 3
    "kimi" -> 3
    "nginx" -> 3
    "caddy" -> 3
    "network_diagnostics" -> 4
    "network_scan" -> 4
    "packet_analysis" -> 4
    "web_sqlmap" -> 4
    "web_nikto" -> 4
    "file_forensics" -> 5
    "system_performance" -> 5
    "database_clients" -> 5
    "file_sync" -> 5
    "vector_graphics" -> 5
    else -> 0
}

// Keep complete CLI evidence in backend status; the interface displays concise version numbers.
private fun String?.toolboxVersion(context: Context): String = Regex("\\d+(?:\\.\\d+){1,4}")
    .findAll(orEmpty()).map { it.value }.distinct().joinToString(" / ")
    .ifBlank { context.getString(R.string.linux_toolbox_version_unknown) }
