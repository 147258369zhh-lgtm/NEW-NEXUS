package io.github.mangi.eta.fork.tasks

import org.junit.Assert.assertThrows
import org.junit.Test

class AgentSelfSchedulePolicyTest {
    @Test
    fun requiresExplicitConfirmation() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentSelfSchedulePolicy.validate("每日汇报", "整理今天的任务", confirmed = false)
        }
    }
}
