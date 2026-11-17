package io.github.mangi.eta.ui.screens.meeting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.meeting.MeetingModelOption
import io.github.mangi.eta.fork.meeting.MeetingHostOverride
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.fork.meeting.MeetingProfileOption
import io.github.mangi.eta.fork.meeting.MeetingRoomCreationBridge
import io.github.mangi.eta.fork.meeting.MeetingRoomCreationOptions
import io.github.mangi.eta.fork.meeting.MeetingRoomSelectionDraft
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingWorkflowCatalog
import io.github.mangi.eta.fork.meeting.MeetingWorkflowTemplate
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.AgentIdentityAvatar
import io.github.mangi.eta.ui.components.AgentIdentityPalette
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexaCompactAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun MeetingRoomsScreen(
    onOpenRoom: (String) -> Unit,
    onBack: () -> Unit,
    openCreateOnStart: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms by remember(context) { MeetingRepository.rooms(context) }.collectAsState(initial = emptyList())
    var showCreate by rememberSaveable { mutableStateOf(openCreateOnStart) }

    MiuixScaffoldPage(
        title = stringResource(R.string.meeting_rooms_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showCreate = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.meeting_room_create),
                )
            }
        },
    ) {
        item(key = "rooms_heading") {
            SmallTitle(stringResource(R.string.meeting_rooms_existing, rooms.size))
        }
        if (rooms.isEmpty()) {
            item(key = "rooms_empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ListEmptyState(
                        title = stringResource(R.string.meeting_rooms_empty_title),
                        summary = stringResource(R.string.meeting_rooms_empty_summary),
                        icon = Icons.Rounded.Groups,
                        action = {
                            TextButton(
                                text = stringResource(R.string.meeting_room_create),
                                onClick = { showCreate = true },
                            )
                        },
                    )
                }
            }
        } else {
            items(rooms, key = { "room-${it.id}" }) { room ->
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                        ArrowPreference(
                            title = room.title,
                            summary = roomSummary(room),
                            startAction = {
                                AgentIdentityAvatar(displayName = room.title, seed = room.id, size = 28.dp, icon = null,
                                    palette = AgentIdentityPalette.MeetingVivid)
                            },
                            endActions = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.meeting_settings_parallel_value, room.participantCount),
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                MeetingRepository.setRoomPinned(context, room.id, room.pinnedAt == null)
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PushPin,
                                            contentDescription = stringResource(
                                                if (room.pinnedAt == null) R.string.meeting_room_pin else R.string.meeting_room_unpin,
                                            ),
                                            modifier = Modifier.size(18.dp),
                                            tint = if (room.pinnedAt == null) {
                                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            } else MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            onClick = { onOpenRoom(room.id) },
                        )
                }
            }
        }
        item(key = "topology_explainer") {
            SmallTitle(stringResource(R.string.meeting_room_topology_title))
            Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(R.string.meeting_room_topology_summary),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (showCreate) {
        MeetingRoomCreateDialog(
            onDismiss = { showCreate = false },
            onCreated = { roomId ->
                showCreate = false
                onOpenRoom(roomId)
            },
        )
    }
}

