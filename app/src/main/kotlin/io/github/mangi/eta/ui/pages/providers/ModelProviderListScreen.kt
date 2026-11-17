package io.github.mangi.eta.ui.pages.providers

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentCandidateReliability
import io.github.mangi.eta.agent.runtime.AgentCandidateReliabilityKey
import io.github.mangi.eta.agent.runtime.AgentCandidateReliabilityStats
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AutomationModelSettings
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelCandidateBinding
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelRoleSettings
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.ProviderConnectionHealthStore
import io.github.mangi.eta.data.repository.ProviderHealthRecord
import io.github.mangi.eta.data.repository.ProviderHealthState
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.NexusSettingsSurface
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val providerLoadState by remember { io.github.mangi.eta.ui.providerSettingsLoadState(ProviderRepository.providersFlow()) }
        .collectAsState(initial = io.github.mangi.eta.ui.cachedProviderSettingsLoadState())
    val providers = (providerLoadState as? io.github.mangi.eta.ui.ProviderSettingsLoadState.Loaded)?.providers.orEmpty()
    val connectionHealth by ProviderConnectionHealthStore.records.collectAsState()
    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { ProviderConnectionHealthStore.observe(context) }
    }
    val selectedProviderId by remember { RuntimeConfigRepository.selectedProviderIdFlow() }.collectAsState(initial = null)
    val selectedModelId by remember { RuntimeConfigRepository.selectedModelIdFlow() }.collectAsState(initial = null)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val pageTab = selectedTab.coerceIn(0, 2)
    var fallbackSettings by remember { mutableStateOf(io.github.mangi.eta.data.model.ModelFallbackSettings()) }
    var candidateSettings by remember { mutableStateOf(ModelCandidateSettings()) }
    var automationModelSettings by remember { mutableStateOf(AutomationModelSettings()) }
    var modelRoleSettings by remember { mutableStateOf(ModelRoleSettings()) }
    var candidateReliability by remember {
        mutableStateOf(emptyMap<AgentCandidateReliabilityKey, AgentCandidateReliabilityStats>())
    }
    LaunchedEffect(pageTab) {
        if (pageTab != 1) return@LaunchedEffect
        RuntimeConfigRepository.modelFallbackSettingsFlow().collect { fallbackSettings = it }
    }
    LaunchedEffect(pageTab) {
        if (pageTab != 1) return@LaunchedEffect
        RuntimeConfigRepository.modelCandidateSettingsFlow().collect { candidateSettings = it }
    }
    LaunchedEffect(pageTab) {
        if (pageTab != 1) return@LaunchedEffect
        RuntimeConfigRepository.automationModelSettingsFlow().collect { automationModelSettings = it }
    }
    LaunchedEffect(pageTab) {
        if (pageTab != 1) return@LaunchedEffect
        RuntimeConfigRepository.modelRoleSettingsFlow().collect { modelRoleSettings = it }
    }
    LaunchedEffect(context, pageTab) {
        if (pageTab != 1) return@LaunchedEffect
        AgentRunMetricsStore.candidateReliability(context).collect { candidateReliability = it }
    }
    var reliabilitySort by rememberSaveable { mutableStateOf(false) }
    val providerCredentialState by produceState<Map<String, Boolean>>(emptyMap(), providers) {
        value = withContext(Dispatchers.IO) {
            providers.associate { provider -> provider.id to provider.hasUsableCredentials() }
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var onlineImageSettingsOpen by remember { mutableStateOf(false) }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    var fallbackPickerOpen by remember { mutableStateOf(false) }
    var candidatePickerOpen by remember { mutableStateOf(false) }
    var candidatePurposeTarget by remember { mutableStateOf<CandidateModelRowUi?>(null) }
    var rolePickerTarget by remember { mutableStateOf<ModelRolePickerTarget?>(null) }
    suspend fun saveRoleSettings(settings: ModelRoleSettings) {
        try {
            RuntimeConfigRepository.setModelRoleSettings(settings)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Toast.makeText(context, failure.message ?: "处理模型未保存，请重新选择", Toast.LENGTH_LONG).show()
        }
    }
    val providerListState = rememberLazyListState()
    val strategyListState = rememberLazyListState()
    val runtimeListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    val filteredProviders = remember(providers, searchQuery) {
        val query = searchQuery.trim()
        providers.filter { provider ->
            query.isBlank() ||
                provider.name.contains(query, ignoreCase = true) ||
                provider.baseUrl.contains(query, ignoreCase = true) ||
                provider.typeLabel.contains(query, ignoreCase = true)
        }
    }
    val usableProviders = remember(providers, providerCredentialState) {
        providers.filter { provider ->
            provider.isEnabled && providerCredentialState[provider.id] == true
        }.mapNotNull { provider ->
            provider.models.filter(Model::isEnabled).takeIf(List<Model>::isNotEmpty)
                ?.let { FallbackProviderChoice(provider, it) }
        }
    }
    val fallbackProviders = remember(usableProviders, selectedModelId) {
        usableProviders.mapNotNull { choice ->
            val provider = choice.provider
            provider.models
                .filter { it.id != selectedModelId }
                .takeIf(List<Model>::isNotEmpty)
                ?.let { FallbackProviderChoice(provider, it) }
        }
    }
    val primaryProvider = remember(providers, selectedProviderId) {
        providers.firstOrNull { it.id == selectedProviderId }
    }
    val primaryModel = remember(primaryProvider, selectedModelId) {
        primaryProvider?.models?.firstOrNull { it.id == selectedModelId }
    }
    val primarySummary = primaryModel?.displayName ?: "尚未选择主模型"
    val selectedFallback = remember(fallbackProviders, fallbackSettings.modelId, fallbackSettings.providerId) {
        fallbackProviders.firstNotNullOfOrNull { choice ->
            if (fallbackSettings.providerId != null && choice.provider.id != fallbackSettings.providerId) return@firstNotNullOfOrNull null
            choice.models.firstOrNull { it.id == fallbackSettings.modelId }
                ?.let { FallbackModelSelection(choice.provider, it) }
        }
    }
    val candidateRows = remember(candidateSettings.candidates, providers, candidateReliability, reliabilitySort) {
        val manual = candidateSettings.candidates.map { binding ->
            val provider = providers.firstOrNull { it.id == binding.providerId }
            val model = provider?.models?.firstOrNull { it.id == binding.modelId && it.isEnabled }
            CandidateModelRowUi(
                binding = binding,
                provider = provider,
                model = model,
                stats = candidateReliability[AgentCandidateReliabilityKey(binding.providerId, binding.modelId)],
            )
        }.sortedWith(compareBy<CandidateModelRowUi> { it.binding.priority }
            .thenBy { it.binding.providerId }
            .thenBy { it.binding.modelId })
        if (!reliabilitySort) manual else AgentCandidateReliability.orderByReliability(
            items = manual,
            keyOf = { AgentCandidateReliabilityKey(it.binding.providerId, it.binding.modelId) },
            priorityOf = { it.binding.priority },
            stats = candidateReliability,
        )
    }
    val candidatePickerProviders = remember(usableProviders, candidateSettings.candidates) {
        usableProviders.mapNotNull { choice ->
            val selectedModelIds = candidateSettings.candidates
                .filter { it.providerId == choice.provider.id }
                .mapTo(mutableSetOf()) { it.modelId }
            choice.models.filter { it.id !in selectedModelIds }
                .takeIf(List<Model>::isNotEmpty)
                ?.let { FallbackProviderChoice(choice.provider, it) }
        }
    }
    val selectedAuxiliary = remember(usableProviders, modelRoleSettings.auxiliaryModelId, modelRoleSettings.auxiliaryProviderId) {
        usableProviders.firstNotNullOfOrNull { choice ->
            if (modelRoleSettings.auxiliaryProviderId != null && choice.provider.id != modelRoleSettings.auxiliaryProviderId) {
                return@firstNotNullOfOrNull null
            }
            choice.models.firstOrNull { it.id == modelRoleSettings.auxiliaryModelId }
                ?.let { FallbackModelSelection(choice.provider, it) }
        }
    }
    fun chooseRole(modelId: String?, providerId: String?): FallbackModelSelection? =
        usableProviders.firstNotNullOfOrNull { choice ->
            if (providerId != null && choice.provider.id != providerId) return@firstNotNullOfOrNull null
            choice.models.firstOrNull { it.id == modelId }
                ?.let { FallbackModelSelection(choice.provider, it) }
        }
    val selectedMemory = remember(usableProviders, modelRoleSettings.memoryModelId, modelRoleSettings.memoryProviderId) {
        chooseRole(modelRoleSettings.memoryModelId, modelRoleSettings.memoryProviderId)
    }
    val selectedLearning = remember(usableProviders, modelRoleSettings.learningModelId, modelRoleSettings.learningProviderId) {
        chooseRole(modelRoleSettings.learningModelId, modelRoleSettings.learningProviderId)
    }
    val selectedTranscript = remember(usableProviders, modelRoleSettings.transcriptModelId, modelRoleSettings.transcriptProviderId) {
        chooseRole(modelRoleSettings.transcriptModelId, modelRoleSettings.transcriptProviderId)
    }
    val effectiveCompaction = remember(usableProviders, modelRoleSettings) {
        RuntimeConfigRepository.resolveCompactionRole(modelRoleSettings, providers)?.let { resolved ->
            FallbackModelSelection(resolved.provider, resolved.model)
        } ?: primaryProvider?.let { provider -> primaryModel?.let { model -> FallbackModelSelection(provider, model) } }
    }
    fun inheritedRole(title: String, selected: FallbackModelSelection?): String = when {
        selected != null -> title
        selectedAuxiliary != null -> "$title · 继承辅助模型"
        else -> "$title · 当前模型"
    }
    val effectiveMemory = selectedMemory ?: selectedAuxiliary ?: primaryProvider?.let { provider -> primaryModel?.let { model -> FallbackModelSelection(provider, model) } }
    val effectiveLearning = selectedLearning ?: selectedAuxiliary ?: primaryProvider?.let { provider -> primaryModel?.let { model -> FallbackModelSelection(provider, model) } }
    val effectiveTranscript = selectedTranscript ?: selectedAuxiliary ?: primaryProvider?.let { provider -> primaryModel?.let { model -> FallbackModelSelection(provider, model) } }
    if (onlineImageSettingsOpen) {
        OnlineImageSettingsPage(onBack = { onlineImageSettingsOpen = false })
        return
    }
    if (rolePickerTarget != null) {
        val target = rolePickerTarget ?: return
        FallbackModelPickerPage(
            providers = usableProviders,
            selected = when (target) {
                ModelRolePickerTarget.AUXILIARY -> selectedAuxiliary
                ModelRolePickerTarget.COMPACTION -> effectiveCompaction
                ModelRolePickerTarget.MEMORY -> effectiveMemory
                ModelRolePickerTarget.LEARNING -> effectiveLearning
                ModelRolePickerTarget.TRANSCRIPT -> effectiveTranscript
            },
            clearTitle = when (target) {
                ModelRolePickerTarget.AUXILIARY -> "清除辅助模型"
                ModelRolePickerTarget.COMPACTION -> "清除压缩模型，恢复继承"
                ModelRolePickerTarget.MEMORY -> "清除记忆模型，恢复继承"
                ModelRolePickerTarget.LEARNING -> "清除自学习模型，恢复继承"
                ModelRolePickerTarget.TRANSCRIPT -> "清除语音整理模型，恢复继承"
            },
            onDismissRequest = { rolePickerTarget = null },
            onSelect = { provider, model ->
                rolePickerTarget = null
                scope.launch {
                    saveRoleSettings(
                        when (target) {
                            ModelRolePickerTarget.AUXILIARY -> modelRoleSettings.copy(
                                auxiliaryProviderId = provider.id,
                                auxiliaryModelId = model.id,
                            )
                            ModelRolePickerTarget.COMPACTION -> modelRoleSettings.copy(
                                compactionProviderId = provider.id,
                                compactionModelId = model.id,
                            )
                            ModelRolePickerTarget.MEMORY -> modelRoleSettings.copy(
                                memoryProviderId = provider.id,
                                memoryModelId = model.id,
                            )
                            ModelRolePickerTarget.LEARNING -> modelRoleSettings.copy(
                                learningProviderId = provider.id,
                                learningModelId = model.id,
                            )
                            ModelRolePickerTarget.TRANSCRIPT -> modelRoleSettings.copy(
                                transcriptProviderId = provider.id,
                                transcriptModelId = model.id,
                            )
                        },
                    )
                }
            },
            onClear = {
                rolePickerTarget = null
                scope.launch {
                    saveRoleSettings(
                        when (target) {
                            ModelRolePickerTarget.AUXILIARY -> modelRoleSettings.copy(
                                auxiliaryProviderId = null,
                                auxiliaryModelId = null,
                            )
                            ModelRolePickerTarget.COMPACTION -> modelRoleSettings.copy(
                                compactionProviderId = null,
                                compactionModelId = null,
                            )
                            ModelRolePickerTarget.MEMORY -> modelRoleSettings.copy(
                                memoryProviderId = null,
                                memoryModelId = null,
                            )
                            ModelRolePickerTarget.LEARNING -> modelRoleSettings.copy(
                                learningProviderId = null,
                                learningModelId = null,
                            )
                            ModelRolePickerTarget.TRANSCRIPT -> modelRoleSettings.copy(
                                transcriptProviderId = null,
                                transcriptModelId = null,
                            )
                        },
                    )
                }
            },
        )
        return
    }
    if (fallbackPickerOpen) {
        FallbackModelPickerPage(
            providers = fallbackProviders,
            selected = selectedFallback,
            clearTitle = "不使用备用模型",
            onDismissRequest = { fallbackPickerOpen = false },
            onSelect = { provider, model ->
                fallbackPickerOpen = false
                scope.launch {
                    RuntimeConfigRepository.setModelFallbackSettings(
                        fallbackSettings.copy(enabled = true, modelId = model.id, providerId = provider.id),
                    )
                }
            },
            onClear = {
                fallbackPickerOpen = false
                scope.launch {
                    RuntimeConfigRepository.setModelFallbackSettings(
                        fallbackSettings.copy(enabled = false, modelId = null, providerId = null),
                    )
                }
            },
        )
        return
    }
    if (candidatePickerOpen) {
        FallbackModelPickerPage(
            providers = candidatePickerProviders,
            selected = null,
            clearTitle = "取消添加候选模型",
            onDismissRequest = { candidatePickerOpen = false },
            onSelect = { provider, model ->
                candidatePickerOpen = false
                val nextPriority = (candidateSettings.candidates.maxOfOrNull { it.priority } ?: -1) + 1
                scope.launch {
                    RuntimeConfigRepository.setModelCandidateSettings(
                        candidateSettings.copy(candidates = candidateSettings.candidates + ModelCandidateBinding(
                            providerId = provider.id,
                            modelId = model.id,
                            purpose = ModelCandidatePurpose.CHAT,
                            priority = nextPriority,
                        )),
                    )
                }
            },
            onClear = { candidatePickerOpen = false },
        )
        return
    }

    candidatePurposeTarget?.let { target ->
        var selectedPurpose by remember(target.binding.providerId, target.binding.modelId, target.binding.purpose) {
            mutableStateOf(target.binding.purpose)
        }
        var requiredTools by remember(target.binding.providerId, target.binding.modelId, target.binding.purpose) {
            mutableStateOf(target.binding.requiredTools)
        }
        var requiredVision by remember(target.binding.providerId, target.binding.modelId, target.binding.purpose) {
            mutableStateOf(target.binding.requiredVision)
        }
        var minimumContextWindowText by remember(target.binding.providerId, target.binding.modelId, target.binding.purpose) {
            mutableStateOf(target.binding.minimumContextWindow?.toString().orEmpty())
        }
        val contextWindowError = candidateContextWindowInputError(minimumContextWindowText)
        WindowDialog(
            show = true,
            title = "候选模型设置",
            onDismissRequest = { candidatePurposeTarget = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .providerDialogScrollableBody()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "${target.provider?.name ?: target.binding.providerId} / ${target.model?.displayName ?: target.binding.modelId}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Text("适用用途", style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        editableCandidatePurposes.forEach { purpose ->
                            EtaChoiceChip(
                                text = purpose.uiLabel(),
                                selected = purpose == selectedPurpose,
                                onClick = { selectedPurpose = purpose },
                            )
                        }
                    }
                    SwitchPreference(
                        title = "要求工具调用",
                        summary = "候选只有声明支持工具时才可被选择",
                        checked = requiredTools,
                        onCheckedChange = { requiredTools = it },
                    )
                    SwitchPreference(
                        title = "要求视觉输入",
                        summary = "包含图片时，候选必须声明支持视觉",
                        checked = requiredVision,
                        onCheckedChange = { requiredVision = it },
                    )
                    TextField(
                        value = minimumContextWindowText,
                        onValueChange = { minimumContextWindowText = it.filter(Char::isDigit) },
                        label = "最低上下文窗口（可选）",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = contextWindowError ?: "留空表示不限制；上限为 10,000,000 tokens。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = if (contextWindowError == null) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                MiuixDialogActions(
                    confirmText = "保存",
                    confirmEnabled = contextWindowError == null,
                    onCancel = { candidatePurposeTarget = null },
                    onConfirm = {
                        if (contextWindowError == null) {
                            val minimumContextWindow = minimumContextWindowText
                                .trim()
                                .takeIf(String::isNotEmpty)
                                ?.toInt()
                            scope.launch {
                                RuntimeConfigRepository.setModelCandidateSettings(
                                    candidateSettings.copy(candidates = candidateSettings.candidates.filterNot { binding ->
                                        binding.providerId == target.binding.providerId &&
                                            binding.modelId == target.binding.modelId &&
                                            binding.purpose in setOf(target.binding.purpose, selectedPurpose)
                                    } + target.binding.copy(
                                        purpose = selectedPurpose,
                                        requiredTools = requiredTools,
                                        requiredVision = requiredVision,
                                        minimumContextWindow = minimumContextWindow,
                                    )),
                                )
                                candidatePurposeTarget = null
                            }
                        }
                    },
                )
            }
        }
        return
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        onBack = onBack,
        listState = when (pageTab) {
            1 -> strategyListState
            2 -> runtimeListState
            else -> providerListState
        },
        aboveContent = {
            NexusSettingsTabs(
                tabs = listOf(
                    stringResource(R.string.settings_tab_providers),
                    "本地模型",
                    "模型策略",
                    stringResource(R.string.settings_tab_runtime),
                ),
                selectedTab = if (pageTab == 0) 0 else pageTab + 1,
                onTabSelected = { index ->
                    if (index == 1) onNavigate(AppRoute.LocalModels)
                    else selectedTab = if (index == 0) 0 else index - 1
                },
            )
        },
    ) {
        if (pageTab == 0) {
            item(key = "search") {
                InputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.ui_search_provider_74e049),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 4.dp),
                )
            }
        }

        if (pageTab == 1) {
            item(key = "model_failover") {
                ProviderSection(title = "运行与容灾") {
                    FailoverModelRow(
                        role = "主模型",
                        provider = primaryProvider,
                        model = primaryModel,
                        fallbackText = primarySummary,
                    )
                    FailoverModelRow(
                        role = "备用模型",
                        provider = selectedFallback?.provider,
                        model = selectedFallback?.model,
                        fallbackText = if (fallbackProviders.isEmpty()) "暂无可用模型" else "选择供应商",
                        enabled = fallbackProviders.isNotEmpty(),
                        onClick = { fallbackPickerOpen = true },
                    )
                    SwitchPreference(
                        title = "故障自动切换",
                        summary = "主模型首次请求失败时使用备用模型",
                        checked = fallbackSettings.enabled,
                        enabled = fallbackSettings.enabled || selectedFallback != null,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                RuntimeConfigRepository.setModelFallbackSettings(
                                    fallbackSettings.copy(
                                        enabled = enabled,
                                        modelId = fallbackSettings.modelId.takeIf { selectedFallback != null },
                                        providerId = fallbackSettings.providerId.takeIf { selectedFallback != null },
                                    ),
                                )
                            }
                        },
                    )
                    if (fallbackSettings.enabled && selectedFallback == null) {
                        Text(
                            text = "备用模型未配置",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            item(key = "candidate_routing") {
                ProviderSection(title = stringResource(R.string.settings_tab_candidate_routing)) {
                    SwitchPreference(
                        title = "主模型优先的智能候选路由",
                        summary = "需要工具、视觉或更长上下文时选择候选模型",
                        checked = candidateSettings.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                RuntimeConfigRepository.setModelCandidateSettings(
                                    candidateSettings.copy(enabled = enabled),
                                )
                            }
                        },
                    )
                    SwitchPreference(
                        title = "按可靠性排序（最近 30 天）",
                        summary = "样本不足时保留手动顺序",
                        checked = reliabilitySort,
                        enabled = candidateRows.isNotEmpty(),
                        onCheckedChange = { reliabilitySort = it },
                    )
                    SwitchPreference(
                        title = "自动任务候选路由",
                        summary = "仅使用支持自动任务的候选模型",
                        checked = automationModelSettings.candidateRoutingEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                RuntimeConfigRepository.setAutomationModelSettings(
                                    automationModelSettings.copy(candidateRoutingEnabled = enabled),
                                )
                            }
                        },
                    )
                    if (candidateRows.isEmpty()) {
                        Text(
                            text = "尚未添加候选模型。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    } else {
                        candidateRows.forEach { row ->
                            CandidateModelRow(
                                row = row,
                                onPurpose = { candidatePurposeTarget = row },
                                onToggle = {
                                    scope.launch {
                                        RuntimeConfigRepository.setModelCandidateSettings(
                                            candidateSettings.copy(candidates = candidateSettings.candidates.map { binding ->
                                                if (binding.providerId == row.binding.providerId &&
                                                    binding.modelId == row.binding.modelId &&
                                                    binding.purpose == row.binding.purpose
                                                ) binding.copy(enabled = !binding.enabled) else binding
                                            }),
                                        )
                                    }
                                },
                                onRemove = {
                                    scope.launch {
                                        RuntimeConfigRepository.setModelCandidateSettings(
                                            candidateSettings.copy(candidates = candidateSettings.candidates.filterNot { binding ->
                                                binding.providerId == row.binding.providerId &&
                                                    binding.modelId == row.binding.modelId &&
                                                    binding.purpose == row.binding.purpose
                                            }),
                                        )
                                    }
                                },
                            )
                        }
                    }
                    FailoverModelRow(
                        role = "添加候选模型",
                        provider = null,
                        model = null,
                        fallbackText = if (candidatePickerProviders.isEmpty()) "当前没有可添加模型" else "从当前可用模型添加",
                        enabled = candidatePickerProviders.isNotEmpty(),
                        onClick = { candidatePickerOpen = true },
                    )
                }
            }
        }

        if (pageTab == 1) {
            item(key = "model_roles") {
                ProviderSection(title = "处理模型") {
                    FailoverModelRow(
                        role = "辅助模型",
                        provider = selectedAuxiliary?.provider,
                        model = selectedAuxiliary?.model,
                        fallbackText = "未指定，由各用途自行选择",
                        onClick = { rolePickerTarget = ModelRolePickerTarget.AUXILIARY },
                    )
                    FailoverModelRow(
                        role = if (modelRoleSettings.compactionModelId != null) {
                            "上下文压缩"
                        } else if (modelRoleSettings.auxiliaryModelId != null) {
                            "上下文压缩 · 继承辅助模型"
                        } else {
                            "上下文压缩 · 当前模型"
                        },
                        provider = effectiveCompaction?.provider,
                        model = effectiveCompaction?.model,
                        fallbackText = primarySummary,
                        onClick = { rolePickerTarget = ModelRolePickerTarget.COMPACTION },
                    )
                    FailoverModelRow(
                        role = inheritedRole("记忆提取", selectedMemory),
                        provider = effectiveMemory?.provider,
                        model = effectiveMemory?.model,
                        fallbackText = primarySummary,
                        onClick = { rolePickerTarget = ModelRolePickerTarget.MEMORY },
                    )
                    FailoverModelRow(
                        role = inheritedRole("自学习", selectedLearning),
                        provider = effectiveLearning?.provider,
                        model = effectiveLearning?.model,
                        fallbackText = primarySummary,
                        onClick = { rolePickerTarget = ModelRolePickerTarget.LEARNING },
                    )
                    FailoverModelRow(
                        role = inheritedRole("语音整理", selectedTranscript),
                        provider = effectiveTranscript?.provider,
                        model = effectiveTranscript?.model,
                        fallbackText = primarySummary,
                        onClick = { rolePickerTarget = ModelRolePickerTarget.TRANSCRIPT },
                    )
                }
            }
        }

        if (pageTab == 2) {
            item(key = "default_runtime") {
                ProviderSection(title = "默认运行") {
                    ProviderRuntimeSwitch(
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        prefKey = Prefs.Keys.AGENT_THINKING_ENABLED,
                    )
                    ProviderRuntimeSwitch(
                        title = stringResource(R.string.settings_auto_compaction),
                        prefKey = Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED,
                    )
                }
            }
        }

        if (pageTab == 0) {
            item(key = "list_heading") {
                SmallTitle(
                    text = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            if (providerLoadState is io.github.mangi.eta.ui.ProviderSettingsLoadState.Loading) {
                item(key = "providers_loading") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Text("正在加载模型服务商…", style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                }
            } else if (providerLoadState is io.github.mangi.eta.ui.ProviderSettingsLoadState.Failed) {
                item(key = "providers_error") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Text("模型服务商加载失败，请稍后重试", style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                }
            } else if (filteredProviders.isEmpty()) {
                item(key = "providers_empty") {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) stringResource(R.string.provider_empty) else stringResource(R.string.provider_no_matches),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                }
            } else {
                items(filteredProviders, key = { "provider-${it.id}" }) { provider ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        ProviderListItem(
                            provider = provider,
                            isSelected = provider.id == selectedProviderId,
                            credentialsReady = providerCredentialState[provider.id] == true,
                            health = connectionHealth[provider.id],
                            onOpen = { onNavigate(AppRoute.ModelProviderDetail(provider.id)) },
                            onDelete = if (!provider.isBuiltIn) ({ providerToDelete = provider }) else null,
                            onSelect = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedProviderId(provider.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
            }
            item(key = "online_image_model") {
                ProviderSection(title = "在线图片模型") {
                    ArrowPreference(title = "文生图接口与模型", summary = "填写图片接口、API Key 和模型，生成结果进入当前绘图或会议成果",
                        onClick = { onlineImageSettingsOpen = true })
                }
            }
            item(key = "create_section") {
                ProviderSection(title = stringResource(R.string.ui_add_new_provider_74df54)) {
                    ArrowPreference(
                        title = "从 models.dev 添加",
                        startAction = {
                            PreferenceIcon(Icons.Rounded.TravelExplore, EtaIconTone.MODEL)
                        },
                        onClick = { onNavigate(AppRoute.CommunityCatalog) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                        startAction = {
                            ProviderBrandIcon(ProviderSourceTypes.OPENAI)
                        },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible)) },
                    )
                    ArrowPreference(
                        title = stringResource(R.string.ui_new_anthropic_db6098),
                        startAction = {
                            ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC)
                        },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Anthropic)) },
                    )
                    ArrowPreference(
                        title = "新增 Gemini",
                        startAction = { ProviderBrandIcon(ProviderSourceTypes.GEMINI) },
                        onClick = { onNavigate(AppRoute.ModelProviderNew(NewProviderType.Gemini)) },
                    )
                }
            }
        }
    }

    if (providerToDelete != null) {
        OverlayDialog(
            show = true,
            title = stringResource(R.string.ui_remove_provider_9f848f),
            summary = stringResource(R.string.provider_delete_summary, providerToDelete?.name.orEmpty()),
            onDismissRequest = { providerToDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onCancel = { providerToDelete = null },
                onConfirm = {
                    scope.launch {
                        providerToDelete?.let { provider ->
                            ProviderRepository.deleteProvider(provider.id)
                            withContext(Dispatchers.IO) {
                                ProviderConnectionHealthStore.clear(context, provider.id)
                            }
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        }
                        providerToDelete = null
                    }
                },
            )
        }
    }

}

