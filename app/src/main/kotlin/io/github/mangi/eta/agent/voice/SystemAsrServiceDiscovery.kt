package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognitionService
import android.content.pm.ResolveInfo

internal data class AsrServiceInfo(
    val packageName: String,
    val serviceName: String,
    val label: String,
    val isXiaomi: Boolean,
)

internal object SystemAsrServiceDiscovery {
    fun list(context: Context): List<AsrServiceInfo> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return context.packageManager.queryIntentServices(intent, 0)
            .mapNotNull { it.toInfo(context.packageManager) }
            .distinctBy { it.packageName to it.serviceName }
            .sortedWith(compareByDescending<AsrServiceInfo> { it.isXiaomi }.thenBy { it.label })
    }

    fun preferred(context: Context): AsrServiceInfo? = list(context).firstOrNull()

    private fun ResolveInfo.toInfo(packageManager: android.content.pm.PackageManager): AsrServiceInfo? {
        val service = serviceInfo ?: return null
        val packageName = service.packageName.orEmpty()
        if (packageName.isBlank()) return null
        return AsrServiceInfo(
            packageName = packageName,
            serviceName = service.name.orEmpty(),
            label = service.loadLabel(packageManager).toString().ifBlank { packageName },
            isXiaomi = packageName.contains("xiaomi", true) || packageName.contains("miui", true),
        )
    }
}
