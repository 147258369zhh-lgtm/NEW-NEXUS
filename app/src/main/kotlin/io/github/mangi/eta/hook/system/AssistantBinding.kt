package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
)

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.OEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ETA -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ETA_PACKAGE,
        componentName = ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT,
        displayName = "Nexus",
    )
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
    explicitSelection: Boolean = false,
): Boolean = explicitSelection || (autoConfigEnabled && target != PowerAssistantTarget.OEM)

internal enum class AssistantSelectionAction {
    NONE,
    CONFIGURE_MANAGED,
    RESTORE_OEM,
}

internal fun assistantSelectionAction(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
    explicitSelection: Boolean = false,
    hasManagedOwnership: Boolean = false,
): AssistantSelectionAction = when {
    target == PowerAssistantTarget.OEM -> if (explicitSelection || (autoConfigEnabled && hasManagedOwnership)) {
        AssistantSelectionAction.RESTORE_OEM
    } else AssistantSelectionAction.NONE
    shouldConfigureAssistant(autoConfigEnabled, target, explicitSelection) ->
        AssistantSelectionAction.CONFIGURE_MANAGED
    else -> AssistantSelectionAction.NONE
}

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
    explicitSelection: Boolean = false,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget, explicitSelection) &&
    expectedTarget == currentTarget

/** A request belongs to the Android user who made the selection, not every user of the module. */
internal fun assistantSelectionRequestForUser(request: String, userId: Int): String? {
    if (request.isBlank()) return null
    val separator = request.indexOf(':')
    // Compatibility for the first unscoped request format; never replay it in secondary users.
    if (separator < 0) return request.takeIf { userId == 0 }
    return request.takeIf { request.substring(0, separator).toIntOrNull() == userId && separator < request.lastIndex }
}
