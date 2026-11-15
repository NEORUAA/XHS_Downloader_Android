// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.neoruaa.xhsdn.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private val LocalMiuixTopBarBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalMiuixTopBarSurfaceAlpha = staticCompositionLocalOf<State<Float>?> { null }

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
    scrollBehavior: ScrollBehavior? = null,
    contentModifier: Modifier = Modifier,
    compact: Boolean = false,
    defaultWindowInsetsPadding: Boolean = true,
    visualContentOffset: (() -> Float)? = null,
    horizontalPadding: Dp? = null
) {
    val fixedHeight = isWideScreen || compact
    val barState = scrollBehavior?.state
    var restoreHeightOffsetAfterMeasure by remember(barState, fixedHeight) {
        mutableStateOf(!fixedHeight && barState?.heightOffsetLimit == 0f)
    }
    val largeBarModifier = if (!fixedHeight && barState != null) {
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val limit = barState.heightOffsetLimit
            // The native large title publishes its measured range before this parent returns.
            if (restoreHeightOffsetAfterMeasure && limit < 0f && limit > -Float.MAX_VALUE) {
                barState.heightOffset = if (barState.contentOffset < 0f) limit else 0f
                restoreHeightOffsetAfterMeasure = false
            }
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    } else Modifier

    if (useMiuixOs4() && fixedHeight) {
        MiuixProgressiveAppBarSurface(
            backdrop = backdrop,
            modifier = modifier,
            scrollBehavior = scrollBehavior,
            visualContentOffset = visualContentOffset
        ) {
            FixedSmallTopAppBar(
                title = title,
                modifier = contentModifier,
                color = Color.Transparent,
                navigationIcon = navigationIcon,
                actions = actions,
                bottomContent = bottomContent,
                scrollBehavior = scrollBehavior,
                defaultWindowInsetsPadding = defaultWindowInsetsPadding,
                compactTitle = compact,
                navigationIconPadding = horizontalPadding ?: GlassTopAppBarDefaults.HorizontalPadding,
                actionIconPadding = horizontalPadding ?: GlassTopAppBarDefaults.HorizontalPadding
            )
        }
    } else if (useMiuixOs4()) {
        GlassTopAppBar(
            title = title,
            modifier = modifier.then(largeBarModifier),
            contentModifier = contentModifier,
            progressiveBlur = true,
            backdrop = backdrop,
            style = GlassDefaults.style(isSystemInDarkTheme()),
            scrollBehavior = scrollBehavior,
            visualContentOffset = visualContentOffset,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent
        )
    } else if (fixedHeight) {
        FixedSmallTopAppBar(
            title = title,
            modifier = modifier.then(contentModifier).miuixTopBarBlur(backdrop),
            color = backdrop.miuixTopBarColor(),
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            compactTitle = compact,
            navigationIconPadding = horizontalPadding ?: TopAppBarDefaults.NavigationIconPadding,
            actionIconPadding = horizontalPadding ?: TopAppBarDefaults.ActionIconPadding
        )
    } else {
        TopAppBar(
            title = title,
            largeTitle = title,
            modifier = modifier.then(largeBarModifier).then(contentModifier).miuixTopBarBlur(backdrop),
            color = backdrop.miuixTopBarColor(),
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
            scrollBehavior = scrollBehavior,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            navigationIconPadding = horizontalPadding ?: TopAppBarDefaults.NavigationIconPadding,
            actionIconPadding = horizontalPadding ?: TopAppBarDefaults.ActionIconPadding
        )
    }
}

/** Adapts the native four-slot layout and places the action slot omitted by this snapshot. */
@Composable
private fun FixedSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    compactTitle: Boolean = false,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding
) {
    val titleFontSize = MiuixTheme.textStyles.title3.fontSize
    val titleAutoSize = remember(compactTitle, titleFontSize) {
        if (compactTitle) TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = titleFontSize) else null
    }
    SideEffect {
        scrollBehavior?.state?.let { state ->
            if (state.heightOffsetLimit != 0f) state.heightOffsetLimit = 0f
            if (state.heightOffset != 0f) state.heightOffset = 0f
        }
    }
    Layout(
        content = {
            Box(Modifier.padding(start = navigationIconPadding)) { navigationIcon() }
            Box(Modifier.padding(horizontal = if (compactTitle) 8.dp else TopAppBarDefaults.TitlePadding)) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    fontSize = titleFontSize,
                    autoSize = titleAutoSize,
                    fontWeight = FontWeight.Medium,
                    overflow = if (compactTitle) TextOverflow.StartEllipsis else TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
            Row(
                modifier = Modifier.padding(end = actionIconPadding),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
            Box { bottomContent() }
        },
        modifier = modifier
            .background(color)
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                } else Modifier
            )
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .pointerInput(Unit) { detectTapGestures { } }
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val navigation = measurables[0].measure(looseConstraints)
        val actionsPlaceable = measurables[2].measure(looseConstraints)
        val availableTitleWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - navigation.width - actionsPlaceable.width).coerceAtLeast(0)
        } else Constraints.Infinity
        val titleMaxWidth = if (availableTitleWidth == Constraints.Infinity) {
            availableTitleWidth
        } else (availableTitleWidth * 0.9f).roundToInt()
        val titlePlaceable = measurables[1].measure(looseConstraints.copy(maxWidth = titleMaxWidth))
        val bottom = measurables[3].measure(looseConstraints)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else {
            maxOf(navigation.width + titlePlaceable.width + actionsPlaceable.width, bottom.width)
                .coerceAtLeast(constraints.minWidth)
        }
        val centerY = TopAppBarDefaults.SmallTopAppBarCenterHeight.roundToPx() / 2
        val titleY = centerY - titlePlaceable.height / 2
        val contentTop = maxOf(TopAppBarDefaults.CollapsedHeight.roundToPx(), titleY + titlePlaceable.height)
        layout(width, contentTop + bottom.height) {
            navigation.placeRelative(0, centerY - navigation.height / 2)
            actionsPlaceable.placeRelative(width - actionsPlaceable.width, centerY - actionsPlaceable.height / 2)
            val leadingWidth = if (layoutDirection == LayoutDirection.Ltr) navigation.width else actionsPlaceable.width
            val trailingWidth = if (layoutDirection == LayoutDirection.Ltr) actionsPlaceable.width else navigation.width
            var titleX = (width - titlePlaceable.width) / 2
            if (titleX < leadingWidth) {
                titleX = leadingWidth
            } else if (titleX + titlePlaceable.width > width - trailingWidth) {
                titleX = width - trailingWidth - titlePlaceable.width
            }
            titlePlaceable.place(titleX, titleY)
            bottom.placeRelative(0, contentTop)
        }
    }
}

