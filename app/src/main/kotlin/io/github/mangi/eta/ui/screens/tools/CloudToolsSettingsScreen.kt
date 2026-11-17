package io.github.mangi.eta.ui.screens.tools

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CloudCapabilities
import io.github.mangi.eta.data.model.CloudCapabilityDefinition
import io.github.mangi.eta.data.model.CloudCapabilityRoute
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference as ArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as DropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference

@Composable
internal fun CloudToolsSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context, scope) { CloudToolsSettingsStore(context, scope) }
    val webStore = remember(context, scope) { WebToolsSettingsStore(context, scope) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var operation by rememberSaveable { mutableStateOf(WebToolsCapability.SEARCH.operation) }
    var provider by rememberSaveable { mutableStateOf("deepseek") }
    var leaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val capabilitiesList = rememberLazyListState()
    val providersList = rememberLazyListState()
    val group = cloudCapabilityGroups.indexOfFirst { operation in it.operations }.coerceAtLeast(0)
    val webCapability = WebToolsCapability.entries.firstOrNull { it.operation == operation }
    val editingWeb = tab == 0 && webCapability != null
    val currentSaving = if (editingWeb) webStore.saving else store.saving
    val currentEditable = if (editingWeb) webStore.editable else store.editable
    val currentHasChanges = if (editingWeb) webStore.hasUnsavedChanges else store.hasUnsavedChanges
    fun saveCurrentDraft() {
        // The two settings owners save independently; this action only saves the visible owner.
        if (editingWeb) webStore.save() else store.save()
    }
    fun leave(action: () -> Unit) {
        if (store.saving || webStore.saving) return
        if (store.hasUnsavedChanges || webStore.hasUnsavedChanges) leaveAction = action else action()
    }
    BackHandler(enabled = store.saving || webStore.saving || store.hasUnsavedChanges || webStore.hasUnsavedChanges) {
        leave(onBack)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.cloud_tools_title),
        onBack = { leave(onBack) },
        modifier = Modifier.imePadding(),
        listState = if (tab == 0) capabilitiesList else providersList,
        aboveContent = {
            NexusSettingsTabs(
                tabs = listOf(stringResource(R.string.cloud_tools_tab_capabilities),
                    stringResource(R.string.cloud_tools_tab_providers)),
                selectedTab = tab,
                onTabSelected = { tab = it },
            )
        },
        actions = {
            TextButton(
                text = stringResource(if (currentSaving) R.string.cloud_tools_saving else R.string.cloud_tools_save),
                onClick = ::saveCurrentDraft,
                enabled = currentEditable && currentHasChanges,
            )
        },
        overlay = {
            WindowDialog(
                show = leaveAction != null,
                title = stringResource(R.string.cloud_tools_pending_title),
                onDismissRequest = { leaveAction = null },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.cloud_tools_pending_leave), style = MiuixTheme.textStyles.body2)
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.cloud_tools_discard),
                        cancelText = stringResource(R.string.cloud_tools_keep_editing),
                        onCancel = { leaveAction = null },
                        onConfirm = {
                            val action = leaveAction
                            leaveAction = null
                            action?.invoke()
                        },
                    )
                }
            }
        },
    ) {
        if (tab == 0) {
            item(key = "capability_selection") {
                SmallTitle(stringResource(R.string.cloud_tools_tab_capabilities))
                CloudCard {
                    val groups = cloudCapabilityGroups
                    val chosen = groups[group.coerceIn(groups.indices)]
                    DropdownPreference(
                        title = stringResource(R.string.cloud_tools_group),
                        items = groups.map { stringResource(it.title) },
                        selectedIndex = group.coerceIn(groups.indices),
                        onSelectedIndexChange = { index ->
                            groups.getOrNull(index)?.let {
                                operation = it.operations.first()
                            }
                        },
                    )
                    DropdownPreference(
                        title = stringResource(R.string.cloud_tools_capability),
                        items = chosen.operations.map { cloudCapabilityLabel(it) },
                        selectedIndex = chosen.operations.indexOf(operation).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> chosen.operations.getOrNull(index)?.let { operation = it } },
                    )
                }
            }
        }
        if (editingWeb) {
            item(key = "route-$operation") {
                key(operation) { WebToolsCapabilitySection(webStore, requireNotNull(webCapability)) }
            }
            item(key = "web-save") { WebToolsSaveBlock(webStore, showPendingState = true) }
            return@MiuixScaffoldPage
        }
        if (!store.loaded) {
            item(key = "loading") {
                if (store.message != null) {
                    ListEmptyState(
                        title = store.message.orEmpty(),
                        icon = Icons.Rounded.ErrorOutline,
                        action = {
                            TextButton(stringResource(R.string.cloud_tools_retry), onClick = store::reload,
                                enabled = !store.loading)
                        },
                    )
                } else Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
            return@MiuixScaffoldPage
        }
        item(key = "overview") {
            CloudNote(stringResource(R.string.cloud_tools_upload_note), modifier = Modifier.padding(top = 8.dp))
        }
        if (tab == 0) {
            item(key = "master") {
                CloudCard {
                    SwitchPreference(
                        title = stringResource(R.string.cloud_tools_enable),
                        summary = stringResource(R.string.cloud_tools_enable_summary),
                        checked = store.settings.enabled,
                        enabled = store.editable,
                        onCheckedChange = { store.edit(store.settings.copy(enabled = it)) },
                    )
                }
            }
            val definition = CloudCapabilities.definitions.firstOrNull { it.operation == operation }
                ?: CloudCapabilities.definitions.first()
            item(key = "route-$operation") {
                CloudCapabilitySection(
                    store = store,
                    definition = definition,
                    onProvider = { provider = it; tab = 1 },
                )
            }
            item(key = "existing_settings") {
                SmallTitle(stringResource(R.string.cloud_tools_existing_settings))
                CloudCard {
                    ArrowPreference(
                        title = stringResource(R.string.cloud_tools_speech),
                        summary = stringResource(R.string.cloud_tools_speech_summary),
                        onClick = { leave { onNavigate(AppRoute.SpeechSettings) } },
                    )
                }
            }
        } else {
            item(key = "provider_selector") {
                SmallTitle(stringResource(R.string.cloud_tools_tab_providers))
                CloudCard {
                    DropdownPreference(
                        title = stringResource(R.string.cloud_tools_provider),
                        items = cloudProviders.map { stringResource(providerName(it)) },
                        selectedIndex = cloudProviders.indexOf(provider).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> cloudProviders.getOrNull(index)?.let { provider = it } },
                    )
                    BasicComponent(
                        title = credentialStatus(store, provider),
                        summary = stringResource(R.string.cloud_tools_not_verified),
                    )
                }
            }
            if (store.credentialsError != null) item(key = "credential_error") {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) {
                    Text(store.credentialsError.orEmpty(), style = MiuixTheme.textStyles.footnote2, color = StatusError)
                    TextButton(
                        text = stringResource(if (store.loading) R.string.cloud_tools_loading else R.string.cloud_tools_retry_credentials),
                        onClick = store::retryCredentials,
                        enabled = store.editable,
                    )
                }
            }
            item(key = "provider-$provider") {
                key(provider) { CloudProviderSection(store, provider) }
            }
        }
        item(key = "save") { CloudSaveBlock(store) }
    }
}

