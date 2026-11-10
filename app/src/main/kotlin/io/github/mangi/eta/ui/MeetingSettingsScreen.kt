package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.meeting.MeetingSettingsStore
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val meetingParallelPresets = listOf(1, 2, 3, 4, 5, 6, 7, 8)
private val meetingSummaryLinePresets = listOf(1, 2, 4, 6, 0)
private val meetingTimeoutMinutePresets = listOf(2, 5, 10, 20, 30)

@Composable
internal fun MeetingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { MeetingSettingsStore.get(context.applicationContext) }
    val settings by store.settings.collectAsState()
    val parallelLabels = meetingParallelPresets.map { count ->
        stringResource(R.string.meeting_settings_parallel_value, count)
    }
    val summaryLabels = meetingSummaryLinePresets.map { lines ->
        if (lines == 0) {
            stringResource(R.string.meeting_settings_summary_unlimited)
        } else {
            stringResource(R.string.meeting_settings_summary_value, lines)
        }
    }
    val timeoutLabels = meetingTimeoutMinutePresets.map { minutes ->
        stringResource(R.string.meeting_settings_timeout_value, minutes)
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.meeting_settings_title),
        onBack = onBack,
    ) {
        item(key = "meeting_routing") {
            SmallTitle(stringResource(R.string.meeting_settings_group_routing))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.meeting_settings_auto_dispatch),
                    summary = stringResource(R.string.meeting_settings_auto_dispatch_summary),
                    checked = settings.autoDispatchWithoutMention,
                    onCheckedChange = { enabled ->
                        store.update { it.copy(autoDispatchWithoutMention = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.meeting_settings_max_parallel),
                    summary = stringResource(R.string.meeting_settings_max_parallel_summary),
                    items = parallelLabels,
                    selectedIndex = meetingParallelPresets.indexOf(settings.maxParallelParticipants)
                        .coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        meetingParallelPresets.getOrNull(index)?.let { count ->
                            store.update { it.copy(maxParallelParticipants = count) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.meeting_settings_fallback_all_agents),
                    summary = stringResource(R.string.meeting_settings_fallback_all_agents_summary),
                    checked = settings.fallbackToAllAgents,
                    onCheckedChange = { enabled ->
                        store.update { it.copy(fallbackToAllAgents = enabled) }
                    },
                )
            }
        }

        item(key = "meeting_context") {
            SmallTitle(stringResource(R.string.meeting_settings_group_context))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.meeting_settings_auto_compaction),
                    summary = stringResource(R.string.meeting_settings_auto_compaction_summary),
                    checked = settings.meetingAutoCompactionEnabled,
                    onCheckedChange = { enabled ->
                        store.update { it.copy(meetingAutoCompactionEnabled = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.meeting_settings_timeout),
                    summary = stringResource(R.string.meeting_settings_timeout_summary),
                    items = timeoutLabels,
                    selectedIndex = meetingTimeoutMinutePresets.indexOfFirst { minutes ->
                        minutes * 60 == settings.requestTimeoutSeconds
                    }.takeIf { it >= 0 } ?: meetingTimeoutMinutePresets.indexOf(10),
                    onSelectedIndexChange = { index ->
                        meetingTimeoutMinutePresets.getOrNull(index)?.let { minutes ->
                            store.update { it.copy(requestTimeoutSeconds = minutes * 60) }
                        }
                    },
                )
            }
        }

        item(key = "meeting_conversation") {
            SmallTitle(stringResource(R.string.meeting_settings_group_conversation))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.meeting_settings_auto_follow),
                    summary = stringResource(R.string.meeting_settings_auto_follow_summary),
                    checked = settings.autoFollowMessages,
                    onCheckedChange = { enabled ->
                        store.update { it.copy(autoFollowMessages = enabled) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.meeting_settings_auto_summarize),
                    summary = stringResource(R.string.meeting_settings_auto_summarize_summary),
                    checked = settings.autoSummarizeResults,
                    onCheckedChange = { enabled ->
                        store.update { it.copy(autoSummarizeResults = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.meeting_settings_summary_lines),
                    items = summaryLabels,
                    selectedIndex = meetingSummaryLinePresets.indexOf(settings.summaryPreviewLines)
                        .takeIf { it >= 0 }
                            ?: meetingSummaryLinePresets.indexOf(1),
                    onSelectedIndexChange = { index ->
                        meetingSummaryLinePresets.getOrNull(index)?.let { lines ->
                            store.update { it.copy(summaryPreviewLines = lines) }
                        }
                    },
                )
            }
        }

        item(key = "meeting_behavior_note") {
            Text(
                text = stringResource(R.string.meeting_settings_note),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}
