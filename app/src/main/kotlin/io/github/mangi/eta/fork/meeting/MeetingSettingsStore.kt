package io.github.mangi.eta.fork.meeting

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MeetingSettings(
    val autoDispatchWithoutMention: Boolean = true,
    val maxParallelParticipants: Int = 4,
    val autoFollowMessages: Boolean = true,
    val summaryPreviewLines: Int = 1,
    val meetingAutoCompactionEnabled: Boolean = true,
    val requestTimeoutSeconds: Int = 600,
    val fallbackToAllAgents: Boolean = true,
    val autoSummarizeResults: Boolean = true,
)

/** Meeting-specific defaults shared by the room UI and runtime. */
internal class MeetingSettingsStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(readSettings())

    val settings: StateFlow<MeetingSettings> = mutableSettings.asStateFlow()

    val current: MeetingSettings
        get() = mutableSettings.value

    fun update(transform: (MeetingSettings) -> MeetingSettings) {
        val updated = normalize(transform(current))
        preferences.edit()
            .putBoolean(KEY_AUTO_DISPATCH, updated.autoDispatchWithoutMention)
            .putInt(KEY_MAX_PARALLEL, updated.maxParallelParticipants)
            .putBoolean(KEY_AUTO_FOLLOW, updated.autoFollowMessages)
            .putInt(KEY_SUMMARY_LINES, updated.summaryPreviewLines)
            .putBoolean(KEY_AUTO_COMPACTION, updated.meetingAutoCompactionEnabled)
            .putInt(KEY_REQUEST_TIMEOUT_SECONDS, updated.requestTimeoutSeconds)
            .putBoolean(KEY_FALLBACK_TO_ALL_AGENTS, updated.fallbackToAllAgents)
            .putBoolean(KEY_AUTO_SUMMARIZE_RESULTS, updated.autoSummarizeResults)
            .apply()
        mutableSettings.value = updated
    }

    private fun readSettings(): MeetingSettings = normalize(
        MeetingSettings(
            autoDispatchWithoutMention = preferences.getBoolean(KEY_AUTO_DISPATCH, true),
            maxParallelParticipants = preferences.getInt(KEY_MAX_PARALLEL, 4),
            autoFollowMessages = preferences.getBoolean(KEY_AUTO_FOLLOW, true),
            summaryPreviewLines = preferences.getInt(KEY_SUMMARY_LINES, 1),
            meetingAutoCompactionEnabled = preferences.getBoolean(KEY_AUTO_COMPACTION, true),
            requestTimeoutSeconds = preferences.getInt(KEY_REQUEST_TIMEOUT_SECONDS, 600),
            fallbackToAllAgents = preferences.getBoolean(KEY_FALLBACK_TO_ALL_AGENTS, true),
            autoSummarizeResults = preferences.getBoolean(KEY_AUTO_SUMMARIZE_RESULTS, true),
        ),
    )

    private fun normalize(settings: MeetingSettings): MeetingSettings = settings.copy(
        maxParallelParticipants = settings.maxParallelParticipants.coerceIn(1, 8),
        summaryPreviewLines = settings.summaryPreviewLines.takeIf { it == 0 || it in 1..12 } ?: 1,
        requestTimeoutSeconds = settings.requestTimeoutSeconds.coerceIn(60, 3_600),
    )

    companion object {
        private const val FILE_NAME = "eta_meeting_settings"
        private const val KEY_AUTO_DISPATCH = "auto_dispatch_without_mention"
        private const val KEY_MAX_PARALLEL = "max_parallel_participants"
        private const val KEY_AUTO_FOLLOW = "auto_follow_messages"
        private const val KEY_SUMMARY_LINES = "summary_preview_lines"
        private const val KEY_AUTO_COMPACTION = "meeting_auto_compaction_enabled"
        private const val KEY_REQUEST_TIMEOUT_SECONDS = "request_timeout_seconds"
        private const val KEY_FALLBACK_TO_ALL_AGENTS = "fallback_to_all_agents"
        private const val KEY_AUTO_SUMMARIZE_RESULTS = "auto_summarize_results"

        @Volatile
        private var instance: MeetingSettingsStore? = null

        fun get(context: Context): MeetingSettingsStore =
            instance ?: synchronized(this) {
                instance ?: MeetingSettingsStore(context).also { instance = it }
            }
    }
}