@Composable
private fun CloudCapabilitySection(store: CloudToolsSettingsStore, definition: CloudCapabilityDefinition,
    onProvider: (String) -> Unit) {
    val route = store.settings.routes[definition.operation]
        ?: CloudCapabilityRoute(primary = definition.providers.first())
    val primaryOptions = definition.providers
    val backupOptions = listOf("") + definition.providers.filter { it != route.primary }
    val timeoutOptions = (listOf(30, 60, 120, 180, 300, 600) + route.timeoutSeconds).distinct().sorted()
    val sizeOptions = (listOf(1, 5, 8, 10, 20, 32, 64, 100) + route.maxFileMiB).distinct().sorted()
    Column {
        SmallTitle(definition.label)
        CloudCard {
            SwitchPreference(
                title = stringResource(R.string.cloud_tools_capability_enable),
                checked = route.enabled,
                enabled = store.editable,
                onCheckedChange = { store.editRoute(definition.operation, route.copy(enabled = it)) },
            )
            DropdownPreference(
                title = stringResource(R.string.cloud_tools_primary),
                items = primaryOptions.map { stringResource(providerName(it)) },
                selectedIndex = primaryOptions.indexOf(route.primary).coerceAtLeast(0),
                enabled = store.editable,
                onSelectedIndexChange = { index -> primaryOptions.getOrNull(index)?.let { selected ->
                    store.editRoute(definition.operation, route.copy(primary = selected,
                        backup = route.backup.takeUnless { it == selected }.orEmpty()))
                } },
            )
            DropdownPreference(
                title = stringResource(R.string.cloud_tools_backup),
                items = backupOptions.map { stringResource(if (it.isEmpty()) R.string.cloud_tools_backup_none else providerName(it)) },
                selectedIndex = backupOptions.indexOf(route.backup).coerceAtLeast(0),
                enabled = store.editable && primaryOptions.size > 1,
                onSelectedIndexChange = { index -> backupOptions.getOrNull(index)?.let {
                    store.editRoute(definition.operation, route.copy(backup = it))
                } },
            )
            DropdownPreference(
                title = stringResource(R.string.cloud_tools_timeout),
                items = timeoutOptions.map { stringResource(R.string.cloud_tools_seconds, it) },
                selectedIndex = timeoutOptions.indexOf(route.timeoutSeconds),
                enabled = store.editable,
                onSelectedIndexChange = { index -> timeoutOptions.getOrNull(index)?.let {
                    store.editRoute(definition.operation, route.copy(timeoutSeconds = it))
                } },
            )
            DropdownPreference(
                title = stringResource(R.string.cloud_tools_file_limit),
                summary = stringResource(R.string.cloud_tools_file_limit_summary),
                items = sizeOptions.map { "$it MiB" },
                selectedIndex = sizeOptions.indexOf(route.maxFileMiB),
                enabled = store.editable,
                onSelectedIndexChange = { index -> sizeOptions.getOrNull(index)?.let {
                    store.editRoute(definition.operation, route.copy(maxFileMiB = it))
                } },
            )
            ArrowPreference(
                title = stringResource(R.string.cloud_tools_configure_provider, stringResource(providerName(route.primary))),
                summary = credentialStatus(store, route.primary),
                onClick = { onProvider(route.primary) },
            )
        }
        CloudNote(stringResource(capabilityDescription(definition.operation)))
        if (definition.operation == "ocr") CloudNote(stringResource(R.string.cloud_tools_backup_note))
    }
}

