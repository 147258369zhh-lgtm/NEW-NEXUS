package io.github.mangi.eta.agent.device

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class NexaSoftRebootResult { REQUESTED, ROOT_REQUIRED, UNSUPPORTED, BUSY, UNCONFIRMED }

/** One explicit action, never a saved boot preference or an automatic retry. */
internal object NexaSoftReboot {
    private val runner = NexaSoftRebootRunner { command ->
        BoundedRootCommandExecutor(AndroidAgentLogger).use {
            it.execute(command, timeoutMillis = 15_000L, maxOutputBytes = 2_048)
        }
    }

    suspend fun request(context: Context): NexaSoftRebootResult = withContext(Dispatchers.IO) {
        if (!RootAccess.isGranted) RootAccess.request(context.applicationContext).join()
        runner.request(RootAccess.isGranted)
    }
}

internal class NexaSoftRebootRunner(
    private val execute: (String) -> BoundedRootCommandExecutor.Result,
) {
    private val running = AtomicBoolean(false)

    fun request(rootGranted: Boolean): NexaSoftRebootResult {
        if (!rootGranted) return NexaSoftRebootResult.ROOT_REQUIRED
        if (!running.compareAndSet(false, true)) return NexaSoftRebootResult.BUSY
        return try {
            val result = execute(COMMAND)
            when {
                result.errorCode == "ROOT_REQUIRED" || result.errorCode == "ROOT_UNAVAILABLE" -> NexaSoftRebootResult.ROOT_REQUIRED
                result.exitCode == 64 && !result.timedOut -> NexaSoftRebootResult.UNSUPPORTED
                result.ok && result.stdout.lineSequence().any { it == REQUEST_MARKER } -> NexaSoftRebootResult.REQUESTED
                else -> NexaSoftRebootResult.UNCONFIRMED
            }
        } catch (_: Exception) {
            NexaSoftRebootResult.UNCONFIRMED
        } finally {
            running.set(false)
        }
    }

    companion object {
        private const val REQUEST_MARKER = "NEXA_SOFT_REBOOT_REQUESTED"

        // This userspace-restart path was verified on the supported KernelSU device.
        // Do not substitute a full reboot when this exact capability is unavailable.
        internal val COMMAND = """
            nexa_ksud=${'$'}(command -v ksud 2>/dev/null || true)
            if [ -z "${'$'}nexa_ksud" ] || [ ! -x "${'$'}nexa_ksud" ]; then
                nexa_ksud=/data/adb/ksud
            fi
            [ -x "${'$'}nexa_ksud" ] || exit 64
            printf '%s\n' '$REQUEST_MARKER'
            exec "${'$'}nexa_ksud" soft-reboot
        """.trimIndent()
    }
}