@Composable
private fun FailoverModelRow(
    role: String,
    provider: ProviderSetting?,
    model: Model?,
    fallbackText: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val modelLabel = model?.displayName ?: fallbackText
    val providerName = provider?.name?.takeIf { it.isNotBlank() && it != modelLabel }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (provider != null || model != null) {
                ModelBrandMark(
                    modelId = model?.id,
                    sourceType = provider?.sourceType,
                    size = 20.dp,
                )
            } else {
                Icon(
                    Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = role,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = modelLabel,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            providerName?.let { providerName ->
                Text(
                    text = providerName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onClick != null) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "当前模型",
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private data class CandidateModelRowUi(
    val binding: ModelCandidateBinding,
    val provider: ProviderSetting?,
    val model: Model?,
    val stats: AgentCandidateReliabilityStats? = null,
)

@Composable
private fun CandidateModelRow(
    row: CandidateModelRowUi,
    onPurpose: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            ModelBrandMark(
                modelId = row.model?.id ?: row.binding.modelId,
                sourceType = row.provider?.sourceType,
                size = 21.dp,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onPurpose),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.model?.displayName ?: row.binding.modelId,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.provider?.name ?: row.binding.providerId,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.binding.constraintSummary().let { summary ->
                    if (row.binding.enabled) summary else "$summary · 已停用"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = if (row.model == null) MiuixTheme.colorScheme.error
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.stats?.reliabilitySummary() ?: "最近 30 天暂无终态请求",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            state = ToggleableState(row.binding.enabled),
            onClick = onToggle,
        )
        Icon(
            Icons.Rounded.Delete,
            contentDescription = "移除候选模型",
            modifier = Modifier
                .size(34.dp)
                .clickable(onClick = onRemove)
                .padding(8.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

private fun ModelCandidateBinding.constraintSummary(): String {
    val constraints = buildList {
        if (requiredTools) add("工具必需")
        if (requiredVision) add("视觉必需")
        minimumContextWindow?.let { add("上下文 ≥ ${it.compactTokenLabel()}") }
    }
    return "用途：${purpose.uiLabel()} · " +
        constraints.ifEmpty { listOf("无额外能力约束") }.joinToString(" · ")
}

private val editableCandidatePurposes = listOf(
    ModelCandidatePurpose.ALL,
    ModelCandidatePurpose.CHAT,
    ModelCandidatePurpose.SUBAGENT,
    ModelCandidatePurpose.MEETING,
    ModelCandidatePurpose.AUTOMATION,
    ModelCandidatePurpose.AUXILIARY,
    ModelCandidatePurpose.COMPACTION,
)

internal fun candidateContextWindowInputError(value: String): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    val parsed = normalized.toIntOrNull()
    return if (parsed != null && parsed in 1..ModelCandidateBinding.MAX_CONTEXT_WINDOW) {
        null
    } else {
        "最低上下文窗口必须是 1 到 10,000,000 之间的整数"
    }
}

private fun AgentCandidateReliabilityStats.reliabilitySummary(): String {
    if (sampleCount == 0) return "最近 30 天暂无终态请求"
    val details = buildList {
        add("30 天 $sampleCount 次")
        successRate?.let { add("成功率 ${(it * 100).roundToInt()}%") }
        medianTotalMs?.let { add("中位 ${it.latencyLabel()}") }
        priceCoverage?.let { coverage ->
            val priced = "价格覆盖 ${(coverage * 100).roundToInt()}%"
            if (costs.isEmpty()) add(priced) else add(
                "$priced · " + costs.joinToString(" + ") { cost ->
                    "${cost.amount.stripTrailingZeros().toPlainString()} ${cost.currency}"
                },
            )
        }
    }
    val prefix = if (dataSufficient) ""
    else "数据不足（$sampleCount/${AgentCandidateReliability.MINIMUM_SAMPLES}）· "
    return prefix + details.joinToString(" · ")
}

private fun Long.latencyLabel(): String = if (this < 1_000) "${this}ms" else "${(this / 100.0).roundToInt() / 10.0}s"

private fun ModelCandidatePurpose.uiLabel(): String = when (this) {
    ModelCandidatePurpose.ALL -> "全部用途"
    ModelCandidatePurpose.CHAT -> "会话"
    ModelCandidatePurpose.MEETING -> "会议"
    ModelCandidatePurpose.AUTOMATION -> "自动任务"
    ModelCandidatePurpose.AUXILIARY -> "辅助"
    ModelCandidatePurpose.COMPACTION -> "压缩"
    ModelCandidatePurpose.SUBAGENT -> "子 Agent"
}

private fun Int.compactTokenLabel(): String = when {
    this >= 1_000_000 && this % 1_000_000 == 0 -> "${this / 1_000_000}M"
    this >= 1_000 && this % 1_000 == 0 -> "${this / 1_000}K"
    else -> toString()
}

private data class FallbackProviderChoice(
    val provider: ProviderSetting,
    val models: List<Model>,
)

private data class FallbackModelSelection(
    val provider: ProviderSetting,
    val model: Model,
)

private enum class ModelRolePickerTarget { AUXILIARY, COMPACTION, MEMORY, LEARNING, TRANSCRIPT }

@Composable
private fun FallbackModelPickerPage(
    providers: List<FallbackProviderChoice>,
    selected: FallbackModelSelection?,
    clearTitle: String,
    onDismissRequest: () -> Unit,
    onSelect: (ProviderSetting, Model) -> Unit,
    onClear: () -> Unit,
) {
    var selectedProviderId by remember { mutableStateOf<String?>(null) }
    val activeProvider = providers.firstOrNull { it.provider.id == selectedProviderId }

    MiuixScaffoldPage(
        title = if (activeProvider == null) "选择供应商" else "选择模型",
        onBack = {
            if (activeProvider == null) onDismissRequest() else selectedProviderId = null
        },
    ) {
        item(key = "clear") {
            FallbackPickerRow(
                title = clearTitle,
                subtitle = null,
                selected = selected == null,
                modelId = null,
                sourceType = activeProvider?.provider?.sourceType,
                leadingIcon = Icons.Rounded.Block,
                trailingCheck = activeProvider != null,
                trailingChevron = activeProvider == null,
                onClick = onClear,
            )
        }
        if (activeProvider == null) {
            items(providers, key = { "provider:${it.provider.id}" }) { choice ->
                val isSelected = selected?.provider?.id == choice.provider.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .squircleSurface(
                            color = if (isSelected) MiuixTheme.colorScheme.tertiaryContainer
                            else MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            cornerRadius = 14.dp,
                        )
                        .clickable { selectedProviderId = choice.provider.id }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(34.dp).squircleSurface(
                            MiuixTheme.colorScheme.surface,
                            10.dp,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProviderIcon(choice.provider)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            choice.provider.name,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${choice.models.size} 个可用模型",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            item(key = "provider_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier.size(32.dp).squircleSurface(
                            MiuixTheme.colorScheme.secondaryContainer,
                            10.dp,
                        ),
                        contentAlignment = Alignment.Center,
                    ) { ProviderIcon(activeProvider.provider) }
                    Text(
                        activeProvider.provider.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            items(activeProvider.models, key = { "model:${it.id}" }) { model ->
                FallbackPickerRow(
                    title = model.displayName,
                    subtitle = model.id.takeIf { !it.equals(model.displayName, ignoreCase = true) },
                    selected = selected?.provider?.id == activeProvider.provider.id &&
                        selected.model.id == model.id,
                    modelId = model.id,
                    sourceType = activeProvider.provider.sourceType,
                    trailingCheck = true,
                    onClick = { onSelect(activeProvider.provider, model) },
                )
            }
        }
    }
}

@Composable
private fun FallbackPickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    modelId: String?,
    sourceType: String?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    trailingCheck: Boolean = false,
    trailingChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) MiuixTheme.colorScheme.tertiaryContainer
                else MiuixTheme.colorScheme.surface.copy(alpha = 0f),
                cornerRadius = 14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(26.dp).squircleSurface(
                    MiuixTheme.colorScheme.secondaryContainer,
                    8.dp,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else if (modelId != null) {
            ModelBrandMark(modelId = modelId, sourceType = sourceType, size = 22.dp)
        } else {
            Box(
                modifier = Modifier.size(22.dp).squircleSurface(
                    MiuixTheme.colorScheme.secondaryContainer,
                    7.dp,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer
                else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected && trailingCheck) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        } else if (trailingChevron) {
            Text(
                "›",
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isSelected: Boolean,
    credentialsReady: Boolean,
    health: ProviderHealthRecord?,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val activeModelId = provider.models.firstOrNull { it.isEnabled }?.id
    val currentHealth = health?.takeIf { it.modelId.isBlank() || activeModelId != null && it.modelId == activeModelId }
    NexusSettingsSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .graphicsLayer { alpha = opacity },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProviderIcon(provider)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = provider.name,
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isSelected) TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = when {
                            !provider.isEnabled -> stringResource(R.string.ui_disabled_0fe5a9)
                            !credentialsReady -> "待配置"
                            currentHealth?.state == ProviderHealthState.HEALTHY -> "已连接"
                            currentHealth?.state == ProviderHealthState.CATALOG_AVAILABLE -> "目录可用"
                            currentHealth?.state == ProviderHealthState.FAILED -> "故障"
                            else -> "待检查"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = when (currentHealth?.state) {
                            ProviderHealthState.HEALTHY -> EtaIconTone.SYSTEM.accent
                            ProviderHealthState.CATALOG_AVAILABLE -> EtaIconTone.SYSTEM.accent
                            ProviderHealthState.FAILED -> MiuixTheme.colorScheme.error
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        maxLines = 1,
                    )
                    Text("·", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(
                        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            Checkbox(
                state = ToggleableState(isSelected),
                onClick = if (provider.isEnabled) onSelect else null,
                enabled = provider.isEnabled,
            )
        }
    }
}

@Composable
private fun ProviderRuntimeSwitch(title: String, prefKey: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(prefKey) { mutableStateOf(false) }
    val preferences = remember { Prefs.localAgentPreferences() }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[prefKey] ?: true
    var checked by remember(preferences, prefKey) {
        mutableStateOf(preferences?.getBoolean(prefKey, default) ?: default)
    }
    DisposableEffect(preferences, prefKey) {
        val target = preferences ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == prefKey) checked = changed.getBoolean(prefKey, default)
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        checked = checked,
        enabled = preferences != null && !saving,
        onCheckedChange = { enabled ->
            if (preferences == null || saving) return@SwitchPreference
            saving = true
            scope.launch {
                try {
                    io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(context).setRuntimePreferenceFromUi(prefKey, enabled)
                    checked = enabled
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show() }
                finally { saving = false }
            }
        },
    )
}

private fun ProviderSetting.hasUsableCredentials(): Boolean {
    if (io.github.mangi.eta.agent.packages.PackageAiRegistry.isPackageProvider(id))
        return isEnabled && baseUrl != "https://plugin.nexus.invalid" && runCatching {
            io.github.mangi.eta.agent.packages.PackageAiRegistry(io.github.mangi.eta.data.datastore.SettingsDataStore.applicationContext()).available(id)
        }.getOrDefault(false)
    if (!ProviderAuthModes.isOAuth(authMode)) return hasUsableConnection()
    return runCatching {
        ProviderCredentialRepository.account(id, authMode)?.let { account ->
            !account.needsReauthentication && account.planEnabled
        } == true
    }.getOrDefault(false) && isEnabled
}