@Composable
private fun CloudProviderSection(store: CloudToolsSettingsStore, provider: String) {
    val settings = store.settings
    val credentials = store.credentials
    val secretsEditable = store.editable && store.credentialsReadable
    when (provider) {
        "deepseek" -> {
            CloudCard {
                CloudFields {
                    CloudField(stringResource(R.string.cloud_tools_api_key), credentials.deepSeekApiKey,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(deepSeekApiKey = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_base_url), settings.deepSeek.baseUrl,
                        enabled = store.editable, url = true,
                        onChange = { store.edit(settings.copy(deepSeek = settings.deepSeek.copy(baseUrl = it.trim()))) })
                    CloudField(stringResource(R.string.cloud_tools_model), settings.deepSeek.model,
                        enabled = store.editable,
                        onChange = { store.edit(settings.copy(deepSeek = settings.deepSeek.copy(model = it.trim()))) })
                }
            }
            CloudNote(stringResource(R.string.cloud_tools_deepseek_note))
        }
        "bailian" -> {
            val config = settings.bailian
            var selectedModel by rememberSaveable { mutableIntStateOf(0) }
            val models = listOf(
                CloudModelField(R.string.cloud_tools_model_image, config.imageModel) { config.copy(imageModel = it) },
                CloudModelField(R.string.cloud_tools_model_image_edit, config.imageEditModel) { config.copy(imageEditModel = it) },
                CloudModelField(R.string.cloud_tools_model_video_understand, config.videoUnderstandingModel) { config.copy(videoUnderstandingModel = it) },
                CloudModelField(R.string.cloud_tools_model_video_generate, config.videoGenerationModel) { config.copy(videoGenerationModel = it) },
                CloudModelField(R.string.cloud_tools_model_image_video, config.imageToVideoModel) { config.copy(imageToVideoModel = it) },
                CloudModelField(R.string.cloud_tools_model_embedding, config.embeddingModel) { config.copy(embeddingModel = it) },
                CloudModelField(R.string.cloud_tools_model_rerank, config.rerankModel) { config.copy(rerankModel = it) },
                CloudModelField(R.string.cloud_tools_model_translation, config.translationModel) { config.copy(translationModel = it) },
            )
            CloudCard {
                CloudFields {
                    CloudField(stringResource(R.string.cloud_tools_api_key), credentials.bailianApiKey,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(bailianApiKey = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_workspace), config.workspaceId,
                        enabled = store.editable,
                        onChange = { store.edit(settings.copy(bailian = config.copy(workspaceId = it.trim()))) })
                    CloudField(stringResource(R.string.cloud_tools_region), config.region,
                        enabled = store.editable,
                        onChange = { store.edit(settings.copy(bailian = config.copy(region = it.trim()))) })
                    CloudField(stringResource(R.string.cloud_tools_optional_url), config.baseUrl,
                        enabled = store.editable, url = true,
                        onChange = { store.edit(settings.copy(bailian = config.copy(baseUrl = it.trim()))) })
                }
            }
            CloudNote(stringResource(R.string.cloud_tools_bailian_note))
            SmallTitle(stringResource(R.string.cloud_tools_provider_models))
            CloudCard {
                DropdownPreference(
                    title = stringResource(R.string.cloud_tools_model_usage),
                    items = models.map { stringResource(it.title) },
                    selectedIndex = selectedModel.coerceIn(models.indices),
                    onSelectedIndexChange = { selectedModel = it },
                )
                val model = models[selectedModel.coerceIn(models.indices)]
                CloudFields {
                    CloudField(stringResource(R.string.cloud_tools_model), model.value, enabled = store.editable,
                        onChange = { store.edit(settings.copy(bailian = model.update(it.trim()))) })
                }
            }
        }
        "baidu" -> {
            CloudCard {
                CloudFields {
                    CloudField(stringResource(R.string.cloud_tools_api_key), credentials.baiduApiKey,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(baiduApiKey = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_secret_key), credentials.baiduSecretKey,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(baiduSecretKey = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_base_url), settings.baidu.baseUrl,
                        enabled = store.editable, url = true,
                        onChange = { store.edit(settings.copy(baidu = settings.baidu.copy(baseUrl = it.trim()))) })
                }
            }
            CloudNote(stringResource(R.string.cloud_tools_baidu_note))
        }
        "tencent" -> {
            val config = settings.tencent
            CloudCard {
                CloudFields {
                    CloudField(stringResource(R.string.cloud_tools_secret_id), credentials.tencentSecretId,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(tencentSecretId = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_secret_key), credentials.tencentSecretKey,
                        secret = true, enabled = secretsEditable,
                        onChange = { store.editCredentials(credentials.copy(tencentSecretKey = it.trim())) })
                    CloudField(stringResource(R.string.cloud_tools_region), config.region,
                        enabled = store.editable,
                        onChange = { store.edit(settings.copy(tencent = config.copy(region = it.trim()))) })
                    CloudField(stringResource(R.string.cloud_tools_base_url), config.baseUrl,
                        enabled = store.editable, url = true,
                        onChange = { store.edit(settings.copy(tencent = config.copy(baseUrl = it.trim()))) })
                    CloudField(stringResource(R.string.cloud_tools_biz_type), config.bizType,
                        enabled = store.editable,
                        onChange = { store.edit(settings.copy(tencent = config.copy(bizType = it.trim()))) })
                }
            }
            CloudNote(stringResource(R.string.cloud_tools_tencent_note))
        }
    }
}

