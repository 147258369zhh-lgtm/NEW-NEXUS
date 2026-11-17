package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeShellCancellationTest {
    @Test fun cancelledChildCannotStartAProcessAndDoesNotCloseTheSharedSupervisor() {
        val supervisor = ShellProcessSupervisor()
        val child = AgentRunController().apply { cancel() }
        assertThrows(AgentRunCancelledException::class.java) {
            RuntimeInvocationScope.withController(child) {
                runOneShotShell(supervisor, "user", "exit 0", 1)
            }
        }
        assertFalse(supervisor.isClosing)
    }

    @Test fun cancelledChildCannotOpenASessionOrReadFiles() {
        val terminal = RootShellTerminalController(NoOpLogger)
        try {
            val child = AgentRunController().apply { cancel() }
            RuntimeInvocationScope.withController(child) {
                assertThrows(AgentRunCancelledException::class.java) {
                    terminal.executeTool("terminal", JSONObject().put("action", "open").put("identity", "user"))
                }
                assertThrows(AgentRunCancelledException::class.java) {
                    terminal.fileTool("read_file", JSONObject().put("path", "unused"))
                }
            }
        } finally { terminal.closeAll() }
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
