package io.github.mangi.eta.fork.conversations

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationArchivePolicyTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun disabled_settings_archive_nothing() {
        val result = ConversationArchivePolicy.eligibleConversationIds(
            extensions = listOf(extension("old", now - 60 * day)),
            settings = ConversationArchiveSettings(enabled = false),
            nowMillis = now,
            eligibility = { ConversationArchiveEligibility(false, false, false, false) },
        )

        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun cutoff_is_inclusive_and_already_archived_rows_are_skipped() {
        val result = ConversationArchivePolicy.eligibleConversationIds(
            extensions = listOf(
                extension("exact", now - 30 * day),
                extension("recent", now - 29 * day),
                extension("archived", now - 90 * day, archivedAt = now - day),
            ),
            settings = ConversationArchiveSettings(idleDays = 30),
            nowMillis = now,
            eligibility = { ConversationArchiveEligibility(false, false, false, false) },
        )

        assertEquals(listOf("exact"), result)
    }

    @Test
    fun exemption_rules_are_applied_independently() {
        val extensions = listOf(
            extension("plain", now - 40 * day),
            extension("pinned", now - 40 * day),
            extension("meeting", now - 40 * day),
            extension("running", now - 40 * day),
            extension("manual-exempt", now - 40 * day, autoArchiveExempt = true),
        )
        val eligibility = mapOf(
            "plain" to ConversationArchiveEligibility(false, false, false, false),
            "pinned" to ConversationArchiveEligibility(true, false, false, false),
            "meeting" to ConversationArchiveEligibility(false, true, false, false),
            "running" to ConversationArchiveEligibility(false, false, true, false),
            "manual-exempt" to ConversationArchiveEligibility(false, false, false, true),
        )

        val protected = ConversationArchivePolicy.eligibleConversationIds(
            extensions = extensions,
            settings = ConversationArchiveSettings(
                exemptPinned = true,
                exemptMeetings = true,
                exemptRunning = true,
            ),
            nowMillis = now,
            eligibility = eligibility::getValue,
        )
        val allAllowed = ConversationArchivePolicy.eligibleConversationIds(
            extensions = extensions,
            settings = ConversationArchiveSettings(
                exemptPinned = false,
                exemptMeetings = false,
                exemptRunning = false,
            ),
            nowMillis = now,
            eligibility = eligibility::getValue,
        )

        assertEquals(listOf("plain"), protected)
        assertEquals(listOf("plain", "pinned", "meeting", "running"), allAllowed)
    }

    private fun extension(
        id: String,
        lastActiveAt: Long,
        archivedAt: Long? = null,
        autoArchiveExempt: Boolean = false,
    ) = ConversationExtension(
        conversationId = id,
        lastActiveAt = lastActiveAt,
        archivedAt = archivedAt,
        autoArchiveExempt = autoArchiveExempt,
        createdAt = now - 100 * day,
        updatedAt = now,
    )
}
