package io.github.mangi.eta.ui.components

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.R

/**
 * 随包的等宽字体。部分系统会把 [FontFamily.Monospace] 替换成比例字体，终端列宽与对齐随之失效；
 * 终端界面统一使用这份字体，CJK 等缺失字形由系统字体回退，网格对齐由按单元格绘制保证。
 * 采用无连字版本，避免 `->`、`!=` 在命令与代码里被合成单个字形。
 */
internal val EtaMonospace = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** 经典皮肤的“清润人文”预设；CJK 字形由系统回退，英文与数字保持轻人文节奏。 */
internal val NexaHumanist = FontFamily(
    Font(R.font.manrope_variable, FontWeight.Normal),
    Font(R.font.manrope_variable, FontWeight.Medium),
    Font(R.font.manrope_variable, FontWeight.SemiBold),
    Font(R.font.manrope_variable, FontWeight.Bold),
)

/** 书刊标题预设仅用于标题与阅读的开场层，正文保持系统无衬线。 */
internal val NexaEditorial = FontFamily(
    Font(R.font.newsreader_variable, FontWeight.Normal),
    Font(R.font.newsreader_variable, FontWeight.Medium),
    Font(R.font.newsreader_variable, FontWeight.SemiBold),
    Font(R.font.newsreader_variable, FontWeight.Bold),
)
