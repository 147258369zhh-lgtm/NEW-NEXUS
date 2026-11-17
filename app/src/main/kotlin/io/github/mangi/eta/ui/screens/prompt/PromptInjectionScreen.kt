package io.github.mangi.eta.ui.screens.prompt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptBlock
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.PromptIssueLevel
import io.github.mangi.eta.agent.prompt.PromptScopeType
import io.github.mangi.eta.agent.prompt.PromptTarget
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.pages.providers.TagChip
import io.github.mangi.eta.ui.screens.skills.BoundaryToolsScreen
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import org.json.JSONObject
import java.util.UUID

private const val TAB_TEMPLATES = 0
private const val TAB_BINDINGS = 1
private const val TAB_TRANSFER = 2
private const val TAB_GOVERNANCE = 3

private sealed interface PromptPage {
    data object Library : PromptPage
    data class Editor(val template: PromptTemplate) : PromptPage
    data class Targets(val type: PromptScopeType) : PromptPage
    data class Picker(val target: PromptTarget) : PromptPage
    data object ExportPicker : PromptPage
    data object Boundary : PromptPage
    data object Audit : PromptPage
    data object Preview : PromptPage
}

private data class PromptLibraryViewport(
    val tab: Int,
    val index: Int,
    val offset: Int,
    val firstKey: Any?,
)

private val PromptPageSaver = Saver<MutableState<PromptPage>, String>(
    save = { state -> promptPageToWire(state.value) },
    restore = { raw -> mutableStateOf(promptPageFromWire(raw)) },
)

private fun promptPageToWire(page: PromptPage): String = when (page) {
    PromptPage.Library -> JSONObject().put("page", "library").toString()
    is PromptPage.Editor -> {
        val persisted = PromptInjectionStore.template(page.template.id)
        if (persisted != null) {
            JSONObject().put("page", "editor").put("template_id", page.template.id).toString()
        } else {
            JSONObject().put("page", "editor").put("template", page.template.toJson()).toString()
        }
    }
    is PromptPage.Targets -> JSONObject()
        .put("page", "targets")
        .put("type", page.type.wireValue)
        .toString()
    is PromptPage.Picker -> JSONObject()
        .put("page", "picker")
        .put("target", page.target.toJson())
        .toString()
    PromptPage.ExportPicker -> JSONObject().put("page", "export").toString()
    PromptPage.Boundary -> JSONObject().put("page", "boundary").toString()
    PromptPage.Audit -> JSONObject().put("page", "audit").toString()
    PromptPage.Preview -> JSONObject().put("page", "preview").toString()
}

private fun promptPageFromWire(raw: String): PromptPage = runCatching {
    val json = JSONObject(raw)
    when (json.optString("page")) {
        "editor" -> {
            val template = json.optString("template_id").takeIf(String::isNotBlank)
                ?.let(PromptInjectionStore::template)
                ?: json.optJSONObject("template")?.let(PromptTemplate::fromJson)
            template?.let(PromptPage::Editor) ?: PromptPage.Library
        }
        "targets" -> PromptScopeType.fromWire(json.optString("type"))
            ?.let(PromptPage::Targets)
            ?: PromptPage.Library
        "picker" -> json.optJSONObject("target")
            ?.let(PromptTarget::fromJson)
            ?.let(PromptPage::Picker)
            ?: PromptPage.Library
        "export" -> PromptPage.ExportPicker
        "boundary" -> PromptPage.Boundary
        "audit" -> PromptPage.Audit
        "preview" -> PromptPage.Preview
        else -> PromptPage.Library
    }
}.getOrDefault(PromptPage.Library)

