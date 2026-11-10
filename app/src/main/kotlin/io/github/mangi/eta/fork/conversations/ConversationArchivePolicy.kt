package io.github.mangi.eta.fork.conversations

internal data class ConversationArchiveEligibility(
    val isPinned: Boolean,
    val isMeeting: Boolean,
    val isRunning: Boolean,
    val isExempt: Boolean,
)

internal object ConversationArchivePolicy {
    private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

    fun eligibleConversationIds(
        extensions: Collection<ConversationExtension>,
        settings: ConversationArchiveSettings,
        nowMillis: Long,
        eligibility: (String) -> ConversationArchiveEligibility,
    ): List<String> {
        if (!settings.enabled) return emptyList()
        val cutoff = nowMillis - settings.idleDays.coerceIn(
            MIN_ARCHIVE_IDLE_DAYS,
            MAX_ARCHIVE_IDLE_DAYS,
        ) * MILLIS_PER_DAY
        return extensions.asSequence()
            .filter { it.archivedAt == null }
            .filterNot { it.autoArchiveExempt }
            .filter { it.lastActiveAt <= cutoff }
            .filter { extension ->
                val state = eligibility(extension.conversationId)
                when {
                    state.isExempt -> false
                    state.isPinned && settings.exemptPinned -> false
                    (state.isMeeting || extension.kind == MEETING_CONVERSATION_KIND) &&
                        settings.exemptMeetings -> false
                    state.isRunning && settings.exemptRunning -> false
                    else -> true
                }
            }
            .map(ConversationExtension::conversationId)
            .toList()
    }
}
