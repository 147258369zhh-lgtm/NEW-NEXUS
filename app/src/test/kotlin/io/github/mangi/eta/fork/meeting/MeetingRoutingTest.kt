package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingRoutingTest {
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
}
