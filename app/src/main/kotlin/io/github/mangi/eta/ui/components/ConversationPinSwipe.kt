package io.github.mangi.eta.ui.components

internal fun conversationPinDragOffset(
    currentOffsetPx: Float,
    horizontalDeltaPx: Float,
    maxRevealPx: Float,
): Float {
    val safeMax = maxRevealPx.takeIf { it.isFinite() && it > 0f } ?: 0f
    val current = currentOffsetPx.takeIf { it.isFinite() } ?: return 0f
    val delta = horizontalDeltaPx.takeIf { it.isFinite() } ?: 0f
    return (current + delta).coerceIn(0f, safeMax)
}

internal fun shouldCommitConversationPin(
    offsetPx: Float,
    thresholdPx: Float,
): Boolean = offsetPx.isFinite() &&
    thresholdPx.isFinite() &&
    thresholdPx > 0f &&
    offsetPx >= thresholdPx

internal enum class ConversationRowGesture { Pin, Delete, Cancel }

internal fun classifyConversationRowGesture(
    offsetPx: Float,
    pinThresholdPx: Float,
    deleteThresholdPx: Float,
): ConversationRowGesture = when {
    shouldCommitConversationPin(offsetPx, pinThresholdPx) -> ConversationRowGesture.Pin
    offsetPx.isFinite() && deleteThresholdPx.isFinite() && deleteThresholdPx > 0f &&
        offsetPx <= -deleteThresholdPx -> ConversationRowGesture.Delete
    else -> ConversationRowGesture.Cancel
}
