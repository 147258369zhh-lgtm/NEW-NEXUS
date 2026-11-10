package io.github.mangi.eta.ui.screens.localmodel

import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRuntimeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelLaunchDecisionTest {
    @Test
    fun explicitDownloadAndStartWinsOverDisabledAutoStart() {
        assertEquals(
            LocalModelLaunchDecision.START,
            localModelLaunchDecision(
                startAfterDownload = true,
                format = LocalModelFormat.LITERT_LM,
                settings = LocalModelRuntimeSettings(autoConnect = false, autoStart = false),
            ),
        )
    }

    @Test
    fun autoConnectAndAutoStartLoadTheModelAfterDownload() {
        assertEquals(
            LocalModelLaunchDecision.START,
            localModelLaunchDecision(
                startAfterDownload = false,
                format = LocalModelFormat.GGUF,
                settings = LocalModelRuntimeSettings(autoConnect = true, autoStart = true),
            ),
        )
    }

    @Test
    fun autoConnectOnlyAddsTheModelToThePicker() {
        assertEquals(
            LocalModelLaunchDecision.REGISTER_ONLY,
            localModelLaunchDecision(
                startAfterDownload = false,
                format = LocalModelFormat.MNN,
                settings = LocalModelRuntimeSettings(autoConnect = true, autoStart = false),
            ),
        )
    }

    @Test
    fun startWithoutAutoConnectKeepsTheModelUntouched() {
        assertEquals(
            LocalModelLaunchDecision.NONE,
            localModelLaunchDecision(
                startAfterDownload = false,
                format = LocalModelFormat.LITERT_LM,
                settings = LocalModelRuntimeSettings(autoConnect = false, autoStart = true),
            ),
        )
    }

    @Test
    fun formatsWithoutAnInProcessRuntimeAreNeverAutoStarted() {
        assertEquals(
            LocalModelLaunchDecision.NONE,
            localModelLaunchDecision(
                startAfterDownload = true,
                format = LocalModelFormat.ONNX,
                settings = LocalModelRuntimeSettings(autoConnect = true, autoStart = true),
            ),
        )
        assertTrue(LocalModelFormat.LITERT_LM.supportsBuiltInRuntime())
        assertTrue(LocalModelFormat.GGUF.supportsBuiltInRuntime())
        assertTrue(LocalModelFormat.MNN.supportsBuiltInRuntime())
        assertFalse(LocalModelFormat.TASK.supportsBuiltInRuntime())
    }
}
