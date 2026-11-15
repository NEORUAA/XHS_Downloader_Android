package com.neoruaa.xhsdn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassBottomSheetSurface
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults
import top.yukonga.miuix.kmp.layout.BottomSheetSurfaceScope
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.drawEdgeStroke
import top.yukonga.miuix.kmp.material.drawGlassMask
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

private val LocalOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun MiuixDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    onDismissFinished: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = LocalMiuixComponentScope.current
    if (scope.useOs4) {
        val backdrop = LocalOverlayBackdrop.current ?: scope.pageBackdrop
        val dialogBackdrop = rememberLayerBackdrop()
        val nestedBackdrop = rememberOverlayBackdrop(backdrop, dialogBackdrop)
        GlassDialog(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = modifier.layerBackdrop(dialogBackdrop),
            backdrop = backdrop,
            title = title,
            summary = summary,
            renderInRootScaffold = true,
            onDismissFinished = onDismissFinished
        ) {
            CompositionLocalProvider(LocalOverlayBackdrop provides nestedBackdrop) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    } else {
        WindowDialog(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            title = title,
            summary = summary,
            onDismissFinished = onDismissFinished
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun MiuixDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val textStyle = MiuixTheme.textStyles.button
    val autoSize = remember(textStyle.fontSize) {
        TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = textStyle.fontSize)
    }
    val buttonContent: @Composable RowScope.() -> Unit = {
        Text(
            text = text,
            style = textStyle,
            autoSize = autoSize,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (LocalMiuixComponentScope.current.useOs4) {
        GlassDialogButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            role = if (primary) GlassDialogButtonRole.Primary else GlassDialogButtonRole.Normal,
            content = buttonContent
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
            content = buttonContent
        )
    }
}

@Composable
fun MiuixBottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    allowDismiss: Boolean = true,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    onDismissFinished: () -> Unit = {},
    visualContentOffset: (() -> Float)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val scope = LocalMiuixComponentScope.current
    val cornerRadius = BottomSheetDefaults.insideMargin.width + GlassTopAppBarDefaults.ButtonSize / 2
    var sheetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val panelModifier = modifier.onGloballyPositioned { sheetCoordinates = it }
    if (scope.useOs4) {
        val backdrop = LocalOverlayBackdrop.current ?: scope.pageBackdrop
        val sheetBackdrop = rememberLayerBackdrop()
        val nestedBackdrop = rememberOverlayBackdrop(backdrop, sheetBackdrop)
        var surfaceScope by remember { mutableStateOf<BottomSheetSurfaceScope?>(null) }
        val nativeSurface = glassBottomSheetSurface(backdrop)
        val contentSurface = glassBottomSheetSurface(backdrop, stroke = null)
        val shape = remember(cornerRadius) {
            GlassShape(topStart = cornerRadius, topEnd = cornerRadius, bottomEnd = 0.dp, bottomStart = 0.dp)
        }
        val stroke = GlassBottomSheetDefaults.stroke()
        // Keep native sheet mechanics, with the glass base inside the header's recording source.
        OverlayBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = panelModifier.layerBackdrop(sheetBackdrop),
            title = null,
            cornerRadius = cornerRadius,
            allowDismiss = allowDismiss,
            insideMargin = DpSize(0.dp, 0.dp),
            backgroundColor = GlassBottomSheetDefaults.containerColor(),
            dimColor = GlassBottomSheetDefaults.ScrimColor,
            surface = {
                if (isOverscroll) {
                    nativeSurface()
                } else {
                    val currentScope = this
                    SideEffect { surfaceScope = currentScope }
                    Modifier.graphicsLayer {
                        this.shape = shape
                        clip = true
                        compositingStrategy = CompositingStrategy.Offscreen
                    }.drawWithContent {
                        drawContent()
                        drawEdgeStroke(shape, stroke)
                        drawGlassMask(shape)
                    }
                }
            },
            renderInRootScaffold = true,
            onDismissFinished = onDismissFinished
        ) {
            CompositionLocalProvider(LocalOverlayBackdrop provides nestedBackdrop) {
                MiuixSheetContent(
                    title = title,
                    startAction = startAction,
                    endAction = endAction,
                    surface = {
                        surfaceScope?.let { with(it) { contentSurface() } }
                            ?: Modifier.background(GlassBottomSheetDefaults.containerColor())
                    },
                    cornerRadius = cornerRadius,
                    sheetCoordinates = sheetCoordinates,
                    visualContentOffset = visualContentOffset,
                    content = content
                )
            }
        }
    } else {
        WindowBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = panelModifier,
            title = null,
            cornerRadius = cornerRadius,
            allowDismiss = allowDismiss,
            insideMargin = DpSize(0.dp, 0.dp),
            backgroundColor = MiuixTheme.colorScheme.surface,
            onDismissFinished = onDismissFinished
        ) {
            MiuixSheetContent(
                title = title,
                startAction = startAction,
                endAction = endAction,
                surface = { Modifier.background(MiuixTheme.colorScheme.surface) },
                cornerRadius = cornerRadius,
                sheetCoordinates = sheetCoordinates,
                visualContentOffset = visualContentOffset,
                content = content
            )
        }
    }
}