@Composable
internal fun PromptInjectionScreen(
    onOpenCharacters: (() -> Unit)? = null,
    storeOverride: PromptInjectionUiStore? = null,
    onLibraryState: ((LazyListState) -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ownedStore = remember(context.applicationContext) {
        PromptInjectionUiStore(context.applicationContext)
    }
    val store = storeOverride ?: ownedStore
    var tab by rememberSaveable { mutableIntStateOf(TAB_TEMPLATES) }
    var page by rememberSaveable(saver = PromptPageSaver) { mutableStateOf<PromptPage>(PromptPage.Library) }
    // Secondary pages replace the library composition; keep its scroll owners alive here.
    val templateListState = rememberLazyListState()
    val bindingsListState = rememberLazyListState()
    val transferListState = rememberLazyListState()
    val governanceListState = rememberLazyListState()
    val libraryStates = listOf(templateListState, bindingsListState, transferListState, governanceListState)
    val libraryStateHolder = rememberSaveableStateHolder()
    var returnViewport by remember { mutableStateOf<PromptLibraryViewport?>(null) }
    val openFromLibrary: (PromptPage) -> Unit = { destination ->
        val currentState = libraryStates[tab]
        returnViewport = PromptLibraryViewport(tab, currentState.firstVisibleItemIndex,
            currentState.firstVisibleItemScrollOffset, currentState.layoutInfo.visibleItemsInfo.firstOrNull()?.key)
        page = destination
    }
    // A newly attached LazyColumn can clamp its retained offset during its first measurement.
    // Reapply the captured viewport once the library has attached and its first layout has run.
    LaunchedEffect(page, returnViewport) {
        val viewport = returnViewport
        if (page == PromptPage.Library && viewport != null) {
            withFrameNanos { }
            val restoredState = libraryStates[viewport.tab]
            val anchor = restoredState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == viewport.firstKey }
            restoredState.scrollToItem(anchor?.index ?: viewport.index, viewport.offset)
            returnViewport = null
        }
    }
    SideEffect { onLibraryState?.invoke(libraryStates[tab]) }
    LaunchedEffect(store) { store.refresh() }
    val finishPicker: (PromptTarget) -> Unit = { target ->
        if (target.type == PromptScopeType.GLOBAL) {
            tab = TAB_BINDINGS
            page = PromptPage.Library
        } else {
            page = PromptPage.Targets(target.type)
        }
    }

    when (val current = page) {
        PromptPage.Library -> libraryStateHolder.SaveableStateProvider("prompt-library") { PromptLibraryScaffold(
            store = store,
            tab = tab,
            listState = when (tab) {
                TAB_BINDINGS -> bindingsListState
                TAB_TRANSFER -> transferListState
                TAB_GOVERNANCE -> governanceListState
                else -> templateListState
            },
            onTabChange = { tab = it },
            onBack = onBack,
            onCreate = { openFromLibrary(PromptPage.Editor(newPromptTemplate())) },
            onEdit = { openFromLibrary(PromptPage.Editor(it)) },
            onOpenTargets = { openFromLibrary(PromptPage.Targets(it)) },
            onOpenGlobal = { openFromLibrary(PromptPage.Picker(PromptTarget.GLOBAL)) },
            onOpenExportPicker = { openFromLibrary(PromptPage.ExportPicker) },
            onOpenBoundary = { openFromLibrary(PromptPage.Boundary) },
            onOpenAudit = { openFromLibrary(PromptPage.Audit) },
            onOpenPreview = { openFromLibrary(PromptPage.Preview) },
            onOpenCharacters = onOpenCharacters,
        ) }
        PromptPage.Boundary -> BoundaryToolsScreen(onBack = { page = PromptPage.Library })
        PromptPage.Audit -> PromptAssemblyAuditScreen(store,
            onEdit = { page = PromptPage.Editor(it) }, onBack = { page = PromptPage.Library })
        PromptPage.Preview -> PromptAssemblyPreviewScreen(store, onBack = { page = PromptPage.Library })
        is PromptPage.Editor -> {
            PromptTemplateEditorScreen(
                template = current.template,
                store = store,
                onBack = { page = PromptPage.Library },
                onSaved = {
                    tab = TAB_TEMPLATES
                    page = PromptPage.Library
                },
            )
        }
        is PromptPage.Targets -> {
            PromptScopeTargetsScreen(
                type = current.type,
                store = store,
                onBack = { page = PromptPage.Library },
                onPick = { target -> page = PromptPage.Picker(target) },
            )
        }
        is PromptPage.Picker -> {
            PromptScopePickerScreen(
                target = current.target,
                store = store,
                onBack = { finishPicker(current.target) },
                onBound = { finishPicker(current.target) },
            )
        }
        PromptPage.ExportPicker -> {
            PromptExportPickerScreen(
                store = store,
                onBack = { page = PromptPage.Library },
                onExported = { page = PromptPage.Library },
            )
        }
    }
}

