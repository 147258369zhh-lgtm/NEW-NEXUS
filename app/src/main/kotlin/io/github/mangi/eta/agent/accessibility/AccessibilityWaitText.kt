package io.github.mangi.eta.agent.accessibility

import io.github.mangi.eta.R
import androidx.annotation.StringRes

@StringRes
internal fun AccessibilityWaitPhase.textResource(): Int = when (this) {
    AccessibilityWaitPhase.WAITING -> R.string.accessibility_wait_detail
    AccessibilityWaitPhase.RESTORED -> R.string.accessibility_wait_restored
    AccessibilityWaitPhase.TIMED_OUT -> R.string.accessibility_wait_timeout
    AccessibilityWaitPhase.CANCELLED -> R.string.accessibility_wait_cancelled
}
