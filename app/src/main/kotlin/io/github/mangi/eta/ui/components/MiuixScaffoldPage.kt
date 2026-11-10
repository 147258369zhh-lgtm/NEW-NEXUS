package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    aboveContent: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)
    var entered by remember { mutableStateOf(false) }
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(EtaMotion.content, easing = FastOutSlowInEasing),
        label = "page-content-alpha",
    )
    val contentOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 10.dp,
        animationSpec = EtaMotion.layoutSpring(),
        label = "page-content-offset",
    )
    val contentScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.992f,
        animationSpec = EtaMotion.layoutSpring(),
        label = "page-content-scale",
    )
    LaunchedEffect(Unit) { entered = true }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                // 页面自带主视觉（如角色详情）时用紧凑标题，避免大标题与主视觉重复。
                if (compactTitle) {
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
        Column(
            Modifier
                .fillMaxSize()
                .etaPageAtmosphere()
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            Box(Modifier.fillMaxWidth().horizontalCutoutPadding()) { aboveContent() }
            WidePageContent(modifier = Modifier.weight(1f)) { sidePadding ->
                // 保留 MiuixTheme 注入的默认越界工厂，让短内容页也能回弹到顶栏采样区。
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = contentAlpha
                            scaleX = contentScale
                            scaleY = contentScale
                            translationY = contentOffset.toPx()
                        }
                        .horizontalCutoutPadding()
                        .captureForTopBar(backdrop)
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
        overlay()
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
    val backdrop = rememberTopBarBackdrop()
    val topBarColor = topBarContainerColor(backdrop)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopBarBackdrop(backdrop) {
                if (compactTitle) {
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
        Box(modifier = Modifier.fillMaxSize().etaPageAtmosphere().captureForTopBar(backdrop)) {
            WidePageContent { sidePadding ->
                content(paddingValues, scrollBehavior, sidePadding)
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
