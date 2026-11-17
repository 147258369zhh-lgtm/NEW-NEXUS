@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ProviderConnectionHealthStore
import io.github.mangi.eta.data.repository.ProviderConnectionProbe
import io.github.mangi.eta.data.repository.ProviderHealthRecord
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixPageBottomSpacer
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providerLoadState by remember { io.github.mangi.eta.ui.providerSettingsLoadState(ProviderRepository.providersFlow()) }
        .collectAsState(initial = io.github.mangi.eta.ui.cachedProviderSettingsLoadState())
    val providers = (providerLoadState as? io.github.mangi.eta.ui.ProviderSettingsLoadState.Loaded)?.providers.orEmpty()
    val connectionHealth by ProviderConnectionHealthStore.records.collectAsState()
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { ProviderConnectionHealthStore.observe(context) }
    }
    var createdId by remember { mutableStateOf<String?>(null) }
    var testingProviderId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    val draft = remember(newType) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> CustomProviderSetting(
                id = "",
                name = "",
                baseUrl = "",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.LocalRuntime -> CustomProviderSetting(
                id = "",
                name = "本地模型服务",
                baseUrl = "http://127.0.0.1:11434/v1",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            NewProviderType.Gemini -> GeminiProviderSetting(
                id = "",
                name = "",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        MiuixScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(when (providerLoadState) {
                        io.github.mangi.eta.ui.ProviderSettingsLoadState.Loading -> "正在加载服务商信息…"
                        io.github.mangi.eta.ui.ProviderSettingsLoadState.Failed -> "服务商信息加载失败，请重试"
                        is io.github.mangi.eta.ui.ProviderSettingsLoadState.Loaded -> stringResource(R.string.ui_provider_does_not_exist_83cee6)
                    })
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by rememberSaveable(initial.id) { mutableIntStateOf(0) }
    LaunchedEffect(provider?.id, createdId, providerId) {
        if (providerId == null && createdId != null && provider?.id == createdId) {
            currentTab = 1
        }
    }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    MiuixScaffold(title = title, onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                ProviderDetailStatusCard(
                    provider = initial,
                    health = connectionHealth[initial.id],
                    checking = testingProviderId == initial.id,
                    onTest = {
                        if (testingProviderId != null) return@ProviderDetailStatusCard
                        testingProviderId = initial.id
                        scope.launch {
                            val probeModelId = initial.models.firstOrNull { it.isEnabled && (it.outputModalities.isEmpty() ||
                                it.outputModalities.any { modality -> modality.equals("text", ignoreCase = true) }) }?.id
                            ProviderConnectionProbe.test(context, initial)
                                .onSuccess { result ->
                                    withContext(Dispatchers.IO) {
                                        ProviderConnectionHealthStore.recordSuccess(context, initial.id, result.modelId)
                                    }
                                }
                                .onFailure {
                                    if (probeModelId != null) withContext(Dispatchers.IO) { ProviderConnectionHealthStore.recordFailure(
                                        context, initial.id, probeModelId, it.message ?: "连接测试失败",
                                    ) }
                                }
                            testingProviderId = null
                        }
                    },
                    modifier = Modifier.padding(horizontal = sidePadding + 16.dp, vertical = 6.dp),
                )
            }
            val tabIds = if (isNew) listOf(0, 2, 3) else listOf(0, 1, 2, 3)
            NexusSettingsTabs(
                tabs = tabIds.map { tab ->
                    stringResource(when (tab) {
                        1 -> R.string.page_model_98fd0c
                        2 -> R.string.settings_tab_behavior
                        3 -> R.string.settings_tab_headers
                        else -> R.string.ui_connection_configuration_7d057b
                    })
                },
                selectedTab = tabIds.indexOf(currentTab).coerceAtLeast(0),
                onTabSelected = { currentTab = tabIds[it] },
                modifier = Modifier.padding(horizontal = sidePadding),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ProviderConfigTab(
                    provider = initial,
                    draft = configDraft,
                    onDraftChange = { configDraft = it },
                    scope = scope,
                    isNew = isNew,
                    activeTab = currentTab,
                    scrollBehavior = scrollBehavior,
                    contentSidePadding = sidePadding,
                    onCreated = { id -> createdId = id },
                    onDeleted = onBack,
                )
                if (!isNew) {
                    ProviderModelsTab(
                        provider = initial,
                        visible = currentTab == 1,
                        scope = scope,
                        scrollBehavior = scrollBehavior,
                        contentSidePadding = sidePadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailStatusCard(
    provider: ProviderSetting,
    health: ProviderHealthRecord?,
    checking: Boolean,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeModelId = provider.models.firstOrNull { it.isEnabled }?.id
    val currentHealth = health?.takeIf { it.modelId.isBlank() || activeModelId != null && it.modelId == activeModelId }
    Card(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ProviderIcon(provider)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when {
                        checking -> "正在检查连接"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.HEALTHY -> "连接验证成功"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.CATALOG_AVAILABLE -> "模型目录连接成功"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.FAILED -> "连接测试失败"
                        provider.isEnabled -> "连接已启用"
                        else -> "连接已停用"
                    },
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = currentHealth?.message?.takeIf(String::isNotBlank) ?: provider.baseUrl,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                EtaStatusPill(
                    text = when {
                        !provider.isEnabled -> "停用"
                        checking -> "检查中"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.HEALTHY -> "已验证"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.CATALOG_AVAILABLE -> "目录可用"
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.FAILED -> "故障"
                        provider.hasUsableConnection() -> "待检查"
                        else -> "待配置"
                    },
                    tone = when {
                        !provider.isEnabled -> EtaStatusTone.Neutral
                        checking -> EtaStatusTone.Warning
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.HEALTHY -> EtaStatusTone.Positive
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.CATALOG_AVAILABLE -> EtaStatusTone.Positive
                        currentHealth?.state == io.github.mangi.eta.data.repository.ProviderHealthState.FAILED -> EtaStatusTone.Danger
                        else -> EtaStatusTone.Neutral
                    },
                )
                TextButton(
                    text = if (checking) "检查中" else "测试",
                    enabled = !checking && provider.hasUsableConnection(),
                    onClick = onTest,
                    minWidth = 0.dp,
                    minHeight = 32.dp,
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    activeTab: Int,
    scrollBehavior: ScrollBehavior,
    contentSidePadding: Dp,
    onCreated: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    var headersExpanded by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var isWorking by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    val connectionListState = rememberLazyListState()
    val behaviorListState = rememberLazyListState()
    val headersListState = rememberLazyListState()
    // 只恢复中断提示；回调监听、验证码和授权 URL 随登录会话销毁。
    var loginInProgress by rememberSaveable(provider.id) { mutableStateOf(false) }
    var pendingInterruption by remember(provider.id) { mutableStateOf(loginInProgress) }
    val loginStore = remember(provider.id, scope) { ProviderLoginStore(provider.id, scope) }
    val loginState by loginStore.state.collectAsState()
    var browserError by remember(loginState.prompt) { mutableStateOf<String?>(null) }
    val openBrowser: () -> Unit = {
        loginState.prompt?.let { browserError = openProviderAccountLink(context, it.authorizationUrl) }
    }
    DisposableEffect(loginStore) {
        onDispose { loginStore.close() }
    }
    LaunchedEffect(loginStore, draft.authMode, provider.authMode) {
        loginStore.load(draft.authMode, interrupted = pendingInterruption)
        pendingInterruption = false
    }
    LaunchedEffect(loginState.phase) {
        loginInProgress = loginState.phase == ProviderLoginPhase.Saving || loginState.phase == ProviderLoginPhase.Authorizing
    }
    LaunchedEffect(loginState.prompt) {
        val prompt = loginState.prompt
        if (prompt != null && prompt.userCode == null) openBrowser()
    }

    if (activeTab != 1) {
        LazyColumn(
            state = when (activeTab) {
                2 -> behaviorListState
                3 -> headersListState
                else -> connectionListState
            },
            modifier = Modifier
                .fillMaxSize()
                // 低层 MiuixScaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
                .imePadding()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = contentSidePadding,
                end = contentSidePadding,
            ),
            overscrollEffect = null,
        ) {
            if (activeTab == 0) {
                item(key = "connection") {
                    ProviderSection(title = stringResource(R.string.ui_connection_configuration_7d057b)) {
                        ProviderConnectionFields(
                            provider = provider,
                            draft = draft,
                            onDraftChange = onDraftChange,
                            loginState = loginState,
                            enabled = !isWorking && !loginState.isBusy,
                            browserError = browserError,
                            onOpenBrowser = openBrowser,
                            onManageUsage = { browserError = openProviderAccountLink(context, "https://chatgpt.com/codex/settings/usage") },
                            onLogin = {
                                val validationError = validateProviderDraft(context, draft)
                                if (validationError != null) {
                                    status = context.getString(R.string.provider_error, validationError)
                                } else {
                                    loginStore.login(draft.toProvider(provider))
                                }
                            },
                            onDisconnect = loginStore::disconnect,
                            onCancel = loginStore::cancel,
                        )
                        BasicComponent(
                            title = stringResource(R.string.ui_test_connection_10b7d8),
                            summary = testStatus,
                            enabled = !isWorking && !loginState.isBusy,
                            onClick = {
                                val validationError = validateProviderDraft(context, draft)
                                if (validationError != null) {
                                    testStatus = context.getString(R.string.provider_error, validationError)
                                    return@BasicComponent
                                }
                                scope.launch {
                                    isWorking = true
                                    testStatus = context.getString(R.string.page_testing_f43705)
                                    try {
                                        testStatus = testConnection(
                                            context,
                                            draft.toProvider(provider)
                                        )
                                    } finally {
                                        isWorking = false
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (activeTab == 3) {
                providerHeadersEditor(
                    headers = draft.headers,
                    expanded = headersExpanded,
                    onExpandedChange = { headersExpanded = it },
                    onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
                    enabled = !isWorking && !loginState.isBusy,
                )
            }

            if (activeTab == 2) {
                item(key = "preferences_and_prompt") {
                    ProviderSection(title = stringResource(R.string.ui_preferences_and_strategies_2abd3c)) {
                        SwitchPreference(
                            title = stringResource(R.string.ui_enable_this_provider_683a76),
                            checked = draft.isEnabled,
                            onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                            enabled = !isWorking && !loginState.isBusy,
                        )
                    }
                    ProviderSection(title = stringResource(R.string.ui_system_prompt_word_193981)) {
                        TextField(
                            value = draft.systemPrompt,
                            onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
                            label = stringResource(R.string.ui_leave_blank_to_use_the_default_mobile_agent_prompt_w_21e7c8),
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            minLines = 4,
                            enabled = !isWorking && !loginState.isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }

            item(key = "actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextButton(
                        text = when {
                            isWorking -> context.getString(R.string.page_saving_d70d42)
                            creationCommitted -> context.getString(R.string.page_created_62cfc5)
                            isNew -> context.getString(R.string.page_create_fcbd09)
                            else -> context.getString(R.string.page_save_configuration_817af1)
                        },
                        enabled = !isWorking && !loginState.isBusy && !creationCommitted,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            val validationError = validateProviderDraft(context, draft)
                            if (validationError != null) {
                                status = context.getString(R.string.provider_error, validationError)
                                return@TextButton
                            }
                            scope.launch {
                                isWorking = true
                                val built = draft.toProvider(provider)
                                try {
                                    if (isNew) {
                                        val added = io.github.mangi.eta.data.repository.ProviderSettingsService(context).save(
                                            built.withId(ProviderRepository.newId())
                                        )
                                        RuntimeConfigRepository.syncToRemotePreferences(
                                            EtaApp.serviceInstance
                                        )
                                        status = context.getString(R.string.capability_provider_created)
                                        creationCommitted = true
                                        onCreated(added.id)
                                    } else {
                                        io.github.mangi.eta.data.repository.ProviderSettingsService(context).save(built, provider)
                                        RuntimeConfigRepository.syncToRemotePreferences(
                                            EtaApp.serviceInstance
                                        )
                                        status = when {
                                            !built.isEnabled -> context.getString(R.string.page_saved_provider_not_enabled_7afa54)
                                            else -> context.getString(R.string.capability_provider_saved)
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    status = context.getString(
                                        R.string.provider_error,
                                        throwable.message ?: context.getString(R.string.provider_save_failed),
                                    )
                                } finally {
                                    isWorking = false
                                }
                            }
                        },
                    )
                    if (!isNew && activeTab == 2) {
                        Spacer(modifier = Modifier.height(12.dp))
                        TextButton(
                            text = if (provider.isBuiltIn) {
                                context.getString(R.string.page_reset_built_in_configuration_35b6ec)
                            } else {
                                context.getString(R.string.page_remove_provider_9f848f)
                            },
                            enabled = !isWorking && !loginState.isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(
                                textColor = MiuixTheme.colorScheme.error,
                            ),
                            onClick = {
                                if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                            },
                        )
                    }
                    status?.let { message ->
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) StatusError else StatusSuccess,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                MiuixPageBottomSpacer()
            }
        }
    }

    if (showDeleteDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, provider.name),
            onDismissRequest = { if (!isWorking) showDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.page_delete_3755f5),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                destructive = true,
                onCancel = { showDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.deleteProvider(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            showDeleteDialog = false
                            onDeleted()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_delete_failed),
                            )
                            showDeleteDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }

    if (showResetDialog) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_reset_built_in_configuration_35b6ec),
            summary = stringResource(R.string.provider_reset_summary, provider.name),
            onDismissRequest = { if (!isWorking) showResetDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isWorking) context.getString(R.string.page_resetting_616090) else context.getString(R.string.page_reset_3d8134),
                cancelEnabled = !isWorking,
                confirmEnabled = !isWorking,
                // 重置会覆盖用户改过的连接与模型配置，与删除同属破坏性确认。
                destructive = true,
                onCancel = { showResetDialog = false },
                onConfirm = {
                    scope.launch {
                        isWorking = true
                        try {
                            ProviderRepository.resetBuiltIn(provider.id)
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            status = context.getString(R.string.page_reset_a0cc65)
                            showResetDialog = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            status = context.getString(
                                R.string.provider_error,
                                throwable.message ?: context.getString(R.string.provider_reset_failed),
                            )
                            showResetDialog = false
                        } finally {
                            isWorking = false
                        }
                    }
                },
            )
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String {
    if (io.github.mangi.eta.agent.packages.PackageAiRegistry.isPackageProvider(provider.id)) return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        kotlinx.coroutines.suspendCancellableCoroutine { pending ->
            val owner = io.github.mangi.eta.agent.runtime.AgentRunController()
            pending.invokeOnCancellation { owner.cancel() }
            try { pending.resumeWith(Result.success(io.github.mangi.eta.agent.packages.PackageAiCatalog(context).test(provider, owner))) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { pending.cancel(cancelled) }
            catch (failure: Exception) { pending.resumeWith(Result.success(context.getString(R.string.provider_error, failure.message ?: "插件模型测试失败"))) }
            finally { owner.cancel() }
        }
    }
    return RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
}