/** Measures the fixed header first, then lets the scroll viewport extend underneath it. */
@Composable
private fun MiuixSheetContent(
    title: String?,
    startAction: @Composable (() -> Unit)?,
    endAction: @Composable (() -> Unit)?,
    surface: @Composable () -> Modifier,
    cornerRadius: Dp,
    sheetCoordinates: LayoutCoordinates?,
    visualContentOffset: (() -> Float)?,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val scope = LocalMiuixComponentScope.current
    val windowLayout = rememberWindowLayoutInfo()
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
    var reservedChromeHeight by remember { mutableIntStateOf(0) }
    val contentBackdrop = if (scope.useOs4) rememberLayerBackdrop() else null
    val sheetScope = remember(scope, contentBackdrop) { scope.copy(contentBackdrop = contentBackdrop) }

    CompositionLocalProvider(LocalMiuixComponentScope provides sheetScope) {
        SubcomposeLayout(
            modifier = modifier.fillMaxWidth()
                // Render the body base beneath the native, independently animated drag handle.
                .zIndex(-1f)
                .consumeWindowInsets(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .onGloballyPositioned { coordinates ->
                    if (sheetCoordinates?.isAttached == true) {
                        reservedChromeHeight = sheetCoordinates.localPositionOf(coordinates, Offset.Zero)
                            .y.toInt().coerceAtLeast(0)
                    }
                }
        ) { constraints ->
            // Reuse native chrome space while keeping the full scroll viewport and hit targets.
            val maxHeight = if (constraints.hasBoundedHeight) {
                constraints.maxHeight + reservedChromeHeight
            } else constraints.maxHeight
            val childConstraints = constraints.copy(minHeight = 0, maxHeight = maxHeight)
            val header = subcompose("sheet_header") {
                AdaptiveTopAppBar(
                    title = title.orEmpty(),
                    isWideScreen = windowLayout.isWideScreen,
                    compact = true,
                    defaultWindowInsetsPadding = false,
                    backdrop = contentBackdrop,
                    visualContentOffset = visualContentOffset,
                    horizontalPadding = BottomSheetDefaults.insideMargin.width,
                    contentModifier = Modifier.padding(
                        top = cornerRadius - TopAppBarDefaults.SmallTopAppBarCenterHeight / 2
                    ),
                    navigationIcon = { startAction?.invoke() },
                    actions = { endAction?.invoke() }
                )
            }.single().measure(childConstraints)
            val contentPadding = PaddingValues(
                start = BottomSheetDefaults.insideMargin.width,
                top = header.height.toDp(),
                end = BottomSheetDefaults.insideMargin.width,
                bottom = bottomPadding
            )
            val body = subcompose("sheet_body") {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .miuixBackdropSource(contentBackdrop)
                ) {
                    Box(Modifier.matchParentSize().then(surface()))
                    content(contentPadding)
                }
            }.single().measure(childConstraints)
            val height = (maxOf(header.height, body.height) - reservedChromeHeight).coerceAtLeast(0)
            layout(constraints.maxWidth, height) {
                body.placeRelative(0, -reservedChromeHeight)
                header.placeRelative(0, -reservedChromeHeight)
            }
        }
    }
}

/** A child overlay samples its parent panel, with its own root popup kept outside the recording. */
@Composable
private fun rememberOverlayBackdrop(page: Backdrop?, panel: LayerBackdrop): Backdrop =
    remember(page, panel) {
        object : Backdrop {
            override val isCoordinatesDependent = true
            override val offsetResidualX: Float get() = page?.offsetResidualX ?: 0f
            override val offsetResidualY: Float get() = page?.offsetResidualY ?: 0f

            override fun DrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?,
                downscaleFactor: Float
            ) {
                page?.let { with(it) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) } }
                with(panel) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
        }
    }
