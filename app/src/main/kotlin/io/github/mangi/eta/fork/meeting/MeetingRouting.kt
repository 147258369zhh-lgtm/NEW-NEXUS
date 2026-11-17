package io.github.mangi.eta.fork.meeting

internal sealed interface MeetingRoutingDecision {
    data class Direct(val participant: String, val mentions: List<String> = listOf(participant)) : MeetingRoutingDecision
    data class Parallel(val participants: List<String>, val mentions: List<String>) : MeetingRoutingDecision
    data class HostDispatch(val text: String) : MeetingRoutingDecision
    data class UnknownMentions(val mentions: List<String>) : MeetingRoutingDecision
}

internal object MeetingMentionRouter {
    fun isAsciiTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_'

    fun isMentionBoundary(text: String, start: Int): Boolean {
        if (start <= 0 || !isAsciiTokenCharacter(text[start - 1])) return true
        var previous = start - 1
        while (previous >= 0 && (isAsciiTokenCharacter(text[previous]) || text[previous] == '-')) previous--
        if (previous < 0 || text[previous] != '@') return false
        return previous == 0 || !isAsciiTokenCharacter(text[previous - 1])
    }

    fun resolveProfile(name: String, available: Collection<String>): String? =
        available.firstOrNull { it == name } ?: available.filter { it.equals(name, ignoreCase = true) }.singleOrNull()

    private fun tokenEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length) {
            val codePoint = Character.codePointAt(text, end)
            if (!Character.isLetterOrDigit(codePoint) && codePoint != '_'.code && codePoint != '-'.code) break
            end += Character.charCount(codePoint)
        }
        return end
    }

    /** Addressing the chair requests coordination; it must not turn the chair into a worker. */
    fun throughHost(decision: MeetingRoutingDecision, host: String, text: String): MeetingRoutingDecision = when (decision) {
        is MeetingRoutingDecision.Direct -> if (decision.participant == host) {
            MeetingRoutingDecision.HostDispatch(stripLeadingMentions(text))
        } else decision
        is MeetingRoutingDecision.Parallel -> {
            val workers = decision.participants.filterNot { it == host }
            when (workers.size) {
                0 -> MeetingRoutingDecision.HostDispatch(stripLeadingMentions(text))
                1 -> MeetingRoutingDecision.Direct(workers.single())
                else -> MeetingRoutingDecision.Parallel(workers, workers)
            }
        }
        else -> decision
    }

    private val mentionPattern = Regex("@(?:\\[((?:\\\\[\\\\\\]]|[^\\\\\\]])*)\\]|([a-z0-9][a-z0-9_-]*))", RegexOption.IGNORE_CASE)
    private val simpleName = Regex("[a-z0-9][a-z0-9_-]*", RegexOption.IGNORE_CASE)
    private const val MAX_PARALLEL_PARTICIPANTS = 4

    fun formatMention(profileName: String): String = if (simpleName.matches(profileName)) {
        "@$profileName"
    } else {
        "@[${profileName.replace("\\", "\\\\").replace("]", "\\]")}]"
    }

    fun describeUnknown(value: String): String = if (value.startsWith("@[")) value else "@$value"

    private fun valueOf(match: MatchResult): String = if (match.groupValues[2].isNotEmpty()) {
        match.groupValues[2]
    } else {
        match.groupValues[1].replace(Regex("\\\\([\\\\\\]])"), "$1")
    }

    fun route(
        text: String,
        availableProfiles: Collection<String>,
        maxParticipants: Int = MAX_PARALLEL_PARTICIPANTS,
        hostProfile: String? = null,
    ): MeetingRoutingDecision {
        val available = availableProfiles.filter(String::isNotBlank).distinct()
        val mentions = mutableListOf<Pair<String, String>>()
        val invalid = mutableListOf<String>()
        var scan = 0
        var previousMentionEnd = -1
        while (scan < text.length) {
            val start = text.indexOf('@', scan)
            if (start < 0) break
            if (start > 0 && start != previousMentionEnd && isAsciiTokenCharacter(text[start - 1])) {
                scan = start + 1
                continue
            }
            val match = mentionPattern.matchAt(text, start)
            if (match != null) {
                val value = valueOf(match)
                val end = match.range.last + 1
                val trailing = if (end < text.length) Character.codePointAt(text, end) else -1
                if (match.groupValues[2].isNotEmpty() && trailing >= 0 && Character.isLetterOrDigit(trailing)) {
                    val suffix = text.substring(end, tokenEnd(text, end))
                    invalid += value + suffix
                    scan = end + suffix.length
                } else {
                    if (value.isBlank()) invalid += match.value else mentions += value to match.value
                    scan = end
                }
                previousMentionEnd = scan
            } else {
                if (text.getOrNull(start + 1) == '[') {
                    invalid += text.substring(start).take(80)
                    break
                }
                if (start + 1 < text.length && Character.isLetterOrDigit(Character.codePointAt(text, start + 1))) {
                    val token = text.substring(start + 1, tokenEnd(text, start + 1))
                    invalid += token
                    scan = start + 1 + token.length
                    continue
                }
                scan = start + 1
            }
        }
        if (invalid.isNotEmpty()) return MeetingRoutingDecision.UnknownMentions(invalid)
        if (mentions.isEmpty()) return MeetingRoutingDecision.HostDispatch(text.trim())

        val resolved = mentions.mapNotNull { (name, _) -> resolveProfile(name, available) }.distinct()
        val unknown = mentions.filter { (name, _) -> resolveProfile(name, available) == null }
            .map { (name, raw) -> if (raw.startsWith("@[")) raw else name.lowercase() }.distinct()
        if (unknown.isNotEmpty()) return MeetingRoutingDecision.UnknownMentions(unknown)
        val limit = maxParticipants.coerceIn(1, 8)
        var workers = 0
        val active = resolved.filter { name ->
            if (name == hostProfile) true else if (workers < limit) {
                workers++
                true
            } else false
        }
        return if (active.size == 1) {
            MeetingRoutingDecision.Direct(active.single(), mentions = active)
        } else {
            MeetingRoutingDecision.Parallel(active, mentions = active)
        }
    }

    fun stripLeadingMentions(text: String): String {
        var result = text.trimStart()
        while (true) {
            val match = mentionPattern.matchAt(result, 0) ?: break
            result = result.substring(match.range.last + 1).trimStart(' ', '，', ',', ':', '：')
        }
        return result.trim().ifBlank { text.trim() }
    }
}