@Composable
private fun credentialStatus(store: CloudToolsSettingsStore, provider: String): String = stringResource(when {
    !store.credentialsReadable -> R.string.cloud_tools_credential_unreadable
    store.credentialChanged(provider) -> R.string.cloud_tools_credential_pending
    store.credentials.configured(provider) -> R.string.cloud_tools_credential_saved
    providerCredential(store.credentials, provider).any { it.isNotBlank() } -> R.string.cloud_tools_credential_incomplete
    else -> R.string.cloud_tools_credential_missing
})

@Composable
private fun CloudFields(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun CloudField(label: String, value: String, enabled: Boolean, secret: Boolean = false,
    url: Boolean = false, onChange: (String) -> Unit) {
    var visible by remember(label) { mutableStateOf(false) }
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        enabled = enabled,
        singleLine = true,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = when {
            secret -> KeyboardType.Password
            url -> KeyboardType.Uri
            else -> KeyboardType.Text
        }, autoCorrectEnabled = false),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            {
                IconButton(onClick = { visible = !visible }, enabled = enabled) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.cloud_tools_hide_key else R.string.cloud_tools_show_key))
                }
            }
        } else null,
    )
}

@Composable
private fun CloudCard(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp), content = content)
}

@Composable
private fun CloudNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = modifier.padding(horizontal = 28.dp).padding(bottom = 12.dp))
}

