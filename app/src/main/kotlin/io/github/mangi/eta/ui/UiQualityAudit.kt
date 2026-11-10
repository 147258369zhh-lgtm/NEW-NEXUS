package io.github.mangi.eta.ui

/** Small, platform-neutral checks shared by new screens to keep touch targets accessible. */
internal object UiQualityAudit {
    const val MIN_TOUCH_TARGET_DP = 48
    const val MAX_PRIMARY_TITLE_CHARS = 36

    fun validPrimaryTitle(title: String): Boolean = title.trim().isNotEmpty() && title.length <= MAX_PRIMARY_TITLE_CHARS
    fun validTouchTarget(widthDp: Int, heightDp: Int): Boolean = widthDp >= MIN_TOUCH_TARGET_DP && heightDp >= MIN_TOUCH_TARGET_DP
}
