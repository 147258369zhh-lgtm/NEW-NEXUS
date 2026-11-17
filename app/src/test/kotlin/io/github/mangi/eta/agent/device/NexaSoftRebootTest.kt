package io.github.mangi.eta.agent.device

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaSoftRebootTest {
    private fun result(exit: Int = 0, text: String = "NEXA_SOFT_REBOOT_REQUESTED\n", timeout: Boolean = false) =
        BoundedRootCommandExecutor.Result(exit, text, "", timeout, false)

    @Test fun missingRootNeverStartsACommand() {
        val calls = AtomicInteger()
        val runner = NexaSoftRebootRunner { calls.incrementAndGet(); result() }
        assertEquals(NexaSoftRebootResult.ROOT_REQUIRED, runner.request(false))
        assertEquals(0, calls.get())
    }

    @Test fun unsupportedRestartIsReportedWithoutRetryOrFallback() {
        val calls = AtomicInteger()
        val runner = NexaSoftRebootRunner { calls.incrementAndGet(); result(exit = 64, text = "") }
        assertEquals(NexaSoftRebootResult.UNSUPPORTED, runner.request(true))
        assertEquals(1, calls.get())
    }

    @Test fun exitSuccessRequiresTheRequestReceiptAndTimeoutIsNotSuccess() {
        assertEquals(NexaSoftRebootResult.UNCONFIRMED, NexaSoftRebootRunner { result(text = "") }.request(true))
        assertEquals(NexaSoftRebootResult.UNCONFIRMED, NexaSoftRebootRunner { result(timeout = true) }.request(true))
        assertEquals(NexaSoftRebootResult.REQUESTED, NexaSoftRebootRunner { result() }.request(true))
    }

    @Test fun repeatedPressWhileRunningDoesNotScheduleASecondRestart() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val runner = NexaSoftRebootRunner {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            result()
        }
        val first = thread { runner.request(true) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(NexaSoftRebootResult.BUSY, runner.request(true))
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            first.join(3_000)
        }
    }
}
