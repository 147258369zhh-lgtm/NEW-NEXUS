package io.github.mangi.eta.core

import android.content.Context
import android.content.Intent

/** UI launch names shared by runtime services without importing a concrete Activity. */
internal object NexusUiContract {
    const val ACTION_OPEN_TERMINAL = "io.github.zhh_lgtm.nexus.action.OPEN_TERMINAL"
    const val ACTION_OPEN_CONVERSATION_ID = "io.github.zhh_lgtm.nexus.action.OPEN_CONVERSATION_ID"
    const val EXTRA_CONVERSATION_ID = "io.github.zhh_lgtm.nexus.extra.CONVERSATION_ID"
    const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.zhh_lgtm.nexus.action.OPEN_ARCHIVED_CONVERSATION"
    const val EXTRA_HANDOFF_SOURCE = "io.github.zhh_lgtm.nexus.extra.HANDOFF_SOURCE"
    const val EXTRA_CONVERSATION_KEY = "io.github.zhh_lgtm.nexus.extra.CONVERSATION_KEY"

    fun launchIntent(context: Context, action: String? = null): Intent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        return intent.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action != null) this.action = action
        }
    }
}

/** Process-local foreground signal used by runtime overlays and notifications. */
internal object NexusUiPresence {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun setForeground(value: Boolean) {
        isForeground = value
    }
}
