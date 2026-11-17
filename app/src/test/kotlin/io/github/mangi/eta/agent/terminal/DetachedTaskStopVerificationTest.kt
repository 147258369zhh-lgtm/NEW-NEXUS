package io.github.mangi.eta.agent.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** 停止结果只能来自进程检查；异常退出码不得被当作成功。 */
class DetachedTaskStopVerificationTest {
    @Test
    fun onlyVerifiedProcessCheckCountsAsStopped() {
        assertEquals(StopVerification.CONFIRMED_GONE, classifyStopExit(0))
        assertEquals(StopVerification.OWNED_PROCESS_REMAINS, classifyStopExit(81))
        listOf(-3, -2, -1, 1, 2, 126, 127, 255).forEach { exitCode ->
            assertEquals("exit=$exitCode", StopVerification.UNCONFIRMED, classifyStopExit(exitCode))
        }
    }

    @Test
    fun cancelledCommandKeepsItsOwnTerminalStatus() {
        val task = DetachedTask(
            id = "job_cancel01", pid = 1234, token = "token", command = "sleep 30",
            cwd = "/tmp", identity = "user", environment = TerminalEnvironment.ANDROID,
            logPath = "/tmp/job_cancel01.log", startedAt = 1L, commandTask = true,
            completedAt = 2L, endReason = "cancelled",
        )
        assertEquals("cancelled", task.commandStatus)
        assertEquals("interrupted", task.copy(endReason = "interrupted").commandStatus)
    }
}
