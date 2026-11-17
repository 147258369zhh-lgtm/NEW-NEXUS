package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** Message-only preferences. The surrounding application typography remains unchanged. */
@Serializable
data class MessageTypographySettings(
    val preset: MessageTypographyPreset = MessageTypographyPreset.STANDARD,
    val font: MessageFont = MessageFont.FOLLOW_INTERFACE,
    val bodySize: Float = 16f,
    val lineHeight: Float = 26f,
    val paragraphGapScale: Float = 1f,
    val headingScale: Float = 1f,
    val headingWeight: Int = 600,
    val thinkingSize: Float = 14f,
    val thinkingLineHeight: Float = 22f,
    val codeSize: Float = 13f,
    val codeWrap: Boolean = false,
    val tableSize: Float = 14f,
    val tableDensity: Float = 1f,
    val renderMarkdown: Boolean = true,
    /** Zero follows the existing message weight, preserving older profiles. */
    val bodyWeight: Int = 0,
    val bodyLetterSpacing: Float = 0f,
    val thinkingLetterSpacing: Float = 0f,
    val codeLineHeightScale: Float = 1.55f,
) {
    fun normalized(): MessageTypographySettings {
        fun Float.limit(default: Float, min: Float, max: Float) =
            if (isFinite()) coerceIn(min, max) else default
        val body = bodySize.limit(16f, 12f, 24f)
        val thinking = thinkingSize.limit(14f, 11f, 22f)
        return copy(
            bodySize = body,
            lineHeight = lineHeight.limit(26f, 16f, 42f).coerceAtLeast(body * 1.2f),
            paragraphGapScale = paragraphGapScale.limit(1f, .5f, 1.8f),
            headingScale = headingScale.limit(1f, .9f, 1.4f),
            headingWeight = headingWeight.coerceIn(400, 700),
            thinkingSize = thinking,
            thinkingLineHeight = thinkingLineHeight.limit(22f, 15f, 36f).coerceAtLeast(thinking * 1.2f),
            codeSize = codeSize.limit(13f, 10f, 22f),
            tableSize = tableSize.limit(14f, 11f, 22f),
            tableDensity = tableDensity.limit(1f, .65f, 1.4f),
            bodyWeight = if (bodyWeight == 0) 0 else bodyWeight.coerceIn(300, 700),
            bodyLetterSpacing = bodyLetterSpacing.limit(0f, -.5f, 3f),
            thinkingLetterSpacing = thinkingLetterSpacing.limit(0f, -.5f, 3f),
            codeLineHeightScale = codeLineHeightScale.limit(1.55f, 1.2f, 2f),
        )
    }
    companion object {
        fun forPreset(preset: MessageTypographyPreset): MessageTypographySettings = when (preset) {
            MessageTypographyPreset.STANDARD -> MessageTypographySettings()
            MessageTypographyPreset.COMPACT -> MessageTypographySettings(
                preset = preset, bodySize = 15f, lineHeight = 23f, paragraphGapScale = .7f,
                thinkingSize = 13f, thinkingLineHeight = 20f, tableDensity = .8f,
            )
            MessageTypographyPreset.RELAXED -> MessageTypographySettings(
                preset = preset, bodySize = 18f, lineHeight = 30f, paragraphGapScale = 1.3f,
                thinkingSize = 15f, thinkingLineHeight = 25f, codeSize = 14f, tableSize = 16f,
                tableDensity = 1.15f,
            )
            MessageTypographyPreset.CUSTOM -> MessageTypographySettings(preset = preset)
        }
    }
}

@Serializable
enum class MessageTypographyPreset { STANDARD, COMPACT, RELAXED, CUSTOM }

@Serializable
enum class MessageFont { FOLLOW_INTERFACE, SYSTEM, HUMANIST, EDITORIAL, MONOSPACE, SYSTEM_SERIF }
