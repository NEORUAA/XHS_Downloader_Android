package com.neoruaa.xhsdn.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
data class WindowLayoutInfo(
    val isWideScreen: Boolean,
    val contentStartPadding: Dp,
    val contentEndPadding: Dp,
    val topBarContentStartPadding: Dp,
    val topBarContentEndPadding: Dp
)

@Composable
fun rememberWindowLayoutInfo(
    maxContentWidth: Dp = 800.dp
): WindowLayoutInfo {
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .asPaddingValues()
    val screenWidth = configuration.screenWidthDp.dp
    val centeredPadding = ((screenWidth - maxContentWidth) / 2).coerceAtLeast(0.dp)
    val startInset = horizontalInsets.calculateLeftPadding(layoutDirection)
    val endInset = horizontalInsets.calculateRightPadding(layoutDirection)
    val startPadding = maxOf(
        centeredPadding,
        startInset
    )
    val endPadding = maxOf(
        centeredPadding,
        endInset
    )
    val topBarStartPadding = (startPadding - startInset).coerceAtLeast(0.dp)
    val topBarEndPadding = (endPadding - endInset).coerceAtLeast(0.dp)

    return remember(
        configuration.screenWidthDp,
        layoutDirection,
        maxContentWidth,
        startPadding,
        endPadding,
        topBarStartPadding,
        topBarEndPadding
    ) {
        WindowLayoutInfo(
            isWideScreen = configuration.screenWidthDp >= 600,
            contentStartPadding = startPadding,
            contentEndPadding = endPadding,
            topBarContentStartPadding = topBarStartPadding,
            topBarContentEndPadding = topBarEndPadding
        )
    }
}

@Composable
fun AdaptiveTopAppBar(
    title: String,
    isWideScreen: Boolean,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null
) {
    if (isWideScreen) {
        SmallTopAppBar(
            title = title,
            modifier = modifier.miuixTopBarBlur(backdrop),
            color = backdrop.miuixTopBarColor(),
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
            scrollBehavior = scrollBehavior
        )
    } else {
        TopAppBar(
            title = title,
            largeTitle = title,
            modifier = modifier.miuixTopBarBlur(backdrop),
            color = backdrop.miuixTopBarColor(),
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
fun TopAppBarIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            }
        )
    }
}

@Composable
fun ActionIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MiuixTheme.colorScheme.onSecondaryContainer
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp,
        backgroundColor = MiuixTheme.colorScheme.secondaryContainer
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}
