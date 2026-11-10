package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.etaGlassSurface
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 分组标题 + 卡片的标准组合，Provider 相关页面统一使用 Miuix 12.dp 外边距。 */
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .then(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)))
                        .background(
                            Brush.verticalGradient(
                                listOf(MiuixTheme.colorScheme.primary, MiuixTheme.colorScheme.secondary),
                            ),
                        ),
                )
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EtaUiMetrics.pageHorizontal)
                .padding(bottom = EtaUiMetrics.groupGap)
                .etaGlassSurface(),
        ) {
            content()
        }
    }
}

/** 厂商品牌原色图标；资源已经包含适合圆形裁剪的背景与安全区。 */
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .padding(end = 4.dp)
            .size(32.dp)
            .shadow(3.dp, CircleShape, ambientColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .border(0.65.dp, Color.White.copy(alpha = 0.16f), CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** 已知厂商使用品牌图标，未知来源继续按协议类型使用通用图标。 */
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    val icon = when (provider) {
        is CustomProviderSetting -> Icons.Rounded.Dns
        else -> Icons.Rounded.Language
    }
    EtaIconTile(
        icon = icon,
        modifier = modifier.padding(end = 4.dp),
        tone = EtaIconTone.MODEL,
    )
}

internal enum class TagChipTone { Normal, Emphasized }

/** 小胶囊标签，用于能力标签与状态标记。 */
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    val weight: FontWeight
    val borderColor: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MiuixTheme.colorScheme.secondaryContainer
            foreground = MiuixTheme.colorScheme.onSurfaceVariantSummary
            weight = FontWeight.Normal
            borderColor = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.34f)
        }
        TagChipTone.Emphasized -> {
            background = MiuixTheme.colorScheme.tertiaryContainer
            foreground = MiuixTheme.colorScheme.onTertiaryContainer
            weight = FontWeight.Medium
            borderColor = MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 16.sp),
        fontWeight = weight,
        color = foreground,
        modifier = Modifier
            .squircleBorder(width = 0.65.dp, color = borderColor, cornerRadius = 8.dp)
            .squircleSurface(color = background, cornerRadius = 8.dp)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}
