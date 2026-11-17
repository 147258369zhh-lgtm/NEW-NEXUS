package io.github.mangi.eta.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTypographySettingsTest {
    @Test
    fun legacyProfilesKeepMessageRenderingDefaults() {
        val oldProfile = Json.decodeFromString<MessageTypographySettings>(
            """{"bodySize":18.0,"lineHeight":30.0,"codeSize":14.0,"font":"SYSTEM"}""",
        ).normalized()
        assertEquals(18f, oldProfile.bodySize, 0f)
        assertEquals(MessageFont.SYSTEM, oldProfile.font)
        assertEquals(0, oldProfile.bodyWeight)
        assertEquals(0f, oldProfile.bodyLetterSpacing, 0f)
        assertEquals(0f, oldProfile.thinkingLetterSpacing, 0f)
        assertEquals(1.55f, oldProfile.codeLineHeightScale, 0f)
    }

    @Test
    fun newControlsSurviveSerializationAndResetToStableDefaults() {
        val custom = MessageTypographySettings(
            preset = MessageTypographyPreset.CUSTOM, bodyWeight = 500, bodyLetterSpacing = .8f,
            thinkingLetterSpacing = -.3f, codeLineHeightScale = 1.8f,
        )
        assertEquals(custom, Json.decodeFromString<MessageTypographySettings>(Json.encodeToString(custom)).normalized())
        val defaults = MessageTypographySettings.forPreset(MessageTypographyPreset.STANDARD).normalized()
        assertEquals(MessageTypographySettings(), defaults)
    }

    @Test
    fun invalidControlsAndLineHeightsNormalizeWithoutBreakingReadability() {
        val normalized = MessageTypographySettings(
            bodySize = 24f, lineHeight = 16f, thinkingSize = 22f, thinkingLineHeight = 15f,
            bodyWeight = Int.MAX_VALUE, bodyLetterSpacing = Float.NaN,
            thinkingLetterSpacing = Float.POSITIVE_INFINITY, codeLineHeightScale = Float.NEGATIVE_INFINITY,
        ).normalized()
        assertEquals(700, normalized.bodyWeight)
        assertEquals(0f, normalized.bodyLetterSpacing, 0f)
        assertEquals(0f, normalized.thinkingLetterSpacing, 0f)
        assertEquals(1.55f, normalized.codeLineHeightScale, 0f)
        assertEquals(28.8f, normalized.lineHeight, .0001f)
        assertEquals(26.4f, normalized.thinkingLineHeight, .0001f)
        val extremes = MessageTypographySettings(bodyWeight = -2, bodyLetterSpacing = -9f,
            thinkingLetterSpacing = 9f, codeLineHeightScale = 99f).normalized()
        assertEquals(300, extremes.bodyWeight)
        assertEquals(-.5f, extremes.bodyLetterSpacing, 0f)
        assertEquals(3f, extremes.thinkingLetterSpacing, 0f)
        assertEquals(2f, extremes.codeLineHeightScale, 0f)
        assertEquals(extremes, extremes.normalized())
    }
}
