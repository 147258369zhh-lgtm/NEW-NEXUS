package io.github.mangi.eta.agent.tool

import android.os.Build
import org.json.JSONObject

/**
 * Stable, redacted capability description shared by runs, meetings and Subagents.
 * It deliberately contains state only; it never grants a permission or executes a tool.
 */
internal data class AgentCapabilitySnapshot(
    val schemaVersion: Int = 1,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val root: Boolean,
    val lsposed: Boolean,
    val accessibility: Boolean,
    val accessibilityRecovery: Boolean,
    val notifications: Boolean,
    val usage: Boolean,
    val location: Boolean,
    val colorOs: Boolean,
    val calendarRead: Boolean,
    val calendarWrite: Boolean,
    val supported: Set<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema_version", schemaVersion)
        .put("sdk", sdk)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("root", root)
        .put("lsposed", lsposed)
        .put("accessibility", accessibility)
        .put("accessibility_recovery", accessibilityRecovery)
        .put("notifications", notifications)
        .put("usage", usage)
        .put("location", location)
        .put("color_os", colorOs)
        .put("calendar_read", calendarRead)
        .put("calendar_write", calendarWrite)
        .put("supported_tools", supported.sorted())

    fun compactSummary(): String = buildString {
        append("Android ").append(sdk).append(" · ").append(manufacturer)
        if (root) append(" · Root")
        if (accessibility) append(" · 无障碍")
        if (colorOs) append(" · ColorOS")
    }

    companion object {
        fun from(capabilities: AgentToolCapabilities): AgentCapabilitySnapshot = AgentCapabilitySnapshot(
            sdk = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            root = capabilities.rootAvailable,
            lsposed = capabilities.lsposedAvailable,
            accessibility = capabilities.accessibilityAvailable,
            accessibilityRecovery = capabilities.accessibilityRecoveryAvailable,
            notifications = capabilities.notificationsAllowed,
            usage = capabilities.usageAllowed,
            location = capabilities.locationAllowed,
            colorOs = capabilities.colorOs,
            calendarRead = capabilities.calendarReadable,
            calendarWrite = capabilities.calendarWritable,
            supported = AgentToolRequirements.toolNames.filter { capabilities.unavailableCode(it) == null }.toSet(),
        )
    }
}

internal fun AgentToolCapabilities.snapshot(): AgentCapabilitySnapshot = AgentCapabilitySnapshot.from(this)
