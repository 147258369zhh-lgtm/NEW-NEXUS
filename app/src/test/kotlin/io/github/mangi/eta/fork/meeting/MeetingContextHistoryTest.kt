package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingContextHistoryTest {
    @Test
    fun participantGetsPriorUsersOwnResultsAndHostSummariesButNotOtherAgents() {
        val history = MeetingContextHistory.forParticipant(
            messages = listOf(
                message(1, MeetingAuthorType.USER, "第一轮用户请求"),
                message(2, MeetingAuthorType.AGENT, "探索 Agent 的独立结果", "explore"),
                message(3, MeetingAuthorType.AGENT, "Coder 此前的实现结果", "coder"),
                message(4, MeetingAuthorType.HOST, "主持人汇总了实现与风险", "host"),
                message(5, MeetingAuthorType.SYSTEM, "内部运行状态"),
            ),
            participantProfile = "coder",
        )

        assertEquals(listOf("user", "assistant", "assistant"), history.map { it.role })
        assertTrue(history[0].content.contains("第一轮用户请求"))
        assertTrue(history[1].content.contains("Coder 此前的实现结果"))
        assertTrue(history[2].content.contains("主持人汇总了实现与风险"))
        assertFalse(history.joinToString { it.content }.contains("探索 Agent"))
        assertFalse(history.joinToString { it.content }.contains("内部运行状态"))
    }

    @Test
    fun historyKeepsChronologicalOrderAndStableMeetingMessageIds() {
        val history = MeetingContextHistory.forParticipant(
            messages = listOf(
                message(10, MeetingAuthorType.USER, "旧请求"),
                message(11, MeetingAuthorType.AGENT, "旧结果", "plan"),
                message(12, MeetingAuthorType.USER, "较新请求"),
                message(13, MeetingAuthorType.AGENT, "较新结果", "plan"),
            ),
            participantProfile = "plan",
        )

        assertEquals(
            listOf("旧请求", "旧结果", "较新请求", "较新结果"),
            history.map { message -> message.content.substringAfter('\n') },
        )
        assertEquals(
            listOf("meeting-history-10", "meeting-history-11", "meeting-history-12", "meeting-history-13"),
            history.map { it.messageId },
        )
    }

    @Test
    fun nonHostParticipantSeesUserTextOnlyWhenExplicitlyMentioned() {
        val history = MeetingContextHistory.forParticipant(
            messages = listOf(
                message(1, MeetingAuthorType.USER, "只给主持人的原始问题"),
                message(2, MeetingAuthorType.USER, "发给 coder 的直接问题", mentions = listOf("coder")),
                message(3, MeetingAuthorType.HOST, "主持人上一轮汇总", "host"),
                message(4, MeetingAuthorType.AGENT, "coder 自己的结果", "coder"),
            ),
            participantProfile = "coder",
            isHost = false,
        )

        assertEquals(listOf("user", "assistant"), history.map { it.role })
        assertTrue(history[0].content.contains("发给 coder 的直接问题"))
        assertTrue(history[1].content.contains("coder 自己的结果"))
        assertFalse(history.joinToString { it.content }.contains("只给主持人的原始问题"))
        assertFalse(history.joinToString { it.content }.contains("主持人上一轮汇总"))
    }

    private fun message(
        sortIndex: Int,
        authorType: String,
        content: String,
        authorProfile: String? = null,
        mentions: List<String> = emptyList(),
    ) = MeetingMessageEntity(
        id = sortIndex.toString(),
        roomId = "room",
        sortIndex = sortIndex,
        authorType = authorType,
        authorProfile = authorProfile,
        authorName = authorProfile ?: authorType,
        content = content,
        mentionsJson = org.json.JSONArray(mentions).toString(),
        createdAt = sortIndex.toLong(),
    )
}
