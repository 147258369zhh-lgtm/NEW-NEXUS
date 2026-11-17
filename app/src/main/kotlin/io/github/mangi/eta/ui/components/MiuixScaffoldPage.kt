package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Eta 二级列表页的统一骨架：手机折叠大标题、宽屏固定小标题、顶栏毛玻璃、横屏安全区、
 * 宽屏内容居中、滚动边界触感与越界回弹均由此处统一提供。
 */
@Composable
fun MiuixScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    compactTitle: Boolean = false,
    staticTopBarBackdrop: Boolean = false,
    aboveContent: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val glass = LocalEtaDesignTokens.current.skin.isGlass && !LocalNexusSettingsVisuals.current
    val staticBackdrop = glass || staticTopBarBackdrop
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                // 页面自带主视觉（如角色详情）时用紧凑标题，避免大标题与主视觉重复。
                if (compactTitle || io.github.mangi.eta.ui.app.LocalNexusPresentationProfile.current.id in setOf("omni", "aurora", "frost_glass", "liquid_glass")) {
                    SmallTopAppBar(
                        title = title,
                        color = topBarColor,
                        navigationIcon = { MiuixBackButton(onClick = onBack) },
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    AdaptiveTopAppBar(
                        title = title,
                        color = topBarColor,
                        navigationIcon = { MiuixBackButton(onClick = onBack) },
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            // The pinned navigation lies above the list, so it needs a source covering that area.
            // Capture only the static atmosphere for glass: sampling a parent containing the lens
            // would create a feedback loop. Legacy skins keep their existing scrolling source.
            if (staticBackdrop) Box(Modifier.matchParentSize().captureForTopBar(backdrop).etaPageAtmosphere())
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (glass) Modifier else Modifier.etaPageAtmosphere())
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
                Box(Modifier.fillMaxWidth().horizontalCutoutPadding()) {
                    CompositionLocalProvider(LocalNexusBackdrop provides backdrop) { aboveContent() }
                }
                CompositionLocalProvider(LocalNexusBackdrop provides backdrop) {
                WidePageContent(modifier = Modifier.weight(1f)) { sidePadding ->
                    // 保留 MiuixTheme 注入的默认越界工厂，让短内容页也能回弹到顶栏采样区。
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalCutoutPadding()
                            .captureForTopBar(if (staticBackdrop) null else backdrop)
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(scrollBehavior.nestedScrollConnection),
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                        ),
                    ) {
                        content()
                        item(key = "bottom_spacer") {
                            MiuixPageBottomSpacer()
                        }
                    }
                }
                }
            }
        }
        CompositionLocalProvider(LocalNexusBackdrop provides backdrop) { overlay() }
    }
}

/**
 * 自定义内容二级页的低层骨架。调用方负责把顶部 padding、横向安全区与 nested scroll
 * 接入自己的内容；[sidePadding] 用于在宽屏限制实际内容宽度，滚动容器本身仍应保持全宽。
 */
@Composable
fun MiuixScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    compactTitle: Boolean = false,
    content: @Composable (
        paddingValues: PaddingValues,
        scrollBehavior: ScrollBehavior,
        sidePadding: Dp,
    ) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val glass = LocalEtaDesignTokens.current.skin.isGlass && !LocalNexusSettingsVisuals.current
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                if (compactTitle || io.github.mangi.eta.ui.app.LocalNexusPresentationProfile.current.id in setOf("omni", "aurora", "frost_glass", "liquid_glass")) {
                    SmallTopAppBar(
                        title = title,
                        color = topBarColor,
                        navigationIcon = { MiuixBackButton(onClick = onBack) },
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    AdaptiveTopAppBar(
                        title = title,
                        color = topBarColor,
                        navigationIcon = { MiuixBackButton(onClick = onBack) },
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize()) {
            if (glass) Box(Modifier.matchParentSize().captureForTopBar(backdrop).etaPageAtmosphere())
            Box(Modifier.fillMaxSize().then(
                if (glass) Modifier else Modifier.etaPageAtmosphere().captureForTopBar(backdrop),
            )) {
                CompositionLocalProvider(LocalNexusBackdrop provides backdrop) {
                    WidePageContent { sidePadding ->
                        content(paddingValues, scrollBehavior, sidePadding)
                    }
                }
            }
        }
    }
}

@Composable
fun MiuixPageBottomSpacer(modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .height(24.dp)
            .navigationBarsPadding(),
    )
}
