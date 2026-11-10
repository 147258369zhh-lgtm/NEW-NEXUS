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
import io.github.mangi.eta.fork.meeting.MeetingProfileOption
import io.github.mangi.eta.fork.meeting.MeetingRoomCreationBridge
import io.github.mangi.eta.fork.meeting.MeetingRoomCreationOptions
import io.github.mangi.eta.fork.meeting.MeetingRoomSelectionDraft
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomSummaryRow
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
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
    val rooms by MeetingRepository.rooms(context).collectAsState(initial = emptyList())
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
        item(key = "rooms_card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (rooms.isEmpty()) {
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
                } else {
                    rooms.forEach { room ->
                        ArrowPreference(
                            title = room.title,
                            summary = roomSummary(room),
                            startAction = {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Groups,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
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
    var selectedProfiles by remember { mutableStateOf(emptySet<String>()) }
    var modelOverrides by remember { mutableStateOf(emptyMap<String, MeetingModelOption>()) }
    var modelTarget by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { MeetingRoomCreationBridge.loadOptions(context) }
            .onSuccess { loaded ->
                options = loaded
                val defaultHost = loaded.profiles.firstOrNull()?.name.orEmpty()
                hostProfile = defaultHost
                selectedProfiles = loaded.profiles.take(2).map { it.name }.toSet()
                loading = false
            }
            .onFailure { throwable ->
                loading = false
                error = throwable.message ?: createFailedMessage
            }
    }

    val loadedOptions = options
    WindowDialog(
        show = true,
        title = stringResource(R.string.meeting_room_create),
        onDismissRequest = { if (!creating) onDismiss() },
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = stringResource(R.string.meeting_room_name),
                    singleLine = true,
                    enabled = !creating,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    loading -> MeetingInlineStatus(stringResource(R.string.meeting_room_loading))
                    loadedOptions == null -> MeetingInlineStatus(
                        error ?: stringResource(R.string.meeting_room_create_failed),
                    )
                    else -> {
                        ProfileChoiceSection(
                            title = stringResource(R.string.meeting_room_host),
                            profiles = loadedOptions.profiles,
                            selected = setOf(hostProfile),
                            onToggle = { profile ->
                                hostProfile = profile.name
                                selectedProfiles = selectedProfiles + profile.name
                            },
                        )
                        ProfileChoiceSection(
                            title = stringResource(R.string.meeting_room_participants),
                            profiles = loadedOptions.profiles,
                            selected = selectedProfiles,
                            onToggle = { profile ->
                                selectedProfiles = if (profile.name in selectedProfiles) {
                                    selectedProfiles - profile.name
                                } else {
                                    selectedProfiles + profile.name
                                }
                            },
                        )
                        if (selectedProfiles.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.meeting_room_model_hint),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            (selectedProfiles + hostProfile).filter(String::isNotBlank).distinct()
                                .filter { name ->
                                    loadedOptions.profiles.firstOrNull { it.name == name }?.remoteAgentId == null
                                }
                                .forEach { profileName ->
                                    ModelOverrideRow(
                                        profileName = profileName,
                                        selected = modelOverrides[profileName],
                                        onClick = { modelTarget = profileName },
                                    )
                                }
                        }
                        if (loadedOptions.models.isEmpty()) {
                            MeetingInlineStatus(stringResource(R.string.meeting_room_no_models))
                        }
                    }
                }
                error?.takeIf { loadedOptions != null }?.let { text -> MeetingInlineStatus(text) }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = if (creating) {
                    stringResource(R.string.meeting_room_creating)
                } else {
                    stringResource(R.string.meeting_room_create)
                },
                confirmEnabled = !loading && !creating && loadedOptions != null && title.isNotBlank() &&
                    hostProfile.isNotBlank() && selectedProfiles.isNotEmpty(),
                onCancel = { if (!creating) onDismiss() },
                onConfirm = {
                    val profiles = loadedOptions?.profiles.orEmpty()
                    val draft = MeetingRoomSelectionDraft(
                        title = title,
                        hostProfile = hostProfile,
                        participantProfiles = selectedProfiles + hostProfile,
                        modelOverrides = modelOverrides,
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
                        title = stringResource(R.string.meeting_room_model_auto),
                        subtitle = stringResource(R.string.meeting_room_model_auto_summary),
                        selected = modelOverrides[target] == null,
                        onClick = {
                            modelOverrides = modelOverrides - target
                            modelTarget = null
                        },
                    )
                }
                items(loadedOptions?.models.orEmpty(), key = { it.providerId + it.modelId }) { model ->
                    ModelOptionRow(
                        title = model.displayName.ifBlank { model.modelName },
                        subtitle = model.providerName,
                        selected = modelOverrides[target] == model,
                        onClick = {
                            modelOverrides = modelOverrides + (target to model)
                            modelTarget = null
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileChoiceSection(
    title: String,
    profiles: List<MeetingProfileOption>,
    selected: Set<String>,
    onToggle: (MeetingProfileOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        profiles.forEach { profile ->
            val isSelected = profile.name in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(
                        color = if (isSelected) {
                            MiuixTheme.colorScheme.tertiaryContainer
                        } else {
                            MiuixTheme.colorScheme.surfaceContainer
                        },
                        cornerRadius = 14.dp,
                    )
                    .clickable { onToggle(profile) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
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
                    onClick = { onToggle(profile) },
                )
            }
        }
    }
}

@Composable
private fun ModelOverrideRow(
    profileName: String,
    selected: MeetingModelOption?,
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
                text = selected?.label ?: stringResource(R.string.meeting_room_model_auto),
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
