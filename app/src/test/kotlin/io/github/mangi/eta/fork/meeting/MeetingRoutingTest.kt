package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRoutingTest {
    @Test fun specialNamesRoundTripAndMalformedNamesDoNotDispatch() {
        val names = listOf("张三 · A2A", "team]\\member", "alpha beta")
        names.forEach { name ->
            val mention = MeetingMentionRouter.formatMention(name)
            assertEquals(MeetingRoutingDecision.Direct(name), MeetingMentionRouter.route("$mention 处理", names))
            assertEquals("处理", MeetingMentionRouter.stripLeadingMentions("$mention 处理"))
        }
        listOf("@[unfinished", "@[] 处理", "@[bad\\q] 处理").forEach { text ->
            assertTrue(MeetingMentionRouter.route(text, names) is MeetingRoutingDecision.UnknownMentions)
        }
    }

    @Test fun caseCollisionsNeverResolveToTheWrongParticipant() {
        val names = listOf("Coder", "coder")
        assertEquals(MeetingRoutingDecision.Direct("Coder"), MeetingMentionRouter.route("@Coder 处理", names))
        assertTrue(MeetingMentionRouter.route("@CODER 处理", names) is MeetingRoutingDecision.UnknownMentions)
        assertEquals(MeetingRoutingDecision.Direct("Coder"),
            MeetingMentionRouter.throughHost(MeetingRoutingDecision.Direct("Coder"), "coder", "@Coder 处理"))
    }

    @Test fun chineseTextCanAddressMembersWithoutASpace() {
        assertEquals(MeetingRoutingDecision.Direct("coder"), MeetingMentionRouter.route("请@coder 修复", listOf("coder")))
        assertEquals(MeetingRoutingDecision.Direct("张三 · A2A"),
            MeetingMentionRouter.route("请@[张三 · A2A] 修复", listOf("张三 · A2A")))
        assertTrue(MeetingMentionRouter.route("@张三 修复", listOf("张三")) is MeetingRoutingDecision.UnknownMentions)
        assertTrue(MeetingMentionRouter.route("@coder中文 修复", listOf("coder")) is MeetingRoutingDecision.UnknownMentions)
        assertTrue(MeetingMentionRouter.route("@𠀀 修复", listOf("𠀀")) is MeetingRoutingDecision.UnknownMentions)
        assertTrue(MeetingMentionRouter.route("@coder𠀀 修复", listOf("coder")) is MeetingRoutingDecision.UnknownMentions)
        assertTrue(MeetingMentionRouter.route("mail@coder.com", listOf("coder")) is MeetingRoutingDecision.HostDispatch)
    }

    @Test fun adjacentMentionsAreAllParsedBeforeDispatch() {
        val result = MeetingMentionRouter.route("@coder@plan 处理", listOf("coder", "plan"))
        assertEquals(listOf("coder", "plan"), (result as MeetingRoutingDecision.Parallel).participants)
        assertTrue(MeetingMentionRouter.route("@coder@[未闭合", listOf("coder")) is MeetingRoutingDecision.UnknownMentions)
    }

    @Test fun addressingHostRequestsDelegationWhileExplicitWorkersKeepTheirOwnTasks() {
        val host = MeetingMentionRouter.route("@lead 分析和实现", listOf("lead", "coder"))
        assertEquals(MeetingRoutingDecision.HostDispatch("分析和实现"),
            MeetingMentionRouter.throughHost(host, "lead", "@lead 分析和实现"))
        val both = MeetingMentionRouter.route("@lead @coder 修复文件", listOf("lead", "coder"))
        assertEquals(MeetingRoutingDecision.Direct("coder"),
            MeetingMentionRouter.throughHost(both, "lead", "@lead @coder 修复文件"))
    }

    private val participants = listOf("explore", "coder", "plan")

    @Test
    fun noMentionIsDispatchedToHost() {
        assertEquals(
            MeetingRoutingDecision.HostDispatch("制定发布方案"),
            MeetingMentionRouter.route("制定发布方案", participants),
        )
    }

    @Test
    fun oneMentionExecutesDirectly() {
        val decision = MeetingMentionRouter.route("@coder 修复构建错误", participants)
        assertEquals(
            MeetingRoutingDecision.Direct("coder", mentions = listOf("coder")),
            decision,
        )
        assertEquals("修复构建错误", MeetingMentionRouter.stripLeadingMentions("@coder 修复构建错误"))
    }

    @Test
    fun multipleMentionsRunAsIndependentParallelTasks() {
        val decision = MeetingMentionRouter.route("@coder @plan 评估发布风险并修复构建", participants)
        assertTrue(decision is MeetingRoutingDecision.Parallel)
        assertEquals(
            listOf("coder", "plan"),
            (decision as MeetingRoutingDecision.Parallel).participants,
        )
        assertEquals(
            "评估发布风险并修复构建",
            MeetingMentionRouter.stripLeadingMentions("@coder @plan 评估发布风险并修复构建"),
        )
    }

    @Test
    fun mentionsAreCaseInsensitiveAndDeduplicated() {
        val decision = MeetingMentionRouter.route("@CODER @coder @Explore 继续", participants)
        assertEquals(
            listOf("coder", "explore"),
            (decision as MeetingRoutingDecision.Parallel).participants,
        )
    }

    @Test
    fun unknownMentionIsReportedInsteadOfSilentlyDispatching() {
        assertEquals(
            MeetingRoutingDecision.UnknownMentions(listOf("missing")),
            MeetingMentionRouter.route("@missing 检查", participants),
        )
    }

    @Test
    fun mixedKnownAndUnknownMentionsRequireCorrectionBeforeDispatch() {
        val decision = MeetingMentionRouter.route("@coder @missing 检查", participants)
        assertEquals(
            MeetingRoutingDecision.UnknownMentions(listOf("missing")),
            decision,
        )
    }

    @Test
    fun parallelDispatchIsBounded() {
        val decision = MeetingMentionRouter.route(
            "@a @b @c @d @e 并行处理",
            listOf("a", "b", "c", "d", "e"),
        )
        assertEquals(
            listOf("a", "b", "c", "d"),
            (decision as MeetingRoutingDecision.Parallel).participants,
        )
    }

    @Test
    fun parallelDispatchUsesConfiguredBound() {
        val decision = MeetingMentionRouter.route(
            text = "@a @b @c 并行处理",
            availableProfiles = listOf("a", "b", "c"),
            maxParticipants = 2,
        )
        assertEquals(
            listOf("a", "b"),
            (decision as MeetingRoutingDecision.Parallel).participants,
        )
    }

    @Test fun hostDoesNotConsumeAWorkerSlot() {
        val text = "@lead @a @b @c 开始"
        val decision = MeetingMentionRouter.route(text, listOf("lead", "a", "b", "c"), 2, hostProfile = "lead")
        assertEquals(
            MeetingRoutingDecision.Parallel(listOf("a", "b"), listOf("a", "b")),
            MeetingMentionRouter.throughHost(decision, "lead", text),
        )
    }
}
