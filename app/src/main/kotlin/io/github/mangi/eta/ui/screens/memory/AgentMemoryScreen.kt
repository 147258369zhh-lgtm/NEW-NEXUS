package io.github.mangi.eta.ui.screens.memory

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.DialogChoicePreference
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private enum class MemoryViewMode { Preview, Edit }

/**
 * 记忆页：顶部是开关与预算，下方 MEMORY.md 以“文档”形式占满剩余高度。
 * 默认渲染 Markdown 便于阅读，切到编辑后是无边框的整页编辑区；只有存在未保存修改时才浮出保存栏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryDocumentScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
    onOpenLibrary: () -> Unit,
    embedded: Boolean = false,
    modifier: Modifier = Modifier,
    documentScrollState: ScrollState = rememberScrollState(),
    moduleTabs: @Composable () -> Unit = {},
    onOpenCharacterMemory: (String) -> Unit = {},
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var pendingScope by remember { mutableStateOf<io.github.mangi.eta.data.repository.AgentMemoryScope?>(null) }
    var mode by rememberSaveable { mutableStateOf<MemoryViewMode?>(null) }
    val useCompactTitle = LocalDensity.current.fontScale <= 1.2f
    // 首次读到内容前不决定模式：空记忆直接进入编辑，已有内容先给出可读的预览。
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && mode == null) {
            mode = if (state.savedContent.isBlank()) MemoryViewMode.Edit else MemoryViewMode.Preview
        }
    }
    val currentMode = mode ?: MemoryViewMode.Preview
    val hasUnsavedChanges = state.draft != state.savedContent
    val requestExit = {
        if (hasUnsavedChanges) showDiscardDialog = true else onOpenLibrary()
    }
    // Keep the document pane's back behavior stable even when its scaffold is nested in the
    // memory module. Unsaved edits still get the discard confirmation first.
    BackHandler { requestExit() }

    if (embedded) {
        MemoryDocumentBody(
            state = state,
            mode = currentMode,
            onModeChange = { mode = it },
            onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
            onSave = { onAction(AgentMemoryAction.Save) },
            documentScrollState = documentScrollState,
            moduleTabs = moduleTabs,
            onOpenCharacterMemory = onOpenCharacterMemory,
            onSelectScope = { scope ->
                if (hasUnsavedChanges) pendingScope = scope else onAction(AgentMemoryAction.ScopeSelected(scope))
            },
            modifier = modifier,
        )
    } else {
        MiuixScaffold(
            title = "记忆与经验",
            onBack = requestExit,
            compactTitle = useCompactTitle,
            actions = {
                IconButton(
                    onClick = { showClearDialog = true },
                    enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "清空当前文档",
                        tint = if (!state.isLoading && state.draft.isNotEmpty()) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                    )
                }
            },
        ) { paddingValues, scrollBehavior, sidePadding ->
            MemoryDocumentBody(
                state = state,
                mode = currentMode,
                onModeChange = { mode = it },
                onDraftChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                onSave = { onAction(AgentMemoryAction.Save) },
                documentScrollState = documentScrollState,
                moduleTabs = moduleTabs,
                onOpenCharacterMemory = onOpenCharacterMemory,
                onSelectScope = { scope ->
                    if (hasUnsavedChanges) pendingScope = scope else onAction(AgentMemoryAction.ScopeSelected(scope))
                },
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .padding(top = paddingValues.calculateTopPadding())
                    .padding(horizontal = sidePadding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            )
        }
    }

    if (showClearDialog) {
        WindowDialog(
            show = true,
            title = "清空当前文档",
            summary = "只清空当前所选作用域的文档内容，此操作无法撤销。",
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.memory_clear),
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    if (showDiscardDialog) {
        WindowDialog(
            show = true,
            title = "放弃未保存的修改？",
            summary = "当前文档还有未保存内容。返回后这些修改会丢失。",
            onDismissRequest = { showDiscardDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = "放弃并返回",
                cancelText = "继续编辑",
                destructive = true,
                onCancel = { showDiscardDialog = false },
                onConfirm = {
                    showDiscardDialog = false
                    onAction(AgentMemoryAction.DraftChanged(state.savedContent))
                    onOpenLibrary()
                },
            )
        }
    }

    pendingScope?.let { scope ->
        WindowDialog(
            show = true,
            title = "切换记忆范围",
            summary = "当前文档有未保存修改。切换范围会放弃这些修改。",
            onDismissRequest = { pendingScope = null },
        ) {
            MiuixDialogActions(
                confirmText = "放弃并切换",
                cancelText = "继续编辑",
                destructive = true,
                onCancel = { pendingScope = null },
                onConfirm = {
                    pendingScope = null
                    onAction(AgentMemoryAction.DraftChanged(state.savedContent))
                    onAction(AgentMemoryAction.ScopeSelected(scope))
                },
            )
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_memory_b55ff5),
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MemoryDocumentBody(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    onModeChange: (MemoryViewMode) -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    documentScrollState: ScrollState,
    moduleTabs: @Composable () -> Unit = {},
    onSelectScope: (io.github.mangi.eta.data.repository.AgentMemoryScope) -> Unit,
    onOpenCharacterMemory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imeVisible = WindowInsets.isImeVisible
    var scopeKind by remember(state.selectedScope.key) { mutableStateOf(state.selectedScope.type.path) }
    Column(
        modifier = modifier
            .imePadding()
            .navigationBarsPadding(),
    ) {
        moduleTabs()
        // 键盘弹出时收起设置区，把有限高度全部让给正在编辑的文档。
        AnimatedVisibility(
            visible = !imeVisible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            MemoryDocumentScopeGroup(state = state, kind = scopeKind, onKindChange = { scopeKind = it }, onSelect = onSelectScope, onOpenCharacterMemory = onOpenCharacterMemory)
        }
        if (scopeKind != state.selectedScope.type.path) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text("选择此分类中的具体对象后查看文档。当前文档及草稿已保留。", style = MiuixTheme.textStyles.body2)
            }
            MemorySaveBar(state = state, onDiscard = { onDraftChange(state.savedContent) }, onSave = onSave)
            Spacer(Modifier.height(12.dp))
            return@Column
        }
        var sectionKey by remember(state.selectedScope.key) { mutableStateOf("all") }
        val sections = io.github.mangi.eta.agent.memory.MemoryDocumentSections.parts(state.draft)
        val section = sections.firstOrNull { it.key == sectionKey }
        DialogChoicePreference(
            title = "内容章节",
            value = section?.title ?: "全文（含前言）",
            options = listOf("全文（含前言）") + sections.map { it.title },
            selectedIndex = if (section == null) 0 else sections.indexOf(section) + 1,
            enabled = !state.isLoading && !state.isSaving,
            onSelectedIndexChange = { index -> sectionKey = sections.getOrNull(index - 1)?.key ?: "all" },
        )
        MemoryDocumentCard(
            state = if (section == null) state else state.copy(draft = io.github.mangi.eta.agent.memory.MemoryDocumentSections.content(state.draft, section)),
            mode = mode,
            onModeChange = onModeChange,
            onDraftChange = { value ->
                onDraftChange(if (section == null) value else io.github.mangi.eta.agent.memory.MemoryDocumentSections.replace(state.draft, section, value))
                if (section?.missing == true && value.isNotEmpty()) sectionKey = io.github.mangi.eta.agent.memory.MemoryDocumentSections.parts(state.draft + "\n# ${section.title}\n").firstOrNull { it.title == section.title && !it.missing }?.key ?: "all"
            },
            documentScrollState = documentScrollState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        )
        MemorySaveBar(
            state = state,
            onDiscard = { onDraftChange(state.savedContent) },
            onSave = onSave,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun MemoryDocumentScopeGroup(
    state: AgentMemoryUiState,
    kind: String,
    onKindChange: (String) -> Unit,
    onSelect: (io.github.mangi.eta.data.repository.AgentMemoryScope) -> Unit,
    onOpenCharacterMemory: (String) -> Unit,
) {
    val kinds = listOf("global", "project", "conversation", "task", "meeting", "character")
    val labels = listOf("全局", "项目", "会话", "任务", "会议", "角色")
    val scopes = state.scopes.filter { it.type.path == kind }
    val selectedIndex = scopes.indexOfFirst { it.key == state.selectedScope.key }
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 12.dp),
    ) {
        DialogChoicePreference(title = "记忆分类", value = labels.getOrElse(kinds.indexOf(kind)) { "全局" },
            options = labels, selectedIndex = kinds.indexOf(kind), enabled = !state.isLoading && !state.isSaving,
            onSelectedIndexChange = { index -> onKindChange(kinds.getOrElse(index) { "global" }) })
        Text("范围决定哪些任务可见；核心等章节表示内容的重要性。", style = MiuixTheme.textStyles.footnote2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (kind == "character") {
            val characters = state.characters.entries.toList()
            if (characters.isEmpty()) BasicComponent(title = "暂无角色", summary = "创建角色后，可在这里管理该角色已有的剧情记忆。")
            else DialogChoicePreference(title = "角色记忆", value = "选择角色查看与管理剧情记忆",
                options = characters.map { it.value }, selectedIndex = -1,
                enabled = !state.isLoading && !state.isSaving && !state.hasUnsavedChanges,
                onSelectedIndexChange = { index -> characters.getOrNull(index)?.let { onOpenCharacterMemory(it.key) } })
        } else if (scopes.isEmpty()) {
            BasicComponent(title = "暂无${labels.getOrElse(kinds.indexOf(kind)) { "此类" }}对象", summary = "创建相应对象后可选择其记忆；已有文档始终保留。")
        } else DialogChoicePreference(
            title = stringResource(R.string.memory_scope_label),
            value = scopes.getOrNull(selectedIndex)?.let { scopeLabel(it, state.scopeNames) } ?: "选择要查看和编辑的对象",
            options = scopes.map { scopeLabel(it, state.scopeNames) }, selectedIndex = selectedIndex,
            enabled = !state.isLoading && !state.isSaving,
            onSelectedIndexChange = { index -> scopes.getOrNull(index)?.let(onSelect) },
        )
    }
}

@Composable
internal fun MemorySettingsGroup(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 12.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.ui_enable_memory_4b69b7),
            summary = stringResource(R.string.ui_after_closing_no_memory_will_be_injected_and_the_mod_db3d23),
            checked = state.enabled,
            enabled = !state.isLoading,
            onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.memory_scope_label),
            items = state.scopes.map { scope -> scopeLabel(scope, state.scopeNames) },
            selectedIndex = state.scopes.indexOfFirst { it.key == state.selectedScope.key }.coerceAtLeast(0),
            onSelectedIndexChange = { index -> state.scopes.getOrNull(index)?.let { onAction(AgentMemoryAction.ScopeSelected(it)) } },
            enabled = !state.isLoading && !state.isSaving,
        )
        BasicComponent(
            title = "本轮记忆与经验预算",
            summary = "少量核心最多 ${formatNumber(state.coreBudgetChars)} 字符；核心、事实与经验合计最多 ${formatNumber(state.totalBudgetChars)} 字符。按当前任务检索并整条选取，没有相关内容时少提供；实际随模型窗口缩减。",
        )
    }
}

@Composable
private fun scopeLabel(scope: io.github.mangi.eta.data.repository.AgentMemoryScope, names: Map<String, String> = emptyMap()): String =
    memoryScopeName(scope.key, names)

/** 文档卡片：标题栏承载文件名、体积与预览/编辑切换，正文区占满卡片剩余高度并独立滚动。 */
@Composable
private fun MemoryDocumentCard(
    state: AgentMemoryUiState,
    mode: MemoryViewMode,
    onModeChange: (MemoryViewMode) -> Unit,
    onDraftChange: (String) -> Unit,
    documentScrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val overLimit = state.draftBytes > state.maxBytes
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Notes,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colors.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scopeLabel(state.selectedScope, state.scopeNames),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Text(
                    text = "${formatBytes(state.draftBytes)} / ${formatBytes(state.maxBytes)}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (overLimit) colors.error else colors.onSurfaceVariantSummary,
                )
            }
            MemoryModeSwitch(mode = mode, enabled = !state.isLoading, onModeChange = onModeChange)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.dividerLine),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
                else -> Crossfade(targetState = mode, label = "memory_mode") { current ->
                    when (current) {
                        MemoryViewMode.Preview -> MemoryPreview(
                            content = state.draft,
                            onStartEditing = { onModeChange(MemoryViewMode.Edit) },
                            scrollState = documentScrollState,
                        )
                        MemoryViewMode.Edit -> MemoryEditor(
                            value = state.draft,
                            enabled = !state.isSaving,
                            onValueChange = onDraftChange,
                            scrollState = documentScrollState,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryPreview(content: String, onStartEditing: () -> Unit, scrollState: ScrollState) {
    if (content.isBlank()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onStartEditing)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.memory_empty_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        StaticMarkdown(content = content, tone = MarkdownTone.Answer, modifier = Modifier.fillMaxWidth())
    }
}

/** 无边框整页编辑：与卡片同色，不再嵌一层灰色输入框；点到空白处也能把光标落进正文。 */
@Composable
private fun MemoryEditor(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    scrollState: ScrollState,
) {
    val colors = MiuixTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() }
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9),
                style = MemoryTextStyle(),
                color = colors.onSurfaceVariantSummary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            textStyle = MemoryTextStyle().copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}

