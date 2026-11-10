package io.github.mangi.eta.ui.screens.backup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity

/** A separate foreground process closes every main-process database before startup restoration. */
class BackupRestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = intent.getIntExtra("previous_pid", -1)
        if (previous <= 0 || previous == Process.myPid()) {
            AndroidAgentLogger.warn("Backup restart received an invalid previous process")
            finishAndRemoveTask()
            return
        }
        AndroidAgentLogger.info("Backup restart closing main process before applying restore")
        val killed = runCatching { Process.killProcess(previous) }
            .onFailure { AndroidAgentLogger.warn("Backup restart could not close main process: ${it.safeLogType()}") }
            .isSuccess
        if (!killed) {
            finishAndRemoveTask()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Backup restart could not reopen Nexus: ${failure.safeLogType()}")
            }
            finishAndRemoveTask()
        }, 700)
    }
}
