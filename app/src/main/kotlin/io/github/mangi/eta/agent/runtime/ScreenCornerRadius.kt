package io.github.mangi.eta.agent.runtime

internal fun safeScreenCornerRadius(fallback: Float, read: () -> Int?): Float =
    try { read()?.takeIf { it > 0 }?.toFloat() ?: fallback } catch (_: RuntimeException) { fallback }