@Composable
private fun MemoryTextStyle() = MiuixTheme.textStyles.body2.copy(lineHeight = 22.sp)

/** 轻量分段切换：两个选项常驻可见，选中项用浮起的胶囊表示，比整行 TabRow 更贴合卡片标题栏。 */
@Composable
private fun MemoryModeSwitch(
    mode: MemoryViewMode,
    enabled: Boolean,
    onModeChange: (MemoryViewMode) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .squircleSurface(color = colors.surfaceContainerHigh, cornerRadius = 12.dp)
            .padding(3.dp),
    ) {
        MemoryViewMode.entries.forEach { option ->
            val selected = option == mode
            val background by animateColorAsState(
                if (selected) colors.surface else colors.surfaceContainerHigh,
                label = "memory_mode_tab",
            )
            Box(
                modifier = Modifier
                    .squircleSurface(color = background, cornerRadius = 9.dp)
                    .clickable(enabled = enabled && !selected) { onModeChange(option) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (option == MemoryViewMode.Preview) R.string.memory_mode_preview else R.string.memory_mode_edit,
                    ),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** 有未保存修改或超限时才出现：左侧说明状态，右侧放弃与保存，避免常驻一个灰掉的保存按钮。 */
@Composable
private fun MemorySaveBar(
    state: AgentMemoryUiState,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val overLimit = state.draftBytes > state.maxBytes
    AnimatedVisibility(
        visible = !state.isLoading && (state.hasUnsavedChanges || state.isSaving),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(if (overLimit) R.string.memory_over_limit else R.string.memory_unsaved_changes),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (overLimit) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                )
            }
            TextButton(
                text = stringResource(R.string.memory_discard),
                enabled = !state.isSaving,
                onClick = onDiscard,
                minHeight = 36.dp,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            )
            TextButton(
                text = stringResource(if (state.isSaving) R.string.memory_saving else R.string.memory_save),
                enabled = state.canSave,
                onClick = onSave,
                minHeight = 36.dp,
                insideMargin = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    bytes % (1_024 * 1_024) == 0 -> "${bytes / (1_024 * 1_024)} MiB"
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)
