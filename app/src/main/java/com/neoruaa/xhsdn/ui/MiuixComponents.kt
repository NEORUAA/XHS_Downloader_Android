package com.neoruaa.xhsdn.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.isRenderEffectSupported
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class MiuixComponentScope(
    val useOs4: Boolean = false,
    val contentBackdrop: LayerBackdrop? = null,
    val pageBackdrop: LayerBackdrop? = null,
    val popupInRootScaffold: Boolean = true
)

internal val LocalMiuixComponentScope = staticCompositionLocalOf { MiuixComponentScope() }

@Composable
fun useMiuixOs4(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isRenderEffectSupported()

/** Keeps popup rendering outside the page and scroll-content recordings. */
@Composable
fun MiuixComponentHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val useOs4 = useMiuixOs4()
    val contentBackdrop = rememberMiuixBackdrop(useOs4)
    val pageBackdrop = rememberMiuixBackdrop(useOs4)
    val scope = remember(useOs4, contentBackdrop, pageBackdrop) {
        MiuixComponentScope(useOs4, contentBackdrop, pageBackdrop)
    }
    CompositionLocalProvider(LocalMiuixComponentScope provides scope) {
        Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0)) {
            content()
        }
    }
}

@Composable
internal fun rememberMiuixBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun MiuixSpinnerPreference(
    title: String,
    items: List<DropdownItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null
) {
    val scope = LocalMiuixComponentScope.current
    val plainOptions = items.all {
        it.enabled && it.icon == null && it.summary == null && it.children.isNullOrEmpty()
    }
    if (scope.useOs4 && plainOptions) {
        GlassDropdownPreference(
            items = items.map { it.text },
            selectedIndex = selectedIndex,
            title = title,
            onSelectedIndexChange = { index ->
                onSelectedIndexChange(index)
                items[index].onClick?.invoke()
            },
            modifier = modifier,
            summary = summary,
            backdrop = scope.pageBackdrop,
            visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(isSystemInDarkTheme())),
            renderInRootScaffold = scope.popupInRootScaffold
        )
    } else {
        WindowSpinnerPreference(
            items = items,
            selectedIndex = selectedIndex,
            title = title,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = modifier,
            summary = summary
        )
    }
}