/** Buttons sample the rendered blur band independently of the raw scroll-content texture. */
@Composable
private fun MiuixProgressiveAppBarSurface(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    scrollBehavior: ScrollBehavior? = null,
    visualContentOffset: (() -> Float)? = null,
    content: @Composable () -> Unit
) {
    val currentOffset by rememberUpdatedState(visualContentOffset)
    val isContentScrolled by remember(scrollBehavior) {
        derivedStateOf {
            val offset = currentOffset?.invoke() ?: scrollBehavior?.state?.contentOffset
            offset?.let { it < 0f } ?: true
        }
    }
    val collapseRamp = GlassTopAppBarDefaults.collapseRamp(scrollBehavior, visualContentOffset)
    val transition = updateTransition(isContentScrolled, label = "compactGlassTopBarFloat")
    val maskProgress = transition.animateFloat(
        transitionSpec = { GlassMotion.topBarMask() },
        label = "compactGlassTopBarMask"
    ) { if (it) 1f else 0f }
    val scrolledMaterialProgress = transition.animateFloat(
        transitionSpec = { GlassMotion.topBarButtonFloat() },
        label = "compactGlassTopBarMaterial"
    ) { if (it) 1f else 0f }
    val materialProgress = remember(scrolledMaterialProgress, scrollBehavior, collapseRamp) {
        derivedStateOf {
            val elasticProgress = if (scrollBehavior != null && scrollBehavior.state.overscrollOffset != 0f) {
                collapseRamp.value
            } else 0f
            maxOf(scrolledMaterialProgress.value, elasticProgress)
        }
    }
    var backgroundCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val renderedBackdrop = rememberLayerBackdrop {
        val coordinates = backgroundCoordinates?.takeIf { it.isAttached }
        backdrop?.let { scene ->
            with(scene) { drawBackdrop(this@rememberLayerBackdrop, coordinates) }
        }
        drawContent()
    }
    val blurColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = 0.3f)))
    )
    Box(modifier = modifier) {
        if (backdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layout { measurable, constraints ->
                        val bandHeight = ((constraints.maxHeight + 20.dp.toPx()) * 1.2f).toInt()
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = bandHeight, maxHeight = bandHeight)
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
                    }
                    .onGloballyPositioned { backgroundCoordinates = it }
                    .layerBackdrop(renderedBackdrop)
                    .graphicsLayer {
                        val offset = currentOffset?.invoke() ?: scrollBehavior?.state?.visualContentOffset
                        alpha = offset?.let {
                            (-it / GlassTopAppBarDefaults.ProgressiveBlurRampDistance.toPx()).coerceIn(0f, 1f)
                        } ?: maskProgress.value
                    }
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 0.5f),
                        blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value,
                        colors = blurColors
                    )
            )
        }
        CompositionLocalProvider(
            LocalMiuixTopBarBackdrop provides renderedBackdrop.takeIf { backdrop != null },
            LocalMiuixTopBarSurfaceAlpha provides materialProgress
        ) {
            content()
        }
    }
}

@Composable
fun TopAppBarIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconModifier: Modifier = Modifier
) {
    val useOs4 = useMiuixOs4()
    if (useOs4) {
        val renderedBackdrop = LocalMiuixTopBarBackdrop.current
        // This snapshot has no enabled parameter. Block input before its native clickable.
        val disabledModifier = if (enabled) Modifier else Modifier
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                role = Role.Button
                disabled()
                this.onClick { false }
            }
            .focusProperties { canFocus = false }
            .onPreviewKeyEvent { true }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
        GlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = modifier.then(disabledModifier),
            inheritTopBarMaterial = renderedBackdrop == null,
            backdrop = renderedBackdrop,
            surfaceAlpha = LocalMiuixTopBarSurfaceAlpha.current?.value ?: 1f,
            style = GlassDefaults.style(isSystemInDarkTheme())
        ) {
            Icon(
                imageVector = if (imageVector == MiuixIcons.Back) MiuixIcons.Glass.ChevronBackward else imageVector,
                contentDescription = contentDescription,
                modifier = iconModifier.size(24.dp),
                tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface
            )
        }
        return
    }
    IconButton(
        onClick = onClick,
        modifier = if (enabled) modifier else modifier.clearAndSetSemantics {
            this.contentDescription = contentDescription
            role = Role.Button
            disabled()
            this.onClick { false }
        },
        enabled = enabled,
        minHeight = 35.dp,
        minWidth = 35.dp
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = iconModifier,
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
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
