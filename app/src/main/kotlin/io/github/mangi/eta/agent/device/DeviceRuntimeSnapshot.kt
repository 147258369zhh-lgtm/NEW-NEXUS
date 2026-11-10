package io.github.mangi.eta.agent.device

import android.content.Context
import android.os.Build
import io.github.mangi.eta.EtaApp

/** 只描述当前环境，不将版本号或已授予权限当作真机兼容认证。 */
internal data class DeviceRuntimeSnapshot(
    val sdkInt: Int,
    val androidRelease: String,
    val manufacturer: String,
    val model: String,
    val systemBuild: String,
    val targetSdkInt: Int,
    val root: RootAccessState,
    val frameworkConnected: Boolean,
) {
    companion object {
        fun capture(context: Context) = DeviceRuntimeSnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            systemBuild = Build.DISPLAY,
            targetSdkInt = context.applicationInfo.targetSdkVersion,
            root = RootAccess.state.value,
            frameworkConnected = EtaApp.serviceInstance != null,
        )
    }
}
