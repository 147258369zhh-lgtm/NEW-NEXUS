package io.github.mangi.eta.fork.meeting

internal sealed interface MeetingRoutingDecision {
    data class Direct(val participant: String, val mentions: List<String> = listOf(participant)) : MeetingRoutingDecision
    data class Parallel(val participants: List<String>, val mentions: List<String>) : MeetingRoutingDecision
    data class HostDispatch(val text: String) : MeetingRoutingDecision
    data class UnknownMentions(val mentions: List<String>) : MeetingRoutingDecision
}

internal object MeetingMentionRouter {
    private val mentionPattern = Regex("@([a-z0-9][a-z0-9_-]*)", RegexOption.IGNORE_CASE)
    private const val MAX_PARALLEL_PARTICIPANTS = 4

    fun route(
        text: String,
        availableProfiles: Collection<String>,
        maxParticipants: Int = MAX_PARALLEL_PARTICIPANTS,
    ): MeetingRoutingDecision {
        val available = availableProfiles.map(String::trim).filter(String::isNotBlank).distinct()
        val availableNormalized = available.associateBy(String::lowercase)
        val mentions = mentionPattern.findAll(text)
            .map { it.groupValues[1] }
            .map { it.lowercase() }
            .distinct()
            .toList()
        if (mentions.isEmpty()) return MeetingRoutingDecision.HostDispatch(text.trim())

        val resolved = mentions.mapNotNull(availableNormalized::get).distinct()
        val unknown = mentions.filter { it !in availableNormalized }
        if (unknown.isNotEmpty()) return MeetingRoutingDecision.UnknownMentions(unknown)
        val active = resolved.take(maxParticipants.coerceIn(1, 8))
        return if (active.size == 1) {
            MeetingRoutingDecision.Direct(active.single(), mentions = active)
        } else {
            MeetingRoutingDecision.Parallel(active, mentions = active)
        }
    }

    fun stripLeadingMentions(text: String): String {
        var result = text.trimStart()
        while (true) {
            val match = mentionPattern.find(result) ?: break
            if (match.range.first != 0) break
            result = result.substring(match.range.last + 1).trimStart(' ', '，', ',', ':', '：')
        }
        return result.trim().ifBlank { text.trim() }
    }
}
