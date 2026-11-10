package io.github.mangi.eta.ui.screens.subagent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.subagent.SubagentDraftGenerator
import io.github.mangi.eta.agent.subagent.SubagentPermissionCategory
import io.github.mangi.eta.agent.subagent.SubagentPermissionCatalog
import io.github.mangi.eta.agent.subagent.SubagentRoleAuthoring
import io.github.mangi.eta.agent.subagent.SubagentRoleSections
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentProfileBinding
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaInteractiveRow
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.AgentIdentityAvatar
import io.github.mangi.eta.ui.components.DialogChoicePreference
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.etaRippleIndication
import io.github.mangi.eta.ui.components.rememberEtaInteractionSource
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.ToolChip
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.filterGroups
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.pages.providers.TagChipTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class CustomSubagentDraft(
    val originalName: String? = null,
    val name: String = "",
    val toolsText: String = "",
    val role: SubagentRoleSections = SubagentRoleSections(),
    val providerId: String? = null,
    val modelId: String? = null,
    val contextWindow: Int? = null,
    val reasoningEffort: String? = null,
)

private fun SubagentSettings.withProfileBinding(
    profileName: String,
    binding: SubagentProfileBinding?,
): SubagentSettings {
    val key = profileName.trim().lowercase()
    val normalized = binding?.copy(
        providerId = binding.providerId?.trim()?.takeIf(String::isNotEmpty),
        modelId = binding.modelId?.trim()?.takeIf(String::isNotEmpty),
        reasoningEffort = binding.reasoningEffort?.trim()?.takeIf(String::isNotEmpty),
        tools = binding.tools
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct(),
        disallowedTools = binding.disallowedTools
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct(),
        contextWindow = binding.contextWindow?.takeIf { it > 0 },
        maxToolCalls = binding.maxToolCalls?.takeIf { it > 0 },
    )?.takeUnless {
        it.providerId == null &&
            it.modelId == null &&
            it.reasoningEffort == null &&
            it.tools == null &&
            it.disallowedTools.isEmpty() &&
            it.contextWindow == null &&
            it.maxToolCalls == null
    }
    val updated = profiles.toMutableMap()
    if (normalized == null) updated.remove(key) else updated[key] = normalized
    return copy(profiles = updated)
}

private fun SubagentSettings.renamedProfileBinding(
    oldName: String,
    newName: String,
): SubagentSettings {
    val oldKey = oldName.trim().lowercase()
    val binding = profiles[oldKey] ?: return this
    val updated = profiles.toMutableMap()
    updated.remove(oldKey)
    updated[newName.trim().lowercase()] = binding
    return copy(profiles = updated)
}

private fun SubagentProfileBinding?.orEmpty(): SubagentProfileBinding =
    this ?: SubagentProfileBinding()

private val profileContextBudgetOptions = listOf<Int?>(
    null,
    16_000,
    32_000,
    64_000,
    128_000,
    256_000,
    512_000,
    1_000_000,
)

private val profileToolCallBudgetOptions = listOf<Int?>(null, 8, 16, 32, 64)

