package io.github.mangi.eta.ui.pages.providers

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.isChatModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOutputModalitiesTest {
    @Test fun imageOnlyIsNotAChatModel() {
        val modalities = imageOutputModalities(listOf("text"), image = true, text = false)
        assertEquals(listOf("image"), modalities)
        assertFalse(Model("id", "image", "Image", outputModalities = modalities).isChatModel())
    }

    @Test fun multimodalModelsKeepTextAndImage() {
        val modalities = imageOutputModalities(listOf("text", "image"), image = true, text = true)
        assertEquals(listOf("text", "image"), modalities)
        assertTrue(Model("id", "gemini", "Gemini", outputModalities = modalities).isChatModel())
    }

    @Test fun disablingImageRestoresTextAndPreservesOtherModalities() {
        assertEquals(listOf("audio", "text"), imageOutputModalities(listOf("image", "audio"), image = false, text = false))
    }
}
