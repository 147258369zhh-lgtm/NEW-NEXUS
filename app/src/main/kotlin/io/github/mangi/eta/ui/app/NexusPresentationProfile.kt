package io.github.mangi.eta.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.zhh_lgtm.nexus.application.api.PresentationProfileResolver

internal val LocalNexusPresentationProfile = staticCompositionLocalOf {
    PresentationProfileResolver.forSkin("classic")
}
