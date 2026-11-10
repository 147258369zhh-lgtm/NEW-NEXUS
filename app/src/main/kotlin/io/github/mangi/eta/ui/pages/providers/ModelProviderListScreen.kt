package io.github.mangi.eta.ui.pages.providers

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val fallbackSettings by RuntimeConfigRepository.modelFallbackSettingsFlow()
        .collectAsState(initial = io.github.mangi.eta.data.model.ModelFallbackSettings())
    val providerCredentialState by produceState<Map<String, Boolean>>(emptyMap(), providers) {
        value = withContext(Dispatchers.IO) {
            providers.associate { provider -> provider.id to provider.hasUsableCredentials() }
        }
    }
    var searchQuery by remember { mutableStateOf("") }
    var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
    var fallbackPickerOpen by remember { mutableStateOf(false) }

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
    val fallbackProviders = remember(providers, selectedModelId) {
        providers.filter(ProviderSetting::isEnabled).mapNotNull { provider ->
            provider.models
                .filter { it.isEnabled && it.id != selectedModelId }
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
    if (fallbackPickerOpen) {
        FallbackModelPickerPage(
            providers = fallbackProviders,
            selected = selectedFallback,
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

    MiuixScaffoldPage(title = stringResource(R.string.ui_model_provider_e8c7f5), onBack = onBack) {
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
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 4.dp),
            )
        }

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

        item(key = "local_model_access") {
            ProviderSection(title = "本地模型") {
                ArrowPreference(
                    title = "模型文件",
                    startAction = { PreferenceIcon(Icons.Rounded.Storage, EtaIconTone.MODEL) },
                    onClick = { onNavigate(AppRoute.LocalModels) },
                )
            }
        }

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

        item(key = "list_section") {
            Column {
                SmallTitle(
                    text = pluralStringResource(R.plurals.provider_configured_count, filteredProviders.size, filteredProviders.size),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
                if (filteredProviders.isEmpty()) {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 20.dp)) {
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
                } else {
                    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        filteredProviders.forEach { provider ->
                            ProviderListItem(
                                provider = provider,
                                isSelected = provider.id == selectedProviderId,
                                credentialsReady = providerCredentialState[provider.id] == true,
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
    if (onClick != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaIconTile(Icons.Rounded.SwapHoriz, tone = EtaIconTone.MODEL)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = role,
                    style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.Medium),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = listOfNotNull(modelLabel, providerName).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    BasicComponent(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        startAction = {
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(32.dp)
                    .squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                ModelBrandMark(
                    modelId = model?.id,
                    sourceType = provider?.sourceType,
                    size = 20.dp,
                )
            }
        },
        endActions = {
            TagChip(role, TagChipTone.Emphasized)
        },
    ) {
        Text(
            text = role,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = modelLabel,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
        providerName?.let { providerName ->
            Text(
                text = providerName,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class FallbackProviderChoice(
    val provider: ProviderSetting,
    val models: List<Model>,
)

private data class FallbackModelSelection(
    val provider: ProviderSetting,
    val model: Model,
)

@Composable
private fun FallbackModelPickerPage(
    providers: List<FallbackProviderChoice>,
    selected: FallbackModelSelection?,
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
                title = "不使用备用模型",
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
                        .padding(horizontal = 12.dp, vertical = 3.dp)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
) {
    val opacity = if (provider.isEnabled) 1f else 0.6f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .etaGlassSurface(accent = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondary)
            .combinedClickable(onClick = onOpen, onLongClick = onDelete)
            .graphicsLayer { alpha = opacity },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(38.dp).squircleSurface(MiuixTheme.colorScheme.secondaryContainer, 12.dp),
                contentAlignment = Alignment.Center,
            ) { ProviderIcon(provider) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                Text(
                    text = listOf(provider.typeLabel, provider.baseUrl).filter(String::isNotBlank).joinToString(" · "),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EtaStatusPill(
                        text = when {
                            !provider.isEnabled -> stringResource(R.string.ui_disabled_0fe5a9)
                            credentialsReady -> "可用"
                            else -> "待配置"
                        },
                        tone = if (provider.isEnabled && credentialsReady) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                    )
                    TagChip(pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size))
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
        enabled = preferences != null,
        onCheckedChange = { enabled ->
            val target = preferences ?: return@SwitchPreference
            if (target.edit().putBoolean(prefKey, enabled).commit()) {
                checked = enabled
                Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
            } else {
                Toast.makeText(context, R.string.settings_write_failed, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

private fun ProviderSetting.hasUsableCredentials(): Boolean {
    if (!isEnabled) return false
    if (!ProviderAuthModes.isOAuth(authMode)) return apiKey.isNotBlank()
    return runCatching {
        ProviderCredentialRepository.account(id, authMode)?.let { account ->
            !account.needsReauthentication && account.planEnabled
        } == true
    }.getOrDefault(false)
}