@Composable
private fun CloudSaveBlock(store: CloudToolsSettingsStore) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (store.hasUnsavedChanges) R.string.cloud_tools_pending else R.string.cloud_tools_no_pending),
            style = MiuixTheme.textStyles.footnote2,
            color = if (store.hasUnsavedChanges) StatusWarning else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextButton(
            text = stringResource(if (store.saving) R.string.cloud_tools_saving else R.string.cloud_tools_save),
            onClick = store::save,
            enabled = store.editable && store.hasUnsavedChanges,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
        store.message?.let {
            Text(it, style = MiuixTheme.textStyles.footnote2,
                color = if (store.messageIsError) StatusError else StatusSuccess, textAlign = TextAlign.Center)
        }
    }
}

private val cloudProviders = listOf("deepseek", "bailian", "baidu", "tencent")
private data class CloudCapabilityGroup(@StringRes val title: Int, val operations: List<String>)
private val cloudCapabilityGroups = listOf(
    CloudCapabilityGroup(R.string.web_tools_settings_title, WebToolsCapability.entries.map { it.operation }),
    CloudCapabilityGroup(R.string.cloud_tools_group_documents, listOf("ocr", "file_upload", "file_question", "file_status",
        "file_list", "file_delete", "baidu_ocr", "document_parse", "document_status", "table_recognize")),
    CloudCapabilityGroup(R.string.cloud_tools_group_visual, listOf("vision", "image_generate", "image_edit", "image_status",
        "video_understand", "video_generate", "video_status", "media_tasks")),
    CloudCapabilityGroup(R.string.cloud_tools_group_retrieval, listOf("embedding", "rerank", "translate")),
    CloudCapabilityGroup(R.string.cloud_tools_group_moderation, listOf("moderate")),
)

@Composable
private fun cloudCapabilityLabel(operation: String): String {
    val webCapability = WebToolsCapability.entries.firstOrNull { it.operation == operation }
    return if (webCapability != null) stringResource(webCapability.title)
    else CloudCapabilities.definitions.firstOrNull { it.operation == operation }?.label.orEmpty()
}

private data class CloudModelField(@StringRes val title: Int, val value: String,
    val update: (String) -> io.github.mangi.eta.data.model.CloudBailianConfig)

@StringRes
private fun providerName(provider: String): Int = when (provider) {
    "bailian" -> R.string.cloud_tools_provider_bailian
    "baidu" -> R.string.cloud_tools_provider_baidu
    "tencent" -> R.string.cloud_tools_provider_tencent
    else -> R.string.cloud_tools_provider_deepseek
}

@StringRes
private fun capabilityDescription(operation: String): Int = when (operation) {
    "ocr", "baidu_ocr" -> R.string.cloud_tools_description_ocr
    "file_upload" -> R.string.cloud_tools_description_upload
    "file_question" -> R.string.cloud_tools_description_question
    "file_delete" -> R.string.cloud_tools_description_delete
    "file_list", "file_status" -> R.string.cloud_tools_description_files
    "document_parse", "document_status", "table_recognize" -> R.string.cloud_tools_description_parse
    "vision", "video_understand" -> R.string.cloud_tools_description_understand
    "image_generate", "image_edit", "image_status" -> R.string.cloud_tools_description_image
    "video_generate", "video_status" -> R.string.cloud_tools_description_video
    "media_tasks" -> R.string.cloud_tools_description_tasks
    "embedding", "rerank" -> R.string.cloud_tools_description_retrieval
    "translate" -> R.string.cloud_tools_description_translation
    else -> R.string.cloud_tools_description_moderation
}