@Composable
private fun PromptLibraryScaffold(
    store: PromptInjectionUiStore,
    tab: Int,
    listState: LazyListState,
    onTabChange: (Int) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (PromptTemplate) -> Unit,
    onOpenTargets: (PromptScopeType) -> Unit,
    onOpenGlobal: () -> Unit,
    onOpenExportPicker: () -> Unit,
    onOpenBoundary: () -> Unit,
    onOpenAudit: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenCharacters: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val transferState = remember { PromptTransferPanelState() }
    var deleteTarget by remember { mutableStateOf<PromptTemplate?>(null) }
    val duplicateSuccess = stringResource(R.string.prompt_injection_duplicate_success)
    val deleteSuccess = stringResource(R.string.prompt_injection_delete_success)
    val saveFailed = stringResource(R.string.prompt_injection_save_failed)
    val deleteFailed = stringResource(R.string.prompt_injection_delete_failed)

    MiuixScaffoldPage(
        title = "角色与指令",
        onBack = onBack,
        listState = listState,
        aboveContent = {
            NexusSettingsTabs(
                tabs = listOf(
                    stringResource(R.string.prompt_injection_tab_templates),
                    stringResource(R.string.prompt_injection_tab_bindings),
                    "导入与导出",
                    stringResource(R.string.settings_tab_governance),
                ),
                selectedTab = tab,
                onTabSelected = onTabChange,
            )
        },
        actions = {
            if (tab == TAB_TEMPLATES) {
                IconButton(onClick = onCreate) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.prompt_injection_new))
                }
            }
        },
    ) {
        item(key = "prompt_usage_guide_$tab") {
            PromptUsageGuide(tab)
        }
        if (tab == TAB_GOVERNANCE) item(key = "behavior_modules") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                DetailListRow(title = "真实工具边界", subtitle = "在此编辑全局结构化权限；与本轮、Agent 及审批策略取交集",
                    icon = Icons.Rounded.Public, onClick = onOpenBoundary)
                DetailListRow(title = "本轮生效记录", subtitle = "查看实际请求的来源、阶段、位置、继承、预算省略与公开工具",
                    icon = Icons.Rounded.Description, onClick = onOpenAudit)
                DetailListRow(title = "组装预览", subtitle = "选择模型、范围对象与阶段，查看完整片段及省略原因，不调用模型",
                    icon = Icons.Rounded.Description, onClick = onOpenPreview)
            }
        }
        if (tab == TAB_TEMPLATES) onOpenCharacters?.let { open ->
            item(key = "character_module") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    DetailListRow(title = "剧情人物与背景", subtitle = "角色卡 · 人设 · 世界书 · 剧情记忆",
                        icon = Icons.Rounded.Description, onClick = open)
                }
            }
        }
        if (store.status != null) {
            item(key = "prompt_status") {
                PromptInlineStatus(
                    text = store.status.orEmpty(),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (tab == TAB_TEMPLATES) item(key = "prompt_library_hero") {
            PromptLibraryHero(store = store, onCreate = onCreate)
        }
        when (tab) {
            TAB_TEMPLATES -> templateLibraryItems(
                store = store,
                query = query,
                onQueryChange = { query = it },
                onCreate = onCreate,
                onEdit = onEdit,
                onDuplicate = { template ->
                    scope.launch {
                        val duplicate = duplicateTemplate(template, store.config.templates)
                        store.report(if (store.saveTemplate(duplicate)) duplicateSuccess else saveFailed)
                    }
                },
                onToggle = { template, enabled ->
                    scope.launch {
                        store.report(
                            if (store.saveTemplate(template.copy(enabled = enabled))) null else saveFailed,
                        )
                    }
                },
                onDelete = { deleteTarget = it },
            )
            TAB_BINDINGS -> bindingOverviewItems(
                store = store,
                onOpenTargets = onOpenTargets,
                onOpenGlobal = onOpenGlobal,
            )
            TAB_TRANSFER -> item(key = "prompt_transfer") {
                PromptTransferPanel(
                    store = store,
                    state = transferState,
                    scope = scope,
                    onOpenExportPicker = onOpenExportPicker,
                )
            }
            TAB_GOVERNANCE -> governanceItems(store)
        }
    }

    deleteTarget?.let { template ->
        val bindingCount = store.bindingsFor(template.id).size
        WindowDialog(
            show = true,
            title = stringResource(R.string.prompt_injection_delete_title),
            summary = stringResource(
                R.string.prompt_injection_delete_body,
                template.name.ifBlank { stringResource(R.string.prompt_injection_untitled_template) },
                bindingCount,
            ),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.prompt_injection_delete),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    scope.launch {
                        store.report(if (store.deleteTemplate(template.id)) deleteSuccess else deleteFailed)
                    }
                },
            )
        }
    }
}

