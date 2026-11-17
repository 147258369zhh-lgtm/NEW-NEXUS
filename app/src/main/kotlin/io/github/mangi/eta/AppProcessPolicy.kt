package io.github.mangi.eta

internal object AppProcessPolicy {
    fun isPackageSandbox(processName: String, packageName: String): Boolean =
        processName == "$packageName:package_sandbox" || processName.startsWith("$packageName:package_sandbox:")

    fun shouldInitializeFullRuntime(processName: String, packageName: String): Boolean =
        processName == packageName
}