private fun tokenBudgetLabel(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

private fun subagentDisplayName(name: String): String = when (name) {
    SubagentProfile.TYPE_EXPLORE -> "探索 Agent"
    SubagentProfile.TYPE_CODER -> "工程 Agent"
    SubagentProfile.TYPE_PLAN -> "规划 Agent"
    else -> name
}

private fun subagentRoleIcon(profile: SubagentProfile): ImageVector = when (profile.name) {
    SubagentProfile.TYPE_EXPLORE -> Icons.Rounded.Psychology
    SubagentProfile.TYPE_CODER -> Icons.Rounded.Build
    SubagentProfile.TYPE_PLAN -> Icons.Rounded.Memory
    else -> Icons.Rounded.AutoAwesome
}

@Composable
internal fun SubagentSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val defaultEnabled = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)
    var subagentEnabled by remember(agentPrefs) {
        mutableStateOf(agentPrefs?.getBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, defaultEnabled) ?: defaultEnabled)
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val subagentSettings by RuntimeConfigRepository.subagentSettingsFlow().collectAsState(initial = SubagentSettings())

    var customProfiles by remember { mutableStateOf<List<SubagentProfile>>(emptyList()) }
    fun reloadCustomProfiles() {
        scope.launch {
            customProfiles = withContext(Dispatchers.IO) {
                SubagentProfileCatalog.listCustom()
            }
        }
    }
    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
        reloadCustomProfiles()
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedSecondary = remember(providers, subagentSettings.modelId) {
        subagentSettings.modelId?.takeIf { it.isNotBlank() }?.let { id ->
            providers.enabledModel(id)
        }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val secondarySummary = when {
        resolvedSecondary != null -> "${resolvedSecondary.provider.name} / ${resolvedSecondary.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随主模型 ($primaryModelName)"
        else -> "跟随主模型"
    }

    val effectiveCapabilities = remember(providers, resolvedSecondary, primaryProviderId, primaryModelId) {
        val target = resolvedSecondary ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        val efforts = effectiveCapabilities?.selectableEfforts.orEmpty()
        listOf<ReasoningEffort?>(null) + efforts
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随模型默认"
                ReasoningEffort.DEFAULT -> "Auto"
                else -> effort.displayName
            }
        }
    }
    val selectedEffortIndex = remember(effortOptions, subagentSettings.reasoningEffort) {
        val current = subagentSettings.resolvedReasoningEffort
        effortOptions.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    var showModelSheet by remember { mutableStateOf(false) }
    var modelSheetProfileName by remember { mutableStateOf<String?>(null) }
    var toolPolicyProfileName by remember { mutableStateOf<String?>(null) }
    var inspectedProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    var editorDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    fun editProfile(profile: SubagentProfile) {
        val binding = subagentSettings.bindingFor(profile.name)
        val configuredTools = binding?.tools?.let { configured ->
            if ("*" in configured) SubagentPermissionCatalog.allTools else configured.toSet()
        }
        val toolsText = (configuredTools ?: SubagentPermissionCatalog.defaultTools(profile))
            .sorted()
            .joinToString(", ")
        inspectedProfile = null
        editorError = null
        editorDraft = CustomSubagentDraft(
            originalName = profile.name,
            name = profile.name,
            toolsText = toolsText,
            role = SubagentRoleAuthoring.parseRoleInstruction(profile.roleInstruction).copy(
                description = profile.summary,
                whenToUse = profile.whenToUse,
            ),
            providerId = binding?.providerId,
            modelId = binding?.modelId,
            contextWindow = binding?.contextWindow,
            reasoningEffort = binding?.reasoningEffort,
        )
    }

    fun deleteProfile(profile: SubagentProfile) {
        inspectedProfile = null
        scope.launch {
            withContext(Dispatchers.IO) {
                SubagentProfileCatalog.deleteCustomProfile(profile.name)
            }
            RuntimeConfigRepository.setSubagentSettings(
                subagentSettings.copy(
                    profiles = subagentSettings.profiles - profile.name.lowercase(),
                ),
            )
            reloadCustomProfiles()
        }
    }

    val selectedProfile = inspectedProfile
    BackHandler(enabled = selectedProfile != null) {
        inspectedProfile = null
    }

    MiuixScaffoldPage(
        title = selectedProfile?.let { subagentDisplayName(it.name) } ?: "子 Agent",
        compactTitle = selectedProfile != null,
        onBack = {
            if (selectedProfile != null) inspectedProfile = null else onBack()
        },
        actions = {
            if (selectedProfile == null) {
                IconButton(
                    onClick = {
                        editorError = null
                        editorDraft = CustomSubagentDraft()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "新建子 Agent",
                    )
                }
            } else if (!selectedProfile.builtin) {
                IconButton(onClick = { editProfile(selectedProfile) }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑子 Agent")
                }
            }
        },
        overlay = {
            OverlayBottomSheet(
                show = showModelSheet,
                title = modelSheetProfileName?.let { "${subagentDisplayName(it)} · 模型" }
                    ?: "子 Agent 默认模型",
                onDismissRequest = { showModelSheet = false },
                insideMargin = DpSize(0.dp, 0.dp),
            ) {
                val targetProfileName = modelSheetProfileName
                val targetBinding = targetProfileName?.let(subagentSettings::bindingFor)
                SubagentModelPickerContent(
                    pickerState = pickerState,
                    selectedProviderId = if (targetProfileName == null) {
                        resolvedSecondary?.provider?.id
                    } else {
                        targetBinding?.providerId
                    },
                    selectedSecondaryModelId = if (targetProfileName == null) {
                        subagentSettings.modelId
                    } else {
                        targetBinding?.modelId
                    },
                    primaryModelName = primaryModelName,
                    onSelectModel = { providerId, modelId ->
                        showModelSheet = false
                        scope.launch {
                            val updated = if (targetProfileName == null) {
                                subagentSettings.copy(modelId = modelId)
                            } else {
                                subagentSettings.withProfileBinding(
                                    targetProfileName,
                                    targetBinding.orEmpty().copy(
                                        providerId = providerId,
                                        modelId = modelId,
                                    ),
                                )
                            }
                            RuntimeConfigRepository.setSubagentSettings(updated)
                        }
                    },
                )
            }

            val policyProfileName = toolPolicyProfileName
            val policyBinding = policyProfileName?.let(subagentSettings::bindingFor)
            val policyProfile = policyProfileName?.let(SubagentProfileCatalog::get)
            var selectedPermissionTools by remember(
                policyProfileName,
                policyBinding?.tools,
                policyProfile?.explicitTools,
            ) {
                val configured = policyBinding?.tools
                mutableStateOf(
                    when {
                        configured == null -> policyProfile?.let(SubagentPermissionCatalog::defaultTools).orEmpty()
                        "*" in configured -> SubagentPermissionCatalog.allTools
                        else -> configured.toSet()
                    },
                )
            }
            WindowDialog(
                show = policyProfileName != null,
                title = policyProfileName?.let { "${subagentDisplayName(it)}权限" } ?: "子 Agent 权限",
                onDismissRequest = { toolPolicyProfileName = null },
            ) {
                if (policyProfileName != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "选择此 Agent 可使用的工具",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SubagentPermissionEditor(
                            selectedTools = selectedPermissionTools,
                            onSelectedToolsChange = { selectedPermissionTools = it },
                            maxHeight = 430.dp,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MiuixDialogActions(
                            confirmText = "保存",
                            onCancel = { toolPolicyProfileName = null },
                            onConfirm = {
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.withProfileBinding(
                                            policyProfileName,
                                            policyBinding.orEmpty().copy(
                                                tools = SubagentPermissionCatalog.normalizeSavedTools(
                                                    selectedPermissionTools,
                                                ),
                                            ),
                                        ),
                                    )
                                    toolPolicyProfileName = null
                                }
                            },
                        )
                    }
                }
            }

            val activeDraft = editorDraft
            OverlayBottomSheet(
                show = activeDraft != null,
                title = if (activeDraft?.originalName != null) "编辑子 Agent" else "新建子 Agent",
                onDismissRequest = { editorDraft = null },
                insideMargin = DpSize(0.dp, 0.dp),
            ) {
                activeDraft?.let { draft ->
                    SubagentEditorSheet(
                        draft = draft,
                        error = editorError,
                        availableModels = pickerState.providerGroups.flatMap { it.models },
                        effortOptions = effortOptions,
                        effortLabels = effortLabels,
                        onDraftChange = { editorDraft = it },
                        onError = { editorError = it },
                        onCancel = { editorDraft = null },
                        onSave = { savedDraft ->
                            scope.launch {
                                val tools = savedDraft.toolsText
                                    .split(",")
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val saved = SubagentProfileCatalog.saveCustomProfile(
                                            name = savedDraft.name,
                                            description = savedDraft.role.description,
                                            whenToUse = savedDraft.role.whenToUse,
                                            tools = tools,
                                            prompt = SubagentRoleAuthoring.compose(savedDraft.role),
                                        )
                                        val oldName = savedDraft.originalName
                                        if (oldName != null && oldName != saved.name) {
                                            SubagentProfileCatalog.deleteCustomProfile(oldName)
                                        }
                                        saved to oldName
                                    }
                                }
                                result.fold(
                                    onSuccess = { (saved, oldName) ->
                                        var nextSettings = if (oldName != null && oldName != saved.name) {
                                            subagentSettings.renamedProfileBinding(oldName, saved.name)
                                        } else {
                                            subagentSettings
                                        }
                                        val normalizedTools = SubagentPermissionCatalog.normalizeSavedTools(
                                            tools.toSet(),
                                        )
                                        val previousBinding = nextSettings.profiles[saved.name]
                                        val binding = (previousBinding ?: SubagentProfileBinding()).copy(
                                            providerId = savedDraft.providerId,
                                            modelId = savedDraft.modelId,
                                            reasoningEffort = savedDraft.reasoningEffort,
                                            tools = normalizedTools,
                                            contextWindow = savedDraft.contextWindow,
                                        )
                                        nextSettings = nextSettings.withProfileBinding(saved.name, binding)
                                        RuntimeConfigRepository.setSubagentSettings(nextSettings)
                                        editorDraft = null
                                        editorError = null
                                        reloadCustomProfiles()
                                    },
                                    onFailure = { error ->
                                        editorError = error.message ?: "保存失败"
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
    ) {
        if (selectedProfile != null) {
            item(key = "profile_detail") {
                val profileBinding = subagentSettings.bindingFor(selectedProfile.name)
                val profileTarget = profileBinding?.modelId?.let { id ->
                    providers.enabledModel(id)?.takeIf { target ->
                        profileBinding.providerId.isNullOrBlank() ||
                            target.provider.id == profileBinding.providerId
                    }
                }
                val globalTarget = subagentSettings.modelId?.let(providers::enabledModel)
                val primaryTarget = primaryModelId?.let(providers::enabledModel)
                val effectiveTarget = profileTarget ?: globalTarget ?: primaryTarget
                val profileCapabilities = effectiveTarget?.let { target ->
                    RuntimeConfigRepository.reasoningCapabilities(target.provider, target.model)
                }
                val profileEffortOptions = listOf<ReasoningEffort?>(null) +
                    profileCapabilities?.selectableEfforts.orEmpty()
                val globalEffortLabel = subagentSettings.resolvedReasoningEffort?.displayName
                val profileEffortLabels = profileEffortOptions.map { effort ->
                    when (effort) {
                        null -> globalEffortLabel?.let { "跟随全局（$it）" } ?: "跟随全局"
                        ReasoningEffort.DEFAULT -> "Auto"
                        else -> effort.displayName
                    }
                }
                val profileEffort = profileBinding?.resolvedReasoningEffort
                val profileEffortIndex = profileEffortOptions.indexOf(profileEffort)
                    .takeIf { it >= 0 } ?: 0
                val profileModelSummary = when {
                    profileTarget != null ->
                        "${profileTarget.provider.name} / ${profileTarget.model.displayName}"
                    !profileBinding?.modelId.isNullOrBlank() ->
                        "绑定模型已失效，将回退到全局或主模型"
                    else -> "跟随全局或主模型"
                }
                val contextBudgetIndex = profileContextBudgetOptions
                    .indexOf(profileBinding?.contextWindow)
                    .takeIf { it >= 0 }
                    ?: 0
                val toolCallBudgetIndex = profileToolCallBudgetOptions
                    .indexOf(profileBinding?.maxToolCalls)
                    .takeIf { it >= 0 }
                    ?: 0
                val profileTools = profileBinding?.tools
                val toolPolicySummary = when {
                    profileTools == null -> "跟随角色预设"
                    profileTools == listOf("*") -> "全部非 GUI 工具"
                    profileTools.isEmpty() -> "不允许任何工具"
                    else -> {
                        val selected = profileTools.toSet()
                        val labels = SubagentPermissionCatalog.categories.filter { category ->
                            category.tools.isNotEmpty() && selected.containsAll(category.tools)
                        }.map { it.title }
                        labels.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
                            ?: "自定义 ${profileTools.size} 项工具"
                    }
                } + profileBinding?.disallowedTools
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { " · 禁用 ${it.size} 项" }
                    .orEmpty()
                SubagentProfileDetailContent(
                    profile = selectedProfile,
                    modelSummary = profileModelSummary,
                    effortOptions = profileEffortOptions,
                    effortLabels = profileEffortLabels,
                    selectedEffortIndex = profileEffortIndex,
                    toolPolicySummary = toolPolicySummary,
                    contextBudgetOptions = profileContextBudgetOptions,
                    contextBudgetLabels = profileContextBudgetOptions.map { value ->
                        value?.let { "${tokenBudgetLabel(it)} tokens" } ?: "跟随模型窗口"
                    },
                    selectedContextBudgetIndex = contextBudgetIndex,
                    toolCallBudgetOptions = profileToolCallBudgetOptions,
                    toolCallBudgetLabels = profileToolCallBudgetOptions.map { value ->
                        value?.let { "$it 次" } ?: "不设插件上限"
                    },
                    selectedToolCallBudgetIndex = toolCallBudgetIndex,
                    onConfigureModel = {
                        modelSheetProfileName = selectedProfile.name
                        showModelSheet = true
                    },
                    onConfigureTools = { toolPolicyProfileName = selectedProfile.name },
                    onSelectEffort = { effort ->
                        scope.launch {
                            RuntimeConfigRepository.setSubagentSettings(
                                subagentSettings.withProfileBinding(
                                    selectedProfile.name,
                                    profileBinding.orEmpty().copy(
                                        reasoningEffort = effort?.wireValue,
                                    ),
                                ),
                            )
                        }
                    },
                    onSelectContextBudget = { value ->
                        scope.launch {
                            RuntimeConfigRepository.setSubagentSettings(
                                subagentSettings.withProfileBinding(
                                    selectedProfile.name,
                                    profileBinding.orEmpty().copy(contextWindow = value),
                                ),
                            )
                        }
                    },
                    onSelectToolCallBudget = { value ->
                        scope.launch {
                            RuntimeConfigRepository.setSubagentSettings(
                                subagentSettings.withProfileBinding(
                                    selectedProfile.name,
                                    profileBinding.orEmpty().copy(maxToolCalls = value),
                                ),
                            )
                        }
                    },
                    onEdit = if (!selectedProfile.builtin) {
                        { editProfile(selectedProfile) }
                    } else {
                        null
                    },
                    onDelete = if (!selectedProfile.builtin) {
                        { deleteProfile(selectedProfile) }
                    } else {
                        null
                    },
                )
            }
        } else {
            item(key = "config_group") {
                SmallTitle("调度与默认配置")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .squircleSurface(MiuixTheme.colorScheme.primaryContainer, 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "按角色拆分任务",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "为检索、工程和规划任务选择独立的能力边界。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    SwitchPreference(
                        title = "启用子 Agent",
                        checked = subagentEnabled,
                        enabled = agentPrefs != null,
                        onCheckedChange = { checked ->
                            val targetPrefs = agentPrefs ?: return@SwitchPreference
                            if (targetPrefs.edit().putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, checked).commit()) {
                                subagentEnabled = checked
                                Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                                scope.launch {
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            }
                        },
                    )
                    ArrowPreference(
                        title = "默认运行模型",
                        summary = secondarySummary,
                        enabled = subagentEnabled,
                        onClick = {
                            modelSheetProfileName = null
                            showModelSheet = true
                        },
                    )
                    if (effortOptions.size > 1) {
                        OverlayDropdownPreference(
                            title = "默认思考强度",
                            items = effortLabels,
                            selectedIndex = selectedEffortIndex,
                            enabled = subagentEnabled,
                            onSelectedIndexChange = { index ->
                                val chosen = effortOptions.getOrNull(index)
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(reasoningEffort = chosen?.wireValue),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item(key = "builtin_group") {
                SmallTitle("内置 Agent", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
                        SubagentProfileRow(
                            profile = profile,
                            onClick = { inspectedProfile = profile },
                        )
                    }
                }
            }

            item(key = "custom_group") {
                SmallTitle("自定义 Agent", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    if (customProfiles.isEmpty()) {
                        ListEmptyState(
                            title = "还没有自定义 Agent",
                            summary = "定义职责、权限与运行模型",
                            icon = Icons.Rounded.Extension,
                            action = {
                                TextButton(
                                    text = "新建 Agent",
                                    onClick = {
                                        editorError = null
                                        editorDraft = CustomSubagentDraft()
                                    },
                                )
                            },
                        )
                    } else {
                        customProfiles.forEach { profile ->
                            SubagentProfileRow(
                                profile = profile,
                                onClick = { inspectedProfile = profile },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileRow(
    profile: SubagentProfile,
    onClick: () -> Unit,
) {
    val interactionSource = rememberEtaInteractionSource()
    EtaInteractiveRow(
        interactionSource = interactionSource,
        enabled = true,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = etaRippleIndication(),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        AgentIdentityAvatar(
            displayName = subagentDisplayName(profile.name),
            seed = profile.name,
            size = 42.dp,
            icon = subagentRoleIcon(profile),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = subagentDisplayName(profile.name),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TagChip(
                    text = if (profile.builtin) "内置" else "自定义",
                    tone = if (profile.builtin) TagChipTone.Normal else TagChipTone.Emphasized,
                )
            }
            Text(
                text = profile.summary,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TagChip("${profile.toolHighlights.size} 项能力")
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentPermissionEditor(
    selectedTools: Set<String>,
    onSelectedToolsChange: (Set<String>) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    val selectedCategories = SubagentPermissionCatalog.selectedCategoryIds(selectedTools)
    val partialCategories = SubagentPermissionCatalog.partialCategoryIds(selectedTools)
    val readOnlyTools = SubagentPermissionCatalog.categories
        .filter { it.risk == SubagentPermissionCategory.Risk.READ }
        .flatMapTo(linkedSetOf()) { it.tools }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EtaChoiceChip(
                text = "推荐只读",
                selected = selectedTools == readOnlyTools,
                onClick = { onSelectedToolsChange(readOnlyTools) },
            )
            EtaChoiceChip(
                text = "全部权限",
                selected = selectedTools.containsAll(SubagentPermissionCatalog.allTools),
                onClick = { onSelectedToolsChange(SubagentPermissionCatalog.allTools) },
            )
            EtaChoiceChip(
                text = "清空",
                selected = selectedTools.isEmpty(),
                onClick = { onSelectedToolsChange(emptySet()) },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SubagentPermissionCatalog.categories.forEach { category ->
                val allSelected = category.id in selectedCategories
                val partial = category.id in partialCategories
                val expanded = expandedCategory == category.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(
                            color = when {
                                allSelected -> MiuixTheme.colorScheme.tertiaryContainer
                                else -> MiuixTheme.colorScheme.surfaceContainer
                            },
                            cornerRadius = 14.dp,
                        )
                        .clickable {
                            val next = if (allSelected) {
                                selectedTools - category.tools
                            } else {
                                selectedTools + category.tools
                            }
                            onSelectedToolsChange(next)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            state = when {
                                allSelected -> ToggleableState.On
                                partial -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            onClick = {
                                onSelectedToolsChange(
                                    if (allSelected) selectedTools - category.tools else selectedTools + category.tools,
                                )
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.title,
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (allSelected) {
                                    MiuixTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = category.summary,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = if (expanded) "收起" else "查看",
                            onClick = { expandedCategory = if (expanded) null else category.id },
                        )
                    }
                    Text(
                        text = when (category.risk) {
                            SubagentPermissionCategory.Risk.READ -> "只读 · ${category.tools.size} 项"
                            SubagentPermissionCategory.Risk.WRITE -> "写入 · ${category.tools.size} 项"
                            SubagentPermissionCategory.Risk.DESTRUCTIVE -> "高影响 · ${category.tools.size} 项"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = when (category.risk) {
                            SubagentPermissionCategory.Risk.READ -> MiuixTheme.colorScheme.primary
                            SubagentPermissionCategory.Risk.WRITE -> StatusError.copy(alpha = 0.82f)
                            SubagentPermissionCategory.Risk.DESTRUCTIVE -> StatusError
                        },
                        modifier = Modifier.padding(start = 56.dp),
                    )
                    if (expanded) {
                        FlowRow(
                            modifier = Modifier.padding(start = 52.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            category.tools.sorted().forEach { tool ->
                                EtaChoiceChip(
                                    text = tool,
                                    selected = tool in selectedTools,
                                    onClick = {
                                        onSelectedToolsChange(
                                            if (tool in selectedTools) selectedTools - tool else selectedTools + tool,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "系统始终禁止递归派发、修改子 Agent 自身和独占屏幕操作。选择分类只决定模型可调用的具体工具。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 12.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun SubagentEditorSheet(
    draft: CustomSubagentDraft,
    error: String?,
    availableModels: List<AgentModelOptionUi>,
    effortOptions: List<ReasoningEffort?>,
    effortLabels: List<String>,
    onDraftChange: (CustomSubagentDraft) -> Unit,
    onError: (String?) -> Unit,
    onCancel: () -> Unit,
    onSave: (CustomSubagentDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(draft.originalName) { mutableIntStateOf(0) }
    var reference by rememberSaveable(draft.originalName) { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val editorHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.82f
    val tools = remember(draft.toolsText) {
        draft.toolsText.split(",").map(String::trim).filter(String::isNotEmpty).toSet()
    }
    val modelChoices = listOf<AgentModelOptionUi?>(null) + availableModels
    val modelLabels = listOf("跟随全局 / 主模型") + availableModels.map {
        "${it.providerName} / ${it.displayName}"
    }
    val selectedModelIndex = modelChoices.indexOfFirst {
        it != null && it.providerId == draft.providerId && it.modelId == draft.modelId
    }.takeIf { it >= 0 } ?: 0
    val contextOptions = listOf<Int?>(null, 32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000)
    val contextLabels = listOf("跟随所选模型") + contextOptions.drop(1).mapNotNull { value ->
        value?.let { "${formatContextValue(it)} tokens" }
    }
    val selectedContextIndex = contextOptions.indexOf(draft.contextWindow).takeIf { it >= 0 } ?: 0
    val selectedEffortIndex = effortOptions.indexOfFirst {
        it?.wireValue == draft.reasoningEffort
    }.takeIf { it >= 0 } ?: 0
    val composedRole = remember(draft.role) { SubagentRoleAuthoring.compose(draft.role) }
    val canSave = draft.name.isNotBlank() &&
        draft.role.description.isNotBlank() &&
        composedRole.isNotBlank() &&
        !generating

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(editorHeight)
            .navigationBarsPadding(),
    ) {
        TabRow(
            tabs = listOf("角色", "权限", "运行", "预览"),
            selectedTabIndex = tab,
            onTabSelected = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tab) {
                0 -> {
                    SmallTitle("角色身份", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(EtaUiMetrics.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TextField(
                                value = draft.name,
                                onValueChange = { value ->
                                    onError(null)
                                    onDraftChange(
                                        draft.copy(
                                            name = value.lowercase().filter {
                                                it in 'a'..'z' || it in '0'..'9' || it == '-'
                                            }.take(48),
                                        ),
                                    )
                                },
                                label = "唯一名称",
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SubagentRoleField("一句话简介", draft.role.description, 2) {
                                onDraftChange(draft.copy(role = draft.role.copy(description = it)))
                            }
                            SubagentRoleField("何时应该派发", draft.role.whenToUse, 3) {
                                onDraftChange(draft.copy(role = draft.role.copy(whenToUse = it)))
                            }
                        }
                    }

                    SmallTitle("AI 起草", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(EtaUiMetrics.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                EtaIconTile(Icons.Rounded.AutoAwesome, tone = EtaIconTone.AUTOMATION)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "从任务描述生成",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "自动生成职责、流程与边界",
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                            TextField(
                                value = reference,
                                onValueChange = { reference = it.take(12_000) },
                                label = "粘贴任务描述、选中内容或操作记录",
                                useLabelAsPlaceholder = true,
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextButton(
                                text = if (generating) "生成中" else "生成角色草稿",
                                enabled = !generating && (
                                    reference.isNotBlank() || draft.name.isNotBlank() ||
                                        draft.role.description.isNotBlank() || draft.role.responsibilities.isNotBlank()
                                    ),
                                onClick = {
                                    scope.launch {
                                        generating = true
                                        onError(null)
                                        val seed = reference.takeIf(String::isNotBlank)
                                            ?: listOf(
                                                draft.name,
                                                draft.role.description,
                                                draft.role.whenToUse,
                                                draft.role.responsibilities,
                                                draft.role.workflow,
                                            ).filter(String::isNotBlank).joinToString("\n")
                                        runCatching { SubagentDraftGenerator.generate(seed) }
                                            .onSuccess { generated ->
                                                onDraftChange(
                                                    draft.copy(
                                                        role = generated.copy(
                                                            description = generated.description.takeIf(String::isNotBlank)
                                                                ?: draft.role.description,
                                                            whenToUse = generated.whenToUse.takeIf(String::isNotBlank)
                                                                ?: draft.role.whenToUse,
                                                        ),
                                                    ),
                                                )
                                            }
                                            .onFailure { onError(it.message ?: "生成失败，请检查主模型配置") }
                                        generating = false
                                    }
                                },
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }

                    SmallTitle("角色指令", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    Text(
                        text = "长期行为约束；一次性任务不要写在这里",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 12.dp)
                            .padding(horizontal = 13.dp, vertical = 11.dp),
                    )
                    SubagentRoleField("职责范围", draft.role.responsibilities, 4) {
                        onDraftChange(draft.copy(role = draft.role.copy(responsibilities = it)))
                    }
                    SubagentRoleField("工作流程", draft.role.workflow, 4) {
                        onDraftChange(draft.copy(role = draft.role.copy(workflow = it)))
                    }
                    SubagentRoleField("输出要求", draft.role.outputContract, 3) {
                        onDraftChange(draft.copy(role = draft.role.copy(outputContract = it)))
                    }
                    SubagentRoleField("失败与边界", draft.role.failurePolicy, 3) {
                        onDraftChange(draft.copy(role = draft.role.copy(failurePolicy = it)))
                    }
                    SubagentRoleField("补充角色指令", draft.role.additionalInstructions, 3) {
                        onDraftChange(draft.copy(role = draft.role.copy(additionalInstructions = it)))
                    }
                }
                1 -> {
                    SmallTitle("工具授权", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    SubagentPermissionEditor(
                        selectedTools = tools,
                        onSelectedToolsChange = { selected ->
                            onDraftChange(draft.copy(toolsText = selected.sorted().joinToString(", ")))
                        },
                        maxHeight = editorHeight * 0.58f,
                    )
                }
                2 -> {
                    SmallTitle("独立运行配置", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    DialogChoicePreference(
                        title = "运行模型",
                        value = modelLabels[selectedModelIndex],
                        options = modelLabels,
                        selectedIndex = selectedModelIndex,
                        enabled = availableModels.isNotEmpty(),
                        onSelectedIndexChange = { index ->
                            val selected = modelChoices.getOrNull(index)
                            onDraftChange(
                                draft.copy(
                                    providerId = selected?.providerId,
                                    modelId = selected?.modelId,
                                    contextWindow = draft.contextWindow ?: selected?.contextWindow,
                                ),
                            )
                        },
                    )
                    DialogChoicePreference(
                        title = "上下文预算",
                        value = contextLabels[selectedContextIndex],
                        options = contextLabels,
                        selectedIndex = selectedContextIndex,
                        onSelectedIndexChange = { index ->
                            onDraftChange(draft.copy(contextWindow = contextOptions.getOrNull(index)))
                        },
                    )
                    if (effortOptions.size > 1) {
                        DialogChoicePreference(
                            title = "思考强度",
                            value = effortLabels.getOrElse(selectedEffortIndex) { "跟随全局" },
                            options = effortLabels,
                            selectedIndex = selectedEffortIndex,
                            onSelectedIndexChange = { index ->
                                onDraftChange(draft.copy(reasoningEffort = effortOptions.getOrNull(index)?.wireValue))
                            },
                        )
                    }
                    Text(
                        text = "这里是该子 Agent 的独立配置。未指定模型或上下文时，会跟随全局设置和主模型。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                else -> {
                    SmallTitle("最终提示词")
                    Text(
                        text = composedRole.ifBlank { "完整填写角色定义后，这里会显示实际发送给模型的提示词。" },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 12.dp)
                            .padding(16.dp),
                    )
                    Text(
                        text = "系统还会附加子 Agent 通用边界：只能向主 Agent 交付结果、不能直接询问终端用户、不能递归派发或自我修改。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            error?.let { message ->
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = StatusError,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        MiuixDialogActions(
            confirmText = if (generating) "生成中" else "保存子 Agent",
            cancelText = "取消",
            confirmEnabled = canSave,
            cancelEnabled = !generating,
            onCancel = onCancel,
            onConfirm = { onSave(draft) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun SubagentRoleField(
    label: String,
    value: String,
    minLines: Int,
    onValueChange: (String) -> Unit,
) {
    TextField(
        value = value,
        onValueChange = { onValueChange(it.take(8_000)) },
        label = label,
        useLabelAsPlaceholder = true,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatContextValue(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value % 1_000 == 0 -> "${value / 1_000}K"
    else -> value.toString()
}

@Composable
private fun SubagentModelPickerContent(
    pickerState: io.github.mangi.eta.ui.model.AgentModelPickerUiState,
    selectedProviderId: String?,
    selectedSecondaryModelId: String?,
    primaryModelName: String?,
    onSelectModel: (providerId: String?, modelId: String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val filteredGroups = remember(pickerState.providerGroups, query, providerFilter) {
        pickerState.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
    }
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .navigationBarsPadding(),
    ) {
        InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = "搜索模型或服务商",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (pickerState.providerGroups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "filter:all") {
                    EtaChoiceChip(
                        text = "全部",
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                    )
                }
                items(
                    items = pickerState.providerGroups,
                    key = { "filter:${it.providerId}" },
                ) { group ->
                    EtaChoiceChip(
                        text = group.providerName,
                        selected = providerFilter == group.providerId,
                        onClick = {
                            providerFilter = if (providerFilter == group.providerId) null else group.providerId
                        },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (query.isBlank() && providerFilter == null) {
                item(key = "inherit_primary") {
                    SubagentModelRow(
                        title = "跟随主模型",
                        subtitle = primaryModelName?.let { "当前：$it" } ?: "默认继承会话正在使用的主模型",
                        modelId = pickerState.selectedModel?.modelId,
                        sourceType = pickerState.selectedModel?.providerSourceType,
                        selected = selectedSecondaryModelId == null,
                        onClick = { onSelectModel(null, null) },
                    )
                }
            }
            filteredGroups.forEach { group ->
                if (providerFilter == null) {
                    item(key = "header:${group.providerId}") {
                        Text(
                            text = group.providerName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(
                    items = group.models,
                    key = { model -> "model:${model.providerId}:${model.id}" },
                ) { model ->
                    SubagentModelOptionRow(
                        model = model,
                        selected = model.id == selectedSecondaryModelId &&
                            (selectedProviderId == null || model.providerId == selectedProviderId),
                        onClick = { onSelectModel(model.providerId, model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentModelOptionRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SubagentModelRow(
        title = model.displayName,
        subtitle = model.modelId.takeIf { !it.equals(model.displayName, ignoreCase = true) },
        modelId = model.modelId,
        sourceType = model.providerSourceType,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SubagentModelRow(
    title: String,
    subtitle: String?,
    modelId: String?,
    sourceType: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surface.copy(alpha = 0f)
                },
                cornerRadius = 14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = modelId,
            sourceType = sourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileDetailContent(
    profile: SubagentProfile,
    modelSummary: String,
    effortOptions: List<ReasoningEffort?>,
    effortLabels: List<String>,
    selectedEffortIndex: Int,
    toolPolicySummary: String,
    contextBudgetOptions: List<Int?>,
    contextBudgetLabels: List<String>,
    selectedContextBudgetIndex: Int,
    toolCallBudgetOptions: List<Int?>,
    toolCallBudgetLabels: List<String>,
    selectedToolCallBudgetIndex: Int,
    onConfigureModel: () -> Unit,
    onConfigureTools: () -> Unit,
    onSelectEffort: (ReasoningEffort?) -> Unit,
    onSelectContextBudget: (Int?) -> Unit,
    onSelectToolCallBudget: (Int?) -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val roleSections = remember(profile.roleInstruction) {
        SubagentRoleAuthoring.parseRoleInstruction(profile.roleInstruction)
    }
    val contextLabel = contextBudgetLabels.getOrElse(selectedContextBudgetIndex) { "跟随模型窗口" }
    val toolCallLabel = toolCallBudgetLabels.getOrElse(selectedToolCallBudgetIndex) { "跟随默认" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AgentIdentityAvatar(
                        displayName = subagentDisplayName(profile.name),
                        seed = profile.name,
                        size = 50.dp,
                        icon = subagentRoleIcon(profile),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = subagentDisplayName(profile.name),
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = profile.summary,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TagChip(
                        text = if (profile.builtin) "内置角色" else "自定义角色",
                        tone = TagChipTone.Emphasized,
                    )
                    TagChip(contextLabel)
                    TagChip("单次最多 $toolCallLabel")
                }
            }
        }

        SmallTitle("运行配置", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = EtaUiMetrics.groupGap),
        ) {
            ArrowPreference(
                title = "独立模型",
                summary = modelSummary,
                onClick = onConfigureModel,
            )
            if (effortOptions.size > 1) {
                DialogChoicePreference(
                    title = "思考强度",
                    value = effortLabels.getOrElse(selectedEffortIndex) { "跟随全局" },
                    options = effortLabels,
                    selectedIndex = selectedEffortIndex,
                    onSelectedIndexChange = { index ->
                        onSelectEffort(effortOptions.getOrNull(index))
                    },
                )
            }
            DialogChoicePreference(
                title = "上下文预算",
                value = contextLabel,
                options = contextBudgetLabels,
                selectedIndex = selectedContextBudgetIndex,
                onSelectedIndexChange = { index ->
                    onSelectContextBudget(contextBudgetOptions.getOrNull(index))
                },
            )
            DialogChoicePreference(
                title = "工具调用上限",
                value = toolCallLabel,
                options = toolCallBudgetLabels,
                selectedIndex = selectedToolCallBudgetIndex,
                onSelectedIndexChange = { index ->
                    onSelectToolCallBudget(toolCallBudgetOptions.getOrNull(index))
                },
            )
        }

        SmallTitle("角色与边界", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = EtaUiMetrics.groupGap),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                if (!profile.builtin && profile.whenToUse.isNotBlank()) {
                    SubagentRoleBlock("何时派发", profile.whenToUse)
                }
                if (!profile.builtin && roleSections.description.isNotBlank()) {
                    SubagentRoleBlock("角色简介", roleSections.description)
                }
                SubagentRoleBlock("职责范围", roleSections.responsibilities)
                SubagentRoleBlock("工作流程", roleSections.workflow)
                SubagentRoleBlock("输出要求", roleSections.outputContract)
                SubagentRoleBlock("失败与边界", roleSections.failurePolicy)
                SubagentRoleBlock("补充指令", roleSections.additionalInstructions)
                if (listOf(
                        roleSections.responsibilities,
                        roleSections.workflow,
                        roleSections.outputContract,
                        roleSections.failurePolicy,
                        roleSections.additionalInstructions,
                    ).all(String::isBlank)
                ) {
                    SubagentRoleBlock("执行规则", profile.roleInstruction)
                }
            }
        }

        SmallTitle("工具能力", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Card(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = EtaUiMetrics.groupGap),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    EtaIconTile(Icons.Rounded.VerifiedUser, tone = EtaIconTone.TOOL)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "权限策略",
                            style = MiuixTheme.textStyles.footnote1,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = toolPolicySummary,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        text = "管理",
                        onClick = onConfigureTools,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    profile.toolHighlights.forEach { toolName ->
                        ToolChip(toolDisplayName(context.resources, toolName))
                    }
                }
            }
        }

        if (onEdit != null || onDelete != null) {
            SmallTitle("管理", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = EtaUiMetrics.groupGap),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (onEdit != null) {
                        TextButton(
                            text = "编辑角色与运行配置",
                            onClick = onEdit,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (onDelete != null) {
                        TextButton(
                            text = "删除这个 Agent",
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(textColor = StatusError),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentRoleBlock(label: String, value: String) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
