package io.github.mangi.eta.agent.runtime

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeWirePauseTest {

    @Test
    fun `pause summary survives the result bundle`() {
        val result = AgentRuntimeWire.RunResult(
            runId = "task-1",
            ok = false,
            content = "",
            error = "需要补充输入后才能继续本次任务",
            failureCode = "A2A_INPUT_REQUIRED",
            pause = AgentRuntimeWire.RunPause(
                kind = AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED,
                question = "Which report should I open?",
                options = listOf("weekly", "monthly"),
                allowFreeText = true,
            ),
        )

        val decoded = AgentRuntimeWire.runResultFromBundle(AgentRuntimeWire.toBundle(result))

        assertEquals(result.failureCode, decoded.failureCode)
        assertEquals(result.pause, decoded.pause)
    }

    @Test
    fun `results without a pause keep a null pause`() {
        val decoded = AgentRuntimeWire.runResultFromBundle(
            AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult("task-2", true, "answer")),
        )

        assertNull(decoded.pause)
    }
}
