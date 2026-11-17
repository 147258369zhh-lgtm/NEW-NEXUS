package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val skin: AppearanceSkin = AppearanceSkin.CLASSIC,
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.TONAL_SPOT,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val swipeDismissEnabled: Boolean = true,
    val predictiveBackEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val fontFamily: AppearanceFontFamily = AppearanceFontFamily.SYSTEM,
    val messageTypography: MessageTypographySettings = MessageTypographySettings(),
    val glassOpacityMultiplier: Float = 1f,
    val glassBlurMultiplier: Float = 1f,
    val glassRefractionEnabled: Boolean = true,
    val pressFeedbackEnabled: Boolean = true,
    val homeModeSwipeEnabled: Boolean = true,
    val homeModeSwipeThresholdDp: Float = 56f,
) {
    fun normalized(): AppearanceSettings = copy(
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        messageTypography = messageTypography.normalized(),
        glassOpacityMultiplier = if (glassOpacityMultiplier.isFinite()) glassOpacityMultiplier.coerceIn(0.65f, 1.25f) else 1f,
        glassBlurMultiplier = if (glassBlurMultiplier.isFinite()) glassBlurMultiplier.coerceIn(0.5f, 2f) else 1f,
        homeModeSwipeThresholdDp = if (homeModeSwipeThresholdDp.isFinite()) homeModeSwipeThresholdDp.coerceIn(32f, 96f) else 56f,
    )
}

@Serializable
enum class AppearanceFontFamily(val persistedValue: String) {
    SYSTEM("system"),
    HUMANIST("humanist"),
    EDITORIAL("editorial");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceFontFamily = when (value) {
            "sans_serif", "monospace", "jetbrains_mono" -> HUMANIST
            "serif" -> EDITORIAL
            else -> entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
        }
    }
}

@Serializable
enum class AppearanceSkin(val persistedValue: String) {
    CLASSIC("classic"),
    OMNI("omni"),
    AURORA("aurora"),
    FROST_GLASS("frost_glass"),
    LIQUID_GLASS("liquid_glass");

    val isGlass: Boolean get() = this == FROST_GLASS || this == LIQUID_GLASS

    companion object {
        fun fromPersistedValue(value: String?): AppearanceSkin = when (value) {
            "omni", "aurora" -> CLASSIC
            "frost_glass" -> LIQUID_GLASS
            else -> entries.firstOrNull { it.persistedValue == value } ?: CLASSIC
        }
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: TONAL_SPOT
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
