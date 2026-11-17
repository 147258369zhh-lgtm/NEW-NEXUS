package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.PresentationProfile

object PresentationProfileResolver {
    fun forSkin(profileId: String): PresentationProfile = when (profileId.trim().lowercase()) {
        "aurora" -> PresentationProfile(
            id = "aurora",
            fallbackProfileId = "classic",
            capabilities = setOf("timeline", "trace", "adaptive-sidebar", "workspace", "compact", "material-motion"),
        )
        "frost_glass", "liquid_glass" -> PresentationProfile(
            id = profileId.trim().lowercase(),
            fallbackProfileId = "classic",
            capabilities = setOf("timeline", "trace", "adaptive-sidebar", "workspace", "compact", "glass"),
        )
        "omni" -> PresentationProfile(
            id = "omni",
            fallbackProfileId = "classic",
            capabilities = setOf("timeline", "trace", "adaptive-sidebar", "workspace"),
        )
        "classic", "" -> PresentationProfile(
            id = "classic",
            capabilities = setOf("timeline", "trace", "miuix", "workspace"),
        )
        else -> PresentationProfile(id = "classic",
            capabilities = setOf("timeline", "trace", "miuix", "workspace", "unknown-profile-fallback"))
    }
}
