package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.Model

internal fun imageOutputModalities(existing: List<String>, image: Boolean, text: Boolean): List<String> {
    val other = existing.filterNot {
        it.equals(Model.IMAGE_MODALITY, ignoreCase = true) || it.equals(Model.TEXT_MODALITY, ignoreCase = true)
    }
    return other + (if (text || !image) listOf(Model.TEXT_MODALITY) else emptyList()) +
        (if (image) listOf(Model.IMAGE_MODALITY) else emptyList())
}