@Composable
internal fun MeetingSettingsPreferenceEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    startAction: (@Composable () -> Unit)? = null,
) {
    ArrowPreference(
        title = stringResource(R.string.meeting_settings_title),
        startAction = startAction,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
internal fun MeetingRoomCreateDialog(
    onDismiss: () -> Unit,
    onCreated: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createFailedMessage by rememberUpdatedState(stringResource(R.string.meeting_room_create_failed))
    val invalidDraftMessage = stringResource(R.string.meeting_room_invalid_draft)
    var title by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<MeetingRoomCreationOptions?>(null) }
    var hostProfile by rememberSaveable { mutableStateOf("") }
    var hostOverrideName by rememberSaveable { mutableStateOf("") }
    var hostOverrideInstruction by rememberSaveable { mutableStateOf("") }
    val hostOverride = hostOverrideInstruction.takeIf(String::isNotBlank)?.let { MeetingHostOverride(hostOverrideName, it) }
    var showHostEditor by remember { mutableStateOf(false) }
    var hostEditorName by rememberSaveable { mutableStateOf("") }
    var hostEditorInstruction by rememberSaveable { mutableStateOf("") }
    var selectionInitialized by rememberSaveable { mutableStateOf(false) }
    var showHostChoices by rememberSaveable { mutableStateOf(false) }
    var showParticipantChoices by rememberSaveable { mutableStateOf(false) }
    var selectedProfiles by rememberSaveable(stateSaver = listSaver(
        save = { it.toList() },
        restore = { it.toSet() },
    )) { mutableStateOf(emptySet<String>()) }
    var modelOverrides by remember { mutableStateOf(emptyMap<String, MeetingModelOption>()) }
    var savedModelOverrides by rememberSaveable { mutableStateOf(emptyList<String>()) }
    fun selectModels(overrides: Map<String, MeetingModelOption>) {
        modelOverrides = overrides
        savedModelOverrides = overrides.flatMap { (profile, model) -> listOf(profile, model.providerId, model.modelId) }
    }
    var modelTarget by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var templateLoading by remember { mutableStateOf(false) }
    var showRoomTemplates by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var presetNote by remember { mutableStateOf<String?>(null) }
    var workflowId by rememberSaveable { mutableStateOf<String?>(null) }
    val roomTemplates by remember(context) { MeetingRepository.rooms(context) }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        runCatching { MeetingRoomCreationBridge.loadOptions(context) }
            .onSuccess { loaded ->
                options = loaded
                val availableNames = loaded.profiles.mapTo(hashSetOf()) { it.name }
                val restored = selectionInitialized
                hostProfile = hostProfile.takeIf { it in availableNames } ?: loaded.defaultHostProfile
                val previousSelection = selectedProfiles
                selectedProfiles = if (!restored) emptySet()
                    else previousSelection.filterTo(linkedSetOf()) { it in availableNames && it != hostProfile }
                selectionInitialized = true
                val restoredModels = savedModelOverrides.chunked(3).mapNotNull { saved ->
                    if (saved.size != 3 || saved[0] !in availableNames) null else
                        loaded.models.firstOrNull { it.providerId == saved[1] && it.modelId == saved[2] }?.let { saved[0] to it }
                }.toMap()
                if (restored && (previousSelection.any { it !in availableNames } || restoredModels.size * 3 != savedModelOverrides.size)) {
                    error = "部分参与者或模型已不可用，请检查当前选择。"
                }
                selectModels(restoredModels)
                loading = false
            }
            .onFailure { throwable ->
                loading = false
                error = throwable.message ?: createFailedMessage
            }
    }

    val loadedOptions = options
    val selectedWorkflow = workflowId?.let(MeetingWorkflowCatalog::find)
    val bindingPreview = loadedOptions?.let { loaded -> runCatching {
        MeetingRoomCreationBridge.automaticBindings(MeetingRoomSelectionDraft(title, hostProfile, selectedProfiles + hostProfile,
            modelOverrides, workflowId), loaded)
    }.getOrNull() }.orEmpty()
    WindowDialog(
        show = true,
        title = stringResource(R.string.meeting_room_create),
        onDismissRequest = { if (!creating) onDismiss() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "title") {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = stringResource(R.string.meeting_room_name),
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                }
                if (!loading && loadedOptions != null) {
                    item(key = "presets") {
                    ArrowPreference(title = selectedWorkflow?.title ?: "选择会议用途", summary = selectedWorkflow?.description ?: presetNote ?: "12种协作流程 · 阶段、材料共享与结束规则",
                        enabled = !creating && !templateLoading, onClick = { showPresets = true })
                    }
                    selectedWorkflow?.let { workflow ->
                        item(key = "workflow_scope") {
                            Text(text = "按需招募 ${workflow.minParticipants} 位协作者 · ${workflow.maxCalls} 次请求 / ${workflow.maxMinutes} 分钟\n成员共享范围遵循所选流程，文件资源不自动隔离。",
                                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 12.dp))
                        }
                    }
                }
                if (!loading && loadedOptions != null && roomTemplates.isNotEmpty()) {
                    item(key = "templates") {
                    ArrowPreference(title = "从现有会议室创建", summary = "复制主持人、参与者和可用模型，可继续修改",
                        enabled = !creating && !templateLoading, onClick = { showRoomTemplates = true })
                    }
                }
                when {
                    loading -> item(key = "loading") { MeetingInlineStatus(stringResource(R.string.meeting_room_loading)) }
                    loadedOptions == null -> item(key = "load_error") { MeetingInlineStatus(
                        error ?: stringResource(R.string.meeting_room_create_failed),
                    ) }
                    else -> {
                        item(key = "host_heading") {
                            ProfileChoiceHeading(stringResource(R.string.meeting_room_host), if (showHostChoices) "收起" else "更换") {
                                showHostChoices = !showHostChoices
                            }
                        }
                        item(key = "default_host_note") {
                            Text(text = "可仅由主持人开始，收到任务后再招募协作者。",
                                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (hostOverride == null) loadedOptions.profiles.firstOrNull { it.name == hostProfile }?.let { currentHost ->
                            item(key = "selected_host") { ProfileChoiceRow(currentHost, true) { showHostChoices = !showHostChoices } }
                        }
                        hostOverride?.let { temporary ->
                            item(key = "temporary_host") {
                                ProfileChoiceRow(MeetingProfileOption(hostProfile, "仅本会议 · 临时主持职责", false,
                                    displayName = temporary.displayName), true) {
                                    hostEditorName = temporary.displayName
                                    hostEditorInstruction = temporary.instruction
                                    showHostEditor = true
                                }
                            }
                        }
                        if (showHostChoices) items(loadedOptions.profiles.filter { it.name != hostProfile || hostOverride != null }, key = { "host:${it.name}" }) { profile ->
                            ProfileChoiceRow(profile, profile.name == hostProfile && hostOverride == null) {
                                hostProfile = profile.name
                                hostOverrideName = ""
                                hostOverrideInstruction = ""
                                selectedProfiles = selectedProfiles - profile.name
                                showHostChoices = false
                            }
                        }
                        item(key = "new_host") {
                            ArrowPreference(title = "临时新建/覆盖主持", summary = "只用于本会议，保留当前主持的模型和工具配置",
                                enabled = loadedOptions.profiles.firstOrNull { it.name == hostProfile }?.remoteAgentId == null,
                                onClick = {
                                    scope.launch {
                                        val profile = withContext(Dispatchers.IO) { SubagentProfileCatalog.get(hostProfile) }
                                        hostEditorName = hostOverride?.displayName ?: "临时主持"
                                        hostEditorInstruction = hostOverride?.instruction ?: profile?.roleInstruction ?: SubagentProfile.MEETING_HOST.roleInstruction
                                        showHostEditor = true
                                    }
                                })
                        }
                        item(key = "participants_heading") {
                            ProfileChoiceHeading(stringResource(R.string.meeting_room_participants), if (showParticipantChoices) "完成" else "选择") {
                                showParticipantChoices = !showParticipantChoices
                            }
                        }
                        item(key = "participants_note") {
                            Text(text = if (selectedProfiles.isEmpty()) "暂不预选 · 主持人可按任务招募" else "已预选 ${selectedProfiles.size} 位协作者",
                                style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        items(loadedOptions.profiles.filter { it.name != hostProfile && (showParticipantChoices || it.name in selectedProfiles) }, key = { "participant:${it.name}" }) { profile ->
                            ProfileChoiceRow(profile, profile.name in selectedProfiles) {
                                selectedProfiles = if (profile.name in selectedProfiles) {
                                    selectedProfiles - profile.name
                                } else {
                                    selectedProfiles + profile.name
                                }
                            }
                        }
                        if (hostProfile.isNotBlank()) {
                            item(key = "model_heading") {
                            Text(
                                text = "模型分配 · 创建时固定，可点按指定",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            }
                            val localSelections = (selectedProfiles + hostProfile).filter(String::isNotBlank).distinct()
                                .filter { name ->
                                    loadedOptions.profiles.firstOrNull { it.name == name }?.remoteAgentId == null
                                }
                            items(localSelections, key = { "override:$it" }) { profileName ->
                                ModelOverrideRow(profileName = profileName, selected = bindingPreview[profileName],
                                    automatic = profileName !in modelOverrides,
                                    onClick = { modelTarget = profileName })
                            }
                            if (bindingPreview.values.map { it.diversityIdentity }.distinct().size < localSelections.size) {
                                item(key = "model_diversity") { MeetingInlineStatus("当前候选或明确绑定不足以让所有成员使用不同模型；会显示实际绑定，不把多个角色当成多个模型。") }
                            }
                        }
                        if (loadedOptions.models.isEmpty()) {
                            item(key = "no_models") { MeetingInlineStatus(stringResource(R.string.meeting_room_no_models)) }
                        }
                    }
                }
                error?.takeIf { loadedOptions != null }?.let { text ->
                    item(key = "error") { MeetingInlineStatus(text) }
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 10.dp),
                confirmText = if (creating) {
                    stringResource(R.string.meeting_room_creating)
                } else {
                    stringResource(R.string.meeting_room_create)
                },
                confirmEnabled = !loading && !creating && !templateLoading && loadedOptions != null && title.isNotBlank() &&
                    hostProfile.isNotBlank(),
                onCancel = { if (!creating) onDismiss() },
                onConfirm = {
                    val profiles = loadedOptions?.profiles.orEmpty()
                    val draft = MeetingRoomSelectionDraft(
                        title = title,
                        hostProfile = hostProfile,
                        participantProfiles = selectedProfiles + hostProfile,
                        modelOverrides = modelOverrides,
                        templateId = workflowId,
                        hostOverride = hostOverride,
                    )
                    if (!draft.validate()) {
                        error = invalidDraftMessage
                    } else {
                        scope.launch {
                            creating = true
                            error = null
                            runCatching {
                                MeetingRoomCreationBridge.createRoom(context, draft, profiles)
                            }.onSuccess(onCreated)
                                .onFailure { throwable ->
                                    error = throwable.message
                                        ?: createFailedMessage
                                    creating = false
                                }
                        }
                    }
                },
            )
        }
    }

    if (showPresets) {
        WindowDialog(show = true, title = "选择会议用途", onDismissRequest = { showPresets = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item(key = "isolated") { ArrowPreference(title = "独立分工（兼容原会议）", summary = "主持分工 → 成员独立执行 → 主持汇总", onClick = {
                    workflowId = null
                    presetNote = "独立分工 · 成员互不共享输出"
                    showPresets = false
                }) }
                items(MeetingWorkflowCatalog.all, key = { it.id }) { workflow ->
                    ArrowPreference(title = "${workflow.id} · ${workflow.title}", summary = workflow.description, onClick = {
                        workflowId = workflow.id
                        val available = loadedOptions?.profiles.orEmpty().map { it.name }
                        if (hostProfile !in available) hostProfile = loadedOptions?.defaultHostProfile.orEmpty()
                        val selected = selectedProfiles.filter { it in available && it != hostProfile }.toMutableSet()
                        selectedProfiles = selected
                        if (title.isBlank()) title = workflow.title.substringBefore(" /")
                        presetNote = workflow.description
                        error = null
                        showPresets = false
                    })
                }
                item(key = "cancel") { TextButton(text = stringResource(R.string.action_cancel), onClick = { showPresets = false }) }
            }
        }
    }

    if (showRoomTemplates) {
        WindowDialog(show = true, title = "选择会议室", onDismissRequest = { if (!templateLoading) showRoomTemplates = false }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(roomTemplates, key = { it.id }) { room ->
                    ArrowPreference(title = room.title, summary = roomSummary(room), enabled = !templateLoading,
                        onClick = {
                            scope.launch {
                                templateLoading = true
                                try {
                                    val (snapshot, copiedHostOverride) = withContext(Dispatchers.IO) {
                                        MeetingRepository.snapshot(context, room.id).first() to MeetingRepository.hostOverride(context, room.id)
                                    }
                                    requireNotNull(snapshot) { "会议室不存在" }
                                    val availableProfiles = loadedOptions?.profiles.orEmpty().associateBy { it.name }
                                    val members = snapshot.participants.filter { it.enabled }
                                    fun compatible(member: io.github.mangi.eta.fork.meeting.MeetingParticipantEntity): Boolean {
                                        val option = availableProfiles[member.profileName] ?: return false
                                        return if (member.providerId?.startsWith("a2a:") == true) {
                                            option.remoteAgentId == member.providerId.removePrefix("a2a:")
                                        } else option.remoteAgentId == null
                                    }
                                    val missing = members.filterNot(::compatible)
                                    val host = members.firstOrNull { it.isHost }
                                    require(host != null && compatible(host)) { "原主持人已不可用，请选择其他会议室" }
                                    hostProfile = host.profileName
                                    hostOverrideName = copiedHostOverride?.displayName.orEmpty()
                                    hostOverrideInstruction = copiedHostOverride?.instruction.orEmpty()
                                    selectedProfiles = members.filter { compatible(it) && !it.isHost }.map { it.profileName }.toSet()
                                    workflowId = snapshot.room.workflowSnapshotJson?.let { MeetingWorkflowTemplate.decode(it).id }
                                    presetNote = "从 ${room.title} 复制"
                                    selectModels(members.mapNotNull { member ->
                                        loadedOptions?.models?.firstOrNull {
                                            it.providerId == member.providerId && it.modelId == member.modelId
                                        }?.let { member.profileName to it }
                                    }.toMap())
                                    val unavailableModels = members.count { member ->
                                        member.providerId != null && !member.providerId.startsWith("a2a:") &&
                                            member.modelId != null && member.profileName !in modelOverrides
                                    }
                                    error = if (missing.isNotEmpty() || unavailableModels > 0) {
                                        "部分参与者或模型已不可用，请检查当前选择。"
                                    } else null
                                    showRoomTemplates = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    error = failure.message ?: createFailedMessage
                                    showRoomTemplates = false
                                } finally {
                                    templateLoading = false
                                }
                            }
                        })
                }
            }
        }
    }

    if (showHostEditor) {
        WindowDialog(show = true, title = "本会议临时主持", onDismissRequest = { showHostEditor = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(value = hostEditorName, onValueChange = { hostEditorName = it.take(80) },
                    label = "主持名称", singleLine = true, modifier = Modifier.fillMaxWidth())
                TextField(value = hostEditorInstruction, onValueChange = { hostEditorInstruction = it.take(16_000) },
                    label = "职责、规则与交付要求", modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
                Text(text = "保存后选为本会议主持。会议阶段与工具权限仍受原有规则限制。",
                    style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                MiuixDialogActions(confirmText = "创建并选中", confirmEnabled = MeetingHostOverride(hostEditorName, hostEditorInstruction).validate(),
                    onCancel = { showHostEditor = false }, onConfirm = {
                        hostOverrideName = hostEditorName.trim()
                        hostOverrideInstruction = hostEditorInstruction.trim()
                        selectedProfiles = selectedProfiles - hostProfile
                        showHostEditor = false
                    })
            }
        }
    }

    val target = modelTarget
    if (target != null) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.meeting_room_choose_model, target),
            onDismissRequest = { modelTarget = null },
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item(key = "profile_default") {
                    ModelOptionRow(
                        title = "自动分配并固定模型",
                        subtitle = "优先专属绑定，按能力、上下文与成员间模型差异选择",
                        selected = modelOverrides[target] == null,
                        onClick = {
                            selectModels(modelOverrides - target)
                            modelTarget = null
                        },
                    )
                }
                items(loadedOptions?.models.orEmpty(), key = { "model:${it.providerId.length}:${it.providerId}${it.modelId}" }) { model ->
                    ModelOptionRow(
                        title = model.displayName.ifBlank { model.modelName },
                        subtitle = model.providerName,
                        selected = modelOverrides[target] == model,
                        onClick = {
                            selectModels(modelOverrides + (target to model))
                            modelTarget = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileChoiceHeading(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
    if (action != null) NexaCompactAction(action, onClick = onAction)
    }
}

@Composable
private fun ProfileChoiceRow(profile: MeetingProfileOption, isSelected: Boolean, onToggle: () -> Unit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.tertiaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainer
                        },
                        cornerRadius = 20.dp,
                    )
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentIdentityAvatar(displayName = profile.displayName, seed = profile.name, size = 28.dp,
                    icon = null, singleInitial = true, palette = AgentIdentityPalette.MeetingVivid)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.displayName,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.onTertiaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                    )
                    if (profile.summary.isNotBlank()) {
                        Text(
                            text = profile.summary,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Checkbox(
                    state = ToggleableState(isSelected),
                    onClick = onToggle,
                )
            }
}

@Composable
private fun ModelOverrideRow(
    profileName: String,
    selected: MeetingModelOption?,
    automatic: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 14.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profileName,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = selected?.let { (if (automatic) "自动 · " else "指定 · ") + it.label } ?: "暂未分配可用模型",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            text = stringResource(R.string.meeting_room_change_model),
            onClick = onClick,
        )
    }
}

@Composable
private fun ModelOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                if (selected) MiuixTheme.colorScheme.tertiaryContainer else MiuixTheme.colorScheme.surface,
                14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MiuixTheme.colorScheme.onTertiaryContainer
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
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

@Composable
private fun MeetingInlineStatus(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 12.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun roomSummary(room: MeetingRoomSummaryRow): String =
    stringResource(
        R.string.meeting_room_row_summary,
        room.hostProfile,
        room.participantCount,
        meetingStatusLabel(room.status),
    )

@Composable
internal fun meetingStatusLabel(status: String): String = stringResource(
    when (status) {
        MeetingRoomStatus.RUNNING -> R.string.meeting_room_status_running
        MeetingRoomStatus.WAITING_FOR_USER -> R.string.meeting_room_status_waiting
        MeetingRoomStatus.FAILED -> R.string.meeting_room_status_failed
        else -> R.string.meeting_room_status_idle
    },
)
