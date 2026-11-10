package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal fun colorOsBackgroundSettingsCommand(context: Context): String =
    "am start --user ${DeviceToolContract.appUserId(context)} -n " +
        "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
        "--es title Nexus --es pkgName ${DeviceToolContract.quote(context.packageName)} --es drainType APP"

internal suspend fun openBackgroundPermissionSettings(context: Context) {
    val colorOs = Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("oppo", "realme", "oneplus")
    val opened = if (RootAccess.isGranted && colorOs) {
        runInterruptible(Dispatchers.IO) {
            BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                val result = executor.execute(colorOsBackgroundSettingsCommand(context))
                // am 可能以 exit 0 返回 Error；不能把这类结果当作打开成功。
                backgroundSettingsOpened(result)
            }
        }
    } else false
    if (opened) return
    withContext(Dispatchers.Main) {
        val fallbackOpened = openFirstAvailableSettings(context, listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
        ))
        if (!fallbackOpened) Toast.makeText(context, R.string.compat_settings_unavailable, Toast.LENGTH_LONG).show()
    }
}

internal fun backgroundSettingsOpened(result: BoundedRootCommandExecutor.Result): Boolean =
    result.ok && !Regex("(?im)^\\s*(error|exception|securityexception)[: ]")
        .containsMatchIn(result.stdout + "\n" + result.stderr)

internal fun openFirstAvailableSettings(context: Context, intents: List<Intent>): Boolean =
    intents.any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
