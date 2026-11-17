package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.control.*
import org.json.JSONObject

internal data class LinuxExecutionLimits(val cpuSeconds: Int = 120, val memoryMb: Int = 1536, val maxShells: Int = 4) {
    init { require(cpuSeconds in 1..3600 && memoryMb in 256..8192 && maxShells in 1..8) }
    /** Per-process limits are inherited by descendants; this is not a machine-wide cgroup. */
    fun command(command: String?): String = "ulimit -t $cpuSeconds || exit 125; ulimit -v ${memoryMb * 1024L} || exit 125; " +
        if (command == null) "exec /bin/sh" else command
    fun json() = JSONObject().put("cpu_seconds", cpuSeconds).put("memory_mb", memoryMb).put("max_shells", maxShells)
}

internal object LinuxExecutionSettings {
    private const val PREFS = "eta_linux_resources"
    fun read(context: Context): LinuxExecutionLimits {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LinuxExecutionLimits(prefs.getInt("cpu_seconds", 120).coerceIn(1, 3600), prefs.getInt("memory_mb", 1536).coerceIn(256, 8192),
            prefs.getInt("max_shells", 4).coerceIn(1, 8))
    }
    fun save(context: Context, value: JSONObject) {
        val limits = LinuxExecutionLimits(value.getInt("cpu_seconds"), value.getInt("memory_mb"), value.getInt("max_shells"))
        check(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("cpu_seconds", limits.cpuSeconds)
            .putInt("memory_mb", limits.memoryMb).putInt("max_shells", limits.maxShells).commit()) { "Linux 资源设置未保存" }
    }
    fun memoryPressure(context: Context): Boolean {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        return info.totalMem > 0 && (info.lowMemory || info.availMem < maxOf(info.threshold, 256L * 1024 * 1024))
    }
}

internal object LinuxResourceControlProvider : EtaControlProvider {
    override val providerId = "linux-resources"
    override fun capabilities() = listOf(EtaControlRegistration(EtaControlCapabilityInfo("settings.linux.resources", "Linux 资源限制", "工具与设备",
        "配置 Agent Linux 的单进程 CPU/地址空间上限与每个执行器的活动 Shell 上限", EtaControlDanger.WRITE,
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("cpu_seconds", number(1, 3600)).put("memory_mb", number(256, 8192)).put("max_shells", number(1, 8)))
            .put("required", org.json.JSONArray(listOf("cpu_seconds", "memory_mb", "max_shells"))).put("additionalProperties", false), true),
        read = { LinuxExecutionSettings.read(it).json() }, snapshot = { context, _ -> LinuxExecutionSettings.read(context).json() },
        rollback = { context, before -> LinuxExecutionSettings.save(context, before); JSONObject() },
        execute = { context, parameters -> LinuxExecutionSettings.save(context, parameters); JSONObject() }))
    private fun number(minimum: Int, maximum: Int) = JSONObject().put("type", "integer").put("minimum", minimum).put("maximum", maximum)
}
