package io.github.mangi.eta.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsUseClassicSkinForFreshProfiles() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceSkin.CLASSIC, settings.skin)
        assertEquals(AppearanceThemeMode.SYSTEM, settings.themeMode)
        assertFalse(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertTrue(settings.swipeDismissEnabled)
        assertTrue(settings.predictiveBackEnabled)
        assertEquals(DEFAULT_INTERFACE_SCALE, settings.interfaceScale)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearanceSkin.CLASSIC, AppearanceSkin.fromPersistedValue("unknown"))
        assertEquals(AppearanceSkin.CLASSIC, AppearanceSkin.fromPersistedValue("omni"))
        assertEquals(AppearanceSkin.CLASSIC, AppearanceSkin.fromPersistedValue("classic"))
        assertEquals(AppearanceSkin.CLASSIC, AppearanceSkin.fromPersistedValue("aurora"))
        assertEquals(AppearanceSkin.LIQUID_GLASS, AppearanceSkin.fromPersistedValue("frost_glass"))
        assertEquals(AppearancePaletteStyle.TONAL_SPOT, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }

    @Test
    fun interfaceScaleIsFiniteAndClamped() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(0.95f, normalizeInterfaceScale(0.95f))
    }

    @Test
    fun newEffectAndGestureDefaultsPreserveExistingBehavior() {
        val settings = AppearanceSettings().normalized()

        assertEquals(1f, settings.glassOpacityMultiplier, 0f)
        assertEquals(1f, settings.glassBlurMultiplier, 0f)
        assertTrue(settings.glassRefractionEnabled)
        assertTrue(settings.pressFeedbackEnabled)
        assertTrue(settings.homeModeSwipeEnabled)
        assertEquals(56f, settings.homeModeSwipeThresholdDp, 0f)
    }

    @Test
    fun validEffectsAndGesturesPreserveUserValues() {
        val settings = AppearanceSettings(
            skin = AppearanceSkin.LIQUID_GLASS,
            themeMode = AppearanceThemeMode.DARK,
            blurEnabled = false,
            interfaceScale = 0.95f,
            glassOpacityMultiplier = 0.85f,
            glassBlurMultiplier = 1.5f,
            glassRefractionEnabled = false,
            pressFeedbackEnabled = false,
            homeModeSwipeEnabled = false,
            homeModeSwipeThresholdDp = 72f,
        )

        assertEquals(settings, settings.normalized())
    }

    @Test
    fun effectAndGestureRangesAreClamped() {
        val lower = AppearanceSettings(
            glassOpacityMultiplier = 0.1f,
            glassBlurMultiplier = -1f,
            homeModeSwipeThresholdDp = 1f,
        ).normalized()
        val upper = AppearanceSettings(
            glassOpacityMultiplier = 4f,
            glassBlurMultiplier = 4f,
            homeModeSwipeThresholdDp = 150f,
        ).normalized()

        assertEquals(0.65f, lower.glassOpacityMultiplier, 0f)
        assertEquals(0.5f, lower.glassBlurMultiplier, 0f)
        assertEquals(32f, lower.homeModeSwipeThresholdDp, 0f)
        assertEquals(1.25f, upper.glassOpacityMultiplier, 0f)
        assertEquals(2f, upper.glassBlurMultiplier, 0f)
        assertEquals(96f, upper.homeModeSwipeThresholdDp, 0f)
        assertEquals(lower, lower.normalized())
        assertEquals(upper, upper.normalized())
    }

    @Test
    fun nonFiniteEffectsAndGesturesReturnToDefaults() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            val settings = AppearanceSettings(
                glassOpacityMultiplier = invalid,
                glassBlurMultiplier = invalid,
                homeModeSwipeThresholdDp = invalid,
                glassRefractionEnabled = false,
                pressFeedbackEnabled = false,
                homeModeSwipeEnabled = false,
            ).normalized()

            assertEquals(1f, settings.glassOpacityMultiplier, 0f)
            assertEquals(1f, settings.glassBlurMultiplier, 0f)
            assertEquals(56f, settings.homeModeSwipeThresholdDp, 0f)
            assertFalse(settings.glassRefractionEnabled)
            assertFalse(settings.pressFeedbackEnabled)
            assertFalse(settings.homeModeSwipeEnabled)
        }
    }

    @Test
    fun legacyAppearanceJsonKeepsExistingValuesAndDefaultsNewFields() {
        val restored = Json.decodeFromString<AppearanceSettings>(
            """{"skin":"LIQUID_GLASS","themeMode":"DARK","blurEnabled":false,"interfaceScale":1.05}""",
        ).normalized()

        assertEquals(AppearanceSkin.LIQUID_GLASS, restored.skin)
        assertEquals(AppearanceThemeMode.DARK, restored.themeMode)
        assertFalse(restored.blurEnabled)
        assertEquals(1.05f, restored.interfaceScale, 0f)
        assertEquals(1f, restored.glassOpacityMultiplier, 0f)
        assertEquals(1f, restored.glassBlurMultiplier, 0f)
        assertTrue(restored.glassRefractionEnabled)
        assertTrue(restored.pressFeedbackEnabled)
        assertTrue(restored.homeModeSwipeEnabled)
        assertEquals(56f, restored.homeModeSwipeThresholdDp, 0f)
    }

    @Test
    fun newEffectAndGestureValuesSurviveSettingsBackupJsonRoundTrip() {
        val settings = Settings(
            appearance = AppearanceSettings(
                skin = AppearanceSkin.LIQUID_GLASS,
                glassOpacityMultiplier = 0.75f,
                glassBlurMultiplier = 1.75f,
                glassRefractionEnabled = false,
                pressFeedbackEnabled = false,
                homeModeSwipeEnabled = false,
                homeModeSwipeThresholdDp = 80f,
            ),
        )

        assertEquals(settings, Json.decodeFromString<Settings>(Json.encodeToString(settings)))
    }
}
