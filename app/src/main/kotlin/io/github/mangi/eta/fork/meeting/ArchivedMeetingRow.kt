package io.github.mangi.eta.fork.meeting

/** Only bounded display fields leave the archive query; workflow and transcript stay in storage. */
internal data class ArchivedMeetingRow(
    val id: String,
    val title: String,
    val archivedAt: Long,
    val messageCount: Int,
    val preview: String,
)
