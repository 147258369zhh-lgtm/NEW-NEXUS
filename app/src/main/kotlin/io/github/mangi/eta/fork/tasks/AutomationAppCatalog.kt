package io.github.mangi.eta.fork.tasks

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Collator
import java.util.Locale

internal data class AutomationAppOption(
    val packageName: String,
    val label: String,
)

internal object AutomationAppCatalog {
    fun launchableApps(context: Context): List<AutomationAppOption> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val labels = linkedMapOf<String, AutomationAppOption>()
        packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L)).forEach { info ->
            val packageName = info.activityInfo?.applicationInfo?.packageName ?: return@forEach
            labels.putIfAbsent(
                packageName,
                AutomationAppOption(
                    packageName = packageName,
                    label = info.loadLabel(packageManager).toString().trim().ifBlank { packageName },
                ),
            )
        }
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        return labels.values.sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    fun label(context: Context, packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(
            packageName,
            PackageManager.ApplicationInfoFlags.of(0L),
        )
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)
}
