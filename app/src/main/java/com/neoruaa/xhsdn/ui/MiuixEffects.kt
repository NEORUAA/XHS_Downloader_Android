package com.neoruaa.xhsdn.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun rememberMiuixTopBarBackdrop(): LayerBackdrop? {
    if (!isRenderEffectSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun Modifier.miuixTopBarBlur(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            blurRadius = 25f,
            colors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)
                    )
                )
            )
        )
    )
}

@Composable
fun LayerBackdrop?.miuixTopBarColor(): Color =
    if (this == null) MiuixTheme.colorScheme.surface else Color.Transparent

fun Modifier.miuixBackdropSource(backdrop: LayerBackdrop?): Modifier =
    backdrop?.let { then(Modifier.layerBackdrop(it)) } ?: this

fun Modifier.miuixVerticalScrollEffects(): Modifier =
    scrollEndHaptic().overScrollVertical()