@Composable
private fun PromptUsageGuide(tab: Int) {
    val guide = when (tab) {
        TAB_BINDINGS -> Triple("让指令在正确的位置生效", "选择范围 → 绑定方案 → 选择继承方式",
            "全局用于通用偏好，项目与会话用于具体任务。指令块可指定普通对话、会议阶段或子 Agent；替换继承前可先到治理查看组装预览。")
        TAB_TRANSFER -> Triple("备份与交换方案", "导入先检查 → 选择保留内容 → 导出所需方案",
            "这里管理指令方案的导入、导出与旧配置迁移。剧情角色卡的 PNG/JSON、世界书和人物资料仍在“剧情人物与背景”内管理。")
        TAB_GOVERNANCE -> Triple("核对实际生效与权限", "组装预览 → 本轮记录 → 结构检查",
            "预览使用实际组装规则，不调用模型；本轮记录反映真实请求的来源、阶段和预算。文字指令不能扩大工具权限，权限单独在真实工具边界中设置。")
        else -> Triple("先建立可复用的行为方案", "创建方案 → 按阶段写指令 → 到应用范围绑定",
            "身份、职责、工作规则与阶段指令集中在方案中。剧情角色是可选模块；启用方案后还需绑定范围才会使用，具体纳入情况可在治理核对。")
    }
    var expanded by rememberSaveable(tab) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(guide.first, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            Text(guide.second, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
            androidx.compose.animation.AnimatedVisibility(expanded) {
                Text(guide.third, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton(text = if (expanded) "收起用法" else "查看用法", onClick = { expanded = !expanded })
        }
    }
}

private fun LazyListScope.templateLibraryItems(
    store: PromptInjectionUiStore,
    query: String,
    onQueryChange: (String) -> Unit,
    onCreate: () -> Unit,
    onEdit: (PromptTemplate) -> Unit,
    onDuplicate: (PromptTemplate) -> Unit,
    onToggle: (PromptTemplate, Boolean) -> Unit,
    onDelete: (PromptTemplate) -> Unit,
) {
    val templates = store.config.templates
        .filter { template ->
            query.isBlank() || template.name.contains(query, ignoreCase = true) ||
                template.description.contains(query, ignoreCase = true)
        }
        .sortedWith(compareByDescending<PromptTemplate> { it.updatedAt })

    if (store.config.templates.isNotEmpty() || query.isNotBlank()) {
        item(key = "prompt_search") {
            TextField(
                value = query,
                onValueChange = { onQueryChange(it.take(120)) },
                label = stringResource(R.string.prompt_injection_search_templates),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
    if (templates.isNotEmpty()) {
        templates.forEach { template ->
            item(key = "prompt_template_${template.id}") {
                PromptTemplateCard(
                    template = template,
                    boundLabels = store.bindingsFor(template.id).map { binding ->
                        promptTargetLabel(store, binding.target, template.name)
                    },
                    onToggle = { enabled -> onToggle(template, enabled) },
                    onEdit = { onEdit(template) },
                    onDuplicate = { onDuplicate(template) },
                    onDelete = { onDelete(template) },
                )
            }
        }
    } else if (store.loading) {
        item(key = "prompt_loading") {
            PromptInlineStatus(
                text = stringResource(R.string.prompt_injection_loading),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    } else {
        item(key = "prompt_empty") {
            PromptEmptyState(
                title = if (query.isBlank()) {
                    stringResource(R.string.prompt_injection_empty_title)
                } else {
                    stringResource(R.string.prompt_injection_search_empty)
                },
                summary = stringResource(R.string.prompt_injection_empty_summary),
                icon = Icons.Rounded.Description,
                action = if (query.isBlank()) {
                    {
                        TextButton(
                            text = stringResource(R.string.prompt_injection_new),
                            onClick = onCreate,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun PromptLibraryHero(store: PromptInjectionUiStore, onCreate: () -> Unit) {
    val report = store.analyzeLibrary()
    val enabled = store.config.templates.count { it.enabled }
    val issues = report.errors.size + report.warnings.size
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .etaGlassSurface(accent = MiuixTheme.colorScheme.primary, cornerRadius = 20.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EtaIconTile(
                    icon = Icons.Rounded.Description,
                    tone = EtaIconTone.AUTOMATION,
                    size = 42.dp,
                    glyphSize = 22.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "行为方案",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "集中配置身份、职责与规则，按范围和阶段应用",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                EtaStatusPill(
                    text = when {
                        report.errors.isNotEmpty() -> "需处理"
                        issues > 0 -> "有提示"
                        else -> "已就绪"
                    },
                    tone = when {
                        report.errors.isNotEmpty() -> EtaStatusTone.Danger
                        issues > 0 -> EtaStatusTone.Warning
                        else -> EtaStatusTone.Positive
                    },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagChip("$enabled 个启用模板")
                TagChip("${store.boundScopeCount()} 个作用域")
                TagChip(if (issues == 0) "结构检查通过" else "$issues 项检查")
            }
            if (store.config.templates.isEmpty()) {
                TextButton(text = "新建第一条指令", onClick = onCreate)
            }
        }
    }
}

@Composable
private fun PromptOverviewCard(store: PromptInjectionUiStore) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = colors.secondaryContainer.copy(alpha = 0.32f),
            contentColor = colors.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptIconBadge(Icons.Rounded.Description)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.prompt_injection_overview_title),
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.prompt_injection_overview_note),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(colors.surfaceContainer.copy(alpha = 0.7f), 12.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PromptStat(
                    value = store.config.templates.size.toString(),
                    label = stringResource(R.string.prompt_injection_stat_templates),
                )
                PromptStat(
                    value = store.config.templates.count(PromptTemplate::enabled).toString(),
                    label = stringResource(R.string.prompt_injection_stat_enabled),
                )
                PromptStat(
                    value = store.boundScopeCount().toString(),
                    label = stringResource(R.string.prompt_injection_stat_scopes),
                )
                PromptStat(
                    value = promptCount(store.enabledCharacterCount()),
                    label = stringResource(R.string.prompt_injection_stat_chars),
                )
            }
        }
    }
}

@Composable
private fun PromptStat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun PromptTemplateCard(
    template: PromptTemplate,
    boundLabels: List<String>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .etaGlassSurface(
                accent = if (template.enabled) colors.primary else colors.outline,
                cornerRadius = 18.dp,
            ),
        colors = CardDefaults.defaultColors(
            color = if (template.enabled) {
                colors.secondaryContainer.copy(alpha = 0.42f)
            } else {
                colors.surfaceContainer.copy(alpha = 0.74f)
            },
            contentColor = colors.onSurface,
        ),
        onClick = onEdit,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PromptIconBadge(Icons.Rounded.Description)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = template.name.ifBlank {
                            stringResource(R.string.prompt_injection_untitled_template)
                        },
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = template.description.ifBlank {
                            stringResource(R.string.prompt_injection_no_description)
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = template.enabled, onCheckedChange = onToggle)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(colors.surfaceContainer.copy(alpha = 0.64f), 11.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PromptPill(
                        text = stringResource(
                            if (template.enabled) R.string.prompt_injection_enabled else R.string.prompt_injection_disabled,
                        ),
                        tone = if (template.enabled) PromptPillTone.Success else PromptPillTone.Neutral,
                    )
                    PromptPill(stringResource(R.string.prompt_injection_block_count, template.blocks.size))
                    PromptPill(stringResource(R.string.prompt_injection_char_count, promptCount(template.characterCount)))
                    PromptPill(
                        text = stringResource(R.string.prompt_injection_binding_count, boundLabels.size),
                        tone = if (boundLabels.isEmpty()) PromptPillTone.Neutral else PromptPillTone.Accent,
                    )
                }
                if (boundLabels.isNotEmpty()) {
                    PromptMetaRow(items = boundLabels.take(2) + if (boundLabels.size > 2) {
                        listOf(stringResource(R.string.prompt_injection_more_scopes, boundLabels.size - 2))
                    } else {
                        emptyList()
                    })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.prompt_injection_edit))
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.prompt_injection_duplicate))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.prompt_injection_delete),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun LazyListScope.governanceItems(store: PromptInjectionUiStore) {
    val report = store.analyzeLibrary()
    item(key = "prompt_governance_overview") {
        SmallTitle("指令治理")
        CardMaterialMetric(
            title = "运行装配检查",
            value = when {
                report.errors.isNotEmpty() -> "${report.errors.size} 个阻断问题"
                report.warnings.isNotEmpty() -> "${report.warnings.size} 个风险提示"
                else -> "结构正常"
            },
            summary = "检查空模板、重复绑定、失效绑定、权限绕过措辞和上下文预算风险。",
            warning = report.errors.isNotEmpty() || report.warnings.isNotEmpty(),
        )
    }
    item(key = "prompt_governance_issues") {
        SmallTitle("检查结果")
        Card(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                .etaGlassSurface(accent = MiuixTheme.colorScheme.primary, cornerRadius = 16.dp),
            colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
        ) {
            val visible = report.issues
                .sortedBy { it.level.ordinal }
                .take(8)
            if (visible.isEmpty()) {
                DetailListRow(
                    title = "未发现结构问题",
                    subtitle = "指令将按全局、模型、角色、会话和子 Agent 等作用域逐层装配。",
                    icon = Icons.Rounded.Description,
                )
            } else {
                visible.forEach { issue ->
                    DetailListRow(
                        title = when (issue.level) {
                            PromptIssueLevel.ERROR -> "阻断"
                            PromptIssueLevel.WARNING -> "风险"
                            PromptIssueLevel.INFO -> "提示"
                        },
                        subtitle = issue.message,
                        icon = when (issue.level) {
                            PromptIssueLevel.ERROR -> Icons.Rounded.DeleteOutline
                            PromptIssueLevel.WARNING -> Icons.Rounded.Public
                            PromptIssueLevel.INFO -> Icons.Rounded.Description
                        },
                    )
                }
            }
        }
    }
    item(key = "prompt_governance_pipeline") {
        SmallTitle("装配顺序")
        PromptSectionNote(
            "固定安全与工具合同 → 全局 → 模型 → 会议室 → 角色 → 子 Agent → 自动化 → 会话 → 参与者 → 当前用户输入。" +
                "越具体的范围只能调整表达和工作重点，不能增加 Root、文件、网络或工具权限。",
        )
    }
    item(key = "prompt_governance_channels") {
        SmallTitle("覆盖通道")
        Card(
            Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                .etaGlassSurface(accent = MiuixTheme.colorScheme.secondary, cornerRadius = 16.dp),
            colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
        ) {
            listOf(
                "普通对话" to "会话级指令、角色指令和模型指令共同生效",
                "会议室" to "主持人与参与者可分别绑定，不混用身份",
                "子 Agent" to "按 Profile 继承并可追加独立指令",
                "角色与剧情" to "角色卡主指令、后置规则和世界书分开记录",
                "自动任务" to "任务触发时只加载该自动化作用域指令",
            ).forEach { (title, summary) ->
                DetailListRow(
                    title = title,
                    subtitle = summary,
                    icon = Icons.Rounded.Description,
                )
            }
        }
    }
}

@Composable
private fun CardMaterialMetric(title: String, value: String, summary: String, warning: Boolean) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .etaGlassSurface(
                accent = if (warning) colors.secondary else colors.primary,
                cornerRadius = 18.dp,
            ),
        colors = CardDefaults.defaultColors(
            color = (if (warning) colors.secondaryContainer else colors.surfaceContainer).copy(alpha = 0.42f),
            contentColor = colors.onSurface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
            Text(
                value,
                style = MiuixTheme.textStyles.title3,
                color = if (warning) colors.primary else colors.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(summary, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        }
    }
}

private fun LazyListScope.bindingOverviewItems(
    store: PromptInjectionUiStore,
    onOpenTargets: (PromptScopeType) -> Unit,
    onOpenGlobal: () -> Unit,
) {
    item(key = "binding_overview") {
        val colors = MiuixTheme.colorScheme
        Card(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                .etaGlassSurface(accent = colors.primary, cornerRadius = 18.dp),
            colors = CardDefaults.defaultColors(
                color = colors.secondaryContainer.copy(alpha = 0.28f),
                contentColor = colors.onSurface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.prompt_injection_bindings_overview, store.boundScopeCount()),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.prompt_injection_bindings_note),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
    item(key = "binding_priority") {
        Column {
            SmallTitle(stringResource(R.string.prompt_injection_priority_title))
            PromptSectionNote("全局 → 模型 → 项目 → 会议 → 角色 → 子 Agent → 自动任务 → 会话 → 成员/主持。相同方案仅注入一次；具体范围优先分配完整指令块预算。明确替换或停用仅控制模板继承，当前用户要求和运行时合同仍优先；结构检查不能裁决自由文本的语义冲突。")
        }
    }
    item(key = "binding_global") {
        Column {
            SmallTitle(stringResource(R.string.prompt_injection_global))
            Card(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    .etaGlassSurface(accent = MiuixTheme.colorScheme.primary, cornerRadius = 16.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
                onClick = onOpenGlobal,
            ) {
                DetailListRow(
                    title = stringResource(R.string.prompt_injection_global),
                    subtitle = stringResource(R.string.prompt_injection_global_summary),
                    icon = Icons.Rounded.Public,
                    value = store.bindingFor(PromptTarget.GLOBAL)?.let { binding ->
                        store.template(binding.templateId)?.name
                            ?: stringResource(R.string.prompt_injection_missing_template)
                    } ?: stringResource(R.string.prompt_injection_unbound),
                    onClick = onOpenGlobal,
                )
            }
        }
    }
    val editableScopes = listOf(
        PromptScopeType.MODEL,
        PromptScopeType.PROJECT,
        PromptScopeType.CONVERSATION,
        PromptScopeType.SUBAGENT,
        PromptScopeType.MEETING,
        PromptScopeType.MEETING_PARTICIPANT,
        PromptScopeType.CHARACTER,
        PromptScopeType.AUTOMATION,
    )
    item(key = "binding_scopes") {
        Column {
            SmallTitle(stringResource(R.string.prompt_injection_scope_targets))
            Card(
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    .etaGlassSurface(accent = MiuixTheme.colorScheme.secondary, cornerRadius = 16.dp),
                colors = CardDefaults.defaultColors(MiuixTheme.colorScheme.surfaceContainer, MiuixTheme.colorScheme.onSurface),
            ) {
                editableScopes.forEach { type ->
                    val targets = store.targets(type)
                    val bound = targets.count { store.bindingFor(it.target) != null }
                    DetailListRow(
                        title = stringResource(type.titleRes()),
                        subtitle = when (type) {
                            PromptScopeType.MODEL -> stringResource(R.string.prompt_injection_scope_model_summary)
                            PromptScopeType.PROJECT -> "仅在绑定项目的运行中生效，并由子 Agent 继承合法项目范围"
                            PromptScopeType.CONVERSATION -> stringResource(R.string.prompt_injection_scope_conversation_summary)
                            PromptScopeType.SUBAGENT -> stringResource(R.string.prompt_injection_scope_subagent_summary)
                            PromptScopeType.MEETING -> stringResource(R.string.prompt_injection_scope_meeting_summary)
                            PromptScopeType.MEETING_PARTICIPANT -> stringResource(R.string.prompt_injection_scope_participant_summary)
                            PromptScopeType.CHARACTER, PromptScopeType.AUTOMATION ->
                                stringResource(R.string.prompt_injection_targets_note)
                            else -> ""
                        },
                        icon = type.icon(),
                        value = stringResource(R.string.prompt_injection_scope_count, targets.size, bound),
                        onClick = { onOpenTargets(type) },
                    )
                }
            }
        }
    }
}

private fun newPromptTemplate(): PromptTemplate = PromptTemplate(
    name = "",
    description = "",
    blocks = listOf(PromptBlock(title = "", content = "", order = 0)),
    source = "manual",
)

private fun duplicateTemplate(template: PromptTemplate, existing: List<PromptTemplate>): PromptTemplate {
    val baseName = template.name.ifBlank { "Prompt template" }
    var index = 1
    var candidate = "$baseName (copy)"
    val names = existing.mapTo(hashSetOf(), PromptTemplate::name)
    while (candidate in names) {
        index += 1
        candidate = "$baseName (copy $index)"
    }
    val now = System.currentTimeMillis()
    return template.copy(
        id = UUID.randomUUID().toString(),
        name = candidate.take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS),
        createdAt = now,
        updatedAt = now,
        source = "duplicate",
        blocks = template.blocks.map { block -> block.copy(id = UUID.randomUUID().toString()) },
    )
}
